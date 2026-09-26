import { createHash, randomBytes } from 'node:crypto';
import { mac } from './wire.js';

// SRP-6a, RFC 5054 Appendix A's fixed 2048-bit group, with SHA-256.
// The short code is never transmitted or used directly as an encryption key.
export const PRIME = 'AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B855F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773BCA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB694B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73';
const N = BigInt('0x' + PRIME), g = 2n;
const hash = (...parts) => createHash('sha256').update(Buffer.concat(parts.map(p => Buffer.isBuffer(p) ? p : Buffer.from(p)))).digest();
const integer = b => BigInt('0x' + b.toString('hex'));
const pad = n => Buffer.from(n.toString(16).padStart(512, '0'), 'hex');
const k = integer(hash(pad(N), pad(g)));
function pow(base, exp) { let out = 1n; base = (base % N + N) % N; while (exp) { if (exp & 1n) out = out * base % N; base = base * base % N; exp >>= 1n; } return out; }
function publicValue(value) {
  if (typeof value !== 'string' || !/^[a-f0-9]{512}$/.test(value)) throw new Error('配对握手无效');
  const n = BigInt('0x' + value); if (n <= 1n || n >= N - 1n) throw new Error('配对握手无效'); return n;
}
const privateValue = () => integer(randomBytes(32)) + 1n;
function exponent(code, salt, id) {
  if (!/^\d{8}$/.test(code) || !/^[a-f0-9]{32}$/.test(salt) || !/^[a-f0-9]{32}$/.test(id)) throw new Error('配对信息无效');
  return integer(hash(Buffer.from(salt, 'hex'), hash('lanlink-pair-v2/' + id + ':' + code)));
}
function keys(S, A, B, salt, id) {
  const context = hash('lanlink-pair-v2', Buffer.from(id, 'hex'), Buffer.from(salt, 'hex'), pad(A), pad(B));
  const key = hash(pad(S), context);
  return { key, clientProof: mac(key, 'client|' + context.toString('hex')), serverProof: mac(key, 'server|' + context.toString('hex')) };
}
export function clientStart() { const a = privateValue(); return { a, A: pad(pow(g, a)).toString('hex') }; }
export function clientFinish(client, code, message) {
  const A = publicValue(client.A), B = publicValue(message.B), x = exponent(code, message.salt, message.id);
  const u = integer(hash(pad(A), pad(B))); if (!u) throw new Error('配对握手无效');
  return keys(pow(B - k * pow(g, x), client.a + u * x), A, B, message.salt, message.id);
}
export function serverStart(code, salt, id, value) {
  const A = publicValue(value), v = pow(g, exponent(code, salt, id)), b = privateValue();
  const B = (k * v + pow(g, b)) % N, u = integer(hash(pad(A), pad(B)));
  if (!u) throw new Error('配对握手无效');
  return { ...keys(pow(A * pow(v, u), b), A, B, salt, id), B: pad(B).toString('hex') };
}
