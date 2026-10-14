import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { AndroidRecovery } from '../src/android-recovery.js';
import { Service } from '../src/service.js';
import { createActions } from '../src/actions.js';
import { newToken } from '../src/identity.js';
import { randomUUID } from 'node:crypto';

function fixture() {
  const service = Object.assign(new EventEmitter(), {
    config: { master: true }, group: { data: { id: 'group' }, excluded: () => false },
    peers: new Map(), screenTargets: new Map(), videoViewer: { sessions: new Map(), opening: new Map() },
  });
  const peer = { remote: { id: 'phone' }, info: { name: '测试手机', platform: 'android', capabilities: { backgroundSessionId: 'old-session' } } };
  service.peers.set('phone', peer);
  return { service, peer, recovery: new AndroidRecovery(service) };
}

test('a lost connection does not identify a battery policy as its cause', () => {
  const { peer, recovery } = fixture();
  peer.info.capabilities.backgroundPolicy = { backgroundRestricted: true, batteryExempt: false };
  recovery.disconnected(peer);
  assert.equal(recovery.notice.reason, 'disconnected');
  assert.equal(recovery.notice.confirmed, false);
  assert.match(recovery.notice.message, /暂时无法确认/);
});

test('recovery is correlated with the exact former session, survives dismissal, and is reported once', () => {
  const { peer, recovery } = fixture();
  recovery.disconnected(peer);
  const original = recovery.notice.requestId;
  recovery.dismiss(original);
  assert.equal(recovery.notice, null);
  peer.info.capabilities.backgroundSessionId = 'new-session';
  peer.info.capabilities.backgroundRecovery = { id: 'event', previousSessionId: 'unrelated', reason: 'restricted', at: 0 };
  recovery.identity(peer);
  assert.equal(recovery.notice, null);
  peer.info.capabilities.backgroundRecovery.previousSessionId = 'old-session';
  peer.info.capabilities.backgroundSessionId = 'new-session';
  recovery.identity(peer);
  assert.equal(recovery.notice.reason, 'restricted');
  assert.equal(recovery.notice.confirmed, false);
  assert.match(recovery.notice.message, /可能/);
  assert.notEqual(recovery.notice.requestId, original);
  const updated = recovery.notice;
  recovery.dismiss(original);
  assert.equal(recovery.notice, updated, 'a stale window cannot dismiss the new diagnosis');
  recovery.dismiss(updated.requestId);
  recovery.identity(peer);
  assert.equal(recovery.notice, null);
});

test('known exit causes take their own explanation; invalid, stale and replaced-peer reports cannot create a diagnosis', () => {
  for (const reason of ['low-memory', 'crash', 'user-stop', 'unknown']) {
    const { peer, recovery } = fixture();
    recovery.disconnected(peer);
    peer.info.capabilities.backgroundSessionId = 'new-session';
    peer.info.capabilities.backgroundRecovery = { id: 'event', previousSessionId: 'old-session', reason };
    recovery.identity(peer);
    assert.equal(recovery.notice.reason, reason);
    assert.equal(recovery.notice.confirmed, reason !== 'unknown');
    if (reason !== 'unknown') assert.doesNotMatch(recovery.notice.message, /省电/);
  }
  for (const invalid of ['reason', 'replaced', 'expired', 'group', 'removed']) {
    const { service, peer, recovery } = fixture();
    recovery.disconnected(peer);
    recovery.dismiss(recovery.notice.requestId);
    peer.info.capabilities.backgroundSessionId = 'new-session';
    peer.info.capabilities.backgroundRecovery = { id: 'event', previousSessionId: 'old-session', reason: 'crash' };
    if (invalid === 'reason') peer.info.capabilities.backgroundRecovery.reason = '__proto__';
    if (invalid === 'replaced') service.peers.set('phone', {});
    if (invalid === 'expired') recovery.pending.get('phone').until = 0;
    if (invalid === 'group') service.group.data.id = 'another-group';
    if (invalid === 'removed') service.group.excluded = () => true;
    recovery.identity(peer);
    assert.equal(recovery.notice, null, invalid);
  }
});

test('a same-process reconnect clears transport loss and cannot inherit a later crash', () => {
  const { service, peer, recovery } = fixture();
  recovery.disconnected(peer);
  recovery.identity(peer);
  assert.equal(recovery.notice, null);
  assert.equal(recovery.pending.size, 0);
  service.config.master = false;
  recovery.disconnected(peer);
  peer.info.capabilities.backgroundSessionId = 'new-session';
  peer.info.capabilities.backgroundRecovery = { id: 'later', previousSessionId: 'old-session', reason: 'crash' };
  recovery.identity(peer);
  assert.equal(recovery.notice, null);
});

test('phone reboot is reported separately from battery restrictions', () => {
  const { peer, recovery } = fixture();
  recovery.disconnected(peer);
  peer.info.capabilities.backgroundSessionId = 'new-session';
  peer.info.capabilities.backgroundRecovery = { id: 'boot', previousSessionId: 'old-session', reason: 'unknown', rebooted: true };
  recovery.identity(peer);
  assert.equal(recovery.notice.reason, 'reboot');
  assert.doesNotMatch(recovery.notice.message, /省电|自启动/);
});

test('notices are limited to controllers, suppressed on local shutdown/group changes, and queued per device', () => {
  for (const suppress of ['closing', 'changingGroup', 'intentionalClose', 'unrelated', 'desktop', 'excluded']) {
    const { service, peer, recovery } = fixture();
    if (suppress === 'intentionalClose') peer.intentionalClose = true;
    else if (suppress === 'unrelated') service.config.master = false;
    else if (suppress === 'desktop') peer.info.platform = 'darwin';
    else if (suppress === 'excluded') service.group.excluded = () => true;
    else service[suppress] = true;
    recovery.disconnected(peer);
    assert.equal(recovery.notice, null, suppress);
  }
  const { service, peer, recovery } = fixture();
  service.config.master = false;
  service.videoViewer.sessions.set('phone', {});
  recovery.disconnected(peer);
  assert.equal(recovery.notice.id, 'phone');
  service.config.master = true;
  recovery.disconnected({ ...peer, remote: { id: 'phone-2' } });
  recovery.dismiss(recovery.notice.requestId);
  assert.equal(recovery.notice.id, 'phone-2');
});

test('real authenticated peer loss and reconnection expose and dismiss a diagnosed notice through application actions', { timeout: 15000 }, async t => {
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
  const until = async predicate => { for (let i = 0; i < 200; i++) { if (predicate()) return; await new Promise(resolve => setTimeout(resolve, 10)); } throw Error('Timed out waiting for recovery state'); };
  await controller.connect('127.0.0.1', phone.port);
  await until(() => controller.peers.get(phone.config.id)?.info?.platform === 'android');
  controller.getPeer(phone.config.id).close();
  assert.equal(controller.state().androidDisconnectNotice.reason, 'disconnected');
  await until(() => !phone.peers.has(controller.config.id));
  recoveryEvent = { id: 'restart-event', previousSessionId: 'original-session', reason: 'low-memory' };
  await controller.connect('127.0.0.1', phone.port);
  await until(() => controller.state().androidDisconnectNotice?.reason === 'low-memory');
  const actions = createActions(controller);
  await actions('android-disconnect-dismiss', { requestId: controller.state().androidDisconnectNotice.requestId });
  assert.equal(controller.state().androidDisconnectNotice, null);
  await controller.groupChanged();
  await until(() => !controller.peers.has(phone.config.id));
  assert.equal(controller.state().androidDisconnectNotice, null, 'planned delayed socket closure is silent after changingGroup is cleared');
});
