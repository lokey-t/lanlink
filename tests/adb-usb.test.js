import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { AdbManager } from '../src/adb.js';
import { AdbGuide } from '../src/adb-guide.js';
import { mkdtemp, rm, readFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

function fixture({ listing = 'phone-usb device model:Phone\n', engine = { ready: false, authorized: false }, binding = false } = {}) {
  const service = new EventEmitter(), requests = [], verifications = [], commands = [];
  const member = { name: '组内手机', platform: 'android', key: 'phone-key' };
  const peer = { info: { ...member, capabilities: { memberKey: member.key } }, request: async method => {
    requests.push(method);
    if (method === 'permission-status') return { engine };
    if (method === 'adbStatus') return { ready: engine.ready };
    if (method === 'adb-permission-request' || method === 'permission-cancel') return {};
    throw Error(`Unexpected operation: ${method}`);
  } };
  Object.assign(service, {
    config: { adbBindings: binding ? { phone: { serial: 'phone-usb', key: member.key } } : {} },
    peers: new Map([['phone', peer]]), edge: { waitForLeave() {} },
    group: { data: { members: { phone: member } }, excluded: () => false },
    getPeer(id) { const p = this.peers.get(id); if (!p || p.closed) throw Error('offline'); return p; },
    links: { verifyUsb: async (id, serial) => { verifications.push([id, serial]); return serial === 'phone-usb'; } }
  });
  const state = { listing };
  service.adb = new AdbManager(service, { locate: async () => '/adb', run: async (_exe, args) => {
    commands.push(args); assert.deepEqual(args, ['devices', '-l']); return { stdout: state.listing };
  } });
  service.adbGuide = new AdbGuide(service);
  return { service, manager: service.adb, guide: service.adbGuide, peer, member, requests, verifications, commands, state, engine };
}

test('USB insertion verifies group identity before opening a setup-only ADB guide', async () => {
  const f = fixture({ listing: 'other-usb device model:Phone\nphone-usb device model:Phone\n' });
  await f.manager.tick();
  assert.deepEqual(f.verifications, [['phone', 'other-usb'], ['phone', 'phone-usb']]);
  assert.deepEqual(f.requests, ['permission-status', 'permission-status']);
  assert.equal(f.guide.notice.id, 'phone');
  assert.equal(f.guide.notice.serial, 'phone-usb');
  assert.equal(f.guide.notice.trigger, 'usb');
  assert.equal(f.guide.notice.setupOnly, true);
  assert.equal(f.guide.notice.capture, undefined);
  assert.equal(f.guide.notice.unauthorized, true);
  assert.deepEqual(f.service.config.adbBindings, {});
});

test('an unknown unauthorized USB device is never guessed to be the online group phone', async () => {
  const f = fixture({ listing: 'phone-usb unauthorized model:Phone\n' });
  await f.manager.tick();
  assert.equal(f.guide.notice, null);
  assert.deepEqual(f.requests, []);
  assert.deepEqual(f.verifications, []);
  f.service.config.knownDevices = { phone: { name: 'Phone', platform: 'android' } };
  f.service.group.data.members = {};
  await f.manager.tick();
  assert.equal(f.guide.notice, null);
});

test('saved USB identities may prompt offline members but reject changed, removed or ambiguous identities', async () => {
  const offline = fixture({ listing: 'phone-usb unauthorized\n', binding: true });
  offline.service.peers.clear();
  await offline.manager.tick();
  assert.equal(offline.guide.notice.id, 'phone');
  assert.equal(offline.guide.notice.usbUnauthorized, true);
  const status = await offline.guide.status(offline.guide.notice.requestId);
  assert.equal(status.ready, false); assert.match(status.message, /允许 USB 调试/);
  for (const change of [
    f => { f.service.config.adbBindings.phone.key = 'old-key'; },
    f => { f.member.key = 'replacement-key'; },
    f => { f.peer.info.capabilities.memberKey = 'replacement-key'; },
    f => { f.service.group.excluded = () => true; },
    f => { delete f.service.group.data.members.phone; },
    f => { f.service.config.adbBindings.phone.serial = 'different-phone'; },
    f => { f.service.group.data.members.other = { ...f.member, key: 'other-key' }; f.service.config.adbBindings.other = { serial: 'phone-usb', key: 'other-key' }; }
  ]) {
    const f = fixture({ listing: 'phone-usb unauthorized\n', binding: true }); change(f);
    await f.manager.tick(); assert.equal(f.guide.notice, null);
  }
});

test('USB guide dismissal is remembered until removal and another pending phone is not dropped', async () => {
  const f = fixture({ listing: 'phone-usb unauthorized\n', binding: true });
  await f.manager.scanUsb();
  const first = f.guide.notice.requestId;
  await f.manager.scanUsb(); assert.equal(f.guide.notice.requestId, first);
  f.guide.cancel(first);
  await f.manager.scanUsb(); await f.manager.scanUsb(); assert.equal(f.guide.notice, null);
  f.state.listing = ''; await f.manager.scanUsb();
  f.state.listing = 'phone-usb unauthorized\n';
  f.guide.notice = { id: 'other', requestId: 'other-request' };
  await f.manager.scanUsb(); assert.equal(f.guide.notice.id, 'other');
  f.guide.cancel('other-request'); await f.manager.scanUsb();
  assert.equal(f.guide.notice.id, 'phone'); assert.notEqual(f.guide.notice.requestId, first);
});

test('ready, recovering and Root phones do not get unnecessary helper prompts', async () => {
  for (const engine of [{ ready: true }, { recovering: true }, { root: true, ready: false }]) {
    const f = fixture({ engine }); await f.manager.scanUsb();
    assert.equal(f.guide.notice, null); assert.deepEqual(f.verifications, []);
  }
  const f = fixture({ engine: { ready: false, revoked: true, authorized: false } });
  await f.manager.scanUsb();
  assert.equal(f.guide.notice.revoked, true);
  await f.guide.status(f.guide.notice.requestId); await f.guide.status(f.guide.notice.requestId);
  assert.equal(f.requests.filter(method => method === 'adb-permission-request').length, 1);
  assert.ok(!f.requests.includes('adbBootstrap'));
});

test('a ready LAN helper cannot dismiss an outstanding system USB authorization request', async () => {
  const f = fixture({ listing: 'other-usb device\nphone-usb unauthorized\n', binding: true, engine: { ready: true, authorized: true } });
  await f.manager.scanUsb(); const requestId = f.guide.notice.requestId;
  const status = await f.guide.status(requestId);
  assert.equal(status.ready, false); assert.equal(status.usbUnauthorized, true);
  assert.match(status.message, /允许 USB 调试/);
  const match = await f.manager.match('phone');
  assert.equal(match.connected, false); assert.match(match.message, /允许 USB 调试/);
  assert.ok(!f.requests.includes('adbStatus'));
  await assert.rejects(f.guide.complete(requestId), /ADB_SETUP_REQUIRED/);
  f.state.listing = ''; assert.equal((await f.guide.status(requestId)).ready, false);
  f.state.listing = 'phone-usb device\n';
  assert.equal((await f.manager.match('phone')).ready, true);
  const intent = await f.guide.complete(requestId);
  assert.equal(intent.setupOnly, true); assert.equal(intent.capture, undefined); assert.equal(f.guide.notice, null);
});

test('wireless and emulator transports do not count as USB insertion', async () => {
  const f = fixture({ listing: 'emulator-5554 device\n192.168.1.2:5555 device\nadb-test._adb-tls-connect._tcp device\n' });
  await f.manager.scanUsb();
  assert.deepEqual(f.verifications, []); assert.deepEqual(f.requests, []); assert.equal(f.guide.notice, null);
});

test('membership changes or replaced peers during USB verification cannot open a stale guide', async () => {
  for (const change of [
    f => { f.service.peers.set('phone', { ...f.peer }); },
    f => { f.service.group.excluded = () => true; },
    f => { f.service.group = { ...f.service.group }; },
    f => { f.service.closing = true; },
    f => { f.service.changingGroup = true; },
    f => { f.manager.busy = true; }
  ]) {
    const f = fixture();
    f.service.links.verifyUsb = async () => { change(f); return true; };
    await f.manager.scanUsb(); assert.equal(f.guide.notice, null);
  }
});

test('USB polling never overlaps and a scan failure does not prevent saved recovery', async () => {
  const f = fixture(); let release, scans = 0, recoveries = 0;
  f.manager.scanUsb = async () => { scans++; await new Promise(resolve => { release = resolve; }); };
  f.manager.recover = async () => { recoveries++; };
  const pending = f.manager.tick(); await f.manager.tick();
  assert.equal(scans, 1); release(); await pending; assert.equal(recoveries, 1);
  f.manager.scanUsb = async () => { throw Error('USB unavailable'); };
  await f.manager.tick(); assert.equal(recoveries, 2); assert.equal(f.manager.ticking, false);
});

async function automaticFixture(t) {
  const f = fixture(), permissions = { input: { appEnabled: true, peerAllowed: true, systemGranted: false } };
  f.service.dir = await mkdtemp(path.join(os.tmpdir(), 'lanlink-auto-usb-'));
  t.after(() => rm(f.service.dir, { recursive: true, force: true }));
  f.permissions = permissions; f.starts = 0; f.transport = 'phone-usb';
  f.service.links.verifyUsb = async (id, serial) => { f.verifications.push([id, serial]); return id === 'phone' && serial === f.transport; };
  const bootstrap = { apkPath: '/data/app/~~test==/com.lanlink.app-test/base.apk', port: 41234, secret: 'lanlink1:' + 'A'.repeat(43) };
  f.peer.request = async method => {
    f.requests.push(method);
    if (method === 'permission-status') return { engine: f.engine, permissions };
    if (method === 'adbStatus') return { ready: f.engine.ready };
    if (method === 'adbBootstrap') return bootstrap;
    if (method === 'adb-permission-request') return {};
    throw Error('Unexpected operation: ' + method);
  };
  f.manager.wait = async () => {};
  f.manager.run = async (_exe, args) => {
    f.commands.push(args);
    if (args[0] === 'devices') return { stdout: f.state.listing };
    assert.equal(args[0], '-s'); assert.equal(args[1], f.transport);
    if (args.includes('pm')) return { stdout: 'package:' + bootstrap.apkPath };
    if (args[3]?.includes('app_process')) { f.starts++; f.engine.ready = true; f.engine.authorized = true; }
    return { stdout: '' };
  };
  return f;
}

test('verified group USB automatically starts the helper, saves binding and notifies only after ready', async t => {
  const f = await automaticFixture(t);
  await f.manager.tick();
  assert.equal(f.starts, 1); assert.equal(f.guide.notice, null);
  assert.deepEqual(f.service.config.adbBindings.phone, { serial: 'phone-usb', key: 'phone-key' });
  assert.deepEqual(JSON.parse(await readFile(path.join(f.service.dir, 'config.json'), 'utf8')).adbBindings.phone, f.service.config.adbBindings.phone);
  const notice = f.manager.authorizationNotice();
  assert.equal(notice.id, 'phone'); assert.equal(notice.name, '组内手机'); assert.match(notice.message, /设备已授权/);
  f.manager.dismissAuthorization('unrelated'); assert.equal(f.manager.authorizationNotice().requestId, notice.requestId);
  await f.manager.tick(); assert.equal(f.starts, 1); assert.equal(f.manager.authorizationNotice().requestId, notice.requestId);
  f.manager.dismissAuthorization(notice.requestId); await f.manager.tick(); assert.equal(f.manager.authorizationNotice(), null);
  f.state.listing = ''; await f.manager.scanUsb(); f.state.listing = 'phone-usb device\n'; f.engine.ready = false; f.engine.authorized = false;
  await f.manager.scanUsb(); assert.equal(f.starts, 2); assert.notEqual(f.manager.authorizationNotice().requestId, notice.requestId);
});

test('automatic USB respects revoked and disabled grants and never reports failure as success', async t => {
  for (const mode of ['revoked', 'disabled', 'denied', 'failure']) {
    const f = await automaticFixture(t);
    if (mode === 'revoked') f.engine.revoked = true;
    if (mode === 'disabled') f.permissions.input.appEnabled = false;
    if (mode === 'denied') f.permissions.input.peerAllowed = false;
    if (mode === 'failure') f.manager.run = async (_exe, args) => args[0] === 'devices' ? { stdout: f.state.listing } : Promise.reject(Error('ADB failed'));
    await f.manager.scanUsb();
    assert.equal(f.starts, 0); assert.equal(f.manager.authorizationNotice(), null); assert.equal(f.guide.notice.id, 'phone');
  }
});

test('USB guide resumes automatic configuration after phone consent without a desktop click', async t => {
  const f = await automaticFixture(t); f.engine.revoked = true;
  await f.manager.scanUsb(); assert.equal(f.guide.notice.id, 'phone'); assert.equal(f.starts, 0);
  f.engine.revoked = false;
  await f.manager.scanUsb(); assert.equal(f.starts, 1); assert.equal(f.guide.notice, null); assert.equal(f.manager.authorizationNotice().id, 'phone');
});

test('authorization notifications disappear after membership or group changes', async t => {
  const f = await automaticFixture(t); await f.manager.scanUsb(); assert.ok(f.manager.authorizationNotice());
  f.service.group = { ...f.service.group }; assert.equal(f.manager.authorizationNotice(), null);
});

test('ADB polling also automatically configures an identity-verified wireless transport', async t => {
  const f = await automaticFixture(t); f.transport = '192.168.1.20:39541'; f.state.listing = f.transport + ' device\n';
  await f.manager.tick(); assert.equal(f.starts, 1); assert.equal(f.manager.authorizationNotice().id, 'phone');
  assert.equal(f.service.config.adbBindings.phone.serial, f.transport);
});

test('an authorized but stopped helper is restored on a newly verified computer', async t => {
  const f = await automaticFixture(t); f.engine.authorized = true;
  await f.manager.scanUsb(); assert.equal(f.starts, 1); assert.equal(f.manager.authorizationNotice().id, 'phone');
});

test('starting a helper process without a ready confirmation never produces a success popup', async t => {
  const f = await automaticFixture(t), run = f.manager.run;
  f.manager.run = async (...args) => { const result = await run(...args); f.engine.ready = false; return result; };
  await f.manager.scanUsb(); assert.equal(f.starts, 1); assert.equal(f.manager.authorizationNotice(), null);
  assert.equal(f.guide.notice.id, 'phone'); assert.deepEqual(f.service.config.adbBindings, {});
});

test('group replacement during automatic setup cannot display a stale success or setup guide', async t => {
  const f = await automaticFixture(t);
  f.manager.authorize = async () => { f.service.group = { ...f.service.group }; return { mode: 'adb/root' }; };
  await f.manager.scanUsb(); assert.equal(f.manager.authorizationNotice(), null); assert.equal(f.guide.notice, null);
});
