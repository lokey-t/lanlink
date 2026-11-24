import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { Service } from '../src/service.js';
import { newToken } from '../src/identity.js';
import { randomUUID } from 'node:crypto';

test('real authenticated peer loss and reconnection stay silent on the desktop', { timeout: 15000 }, async t => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'lanlink-recovery-')), token = newToken(), services = [];
  t.after(async () => { await Promise.all(services.map(service => service.close())); await rm(directory, { recursive: true, force: true }); });
  for (const name of ['controller', 'phone']) {
    const folder = path.join(directory, name); await mkdir(folder);
    const native = Object.assign(new EventEmitter(), { capabilities: {}, start: async () => {}, call: async () => ({}), close: () => {} });
    const service = new Service({ id: randomUUID(), name, token, master: name === 'controller', permissions: {}, edges: {} }, folder, native, { host: '127.0.0.1', port: 0, noDiscovery: true, noQuic: true });
    services.push(service);
  }
  const [controller, phone] = services, identity = phone.identity.bind(phone);
  let recoveryEvent;
  phone.identity = () => ({ ...identity(), platform: 'android', capabilities: { ...identity().capabilities, backgroundSessionId: recoveryEvent ? 'next-session' : 'original-session', backgroundRecovery: recoveryEvent } });
  for (const service of services) await service.start();
  const until = async predicate => { for (let i = 0; i < 200; i++) { if (predicate()) return; await new Promise(resolve => setTimeout(resolve, 10)); } throw Error('Timed out waiting for peer state'); };
  await controller.connect('127.0.0.1', phone.port);
  await until(() => controller.peers.get(phone.config.id)?.info?.platform === 'android');
  controller.getPeer(phone.config.id).close();
  await until(() => !controller.peers.has(phone.config.id));
  assert.equal(controller.state().androidDisconnectNotice, undefined);
  await until(() => !phone.peers.has(controller.config.id));
  recoveryEvent = { id: 'restart-event', previousSessionId: 'original-session', reason: 'low-memory' };
  await controller.connect('127.0.0.1', phone.port);
  await until(() => controller.peers.get(phone.config.id)?.info?.capabilities?.backgroundRecovery?.id === recoveryEvent.id);
  assert.equal(controller.state().androidDisconnectNotice, undefined);
  await controller.groupChanged();
  await until(() => !controller.peers.has(phone.config.id));
  assert.equal(controller.state().androidDisconnectNotice, undefined);
});
