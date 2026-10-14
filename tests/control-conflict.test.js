import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { randomUUID } from 'node:crypto';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { Service } from '../src/service.js';
import { newToken } from '../src/identity.js';

const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(check) { const end = Date.now() + 4000; while (!check()) { if (Date.now() > end) throw Error('Timed out'); await pause(10); } }
class Native extends EventEmitter {
  constructor() { super(); this.capabilities = { input: true, screen: true, clipboard: true, host: true }; this.calls = []; }
  async start() {}
  async call(method, args) { this.calls.push({ method, args }); return method === 'screen' ? { data: 'fixture', width: 20, height: 10 } : method === 'clipboardGet' ? { text: '', files: [] } : {}; }
  close() {}
}
async function pair(t) {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'lanlink-conflict-')), token = newToken(), services = [];
  t.after(async () => { await Promise.all(services.map(s => s.close())); await rm(dir, { recursive: true, force: true }); });
  for (const name of ['A', 'B']) {
    const local = path.join(dir, name); await mkdir(local);
    const s = new Service({ id: randomUUID(), name, token, permissions: { input: true, screen: true, clipboard: true }, edges: {} }, local, new Native(), { host: '127.0.0.1', port: 0, noDiscovery: true, videoSource: () => ({ close() {} }) });
    services.push(s); await s.start();
  }
  const [a, b] = services; a.connect('127.0.0.1', b.port); await until(() => a.peers.size && b.peers.size && a.peers.values().next().value.info && b.peers.values().next().value.info);
  return { a, b };
}

test('preparation checks screen and input without acquiring; missing screen cannot start control', async t => {
  const { a,b }=await pair(t), p=a.getPeer(b.config.id);
  await b.settings({permissions:{screen:false}});
  await assert.rejects(p.request('prepare',{screen:true}),/PERMISSION_REQUIRED/);
  await assert.rejects(a.switchTo(b.config.id,'right',false,false,false,true),/PERMISSION_REQUIRED/);
  assert.equal(b.owner,null); assert.equal(a.active,null); assert.equal(b.videoHost.sessions.size,0);
  await b.settings({permissions:{screen:true}});
  assert.equal((await p.request('prepare',{screen:true})).ready,true);
  assert.equal(b.owner,null);
  await a.switchTo(b.config.id,'right',false,false,false,true);
  assert.equal(b.owner.id,a.config.id);
});

test('broken encrypted transport can reacquire saved consent and create a fresh video session', async t => {
  const {a,b}=await pair(t), id=b.config.id;
  b.options.videoSource=(_native,_h264,emit) => {
    const timer=setInterval(() => emit({codec:'jpeg',width:20,height:10,data:Buffer.from('fixture'),key:true}),20);
    return {close(){clearInterval(timer);}};
  };
  await a.switchTo(id,'right',false,false,false,true); await a.openStream(id,true);
  assert.equal((await a.videoViewer.next(id)).width,20);
  a.getPeer(id).close();
  await until(() => !a.peers.size && !b.peers.size && !a.active && !a.returning);
  a.connect('127.0.0.1',b.port);
  await until(() => a.peers.get(id)?.info && b.peers.get(a.config.id)?.info);
  await a.switchTo(id,'right',false,false,false,true); await a.openStream(id,true);
  assert.equal((await a.videoViewer.next(id)).height,10);
  assert.equal(b.consent.list().length,0);
  await b.stopInbound();
  await assert.rejects(a.getPeer(id).request('prepare',{screen:true}),/CONTROL_ENDED/);
});

test('simultaneous encrypted acquisition never creates a reciprocal control loop and can retry', async t => {
  const { a, b } = await pair(t);
  const results = await Promise.allSettled([a.switchTo(b.config.id, 'right', false), b.switchTo(a.config.id, 'right', false)]);
  assert.ok(results.some(r => r.status === 'rejected' && /CONTROL_CONFLICT/.test(r.reason.message)));
  assert.ok(!(a.active && b.active)); assert.ok(!(a.owner && b.owner));
  await Promise.all([a.returnLocal(), b.returnLocal()]);
  assert.equal(a.switching, false); assert.equal(b.switching, false);
  await a.switchTo(b.config.id, 'right', false); assert.equal(b.owner.id, a.config.id);
});

