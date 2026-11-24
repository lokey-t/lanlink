import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,rm,readFile} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {mouseDefaults,mouseDevices,mouseRecords,mouseExchange,saveMouseFeel,receiveMouseFeel,applyLocalMouse,validateMouseSettings,checkMousePermission} from '../src/mouse-feel.js';
import {Service} from '../src/service.js';
import {EventEmitter} from 'node:events';
import {randomUUID} from 'node:crypto';
import {newToken} from '../src/identity.js';

async function fixture(t,id='a'){
  const dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-mouse-'));t.after(()=>rm(dir,{recursive:true,force:true}));
  const calls=[],sent=[];
  const members={a:{id:'a',name:'Windows',platform:'win32'},b:{id:'b',name:'Mac',platform:'darwin'},c:{id:'c',name:'Offline',platform:'darwin'},d:{id:'d',name:'Phone',platform:'android'}};
  const s={dir,config:{id,group:{id:'group',members},masterSelection:{group:'group',id:'c'}},peers:new Map(),native:{capabilities:{mouseFeel:1,input:true},call:async(...args)=>calls.push(args)},configure:async f=>f(),emit:()=>{},log:()=>{},currentPeer:p=>!p.closed};
  s.group={data:s.config.group,own:members[id],excluded:()=>false};
  s.peers.set(id==='a'?'b':'a',{remote:{id:id==='a'?'b':'a'},info:{...members[id==='a'?'b':'a'],capabilities:{mouseFeel:1,input:false}},send:m=>sent.push(m)});
  return {s,calls,sent};
}
test('master first even offline, then online, then offline; includes unsupported devices',async t=>{
  const {s}=await fixture(t);const devices=mouseDevices(s);
  assert.deepEqual(devices.map(d=>d.id),['c','b','a','d']);assert.equal(devices[1].needsPermission,true);assert.equal(devices[3].supported,false);
});
test('independent device edits persist and propagate without changing another device',async t=>{
  const {s,calls,sent}=await fixture(t);const settings={...mouseDefaults,sensitivity:1.8,reverse:true};
  await saveMouseFeel(s,[{id:'b',settings}]);
  assert.deepEqual(mouseRecords(s).b.settings,settings);assert.equal(mouseRecords(s).a,undefined);
  assert.deepEqual(calls.at(-1),['mouse-feel',mouseDefaults]);assert.equal(sent.at(-1).type,'mouse-feel');
  const config=JSON.parse(await readFile(path.join(s.dir,'config.json'),'utf8'));assert.deepEqual(config.mouseFeel.devices.b.settings,settings);
  const target=await fixture(t,'b');await receiveMouseFeel(target.s,mouseExchange(s));assert.deepEqual(target.calls.at(-1),['mouse-feel',settings]);
  assert.equal(target.sent.length,1);await receiveMouseFeel(target.s,mouseExchange(s));assert.equal(target.sent.length,1);
});
test('offline edits converge after reconnect; deterministic revision/origin conflict resolution',async t=>{
  const {s}=await fixture(t);await saveMouseFeel(s,[{id:'c',settings:{...mouseDefaults,disableAcceleration:true}}]);
  const other=await fixture(t);await receiveMouseFeel(other.s,mouseExchange(s));assert.equal(mouseRecords(other.s).c.settings.disableAcceleration,true);
  const r=structuredClone(mouseExchange(s));r.devices.c.origin='b';r.devices.c.settings.reverse=true;await receiveMouseFeel(s,r);assert.equal(mouseRecords(s).c.settings.reverse,true);
  r.devices.c.origin='a';r.devices.c.settings.reverse=false;await receiveMouseFeel(s,r);assert.equal(mouseRecords(s).c.settings.reverse,true);
});
test('invalid batch is atomic; mobile, nonmember, invalid values rejected',async t=>{
  const {s,calls}=await fixture(t);
  for(const edit of [{id:'x',settings:mouseDefaults},{id:'d',settings:mouseDefaults},{id:'a',settings:{...mouseDefaults,disableAcceleration:true}},{id:'b',settings:{...mouseDefaults,sensitivity:Infinity}}]){
    await assert.rejects(saveMouseFeel(s,[{id:'c',settings:mouseDefaults},edit]));assert.equal(s.config.mouseFeel,undefined);
  }
  assert.equal(calls.length,0);for(const sensitivity of [0,3.1,NaN,'1'])assert.throws(()=>validateMouseSettings({...mouseDefaults,sensitivity}));
});
test('ignore wrong group, unknown origin, future versions; reset native on group change',async t=>{
  const {s,calls}=await fixture(t);await saveMouseFeel(s,[{id:'a',settings:{...mouseDefaults,sensitivity:2}}]);
  const invalid=structuredClone(mouseExchange(s));invalid.devices.a.settings.sensitivity=3;invalid.devices.a.revision=Date.now()+86400001;
  await receiveMouseFeel(s,invalid);assert.equal(mouseRecords(s).a.settings.sensitivity,2);
  invalid.devices.a.revision=Date.now()+100;invalid.devices.a.origin='stranger';await receiveMouseFeel(s,invalid);assert.equal(mouseRecords(s).a.settings.sensitivity,2);
  s.group.data.id='new';await applyLocalMouse(s);assert.deepEqual(mouseRecords(s),{});assert.deepEqual(calls.at(-1),['mouse-feel',mouseDefaults]);
});

