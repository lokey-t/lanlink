// Disposable emulator integration: native desktop ADB backend -> real shell helper.
// No personal phone is targeted. All credentials are generated for this run.
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { EventEmitter } from 'node:events';
import { mkdtemp, rm } from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { loadConfig } from '../src/identity.js';
import { Service } from '../src/service.js';
import { AdbManager, findAdb } from '../src/adb.js';

const exec = promisify(execFile), serial = 'emulator-5580';
const adb = await findAdb();
if (!adb) throw Error('Install Android SDK Platform-Tools first');
const device = (await exec(adb, ['-s', serial, 'shell', 'getprop', 'ro.hardware'], { windowsHide: true })).stdout.trim();
if (!/ranchu|goldfish/.test(device)) throw Error('Only the disposable Android emulator is permitted');
class FakeNative extends EventEmitter {
  capabilities = { input: false, host: false, clipboard: false, screen: false };
  async start() {}
  async call() { return {}; }
  close() {}
}
const dir = await mkdtemp(path.join(os.tmpdir(), 'lanlink-adb-smoke-'));
const config = await loadConfig(dir);
config.permissions = { input: false, clipboard: false, screen: false, shutdown: false };
const service = new Service(config, dir, new FakeNative(), { port: 0, host: '0.0.0.0', noDiscovery: true });
let instrument, peer;
try {
  await service.start();
  instrument = exec(adb, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'adbSmokePort', String(service.port), '-e', 'adbSmokeToken', config.token, 'com.lanlink.app.test/com.lanlink.app.PermissionFlowTest'], { windowsHide: true, timeout: 100000, maxBuffer: 1024 * 1024 }).then(r => ({ output: r.stdout }), () => ({ failed: true }));
  const deadline = Date.now() + 20000;
  while (!(peer = [...service.peers.values()].find(p => p.info?.platform === 'android'))) {
    if (Date.now() > deadline) throw Error('Android fixture did not connect to desktop service');
    await new Promise(r => setTimeout(r, 100));
  }
  const id = peer.remote.id;
  let result;
  if (process.argv.includes('--reuse-helper')) {
    const end = Date.now() + 10000;
    while (!(await peer.request('adbStatus')).ready) {
      if (Date.now() > end) throw Error('Persisted helper did not reconnect after application process restart');
      await new Promise(r => setTimeout(r, 200));
    }
    result = { mode: 'adb/root' };
  } else result = await new AdbManager(service).authorize({ id, serial });
  if (result.mode !== 'adb/root') throw Error('ADB enhanced mode did not activate');
  if (!(await peer.request('testReconnect', {}, 15000)).reconnected) throw Error('Persistent helper did not reconnect');
  const permissions = await peer.request('permission-status');
  if (permissions.permissions.input.systemGranted !== true || permissions.permissions.input.peerAllowed !== true || permissions.permissions.screen.systemGranted !== false || permissions.permissions.shutdown.supported !== false) throw Error('Android permission status did not match actual helper/projection/consent');
  await service.switchTo(id, 'right', false, true);
  const target = await peer.request('testTarget');
  await peer.request('input', {kind:'move',x:target.x,y:target.y});
  await peer.request('input', {kind:'button',button:0,down:true});
  await peer.request('input', {kind:'button',button:0,down:false});
  await peer.request('input', {kind:'key',code:'KeyA',down:true});
  await peer.request('input', {kind:'key',code:'KeyA',down:false});
  await peer.request('input', {kind:'button',button:0,down:true});
  await peer.request('input', {kind:'move',x:target.x+0.04,y:target.y});
  await peer.request('input', {kind:'button',button:0,down:false});
  await new Promise(r=>setTimeout(r,200));
  const received=await peer.request('testTarget');
  if(received.clicks<1 || received.keys<2 || received.moves<1)throw Error('Android did not receive mouse click/drag/keyboard: '+JSON.stringify(received));
  await peer.request('input', { kind: 'key', code: 'ShiftLeft', down: true });
  await peer.request('input', { kind: 'key', code: 'ShiftLeft', down: false });
  await peer.request('input', { kind: 'navigation', action: 'home' });
  await service.returnLocal();
  await peer.request('testDone');
  const report = await instrument;
  if (report.failed || !/PASS: encrypted ADB bootstrap/.test(report.output) || /FAIL|Exception|INSTRUMENTATION_FAILED/.test(report.output)) throw Error('Android ADB instrumentation did not pass');
  console.log('PASS: explicit emulator transport, authenticated bootstrap, persistent binding + authenticated reconnect without ADB, real helper heartbeat, key down/up, Home navigation and release.');
} finally {
  if (peer && !peer.closed) await peer.request('testDone').catch(() => {});
  await service.close();
  if (instrument) await instrument;
  await rm(dir, { recursive: true, force: true });
}
