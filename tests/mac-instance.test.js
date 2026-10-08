import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { mkdtemp, writeFile, rm, copyFile, symlink } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

test('macOS versions sharing a profile elect one owner, preserve separate profiles, and recover after a crash', { skip: process.platform !== 'darwin', timeout: 60000 }, async t => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'lanlink-instance-'));
  const children = [];
  t.after(async () => {
    for (const child of children) if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
    await Promise.all(children.map(child => child.finished));
    await rm(root, { recursive: true, force: true });
  });
  const helper = path.join(root, 'main.swift'), versionA = path.join(root, 'LanLink-A'), versionB = path.join(root, 'LanLink-B');
  await writeFile(helper, `import Foundation
let lease = InstanceLease(dataDirectory: CommandLine.arguments[1])
do {
    if try lease.acquire() {
        print("OWNER \\(ProcessInfo.processInfo.processIdentifier)"); fflush(stdout)
        _ = readLine()
    } else { print("EXISTING \\(lease.ownerPID() ?? 0)"); fflush(stdout) }
} catch { fputs(error.localizedDescription, stderr); exit(1) }
`);
  const compiler = spawn('xcrun', ['swiftc', '-swift-version', '5', '-module-cache-path', path.join(root, 'module-cache'), 'desktop/macos/SingleInstance.swift', helper, '-o', versionA]);
  let diagnostics = ''; compiler.stdout.on('data', b => diagnostics += b); compiler.stderr.on('data', b => diagnostics += b);
  const compiled = await new Promise((resolve, reject) => { compiler.once('error', reject); compiler.once('exit', resolve); });
  assert.equal(compiled, 0, diagnostics);
  await copyFile(versionA, versionB);
  function launch(binary, directory) {
    const child = spawn(binary, [directory]); children.push(child);
    let errors = ''; child.stderr.on('data', b => errors += b);
    child.finished = new Promise((resolve, reject) => { child.once('exit', resolve); child.once('error', reject); });
    child.firstLine = new Promise((resolve, reject) => {
      createInterface({ input: child.stdout }).once('line', resolve);
      child.once('exit', code => { if (code) reject(new Error(errors || `Exited ${code}`)); });
      child.once('error', reject);
    });
    return child;
  }
  const profile = path.join(root, '共享配置 data');
  const contenders = Array.from({ length: 8 }, (_, i) => launch(i % 2 ? versionA : versionB, profile));
  const lines = await Promise.all(contenders.map(child => child.firstLine));
  assert.equal(lines.filter(line => line.startsWith('OWNER ')).length, 1, lines.join('\n'));
  const ownerIndex = lines.findIndex(line => line.startsWith('OWNER ')), owner = contenders[ownerIndex];
  const ownerPID = lines[ownerIndex].split(' ')[1];
  for (let i = 0; i < contenders.length; i++) if (i !== ownerIndex) {
    assert.match(lines[i], /^EXISTING /); assert.equal(await contenders[i].finished, 0);
  }
  const alias = path.join(root, 'profile-alias'); await symlink(profile, alias);
  const aliased = launch(versionB, alias);
  assert.equal(await aliased.firstLine, `EXISTING ${ownerPID}`);
  assert.equal(await aliased.finished, 0);
  const independent = launch(versionB, path.join(root, 'independent'));
  assert.match(await independent.firstLine, /^OWNER /, 'explicit profiles must remain usable for multi-device development');
  independent.stdin.end(); assert.equal(await independent.finished, 0);
  owner.kill('SIGKILL'); await owner.finished;
  const recovered = launch(versionB, profile);
  assert.match(await recovered.firstLine, /^OWNER /, 'a stale owner record must not prevent recovery after a crash');
  recovered.stdin.end(); assert.equal(await recovered.finished, 0);
  const relaunched = launch(versionA, alias);
  assert.match(await relaunched.firstLine, /^OWNER /, 'normal shutdown releases the same shared lease');
  relaunched.stdin.end(); assert.equal(await relaunched.finished, 0);
});
