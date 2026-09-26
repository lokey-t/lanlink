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
import { newToken, loadConfig } from '../src/identity.js';
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
