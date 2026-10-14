import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

// Requires an interactive macOS WindowServer. Keep ordinary headless/CI runs free
// of AppKit windows; opt in when checking the native UI on a logged-in Mac.
test('macOS remote window switches targets only after draining the prior session', { skip: process.platform !== 'darwin' || process.env.LANLINK_MAC_UI_TESTS !== '1', timeout: 120000 }, async t => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'lanlink-remote-probe-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const executable = path.join(root, 'probe'), fixture = path.join(root, 'host.mjs'), log = path.join(root, 'requests.jsonl');
  await writeFile(fixture, `import { createInterface } from 'node:readline';
import { appendFileSync } from 'node:fs';
let active = null;
createInterface({ input: process.stdin }).on('line', line => {
  const request = JSON.parse(line); appendFileSync(process.argv[2], JSON.stringify(request) + '\\n');
  const delay = request.method === 'control' && request.args.id === 'A' ? 250 : ['input', 'paste'].includes(request.method) ? 180 : request.method === 'stream-next' ? 20 : 1;
  setTimeout(() => {
    if (request.method === 'control') active = request.args.id;
    if (request.method === 'release' && (!request.args.id || active === request.args.id)) active = null;
    const result = request.method === 'state' ? { active, peers: [], capabilities: { simulated: true } } : request.method === 'stream-next' ? {data:'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',codec:'h264',fps:30} : {};
    const error = ['input', 'paste'].includes(request.method) ? 'CONTROL_ENDED: previous device disconnected' : undefined;
    process.stdout.write(JSON.stringify({ id: request.id, result, error }) + '\\n');
  }, delay);
}).on('close', () => process.exit(0));
`);
  async function run(command, args) {
    const child = spawn(command, args); let output = '';
    child.stdout.on('data', b => output += b); child.stderr.on('data', b => output += b);
    t.after(() => { if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL'); });
    const code = await new Promise((resolve, reject) => { child.once('exit', resolve); child.once('error', reject); });
    assert.equal(code, 0, output); return output;
  }
  await run('xcrun', ['swiftc', '-swift-version', '5', '-D', 'LANLINK_MODEL_TEST', '-module-cache-path', path.join(root, 'module-cache'), '-framework', 'AppKit', '-framework', 'SwiftUI', '-framework', 'ServiceManagement', 'desktop/macos/LanLink.swift', 'desktop/macos/SharedLayout.swift', 'desktop/macos/Localization.swift', 'desktop/macos/SingleInstance.swift', 'desktop/macos/Remote.swift', 'desktop/macos/AdbGuide.swift', 'desktop/macos/Staging.swift', 'tests/MacRemoteProbe.swift', '-o', executable]);
  assert.match(await run(executable, [process.execPath, fixture, log]), /MAC_REMOTE_PROBE_OK/);
  const requests = (await readFile(log, 'utf8')).trim().split('\n').map(line => JSON.parse(line));
  const indexOf = (method, id) => requests.findIndex(request => request.method === method && request.args.id === id);
  assert.equal(indexOf('control', 'B'), -1, 'superseded selections must not acquire control');
  assert(indexOf('release', 'A') >= 0 && indexOf('release', 'A') < indexOf('control', 'C'), 'old delayed acquire is released before C starts');
  assert(indexOf('release', 'C') >= 0 && indexOf('release', 'C') < indexOf('control', 'D'), 'old input work is drained before D starts');
  assert(requests.filter(request => request.method === 'release').every(request => request.args.id), 'remote window cleanup always targets its own device');
});
