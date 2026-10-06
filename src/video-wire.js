// Separate authenticated binary media channel. Secrets travel only over the paired control channel.
import net from 'node:net';
import { randomBytes, createHmac, createCipheriv, createDecipheriv, timingSafeEqual } from 'node:crypto';
export const MAX_VIDEO = 4 * 1024 * 1024;
export const hmac = (key, ...parts) => createHmac('sha256', key).update(Buffer.concat(parts.map(p => Buffer.isBuffer(p) ? p : Buffer.from(p)))).digest();
export class BinaryReader {
  constructor(socket) { this.iterator = socket[Symbol.asyncIterator](); this.buffer = Buffer.alloc(0); }
  async read(n) {
    if (n < 0 || n > MAX_VIDEO + 64) throw Error('视频数据过大');
    while (this.buffer.length < n) { const v = await this.iterator.next(); if (v.done) throw Error('串流连接已结束'); this.buffer = Buffer.concat([this.buffer, v.value]); }
    const value = this.buffer.subarray(0, n); this.buffer = this.buffer.subarray(n); return value;
  }
}
function nonce(seq) { const b = Buffer.alloc(12); b.writeBigUInt64BE(BigInt(seq), 4); return b; }
function seqBytes(seq) { const b = Buffer.alloc(8); b.writeBigUInt64BE(BigInt(seq)); return b; }
export function packFrame(key, seq, frame) {
  if (!Buffer.isBuffer(frame.data) || frame.data.length > MAX_VIDEO - 64 || !['jpeg', 'h264', 'ping'].includes(frame.codec) || frame.width < 1 || frame.height < 1 || frame.width > 4096 || frame.height > 4096 || frame.width * frame.height > 8388608) throw Error('视频帧无效');
  const header = Buffer.alloc(14); header[0] = frame.codec === 'ping' ? 3 : frame.codec === 'h264' ? 2 : 1; header[1] = frame.key ? 1 : 0; header.writeUInt16BE(frame.width, 2); header.writeUInt16BE(frame.height, 4); header.writeBigUInt64BE(BigInt(Math.floor(frame.time ?? Date.now())), 6);
  const sequence = seqBytes(seq), c = createCipheriv('aes-256-gcm', key, nonce(seq)); c.setAAD(Buffer.concat([Buffer.from('lanlink-video-v1'), sequence]));
  const body = Buffer.concat([sequence, c.update(Buffer.concat([header, frame.data])), c.final(), c.getAuthTag()]);
  const size = Buffer.alloc(4); size.writeUInt32BE(body.length); return Buffer.concat([size, body]);
}
export function unpackFrame(key, seq, body) {
  if (body.length < 38 || body.length > MAX_VIDEO || body.readBigUInt64BE() !== BigInt(seq)) throw Error('视频帧序号或长度无效');
  const c = createDecipheriv('aes-256-gcm', key, nonce(seq)); c.setAAD(Buffer.concat([Buffer.from('lanlink-video-v1'), body.subarray(0, 8)])); c.setAuthTag(body.subarray(-16));
  const plain = Buffer.concat([c.update(body.subarray(8, -16)), c.final()]);
  const frame = { codec: plain[0] === 3 ? 'ping' : plain[0] === 2 ? 'h264' : plain[0] === 1 ? 'jpeg' : '', key: plain[1] === 1, width: plain.readUInt16BE(2), height: plain.readUInt16BE(4), time: Number(plain.readBigUInt64BE(6)), data: plain.subarray(14) };
  if (!frame.codec || frame.width < 1 || frame.height < 1 || frame.width > 4096 || frame.height > 4096 || frame.width * frame.height > 8388608) throw Error('视频尺寸无效');
  return frame;
}
export async function serverHandshake(socket, secret, retainReader=false) {
  const reader = new BinaryReader(socket), sn = randomBytes(32); socket.write(sn);
  const reply = await reader.read(64), cn = reply.subarray(0, 32);
  if (!timingSafeEqual(reply.subarray(32), hmac(secret, 'auth', sn, cn))) throw Error('串流身份验证失败');
  socket.write(hmac(secret, 'ack', sn, cn));const key=hmac(secret, 'media', sn, cn);return retainReader?{key,reader}:key;
}
export async function connectVideo(host, info, signal) {
  if (info.protocol !== 1 || !Number.isInteger(info.port) || info.port < 1 || info.port > 65535 || typeof info.key !== 'string' || !/^[a-f0-9]{64}$/.test(info.key)) throw Error('串流协商无效');
  const socket = net.connect({ host, port: info.port }); socket.setNoDelay(true); socket.setTimeout(6000, () => socket.destroy()); socket.on('error', () => {});
  const abort = () => socket.destroy(); signal?.addEventListener('abort', abort, { once: true }); if (signal?.aborted) abort();
  socket.once('close', () => signal?.removeEventListener('abort', abort));
  try {
    const reader = new BinaryReader(socket), secret = Buffer.from(info.key, 'hex'), sn = await reader.read(32), cn = randomBytes(32);
    socket.write(Buffer.concat([cn, hmac(secret, 'auth', sn, cn)]));
    if (!timingSafeEqual(await reader.read(32), hmac(secret, 'ack', sn, cn))) throw Error('串流身份验证失败');
    const key = hmac(secret, 'media', sn, cn); let seq = 0;
    return { socket, async next() { const size = (await reader.read(4)).readUInt32BE(); return unpackFrame(key, seq++, await reader.read(size)); } };
  } catch (e) { socket.destroy(); throw e; }
}
