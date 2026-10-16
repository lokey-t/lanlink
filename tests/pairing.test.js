import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdtemp, rm } from 'node:fs/promises';
import { EventEmitter } from 'node:events';
import path from 'node:path';
import os from 'node:os';
import dgram from 'node:dgram';
import { clientStart, clientFinish, serverStart } from '../src/pairing-crypto.js';
import { Pairing, exchange, discover } from '../src/pairing.js';
import { newToken, loadConfig, saveConfig } from '../src/identity.js';
import { createActions } from '../src/actions.js';
import { Service } from '../src/service.js';

const hex = () => randomBytes(16).toString('hex');
async function fixture(t, discoveryEnabled = false) {
  let now = 100000;
  const service = { options: { host: '127.0.0.1', noDiscovery: !discoveryEnabled, pairingDiscoveryPort: 0 }, config: { token: newToken() }, port: 45831, log() {} };
  const pairing = new Pairing(service, () => now); await pairing.start(); t.after(() => pairing.close());
  return { pairing, service, advance: ms => now += ms, offer: info => ({ id: info.id, port: pairing.port, host: '127.0.0.1' }) };
}
test('SRP peers agree, wrong code differs, invalid public values are rejected', () => {
  const id = hex(), salt = hex(), c = clientStart(), s = serverStart('00001234', salt, id, c.A);
  const good = clientFinish(c, '00001234', { B: s.B, salt, id });
  assert.deepEqual(good.key, s.key); assert.equal(good.clientProof, s.clientProof); assert.equal(good.serverProof, s.serverProof);
  assert.notDeepEqual(clientFinish(c, '99991234', { B: s.B, salt, id }).key, s.key);
  for (const A of ['0'.repeat(512), 'f'.repeat(512), '123']) assert.throws(() => serverStart('00001234', salt, id, A));
  assert.throws(() => clientFinish(c, '00001234', { B: '0'.repeat(512), salt, id }));
});
test('codes rotate at 60 seconds, refresh polling preserves code, closing revokes it', async t => {
  const f = await fixture(t), initial = f.pairing.show();
  assert.match(initial.code, /^\d{8}$/); assert.equal(initial.remainingMs, 60000);
  for (let i = 0; i < 5; i++) { f.advance(10000); assert.equal(f.pairing.show().id, initial.id); }
  f.advance(10000); const next = f.pairing.show(); assert.notEqual(next.id, initial.id);
  await assert.rejects(exchange(f.offer(initial), initial.code));
  f.pairing.hide(); await assert.rejects(exchange(f.offer(next), next.code));
  const lease = f.pairing.show(); f.advance(15000); await assert.rejects(exchange(f.offer(lease), lease.code));
});
test('short-code exchange encrypts account credential and consumes successful code once', async t => {
  const f = await fixture(t), info = f.pairing.show();
  const wrong = info.code === '00000000' ? '11111111' : '00000000';
  await assert.rejects(exchange(f.offer(info), wrong)); assert.equal(f.pairing.show().id, info.id);
  const received = await exchange(f.offer(info), info.code); assert.equal(received.token, f.service.config.token);
  await assert.rejects(exchange(f.offer(info), info.code));
  assert.notEqual(f.pairing.show().id, info.id);
});
test('attempt limit survives code rotation and page close/reopen', async t => {
  const f = await fixture(t), info = f.pairing.show(); const wrong = info.code === '00000000' ? '11111111' : '00000000';
  for (let i = 0; i < 10; i++) await assert.rejects(exchange(f.offer(info), wrong));
  f.pairing.hide(); const next = f.pairing.show(); await assert.rejects(exchange(f.offer(next), next.code));
  f.advance(60001); const fresh = f.pairing.show(); assert.equal((await exchange(f.offer(fresh), fresh.code)).token, f.service.config.token);
});
test('LAN discovery validates nonce and deduplicates offers without broadcasting code or account key', async t => {
  const server = dgram.createSocket('udp4'); await new Promise(r => server.bind(0, '127.0.0.1', r)); t.after(() => server.close()); const id = hex();
  server.on('message', (raw, from) => {
    const q = JSON.parse(raw); assert.deepEqual(Object.keys(q).sort(), ['kind', 'nonce']);
    for (const nonce of [hex(), q.nonce, q.nonce]) server.send(JSON.stringify({ kind: 'lanlink-pair-offer-v2', nonce, id, port: 12345 }), from.port, from.address);
  });
  const offers = await discover(id, ['127.0.0.1'], server.address().port); assert.equal(offers.length, 1); assert.equal(offers[0].id, id);
});
test('complete discovery-to-short-code exchange resolves the target invitation', async t => {
  const f = await fixture(t, true), info = f.pairing.show();
  const client = new Pairing({ options: { pairingDestinations: ['127.0.0.1'], pairingDiscoveryPort: f.pairing.udp.address().port }, config: {}, log() {} });
  await assert.rejects(client.join('123'), /八位/);
  const result = await client.join(info.code, info.id); assert.equal(result.token, f.service.config.token);
  assert.equal(result.port, 45831); assert.equal(f.pairing.current(), null);
});
test('native pairing API hides QR initially; short-code join persists account and connects encrypted peer', async t => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'lanlink-code-')); const services = [];
  t.after(async () => { await Promise.all(services.map(s => s.close())); await rm(dir, { recursive: true, force: true }); });
  class Native extends EventEmitter { capabilities = {}; async start() {} async call() { return {}; } close() {} }
  for (const name of ['a', 'b']) { const folder = path.join(dir, name), config = await loadConfig(folder); const s = new Service(config, folder, new Native(), { host: '127.0.0.1', port: 0, noDiscovery: true }); await s.start(); services.push(s); }
  const [a, b] = services, own = createActions(a), join = createActions(b), info = await own('pairing');
  assert.match(info.code, /^\d{8}$/); assert.equal(info.qr, undefined); assert.equal(info.token, undefined);
  assert.match((await own('pairing', { reveal: true })).qr, /^data:image\/gif;base64,/);
  const before = b.config.token;
  // Resolve to the actual loopback offering endpoint; all subsequent protocol and persistence paths are real.
  b.pairing.join = code => exchange({ id: info.id, host: '127.0.0.1', port: a.pairing.port }, code);
  await assert.rejects(join('join', { code: info.code === '00000000' ? '11111111' : '00000000' })); assert.equal(b.config.token, before);
  await join('join', { code: info.code }); assert.equal((await loadConfig(b.dir)).token, a.config.token);
  for (let i = 0; !b.peers.has(a.config.id); i++) { assert(i < 100); await new Promise(r => setTimeout(r, 10)); }
  assert.equal(b.peers.get(a.config.id).ready, true);
});