test('an input owner blocks reverse input, video and screenshot paths until release', async t => {
  const { a, b } = await pair(t); await a.switchTo(b.config.id, 'right', false);
  for (const operation of [() => b.switchTo(a.config.id, 'right', false), () => b.openStream(a.config.id, true), () => b.screenFrom(a.config.id)]) await assert.rejects(operation(), /CONTROL_CONFLICT/);
  for (const method of ['acquire', 'videoOpen', 'screen']) await assert.rejects(b.getPeer(a.config.id).request(method), /CONTROL_CONFLICT/);
  await a.returnLocal(); await b.switchTo(a.config.id, 'right', false); assert.equal(a.owner.id, b.config.id);
});

test('screen-only streams reserve the direction before negotiation and release it on close', async t => {
  const { a, b } = await pair(t);
  const results = await Promise.allSettled([a.openStream(b.config.id, true), b.openStream(a.config.id, true)]);
  assert.ok(results.some(r => r.status === 'rejected' && /CONTROL_CONFLICT/.test(r.reason.message)));
  assert.ok(!(a.videoViewer.sessions.size && b.videoViewer.sessions.size));
  await Promise.all([a.closeStream(b.config.id), b.closeStream(a.config.id)]);
  assert.equal(a.outgoingIntents.size, 0); assert.equal(b.outgoingIntents.size, 0);
  await a.openStream(b.config.id, true);
  await assert.rejects(b.switchTo(a.config.id, 'right', false), /CONTROL_CONFLICT/);
  await assert.rejects(b.openStream(a.config.id, true), /CONTROL_CONFLICT/);
  await a.closeStream(b.config.id); await b.switchTo(a.config.id, 'right', false);
});

test('legacy screenshot viewers are visible, prevent reverse control and clear on explicit close', async t => {
  const { a, b } = await pair(t); assert.equal((await a.screenFrom(b.config.id)).data, 'fixture');
  assert.deepEqual(b.state().viewers, [a.config.id]);
  await assert.rejects(b.switchTo(a.config.id, 'right', false), /CONTROL_CONFLICT/);
  await assert.rejects(b.getPeer(a.config.id).request('screen'), /CONTROL_CONFLICT/);
  await a.closeStream(b.config.id); assert.deepEqual(b.state().viewers, []);
  await b.switchTo(a.config.id, 'right', false);
});

test('cancelling pending acquisition releases a late response without stealing a newer session', async t => {
  const { a, b } = await pair(t); let unblock, received;
  const gate = new Promise(resolve => { unblock = resolve; }), entered = new Promise(resolve => { received = resolve; });
  const handle = b.handle.bind(b); let delay = true;
  b.handle = async (peer, method, args) => { const result = await handle(peer, method, args); if (method === 'acquire' && delay) { received(); await gate; } return result; };
  const pending = a.switchTo(b.config.id, 'right', false), rejected = assert.rejects(pending, /CONTROL_ENDED/);
  await entered; await a.returnLocal(); await assert.rejects(a.switchTo(b.config.id, 'right', false), /CONTROL_CONFLICT/);
  unblock(); await rejected; assert.equal(a.active, null); assert.equal(b.owner, null); assert.equal(a.switching, false);
  delay = false; await a.switchTo(b.config.id, 'right', false); assert.equal(b.owner.id, a.config.id);
});

test('closing a pending screen negotiation cancels the late session and cleans all reservations', async t => {
  const { a, b } = await pair(t); let unblock, received;
  const gate = new Promise(resolve => { unblock = resolve; }), entered = new Promise(resolve => { received = resolve; });
  const handle = b.handle.bind(b);
  b.handle = async (peer, method, args) => { const result = await handle(peer, method, args); if (method === 'videoOpen') { received(); await gate; } return result; };
  const pending = a.openStream(b.config.id, true), rejected = assert.rejects(pending, /CONTROL_ENDED/);
  await entered; await a.closeStream(b.config.id); unblock(); await rejected;
  assert.equal(a.videoViewer.sessions.size, 0); assert.equal(a.videoViewer.opening.size, 0); assert.equal(a.outgoingIntents.size, 0); assert.equal(b.videoHost.sessions.size, 0);
  await b.switchTo(a.config.id, 'right', false);
});

test('failed acquire and screen requests release their pending reservations', async t => {
  const { a, b } = await pair(t); await b.settings({ permissions: { input: false, screen: false } });
  await assert.rejects(a.switchTo(b.config.id, 'right', false), /PERMISSION_REQUIRED/);
  await assert.rejects(a.openStream(b.config.id, true), /PERMISSION_REQUIRED/);
  await assert.rejects(a.screenFrom(b.config.id), /PERMISSION_REQUIRED/);
  assert.equal(a.switching, false); assert.equal(a.outgoingIntents.size, 0); assert.equal(a.videoViewer.opening.size, 0);
  await b.switchTo(a.config.id, 'right', false);
});

