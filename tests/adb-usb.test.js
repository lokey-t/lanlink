import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { AdbManager } from '../src/adb.js';
import { AdbGuide } from '../src/adb-guide.js';

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
  assert.deepEqual(f.requests, ['permission-status']);
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

test('ready, authorized, recovering and Root phones do not get unnecessary helper prompts', async () => {
  for (const engine of [{ ready: true }, { ready: false, authorized: true }, { recovering: true }, { root: true, ready: false }]) {
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
