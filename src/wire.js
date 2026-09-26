import { EventEmitter } from 'node:events';
import { randomBytes, createHmac, createHash, hkdfSync, createCipheriv, createDecipheriv, timingSafeEqual, randomUUID } from 'node:crypto';

const MAX_LINE = 8 * 1024 * 1024;
export function mac(secret, message) { return createHmac('sha256', secret).update(message).digest('hex'); }
export function equal(a, b) { return typeof a === 'string' && typeof b === 'string' && a.length === b.length && timingSafeEqual(Buffer.from(a), Buffer.from(b)); }
export function sessionKeys(secret, a, b) {
  const endpoints = [a, b].sort((x, y) => x.id.localeCompare(y.id, 'en'));
  const transcript = endpoints.map(x => `${x.id}:${x.nonce}`).join('|');
  const salt = createHash('sha256').update(transcript).digest();
  const derive = direction => Buffer.from(hkdfSync('sha256', secret, salt, 'lanlink-v1/' + direction, 32));
  const first = a.id === endpoints[0].id;
  return { transcript, tx: derive(first ? 'a-b' : 'b-a'), rx: derive(first ? 'b-a' : 'a-b') };
}
function iv(seq) { const b = Buffer.alloc(12); b.writeBigUInt64BE(BigInt(seq), 4); return b; }
export function seal(key, seq, value) {
  const c = createCipheriv('aes-256-gcm', key, iv(seq)); c.setAAD(Buffer.from('lanlink-v1:' + seq));
  return Buffer.concat([c.update(JSON.stringify(value)), c.final(), c.getAuthTag()]).toString('base64');
}
export function unseal(key, seq, body) {
  const b = Buffer.from(body, 'base64'); if (b.length < 16) throw new Error('Truncated frame');
  const c = createDecipheriv('aes-256-gcm', key, iv(seq)); c.setAAD(Buffer.from('lanlink-v1:' + seq)); c.setAuthTag(b.subarray(-16));
  return JSON.parse(Buffer.concat([c.update(b.subarray(0, -16)), c.final()]).toString('utf8'));
}
export class Peer extends EventEmitter {
  constructor(socket, identity, secret) {
    super(); this.socket = socket; this.secret = secret; this.pending = new Map(); this.tx = 0; this.rx = 0; this.ready = false; this.closed = false;
    this.hello = { v: 1, id: identity.id, nonce: randomBytes(32).toString('hex') };
    this.identity = identity; this.buffer = ''; socket.setEncoding('utf8'); socket.setNoDelay(true);
    this.timer = setTimeout(() => this.close(), 6000);
    socket.on('data', chunk => { try { this.consume(chunk); } catch (e) { this.failure = e.message; this.close(); } });
    socket.on('error', () => this.close()); socket.on('close', () => this.close());
    this.line({ kind: 'hello', ...this.hello });
  }
  line(value) {
    const line = JSON.stringify(value) + '\n';
    if (line.length > MAX_LINE || this.socket.writableLength > 16 * 1024 * 1024) { this.close(); throw new Error('连接缓冲区已满'); }
    if (!this.closed) this.socket.write(line);
  }
  consume(chunk) {
    this.buffer += chunk;
    for (;;) {
      const i = this.buffer.indexOf('\n');
      if (i < 0) { if (this.buffer.length > MAX_LINE) throw new Error('Oversize'); return; }
      if (i > MAX_LINE) throw new Error('Oversize');
      const raw = this.buffer.slice(0, i); this.buffer = this.buffer.slice(i + 1); this.frame(JSON.parse(raw));
    }
  }
  frame(m) {
    if (!this.remote) {
      if (m.kind !== 'hello' || m.v !== 1 || !/^[a-f0-9-]{36}$/.test(m.id) || m.id === this.hello.id || !/^[a-f0-9]{64}$/.test(m.nonce)) throw new Error('Bad hello');
      this.remote = m; this.keys = sessionKeys(this.secret, this.hello, m);
      this.line({ kind: 'auth', proof: mac(this.secret, 'auth|' + this.keys.transcript + '|' + this.hello.id) }); return;
    }
    if (!this.ready) {
      if (m.kind !== 'auth' || !equal(m.proof, mac(this.secret, 'auth|' + this.keys.transcript + '|' + this.remote.id))) throw new Error('Unauthorized');
      this.ready = true; clearTimeout(this.timer); this.send({ type: 'identity', name: this.identity.name, platform: this.identity.platform, capabilities: this.identity.capabilities });
      this.emit('ready'); return;
    }
    if (m.seq !== this.rx || typeof m.body !== 'string') throw new Error('Replay or bad sequence');
    const value = unseal(this.keys.rx, this.rx++, m.body);
    if (value.type === 'response') {
      const p = this.pending.get(value.id); if (!p) return;
      clearTimeout(p.timer); this.pending.delete(value.id); value.error ? p.reject(new Error(value.error)) : p.resolve(value.result);
    } else this.emit('message', value);
  }
  send(value) {
    if (!this.ready || this.closed) throw new Error('设备未连接');
    this.line({ seq: this.tx, body: seal(this.keys.tx, this.tx++, value) });
  }
  request(method, args = {}, timeout = 15000) {
    if (this.pending.size >= 32) return Promise.reject(new Error('请求过多'));
    return new Promise((resolve, reject) => {
      const id = randomUUID(); const timer = setTimeout(() => { this.pending.delete(id); reject(new Error('设备响应超时')); }, timeout);
      this.pending.set(id, { resolve, reject, timer });
      try { this.send({ type: 'request', id, method, args }); } catch (e) { clearTimeout(timer); this.pending.delete(id); reject(e); }
    });
  }
  close() {
    if (this.closed) return; this.closed = true; clearTimeout(this.timer); this.socket.destroy();
    for (const p of this.pending.values()) { clearTimeout(p.timer); p.reject(new Error('连接已断开')); } this.pending.clear(); this.emit('closed');
  }
}