async function groupFixture(t, count = 4) {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'lanlink-merge-')), services = [];
  t.after(async () => { for (const s of services) await s.close(); await rm(dir, { recursive: true, force: true }); });
  class Native extends EventEmitter { capabilities = {}; async start() {} async call() { return {}; } close() {} }
  for (let i = 0; i < count; i++) {
    const folder = path.join(dir, String(i)), config = await loadConfig(folder);
    const s = new Service(config, folder, new Native(), { host: '127.0.0.1', port: 0, noDiscovery: true }); await s.start(); services.push(s);
  }
  return services;
}
async function pairInto(client, host) {
  const invite = host.pairing.show();
  client.pairing.join = (code, _id, dissolve) => exchange({ id: invite.id, host: '127.0.0.1', port: host.pairing.port }, code, client.group.own, client.group.established && !dissolve ? client.group : undefined);
  try { return await createActions(client)('join', { code: invite.code, expectedGroup: client.group.data.id }); } catch (error) { error.message += ' ' + JSON.stringify(host.logs); throw error; }
}
async function waitFor(fn) { for (let i = 0; !fn(); i++) { assert(i < 300, 'peer state converged'); await new Promise(r => setTimeout(r, 10)); } }

test('AB keeps its identity, authorizations and live connection when A enters C invitation', async t => {
  const [a, b, c] = await groupFixture(t, 3);
  await pairInto(b, a); await waitFor(() => a.peers.has(b.config.id));
  const group = a.group.data.id, token = a.config.token, connection = a.peers.get(b.config.id);
  a.config.deviceGrants[b.config.id] = { input: true }; a.config.edges.left = b.config.id;
  await pairInto(a, c);
  await waitFor(() => b.group.data.members[c.config.id] && a.peers.has(c.config.id));
  for (const s of [a, b, c]) { assert.equal(s.group.data.id, group); assert.equal(s.config.token, token); assert.equal(Object.keys(s.group.data.members).length, 3); assert.equal(s.config.dissolvedGroups, undefined); }
  assert.equal(a.peers.get(b.config.id), connection); assert.deepEqual(a.config.deviceGrants[b.config.id], { input: true }); assert.equal(a.config.edges.left, b.config.id);
});

test('AB and CD merge through BC; offline D recovers from B after C is gone', async t => {
  const [a, b, c, d] = await groupFixture(t);
  await pairInto(b, a); await pairInto(d, c);
  await waitFor(() => c.peers.has(d.config.id));
  const old = c.group.data.id, survivor = b.group.data.id;
  c.config.deviceGrants[d.config.id] = { clipboard: true }; c.config.edges.right = d.config.id;
  for (const peer of [...d.sockets]) peer.close();
  await waitFor(() => !c.peers.has(d.config.id));
  await pairInto(b, c);
  assert.equal(c.group.data.id, survivor); assert.equal(d.group.data.id, old);
  assert.deepEqual(c.config.deviceGrants[d.config.id], { clipboard: true });
  await waitFor(() => a.group.data.members[d.config.id]);
  const saved = await loadConfig(b.dir), relay = new (b.group.constructor)({ ...b, config: saved });
  const update = relay.recoveryResponse(d.group.recoveryQuery()); assert.ok(update);
  const forged = { ...update, body: update.body.slice(0, -4) + 'AAAA' };
  await assert.rejects(d.group.accept(forged), /签名/);
  await d.group.accept(update);
  for (const s of [a, b, c, d]) { assert.equal(s.group.data.id, survivor); assert.equal(s.config.token, b.config.token); assert.equal(Object.keys(s.group.data.members).length, 4); assert.equal(s.config.dissolvedGroups, undefined); }
  await assert.rejects(d.group.accept(update), /无效/);
  assert.equal((await loadConfig(d.dir)).group.id, survivor);
});