test('changing group cancels an acquisition already accepted by the former connection', async t => {
  const { a, b } = await pair(t); let unblock, received;
  const gate = new Promise(resolve => { unblock = resolve; }), entered = new Promise(resolve => { received = resolve; });
  const handle = b.handle.bind(b);
  b.handle = async (peer, method, args) => { const result = await handle(peer, method, args); if (method === 'acquire') { received(); await gate; } return result; };
  const pending = a.switchTo(b.config.id, 'right', false), rejected = assert.rejects(pending, /CONTROL_ENDED|连接已断开/);
  await entered; await a.groupChanged(); unblock(); await rejected;
  assert.equal(a.active, null); assert.equal(a.switching, false); assert.equal(b.owner, null);
});

test('a late heartbeat failure from a released session cannot end a newly acquired session', async t => {
  const { a, b } = await pair(t); clearInterval(a.heartbeat);
  await a.switchTo(b.config.id, 'right', false); let rejectHeartbeat, received;
  const gate = new Promise((_resolve, reject) => { rejectHeartbeat = reject; }), entered = new Promise(resolve => { received = resolve; });
  const peer = a.getPeer(b.config.id), request = peer.request.bind(peer); let delay = true;
  peer.request = (method, ...args) => { if (method === 'keepControl' && delay) { received(); return gate; } return request(method, ...args); };
  const heartbeat = a.tick(); await entered; await a.returnLocal(); delay = false; await a.switchTo(b.config.id, 'right', false);
  rejectHeartbeat(Error('old heartbeat failed')); await heartbeat;
  assert.equal(a.active, b.config.id); assert.equal(b.owner.id, a.config.id);
});

test('Android accessibility fallback requires session consent and loses it after release or downgrade', async t => {
  const { a, b } = await pair(t), peer = a.getPeer(b.config.id); peer.info.platform = 'android'; peer.info.capabilities.inputMode = 'accessibility-limited'; b.native.capabilities.inputMode = 'accessibility-limited';
  await assert.rejects(a.switchTo(b.config.id, 'right', false), /FALLBACK_CONFIRMATION/); assert.equal(b.owner, null); assert.equal(a.state().fallbackNotice.id, b.config.id);
  await a.switchTo(b.config.id, 'right', false, false, true); assert.equal(a.active, b.config.id); assert.equal(a.fallbackNotice, null);
  await a.returnLocal(); await assert.rejects(a.switchTo(b.config.id, 'right', false), /FALLBACK_CONFIRMATION/);
  peer.info.capabilities.inputMode = 'adb/root'; b.native.capabilities.inputMode = 'adb/root'; await a.switchTo(b.config.id, 'left', false);
  await a.message(peer, { type: 'identity', name: 'B', platform: 'android', capabilities: { ...b.native.capabilities, inputMode: 'accessibility-limited' } });
  assert.equal(a.active, null); assert.equal(b.owner, null); assert.equal(a.fallbackNotice.edge, 'left');
});

test('a target fallback confirmation error opens the notice even before mode discovery', async t => {
  const { a, b } = await pair(t); const handle = b.handle.bind(b);
  b.handle = (peer, method, args) => { if (method === 'acquire' && !args.fallbackAccepted) throw Error('FALLBACK_CONFIRMATION: 无障碍备用模式'); return handle(peer, method, args); };
  await assert.rejects(a.switchTo(b.config.id, 'top', false), /FALLBACK_CONFIRMATION/);
  assert.deepEqual(a.state().fallbackNotice, { id: b.config.id, edge: 'top', capture: false }); assert.equal(a.switching, false);
  await a.switchTo(b.config.id, 'top', false, false, true); assert.equal(a.fallbackNotice, null);
});

test('a downgrade heartbeat opens fallback guidance before the next capability announcement', async t => {
  const { a, b } = await pair(t); clearInterval(a.heartbeat);
  const peer = a.getPeer(b.config.id); peer.info.platform = 'android'; peer.info.capabilities.inputMode = 'adb/root'; b.native.capabilities.inputMode = 'adb/root';
  await a.switchTo(b.config.id, 'bottom', false); const handle = b.handle.bind(b);
  b.handle = (p, method, args) => { if (method === 'keepControl') throw Error('FALLBACK_CONFIRMATION: 增强输入已断开'); return handle(p, method, args); };
  await a.tick(); assert.equal(a.active, null); assert.equal(b.owner, null);
  assert.deepEqual(a.fallbackNotice, { id: b.config.id, edge: 'bottom', capture: false });
});

