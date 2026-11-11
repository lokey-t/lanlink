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
import { MotionSender, MotionReceiver } from '../src/quic-motion.js';
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
  services[0].connect('127.0.0.1', services[1].port); await until(() => services.every(s => s.peers.size === 1&&[...s.peers.values()].every(p=>p.info)));
  await services[0].settings({masterId:services[0].config.id});await until(()=>services.every(s=>s.state().masterId===services[0].config.id));
  return { a: services[0], b: services[1], folder };
}
test('authenticated group requests work immediately, while explicit restrictions persist after reconnect', async t => {
  const { a, b } = await pair(t), p = a.getPeer(b.config.id);
  const report = await p.request('permission-status');
  assert.ok(Object.values(report.permissions).every(permission => permission.peerAllowed));
  assert.equal((await p.request('permission-request', { permissions: ['input', 'screen'] })).ready, true);
  await a.switchTo(b.config.id, 'right', false);
  assert.equal(b.owner.id, a.config.id);
  assert.equal((await p.request('screen')).data, 'fixture');
  assert.equal((await p.request('clipboardGet')).text, '');
  assert.ok((await p.request('shutdown')).at); b.cancelShutdown();
  assert.equal(b.state().consentRequests.length, 0);
  assert.deepEqual(b.config.deviceGrants, {});
  await b.revokeConsent(a.config.id); await until(() => !a.active);
  assert.equal(b.owner, null);
  for (const method of ['acquire', 'screen', 'clipboardGet', 'shutdown']) await assert.rejects(p.request(method), /CONSENT_DENIED/);
  await p.request('permission-request', { permissions: ['input'] });
  const request = b.state().consentRequests[0];
  await assert.rejects(b.decideConsent({ ...request, requestId: 'stale', accepted: true }), /请求已结束/);
  await b.decideConsent({ ...request, accepted: true, permissions: { input: true } });
  await a.switchTo(b.config.id, 'right', false);
  for (const method of ['screen', 'clipboardGet', 'shutdown']) await assert.rejects(p.request(method), /CONSENT_DENIED/);
  assert.equal((await loadConfig(b.dir)).deviceGrants[a.config.id].input, true);
  await a.returnLocal(); p.close(); await until(() => !a.peers.size && !b.peers.size);
  a.connect('127.0.0.1', b.port); await until(() => a.peers.size && b.peers.size);
  await a.switchTo(b.config.id, 'right', false); assert.equal(b.state().consentRequests.length, 0);
  await b.revokeConsent(a.config.id); assert.equal(b.owner, null);
  await assert.rejects(a.getPeer(b.config.id).request('input', { kind: 'key', code: 'KeyA', down: true }), /CONSENT_DENIED/);
});

test('offline identity persists and rejects malformed / noncanonical tokens', async t => {
  const folder = await mkdtemp(path.join(os.tmpdir(), 'lanlink-identity-')); t.after(() => rm(folder, { recursive: true, force: true }));
  const a = await loadConfig(folder), b = await loadConfig(folder); assert.deepEqual(a, b); assert.equal(a.dwell,100); assert.equal(parseToken(a.token).length, 32); assert.equal(accountId(a.token), accountId(b.token));
  assert.deepEqual(a.permissions, { input: true, screen: true, clipboard: true, shutdown: true });
  for (const bad of ['', '123456', 'lanlink1:password', 'lanlink1:' + 'A'.repeat(42) + 'B']) assert.throws(() => parseToken(bad));
});

test('ending remote control leaves independently authorized file transfers available',async t=>{
  const {a,b,folder}=await pair(t);
  await a.switchTo(b.config.id,'right',false,true);
  const endedSession=a.activeSession.edgeSession;
  await b.stopInbound();
  await until(()=>!a.active);
  const peer=a.getPeer(b.config.id);
  await assert.rejects(peer.request('prepare',{pointer:false,edgeSession:endedSession}),/CONTROL_ENDED/);
  const file=path.join(folder,'after-control.txt');
  await writeFile(file,'independent file sharing');
  await a.staging.share(b.config.id,[file]);
  await until(()=>b.staging.list()[0]?.state==='ready');
  const received=await b.staging.ready(b.staging.list()[0].id);
  assert.equal(await readFile(received.path,'utf8'),'independent file sharing');
  await b.settings({permissions:{clipboard:false}});
  await assert.rejects(a.staging.share(b.config.id,[file]),/未允许|未授权|PERMISSION_REQUIRED/);
  assert.equal(b.config.pausedPeers,undefined);
});