test('real authenticated devices sync edits in either direction and replay offline edits after reconnect',{timeout:15000},async t=>{
  const dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-mouse-pair-')),token=newToken(),services=[],calls=[[],[]];
  t.after(async()=>{await Promise.all(services.map(s=>s.close()));await rm(dir,{recursive:true,force:true});});
  for(let i=0;i<2;i++){
    const caps={mouseFeel:1,input:true,width:1920,height:1080};
    const native=Object.assign(new EventEmitter(),{testCapabilities:caps,capabilities:caps,start:async()=>{},call:async(method,args)=>{calls[i].push({method,args});return method==='capabilities'?caps:{};},close:()=>{}});
    const s=new Service({id:randomUUID(),name:'Mouse '+i,token,master:i===0,permissions:{input:true},edges:{}},path.join(dir,String(i)),native,{host:'127.0.0.1',port:0,noDiscovery:true,noQuic:true});
    services.push(s);await s.start();
  }
  const [a,b]=services;
  const until=async check=>{for(let i=0;i<300;i++){if(check())return;await new Promise(r=>setTimeout(r,15));}throw Error('mouse sync timed out');};
  await a.connect('127.0.0.1',b.port);await until(()=>a.group.data.members[b.config.id]&&b.group.data.members[a.config.id]);
  await a.configure(()=>saveMouseFeel(a,[{id:b.config.id,settings:{...mouseDefaults,sensitivity:1.8}}]));
  await until(()=>mouseRecords(b)[b.config.id]?.settings.sensitivity===1.8);
  assert.deepEqual(calls[1].filter(c=>c.method==='mouse-feel').at(-1).args,{...mouseDefaults,sensitivity:1.8});
  await b.configure(()=>saveMouseFeel(b,[{id:a.config.id,settings:{...mouseDefaults,reverse:true}}]));
  await until(()=>mouseRecords(a)[a.config.id]?.settings.reverse===true);
  a.getPeer(b.config.id).close();await until(()=>!a.peers.has(b.config.id)&&!b.peers.has(a.config.id));
  await a.configure(()=>saveMouseFeel(a,[{id:b.config.id,settings:{...mouseDefaults,smooth:true,sensitivity:.7}}]));
  await a.connect('127.0.0.1',b.port);await until(()=>mouseRecords(b)[b.config.id]?.settings.sensitivity===.7);
  assert.equal(mouseRecords(b)[b.config.id].settings.smooth,true);assert.equal(mouseRecords(b)[a.config.id].settings.reverse,true);
  const peer=a.getPeer(b.config.id),request=peer.request;let motion;
  peer.request=async(method,args)=>{assert.equal(method,'input');motion=args;return {};};
  a.active=b.config.id;a.activeSession={transport:'network'};a.position={x:.5,y:.5};
  try{await a.nativeEvent({type:'input',kind:'move',dx:100,dy:0});}finally{a.active=null;a.activeSession=null;peer.request=request;}
  assert.ok(Math.abs((motion.x-.5)*1920-70)<.000001,'target sensitivity scales shared movement once');
  assert.equal(motion.dx,100,'raw displacement stays unscaled for relative input receivers');
  assert.equal(a.permissionNotice==null,true);assert.equal(b.permissionNotice==null,true);
  assert.equal((await checkMousePermission(a,b.config.id)).guideRequired,false);
  b.native.testCapabilities.input=false;
  assert.equal((await checkMousePermission(a,b.config.id)).guideRequired,true);
  assert.equal(b.permissionNotice.id,a.config.id);assert.equal(a.permissionNotice==null,true);
  b.native.testCapabilities.input=true;
  assert.equal((await checkMousePermission(a,b.config.id)).guideRequired,false);
});
