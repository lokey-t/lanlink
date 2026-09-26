import net from 'node:net';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';
import { Peer } from '../src/wire.js';
import { newToken, parseToken } from '../src/identity.js';
const [java, classpath] = process.argv.slice(2); if (!java || !classpath) throw new Error('Usage: node scripts/interop-test.js <java> <compiled probe + JSON jar classpath>');
const token = newToken(); let peer;
const server = net.createServer(s => {
  peer = new Peer(s, { id: randomUUID(), name: 'Node probe' }, parseToken(token));
  peer.on('closed', () => { if (peer.failure) console.error('Node protocol error:', peer.failure); });
  peer.on('message', m => { if (m.type === 'request') peer.send({ type: 'response', id: m.id, result: m.args }); });
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
try {
  const p = spawn(java, ['-Dlanlink.debug=true', '-Dfile.encoding=UTF-8', '-cp', classpath, 'InteropProbe', String(server.address().port), token], { windowsHide: true }); let output = '';
  p.stdout.on('data', d => output += d); p.stderr.on('data', d => process.stderr.write(d));
  const code = await new Promise((resolve, reject) => { p.on('error', reject); p.on('exit', resolve); });
  assert.equal(code, 0); assert.match(output, /INTEROP_OK/); console.log('Java / Node authentication, HKDF, AES-GCM and Unicode request/response interoperability passed.');
} finally { peer?.close(); await new Promise(r => server.close(r)); }
