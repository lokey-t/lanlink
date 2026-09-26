import { deflateSync } from 'node:zlib';
import { writeFile } from 'node:fs/promises';
const width = 128, pixels = Buffer.alloc((width * 4 + 1) * width);
const bg = [23, 62, 52], white = [220, 235, 207], lime = [183, 218, 135];
function rect(x, y, left, top, right, bottom) { return x >= left && x <= right && y >= top && y <= bottom; }
for (let y = 0; y < width; y++) for (let x = 0; x < width; x++) {
  let color = bg;
  if (rect(x,y,22,28,80,71) && !rect(x,y,28,34,74,65) || rect(x,y,47,71,53,81) || rect(x,y,33,81,67,87)) color = white;
  if (rect(x,y,69,53,104,103)) color = rect(x,y,75,59,98,97) ? bg : lime;
  if (rect(x,y,82,88,91,91)) color = lime;
  const offset = y * (width * 4 + 1) + 1 + x * 4;
  pixels.set([...color,255], offset);
}
function crc(b) { let n = 0xffffffff; for (const v of b) { n ^= v; for (let i = 0; i < 8; i++) n = (n >>> 1) ^ ((n & 1) ? 0xedb88320 : 0); } return (n ^ 0xffffffff) >>> 0; }
function chunk(type, data) { const body = Buffer.concat([Buffer.from(type),data]), size=Buffer.alloc(4), sum=Buffer.alloc(4); size.writeUInt32BE(data.length); sum.writeUInt32BE(crc(body)); return Buffer.concat([size,body,sum]); }
const header = Buffer.alloc(13); header.writeUInt32BE(width); header.writeUInt32BE(width,4); header[8]=8; header[9]=6;
await writeFile('desktop/icon.png', Buffer.concat([Buffer.from('89504e470d0a1a0a','hex'),chunk('IHDR',header),chunk('IDAT',deflateSync(pixels)),chunk('IEND',Buffer.alloc(0))]));
