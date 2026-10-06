import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { AdbManager, findAdb, helperCommand, parseDevices, boundHelperPids } from '../src/adb.js';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { loadConfig } from '../src/identity.js';

const apkPath = '/data/app/~~random==/com.lanlink.app-Abc_123==/base.apk';
const secret = 'lanlink1:' + 'A'.repeat(43);
const bootstrap = { apkPath, port: 41234, secret };
const listing = 'List of devices attached\nemulator-5580 device product:sdk model:Android_SDK transport_id:1\nphone-2 unauthorized transport_id:2\nphone-3 offline\n';

test('group authorization retains offline member bindings and excludes changed or removed identities', async () => {
  const config = { knownDevices: { phone: { name: 'Phone', platform: 'android' } }, adbBindings: { phone: { serial: 'emulator-5580', key: 'public-key' } } };
  const service = { config, peers: new Map(), group: { excluded: () => false, data: { members: { phone: { key: 'public-key' } } } } };
  const manager = new AdbManager(service, { locate: async () => '/adb', run: async () => ({ stdout: listing }) });
  assert.equal((await manager.devices()).devices[0].authorized, true);
  assert.equal((await manager.groupDevices()).devices[0].authorized, true);
  service.group.data.members.phone.key = 'changed-key';
  assert.equal((await manager.devices()).devices[0].authorized, false);
  assert.equal((await manager.groupDevices()).devices[0].authorized, false);
  service.group.excluded = () => true;
  assert.deepEqual((await manager.groupDevices()).devices, []);
});

test('background recovery does not restart a recovering or Root-managed input helper', async () => {
  for (const engine of [{ recovering: true }, { root: true }, { revoked: true }]) {
    const peer = { info: { platform: 'android', capabilities: { memberKey: 'key' } }, request: async method => {
      assert.equal(method, 'permission-status'); return { engine, permissions: { input: { appEnabled: true, peerAllowed: true, systemGranted: false } } };
    } };
    const manager = new AdbManager({ config: { adbBindings: { phone: { key: 'key', serial: 'emulator-5580' } } }, peers: new Map([['phone', peer]]), group: { excluded: () => false } });
    let attempted = false; manager.authorize = async () => { attempted = true; };
    await manager.tick(); assert.equal(attempted, false);
  }
});

test('phone-side revocation overrides stored authorization in both desktop device lists', async () => {
  const info = { name: 'Phone', platform: 'android', capabilities: { memberKey: 'key' } };
  const peer = { info, request: async () => ({ engine: { revoked: true, ready: false } }) };
  const service = { config: { adbBindings: { phone: { key: 'key', serial: 'emulator-5580' } } }, peers: new Map([['phone', peer]]), group: { excluded: () => false } };
  const manager = new AdbManager(service, { locate: async () => '/adb', run: async () => ({ stdout: listing }) });
  assert.equal((await manager.devices()).devices[0].authorized, false);
  const row = (await manager.groupDevices()).devices[0];
  assert.equal(row.authorized, false); assert.equal(row.status, '未就绪');
  assert.match(row.message,/手机已解除 ADB 授权/);
  peer.request = async () => ({ engine: { revoked: false, authorized: false, ready: false } });
  assert.match((await manager.groupDevices()).devices[0].message,/ADB 未授权/);
  assert.equal((await manager.groupDevices()).devices[0].authorized,false);
  peer.request = async () => ({ engine: { revoked: false, authorized: true, ready: false } });
  assert.equal((await manager.groupDevices()).devices[0].authorized, true);
});

test('helper upgrade selects only app_process with matching private binding, never another process or phone',()=>{
  const commands=[`21 app_process /system/bin com.lanlink.app.ShellAgent 41234 ${secret}`,`22 app_process /system/bin com.lanlink.app.ShellAgent 41235 ${secret}`,`23 app_process /system/bin com.lanlink.app.ShellAgent 41234 lanlink1:other`,`24 sh -c com.lanlink.app.ShellAgent 41234 ${secret}`].join('\n');
  assert.deepEqual(boundHelperPids(commands,bootstrap),['21']);
});

test('ADB discovery finds the installed SDK and ignores relative executable paths', async () => {
  const calls = [];
  const adb = await findAdb({ platform: 'darwin', home: '/Users/test', env: { LANLINK_ADB: './unsafe/adb', ANDROID_HOME: '/sdk' }, exists: async p => { calls.push(p); if (p !== '/sdk/platform-tools/adb') throw Error(); } });
  assert.equal(adb, '/sdk/platform-tools/adb'); assert(!calls.includes('./unsafe/adb'));
  assert.deepEqual(parseDevices(listing), [
    { serial: 'emulator-5580', status: 'device', model: 'Android SDK' },
    { serial: 'phone-2', status: 'unauthorized', model: 'phone-2' },
    { serial: 'phone-3', status: 'offline', model: 'phone-3' }
  ]);
});

