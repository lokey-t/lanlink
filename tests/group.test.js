import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { randomUUID, privateDecrypt } from 'node:crypto';
import { Group } from '../src/group.js';
import { newToken, loadConfig } from '../src/identity.js';
import { AdaptiveVideo, VideoFeedback } from '../src/adaptive-video.js';

async function members(t, count=3) {
  const dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-group-')); t.after(()=>rm(dir,{recursive:true,force:true}));
  const token=newToken(), services=[];
  for(let i=0;i<count;i++) {
    const s={config:{id:randomUUID(),name:String(i),token,deviceGrants:{},edges:{},knownDevices:{}},dir:path.join(dir,String(i)),peers:new Map(),videoHost:{closePeer(){}},native:{async call(){}},async groupChanged(){ this.changed=true; },options:{noDiscovery:true}};
    s.group=new Group(s); services.push(s);
  }
  for(const s of services) for(const other of services) if(s!==other) { s.group.learn(other.group.own); s.config.knownDevices[other.config.id]={name:other.config.name}; }
  return services;
}

test('removal encrypts a new credential to offline survivors; revoked member cannot decrypt or replay',async t=>{
  const [a,b,c]=await members(t), before=a.config.token, stable=a.group.data.id;
  a.config.edges.left=b.config.id; a.config.deviceGrants[b.config.id]={input:true};
  await a.group.remove(b.config.id);
  assert.notEqual(a.config.token,before); assert.equal(a.group.data.id,stable);
  assert.equal(a.config.knownDevices[b.config.id],undefined); assert.equal(a.config.deviceGrants[b.config.id],undefined); assert.equal(a.config.edges.left,'');
  assert.equal(a.group.boxes.has(b.config.id),false);
  const box=a.group.boxes.get(c.config.id);
  assert.deepEqual(a.group.recoveryResponse(c.group.recoveryQuery()),box);
  assert.equal(a.group.recoveryResponse(b.group.recoveryQuery()),null);
  assert.equal(a.group.recoveryResponse({...c.group.recoveryQuery(),group:'0'.repeat(24)}),null);
  assert.throws(()=>privateDecrypt({key:Buffer.from(b.config.deviceKey.private,'base64'),format:'der',type:'pkcs8',oaepHash:'sha256'},Buffer.from(box.wrapped,'base64')));
  await assert.rejects(c.group.accept({...box,body:box.body.slice(0,-4)+'AAAA'}),/签名/);
  assert.equal(c.config.token,before);
  await c.group.accept(box); assert.equal(c.config.token,a.config.token); assert.ok(c.changed); assert.ok(c.group.excluded(b.config.id));
  assert.equal(a.group.recoveryResponse(c.group.recoveryQuery()),null);
  await assert.rejects(c.group.accept(box),/已处理/);
  const saved=await loadConfig(c.dir); assert.equal(saved.token,a.config.token); assert.equal(saved.deviceKey.private,c.config.deviceKey.private);
  const restarted={...c,config:saved}; restarted.group=new Group(restarted); assert.ok(restarted.group.excluded(b.config.id));
  const impostor={...b,config:{id:randomUUID(),name:'impostor',token:b.config.token,deviceGrants:{},edges:{}},dir:path.join(b.dir,'impostor')}; impostor.group=new Group(impostor);
  await b.group.admit(impostor.group.own);impostor.group.adopt(structuredClone(b.group.export()),b.config.token);
  await assert.rejects(c.group.accept(impostor.group.makeBox(c.group.own)),/签名/);
  assert.equal(a.group.learn(b.group.own),false); await a.group.admit(b.group.own); assert.equal(a.group.excluded(b.config.id),false);
});

test('long-offline member verifies newcomer admission chain even when original inviter is offline',async t=>{
  const [a,b,c,d]=await members(t,4);
  // D joins after C went offline, so C has no trusted D key yet.
  delete c.group.data.members[d.config.id]; delete c.group.data.anchors[d.config.id]; delete a.group.data.members[d.config.id];
  await a.group.admit(d.group.own);
  const admission=a.group.boxes.get(c.config.id);
  d.group.adopt(structuredClone(a.group.export()),a.config.token);
  await d.group.remove(b.config.id); const update=d.group.boxes.get(c.config.id);
  const forged=structuredClone(update);forged.chain[0].certificate.signature='AAAA'; await assert.rejects(c.group.accept(forged),/签名/);
  await c.group.accept(update);
  assert.equal(c.config.token,d.config.token); assert.ok(c.group.excluded(b.config.id));
  assert.ok(d.group.data.history.some(b=>b.revision===admission.revision&&b.to===c.config.id));
});

test('adaptive bitrate reduces congestion, has hysteresis and bounds, and tolerates clock offset',()=>{
  let now=10000; const abr=new AdaptiveVideo(4000000,()=>now);
  assert.ok(abr.feedback({delayMs:160,rttMs:30})); assert.equal(abr.rate,2600000);
  assert.equal(abr.feedback({delayMs:160,rttMs:30}),false);
  for(let i=0;i<8;i++){now+=3000;abr.feedback({delayMs:200,rttMs:300});} assert.equal(abr.rate,500000);
  for(let i=0;i<180;i++){now+=1000;abr.feedback({delayMs:0,rttMs:10});} assert.equal(abr.rate,8000000);
  assert.equal(abr.feedback({delayMs:NaN,rttMs:10}),false);
  const feedback=new VideoFeedback(()=>now);feedback.receive({time:now-3600000});assert.equal(feedback.report().delayMs,0);
  now+=1000;feedback.receive({time:now-3600120});assert.equal(feedback.report().delayMs,120);
  now+=1000;feedback.receive({time:now+7200000});assert.equal(feedback.report().delayMs,0);
});
