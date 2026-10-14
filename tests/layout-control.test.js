import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {randomUUID} from 'node:crypto';
import {mkdtemp,mkdir,rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {Service} from '../src/service.js';
import {newToken} from '../src/identity.js';

const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(check){const end=Date.now()+6000;while(!check()){if(Date.now()>end)throw Error('layout control condition timed out');await pause(10);}}
class Native extends EventEmitter {
  capabilities={host:true,input:true,screen:true,clipboard:true,width:1000,height:600};calls=[];
  async start(){} close(){}
  async call(method,args){this.calls.push({method,args});return method==='capabilities'?this.capabilities:method==='clipboardGet'?{text:'',files:[]}:{};}
}
async function graph(t){
  const directory=await mkdtemp(path.join(os.tmpdir(),'lanlink-layout-control-')),token=newToken(),all=[];
  t.after(async()=>{await Promise.all(all.map(service=>service.close()));await rm(directory,{recursive:true,force:true});});
  for(const name of ['Master','A','B','C','D']){
    const dir=path.join(directory,name);await mkdir(dir);
    const service=new Service({id:randomUUID(),name,token,edges:{},dwell:0,permissions:{input:true,screen:true,clipboard:true}},dir,new Native(),{host:'127.0.0.1',port:0,noDiscovery:true,noQuic:true});
    all.push(service);await service.start();
  }
  for(let i=0;i<all.length;i++)for(let j=i+1;j<all.length;j++)await all[i].connect('127.0.0.1',all[j].port);
  await until(()=>all.every(service=>service.peers.size===4&&[...service.peers.values()].every(peer=>peer.info)));
  const [master,a,b,c,d]=all,positions=Object.fromEntries(all.map((service,i)=>[service.config.id,[{x:0,y:0},{x:1,y:0},{x:2,y:0},{x:1,y:1},{x:3,y:0}][i]]));
  await master.settings({masterId:master.config.id,layoutPositions:positions});
  await until(()=>all.every(service=>service.state().masterId===master.config.id&&Object.keys(service.state().layout.positions).length===5));
  return {all,master,a,b,c,d};
}
async function enter(master,target){
  master.edge.cooldown=0;master.edge.waitingForLeave=false;
  await master.nativeEvent({type:'cursor',x:999,y:180,width:1000,height:600});
  assert.equal(master.active,target.config.id);
}
async function cross(master,edge,target){
  const x=edge==='right'?1:edge==='left'?0:master.position.x;
  const y=edge==='bottom'?1:edge==='top'?0:master.position.y;
  await master.nativeEvent({type:'input',kind:'move',dx:(x-master.position.x)*1000,dy:(y-master.position.y)*600});
  master.edge.cooldown=0;master.edge.waitingForLeave=false;
  await master.checkReturnEdge();
  await until(()=>!master.switching&&!master.returning);
  assert.equal(master.active,target===master?null:target.config.id);
}

test('one physical master traverses chains, branches and return paths without relaying or recapturing locally',async t=>{
  const {all,master,a,b,c,d}=await graph(t);await enter(master,a);
  const initialY=master.position.y;
  for(const [edge,target]of [['right',b],['right',d],['left',b],['left',a],['bottom',c],['top',a]]){
    const previous=all.find(service=>service.config.id===master.active);
    await cross(master,edge,target);
    assert.equal(previous.owner,null);assert.equal(target.owner.id,master.config.id);
    assert.equal(master.capture,true);
    assert.equal(master.native.calls.filter(call=>call.method==='mode'&&!call.args.remote).length,0,'remote hops keep the original physical capture');
    assert.ok(all.filter(service=>service!==target).every(service=>service.owner===null));
    if(target===d)assert.equal(master.position.y,initialY,'horizontal chains preserve the vertical entry point');
  }
  await cross(master,'left',master);
  assert.equal(master.capture,false);assert.ok(all.every(service=>service.owner===null));
  const warp=master.native.calls.findLast(call=>call.method==='warp');
  assert.equal(warp.args.x,.985,'return to the master enters at its adjacent right edge');
});

test('only the synchronized master may capture or request pointer control, and changing it ends the old capture',async t=>{
  const {all,master,a,b}=await graph(t);
  a.config.master=true; // A stale legacy preference cannot override group selection.
  a.edge.cooldown=0;
  await a.nativeEvent({type:'cursor',x:999,y:200,width:1000,height:600});assert.equal(a.active,null);
  await assert.rejects(a.switchTo(b.config.id,'right',true),/主控鼠标/);
  await assert.rejects(a.getPeer(b.config.id).request('acquire',{pointer:true}),/主控鼠标/);
  await enter(master,a);
  await b.settings({masterId:b.config.id});
  await until(()=>all.every(service=>service.state().masterId===b.config.id)&&!master.active&&!master.returning);
  assert.equal(a.owner,null);assert.equal(master.capture,false);
  assert.deepEqual(master.state().layout.positions,b.state().layout.positions,'master selection does not rearrange the layout');
  b.edge.cooldown=0;b.edge.waitingForLeave=false;
  await b.nativeEvent({type:'cursor',x:0,y:180,width:1000,height:600});
  assert.equal(b.active,a.config.id);assert.equal(a.owner.id,b.config.id);
});

test('a refused target cannot strand the captured mouse or leave two owners',async t=>{
  const {master,a,b}=await graph(t);await enter(master,a);
  await b.setConsent(master.config.id,{input:false});
  await cross(master,'right',master);
  assert.equal(a.owner,null);assert.equal(b.owner,null);assert.equal(master.capture,false);
  assert.ok(master.logs.some(row=>/授权|控制/.test(row.message)));
});

test('an offline neighbor is skipped while other return paths remain usable',async t=>{
  const {master,a,b}=await graph(t);await enter(master,a);
  master.getPeer(b.config.id).close();await until(()=>!master.peers.has(b.config.id));
  master.position.x=1;master.edge.cooldown=0;await master.checkReturnEdge();
  assert.equal(master.active,a.config.id);assert.equal(a.owner.id,master.config.id);
  await cross(master,'left',master);assert.equal(a.owner,null);
});

test('input during remote handoff reaches only the new target and held modifiers survive in order',async t=>{
  const {master,a,b}=await graph(t);await enter(master,a);
  await master.nativeEvent({type:'input',kind:'key',code:'ShiftLeft',down:true});
  const original=a.native.call.bind(a.native);let entered,finish;
  const started=new Promise(resolve=>entered=resolve),gate=new Promise(resolve=>finish=resolve);
  a.native.call=async(method,args)=>{const result=await original(method,args);if(method==='release'){entered();await gate;}return result;};
  master.position.x=1;master.edge.cooldown=0;
  const switching=master.checkReturnEdge();await started;
  const oldInputs=a.native.calls.filter(call=>call.method==='input').length;
  const input=[master.nativeEvent({type:'input',kind:'move',dx:20,dy:0}),master.nativeEvent({type:'input',kind:'key',code:'ShiftLeft',down:false}),master.nativeEvent({type:'input',kind:'button',button:0,down:true}),master.nativeEvent({type:'input',kind:'button',button:0,down:false})];
  finish();await switching;await Promise.all(input);
  assert.equal(master.active,b.config.id);assert.equal(a.native.calls.filter(call=>call.method==='input').length,oldInputs);
  assert.deepEqual(b.native.calls.filter(call=>call.method==='input'&&call.args.kind==='key').map(call=>[call.args.code,call.args.down]),[['ShiftLeft',true],['ShiftLeft',false]]);
  assert.deepEqual(b.native.calls.filter(call=>call.method==='input'&&call.args.kind==='button').map(call=>call.args.down),[true,false]);
});

test('a session-scoped late end notification cannot terminate a newer session with the same device',async t=>{
  const {master,a}=await graph(t);await enter(master,a);
  const previous=master.activeSession.edgeSession;await master.returnLocal();
  await enter(master,a);assert.notEqual(master.activeSession.edgeSession,previous);
  await master.message(master.getPeer(a.config.id),{type:'control-ended',session:previous});
  assert.equal(master.active,a.config.id);assert.equal(a.owner.id,master.config.id);
});

test('leaving a remote edge cancels pending preparation and releases both target leases',async t=>{
  const {master,a,b}=await graph(t);await enter(master,a);
  let entered,finish;const started=new Promise(resolve=>entered=resolve),gate=new Promise(resolve=>finish=resolve);
  const original=master.access.check.bind(master.access);
  master.access.check=async(peer,intent)=>{if(intent.id===b.config.id){entered();await gate;}return original(peer,intent);};
  master.position.x=1;master.edge.cooldown=0;
  const switching=master.checkReturnEdge();
  await started;
  await master.nativeEvent({type:'input',kind:'move',dx:-100,dy:0});
  finish();await switching;
  assert.equal(master.active,null);assert.equal(master.capture,false);assert.equal(a.owner,null);assert.equal(b.owner,null);
});
