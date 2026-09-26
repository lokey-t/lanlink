import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID, createHash } from 'node:crypto';
import { EventEmitter } from 'node:events';
import { mkdtemp, writeFile, readFile, rm, mkdir } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import net from 'node:net';
import { newToken, parseToken, accountId, loadConfig } from '../src/identity.js';
import { seal, unseal, sessionKeys, Peer } from '../src/wire.js';
import { discoveryRecord, verifyRecord } from '../src/discovery.js';
import { EdgeSwitch } from '../src/edge.js';
import { Service, safeName, validateInput } from '../src/service.js';
import { startUI } from '../src/http.js';

const pause = ms => new Promise(r => setTimeout(r, ms));
async function until(check, ms = 3000) { const end = Date.now() + ms; while (!check()) { if (Date.now() > end) throw new Error('Condition timed out'); await pause(10); } }
class FakeNative extends EventEmitter {
  constructor() { super(); this.capabilities = { host: true, input: true, screen: true, clipboard: true, shutdown: true, width: 1920, height: 1080 }; this.clip = { text: '', files: [] }; this.calls = []; }
  async start() {}
  async call(method, args) { this.calls.push({ method, args }); if (method === 'clipboardGet') return this.clip; if (method === 'clipboardSet') this.clip = { text: args.text || '', files: args.files || [] }; if (method === 'screen') return { mime: 'image/jpeg', data: 'fixture', width: 10, height: 10 }; return {}; }
  close() {}
}
async function pair(t, permissions = { input: true, screen: true, clipboard: true, shutdown: true }, approve = true) {
  const folder = await mkdtemp(path.join(os.tmpdir(), 'lanlink-test-')); const token = newToken(); const services = [];
  for (const name of ['A', 'B']) { const dir = path.join(folder, name); await mkdir(dir); const config = { id: randomUUID(), name, token, dwell: 450, edges: {}, master: true, permissions: { ...permissions } }; const s = new Service(config, dir, new FakeNative(), { port: 0, host: '127.0.0.1', noDiscovery: true }); await s.start(); services.push(s); }
  t.after(async () => { await Promise.all(services.map(s => s.close())); await rm(folder, { recursive: true, force: true }); });
  services[0].connect('127.0.0.1', services[1].port); await until(() => services.every(s => s.peers.size === 1));
  if (approve) for (const [source, target] of [[services[0], services[1]], [services[1], services[0]]]) {
    await assert.rejects(source.getPeer(target.config.id).request('acquire'), /CONSENT_PENDING/);
    const request = target.state().consentRequests[0];
    await target.decideConsent({ ...request, accepted: true, permissions: { input: true, screen: true, clipboard: true, shutdown: true } });
  }
  return { a: services[0], b: services[1], folder };
}
test('first control is blocked until local decision and only selected permissions persist', async t => {
  const { a, b } = await pair(t, undefined, false), p = a.getPeer(b.config.id);
  const baseline = b.native.calls.length;
  await assert.rejects(p.request('acquire'), /CONSENT_PENDING/);
  await assert.rejects(p.request('screen'), /CONSENT_PENDING/);
  await assert.rejects(p.request('clipboardGet'), /CONSENT_PENDING/);
  await assert.rejects(p.request('shutdown'), /CONSENT_PENDING/);
  assert.equal(b.state().consentRequests.length, 1); assert.equal(b.owner, null); assert.equal(b.shutdownAt, undefined);
  assert.equal(b.native.calls.length, baseline);
  const request = b.state().consentRequests[0];
  await assert.rejects(b.decideConsent({ ...request, requestId: 'stale', accepted: true }), /请求已结束/);
  await b.decideConsent({ ...request, accepted: true, permissions: { input: true, screen: false, clipboard: false, shutdown: false } });
  await a.switchTo(b.config.id, 'right', false);
  for (const method of ['screen', 'clipboardGet', 'shutdown']) await assert.rejects(p.request(method), /CONSENT_DENIED/);
  assert.equal(b.state().consentRequests.length, 0);
  assert.equal((await loadConfig(b.dir)).deviceGrants[a.config.id].input, true);
  await a.returnLocal(); p.close(); await until(() => !a.peers.size && !b.peers.size);
  a.connect('127.0.0.1', b.port); await until(() => a.peers.size && b.peers.size);
  await a.switchTo(b.config.id, 'right', false); assert.equal(b.state().consentRequests.length, 0);
  await b.revokeConsent(a.config.id); assert.equal(b.owner, null);
  await assert.rejects(a.getPeer(b.config.id).request('input', { kind: 'key', code: 'KeyA', down: true }), /CONSENT_PENDING/);
});

