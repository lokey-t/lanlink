import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import assert from 'node:assert/strict';
import { Pairing, exchange } from '../src/pairing.js';
import { newToken } from '../src/identity.js';
const [java, classpath] = process.argv.slice(2);
if (!java || !classpath) throw new Error('Expected java and classpath');
const token = newToken(), p = new Pairing({ options: { noDiscovery: true, host: '127.0.0.1' }, config: { token }, port: 45831, log() {} });
await p.start(); const info = p.show();
try {
  const c = spawn(java, ['-cp', classpath, 'PairingProbe', 'client', String(p.port), info.id, info.code, token], { windowsHide: true });
  let output = ''; c.stdout.on('data', d => output += d); c.stderr.on('data', d => process.stderr.write(d));
  assert.equal(await new Promise((r, j) => { c.on('exit', r); c.on('error', j); }), 0); assert.match(output, /PAIR_CLIENT_OK/);
} finally { await p.close(); }
const child = spawn(java, ['-cp', classpath, 'PairingProbe', 'server', token], { windowsHide: true });
child.stderr.on('data', d => process.stderr.write(d)); const exited = new Promise(r => child.on('exit', r));
try {
  const line = await new Promise((r, j) => { const lines = createInterface({ input: child.stdout }); lines.once('line', r); child.once('error', j); child.once('exit', () => j(new Error('Java pairing server exited before ready'))); });
  const info = JSON.parse(line), offer = { host: '127.0.0.1', port: info.port, id: info.id };
  await assert.rejects(exchange(offer, info.code === '00000000' ? '11111111' : '00000000'));
  assert.equal((await exchange(offer, info.code)).token, token);
  await assert.rejects(exchange(offer, info.code));
} finally { child.stdin.end('\n'); await exited; }
console.log('Java / Node short-code SRP pairing passed in both directions; wrong-code and replay rejected.');