test('cursor feedback is normalized and sent only while the authorized input lease is active', async t => {
  const {a,b}=await pair(t),received=[];
  a.getPeer(b.config.id).on('message',m=>{if(m.type==='cursor')received.push(m);});
  await a.switchTo(b.config.id,'right',false,true);
  await b.nativeEvent({type:'cursor',x:750,y:250,width:1001,height:501});
  await until(()=>received.length===1);assert.equal(received[0].x,.75);assert.equal(received[0].y,.5);
  b.cursorSentAt=0;b.owner.until=Date.now()-1;
  await b.nativeEvent({type:'cursor',x:0,y:0,width:1001,height:501});
  await pause(40);assert.equal(received.length,1);
  await b.revokeConsent(a.config.id);b.cursorSentAt=0;
  await b.nativeEvent({type:'cursor',x:0,y:0,width:1001,height:501});
  await pause(40);assert.equal(received.length,1);
});

test('configured edge dwell enters shared control, moves into target, and returns at opposite edge', async t => {
  const {a,b}=await pair(t);await a.settings({masterId:a.config.id,dwell:100,edges:{right:b.config.id}});
  await until(()=>b.state().masterId===a.config.id&&b.state().layout.revision===a.state().layout.revision);
  let now=1000;a.edge.now=()=>now;a.edge.cooldown=0;
  await a.nativeEvent({type:'cursor',x:1919,y:540,width:1920,height:1080});
  assert.equal(a.active,null);now+=110;
  await a.nativeEvent({type:'cursor',x:1919,y:540,width:1920,height:1080});
  assert.equal(a.active,b.config.id);assert.equal(a.capture,true);
  assert(a.native.calls.some(c=>c.method==='mode'&&c.args.remote));
  const x=a.position.x;await a.nativeEvent({type:'input',kind:'move',dx:240,dy:20});
  await until(()=>!a.moving);assert(a.position.x>x);
  await a.nativeEvent({type:'input',kind:'button',button:0,down:true});
  await a.nativeEvent({type:'input',kind:'button',button:0,down:false});
  assert(b.native.calls.some(c=>c.method==='input'&&c.args.kind==='button'&&c.args.down));
  a.position.x=0;now+=999;a.checkReturnEdge();assert.equal(a.active,b.config.id);now+=1;a.checkReturnEdge();now+=110;a.checkReturnEdge();
  await until(()=>!a.active&&!a.returning);assert.equal(b.owner,null);
  assert.equal(a.native.calls.findLast(c=>c.method==='warp')?.args.x,.985,'shared-layout return enters the adjacent right side of the physical master');
  now+=1500;await a.nativeEvent({type:'cursor',x:1919,y:540,width:1920,height:1080});now+=1000;await a.nativeEvent({type:'cursor',x:1919,y:540,width:1920,height:1080});
  assert.equal(a.active,null,'stationary cursor must not immediately reenter the phone');
});

test('legacy layouts enable host mode only if it was never explicitly configured', () => {
  for(const configured of [undefined,false,true]) {
    const config={id:randomUUID(),token:newToken(),permissions:{},edges:{right:'phone'},...(configured===undefined?{}:{master:configured})};
    const service=new Service(config,'unused',new Native(),{noDiscovery:true});
    assert.equal(service.config.master,configured===undefined?true:configured);
  }
});

test('system input interruption ends the session, keeps grants and waits for a deliberate return', async t => {
  const {a,b}=await pair(t);const id=b.config.id;
  await a.switchTo(id,'right',true,true);
  await a.nativeEvent({type:'input',kind:'key',code:'ControlLeft',down:true});
  await a.nativeEvent({type:'input-interrupted',blocked:true});
  assert.equal(a.active,null);assert.equal(a.capture,false);assert.equal(b.owner,null);
  assert.equal(a.keys.size,0);assert.equal(a.controlEnded.id,id);
  assert.equal(b.consent.allowed(a.config.id,'input'),true);
  assert.ok(a.native.calls.some(c=>c.method==='mode'&&c.args.remote===false));
  await assert.rejects(a.switchTo(id,'right',true,true),/CONTROL_ENDED/);
  await a.nativeEvent({type:'input-restored'});
  assert.equal(a.active,null);assert.equal(a.edge.waitingForLeave,true);
  await a.switchTo(id,'right',true,true);assert.equal(b.owner.id,a.config.id);
  await a.nativeEvent({type:'input-interrupted',blocked:false});
  assert.equal(a.active,null);assert.equal(b.owner,null);assert.equal(b.consent.list().length,0);
});