test('helper command rejects peer supplied shell syntax, paths, tokens and wrong APK', () => {
  const installed = 'package:' + apkPath;
  assert.match(helperCommand(bootstrap, installed), /^CLASSPATH='\/data\/app\//);
  for (const bad of [{ apkPath: apkPath + ';reboot' }, { apkPath: '/data/app/../other/base.apk' }, { apkPath: '/data/local/tmp/base.apk' }, { secret: secret + '\nreboot' }, { port: '41234;reboot' }, { port: 0 }]) {
    assert.throws(() => helperCommand({ ...bootstrap, ...bad }, installed), /无效/);
  }
  assert.throws(() => helperCommand(bootstrap, 'package:/data/app/other/base.apk'), /不匹配/);
});

function fixture({ status = false, list = listing, failure = false, bootstrapError = null } = {}) {
  const service = new EventEmitter(), commands = [], requests = [];
  let started = false;
  const peer = { info: { platform: 'android', capabilities: { inputMode: 'accessibility-limited' } }, request: async method => {
    requests.push(method);
    if (method === 'adbStatus') return { ready: status || started };
    if (bootstrapError) throw Error(bootstrapError);
    return bootstrap;
  } };
  service.getPeer = id => { assert.equal(id, 'android-id'); return peer; };
  const manager = new AdbManager(service, { locate: async () => '/sdk/adb', wait: async () => {}, run: async (exe, args, options) => {
    commands.push({ exe, args, options });
    if (failure) throw Error('sensitive executable args ' + secret);
    if (args[0] === 'devices') return { stdout: list };
    if (args.includes('pm')) return { stdout: 'package:' + apkPath };
    started = true; return { stdout: '' };
  } });
  return { manager, commands, peer, requests };
}

test('ADB authorization targets one selected transport, verifies helper via paired connection and prefers enhanced mode', async () => {
  const { manager, commands, peer, requests } = fixture();
  const result = await manager.authorize({ id: 'android-id', serial: 'emulator-5580' });
  assert.equal(result.mode, 'adb/root'); assert.equal(peer.info.capabilities.inputMode, 'adb/root');
  assert.deepEqual(requests, ['adbStatus', 'adbBootstrap', 'adbStatus']);
  assert.equal(commands.length, 3);
  for (const call of commands.slice(1)) { assert.deepEqual(call.args.slice(0, 2), ['-s', 'emulator-5580']); assert.equal(call.options.shell, false); assert.equal(call.options.windowsHide, true); }
  assert(!JSON.stringify(result).includes(secret));
});

test('ADB authorization does not launch on unauthorized devices, refusal, existing helper, or unsafe serial', async () => {
  const unauthorized = fixture();
  await assert.rejects(unauthorized.manager.authorize({ id: 'android-id', serial: 'phone-2' }), /USB 调试/);
  assert.equal(unauthorized.requests.length, 0); assert.equal(unauthorized.commands.length, 1);
  const refused = fixture({ bootstrapError: 'CONSENT_PENDING' });
  await assert.rejects(refused.manager.authorize({ id: 'android-id', serial: 'emulator-5580' }), /CONSENT_PENDING/);
  assert.equal(refused.commands.length, 1);
  const ready = fixture({ status: true });
  await ready.manager.authorize({ id: 'android-id', serial: 'emulator-5580' });
  assert.equal(ready.commands.length, 1);
  assert.equal(ready.peer.info.capabilities.inputMode, 'adb/root');
  const invalid = fixture();
  await assert.rejects(invalid.manager.authorize({ id: 'android-id', serial: 'phone;reboot' }), /请选择/);
  assert.equal(invalid.commands.length, 0);
});

test('ADB error messages never leak helper credentials and always clear operation lock', async () => {
  const { manager } = fixture({ failure: true });
  await assert.rejects(manager.authorize({ id: 'android-id', serial: 'emulator-5580' }), error => !error.message.includes(secret) && error.message.includes('ADB 操作失败'));
  assert.equal(manager.busy, false);
});

test('saved ADB binding survives restart; automatic recovery skips changed keys and revoked permission', async t => {
  const dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-adb-binding-'));t.after(()=>rm(dir,{recursive:true,force:true}));
  const f=fixture(); const service=f.manager.service;
  service.config=await loadConfig(dir);service.dir=dir;service.group={excluded:()=>false};service.peers=new Map([['android-id',f.peer]]);
  f.peer.info.capabilities.memberKey='trusted-device-key';
  await f.manager.authorize({id:'android-id',serial:'emulator-5580'});
  service.config=await loadConfig(dir); assert.equal(service.config.adbBindings['android-id'].serial,'emulator-5580');
  f.peer.info.capabilities.inputMode='unavailable'; f.peer.info.capabilities.memberKey='changed';
  const before=f.requests.length; await f.manager.tick(); assert.equal(f.requests.length,before);
  f.peer.info.capabilities.memberKey='trusted-device-key';
  f.peer.request=async method=>{ f.requests.push(method); if(method==='permission-status') return {version:1,permissions:{input:{appEnabled:true,peerAllowed:false}}}; throw Error('Must not create an authorization request'); };
  await f.manager.tick(); assert.equal(f.requests.at(-1),'permission-status');
  await f.manager.forget('android-id'); assert.equal((await loadConfig(dir)).adbBindings['android-id'],undefined);
});

test('automatic recovery checks live helper health despite stale ready capability and restores only bound transport', async t => {
  const dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-adb-recover-')); t.after(()=>rm(dir,{recursive:true,force:true}));
  const f=fixture(), service=f.manager.service;
  service.config=await loadConfig(dir); service.dir=dir; service.group={excluded:()=>false}; service.peers=new Map([['android-id',f.peer]]);
  f.peer.info.capabilities={memberKey:'trusted-key',inputMode:'adb/root'};
  await f.manager.remember('android-id','emulator-5580',f.peer);
  const request=f.peer.request;
  f.peer.request=async method=>method==='permission-status' ? {version:1,permissions:{input:{appEnabled:true,peerAllowed:true,systemGranted:false}}} : request(method);
  await f.manager.tick();
  assert.ok(f.requests.includes('adbBootstrap'));
  assert.equal(f.commands.length,3);
  for(const command of f.commands.slice(1)) assert.deepEqual(command.args.slice(0,2),['-s','emulator-5580']);
  f.manager.retries.clear(); f.commands.length=0;
  f.peer.request=async method=>{assert.equal(method,'permission-status');return {permissions:{input:{appEnabled:true,peerAllowed:true,systemGranted:true}}};};
  await f.manager.tick(); assert.equal(f.commands.length,0);
});