test('offline identity persists and rejects malformed / noncanonical tokens', async t => {
  const folder = await mkdtemp(path.join(os.tmpdir(), 'lanlink-identity-')); t.after(() => rm(folder, { recursive: true, force: true }));
  const a = await loadConfig(folder), b = await loadConfig(folder); assert.deepEqual(a, b); assert.equal(parseToken(a.token).length, 32); assert.equal(accountId(a.token), accountId(b.token));
  assert.deepEqual(a.permissions, { input: true, screen: true, clipboard: true, shutdown: true });
  for (const bad of ['', '123456', 'lanlink1:password', 'lanlink1:' + 'A'.repeat(42) + 'B']) assert.throws(() => parseToken(bad));
});

test('approval enables selected app features; target termination blocks automatic reacquire until a new local approval', async t => {
  const {a,b}=await pair(t,{input:false,screen:false,clipboard:false,shutdown:false},false);
  const p=a.getPeer(b.config.id);
  await assert.rejects(p.request('acquire'),/CONSENT_PENDING/);
  await b.decideConsent({...b.state().consentRequests[0],accepted:true,permissions:{input:true,screen:true,clipboard:false,shutdown:false}});
  assert.deepEqual(b.config.permissions,{input:true,screen:true,clipboard:false,shutdown:false});
  await a.switchTo(b.config.id,'right',false,true); await p.request('input',{kind:'relative',dx:30,dy:-12});
  await b.stopInbound(); await until(()=>a.controlEnded?.id===b.config.id&&!a.active);
  assert.equal(b.owner,null); assert.ok(b.native.calls.some(c=>c.method==='release'));
  for(const method of ['acquire','screen','videoOpen']) await assert.rejects(p.request(method),/CONTROL_ENDED/);
  await assert.rejects(p.request('acquire',{newSession:true}),/CONSENT_PENDING/);
  await b.decideConsent({...b.state().consentRequests[0],accepted:true,permissions:{input:true}});
  await p.request('acquire'); assert.equal(b.owner.id,a.config.id);
});

test('online removal disconnects the real encrypted peer, resets its group and invalidates the old credential', async t => {
  const {a,b}=await pair(t), old=b.config.token, oldGroup=b.group.data.id;
  await until(()=>a.group.data.members[b.config.id]&&b.group.data.members[a.config.id]);
  await a.group.remove(b.config.id);
  await until(()=>a.peers.size===0&&b.peers.size===0&&b.group.data.id!==oldGroup);
  assert.notEqual(a.config.token,old);assert.notEqual(b.config.token,old);assert.notEqual(a.config.token,b.config.token);
  assert.deepEqual(b.config.knownDevices,{});assert.ok(a.group.excluded(b.config.id));
  b.connect('127.0.0.1',a.port);await pause(150);assert.equal(a.peers.size,0);assert.equal(b.peers.size,0);
  // A fresh, explicit pairing is the only route back to the remaining group.
  const invitation=a.pairing.show();
  const {exchange}=await import('../src/pairing.js');
  const joined=await exchange({id:invitation.id,host:'127.0.0.1',port:a.pairing.port},invitation.code,b.group.own);
  assert.equal(joined.token,a.config.token);assert.equal(a.group.excluded(b.config.id),false);
});

test('locally initiated control supports clipboard return without granting reverse control', async t => {
  const { a, b } = await pair(t, undefined, false);
  await assert.rejects(a.getPeer(b.config.id).request('acquire'), /CONSENT_PENDING/);
  await b.decideConsent({ ...b.state().consentRequests[0], accepted: true, permissions: { input: true, clipboard: true } });
  await a.switchTo(b.config.id, 'right', false);
  a.native.clip = { text: '发送', files: [] }; await a.snapshotClipboard(); await until(() => b.clipboard?.text === '发送');
  b.native.clip = { text: '返回', files: [] }; await b.snapshotClipboard(); await until(() => a.clipboard?.text === '返回');
  assert.equal(a.consent.allowed(b.config.id, 'input'), false);
  await assert.rejects(b.getPeer(a.config.id).request('acquire'), /CONTROL_CONFLICT/);
  await a.returnLocal(); assert.equal(a.native.clip.text, '返回');
  assert.equal(a.canShareClipboard(b.config.id), false);
  await assert.rejects(b.getPeer(a.config.id).request('acquire'), /CONSENT_PENDING/);
});