test('edge glow defaults enabled and records only successful shared-input switches', async t => {
  const {a,b}=await pair(t);assert.equal(a.state().edgeGlowEnabled,true);
  await a.switchTo(b.config.id,'left',false,true);assert.equal(a.state().edgeTransition,undefined);
  await a.returnLocal();await a.settings({edgeGlow:false});assert.equal(a.state().edgeGlowEnabled,false);
  await a.switchTo(b.config.id,'bottom',true,true);assert.equal(a.state().edgeTransition.edge,'bottom');assert.ok(a.state().edgeTransition.id);
  await a.returnLocal();const previous=a.state().edgeTransition.id;
  a.inputDesktopBlocked=true;await assert.rejects(a.switchTo(b.config.id,'left',true,true));assert.equal(a.state().edgeTransition.id,previous);
});

test('switch mode and delay stay local; modifier-gated edges switch without dwell', async t => {
  const {a,b}=await pair(t);a.config.master=true;a.config.edges={right:b.config.id};
  await a.settings({switchMode:'hotkey',edgeHotkey:'ctrl-alt',dwell:0});
  assert.equal(a.state().dwell,0);assert.equal(a.state().switchMode,'hotkey');assert.equal(b.state().switchMode,'dwell');
  const cursor={type:'cursor',x:1919,y:500,width:1920,height:1080};
  a.edge.cooldown=0;await a.nativeEvent(cursor);assert.equal(a.active,null);
  await a.nativeEvent({type:'modifiers',mask:1});await a.nativeEvent(cursor);assert.equal(a.active,null);
  await a.nativeEvent({type:'modifiers',mask:3});await a.nativeEvent(cursor);assert.equal(a.active,b.config.id);
  await a.nativeEvent({type:'modifiers',mask:0});a.position.x=0;a.edge.cooldown=0;a.checkReturnEdge();assert.equal(a.active,b.config.id);
  await a.nativeEvent({type:'modifiers',mask:3});a.checkReturnEdge();await until(()=>!a.active && !a.returning);
  assert.equal(b.owner,null);
  await assert.rejects(a.settings({dwell:-1}));await assert.rejects(a.settings({switchMode:'invalid'}));
  await assert.rejects(a.settings({edgeHotkey:'invalid'}));
});

test('dwell feedback starts only at eligible online edges and cancels on leaving', async t => {
  const {a,b}=await pair(t);a.config.master=true;a.config.edges={right:b.config.id};await a.settings({dwell:1000,switchMode:'dwell'});a.edge.cooldown=0;
  const events=[];a.on('edge-feedback',f=>events.push(f));await a.nativeEvent({type:'cursor',x:1919,y:500,width:1920,height:1080});assert.equal(events.at(-1).phase,'countdown');assert.equal(events.at(-1).progress,0);
  await a.nativeEvent({type:'cursor',x:100,y:500,width:1920,height:1080});assert.equal(events.at(-1).phase,'cancel');assert.equal(a.active,null);
  await a.settings({edgeGlow:false});a.edge.cooldown=0;events.length=0;await a.nativeEvent({type:'cursor',x:1919,y:500,width:1920,height:1080});assert.equal(events.length,0);
});

test('away glow uses only the departure edge and preserves local hide preference', async t => {
  const {a,b}=await pair(t);await a.settings({dwell:0,edgeGlowHideAway:true});
  assert.equal(a.state().edgeGlowHideAway,true);assert.equal(b.state().edgeGlowHideAway,false);
  let now=1000;a.edge.now=()=>now;const events=[];a.on('edge-feedback',f=>events.push(f));
  await a.switchTo(b.config.id,'right',true,true);
  assert.deepEqual(events.at(-1),{phase:'depart',edge:'right',hideAway:true});
  a.position.x=0;now+=999;a.checkReturnEdge();assert.equal(a.active,b.config.id);
  assert.equal(events.some(f=>f.edge==='left'),false);
  now+=1;a.checkReturnEdge();await until(()=>!a.active&&!a.returning);
  assert.deepEqual(events.at(-1),{phase:'return',edge:'right',hideAway:true});
});

