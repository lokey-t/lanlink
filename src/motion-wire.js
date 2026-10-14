// Version 2 is shared with Android. All numbers use network byte order.
// The fixed 104-byte record crosses FFI/JNI and QUIC without JSON or Base64.
export const MOTION_BYTES = 104;
export function encodeMotion(m) {
  if (!/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(m.epoch)) throw Error('Invalid motion epoch');
  const b = Buffer.alloc(MOTION_BYTES);
  b[0] = 2; b[1] = (m.kind === 'move' ? 1 : 0) | (m.raw ? 2 : 0);
  Buffer.from(m.epoch.replaceAll('-', ''), 'hex').copy(b, 8);
  for (const [offset, value] of [[24,m.seq],[32,m.time],[40,m.afterInput || 0]]) {
    if (!Number.isSafeInteger(value) || value < 0) throw Error('Invalid motion sequence');
    b.writeBigUInt64BE(BigInt(value), offset);
  }
  for (const [offset, value] of [[48,m.dx],[56,m.dy],[64,m.recentDx],[72,m.recentDy],[80,m.x || 0],[88,m.y || 0],[96,m.ageMs || 0]]) {
    if (!Number.isFinite(value)) throw Error('Invalid motion value');
    b.writeDoubleBE(value, offset);
  }
  return b;
}
export function decodeMotion(b) {
  if (b.length !== MOTION_BYTES || b[0] !== 2 || (b[1] & ~3) || b.subarray(2,8).some(n => n)) throw Error('Invalid motion record');
  const hex = b.subarray(8,24).toString('hex');
  const epoch = `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
  const integer = offset => { const n = Number(b.readBigUInt64BE(offset)); if (!Number.isSafeInteger(n)) throw Error('Invalid motion sequence'); return n; };
  const m = {epoch,seq:integer(24),time:integer(32),afterInput:integer(40),kind:b[1]&1?'move':'relative',dx:b.readDoubleBE(48),dy:b.readDoubleBE(56),recentDx:b.readDoubleBE(64),recentDy:b.readDoubleBE(72),ageMs:b.readDoubleBE(96)};
  if (b[1]&1) Object.assign(m,{x:b.readDoubleBE(80),y:b.readDoubleBE(88),...(b[1]&2?{raw:true}:{})});
  return m;
}