test('new standalone device joins an existing group and grouped peers can pair again without dissolution', async t => {
  const [a, b, c] = await groupFixture(t, 3);
  await pairInto(b, a); await pairInto(c, a);
  const group = a.group.data.id;
  await pairInto(a, c);
  for (const s of [a, b, c]) assert.equal(s.group.data.id, group);
  assert.equal(a.config.dissolvedGroups, undefined);
});

test('two live groups merge and D follows its existing C connection without pairing again', async t => {
  const [a, b, c, d] = await groupFixture(t);
  await pairInto(b, a); await pairInto(d, c);
  await waitFor(() => c.peers.has(d.config.id));
  d.config.deviceGrants[c.config.id] = { input: true };
  await pairInto(b, c);
  await waitFor(() => d.group.data.id === a.group.data.id);
  assert.equal(Object.keys(d.group.data.members).length, 4);
  assert.deepEqual(d.config.deviceGrants[c.config.id], { input: true });
});

test('wrong code and an over-capacity group merge leave both groups intact', async t => {
  const [a, b, c] = await groupFixture(t, 3);
  await pairInto(b, a);
  const before = structuredClone(a.group.export()), remote = structuredClone(c.group.export()), token = a.config.token;
  const info = c.pairing.show(), offer = { id: info.id, host: '127.0.0.1', port: c.pairing.port };
  await assert.rejects(exchange(offer, info.code === '00000000' ? '11111111' : '00000000', a.group.own, a.group));
  assert.deepEqual(a.group.export(), before); assert.deepEqual(c.group.export(), remote);
  for (let i = 0; i < 14; i++) { const member = { ...a.group.own, id: randomUUID() }; a.group.data.members[member.id] = member; }
  const full = structuredClone(a.group.export());
  await assert.rejects(exchange(offer, info.code, a.group.own, a.group), /16/);
  assert.deepEqual(a.group.export(), full); assert.deepEqual(c.group.export(), remote); assert.equal(a.config.token, token);
});

test('offline members can follow successive group merges using records from the final group', async t => {
  const [a, b, c, d, e, f] = await groupFixture(t, 6);
  await pairInto(b, a); await pairInto(d, c); await pairInto(f, e);
  await waitFor(() => c.peers.has(d.config.id));
  for (const peer of [...d.sockets]) peer.close(); await waitFor(() => !c.peers.has(d.config.id));
  await pairInto(b, c); await pairInto(e, b);
  const first = e.group.recoveryResponse(d.group.recoveryQuery()); assert.ok(first); await d.group.accept(first);
  const second = e.group.recoveryResponse(d.group.recoveryQuery()); assert.ok(second); await d.group.accept(second);
  assert.equal(d.group.data.id, e.group.data.id); assert.equal(Object.keys(d.group.data.members).length, 6);
  assert.equal(d.config.token, e.config.token);
});

test('restart finishes a persisted group merge while retaining device permissions', async t => {
  const [a, b, c] = await groupFixture(t, 3);
  await pairInto(b, a); await a.group.admit(c.group.own);
  const meta = structuredClone(a.group.export());
  c.config.deviceGrants[b.config.id] = { screen: false };
  c.config.mergedGroups = c.group.migration(meta, a.config.token);
  c.config.pendingGroupMerge = { group: meta, token: a.config.token };
  await saveConfig(c.dir, c.config);
  const restarted = new Service(await loadConfig(c.dir), path.join(c.dir, 'restart'), new c.native.constructor(), c.options);
  await restarted.start();
  try {
    assert.equal(restarted.group.data.id, a.group.data.id); assert.equal(restarted.config.pendingGroupMerge, undefined);
    assert.deepEqual(restarted.config.deviceGrants[b.config.id], { screen: false });
    assert.equal(Object.keys(restarted.group.data.members).length, 3);
  } finally { await restarted.close(); }
});

test('merging carries revocations forward so offline members do not restore removed-device permissions', async t => {
  const [a, b, c, d] = await groupFixture(t);
  await pairInto(b, a); await pairInto(d, c); await waitFor(() => c.peers.has(d.config.id));
  for (const peer of [...d.sockets]) peer.close(); await waitFor(() => !c.peers.has(d.config.id));
  const removed = randomUUID();
  // D went offline before C recorded this revocation.
  c.group.data.removed.push(removed);
  d.config.knownDevices[removed] = { name: 'previously removed' }; d.config.deviceGrants[removed] = { input: true };
  await pairInto(b, c);
  assert.equal(c.group.recoveryResponse({ ...d.group.recoveryQuery(), id: removed }), null);
  await d.group.accept(b.group.recoveryResponse(d.group.recoveryQuery()));
  assert.ok(d.group.excluded(removed)); assert.equal(d.config.knownDevices[removed], undefined); assert.equal(d.config.deviceGrants[removed], undefined);
  assert.equal(d.group.data.id, a.group.data.id);
});
