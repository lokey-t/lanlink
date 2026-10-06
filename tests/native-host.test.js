import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { mkdtemp, rm, writeFile, readFile } from 'node:fs/promises';
import { EventEmitter } from 'node:events';
import { Service } from '../src/service.js';
import { loadConfig } from '../src/identity.js';
import os from 'node:os';
import path from 'node:path';

test('native inherited-pipe host persists settings, rejects unknown actions and stops on UI exit', { timeout: 15000 }, async t => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'lanlink-pipe-'));
  const child = spawn(process.execPath, ['src/native-host.js', '--simulate', '--no-discovery', '--data', dir, '--port', '0'], { windowsHide: true });
  let seq = 0; const pending = new Map(); let startup;
  const ready = new Promise(resolve => startup = resolve);
  const exit = new Promise(resolve => child.on('exit', resolve));
  createInterface({ input: child.stdout }).on('line', line => {
    const m = JSON.parse(line); if (m.ready) startup();
    const promise = pending.get(m.id); if (promise) { pending.delete(m.id); m.error ? promise.reject(new Error(promise.method+': '+m.error)) : promise.resolve(m.result); }
  });
  t.after(async () => { child.stdin.end(); await exit; await rm(dir, { recursive: true, force: true }); });
  await ready;
  const call = (method, args = {}) => new Promise((resolve, reject) => { const id = String(++seq); pending.set(id, { resolve, reject, method }); child.stdin.write(JSON.stringify({ id, method, args }) + '\n'); });
  await Promise.all([call('settings', { name: 'Native UI' }), call('settings', { dwell: 600 })]);
  const state = await call('state'); assert.equal(state.name, 'Native UI'); assert.equal(state.dwell, 600); assert.equal(state.capabilities.simulated, true);
  assert.match((await call('pairing', { reveal: true })).qr, /^data:image\/gif;base64,/);
  await assert.rejects(call('__proto__'), /接口不存在/);
  await assert.rejects(call('device-paste', { id: 'offline' }), /设备不在线/);
  // Exercise the new native device menu operations against an encrypted peer.
  class FakeNative extends EventEmitter {
    capabilities = { input: true, host: true }; clip = {}; calls = [];
    async start() {}
    async call(method, args) { this.calls.push(method); if (method === 'clipboardSet') this.clip = args; return method === 'clipboardGet' ? this.clip : {}; }
    close() {}
  }
  const targetDir = path.join(dir, 'target'), config = await loadConfig(targetDir);
  config.token = (await loadConfig(dir)).token;
  config.permissions = { input: true, clipboard: true, screen: false, shutdown: false };
  const native = new FakeNative(); const target = new Service(config, targetDir, native, { host: '127.0.0.1', port: 0, noDiscovery: true });
  await target.start(); t.after(() => target.close());
  await call('settings', { permissions: { clipboard: true } });
  await call('connect', { host: '127.0.0.1', port: target.port });
  for (let i = 0; !(await call('state')).peers.length; i++) { assert(i < 100, 'peer connection timed out'); await new Promise(r => setTimeout(r, 10)); }
  await assert.rejects(call('control', { id: config.id }), /CONSENT_PENDING/);
  await target.decideConsent({ ...target.state().consentRequests[0], accepted: true, permissions: { input: true, clipboard: true } });
  await assert.rejects(target.getPeer(state.id).request('acquire'), /CONSENT_PENDING/);
  await call('consent-decide', { ...(await call('state')).consentRequests[0], accepted: true, permissions: { clipboard: true } });
  await call('clipboard', { text: '来自原生设备菜单的中文' });
  await call('device-paste', { id: config.id });
  assert.equal(native.clip.text, '来自原生设备菜单的中文'); assert(native.calls.includes('paste'));
  assert.equal((await call('state')).active, null, 'temporary menu control must be released');
  const file = path.join(dir, '原生文件.txt'); await writeFile(file, 'file transfer through native menu');
  await call('send-files', { id: config.id, files: [file] });
  assert.equal(await readFile(native.clip.files[0], 'utf8'), 'file transfer through native menu');
  assert.equal((await call('state')).active, null);
  await target.close();
  child.stdin.end(); assert.equal(await exit, 0);
});
