import net from 'node:net';
import dgram from 'node:dgram';
import os from 'node:os';
import { randomBytes, randomInt } from 'node:crypto';
import { clientStart, clientFinish, serverStart } from './pairing-crypto.js';
import { equal, seal, unseal } from './wire.js';
import { parseToken } from './identity.js';

export const PAIR_PORT = 45833, CODE_LIFETIME = 60000;
const LEASE = 15000;
const validId = id => typeof id === 'string' && /^[a-f0-9]{32}$/.test(id);
export function broadcasts() {
  const result = new Set(['255.255.255.255', '127.0.0.1']);
  for (const list of Object.values(os.networkInterfaces())) for (const n of list || []) {
    if (n.family !== 'IPv4' || n.internal) continue;
    const ip = n.address.split('.').map(Number), mask = n.netmask.split('.').map(Number);
    result.add(ip.map((v, i) => v | (255 ^ mask[i])).join('.'));
  }
  return result;
}
// Bounded newline channel. Each socket only handles one ephemeral handshake.
function channel(socket, onMessage, onError = () => {}) {
  let buffer = ''; socket.setEncoding('utf8'); socket.setNoDelay(true);
  const timer = setTimeout(() => socket.destroy(), 6000);
  socket.on('error', () => {}); socket.on('close', () => clearTimeout(timer));
  socket.on('data', data => {
    try {
      buffer += data; if (buffer.length > 8000000) throw new Error('Oversize');
      let i; while ((i = buffer.indexOf('\n')) >= 0 && !socket.destroyed) { const raw = buffer.slice(0, i); buffer = buffer.slice(i + 1); Promise.resolve(onMessage(JSON.parse(raw))).catch(error => { onError(error); socket.destroy(); }); }
    } catch { socket.destroy(); }
  });
  return value => socket.write(JSON.stringify(value) + '\n');
}
export class Pairing {
  constructor(service, now = Date.now) { this.service = service; this.now = now; this.sockets = new Set(); this.attempts = []; }
  async start() {
    this.server = net.createServer(s => this.accept(s));
    await new Promise((resolve, reject) => { this.server.once('error', reject); this.server.listen(0, this.service.options.host || '0.0.0.0', resolve); });
    this.server.on('error', e => this.service.log(e.message)); this.port = this.server.address().port;
    if (this.service.options.noDiscovery) return;
    this.udp = dgram.createSocket({ type: 'udp4', reuseAddr: true }); this.udp.on('error', e => this.service.log('配对发现不可用：' + e.message));
    this.udp.on('message', (raw, remote) => {
      try {
        if (raw.length > 256 || !this.current()) return;
        const q = JSON.parse(raw); if (q.kind !== 'lanlink-pair-find-v2' || !validId(q.nonce)) return;
        const reply = { kind: 'lanlink-pair-offer-v2', nonce: q.nonce, id: this.invite.id, port: this.port };
        this.udp.send(JSON.stringify(reply), remote.port, remote.address, () => {});
      } catch {}
    });
    await new Promise(resolve => {
      this.udp.once('listening', resolve); this.udp.once('error', resolve);
      this.udp.bind(this.service.options.pairingDiscoveryPort ?? PAIR_PORT);
    });
  }
  current() { return this.invite && this.invite.expiresAt > this.now() && this.leaseUntil > this.now(); }
  show() {
    if (!this.current()) this.invite = { id: randomBytes(16).toString('hex'), salt: randomBytes(16).toString('hex'), code: String(randomInt(100000000)).padStart(8, '0'), expiresAt: this.now() + CODE_LIFETIME };
    this.leaseUntil = this.now() + LEASE;
    const { id, code, expiresAt } = this.invite;
    return { id, code, expiresAt, remainingMs: expiresAt - this.now(), durationMs: CODE_LIFETIME };
  }
  hide() { this.invite = null; this.leaseUntil = 0; for (const s of this.sockets) s.destroy(); }
  accept(socket) {
    if (!this.current() || this.sockets.size >= 8) { socket.destroy(); return; }
    this.sockets.add(socket); socket.on('close', () => this.sockets.delete(socket));
    let session, invite, reverse = false, pendingGroup, pendingRevision, applying = false;
    const send = channel(socket, async m => {
      if (applying) { socket.destroy(); return; }
      if (pendingGroup) {
        if (m.kind !== 'merge') throw Error('配对握手失败');
        applying = true;
        const result = unseal(session.key, 3, m.body), group = this.service.group;
        if (group !== pendingGroup || group.data.revision !== pendingRevision) throw Error('组已发生变化，请重新配对');
        if (Object.values(group.data.members).some(member => result.group?.members?.[member.id]?.key !== member.key)) throw Error('组公钥信息无效');
        // Keep only this authenticated exchange alive while the group transition revokes invitations.
        this.sockets.delete(socket);
        const { applyPairingResult } = await import('./actions.js');
        group.rememberMigrations(result.migrations);
        result.migrations = group.migration(result.group, result.token);
        await applyPairingResult(this.service, result);
        send({ kind: 'merged', body: seal(session.key, 4, { group: group.data.id, migrations: this.service.config.mergedGroups }) }); socket.end(); return;
      }
      if (!this.current()) { socket.destroy(); return; }
      if (!session) {
        if (m.kind !== 'hello' || m.id !== this.invite.id) { socket.destroy(); return; }
        this.attempts = this.attempts.filter(t => t > this.now() - CODE_LIFETIME);
        if (this.attempts.length >= 10) { send({ error: '尝试过于频繁，请稍后重试' }); socket.end(); return; }
        this.attempts.push(this.now()); invite = this.invite;
        session = serverStart(invite.code, invite.salt, invite.id, m.A);
        reverse = m.retainGroup === true && m.group !== this.service.group?.data.id;
        if (reverse && !this.service.group) { send({ error: '对方版本不支持保留原组，请升级或让新设备输入原组配对码' }); socket.end(); return; }
        send({ kind: 'challenge', id: invite.id, salt: invite.salt, B: session.B, retainGroup: reverse || m.group === this.service.group?.data.id }); return;
      }
      if (invite !== this.invite || m.kind !== 'proof' || !equal(m.proof, session.clientProof)) { socket.destroy(); return; }
      // Consume this code before releasing the encrypted account credential.
      this.invite = null;
      if (reverse) {
        pendingGroup = this.service.group; pendingRevision = pendingGroup.data.revision;
        send({ kind: 'joined', proof: session.serverProof, body: seal(session.key, 0, { token: this.service.config.token, port: this.service.port, reverse: true, member: pendingGroup.own, group: pendingGroup.export() }) }); return;
      }
      if (m.member && this.service.group) await this.service.group.admit(unseal(session.key, 1, m.member));
      send({ kind: 'joined', proof: session.serverProof, body: seal(session.key, 0, { token: this.service.config.token, port: this.service.port, group: this.service.group?.export() }) }); socket.end();
    }, error => this.service.log('配对失败：' + error.message));
  }
  async join(code, targetId, dissolveCurrentGroup = false) {
    if (typeof code !== 'string' || !/^\d{8}$/.test(code)) throw new Error('请输入八位数字配对码');
    if (this.joining) throw new Error('正在配对，请稍候');
    this.joining = true;
    try {
      const offers = await discover(targetId, this.service.options.pairingDestinations ?? broadcasts(), this.service.options.pairingDiscoveryPort ?? PAIR_PORT);
      const candidates = offers.filter(o => o.id !== this.invite?.id);
      if (!candidates.length) throw new Error('未找到待配对设备，请在同一局域网的另一台设备打开“添加设备”');
      // All matching account responses must agree; never pick an arbitrary code collision.
      const retain = this.service.group?.established && !dissolveCurrentGroup ? this.service.group : undefined;
      const results = await Promise.allSettled(candidates.map(o => exchange(o, code, this.service.group?.own, retain)));
      const successes = results.filter(r => r.status === 'fulfilled').map(r => r.value);
      if (!successes.length) {
        const unsupported = results.find(r => r.status === 'rejected' && /原组|每个组|离线成员/.test(r.reason.message));
        throw unsupported?.reason || new Error('配对码错误、已过期或尝试过于频繁，请查看对方最新配对码');
      }
      if (new Set(successes.map(s => s.token)).size !== 1) throw new Error('配对码冲突，请刷新后重试或扫描二维码');
      return successes[0];
    } finally { this.joining = false; }
  }
  async close() { this.hide(); if (this.udp) { try { this.udp.close(); } catch {} } if (this.server) await new Promise(r => this.server.close(r)); }
}
export async function discover(targetId, destinations = broadcasts(), port = PAIR_PORT) {
  if (targetId !== undefined && !validId(targetId)) throw new Error('二维码无效');
  const socket = dgram.createSocket('udp4'), nonce = randomBytes(16).toString('hex'), found = new Map();
  return new Promise((resolve, reject) => {
    let timer, repeat;
    const finish = error => { clearTimeout(timer); clearTimeout(repeat); try { socket.close(); } catch {} error ? reject(error) : resolve([...found.values()]); };
    socket.on('error', () => finish(new Error('无法发现设备，请检查局域网连接')));
    socket.on('message', (raw, rinfo) => {
      try { if (raw.length > 512 || found.size >= 16) return; const m = JSON.parse(raw); if (m.kind === 'lanlink-pair-offer-v2' && m.nonce === nonce && validId(m.id) && (!targetId || targetId === m.id) && Number.isInteger(m.port) && m.port > 0 && m.port <= 65535) found.set(m.id, { id: m.id, host: rinfo.address, port: m.port }); } catch {}
    });
    socket.bind(0, () => {
      socket.setBroadcast(true); const send = () => { for (const host of destinations) socket.send(JSON.stringify({ kind: 'lanlink-pair-find-v2', nonce }), port, host, () => {}); };
      send(); repeat = setTimeout(send, 450); timer = setTimeout(() => finish(), 1000);
    });
  });
}
export function exchange(offer, code, member, retainGroup) {
  return new Promise((resolve, reject) => {
    const socket = net.connect({ host: offer.host, port: offer.port }); const client = clientStart(); let keys, done = false;
    const fail = error => { if (!done) { done = true; reject(error instanceof Error ? error : new Error('配对未完成')); } };
    socket.on('error', fail); socket.on('close', fail);
    let merging;
    const send = channel(socket, async m => { try {
      if (m.error) throw Error(m.error);
      if (merging) {
        const ack = unseal(keys.key, 4, m.body);
        if (m.kind !== 'merged' || ack.group !== retainGroup.data.id) throw Error('配对握手失败');
        done = true;
        if (retainGroup.rememberMigrations(ack.migrations)) { const { saveConfig } = await import('./identity.js'); await saveConfig(retainGroup.service.dir, retainGroup.service.config); retainGroup.publish(); }
        done = true; resolve({ ...merging, host: offer.host }); socket.end(); return;
      }
      if (!keys) {
        if (m.kind !== 'challenge' || m.id !== offer.id) throw new Error('握手失败');
        if (retainGroup && m.retainGroup !== true) throw Error('对方版本不支持保留原组，请升级或让新设备输入原组配对码');
        keys = clientFinish(client, code, m); send({ kind: 'proof', proof: keys.clientProof, member: member ? seal(keys.key, 1, member) : undefined }); return;
      }
      if (m.kind !== 'joined' || !equal(m.proof, keys.serverProof) || typeof m.body !== 'string') throw new Error('握手失败');
      const result = unseal(keys.key, 0, m.body); parseToken(result.token);
      if (!Number.isInteger(result.port) || result.port < 1 || result.port > 65535) throw new Error('端口无效');
      if (result.reverse) {
        if (!retainGroup || retainGroup.service.group !== retainGroup) throw Error('组已发生变化，请重新配对');
        await retainGroup.admitGroup(result.group);
        merging = { token: retainGroup.service.config.token, group: retainGroup.export(), port: result.port };
        send({ kind: 'merge', body: seal(keys.key, 3, { token: merging.token, group: merging.group, migrations: retainGroup.service.config.mergedGroups }) }); return;
      }
      done = true; resolve({ ...result, host: offer.host }); socket.end();
    } catch (error) { fail(error); reject(error); socket.destroy(); }
    });
    socket.once('connect', () => send({ kind: 'hello', id: offer.id, A: client.A, ...(retainGroup ? { retainGroup: true, group: retainGroup.data.id } : {}) }));
  });
}