test('paired device metadata survives disconnect and identity change clears the layout', async t => {
  const { a, b } = await pair(t);
  await until(() => a.config.knownDevices?.[b.config.id]?.name === 'B');
  // A second save is sequenced behind discovery metadata persistence.
  await a.settings({ edges: { right: b.config.id } });
  const persisted = await loadConfig(a.dir);
  assert.equal(persisted.knownDevices[b.config.id].name, 'B');
  a.getPeer(b.config.id).close(); await until(() => !a.peers.size);
  assert.equal(a.state().knownDevices[b.config.id].platform, process.platform);
  assert.equal(a.state().edges.right, b.config.id);
  const { createActions } = await import('../src/actions.js');
  await createActions(a)('join', { token: newToken(), dissolveCurrentGroup: true });
  assert.deepEqual(a.state().knownDevices, {}); assert.deepEqual(a.state().edges, {});
});
test('directional keys agree, ciphertext authenticates sequence and content', () => {
  const secret = randomBytes(32), a = { id: '00000000-0000-0000-0000-000000000001', nonce: '11'.repeat(32) }, b = { id: '00000000-0000-0000-0000-000000000002', nonce: '22'.repeat(32) };
  const ka = sessionKeys(secret, a, b), kb = sessionKeys(secret, b, a); assert.deepEqual(ka.tx, kb.rx); assert.notDeepEqual(ka.tx, kb.tx);
  const body = seal(ka.tx, 0, { text: '你好 👋' }); assert.deepEqual(unseal(kb.rx, 0, body), { text: '你好 👋' });
  assert.throws(() => unseal(kb.rx, 1, body)); assert.throws(() => unseal(randomBytes(32), 0, body));
  const bytes = Buffer.from(body, 'base64'); bytes[0] ^= 1; assert.throws(() => unseal(kb.rx, 0, bytes.toString('base64')));
});
test('discovery requires account proof, fresh timestamp and valid port', () => {
  const secret = randomBytes(32), record = discoveryRecord({ id: randomUUID() }, secret, 45831, 10000);
  assert.equal(verifyRecord(record, secret, 11000).port, 45831); assert.equal(verifyRecord(record, randomBytes(32), 11000), null); assert.equal(verifyRecord(record, secret, 41000), null);
  record.body = record.body.replace('45831', '45832'); assert.equal(verifyRecord(record, secret, 11000), null);
});
test('edge dwell resets on leaving, changing target and cooldown', () => {
  let time = 0; const e = new EdgeSwitch(() => time), edges = { right: 'B' };
  assert.equal(e.update(1919, 50, 1920, 1080, edges, 450), null); time = 449; assert.equal(e.update(1919, 50, 1920, 1080, edges, 450), null);
  e.update(1000, 50, 1920, 1080, edges, 450); time = 500; e.update(1919, 50, 1920, 1080, edges, 450); time = 950;
  assert.deepEqual(e.update(1919, 50, 1920, 1080, edges, 450), { edge: 'right', target: 'B' }); time = 1000; assert.equal(e.update(1919, 50, 1920, 1080, edges, 450), null);
});
test('unsafe paths, invalid inputs and non-finite coordinates rejected', () => {
  for (const name of ['../escape', '..', 'C:\\boot.ini', 'CON', 'LPT1.txt', 'x/y', 'bad\0name', 'trailing.']) assert.throws(() => safeName(name));
  assert.equal(safeName('测试 report.txt'), '测试 report.txt');
  for (const input of [{ kind: 'move', x: Infinity, y: 0 }, { kind: 'move', x: -1, y: 0 }, { kind: 'key', code: 'shell', down: true }, { kind: 'button', button: 4, down: true }, { kind: 'wheel', delta: 1e9 }]) assert.throws(() => validateInput(input));
});
test('encrypted loopback pair enforces control owner and permission revocation', async t => {
  const { a, b } = await pair(t); const p = a.getPeer(b.config.id);
  await assert.rejects(p.request('input', { kind: 'key', code: 'KeyA', down: true }), /控制权/);
  await a.switchTo(b.config.id); await p.request('input', { kind: 'key', code: 'KeyA', down: true }); assert(b.native.calls.some(c => c.method === 'input' && c.args.code === 'KeyA'));
  await b.settings({ permissions: { input: false } }); await assert.rejects(p.request('input', { kind: 'key', code: 'KeyA', down: false }), /允许/); assert.equal(b.owner, null);
  await a.returnLocal(); assert.equal(a.active, null); assert(a.native.calls.some(c => c.method === 'mode' && !c.args.remote));
});
test('file clipboard transfers multiple chunks with preserved name and matching bytes', async t => {
  const { a, b, folder } = await pair(t); const file = path.join(folder, '文档.bin'), bytes = randomBytes(500000); await writeFile(file, bytes); a.native.clip = { text: '', files: [file] };
  const offer = await a.snapshotClipboard(); await until(() => b.clipboard?.id === offer.id); await b.materialize(b.clipboard);
  assert.equal(path.basename(b.native.clip.files[0]), '文档.bin'); assert.deepEqual(await readFile(b.native.clip.files[0]), bytes);
  await writeFile(file, 'changed'); await assert.rejects(a.fileChunk({ offerId: offer.id, fileId: offer.files[0].id, offset: 0 }), /变化/);
  await assert.rejects(a.fileChunk({ offerId: offer.id, fileId: 'unissued', offset: 0 }), /过期/);
});
test('remote copy is placed on local clipboard when returning from shared input', async t => {
  const { a, b } = await pair(t); await a.switchTo(b.config.id); b.native.clip = { text: '从远端复制的中文', files: [] }; await b.snapshotClipboard(); await until(() => a.clipboard?.source === b.config.id); await a.returnLocal(); assert.equal(a.native.clip.text, '从远端复制的中文');
});
test('screen permission and shutdown cancellation do not invoke native shutdown', async t => {
  const { a, b } = await pair(t); const p = a.getPeer(b.config.id); await b.settings({ permissions: { screen: false } }); await assert.rejects(p.request('screen'), /允许/);
  await p.request('shutdown'); assert(b.shutdownAt > Date.now()); await p.request('cancelShutdown'); assert.equal(b.shutdownAt, null); assert(!b.native.calls.some(c => c.method === 'shutdown'));
});
test('disconnect releases native keys and restores controlling computer', async t => {
  const { a, b } = await pair(t); await a.switchTo(b.config.id); a.getPeer(b.config.id).close(); await until(() => !a.active && !b.owner); assert(b.native.calls.some(c => c.method === 'release'));
});
test('stationary remote cursor returns after dwell without additional movement events', async t => {
  const { a, b } = await pair(t); a.config.dwell = 100; await a.switchTo(b.config.id, 'right'); a.position.x = 0; a.position.y = .5;
  await until(() => a.active === null, 1800); assert(a.native.calls.some(c => c.method === 'mode' && c.args.remote === false));
});
test('simultaneous duplicate connections converge without losing the peer', async t => {
  const { a, b } = await pair(t); b.connect('127.0.0.1', a.port); await pause(150); assert.equal(a.peers.size, 1); assert.equal(b.peers.size, 1);
  assert.deepEqual(await a.getPeer(b.config.id).request('screen'), { mime: 'image/jpeg', data: 'fixture', width: 10, height: 10 });
});
test('a wrong account never reaches authenticated ready', async t => {
  const server = net.createServer(); await new Promise(r => server.listen(0, '127.0.0.1', r)); t.after(() => new Promise(r => server.close(r)));
  let ready = false; server.on('connection', s => { const p = new Peer(s, { id: randomUUID() }, randomBytes(32)); p.on('ready', () => ready = true); });
  const s = net.connect(server.address().port, '127.0.0.1'); await new Promise(r => s.once('connect', r)); const p = new Peer(s, { id: randomUUID() }, randomBytes(32)); p.on('ready', () => ready = true); await new Promise(r => p.once('closed', r)); assert.equal(ready, false);
});
test('local console rejects missing bearer and cross-origin requests', async t => {
  const { a } = await pair(t); const ui = await startUI(a, 0); t.after(() => new Promise(r => ui.server.close(r))); const base = ui.url.split('/#')[0];
  assert.equal((await fetch(base + '/api/state')).status, 401);
  assert.equal((await fetch(base + '/api/state', { headers: { 'X-LanLink-Token': ui.token, Origin: 'http://evil.invalid' } })).status, 403);
  const r = await fetch(base + '/api/state', { headers: { 'X-LanLink-Token': ui.token } }); assert.equal(r.status, 200); assert.equal((await r.json()).name, 'A');
  const page = await fetch(base); assert.match(await page.text(), /LanLink/); assert.match(page.headers.get('content-security-policy'), /frame-ancestors 'none'/);
});
