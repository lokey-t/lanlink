import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { gunzipSync } from 'node:zlib';
import { createHash } from 'node:crypto';
const file = await readFile('.tools/downloads/qrcode-generator-2.0.4.tgz');
const digest = createHash('sha512').update(file).digest('base64');
if (digest !== 'mZSiP6RnbHl4xL2Ap5HfkjLnmxfKcPWpWe/c+5XxCuetEenqmNFf1FH/ftXPCtFG5/TDobjsjz6sSNL0Sr8Z9g==') throw new Error('QR package integrity mismatch');
const tar = gunzipSync(file); await mkdir('vendor', { recursive: true });
const wanted = new Map([['package/dist/qrcode.mjs', 'vendor/qrcode.mjs']]);
for (let offset = 0; offset + 512 <= tar.length;) {
  const header = tar.subarray(offset, offset + 512), name = header.subarray(0, 100).toString().split('\0')[0];
  const size = parseInt(header.subarray(124, 136).toString().replaceAll('\0', '').trim(), 8) || 0;
  if (wanted.has(name)) { await writeFile(wanted.get(name), tar.subarray(offset + 512, offset + 512 + size)); wanted.delete(name); }
  offset += 512 + Math.ceil(size / 512) * 512;
}
if (wanted.size) throw new Error('QR package missing expected files: ' + [...wanted.keys()]);
console.log('Vendored qrcode-generator 2.0.4 with verified SHA-512; see THIRD_PARTY_NOTICES.md');
