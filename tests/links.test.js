import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { randomUUID } from 'node:crypto';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { Service } from '../src/service.js';
import { InputTransport } from '../src/input-transport.js';
import { LinkManager } from '../src/links.js';
import { newToken } from '../src/identity.js';

const pause = ms => new Promise(r => setTimeout(r, ms));

test('Android route status follows live input grants independently of screen access', () => {
  const id = 'phone', p = {info:{platform:'android', capabilities:{networkInputReady:true}}};
  const routes = new InputTransport({config:{id:'desktop'}, group:{data:{id:'group'}}, peers:new Map([[id,p]])});
  const target = () => routes.state().targets.find(target => target.id === id);
  p.access = {permissions:{input:{appEnabled:true, peerAllowed:true, systemGranted:false}, screen:{systemGranted:true}}};
  assert.equal(target().selected, 'none');
  assert.equal(target().capabilities.bluetooth, 'unsupported');
  p.access.permissions.input.systemGranted = true;
  assert.equal(target().selected, 'network');
  p.access.permissions.input.peerAllowed = false;
  assert.equal(target().selected, 'none');
});

test('auxiliary route tolerates isolated latency spikes and retires on sustained degradation', async () => {
  const p = Object.assign(new EventEmitter(), {remote:{id:'desktop'}, closed:false, lanRequest:async()=>({})});
  const links = new LinkManager({peers:new Map([['desktop',p]]),group:{excluded:()=>false},emit:()=>{}});
  let delay = 40;
  const active = {primary:p,peer:{request:async()=>{if(delay)await pause(delay);},close:()=>{}},onPrimaryClosed:()=>{}};
  links.links.add(active);
  p.fastLink = active;
  await links.tick();
  assert.equal(p.fastLink,active);
  const pingLan = p.lanRequest;
  p.lanRequest = async () => { throw Error('comparison timed out'); };
  await links.tick();
  assert.equal(p.fastLink,active);
  assert.equal(active.slowSamples,0);
  p.lanRequest = pingLan;
  delay = 0;
  await links.tick();
  assert.equal(active.slowSamples,0);
  delay = 40;
  await links.tick();
  await links.tick();
  assert.equal(p.fastLink,active);
  await links.tick();
  assert.equal(p.fastLink,null);
  assert.equal(p.closed,false);
});
async function until(check) { for(let i=0;i<300;i++){if(check())return;await pause(10);}throw Error('Timed out'); }

test('standby activation cannot override a route selected while its acknowledgement is pending', async () => {
  const primary = Object.assign(new EventEmitter(), {
    remote:{id:'desktop'}, closed:false, routeVersion:0,
    lanRequest:async()=>({})
  });
  const service = {peers:new Map([['desktop',primary]]),group:{excluded:()=>false},emit:()=>{}};
  const manager = new LinkManager(service);
  let acknowledge, entered;
  const waiting = new Promise(resolve => { entered = resolve; });
  const standby = {
    primary, fastSamples:2, onPrimaryClosed:()=>{},
    peer:{
      closed:false,
      request:async method => {
        if (method === 'linkUse') {
          entered();
          await new Promise(resolve => { acknowledge = resolve; });
        }
        return {};
      },
      close(){ this.closed = true; }
    }
  };
  const replacement = {
    primary, onPrimaryClosed:()=>{},
    peer:{closed:false,request:async()=>({}),close(){this.closed=true;}}
  };
  manager.links.add(standby);
  const probing = manager.tick();
  await waiting;
  manager.links.add(replacement);
  manager.activate(primary,replacement);
  acknowledge();
  await probing;
  assert.equal(primary.fastLink,replacement);
  assert.equal(standby.peer.closed,true);
  assert.equal(replacement.peer.closed,false);
  assert.throws(()=>manager.activate(primary,standby),/INPUT_ROUTE_CLOSED/);
});

test('retired probe results cannot accumulate degradation against a newer route generation', async () => {
  let finish, entered;
  const waiting = new Promise(resolve => { entered = resolve; });
  const primary = Object.assign(new EventEmitter(), {
    remote:{id:'desktop'},closed:false,routeVersion:0,
    lanRequest:async()=>{entered();await new Promise(resolve=>{finish=resolve;});}
  });
  const manager = new LinkManager({
    peers:new Map([['desktop',primary]]),group:{excluded:()=>false},emit:()=>{}
  });
  const link = {primary,slowSamples:2,onPrimaryClosed:()=>{},
    peer:{closed:false,request:async()=>{await pause(40);},close(){this.closed=true;}}};
  manager.links.add(link);
  primary.fastLink=link;
  const probing = manager.tick();
  await waiting;
  primary.routeVersion++;
  finish();
  await probing;
  assert.equal(primary.fastLink,link);
  assert.equal(link.slowSamples,2);
  assert.equal(link.peer.closed,false);
});
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

test('a late heartbeat failure on a retired route cannot reset the replacement route',async t=>{
  const {a,b,p}=await pair(t);await link(a,b,p);await a.switchTo(b.config.id,'right',false);
  const old=p.fastLink, request=old.peer.request.bind(old.peer);
  let fail, started;
  const entered=new Promise(resolve=>started=resolve);
  old.peer.request=(method,...args)=>method==='keepControl'?new Promise((_,reject)=>{fail=reject;started();}):request(method,...args);
  const heartbeat=a.tick();
  await entered;
  // Both links still authenticate to the same primary, as during network/USB replacement.
  a.links.forward=async(_serial,port)=>({port,close:async()=>{}});
  await a.links.candidate(b.config.id,null,'fixture-usb');
  const replacement=[...a.links.links].find(candidate=>candidate!==old);
  assert.ok(replacement);
  await replacement.peer.request('linkUse');a.links.activate(p,replacement);
  await p.request('input',{kind:'key',code:'KeyA',down:true});
  const releases=b.native.calls.filter(c=>c.method==='release').length;
  fail(Error('设备响应超时'));await heartbeat;
  assert.equal(p.fastLink,replacement);
  assert.equal(a.active,b.config.id,'renewal confirms the new route before ending the active session');
  assert.equal(b.native.calls.filter(c=>c.method==='release').length,releases,'old heartbeat must not release a newly held key');
  await p.request('input',{kind:'key',code:'KeyA',down:false});
});

test('failed renewal on both the retired auxiliary and current LAN routes still ends control',async t=>{
  const {a,b,p}=await pair(t);await link(a,b,p);await a.switchTo(b.config.id,'right',false);
  let attempts=0;
  const fast=p.fastLink.peer,auxRequest=fast.request.bind(fast),lanRequest=p.lanRequest;
  const fail=()=>{attempts++;return Promise.reject(Error('设备响应超时'));};
  fast.request=(method,...args)=>method==='keepControl'?fail():auxRequest(method,...args);
  p.lanRequest=(method,...args)=>method==='keepControl'?fail():lanRequest(method,...args);
  await a.tick();
  assert.equal(attempts,2,'only one renewal retry is allowed across a route change');
  assert.equal(a.active,null);assert.equal(b.owner,null);
});

test('local QUIC capacity pressure during comparison does not retire a healthy auxiliary route',async t=>{
  const {a,b,p}=await pair(t);await link(a,b,p);const active=p.fastLink,request=p.lanRequest;
  p.lanRequest=(method,...args)=>method==='linkPing'?Promise.reject(Object.assign(Error('busy'),{code:'QUIC_BUSY'})):request(method,...args);
  await a.links.tick();assert.equal(p.fastLink,active);assert.equal(active.peer.closed,false);
});
