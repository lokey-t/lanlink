import {gzipSync} from 'node:zlib';
import {hash} from '../src/update-package.js';

const blockSize = 16384;
const mask = 65535;

// The rolling checksum finds unchanged blocks even when ZIP entries move.
function checksum(bytes, offset) {
 let sum = 0, weighted = 0;
 for (let i = 0; i < blockSize; i++) {
  sum += bytes[offset + i];
  weighted += sum;
 }
 return [sum & mask, weighted & mask];
}

export function buildApkDelta(before, after) {
 const blocks = new Map();
 for (let offset = 0; offset + blockSize <= before.length; offset += blockSize) {
  const [sum, weighted] = checksum(before, offset);
  blocks.set((weighted << 16) | sum, offset);
 }

 const parts = [Buffer.from('LLAPK001'), Buffer.from(hash(before), 'hex')];
 function literal(start, end) {
  if (start === end) return;
  const header = Buffer.alloc(5);
  header[0] = 2;
  header.writeUInt32BE(end - start, 1);
  parts.push(header, after.subarray(start, end));
 }

 let offset = 0, pending = 0, [sum, weighted] = [0, 0];
 if (after.length >= blockSize) [sum, weighted] = checksum(after, 0);
 while (offset + blockSize <= after.length) {
  const match = blocks.get((weighted << 16) | sum);
  if (match !== undefined && before.subarray(match, match + blockSize).equals(after.subarray(offset, offset + blockSize))) {
   literal(pending, offset);
   let length = blockSize;
   while (match + length < before.length && offset + length < after.length &&
          before[match + length] === after[offset + length]) length++;
   const header = Buffer.alloc(9);
   header[0] = 1;
   header.writeUInt32BE(length, 1);
   header.writeUInt32BE(match, 5);
   parts.push(header);
   offset += length;
   pending = offset;
   if (offset + blockSize <= after.length) [sum, weighted] = checksum(after, offset);
  } else {
   const outgoing = after[offset];
   sum = (sum - outgoing + (after[offset + blockSize] ?? 0)) & mask;
   weighted = (weighted - blockSize * outgoing + sum) & mask;
   offset++;
  }
 }
 literal(pending, after.length);
 parts.push(Buffer.from([0]));
 return gzipSync(Buffer.concat(parts));
}
