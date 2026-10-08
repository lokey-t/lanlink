import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { randomUUID } from 'node:crypto';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { Service } from '../src/service.js';
import { newToken } from '../src/identity.js';

const pause = ms => new Promise(r => setTimeout(r, ms));
async function until(check) { for(let i=0;i<300;i++){if(check())return;await pause(10);}throw Error('Timed out'); }
class Native extends EventEmitter {
  capabilities = { input: true, screen: true, host: true };
  calls = [];
  async start() {}
  async call(method, args) { this.calls.push({method,args}); return {}; }
  close() {}
}
async function pair(t) {
  const dir = await mkdtemp(path.join(os.tmpdir(),'lanlink-links-')), token = newToken(), services=[];
  t.after(async()=>{await Promise.all(services.map(s=>s.close()));await rm(dir,{recursive:true,force:true});});
  for(const name of ['A','B']) {
    const folder=path.join(dir,name);await mkdir(folder);
    const s=new Service({id:randomUUID(),name,token,permissions:{input:true,screen:true},edges:{}},folder,new Native(),{host:'127.0.0.1',port:0,noDiscovery:true,noQuic:true});
    services.push(s);await s.start();
  }
  const [a,b]=services;a.connect('127.0.0.1',b.port);
  await until(()=>a.peers.get(b.config.id)?.info && b.peers.get(a.config.id)?.info);
  return {a,b,p:a.getPeer(b.config.id),q:b.getPeer(a.config.id)};
}
async function link(a,b,p) {
  await a.links.candidate(b.config.id,'127.0.0.1');
  assert.ok(p.fastLink,'auxiliary route authenticated and selected');
  assert.equal(a.peers.size,1);assert.equal(b.peers.size,1);
}
async function approve(a,b,p) {
  await p.request('acquire');
}

test('second authenticated path shares consent, executes input once and falls back after disconnect', async t=>{
  const {a,b,p,q}=await pair(t);await link(a,b,p);
  await approve(a,b,p);
  await p.request('input',{kind:'key',code:'KeyA',down:true});
  assert.equal(b.native.calls.filter(c=>c.method==='input').length,1);
  const fast=p.fastLink;
  fast.peer.close();await until(()=>!p.fastLink&&!q.fastLink);
  assert.ok(b.native.calls.some(c=>c.method==='release'),'disconnect releases held input');
  await p.request('input',{kind:'key',code:'KeyA',down:false});
  assert.equal(b.native.calls.filter(c=>c.method==='input').length,2);
  assert.equal(b.owner.id,a.config.id,'LAN session/ownership survives');
  assert.equal(p.closed,false);assert.equal(q.closed,false);
});

test('ambiguous USB/network failure never replays an executed click',async t=>{
  const {a,b,p}=await pair(t);await link(a,b,p);await approve(a,b,p);
  const original=b.native.call.bind(b.native);let started, finish;
  const entered=new Promise(r=>started=r), blocked=new Promise(r=>finish=r);
  b.native.call=async(method,args)=>{await original(method,args);if(method==='input'){started();await blocked;}return {};};
  const pending=p.request('input',{kind:'button',button:0,down:true});
  const rejected=assert.rejects(pending,/断开/);
  await entered;p.fastLink.peer.close();finish();await rejected;
  assert.equal(b.native.calls.filter(c=>c.method==='input').length,1);
  await p.request('input',{kind:'button',button:0,down:false});
  assert.equal(b.native.calls.filter(c=>c.method==='input').length,2);
});

test('auxiliary control cannot bypass peer revocation or use privileged management operations',async t=>{
  const {a,b,p}=await pair(t);await link(a,b,p);await approve(a,b,p);
  await assert.rejects(p.fastLink.peer.request('shutdown'),/辅助通道不支持/);
  await b.revokeConsent(a.config.id);
  await assert.rejects(p.request('input',{kind:'key',code:'KeyA',down:true}),/CONSENT_DENIED|CONTROL_ENDED|控制权/);
  assert.equal(b.native.calls.filter(c=>c.method==='input').length,0);
});

test('both input directions and encrypted video can use the alternate network path',async t=>{
  const {a,b,p,q}=await pair(t);await link(a,b,p);await approve(a,b,p);await p.request('release');
  await q.request('acquire');await q.request('input',{kind:'relative',dx:5,dy:2});await q.request('release');
  assert.equal(a.native.calls.filter(c=>c.method==='input').length,1);
  b.options.videoSource=(_native,_h264,emit)=>{const timer=setInterval(()=>emit({codec:'jpeg',width:20,height:10,data:Buffer.from('fixture'),key:true}),20);return {close(){clearInterval(timer);}};};
  await a.openStream(b.config.id,true);
  assert.equal((await a.videoViewer.next(b.config.id)).width,20);
  assert.ok(a.videoViewer.sessions.get(b.config.id).route);
  await a.closeStream(b.config.id);
  await until(()=>b.videoHost.sessions.size===0);
});

test('closing main LAN session also retires auxiliary channels',async t=>{
  const {a,b,p,q}=await pair(t);await link(a,b,p);
  p.close();await until(()=>a.links.links.size===0&&b.links.links.size===0);
  assert.equal(q.fastLink,null);
});

test('automatic cable scan excludes wireless ADB, offline devices and emulators',async t=>{
  const {a,b,p}=await pair(t);p.info.platform='android';
  a.adb.locate=async()=>'/test/adb';
  a.adb.command=async()=>`List of devices attached
PHONE123 device model:Phone
192.168.1.2:5555 device model:Wireless
adb-serial._adb-tls-connect._tcp device model:Wireless
emulator-5580 device model:Emulator
PHONE456 unauthorized
PHONE789 offline
`;
  const candidates=[];a.links.candidate=async(...args)=>candidates.push(args);
  await a.links.tick();assert.deepEqual(candidates,[[b.config.id,null,'PHONE123']]);
});

test('higher-latency auxiliary route leaves LAN as the selected input path',async t=>{
  const {a,b,p}=await pair(t);
  const dispatch=b.links.dispatch.bind(b.links);
  b.links.dispatch=async(primary,link,m)=>{if(m.method==='linkPing')await pause(30);return dispatch(primary,link,m);};
  await a.links.candidate(b.config.id,'127.0.0.1');
  assert.equal(p.fastLink,undefined);assert.equal(a.links.status(p),'局域网');
});
