import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { randomUUID } from 'node:crypto';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { Service } from '../src/service.js';
import { newToken } from '../src/identity.js';
import { createActions } from '../src/actions.js';

const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(check) { const end = Date.now() + 6000; while (!check()) { if (Date.now() > end) throw Error('mesh condition timed out'); await pause(10); } }
class Native extends EventEmitter {
  constructor(name) { super(); this.name = name; this.calls = []; this.clip = { text: '', files: [] }; this.capabilities = { host: true, input: true, screen: true, clipboard: true, shutdown: true, width: 1000, height: 600 }; }
  async start() {}
  async call(method, args) {
    this.calls.push({ method, args });
    if (method === 'capabilities') return this.capabilities;
    if (method === 'screen') return { data: this.name, width: 1000, height: 600 };
    if (method === 'clipboardGet') return this.clip;
    if (method === 'clipboardSet') this.clip = { text: args.text, files: args.files || [] };
    return {};
  }
  close() {}
}
// Real encrypted sockets and group services, with OS actions replaced by probes.
async function mesh(t) {
  const root = await mkdtemp(path.join(os.tmpdir(), 'lanlink-mesh-')), token = newToken(), devices = [];
  t.after(async () => { await Promise.all(devices.map(s => s.close())); await rm(root, { recursive: true, force: true }); });
  for (const [name, platform] of [['computer-a', 'darwin'], ['computer-b', 'win32'], ['phone-a', 'android'], ['phone-b', 'android']]) {
    const dir = path.join(root, name); await mkdir(dir);
    const s = new Service({ id: randomUUID(), name, token, edges: {}, permissions: { input: true, screen: true, clipboard: true, shutdown: true } }, dir, new Native(name), { host: '127.0.0.1', port: 0, noDiscovery: true, noQuic: true });
    const identity = s.identity.bind(s); s.identity = () => ({ ...identity(), platform });
    if (platform === 'android') s.native.capabilities.inputMode = 'adb/root';
    devices.push(s); await s.start();
  }
  for (let i = 0; i < devices.length; i++) for (let j = i + 1; j < devices.length; j++) devices[i].connect('127.0.0.1', devices[j].port);
  await until(() => devices.every(s => s.peers.size === 3 && [...s.peers.values()].every(p => p.info)));
  return devices;
}

test('two computers control different Android identities independently; contention and revocation stay scoped', async t => {
  const [a, b, phoneA, phoneB] = await mesh(t);
  await Promise.all([a.switchTo(phoneA.config.id, 'right', false), b.switchTo(phoneB.config.id, 'left', false)]);
  await Promise.all([
    a.nativeEvent({ type: 'input', kind: 'key', code: 'KeyA', down: true }),
    b.nativeEvent({ type: 'input', kind: 'key', code: 'KeyB', down: true }),
  ]);
  const keys = s => s.native.calls.filter(c => c.method === 'input' && c.args.kind === 'key').map(c => c.args.code);
  assert.deepEqual(keys(phoneA), ['KeyA']); assert.deepEqual(keys(phoneB), ['KeyB']);
  assert.equal(phoneA.owner.id, a.config.id); assert.equal(phoneB.owner.id, b.config.id);
  await createActions(a)('release', { id: phoneB.config.id });
  assert.equal(a.active, phoneA.config.id, 'a late close from another target must not release the active target');
  await assert.rejects(a.getPeer(phoneB.config.id).request('acquire'), /CONTROL_CONFLICT/);
  assert.equal(phoneB.owner.id, b.config.id);
  assert.equal((await a.getPeer(phoneA.config.id).request('screen')).data, 'phone-a');
  assert.equal((await b.getPeer(phoneB.config.id).request('screen')).data, 'phone-b');
  await phoneA.revokeConsent(a.config.id); await until(() => !a.active);
  assert.equal(b.active, phoneB.config.id); assert.equal(phoneB.owner.id, b.config.id);
  await assert.rejects(a.getPeer(phoneA.config.id).request('acquire'), /CONSENT_DENIED/);
  assert.equal((await b.getPeer(phoneA.config.id).request('permission-status')).permissions.input.peerAllowed, true);
  await b.returnLocal(); await b.switchTo(phoneA.config.id, 'right', false);
  assert.equal(phoneA.owner.id, b.config.id);
  assert.ok([a, b, phoneA, phoneB].every(s => s.state().consentRequests.length === 0));
});

