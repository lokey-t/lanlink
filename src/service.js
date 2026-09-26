import net from 'node:net';
import { EventEmitter } from 'node:events';
import { randomUUID, createHash } from 'node:crypto';
import { mkdir, open, stat, rename, rm } from 'node:fs/promises';
import path from 'node:path';
import { Peer } from './wire.js';
import { parseToken, saveConfig, accountId } from './identity.js';
import { startDiscovery } from './discovery.js';
import { EdgeSwitch } from './edge.js';
import { Pairing } from './pairing.js';
import { Group } from './group.js';
import { Consent } from './consent.js';
import { VideoHost, VideoViewer } from './video.js';
import { permissionReport } from './permissions.js';
import { AdbManager } from './adb.js';

const CHUNK = 192 * 1024, MAX_FILE = 2 * 1024 ** 3;
export function safeName(name) {
  if (typeof name !== 'string' || !name || name.length > 180 || /[<>:"/\\|?*\x00-\x1f]/.test(name) || /[. ]$/.test(name) || /^\.+$/.test(name) || /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(name)) throw new Error('文件名不安全或不兼容');
  return name;
}
export function validateInput(a) {
  if (!a || typeof a !== 'object') throw new Error('输入无效');
  if (a.kind === 'relative' && [a.dx, a.dy].every(n => typeof n === 'number' && Number.isFinite(n) && Math.abs(n) <= 1000)) return a;
  if (a.kind === 'move' && [a.x, a.y].every(n => typeof n === 'number' && Number.isFinite(n) && n >= 0 && n <= 1)) return a;
  if (a.kind === 'key' && /^(Key[A-Z]|Digit[0-9]|Numpad[0-9]|F([1-9]|1[0-2])|Arrow(Left|Right|Up|Down)|Control(Left|Right)|Shift(Left|Right)|Alt(Left|Right)|Meta(Left|Right)|Backspace|Tab|Enter|Escape|Space|Delete|Insert|Home|End|PageUp|PageDown|CapsLock|Semicolon|Equal|Comma|Minus|Period|Slash|Backquote|BracketLeft|BracketRight|Backslash|Quote)$/.test(a.code) && typeof a.down === 'boolean') return a;
  if (a.kind === 'button' && [0, 1, 2].includes(a.button) && typeof a.down === 'boolean') return a;
  if (a.kind === 'wheel' && Number.isFinite(a.delta) && Math.abs(a.delta) <= 2400) return a;
  throw new Error('输入事件无效');
}
export class Service extends EventEmitter {
  constructor(config, dir, native, options = {}) {
    super(); Object.assign(this, { config, dir, native, options }); this.peers = new Map(); this.sockets = new Set(); this.connecting = new Set(); this.edge = new EdgeSwitch(); this.offers = new Map(); this.position = { x: .5, y: .5 }; this.keys = new Set(); this.logs = []; this.owner = null; this.active = null;
    this.config.deviceGrants ||= {}; this.consent = new Consent(this.config.deviceGrants); this.permissionNotice = null;
    this.group = new Group(this); this.config.pausedPeers ||= {};
    this.adb = new AdbManager(this);
    this.outgoingIntents = new Set(); this.screenViewers = new Map(); this.screenTargets = new Map(); this.returning = 0;
    this.videoHost = new VideoHost(this); this.videoViewer = new VideoViewer(this);
    this.native.on('event', e => this.nativeEvent(e).catch(err => this.log(err.message)));
    this.native.on('diagnostic', m => this.log(m));
  }
  log(message) { this.logs.unshift({ time: Date.now(), message: String(message).slice(0, 300) }); this.logs.length = Math.min(this.logs.length, 30); this.emit('change'); }
  identity() { return { id: this.config.id, name: this.config.name, platform: process.platform, capabilities: { ...this.native.capabilities, memberKey: this.group.own.key } }; }
  async start() {
    const pending = this.config.pendingGroupJoin;
    if (this.config.dissolvedGroups?.[this.group.data.id] || pending && this.config.dissolvedGroups?.[pending.previousGroup]) {
      if (pending?.group) this.group.adopt(pending.group, pending.token);
      else { const { newToken } = await import('./identity.js'); this.config.token = newToken(); delete this.config.group; this.group = new Group(this); }
      this.config.knownDevices = {}; this.config.deviceGrants = {}; this.config.edges = {}; this.config.pausedPeers = {}; this.consent.reset(this.config.deviceGrants);
    }
    delete this.config.pendingGroupJoin;
    await saveConfig(this.dir, this.config); this.group.start(); await this.native.start(); this.secret = parseToken(this.config.token);
    this.server = net.createServer(s => this.attach(s));
    await new Promise((resolve, reject) => { this.server.once('error', reject); this.server.listen(this.options.port ?? 45831, this.options.host || '0.0.0.0', resolve); });
      this.server.on('error', e => this.log('网络监听失败：' + e.message)); this.port = this.server.address().port;
    this.pairing = new Pairing(this); await this.pairing.start();
    if (!this.options.noDiscovery) this.stopDiscovery = startDiscovery(this.identity(), this.secret, this.port, (b, address) => {
      if (this.config.id < b.id && !this.peers.has(b.id)) this.connect(address, b.port);
    }, e => this.log('自动发现不可用，可手动连接：' + e.message));
    this.adbTimer = setInterval(() => this.adb.tick().catch(e => this.log(e.message)), 10000); this.adbTimer.unref();
    this.heartbeat = setInterval(() => this.tick().catch(e => this.log(e.message)), 1000);
    this.edgeTimer = setInterval(() => { if (this.active && this.capture && !this.switching) this.checkReturnEdge(); }, 25);
    if (this.config.permissions.clipboard) await this.snapshotClipboard().catch(e => this.log(e.message));
  }
  attach(socket, outgoing = false) {
    if (this.sockets.size >= 32) { socket.destroy(); return; }
    const peer = new Peer(socket, this.identity(), this.secret); peer.outgoing = outgoing; this.sockets.add(peer);
    peer.on('ready', () => {
      if (this.group.excluded(peer.remote.id)) { peer.close(); return; }
      const prior = this.peers.get(peer.remote.id);
      if (prior && !prior.closed) { const preferOutgoing = this.config.id < peer.remote.id; if (peer.outgoing === preferOutgoing && prior.outgoing !== preferOutgoing) { this.peers.set(peer.remote.id, peer); prior.close(); } else { peer.close(); return; } }
      this.peers.set(peer.remote.id, peer); peer.lastSeen = Date.now(); this.log('已建立加密连接');
      if (this.config.permissions.clipboard && this.consent.allowed(peer.remote.id, 'clipboard') && this.clipboard?.source === this.config.id) peer.send({ type: 'clipboard', offer: this.clipboard });
    });
    peer.on('message', m => this.message(peer, m).catch(e => this.log(e.message)));
    peer.on('closed', () => {
      this.sockets.delete(peer);
      if (this.peers.get(peer.remote?.id) !== peer) return;
      this.peers.delete(peer.remote.id);
      this.screenViewers.delete(peer.remote.id); this.screenTargets.delete(peer.remote.id);
      for (const intent of this.outgoingIntents) if (intent.id === peer.remote.id) intent.cancelled = true;
      if (this.controlIntent?.id === peer.remote.id) this.controlIntent.cancelled = true;
      this.videoHost.closePeer(peer.remote.id); this.videoViewer.close(peer.remote.id).catch(() => {});
      this.consent.disconnect(peer.remote.id); if (this.permissionNotice?.id === peer.remote.id) this.permissionNotice = null;
      if (this.active === peer.remote.id) this.returnLocal().catch(e => this.log(e.message));
      if (this.owner?.id === peer.remote.id) { this.owner = null; this.native.call('release').catch(() => {}); }
      this.emit('change');
    });
    return peer;
  }
  connect(host, port = 45831) {
    if (typeof host !== 'string' || !/^[a-zA-Z0-9.:_-]{1,253}$/.test(host) || !Number.isInteger(port) || port < 1 || port > 65535) throw new Error('设备地址或端口无效');
    const key = host + ':' + port; if (this.connecting.has(key)) return;
    this.connecting.add(key); const s = net.connect({ host, port }); s.setTimeout(6000, () => s.destroy());
    s.once('connect', () => { s.setTimeout(0); this.attach(s, true); }); s.on('error', () => {}); s.once('close', () => this.connecting.delete(key));
  }
  getPeer(id) { const p = this.peers.get(id); if (!p || p.closed) throw new Error('设备不在线'); return p; }
  allow(name) { if (!this.config.permissions[name]) throw new Error('目标设备尚未允许此功能：' + name); }
  liveScreens(map) { for (const [id, until] of map) if (until <= Date.now()) map.delete(id); return [...map.keys()]; }
  assertCanReceiveControl() {
    if (this.closing || this.changingGroup) throw Error('CONTROL_ENDED: 当前连接正在结束');
    if (this.active || this.switching || this.returning || this.outgoingIntents.size || this.liveScreens(this.screenTargets).length || [...this.videoViewer.sessions.values()].some(s => !s.closed)) throw Error('CONTROL_CONFLICT: 本设备正在控制或查看其他设备，请先结束当前会话');
  }
  assertCanSendControl() {
    if (this.closing || this.changingGroup) throw Error('CONTROL_ENDED: 当前连接正在结束');
    if (this.owner?.until > Date.now() || this.capturing || this.videoHost.sessions.size || this.liveScreens(this.screenViewers).length) throw Error('CONTROL_CONFLICT: 本设备正在被控制或查看，请先结束被控会话');
  }
  beginOutgoing(id) { this.assertCanSendControl(); this.getPeer(id); const intent = { id, cancelled: false }; this.outgoingIntents.add(intent); return intent; }
  checkOutgoing(intent) { if (intent.cancelled) throw Error('CONTROL_ENDED: 控制请求已取消'); this.assertCanSendControl(); this.getPeer(intent.id); }
  endOutgoing(intent) { this.outgoingIntents.delete(intent); }
  async screenFrom(id) {
    const intent = this.beginOutgoing(id);
    try { const frame = await this.getPeer(id).request('screen'); this.checkOutgoing(intent); this.screenTargets.set(id, Date.now() + 4000); return frame; }
    catch (e) { if (intent.cancelled) await this.peers.get(id)?.request('screenStop', {}, 1500).catch(() => {}); throw e; }
    finally { this.endOutgoing(intent); }
  }
  openStream(id, jpegOnly = false) { return this.videoViewer.open(id, jpegOnly); }
  async closeStream(id) { const hadScreen = this.screenTargets.delete(id); for (const intent of this.outgoingIntents) if (intent.id === id) intent.cancelled = true; await this.videoViewer.close(id); if (hadScreen) await this.peers.get(id)?.request('screenStop', {}, 1500).catch(() => {}); }
  showFallback(id, edge, capture) { this.fallbackNotice = { id, edge, capture }; this.edge.reset(); this.emit('change'); }
  needsFallback(peer, capabilities = peer.info?.capabilities) { return peer.info?.platform === 'android' && capabilities?.inputMode === 'accessibility-limited'; }
  canShareClipboard(id) { return this.consent.allowed(id, 'clipboard') || this.active === id && !Object.hasOwn(this.config.deviceGrants, id); }
  authorize(peer, permission) {
    if (this.config.dissolvedGroups?.[this.group.data.id]) throw Error('当前组已解散');
    if (this.group.excluded(peer.remote.id) || this.peers.get(peer.remote.id) !== peer || peer.closed) throw new Error('设备连接已结束');
    if (this.config.pausedPeers[peer.remote.id]) throw Error('CONTROL_ENDED: 被控设备已结束控制，请重新请求');
    if (permission === 'input' || permission === 'screen') this.assertCanReceiveControl();
    // A local user who is actively controlling a target may transfer their clipboard to it.
    // This is session-scoped and never overrides an explicit saved per-device denial.
    if (permission !== 'clipboard' || !this.canShareClipboard(peer.remote.id)) this.consent.require(peer.remote.id, peer.info?.name, permission);
    if (!this.config.permissions[permission] || this.native.capabilities[permission] === false) {
      this.permissionNotice = { id: peer.remote.id, name: peer.info?.name || '已配对设备', permission, expires: Date.now() + 120000 };
      throw new Error('PERMISSION_REQUIRED: 被控设备尚未允许此功能或缺少系统权限，请在被控设备开启');
    }
  }
  async decideConsent(a) {
    this.getPeer(a.id); this.consent.decide(a.id, a.requestId, a.accepted === true, a.permissions);
    const previous = { ...this.config.permissions };
    if (a.accepted === true) for (const k of ['input', 'screen', 'clipboard', 'shutdown']) if (a.permissions?.[k] === true) this.config.permissions[k] = true;
    try { await saveConfig(this.dir, this.config); } catch (e) { this.config.permissions = previous; delete this.config.deviceGrants[a.id]; throw e; } this.emit('change');
  }
  async revokeConsent(id) {
    if (typeof id !== 'string' || !Object.hasOwn(this.config.deviceGrants, id)) return;
    delete this.config.deviceGrants[id]; this.consent.disconnect(id);
    this.screenViewers.delete(id);
    this.videoHost.closePeer(id);
    if (this.owner?.id === id) { this.owner = null; await this.native.call('release'); }
    if (this.clipboard?.source === id) this.clipboard = null;
    this.cancelShutdown(); await saveConfig(this.dir, this.config); this.emit('change');
  }
  requireOwner(peer) { this.authorize(peer, 'input'); if (this.owner?.id !== peer.remote.id || this.owner.until < Date.now()) throw new Error('请先取得控制权'); this.owner.until = Date.now() + 4000; }
  async message(peer, m) {
    if(this.closing) return;
    peer.lastSeen = Date.now();
    if (this.group.excluded(peer.remote.id)) { peer.close(); return; }
    if (m.type === 'group-dissolved') { await this.group.acceptDissolution(m.notice); return; }
    if (m.type === 'group-members') { await this.group.merge(m); return; }
    if (m.type === 'group-removed') { await this.leaveGroup(); return; }
    if (m.type === 'control-ended') { this.controlEnded = { id: peer.remote.id, at: Date.now() }; if (this.active === peer.remote.id || this.controlIntent?.id === peer.remote.id) await this.returnLocal(); await this.closeStream(peer.remote.id); return; }
    if (m.type === 'identity') { peer.info = { name: String(m.name).slice(0, 80), platform: String(m.platform).slice(0, 30), capabilities: m.capabilities || {} }; if (this.active === peer.remote.id && this.needsFallback(peer) && !this.fallbackAccepted) { const edge = this.enteredEdge, capture = this.capture; await this.returnLocal(); this.showFallback(peer.remote.id, edge, capture); } this.config.knownDevices ||= {}; const old = this.config.knownDevices[peer.remote.id]; const info = { name: peer.info.name, platform: peer.info.platform }; this.config.knownDevices[peer.remote.id] = info; if (JSON.stringify(old) !== JSON.stringify(info)) await saveConfig(this.dir, this.config); if (this.group.learn({ id: peer.remote.id, ...info, key: m.capabilities?.memberKey })) { await saveConfig(this.dir, this.config); this.group.publish(); } this.emit('change'); return; }
    if (m.type === 'ping') { peer.send({ type: 'pong' }); return; }
    if (m.type === 'pong') return;
    if (m.type === 'clipboard') { if (this.config.permissions.clipboard && this.canShareClipboard(peer.remote.id)) { this.clipboard = { ...this.validateOffer(m.offer), source: peer.remote.id }; this.emit('change'); } return; }
    if (m.type !== 'request' || typeof m.id !== 'string' || m.id.length > 64) return;
    peer.inFlight = (peer.inFlight || 0) + 1;
    if (peer.inFlight > 32) { peer.close(); return; }
    try { const result = await this.handle(peer, m.method, m.args || {}); if (!peer.closed) peer.send({ type: 'response', id: m.id, result }); }
    catch (e) { if (!peer.closed) peer.send({ type: 'response', id: m.id, error: e.message }); }
    finally { peer.inFlight--; }
  }
  async handle(peer, method, a) {
    if (this.group.excluded(peer.remote.id)) throw Error('设备已移出组');
    if (method === 'permission-status') {
      if (this.peers.get(peer.remote.id) !== peer || peer.closed) throw Error('设备连接已结束');
      await this.refreshCapabilities(); return permissionReport(this, peer.remote.id);
    }
    if (method === 'group-update') { await this.group.accept(a.box); return {}; }
    if (method === 'group-dissolve') { await this.group.acceptDissolution(a.notice); return {}; }
    if (method === 'videoOpen') return this.videoHost.open(peer, a);
    if (method === 'videoKeep') return this.videoHost.keep(peer, a.id, a.feedback);
    if (method === 'videoStop') return this.videoHost.stop(peer, a.id);
    if (method === 'screenStop') { this.screenViewers.delete(peer.remote.id); return {}; }
    if (method === 'acquire') {
      this.assertCanReceiveControl();
      if (a.newSession === true && this.config.pausedPeers[peer.remote.id]) { delete this.config.pausedPeers[peer.remote.id]; delete this.config.deviceGrants[peer.remote.id]; this.consent.disconnect(peer.remote.id); await saveConfig(this.dir, this.config); }
      this.authorize(peer, 'input'); if (this.owner && this.owner.id !== peer.remote.id && this.owner.until > Date.now()) throw new Error('CONTROL_CONFLICT: 设备正在被其他来源控制');
      this.owner = { id: peer.remote.id, until: Date.now() + 4000 }; return this.native.capabilities;
    }
    if (method === 'release') { this.screenViewers.delete(peer.remote.id); if (this.owner?.id === peer.remote.id) { this.owner = null; await this.native.call('release'); } return {}; }
    if (method === 'keepControl') { this.requireOwner(peer); await this.native.call('heartbeat'); return {}; }
    if (method === 'input') { this.requireOwner(peer); return this.native.call('input', validateInput(a)); }
    if (method === 'text') { this.requireOwner(peer); if (typeof a.text !== 'string' || a.text.length > 100000) throw new Error('文本过长'); return this.native.call('text', a); }
    if (method === 'screen') { this.authorize(peer, 'screen'); if (this.capturing) throw new Error('屏幕正在捕获'); this.capturing = true; this.screenViewers.set(peer.remote.id, Date.now() + 4000); try { const frame = await this.native.call('screen'); this.authorize(peer, 'screen'); return frame; } catch (e) { this.screenViewers.delete(peer.remote.id); throw e; } finally { this.capturing = false; } }
    if (method === 'clipboardGet') { this.authorize(peer, 'clipboard'); return this.snapshotClipboard(false); }
    if (method === 'fileChunk') { this.authorize(peer, 'clipboard'); return this.fileChunk(a); }
    if (method === 'paste') { this.requireOwner(peer); this.authorize(peer, 'clipboard'); if (this.pasting) throw new Error('正在准备粘贴'); this.pasting = true; try { await this.materialize({ ...this.validateOffer(a.offer), source: a.offer.source }); this.requireOwner(peer); this.authorize(peer, 'clipboard'); await this.native.call('paste'); return {}; } finally { this.pasting = false; } }
    if (method === 'shutdown') { this.authorize(peer, 'shutdown'); if (this.shutdownAt) throw new Error('已有待执行关机'); this.shutdownAt = Date.now() + 30000; this.shutdownTimer = setTimeout(() => { this.shutdownAt = null; this.native.call('shutdown').catch(e => this.log('关机未执行：' + e.message)); }, 30000); this.log('收到关机请求，30 秒内可取消'); return { at: this.shutdownAt }; }
    if (method === 'cancelShutdown') { this.cancelShutdown(); return {}; }
    throw new Error('不支持的操作');
  }
  cancelShutdown() { clearTimeout(this.shutdownTimer); this.shutdownAt = null; this.emit('change'); }
  async refreshCapabilities() {
    if (this.capabilityRefresh) return this.capabilityRefresh;
    this.capabilityRefresh = this.native.call('capabilities').then(caps => { this.native.capabilities = { ...this.native.capabilities, ...caps }; }).finally(() => { this.capabilityRefresh = null; });
    return this.capabilityRefresh;
  }
  validateOffer(o) {
    if (!o || typeof o.id !== 'string' || o.id.length > 64 || typeof o.text !== 'string' || o.text.length > 1000000 || !Array.isArray(o.files) || o.files.length > 100) throw new Error('剪贴板数据无效');
    let total = 0;
    for (const f of o.files) { safeName(f.name); if (typeof f.id !== 'string' || f.id.length > 64 || !Number.isSafeInteger(f.size) || f.size < 0 || f.size > MAX_FILE) throw new Error('文件无效或大于 2 GB'); total += f.size; }
    if (total > MAX_FILE) throw new Error('单次传输总量不能超过 2 GB');
    return { id: o.id, text: o.text, files: o.files.map(f => ({ id: f.id, name: f.name, size: f.size })) };
  }
  async snapshotClipboard(broadcast = true) {
    this.allow('clipboard'); const clip = await this.native.call('clipboardGet'); const id = randomUUID(), files = [], entries = new Map();
    if ((clip.files || []).length > 100) throw new Error('每次最多复制 100 个文件');
    for (const location of clip.files || []) {
      const s = await stat(location); if (!s.isFile()) throw new Error('目录请先压缩为 ZIP，再复制');
      const file = { id: randomUUID(), name: safeName(path.basename(location)), size: s.size }; files.push(file); entries.set(file.id, { location, size: s.size, mtime: s.mtimeMs });
    }
    const offer = this.validateOffer({ id, text: clip.text || '', files });
    this.offers.set(id, { entries, until: Date.now() + 3600000 }); while (this.offers.size > 20) this.offers.delete(this.offers.keys().next().value);
    this.clipboard = { ...offer, source: this.config.id };
    if (broadcast) for (const p of this.peers.values()) if (this.canShareClipboard(p.remote.id)) p.send({ type: 'clipboard', offer });
    return this.clipboard;
  }
  async fileChunk(a) {
    const offer = this.offers.get(a.offerId), file = offer?.entries.get(a.fileId);
    if (!file || offer.until < Date.now() || !Number.isSafeInteger(a.offset) || a.offset < 0 || a.offset > file.size) throw new Error('文件分享已过期，请重新复制');
    const s = await stat(file.location); if (s.size !== file.size || s.mtimeMs !== file.mtime) throw new Error('源文件已经变化，请重新复制');
    const h = await open(file.location, 'r'); try { const buffer = Buffer.alloc(Math.min(CHUNK, file.size - a.offset)); const { bytesRead } = await h.read(buffer, 0, buffer.length, a.offset); const data = buffer.subarray(0, bytesRead); return { data: data.toString('base64'), sha256: createHash('sha256').update(data).digest('hex') }; } finally { await h.close(); }
  }
  async materialize(offer) {
    this.allow('clipboard'); this.validateOffer(offer);
    if (!offer.files.length) return this.native.call('clipboardSet', { text: offer.text });
    if (this.receiving) throw new Error('正在接收其他文件，请稍后重试');
    const source = offer.source === this.config.id ? null : this.getPeer(offer.source);
    this.receiving = true;
    const folder = path.join(this.dir, 'received', randomUUID()); const paths = [];
    try {
      await mkdir(folder, { recursive: true });
      for (const f of offer.files) {
        const fileDir = path.join(folder, String(paths.length)); await mkdir(fileDir);
        const destination = path.join(fileDir, safeName(f.name)), temp = destination + '.part';
        const h = await open(temp, 'wx'); let offset = 0;
        try { while (offset < f.size) {
          this.allow('clipboard');
          const args = { offerId: offer.id, fileId: f.id, offset }; const chunk = source ? await source.request('fileChunk', args) : await this.fileChunk(args);
          const data = Buffer.from(chunk.data, 'base64');
          if (!data.length || data.length > CHUNK || offset + data.length > f.size || createHash('sha256').update(data).digest('hex') !== chunk.sha256) throw new Error('文件传输校验失败');
          await h.writeFile(data); offset += data.length; this.transfer = { name: f.name, received: offset, total: f.size };
        }} finally { await h.close(); }
        await rename(temp, destination); paths.push(destination);
      }
      this.allow('clipboard'); await this.native.call('clipboardSet', { files: paths });
    } catch (e) { await rm(folder, { recursive: true, force: true }); throw e; } finally { this.transfer = null; this.receiving = false; }
  }
  async nativeEvent(e) {
    if (e.type === 'cursor' && this.owner && Date.now() - (this.cursorSentAt || 0) >= 32) {
      const peer = this.peers.get(this.owner.id);
      if (peer && !peer.closed && this.owner.until > Date.now() && this.config.permissions.input && this.consent.allowed(this.owner.id,'input') && e.width > 1 && e.height > 1) {
        this.cursorSentAt = Date.now();
        peer.send({ type: 'cursor', x: Math.max(0, Math.min(1, e.x / (e.width - 1))), y: Math.max(0, Math.min(1, e.y / (e.height - 1))) });
      }
      return;
    }
    if (e.type === 'error') { this.log(e.message || '原生操作失败'); return; }
    if (e.type === 'emergency') { if (this.owner) { this.owner = null; await this.native.call('release'); } await this.returnLocal(); return; }
    if (e.type === 'clipboardChanged' && this.config.permissions.clipboard) { await this.snapshotClipboard(); return; }
    if (e.type === 'cursor' && !this.active && this.config.master && !this.owner && !this.switching) {
      const next = this.edge.update(e.x, e.y, e.width, e.height, this.config.edges, this.config.dwell);
      if (next) await this.switchTo(next.target, next.edge); return;
    }
    if (e.type !== 'input' || !this.active) return;
    const p = this.getPeer(this.active);
    if (e.kind === 'move') {
      const width = p.info?.capabilities?.width || 1920, height = p.info?.capabilities?.height || 1080;
      this.position.x = Math.max(0, Math.min(1, this.position.x + e.dx / width)); this.position.y = Math.max(0, Math.min(1, this.position.y + e.dy / height));
      // Serialize/coalesce pointer movement so a slow network never builds an unbounded queue.
      this.pendingMove = { kind: 'move', ...this.position }; if (!this.moving) this.flushMove().catch(err => this.log(err.message)); return;
    }
    if (e.kind === 'key') {
      if (e.down) this.keys.add(e.code); else this.keys.delete(e.code);
      if (e.code === 'KeyV' && (this.keys.has('ControlLeft') || this.keys.has('ControlRight') || this.keys.has('MetaLeft') || this.keys.has('MetaRight'))) { if (e.down) await this.pasteTo(this.active); return; }
    }
    await p.request('input', validateInput(e));
  }
  async flushMove() { this.moving = true; try { while (this.pendingMove && this.active) { const input = this.pendingMove; this.pendingMove = null; await this.getPeer(this.active).request('input', input); } } finally { this.moving = false; } }
  checkReturnEdge() {
    const p = this.peers.get(this.active); if (!p) return;
    const width = p.info?.capabilities?.width || 1920, height = p.info?.capabilities?.height || 1080;
    const opposite = { left: 'right', right: 'left', top: 'bottom', bottom: 'top' }[this.enteredEdge];
    if (this.edge.update(this.position.x * width, this.position.y * height, width, height, { [opposite]: 'local' }, this.config.dwell)) this.returnLocal().catch(e => this.log(e.message));
  }
  async switchTo(id, edge = 'right', capture = true, newSession = false, fallbackAccepted = false) {
    if (this.switching || this.returning) throw Error('CONTROL_CONFLICT: 正在切换控制，请稍后重试'); this.assertCanSendControl();
    if (capture && !this.native.capabilities.host) throw new Error('本机尚未具备全局键鼠权限');
    const p = this.getPeer(id);
    if (this.needsFallback(p) && !fallbackAccepted) { this.showFallback(id, edge, capture); throw Error('FALLBACK_CONFIRMATION: 该手机仅可使用无障碍备用模式，请先确认操作限制'); }
    const intent = { id, cancelled: false }; this.controlIntent = intent; this.switching = true;
    const check = () => { if (intent.cancelled || this.controlIntent !== intent) throw Error('CONTROL_ENDED: 控制请求已取消'); this.assertCanSendControl(); if (this.getPeer(id) !== p) throw Error('设备连接已结束'); };
    try {
      if (this.active) await this.returnLocal(intent); check();
      const capabilities = await p.request('acquire', { newSession, fallbackAccepted }); intent.acquired = true; check();
      if ((this.needsFallback(p) || this.needsFallback(p, capabilities)) && !fallbackAccepted) { this.showFallback(id, edge, capture); throw Error('FALLBACK_CONFIRMATION: 该手机仅可使用无障碍备用模式，请先确认操作限制'); }
      this.active = id; this.activeSession = intent; this.capture = capture; this.enteredEdge = edge; this.fallbackAccepted = fallbackAccepted; this.fallbackNotice = null;
      this.position = { x: edge === 'right' ? .015 : edge === 'left' ? .985 : .5, y: edge === 'bottom' ? .015 : edge === 'top' ? .985 : .5 };
      if (capture) await p.request('input', { kind: 'move', ...this.position }); check(); if (capture) await this.native.call('mode', { remote: true }); check();
      this.edge.reset(); this.log('控制已切换到 ' + (p.info?.name || id));
    } catch (e) {
      if (!intent.cancelled && /^FALLBACK_CONFIRMATION:/.test(e.message)) this.showFallback(id, edge, capture);
      if (this.active === id) await this.returnLocal(intent);
      else if (intent.acquired) await p.request('release').catch(() => {});
      throw e;
    } finally { if (this.controlIntent === intent) { this.controlIntent = null; this.switching = false; } this.emit('change'); }
  }
  async returnLocal(preserveIntent = null) {
    if (this.controlIntent && this.controlIntent !== preserveIntent) this.controlIntent.cancelled = true;
    const previous = this.active; this.active = null; this.activeSession = null; this.capture = false; this.fallbackAccepted = false; this.keys.clear(); this.pendingMove = null; this.edge.reset(); this.screenTargets.delete(previous); this.returning++;
    try {
      await this.native.call('mode', { remote: false }).catch(() => {});
      const p = this.peers.get(previous); if (p) await p.request('release').catch(() => {});
      if (previous) await this.native.call('warp', { x: .5, y: .5 }).catch(() => {});
      if (previous && this.config.permissions.clipboard && this.clipboard && this.clipboard.source !== this.config.id) await this.materialize(this.clipboard).catch(e => this.log('返回本机后同步剪贴板失败：' + e.message));
    } finally { this.returning--; this.emit('change'); }
  }
  async pasteTo(id) {
    if (!this.clipboard) await this.snapshotClipboard();
    return this.getPeer(id).request('paste', { offer: this.clipboard }, 120000);
  }
  async tick() {
    this.consent.list(); if (this.permissionNotice?.expires <= Date.now()) this.permissionNotice = null;
    if (this.permissionNotice) { try { this.native.capabilities = await this.native.call('capabilities'); } catch {} }
    for (const p of this.peers.values()) { if (Date.now() - p.lastSeen > 10000) p.close(); else p.send({ type: 'ping' }); }
    if (this.active) {
      const session = this.activeSession, id = this.active, edge = this.enteredEdge, capture = this.capture;
      try { await this.getPeer(id).request('keepControl', {}, 2500); if (this.activeSession === session && this.capture) await this.native.call('heartbeat'); }
      catch (e) { if (this.activeSession === session) { await this.returnLocal(); if (!this.active && !this.switching && /^FALLBACK_CONFIRMATION:/.test(e.message)) this.showFallback(id, edge, capture); } }
    }
    if (this.owner && this.owner.until < Date.now()) { this.owner = null; await this.native.call('release'); }
    for (const [id, offer] of this.offers) if (offer.until < Date.now()) this.offers.delete(id);
  }
  async settings(a) {
    if (a.name !== undefined) { if (typeof a.name !== 'string' || !a.name.trim() || a.name.length > 80) throw new Error('设备名称应为 1–80 字符'); this.config.name = a.name.trim(); }
    if (a.dwell !== undefined) { if (!Number.isInteger(a.dwell) || a.dwell < 100 || a.dwell > 3000) throw new Error('边缘等待时间范围为 100–3000 毫秒'); this.config.dwell = a.dwell; }
    if (a.edges) for (const edge of ['left', 'right', 'top', 'bottom']) { if (typeof a.edges[edge] === 'string' && a.edges[edge].length <= 64) this.config.edges[edge] = a.edges[edge]; }
    if (a.permissions) for (const name of ['input', 'screen', 'clipboard', 'shutdown']) if (typeof a.permissions[name] === 'boolean') this.config.permissions[name] = a.permissions[name];
    if (typeof a.master === 'boolean') this.config.master = a.master;
    if (!this.config.permissions.input && this.owner) { this.owner = null; await this.native.call('release'); }
    if (!this.config.permissions.screen) { this.videoHost.closeAll(); this.screenViewers.clear(); }
    if (!this.config.permissions.shutdown) this.cancelShutdown();
    if (!this.config.master && this.capture) await this.returnLocal();
    await saveConfig(this.dir, this.config); return {};
  }
  state() { return { id: this.config.id, name: this.config.name, groupId: this.group.data.id, groupEstablished: this.group.established, groupMemberCount: Object.keys(this.group.data.members).length, account: this.group.data.id, controlEnded: this.controlEnded, fallbackNotice: this.fallbackNotice, viewers: [...new Set([...this.videoHost.sessions.values()].map(s => s.peer.remote.id).concat(this.liveScreens(this.screenViewers)))], port: this.port, dwell: this.config.dwell, edges: this.config.edges, consentRequests: this.consent.list(), deviceGrants: this.config.deviceGrants, permissionNotice: this.permissionNotice, permissions: this.config.permissions, master: !!this.config.master, capabilities: this.native.capabilities, knownDevices: this.config.knownDevices || {}, peers: [...this.peers].map(([id, p]) => ({ id, ...p.info, address: p.socket.remoteAddress })), active: this.active, pendingControl: this.controlIntent?.id, owner: this.owner?.id, shutdownAt: this.shutdownAt, transfer: this.transfer, clipboard: this.clipboard ? { source: this.clipboard.source, files: this.clipboard.files.length, textLength: this.clipboard.text.length } : null, logs: this.logs }; }
  async stopInbound() {
    const ids = new Set([...this.videoHost.sessions.values()].map(s => s.peer.remote.id).concat(this.liveScreens(this.screenViewers))); this.screenViewers.clear(); if (this.owner) ids.add(this.owner.id);
    for (const id of ids) { this.config.pausedPeers[id] = true; try { this.peers.get(id)?.send({ type: 'control-ended' }); } catch {} }
    this.videoHost.closeAll(); this.owner = null; await this.native.call('release'); this.cancelShutdown(); await saveConfig(this.dir, this.config); this.emit('change');
  }
  async groupChanged() {
    this.changingGroup = true;
    try {
    if (this.controlIntent) this.controlIntent.cancelled = true; for (const intent of this.outgoingIntents) intent.cancelled = true; this.screenTargets.clear(); this.screenViewers.clear(); this.fallbackNotice = null;
    this.secret = parseToken(this.config.token); this.stopDiscovery?.(); this.videoHost.closeAll(); await this.videoViewer.closeAll(); await this.returnLocal(); this.owner = null; await this.native.call('release'); this.offers.clear(); this.clipboard = null; this.permissionNotice = null; this.cancelShutdown(); this.consent.reset(this.config.deviceGrants);
    const old = [...this.sockets]; setTimeout(() => old.forEach(p => p.close()), 100);
    if (!this.options.noDiscovery) this.stopDiscovery = startDiscovery(this.identity(), this.secret, this.port, (b, host) => { if (!this.group.excluded(b.id) && this.config.id < b.id && !this.peers.has(b.id)) this.connect(host, b.port); }, e => this.log(e.message));
    this.emit('change');
    } finally { this.changingGroup = false; }
  }
  async leaveGroup() {
    this.pairing?.hide(); const { newToken } = await import('./identity.js'); this.config.token = newToken(); delete this.config.group; this.group.close(); this.group = new Group(this); this.config.knownDevices = {}; this.config.deviceGrants = {}; this.config.edges = {}; this.config.pausedPeers = {}; await saveConfig(this.dir, this.config); await this.groupChanged(); this.group.start(); this.log('本设备已移出组，需要重新配对');
  }
  async close() { this.closing = true; clearInterval(this.adbTimer); this.group.close(); clearInterval(this.heartbeat); clearInterval(this.edgeTimer); this.cancelShutdown(); this.videoHost.closeAll(); await this.videoViewer.closeAll(); await this.returnLocal(); this.stopDiscovery?.(); await this.pairing?.close(); for (const p of this.sockets) p.close(); if (this.server) await new Promise(r => this.server.close(r)); this.native.close(); await saveConfig(this.dir, this.config); }
}
