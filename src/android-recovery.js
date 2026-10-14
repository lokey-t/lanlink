import { randomUUID } from 'node:crypto';

const RETENTION = 24 * 60 * 60 * 1000;
const messages = {
  disconnected: '安卓设备连接已中断，暂时无法确认原因。可能是网络变化或应用被系统停止，请在手机打开 LanLink；重新连接后会核对系统退出记录。',
  restricted: '手机上的 LanLink 已在后台异常停止，且检测到系统限制后台运行，可能与本次中断有关。请在手机重新打开 LanLink，按机型引导设置省电白名单和自启动。',
  unknown: '手机上的 LanLink 上次运行已结束，系统未提供明确的退出原因。请重新打开手机上的 LanLink 后连接，并按手机上的提示检查后台运行设置。',
  reboot: '手机已重启，LanLink 连接因此中断。请重新打开手机上的 LanLink；如输入助手未就绪，请通过已信任的调试连接恢复。',
  'low-memory': '安卓系统记录显示 LanLink 因内存不足被停止，导致连接中断。请减少手机上同时运行的应用，然后重新连接。',
  crash: '安卓系统记录显示 LanLink 上次发生崩溃或无响应，导致连接中断。请重新连接；如果反复发生，请检查日志。',
  'user-stop': '安卓系统记录显示 LanLink 被用户或系统的停止操作结束，导致连接中断。请重新打开手机上的 LanLink 后连接。',
};

// A socket cannot report why its remote process died. Keep the old service
// session ID so a later authenticated identity can explain that exact loss,
// without guessing from a battery setting or comparing two devices' clocks.
export class AndroidRecovery {
  constructor(service) { this.service = service; this.pending = new Map(); this.notices = []; }
  prune(now = Date.now()) {
    for (const [id, item] of this.pending) if (item.until <= now || item.group !== this.service.group.data.id || this.service.group.excluded(id)) this.pending.delete(id);
    this.notices = this.notices.filter(item => item.until > now && item.group === this.service.group.data.id && !this.service.group.excluded(item.id));
  }
  get notice() { this.prune(); return this.notices[0] || null; }
  interested(id) {
    const s = this.service;
    return s.config.master || s.active === id || s.controlIntent?.id === id || s.screenTargets.has(id) || s.videoViewer.sessions.has(id) || s.videoViewer.opening.has(id);
  }
  disconnected(peer, now = Date.now()) {
    const s = this.service, id = peer.remote?.id;
    if (s.closing || s.changingGroup || peer.intentionalClose || !id || s.group.excluded(id) || s.config.dissolvedGroups?.[s.group.data.id] || peer.info?.platform !== 'android' || !this.interested(id)) return;
    this.prune(now);
    const item = { id, name: peer.info.name, group: s.group.data.id, sessionId: peer.info.capabilities?.backgroundSessionId, until: now + RETENTION };
    this.pending.set(id, item);
    // Bound memory even if peers repeatedly reconnect under different IDs.
    if (this.pending.size > 64) this.pending.delete(this.pending.keys().next().value);
    this.offer(item, 'disconnected');
  }
  identity(peer) {
    this.prune();
    const id = peer.remote.id, item = this.pending.get(id), recovery = peer.info?.capabilities?.backgroundRecovery;
    if (!item || peer.info.platform !== 'android' || this.service.peers.get(id) !== peer || peer.closed || typeof item.sessionId !== 'string' || !item.sessionId) return;
    if (peer.info.capabilities.backgroundSessionId === item.sessionId) {
      // The same service survived: this was a transport interruption, not its
      // later process exit. Do not retain an association with a future kill.
      this.pending.delete(id);
      this.notices = this.notices.filter(notice => notice.id !== id || notice.reason !== 'disconnected');
      this.service.emit('change');
      return;
    }
    if (!recovery || recovery.previousSessionId !== item.sessionId) return;
    if (typeof recovery.id !== 'string' || !recovery.id || recovery.id.length > 100 || !Object.hasOwn(messages, recovery.reason) || recovery.reason === 'disconnected') return;
    this.pending.delete(id);
    this.offer({ ...item, name: peer.info.name }, recovery.rebooted === true ? 'reboot' : recovery.reason);
  }
  offer(item, reason) {
    const notice = { ...item, requestId: randomUUID(), reason, confirmed: ['low-memory', 'crash', 'user-stop', 'reboot'].includes(reason), message: messages[reason] };
    delete notice.sessionId;
    const index = this.notices.findIndex(other => other.id === item.id);
    if (index < 0) this.notices.push(notice); else this.notices[index] = notice;
    if (this.notices.length > 32) this.notices.shift();
    this.service.emit('change');
  }
  dismiss(requestId) {
    this.notices = this.notices.filter(item => item.requestId !== requestId);
    this.service.emit('change');
    return {};
  }
}