test('simultaneous controllers elect one lease, disconnect only its target, and four-way clipboard keeps its source', async t => {
  const devices = await mesh(t), [a, b, phoneA, phoneB] = devices;
  const result = await Promise.allSettled([a.getPeer(phoneA.config.id).request('acquire'), b.getPeer(phoneA.config.id).request('acquire')]);
  assert.equal(result.filter(r => r.status === 'fulfilled').length, 1);
  assert.match(result.find(r => r.status === 'rejected').reason.message, /CONTROL_CONFLICT/);
  const winner = phoneA.owner.id === a.config.id ? a : b;
  winner.getPeer(phoneA.config.id).close(); await until(() => phoneA.owner === null);
  assert.equal(phoneB.peers.size, 3); assert.equal(phoneB.owner, null);
  winner.connect('127.0.0.1', phoneA.port); await until(() => devices.every(s => s.peers.size === 3));
  phoneB.native.clip = { text: '来自第二台手机', files: [] }; await phoneB.snapshotClipboard();
  await until(() => devices.every(s => s.clipboardSync.latest?.text === '来自第二台手机'));
  assert.ok(devices.every(s => s.clipboardSync.latest.source === phoneB.config.id));
  assert.ok(devices.every(s => s.state().consentRequests.length === 0));
});

test('stale authenticated connection cannot invoke requests after replacement', async t => {
  const [a, b] = await mesh(t), old = a.getPeer(b.config.id);
  a.peers.set(b.config.id, { closed: false });
  await assert.rejects(a.handle(old, 'permission-request', { permissions: ['input'] }), /设备连接已结束/);
  await assert.rejects(a.handle(old, 'acquire', {}), /设备连接已结束/);
  a.peers.set(b.config.id, old);
});

test('closing waits for in-flight authenticated requests before disposing native state and saving configuration', async t => {
  const [a, b] = await mesh(t), original = a.native.call.bind(a.native);
  let enter, release;
  const entered = new Promise(resolve => enter = resolve), gate = new Promise(resolve => release = resolve);
  a.native.call = async (method, args) => { if (method === 'capabilities') { enter(); await gate; } return original(method, args); };
  const request = b.getPeer(a.config.id).request('permission-status').catch(() => {});
  await entered;
  let completed = false;
  const closing = a.close().then(() => { completed = true; });
  try { await pause(40); assert.equal(completed, false); }
  finally { release(); await closing; await request; }
  assert.equal(a.messageTasks.size, 0);
});

test('closing also drains QUIC requests that bypass the TCP message event', async t => {
  const [a, b] = await mesh(t), peer = a.getPeer(b.config.id), original = a.native.call.bind(a.native);
  let enter, release, disposed = false;
  const entered = new Promise(resolve => enter = resolve), gate = new Promise(resolve => release = resolve);
  a.native.call = async (method, args) => { if (method === 'capabilities') { enter(); await gate; assert.equal(disposed, false); } return original(method, args); };
  a.native.close = () => { disposed = true; };
  a.quic.enabled = true; peer.quicConn = 17; a.quic.connections.set(17, peer);
  const request = a.quic.incoming({ conn: 17, stream: 1, data: Buffer.from(JSON.stringify({ method: 'permission-status', args: {} })).toString('base64') });
  await entered;
  let completed = false;
  const closing = a.close().then(() => { completed = true; });
  try { await pause(40); assert.equal(completed, false); assert.equal(disposed, false); }
  finally { release(); await closing; await request; }
  assert.equal(a.messageTasks.size, 0); assert.equal(disposed, true);
});
