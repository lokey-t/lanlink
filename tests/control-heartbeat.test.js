import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {randomUUID} from 'node:crypto';
import {mkdtemp, mkdir, rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {Service} from '../src/service.js';
import {newToken} from '../src/identity.js';
import {quicPath} from '../src/quic-native.js';

const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
function deferred(){let resolve;const promise=new Promise(done=>resolve=done);return {promise,resolve};}
async function deadline(promise, message, ms=700) {
  let timer;
  try{return await Promise.race([promise,new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error(message)),ms);})]);}
  finally{clearTimeout(timer);}
}
async function until(check){for(let i=0;i<400;i++){if(check())return;await pause(10);}throw Error('Connection did not become ready');}
class Native extends EventEmitter {
  capabilities={input:true,screen:true,host:true};
  calls=[];
  async start(){}
  async call(method,args){this.calls.push({method,args});return {};}
  close(){}
}
async function pair(t,transport) {
  const directory=await mkdtemp(path.join(os.tmpdir(),'lanlink-heartbeat-')),token=newToken(),services=[];
  t.after(async()=>{await Promise.all(services.map(service=>service.close()));await rm(directory,{recursive:true,force:true});});
  for(const name of ['A','B']) {
    const folder=path.join(directory,name);await mkdir(folder);
    const service=new Service({id:randomUUID(),name,token,permissions:{input:true,screen:true},edges:{}},folder,new Native(),{host:'127.0.0.1',port:0,noDiscovery:true,noQuic:transport!=='quic'});
    services.push(service);await service.start();
  }
  const [a,b]=services;a.connect('127.0.0.1',b.port);
  await until(()=>a.peers.get(b.config.id)?.info&&b.peers.get(a.config.id)?.info);
  const peer=a.getPeer(b.config.id);
  if(transport==='auxiliary') {
    await a.links.candidate(b.config.id,'127.0.0.1');
    assert.ok(peer.fastLink,'the authenticated alternate input route was selected');
  }
  if(transport==='quic')await until(()=>peer.quicConn&&b.getPeer(a.config.id).quicConn);
  await peer.request('acquire');
  return {a,b,peer};
}

for(const transport of ['tcp','auxiliary','quic']) {
  test(`${transport}: delayed renewal is single-flight and cannot block ordered mouse input`,{skip:transport==='quic'&&!quicPath(),timeout:10000},async t=>{
    const {b,peer}=await pair(t,transport),heartbeatEntered=deferred(),heartbeatReply=deferred(),moveEntered=deferred(),moveReply=deferred();
    const original=b.native.call.bind(b.native);let heartbeatDone=false;
    b.native.call=async(method,args)=>{
      await original(method,args);
      if(method==='heartbeat'){heartbeatEntered.resolve();await heartbeatReply.promise;}
      if(method==='input'&&args.kind==='move'){moveEntered.resolve();await moveReply.promise;}
      return {};
    };
    const renewal=peer.request('keepControl',{},3000);
    renewal.then(()=>{heartbeatDone=true;},()=>{heartbeatDone=true;});
    try {
      await deadline(heartbeatEntered.promise,'renewal never reached the controlled device');
      const duplicates=Array.from({length:10},()=>peer.request('keepControl',{},3000));
      for(const duplicate of duplicates)assert.equal(duplicate,renewal,'one pending renewal is shared by all callers');
      const move=peer.request('input',{kind:'move',x:.7,y:.4});
      await deadline(moveEntered.promise,'mouse motion was blocked behind the renewal response');
      const button=peer.request('input',{kind:'button',button:0,down:true});
      await pause(35);
      assert.equal(b.native.calls.filter(call=>call.method==='input'&&call.args.kind==='button').length,0,'a click still waits for the preceding position to be applied');
      moveReply.resolve();
      await deadline(Promise.all([move,button]),'click was blocked behind the renewal response');
      assert.equal(heartbeatDone,false,'mouse and click completed while the renewal response was still delayed');
      assert.deepEqual(b.native.calls.filter(call=>call.method==='input').map(call=>call.args.kind),['move','button']);
      assert.equal(b.native.calls.filter(call=>call.method==='heartbeat').length,1);
      heartbeatReply.resolve();await renewal;
      await peer.request('keepControl',{},3000);
      assert.equal(b.native.calls.filter(call=>call.method==='heartbeat').length,2,'a completed renewal does not suppress the next one');
    } finally {
      moveReply.resolve();heartbeatReply.resolve();await renewal.catch(()=>{});
    }
  });
}

test('local QUIC admission pressure delays renewal without ending the lease; a real failure still ends it',async t=>{
  const {a,b,peer}=await pair(t,'tcp');await a.switchTo(b.config.id,'right',false);
  const request=peer.request;let pressure=true;
  peer.request=(method,...args)=>method==='keepControl'&&pressure
    ?Promise.reject(Object.assign(Error('QUIC 通道繁忙，请稍后重试'),{code:'QUIC_BUSY'}))
    :request(method,...args);
  const releases=b.native.calls.filter(call=>call.method==='release').length;
  await a.tick();assert.equal(a.active,b.config.id);assert.equal(b.owner.id,a.config.id);
  assert.equal(b.native.calls.filter(call=>call.method==='release').length,releases);
  pressure=false;await a.tick();
  assert.ok(b.native.calls.some(call=>call.method==='heartbeat'));
  peer.request=(method,...args)=>method==='keepControl'?Promise.reject(Error('连接已断开')):request(method,...args);
  await a.tick();assert.equal(a.active,null);assert.equal(b.owner,null);
});
