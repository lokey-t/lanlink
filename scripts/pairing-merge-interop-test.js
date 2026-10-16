import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { EventEmitter } from 'node:events';
import path from 'node:path';
import os from 'node:os';
import assert from 'node:assert/strict';
import { Service } from '../src/service.js';
import { loadConfig } from '../src/identity.js';
import { exchange } from '../src/pairing.js';

const [java, classpath] = process.argv.slice(2);
if (!java || !classpath) throw Error('Expected java and classpath');
const root = await mkdtemp(path.join(os.tmpdir(), 'lanlink-merge-interop-')), services = [];
class Native extends EventEmitter { capabilities = {}; async start() {} async call() { return {}; } close() {} }
function launch(args) {
  const child = spawn(java, ['-cp', classpath, 'PairingMergeProbe', ...args], { windowsHide: true });
  child.stderr.on('data', data => process.stderr.write(data));
  const lines = [], pending = [];
  createInterface({ input: child.stdout }).on('line', line => pending.length ? pending.shift()(line) : lines.push(line));
  const exited = new Promise((resolve, reject) => { child.once('exit', code => code === 0 ? resolve() : reject(Error('Java probe exit ' + code))); child.once('error', reject); });
  // Attach a rejection handler while an invitation is being read.
  exited.catch(() => {});
  return { child, exited, line: () => lines.length ? Promise.resolve(lines.shift()) : Promise.race([new Promise(resolve => pending.push(resolve)), exited.then(() => { throw Error('Probe exited before output'); })]) };
}
try {
  for (let i = 0; i < 4; i++) {
    const dir = path.join(root, String(i)), config = await loadConfig(dir);
    if (i === 1 || i === 3) config.token = services[i - 1].config.token;
    const service = new Service(config, dir, new Native(), { host: '127.0.0.1', port: 0, noDiscovery: true }); await service.start(); services.push(service);
  }
  const [a, b, c, d] = services;
  for (const [left, right] of [[a, b], [c, d]]) {
    await left.group.admit(right.group.own); right.group.adopt(structuredClone(left.group.export()), left.config.token);
  }
  const file = path.join(root, 'java-receiver.json'); await writeFile(file, JSON.stringify(c.config));
  const server = launch(['server', file]);
  try {
    const invite = JSON.parse(await server.line());
    const result = await exchange({ id: invite.id, host: '127.0.0.1', port: invite.port }, invite.code, b.group.own, b.group);
    assert.equal(Object.keys(result.group.members).length, 4);
    server.child.stdin.end('\n');
    assert.equal(JSON.parse(await server.line()).id, a.group.data.id); await server.exited;
  } finally { if (server.child.exitCode === null) server.child.kill(); }

  // Use an independent original AB snapshot for the Java-initiated direction.
  const clientFile = path.join(root, 'java-client.json'); await writeFile(clientFile, JSON.stringify(a.config));
  const invite = c.pairing.show(), client = launch(['client', clientFile, String(c.pairing.port), invite.id, invite.code]);
  try {
    const result = JSON.parse(await client.line()); await client.exited;
    assert.equal(c.group.data.id, a.group.data.id); assert.equal(Object.keys(result.group.members).length, 4);
    const box = c.group.recoveryResponse(d.group.recoveryQuery()); assert.ok(box);
    await d.group.accept(box); assert.equal(d.group.data.id, a.group.data.id);
  } finally { if (client.child.exitCode === null) client.child.kill(); }
  console.log('Java / Node four-device group merging passed in both directions, including offline member recovery.');
} finally {
  for (const service of services) await service.close();
  await rm(root, { recursive: true, force: true });
}
