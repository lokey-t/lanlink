import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, writeFile, readFile, rm, symlink } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

test('macOS editing, service recovery, and offline authorization use live native models', { skip: process.platform !== 'darwin' || process.env.LANLINK_MAC_UI_TESTS !== '1', timeout: 180000 }, async t => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'lanlink-ux-probe-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  await mkdir(path.join(root, 'src')); await mkdir(path.join(root, 'runtime'));
  await symlink(process.execPath, path.join(root, 'runtime', 'node'));
  const log = path.join(root, 'lifecycle.jsonl');
  await writeFile(path.join(root, 'src', 'native-host.js'), `const { createInterface } = require('node:readline');
const { appendFileSync } = require('node:fs');
const log = ${JSON.stringify(log)};
const record = event => appendFileSync(log, JSON.stringify({event,pid:process.pid})+'\\n');
record('start'); let ended = false, ready = false;
process.on('exit', () => record('exit'));
process.stdout.write(JSON.stringify({ready:true})+'\\n');
createInterface({input:process.stdin}).on('line', line => {
 const r=JSON.parse(line); if(r.method==='hold') return;
 if(r.method==='access-phase') { ended = r.args.ended === true; ready = r.args.ready === true; }
 const result = r.method==='state' ? {id:'local', name:'Fixture', knownDevices:{phone:{name:'Phone',platform:'android'}}, peers:[{id:'phone',name:'Phone',platform:'android'}], capabilities:{simulated:true}} : r.method==='access-status' ? {ended,ready,message:ended?'请求已超时，请重新请求':'设备已离线，等待重新连接'} : {pid:process.pid};
 if(r.method==='access-complete') setTimeout(() => process.stdout.write(JSON.stringify({id:r.id,result:{}})+'\\n'), 200);
 else process.stdout.write(JSON.stringify({id:r.id,result})+'\\n');
}).on('close', () => process.exit(0));
`);
  async function run(command, args) {
    const child = spawn(command, args); let output = '';
    child.stdout.on('data', b => output += b); child.stderr.on('data', b => output += b);
    t.after(() => { if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL'); });
    const code = await new Promise((resolve, reject) => { child.once('exit', resolve); child.once('error', reject); });
    assert.equal(code, 0, output); return output;
  }
  const executable = path.join(root, 'probe');
  await run('xcrun', ['swiftc', '-swift-version', '5', '-D', 'LANLINK_MODEL_TEST', '-module-cache-path', path.join(root, 'module-cache'), '-framework', 'AppKit', '-framework', 'SwiftUI', '-framework', 'ServiceManagement', 'desktop/macos/LanLink.swift', 'desktop/macos/SharedLayout.swift', 'desktop/macos/Localization.swift', 'desktop/macos/SingleInstance.swift', 'desktop/macos/Remote.swift', 'desktop/macos/AdbGuide.swift', 'desktop/macos/Staging.swift', 'tests/MacUXProbe.swift', '-o', executable]);
  assert.match(await run(executable, [root]), /MAC_UX_PROBE_OK/);
  const events = (await readFile(log, 'utf8')).trim().split('\n').map(JSON.parse);
  assert.equal(events.filter(e => e.event === 'start').length, 3, 'manual recovery and crash recovery each launch one host');
  const first = events[0].pid;
  assert(events.findIndex(e => e.event === 'exit' && e.pid === first) < events.findIndex(e => e.event === 'start' && e.pid !== first), 'old host exits before a new host starts');
});