test('target termination rejects only the ended session and permits the next automatic session', async t => {
  const {a,b}=await pair(t,{input:false,screen:false,clipboard:false,shutdown:false},false);
  const p=a.getPeer(b.config.id);
  await assert.rejects(p.request('acquire'),/PERMISSION_REQUIRED/);
  const incoming=b.getPeer(a.config.id),send=incoming.send.bind(incoming),ended=[];
  incoming.send=message=>{if(message.type==='control-ended')ended.push(message);return send(message);};
  await b.revokeConsent(a.config.id);
  assert.equal(ended.length,0,'revoking an idle peer must not send a late end notification that cancels its next approved session');
  await p.request('permission-request', {permissions:['input','screen']});
  await b.decideConsent({...b.state().consentRequests[0],accepted:true,permissions:{input:true,screen:true,clipboard:false,shutdown:false}});
  assert.deepEqual(b.config.permissions,{input:true,screen:true,clipboard:false,shutdown:false});
  await a.switchTo(b.config.id,'right',false,true); const oldSession=a.activeSession.edgeSession; await p.request('input',{kind:'relative',dx:30,dy:-12});
  await b.stopInbound(); await until(()=>a.controlEnded?.id===b.config.id&&!a.active);
  assert.equal(b.owner,null); assert.ok(b.native.calls.some(c=>c.method==='release'));
  for(const method of ['prepare','acquire']) await assert.rejects(p.request(method,{edgeSession:oldSession}),/CONTROL_ENDED/);
  await assert.rejects(p.request('keepControl'),/控制权/);
  assert.equal((await p.request('screen')).data,'fixture');
  await p.request('screenStop');
  const report=await p.request('permission-status');assert.equal(report.permissions.input.peerAllowed,true);assert.equal(report.permissions.clipboard.peerAllowed,false);
  await until(()=>!a.returning);
  await a.switchTo(b.config.id,'right',false);
  assert.equal(b.owner.id,a.config.id);
  assert.notEqual(b.owner.edgeSession,oldSession);
  await p.request('release',{edgeSession:oldSession});
  assert.equal(b.owner.edgeSession,a.activeSession.edgeSession);
  await a.message(p,{type:'control-ended',session:oldSession});
  assert.equal(a.active,b.config.id);
  assert.equal(b.state().consentRequests.length,0);
  assert.equal((await loadConfig(b.dir)).deviceGrants[a.config.id].input,true);
  await assert.rejects(p.request('clipboardGet'),/CONSENT_DENIED/);
  await b.revokeConsent(a.config.id);
  await assert.rejects(p.request('acquire',{newSession:true}),/CONSENT_DENIED/);
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
  a.config.deviceGrants[b.config.id] = { input: false, clipboard: true };
  await a.switchTo(b.config.id, 'right', false);
  a.native.clip = { text: '发送', files: [] }; await a.snapshotClipboard(); await until(() => b.clipboard?.text === '发送');
  b.native.clip = { text: '返回', files: [] }; await b.snapshotClipboard(); await until(() => a.clipboard?.text === '返回');
  assert.equal(a.consent.allowed(b.config.id, 'input'), false);
  await assert.rejects(b.getPeer(a.config.id).request('acquire'), /CONTROL_CONFLICT/);
  await a.returnLocal(); assert.equal(a.native.clip.text, '返回');
  assert.equal(a.canShareClipboard(b.config.id), true);
  await assert.rejects(b.getPeer(a.config.id).request('acquire'), /CONSENT_DENIED/);
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
test('returning to an unchanged edge requires leaving before a new capture', () => {
  let now=1000;const e=new EdgeSwitch(()=>now),edges={right:'phone'};
  e.waitForLeave();now+=1000;
  assert.equal(e.update(1919,500,1920,1080,edges,100),null);now+=1000;
  assert.equal(e.update(1919,500,1920,1080,edges,100),null);
  e.update(1500,500,1920,1080,edges,100);e.update(1919,500,1920,1080,edges,100);now+=101;
  assert.deepEqual(e.update(1919,500,1920,1080,edges,100),{edge:'right',target:'phone'});
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
  const range = { offerId: offer.id, fileId: offer.files[0].id, offset: 7, maxBytes: 1024 };
  assert.deepEqual(await a.fileBytes(range), bytes.subarray(7, 1031));
  assert.deepEqual(await a.fileBytes({ ...range, end: 17 }), bytes.subarray(7, 17));
  for (const maxBytes of [0, -1, 192 * 1024 + 1, 1.5, '1024']) await assert.rejects(a.fileBytes({ ...range, maxBytes }), /范围/);
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

test('Datagram recovery at a boundary cannot become a local edge switch after release', async t => {
  const {a,b}=await pair(t);await a.switchTo(b.config.id,'right',false);
  const sender=new MotionSender(),receiver=new MotionReceiver();sender.move({kind:'relative',dx:700,dy:0});const recovered=receiver.accept(sender.move({kind:'relative',dx:600,dy:0}));
  assert.equal(recovered.reduce((n,m)=>n+m.dx,0),1300);
  for(const input of recovered)await b.handle(b.getPeer(a.config.id),'input',input);
  assert.equal(b.edge.waitingForLeave,true);await a.returnLocal();
  await b.settings({masterId:b.config.id,edges:{right:a.config.id},dwell:0});await until(()=>a.state().masterId===b.config.id);b.edge.cooldown=0;
  const boundary={type:'cursor',x:1919,y:540,width:1920,height:1080};
  await b.nativeEvent({...boundary,synthetic:true});await b.nativeEvent(boundary);
  assert.equal(b.active,null);assert.equal(b.edge.progress,null);
  await b.nativeEvent({...boundary,x:960});await b.nativeEvent(boundary);
  assert.equal(b.active,a.config.id,'a new physical approach after leaving must still switch');
});

test('injected pointer samples do not advance the captured return edge',async t=>{
  const {a,b}=await pair(t);await a.switchTo(b.config.id,'right');const before={...a.position};
  await a.nativeEvent({type:'input',kind:'move',dx:-1000,dy:0,synthetic:true});
  assert.deepEqual(a.position,before);assert.ok(!a.pendingMove);
  await a.nativeEvent({type:'input',kind:'move',dx:20,dy:0});assert.ok(a.position.x>before.x);
});

test('controlled device receives only its outgoing edge countdown and a hidden-away departure',async t=>{
  const {a,b}=await pair(t);clearInterval(a.edgeTimer);a.config.dwell=100;
  let clock=0;a.edge.now=()=>clock;const feedback=[];b.on('edge-feedback',m=>feedback.push(m));
  await a.switchTo(b.config.id,'right');a.edge.cooldown=0;feedback.length=0;a.position={x:0,y:.5};
  a.checkReturnEdge();await until(()=>feedback.some(m=>m.phase==='countdown'&&m.progress===0));
  clock=50;a.activeSession.glowAt=0;a.checkReturnEdge();await until(()=>feedback.some(m=>m.phase==='countdown'&&m.progress===.5));
  a.position.x=.5;a.checkReturnEdge();await until(()=>feedback.some(m=>m.phase==='cancel'));assert.equal(a.active,b.config.id);
  a.position.x=0;clock=100;a.activeSession.glowAt=0;a.checkReturnEdge();await until(()=>feedback.filter(m=>m.phase==='countdown'&&m.progress===0).length===2);
  clock=200;a.checkReturnEdge();await until(()=>a.active===null&&feedback.some(m=>m.phase==='depart'));
  assert(feedback.filter(m=>m.edge).every(m=>m.edge==='left'));assert.equal(feedback.at(-1).phase,'depart');assert.equal(feedback.at(-1).hideAway,true);assert.equal(b.owner,null);
  const count=feedback.length;await b.message(b.getPeer(a.config.id),{type:'edge-glow',phase:'countdown',edge:'left',session:'old-session',seq:999,progress:1});assert.equal(feedback.length,count);
});

test('remote glow rejects wrong sessions, reordering, view-only control and revoked permission',async t=>{
  const {a,b}=await pair(t);await a.switchTo(b.config.id,'right');const peer=b.getPeer(a.config.id),session=a.activeSession.edgeSession;
  const feedback={phase:'countdown',edge:'left',progress:.4,seq:2,session};
  assert.equal(b.inboundEdge(peer,{...feedback,session:'wrong'}),false);assert.equal(b.inboundEdge(peer,feedback),true);assert.equal(b.inboundEdge(peer,{...feedback,seq:1}),false);
  assert.equal(b.inboundEdge(peer,{...feedback,seq:3,progress:NaN}),false);b.config.permissions.input=false;assert.equal(b.inboundEdge(peer,{...feedback,seq:3}),false);b.config.permissions.input=true;
  await a.returnLocal();await a.switchTo(b.config.id,'right',false);assert.equal(b.inboundEdge(peer,{...feedback,session:a.activeSession.edgeSession}),false);
});

test('return with an earlier permission guide completes before immediately controlling again',async t=>{
  const {a,b}=await pair(t);await a.switchTo(b.config.id,'right',false);
  a.access.notice={id:b.config.id,requestId:'earlier-guide'};
  await a.returnLocal();assert.equal(a.returning,0);assert.equal(a.active,null);
  await a.switchTo(b.config.id,'right',false);assert.equal(a.active,b.config.id);assert.equal(a.access.notice,null);
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

test('group log export merges the latest 100 entries per device and reports offline members', async t => {
  const { a, b } = await pair(t);
  a.log('local-entry');
  for (let i = 0; i < 101; i++) b.log('remote-entry-' + i + ';');

  const ui = await startUI(a, 0);
  t.after(() => new Promise(resolve => ui.server.close(resolve)));
  const endpoint = ui.url.split('/#')[0] + '/api/logs-export';
  assert.equal((await fetch(endpoint, { method: 'POST' })).status, 401);
  const exportGroup = async () => {
    const response = await fetch(endpoint, { method: 'POST', headers: { 'X-LanLink-Token': ui.token } });
    assert.equal(response.status, 200);
    return response.json();
  };

  const complete = await exportGroup();
  assert.equal(complete.incomplete, false);
  assert.match(complete.name, /^LanLink-logs-.*\.txt$/);
  assert.match(complete.text, /local-entry/);
  assert.match(complete.text, /remote-entry-100;/);
  assert.doesNotMatch(complete.text, /remote-entry-0;/);
  assert.equal(complete.text.match(/remote-entry-/g).length, 100);

  const handle = b.handle.bind(b);
  b.handle = (peer, method, args) => {
    if (method === 'runtime-logs') throw Error('Unsupported operation');
    return handle(peer, method, args);
  };
  const unsupported = await exportGroup();
  assert.equal(unsupported.incomplete, true);
  assert.match(unsupported.text, /=== B .* ===\nLogs unavailable/);
  assert.match(unsupported.text, /local-entry/);

  a.getPeer(b.config.id).close();
  await until(() => !a.peers.has(b.config.id));
  const partial = await exportGroup();
  assert.equal(partial.incomplete, true);
  assert.match(partial.text, /=== B .* ===\nOffline/);
});

test('zero dwell switches on the first eligible edge sample; leaving cancels progress', () => {
  let t=0;const e=new EdgeSwitch(()=>t),edges={right:'B'};
  assert.deepEqual(e.update(99,50,100,100,edges,0),{edge:'right',target:'B'});
  t=1000;e.update(99,50,100,100,edges,1000);t=1500;e.update(99,50,100,100,edges,1000);assert.equal(e.progress.progress,.5);
  e.update(50,50,100,100,edges,1000);assert.equal(e.progress,null);
  t=1600;e.update(99,50,100,100,edges,1000);assert.equal(e.progress.progress,0);
  e.update(99,50,100,100,edges,1000,false);assert.equal(e.progress,null);
});

test('appearance is validated, persisted locally, and never changes a paired device', async t => {
  const {a,b}=await pair(t);
  assert.equal(a.state().theme,'system');
  assert.equal(b.state().theme,'system');
  for(const theme of ['dark','light','system']) {
    await a.settings({theme});
    assert.equal(a.state().theme,theme);
    assert.equal((await loadConfig(a.dir)).theme,theme);
    assert.equal(b.state().theme,'system');
  }
  await assert.rejects(a.settings({theme:'invalid'}),/主题/);
  assert.equal(a.state().theme,'system');
  assert.equal((await loadConfig(a.dir)).theme,'system');
});

test('permission matrix opens the guide on the selected peer and requires local confirmation to restore revoked access', async t => {
  const {a:source,b:target} = await pair(t);
  const {createActions} = await import('../src/actions.js');
  const invoke = createActions(source), confirm = createActions(target);
  const matrix = await confirm('group-permissions', {});
  assert.equal(matrix.devices[0].id, source.config.id);
  assert.equal(matrix.devices[0].master, true);
  assert.equal(matrix.devices.find(device=>device.id === target.config.id).master, false);
  await target.settings({permissions:{screen:false}});
  await target.setConsent(source.config.id,{input:false,screen:false});
  const result=await invoke('permission-guide-open',{id:target.config.id,permission:'screen'});
  assert.equal(result.remote,true);
  assert.equal(source.permissionNotice,null);
  assert.equal(target.permissionNotice.permission,'screen');
  assert.equal(target.config.permissions.screen,false);
  const requestId=target.permissionNotice.requestId;
  await confirm('permission-guide-enable',{requestId});
  assert.equal(target.config.permissions.screen,true);
  assert.equal(target.consent.allowed(source.config.id,'screen'),true);
  await invoke('permission-toggle',{id:target.config.id,permission:'screen',enabled:false});
  assert.equal(target.consent.allowed(source.config.id,'screen'),false);
  const pending=await invoke('permission-toggle',{id:target.config.id,permission:'screen',enabled:true});
  assert.equal(pending.guideRequired,true);
  assert.equal(target.consent.allowed(source.config.id,'screen'),false);
  await confirm('permission-dismiss',{requestId});
  assert.notEqual(target.permissionNotice,null,'an old dialog cannot dismiss the new request');
  await confirm('permission-guide-enable',{requestId:target.permissionNotice.requestId});
  assert.equal(target.consent.allowed(source.config.id,'screen'),true);
  assert.equal(target.consent.allowed(source.config.id,'input'),false,'other permissions remain disabled');
});

async function bluetoothFixture(t) {
  const { a, b } = await pair(t);
  let connected = true;
  const original = a.native.call.bind(a.native);
  a.native.call = async (method, args) => {
    await original(method, args);
    if (method === 'hid-status') return {
      adapterPresent:true, peripheralSupported:true, hidServiceReady:true,
      hosts:connected ? [{id:'fixture-host', bonded:true, hostConnected:true, reportSubscribed:true, ready:true}] : [],
    };
    if (method === 'hid-send' && !connected) throw Error('Disconnected');
    return {};
  };
  const id = b.config.id;
  const {hosts:[host]} = await a.inputTransport.refresh();
  await a.inputTransport.save(id, {host:host.id, confirmed:true});
  a.native.calls.length = 0;
  return {a,b,id, disconnect:() => {connected = false;}};
}

test('Bluetooth carries both mouse and keyboard without network injection and preserves group revocation', async t => {
  const {a,b,id} = await bluetoothFixture(t);
  b.native.capabilities.input = false;
  await a.switchTo(id);
  assert.equal(b.owner.transport,'bluetooth');
  assert.equal(a.state().inputTransport.targets[0].active,'bluetooth');
  assert.equal(a.state().inputTransport.targets[0].availability,'bluetooth_only');
  await a.nativeEvent({type:'input',kind:'move',dx:300,dy:-140});
  await a.nativeEvent({type:'input',kind:'key',code:'KeyA',down:true});
  await assert.rejects(a.getPeer(id).request('input',{kind:'button',button:0,down:true}), /蓝牙键鼠/);
  assert.equal(b.native.calls.some(call=>call.method==='input'),false);
  const reports = a.native.calls.filter(call=>call.method==='hid-send').map(call=>call.args);
  assert.ok(reports.every(report=>report.host==='fixture-host'));
  assert.deepEqual(reports[1].reports,[[1,[0,127,129,0]],[1,[0,127,243,0]],[1,[0,46,0,0]]]);
  assert.deepEqual(reports[2].reports,[[2,[0,0,4,0,0,0,0,0]]]);
  await b.setConsent(a.config.id,{input:false});
  await until(()=>!a.active && !a.returning);
  assert.deepEqual(a.native.calls.filter(call=>call.method==='hid-send').at(-1).args.reports,
    [[1,[0,0,0,0]],[2,[0,0,0,0,0,0,0,0]]]);
  await assert.rejects(a.switchTo(id), /CONSENT/);
});

test('Bluetooth loss releases the old session before authorized network fallback and respects only modes', async t => {
  const {a,b,id,disconnect} = await bluetoothFixture(t);
  await a.inputTransport.save(id,{preference:'network_only'});
  await a.switchTo(id);
  assert.equal(a.activeSession.transport,'network');
  assert.equal(a.state().inputTransport.targets[0].selected,'network');
  assert.equal(a.native.calls.some(call=>call.method==='hid-send'),false);
  await a.inputTransport.save(id,{preference:'auto_bt_first'});
  await a.switchTo(id);
  const prior = a.activeSession.edgeSession;
  disconnect();
  await a.tick();
  assert.equal(a.activeSession.transport,'network');
  assert.notEqual(a.activeSession.edgeSession,prior);
  assert.equal(b.owner.transport,'network');
  await a.inputTransport.save(id,{preference:'bluetooth_only'});
  await assert.rejects(a.switchTo(id), /蓝牙键鼠尚未就绪/);
  assert.equal(a.active,null);
  assert.equal(b.owner,null);
});

test('group HID panel reports the primary, keeps a ready channel on standby, and falls back when switched off', async t => {
  const {a,b,id} = await bluetoothFixture(t);
  const {createActions} = await import('../src/actions.js');
  const invoke = createActions(a), remote = createActions(b);
  const permission = {appEnabled:true, peerAllowed:true, systemGranted:true};
  const selected = () => a.inputTransport.describe(id, permission).selected;
  assert.equal(selected(), 'bluetooth');
  await a.switchTo(id);
  await a.returnLocal();
  assert.equal(selected(), 'bluetooth');
  assert.equal(a.native.calls.some(call=>call.method === 'hid-stop'),false);
  const status = await invoke('group-hid-status', {});
  assert.equal(status.master.id, a.config.id);
  assert.equal(status.targets.find(target=>target.id === id).connected, true);
  await assert.rejects(remote('group-hid-enable', {enabled:false}), /主设备/);
  await invoke('group-hid-enable', {enabled:false});
  assert.equal((await loadConfig(a.dir)).groupHidEnabled, false);
  assert.equal(a.native.calls.some(call=>call.method === 'hid-stop'),true);
  assert.equal(selected(), 'network');
  await a.switchTo(id);
  assert.equal(a.activeSession.transport, 'network');
});

test('only the current primary may open a target HID guide and a stale prompt cannot cancel its replacement', async t => {
  const {a,b} = await pair(t);
  const fromPrimary = a.getPeer(b.config.id);
  await assert.rejects(b.getPeer(a.config.id).request('hid-connect-guide', {name:'Other device'}), /主设备/);
  assert.deepEqual(await fromPrimary.request('hid-connect-guide', {name:'LanLink'}), {opened:true});
  const first = b.inputTransport.guideNotice();
  assert.equal(first.id,a.config.id);
  await fromPrimary.request('hid-connect-guide', {name:'LanLink'});
  const second = b.inputTransport.guideNotice();
  await assert.rejects(b.inputTransport.finishGuide(first.requestId, true), /请求已结束/);
  assert.equal(b.inputTransport.guideNotice(), second);
  await b.inputTransport.finishGuide(second.requestId, true);
  assert.equal(b.inputTransport.guideNotice(), null);
});

test('direct HID pairing proves the primary before starting OS pairing and saves only a ready link', async t => {
  const {a,b} = await pair(t);
  const peer = a.getPeer(b.config.id), serviceId = a.inputTransport.serviceId;
  const nonce = 'a'.repeat(32);
  let acceptsProof = false, subscribed = false, paired = 0;
  peer.info.capabilities.autoHidTarget = 1;
  b.native.capabilities.hidDirectPairing = true;
  a.inputTransport.started = true;
  a.inputTransport.adapter = {rendezvousReady:true};
  a.inputTransport.pairing = {peer, until:Date.now() + 120000};
  a.native.call = async (method, args) => {
    if (method === 'hid-claim' && acceptsProof && args.nonce === nonce) return {host:'paired-host'};
    if (method === 'hid-status') return {hidServiceReady:true, rendezvousReady:true,
      hosts:subscribed ? [{id:'paired-host', hostConnected:true, reportSubscribed:true, ready:true}] : []};
    return {};
  };
  b.native.call = async (method) => {
    if (method === 'hid-find-device') return {address:'0123456789ab', addressType:1, nonce};
    if (method === 'hid-pair-device') { paired++; subscribed = true; return {paired:true}; }
    return {};
  };
  await peer.request('hid-connect-guide', {name:'LanLink', serviceId});
  const id = b.inputTransport.guideNotice().requestId;
  await assert.rejects(b.inputTransport.connectGuide(id), /凭证/);
  assert.equal(paired, 0);
  assert.equal(a.inputTransport.record(b.config.id).host, undefined);
  acceptsProof = true;
  await b.inputTransport.connectGuide(id);
  assert.equal(paired, 1);
  assert.equal(b.inputTransport.guideNotice(), null);
  assert.equal(a.inputTransport.record(b.config.id).host, a.inputTransport.hostKey('paired-host'));
});

test('group HID settings preserve a connected link while the receiver selects network input', async t => {
  const {a,b,id} = await bluetoothFixture(t);
  const sender = a.config.id;
  const offline = randomUUID();
  b.config.knownDevices[offline] = {name:'Offline group device',platform:'win32'};
  const {createActions} = await import('../src/actions.js');
  const invoke = createActions(b);
  const devices = async () => (await invoke('group-hid-status', {})).devices;
  let rows = await devices();
  assert.equal(rows.find(row=>row.id === sender).incoming, true);
  assert.equal(rows.find(row=>row.id === offline).incoming, null);
  assert.ok(rows.every(row=>row.id !== id));

  await invoke('group-hid-toggle', {id:sender,enabled:false});
  rows = await devices();
  assert.equal(rows.find(row=>row.id === sender).enabled, false);
  assert.equal(rows.find(row=>row.id === sender).incoming, true);
  await a.switchTo(id);
  assert.equal(a.activeSession.transport, 'network');
  assert.equal(b.owner.transport, 'network');
  assert.equal(a.inputTransport.connected(id), true);

  await a.returnLocal();
  await invoke('group-hid-toggle', {id:sender,enabled:true});
  await a.switchTo(id);
  assert.equal(a.activeSession.transport, 'bluetooth');
});

test('a local-only Bluetooth target cannot be rebound as a second target or enter the group', async t => {
  const {a,id} = await bluetoothFixture(t);
  const [{host}] = a.state().inputTransport.targets;
  await assert.rejects(a.inputTransport.save(null,{external:true,name:'iPad',host,confirmed:true}),/已绑定/);
  await a.inputTransport.save(id,{host:''});
  const {targets} = await a.inputTransport.save(null,{external:true,name:'iPad',host,confirmed:true});
  const external = targets.find(target=>target.external);
  assert.equal(external.availability,'bluetooth_only');
  assert.equal(a.group.data.members[external.id],undefined);
  await a.switchTo(external.id);
  assert.equal(a.activeSession.external,true);
  await a.inputTransport.save(external.id,{remove:true});
  assert.equal(a.active,null);
  assert.equal(a.inputTransport.record(external.id).host,undefined);
});

test('automatic mode can use the network if the initial HID report fails after acquisition', async t => {
  const {a,b,id,disconnect} = await bluetoothFixture(t);
  disconnect(); // Leave the cached subscription ready until the first native send.
  await a.switchTo(id);
  assert.equal(a.activeSession.transport,'network');
  assert.equal(b.owner.transport,'network');
  assert.equal(a.native.calls.filter(call=>call.method==='mode' && call.args?.remote).length,1);
});

test('LAN capture refreshes stale host permissions and reports which Mac permission is missing', async t => {
  const {a,b} = await pair(t);
  const original = a.native.call.bind(a.native);
  a.native.capabilities.host = false;
  a.native.call = async (method, args) => method === 'capabilities'
    ? {host:false, input:false, inputMonitoring:true} : original(method,args);
  await assert.rejects(a.switchTo(b.config.id), /HOST_INPUT_PERMISSION: .*辅助功能/);
  assert.equal(b.owner, null);
  a.native.call = async (method, args) => method === 'capabilities'
    ? {host:true, input:true, inputMonitoring:true} : original(method,args);
  await a.switchTo(b.config.id);
  await a.nativeEvent({type:'input',kind:'move',dx:23,dy:41});
  assert.equal(b.owner.transport, 'network');
  assert.ok(b.native.calls.some(call=>call.method==='input' && call.args.kind==='move'));
});

test('group Bluetooth requires a physical-link challenge and loses authorization when the master changes', async t => {
  const {a,b} = await pair(t);
  const id = b.config.id;
  const target = a.getPeer(id);
  target.info.capabilities.autoHidTarget = 1;
  const claimed = new Set();
  const original = a.native.call.bind(a.native);
  a.native.call = async (method,args) => {
    if (method==='hid-claim') {
      if (!['a'.repeat(32), 'c'.repeat(32)].includes(args.nonce) || claimed.has(args.nonce)) throw Error('凭证已过期');
      claimed.add(args.nonce);
      return {host:'fixture-ble-link'};
    }
    if (method==='hid-status') return {
      hidServiceReady:true, rendezvousReady:true,
      hosts:[{id:'fixture-ble-link',hostConnected:true,reportSubscribed:true,ready:true}],
    };
    return original(method,args);
  };
  const phone = b.getPeer(a.config.id);
  const {serviceId} = await phone.request('bluetooth-offer');
  assert.equal(serviceId,a.inputTransport.serviceId);
  assert.equal(a.inputTransport.ready(id),false);
  await assert.rejects(phone.request('bluetooth-claim',{serviceId,nonce:'b'.repeat(32)}),/凭证已过期/);
  await phone.request('bluetooth-claim',{serviceId,nonce:'a'.repeat(32)});
  assert.equal(a.inputTransport.ready(id),true);
  await assert.rejects(phone.request('bluetooth-claim',{serviceId,nonce:'a'.repeat(32)}),/凭证已过期/);

  await a.access.read(target);
  const status = () => a.state().inputTransport.targets.find(row=>row.id===id);
  const reports = () => a.native.calls.filter(call=>call.method==='hid-send');
  assert.equal(status().bluetooth.standby,true);
  assert.equal(status().active,'none');
  await a.tick();
  assert.equal(reports().length,0,'standby must not send input or neutral reports');
  assert.equal(b.owner,null,'standby must not acquire an input lease');

  phone.close();
  await until(()=>!a.peers.size && !b.peers.size);
  await a.connect('127.0.0.1',b.port);
  await until(()=>a.peers.get(id)?.info && b.peers.get(a.config.id)?.info);
  const reconnected = a.getPeer(id);
  reconnected.info.capabilities.autoHidTarget = 1;
  assert.equal(a.inputTransport.ready(id),false,'a new LAN session must reauthenticate the existing Bluetooth link');
  const returnedPhone = b.getPeer(a.config.id);
  await returnedPhone.request('bluetooth-claim',{serviceId,nonce:'c'.repeat(32)});
  await a.access.read(reconnected);
  assert.equal(status().bluetooth.standby,true);
  assert.equal(reports().length,0);

  await a.switchTo(id);
  assert.equal(status().bluetooth.standby,false);
  assert.equal(status().active,'bluetooth');
  await a.nativeEvent({type:'input',kind:'key',code:'KeyA',down:true});
  await a.returnLocal();
  const afterRelease = reports().length;
  await a.tick();
  assert.equal(status().bluetooth.standby,true);
  assert.equal(status().active,'none');
  assert.equal(reports().length,afterRelease);
  assert.equal(b.owner,null);

  await a.settings({masterId:id});
  assert.equal(a.inputTransport.ready(id),false);
  await assert.rejects(returnedPhone.request('bluetooth-offer'),/当前主设备/);
});

test('automatic input retries ordinary network authorization when Bluetooth fails before permission preflight', async t => {
  const {a,b,id} = await bluetoothFixture(t);
  const peer = a.getPeer(id);
  delete peer.access;
  let probes = 0;
  a.inputTransport.ready = () => ++probes === 1;
  await a.switchTo(id);
  assert.equal(a.activeSession.transport, 'network');
  await a.nativeEvent({type:'input',kind:'key',code:'KeyA',down:true});
  assert.ok(b.native.calls.some(call=>call.method==='input' && call.args.code==='KeyA'));

  await a.returnLocal();
  await b.setConsent(a.config.id,{input:false});
  delete peer.access;
  probes = 0;
  await assert.rejects(a.switchTo(id), /CONSENT/);
  assert.equal(b.owner, null);
});