test('completed dwell keeps half-bright feedback throughout delayed connection without cancel', async t => {
  const {a,b}=await pair(t);a.config.master=true;a.config.dwell=100;a.config.edges={right:b.config.id};
  let now=1000;a.edge.now=()=>now;a.edge.cooldown=0;
  const events=[];a.on('edge-feedback',f=>events.push(f));
  let proceed;const gate=new Promise(resolve=>{proceed=resolve});a.adbGuide.check=()=>gate;
  const cursor={type:'cursor',x:1919,y:500,width:1920,height:1080};
  await a.nativeEvent(cursor);now+=100;const pending=a.nativeEvent(cursor);
  await until(()=>a.switching);assert.deepEqual(events.at(-1),{phase:'countdown',edge:'right',progress:1});
  assert.equal(events.some(f=>f.phase==='cancel'),false);
  proceed();await pending;assert.equal(events.at(-1).phase,'depart');
  assert.equal(events.some(f=>f.phase==='cancel'),false);
});

test('failed connection clears completed countdown instead of leaving a stuck glow', async t => {
  const {a,b}=await pair(t);a.config.master=true;a.config.dwell=0;a.config.edges={right:b.config.id};a.edge.cooldown=0;
  const events=[];a.on('edge-feedback',f=>events.push(f));a.adbGuide.check=async()=>{throw Error('test unavailable')};
  await assert.rejects(a.nativeEvent({type:'cursor',x:1919,y:500,width:1920,height:1080}),/test unavailable/);
  assert.equal(events.at(-1).phase,'cancel');assert.equal(a.edgeFeedback,null);
});

for (const stage of ['guide','acquire','input']) test('leaving the source edge cancels pending switch during '+stage, async t => {
  const {a,b}=await pair(t);a.config.master=true;a.config.dwell=0;a.config.edges={right:b.config.id};a.edge.cooldown=0;
  let resume,waiting=false;const gate=new Promise(resolve=>{resume=resolve});
  const events=[];a.on('edge-feedback',f=>events.push(f));
  if(stage==='guide')a.adbGuide.check=async()=>{waiting=true;await gate};
  else {const peer=a.getPeer(b.config.id),request=peer.request.bind(peer);peer.request=async(method,args,...rest)=>{const result=await request(method,args,...rest);if(method===stage){waiting=true;await gate}return result};}
  const pending=a.nativeEvent({type:'cursor',x:1919,y:500,width:1920,height:1080});
  const rejected=assert.rejects(pending,/CONTROL_ENDED/);
  await until(()=>waiting);
  await a.nativeEvent({type:'cursor',x:1200,y:500,width:1920,height:1080});
  assert.equal(a.controlIntent.cancelled,true);assert.equal(events.at(-1).phase,'cancel');
  // Coming back before the old reply arrives must not revive the old request.
  await a.nativeEvent({type:'cursor',x:1919,y:500,width:1920,height:1080});
  resume();await rejected;await until(()=>!b.owner);
  assert.equal(a.active,null);assert.equal(a.switching,false);
  assert.equal(events.some(f=>f.phase==='depart'||f.phase==='return'),false);
  assert.equal(a.native.calls.some(c=>c.method==='mode'&&c.args.remote),false);
});

test('group computer master selection enables selected peer and keeps switch preferences local', async t => {
  const {a,b}=await pair(t);await a.settings({masterId:b.config.id,dwell:100});
  await until(()=>b.state().masterId===b.config.id);
  assert.equal(a.state().master,false);assert.equal(b.state().master,true);
  await a.settings({master:true});assert.equal(a.state().master,false,'layout edits must not override chosen master');
  b.config.dwell=700;await b.settings({masterId:a.config.id});await until(()=>a.state().master);
  assert.equal(b.state().master,false);assert.equal(a.state().dwell,100);assert.equal(b.state().dwell,700);
  await assert.rejects(a.settings({masterId:randomUUID()}),/请选择/);
  const phone=randomUUID();a.config.knownDevices[phone]={platform:'android',name:'phone'};
  assert.equal(a.state().masterDevices.some(d=>d.id===phone),false);
  await assert.rejects(a.settings({masterId:phone}),/请选择/);
});

test('master selection reaches an offline computer after reconnect', async t => {
  const {a,b}=await pair(t);a.getPeer(b.config.id).close();await until(()=>!a.peers.size&&!b.peers.size);
  await a.settings({masterId:b.config.id});assert.equal(a.state().master,false);
  a.connect('127.0.0.1',b.port);await until(()=>a.peers.size&&b.peers.size);
  await a.tick();await until(()=>b.state().masterId===b.config.id);assert.equal(b.state().master,true);
});
