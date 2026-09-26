import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

test('developer HTTP entry point starts and exits through authenticated local stop API', { timeout: 12000 }, async t => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'lanlink-entry-'));
  const child = spawn(process.execPath, ['src/main.js', '--simulate', '--no-discovery', '--data', dir, '--port', '0', '--ui-port', '0'], { windowsHide: true });
  let errors = ''; child.stderr.on('data', d => errors += d);
  const exited = new Promise((resolve, reject) => { child.on('exit', resolve); child.on('error', reject); });
  t.after(async () => { if (child.exitCode === null) child.kill(); await exited; await rm(dir, { recursive: true, force: true }); });
  const address = await new Promise((resolve, reject) => {
    let output = '';
    child.stdout.on('data', d => { output += d; const match = output.match(/http:\/\/127\.0\.0\.1:\d+\/#([a-f0-9]{64})/); if (match) resolve(new URL(match[0])); });
    child.once('exit', code => { if (code) reject(new Error(errors || 'Startup failed')); });
  });
  const headers = { 'X-LanLink-Token': address.hash.slice(1), 'Content-Type': 'application/json' };
  const state = await (await fetch(address.origin + '/api/state', { headers })).json(); assert.equal(state.capabilities.simulated, true);
  const stop = await fetch(address.origin + '/api/stop', { method: 'POST', headers, body: '{}' }); assert.equal(stop.status, 200);
  assert.equal(await exited, 0, errors);
});
