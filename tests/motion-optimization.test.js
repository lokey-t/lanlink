import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {mkdtemp,rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {encodeMotion,decodeMotion} from '../src/motion-wire.js';
import {MotionSender,MotionReceiver} from '../src/quic-motion.js';
import {InputQueue} from '../src/input-queue.js';
import {QuicManager} from '../src/quic.js';
import {LinkManager} from '../src/links.js';
import {Service} from '../src/service.js';
import {quicPath} from '../src/quic-native.js';
const tick=()=>new Promise(resolve=>setImmediate(resolve));
async function until(check){const end=performance.now()+3000;while(!check()){if(performance.now()>end)throw Error('Timed out');await new Promise(r=>setTimeout(r,2));}}

test('binary motion preserves fractional raw distance, cumulative loss recovery and input dependency',()=>{
  const sender=new MotionSender(()=>12345),receiver=new MotionReceiver();sender.epoch='00112233-4455-6677-8899-aabbccddeeff';
  sender.move({kind:'move',x:.25,y:1,dx:1.25,dy:300});
  const snapshot={...sender.move({kind:'move',x:.25,y:1,dx:-.5,dy:80}),afterInput:7};
  const bytes=encodeMotion(snapshot);assert.equal(bytes.length,104);
  assert.equal(bytes.subarray(8,24).toString('hex'),'00112233445566778899aabbccddeeff');
  assert.deepEqual(decodeMotion(bytes),{...snapshot,ageMs:0});
  assert.deepEqual(receiver.accept(decodeMotion(bytes)),[{kind:'move',x:.25,y:1,dx:.75,dy:380}]);
  assert.deepEqual(receiver.accept(decodeMotion(bytes)),[]);
  assert.throws(()=>decodeMotion(bytes.subarray(1)),/record/);
  const corrupt=Buffer.from(bytes);corrupt[1]=128;assert.throws(()=>decodeMotion(corrupt),/record/);
  corrupt.set(Buffer.alloc(8,255),24);assert.throws(()=>decodeMotion(corrupt));
});

test('movement overlaps a click reply but the next release and session boundary remain ordered',async()=>{
  let release;const gate=new Promise(r=>release=r),sent=[];
  const q=new InputQueue(async(method,args)=>{sent.push(args.kind||method);if(args.down===true)await gate;return {};},{overlap:method=>method==='input'});
  const press=q.request('input',{kind:'button',down:true});
  await q.request('input',{kind:'move',x:.4,y:.2});
  assert.deepEqual(sent,['button','move']);
  const up=q.request('input',{kind:'button',down:false}),end=q.request('release',{});
  await tick();assert.deepEqual(sent,['button','move']);
  release();await Promise.all([press,up,end]);assert.deepEqual(sent,['button','move','button','release']);
});

test('a route change drains the previous click before sending motion on the new transport',async()=>{
  let release,modern=true;const gate=new Promise(r=>release=r),sent=[];
  const q=new InputQueue(async(method,args)=>{sent.push(args.kind);if(args.kind==='button')await gate;return {};},{overlap:()=>modern});
  const press=q.request('input',{kind:'button'});modern=false;
  const move=q.request('input',{kind:'move',x:1,y:0});await tick();assert.deepEqual(sent,['button']);
  release();await Promise.all([press,move]);assert.deepEqual(sent,['button','move']);
});

test('a motion arriving ahead of its press is held; old epochs and impossible dependencies are discarded',async()=>{
  const calls=[],p={remote:{id:'peer'},quicConn:1,inputRx:0,quicRx:new MotionReceiver()};
  const service={config:{id:'local'},peers:new Map([['peer',p]]),group:{excluded:()=>false},async handle(_p,_method,a){calls.push(a);}};
  const manager=new QuicManager(service);manager.enabled=true;const sender=new MotionSender();p.quicRx.epoch=sender.epoch;
  const m={...sender.move({kind:'relative',dx:4,dy:2}),afterInput:1};
  await manager.applyMotion(p,m);assert.deepEqual(calls,[]);assert.equal(p.heldMotion.seq,1);
  p.inputRx=1;await manager.applyMotion(p,p.heldMotion);assert.deepEqual(calls,[{kind:'relative',dx:4,dy:2}]);
  await manager.applyMotion(p,{...m,epoch:'f'.repeat(36),seq:2});
  await manager.applyMotion(p,{...m,afterInput:100,seq:2});assert.equal(calls.length,1);
});

test('loss recovery crosses the native process once per batch and still checks permission',async()=>{
  const calls=[],context={native:{capabilities:{inputBatch:1},async call(method,args){calls.push({method,args});}},requireOwner(){if(this.denied)throw Error('denied');},edge:{suppress(){}},cancelEdgeFeedback(){}};
  const inputs=[{kind:'relative',dx:1000,dy:0},{kind:'relative',dx:500,dy:.25}];
  await Service.prototype.motionInputs.call(context,{},inputs);assert.deepEqual(calls,[{method:'inputBatch',args:{inputs}}]);
  context.denied=true;await assert.rejects(Service.prototype.motionInputs.call(context,{},inputs),/denied/);assert.equal(calls.length,1);
});

test('native binary mouse moves while the reliable press reply is held, with ordered release',{skip:!quicPath(),timeout:10000},async t=>{
  const dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-motion-v2-')),hold=setInterval(()=>{},1000),calls=[];
  const service=id=>Object.assign(new EventEmitter(),{dir:path.join(dir,id),config:{id},options:{host:'127.0.0.1'},peers:new Map(),group:{excluded:()=>false},log(){},async returnLocal(){},async handle(_p,method,args){if(method==='input')calls.push(args);return {};}});
  const a=service('a'),b=service('b');a.quic=new QuicManager(a);b.quic=new QuicManager(b);
  t.after(async()=>{a.quic.close();b.quic.close();clearInterval(hold);await rm(dir,{recursive:true,force:true});});
  await a.quic.start();await b.quic.start();assert.ok(a.quic.native.binaryMotion,'rebuilt binary ABI must be enabled');
  const peer=id=>Object.assign(new EventEmitter(),{remote:{id},info:{capabilities:{quic:1,motionV2:2}},socket:{remoteAddress:'127.0.0.1'},send(){}});
  const pa=peer('b'),pb=peer('a');a.peers.set('b',pa);b.peers.set('a',pb);
  pa.request=()=>b.quic.offer(pb);pb.request=()=>a.quic.offer(pa);new LinkManager(a).wrap(pa);new LinkManager(b).wrap(pb);
  await a.quic.connect(pa);await until(()=>pa.quicConn&&pb.quicConn);await pa.request('acquire',{});
  await pa.request('input',{kind:'move',x:.1,y:.1});
  let release,held=false;const gate=new Promise(r=>release=r),original=b.quic.native.call.bind(b.quic.native);
  b.quic.native.call=async(op,args,...rest)=>{if(op==='reply'&&!held){held=true;await gate;}return original(op,args,...rest);};
  t.after(()=>release());
  const press=pa.request('input',{kind:'button',button:0,down:true});await until(()=>held);
  let confirmed=false;press.then(()=>confirmed=true);
  await pa.request('input',{kind:'move',x:.8,y:.7});await until(()=>calls.some(a=>a.kind==='move'&&a.x===.8));
  assert.equal(confirmed,false,'movement must not wait for the click response');
  const up=pa.request('input',{kind:'button',button:0,down:false});await tick();assert.equal(calls.some(a=>a.down===false),false);
  release();await Promise.all([press,up]);
  assert.deepEqual(calls.filter(a=>a.kind==='button').map(a=>a.down),[true,false]);
  const moveIndex=calls.findIndex(a=>a.kind==='move'&&a.x===.8),pressIndex=calls.findIndex(a=>a.down===true),upIndex=calls.findIndex(a=>a.down===false);
  assert.ok(pressIndex<moveIndex&&moveIndex<upIndex);
});
