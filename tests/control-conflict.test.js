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
  for (const [source, target] of [[a, b], [b, a]]) {
    await assert.rejects(source.getPeer(target.config.id).request('acquire'), /CONSENT_PENDING/);
    await target.decideConsent({ ...target.state().consentRequests[0], accepted: true, permissions: { input: true, screen: true, clipboard: true } });
  }
  return { a, b };
}

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
