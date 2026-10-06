import { randomUUID } from 'node:crypto';

// Keep input local until setup has passed. Status polling is read-only; an
// explicit connection/setup attempt may ask the phone for missing ADB consent.
export class AdbGuide {
  constructor(service) { this.service = service; this.notice = null; this.generation = 0; }
  async open(id) {
    const peer=this.service.getPeer(id);if(peer.info?.platform!=='android')throw Error('请选择安卓设备');
    if(this.notice && this.notice.id!==id)throw Error('请先关闭当前连接引导');
    this.notice ||= {id,name:peer.info.name,requestId:randomUUID(),setupOnly:true};await this.status(this.notice.requestId);this.service.emit('change');return {};
  }
  async check(peer, intent) {
    if (peer.info?.platform !== 'android') return;
    if (this.notice) throw Error('ADB_SETUP_REQUIRED: 请先完成电脑上的手机连接引导');
    const generation = this.generation;
    const report = await peer.request('permission-status', {}, 4000);
    if (generation !== this.generation) throw Error('CONTROL_ENDED: 控制请求已取消');
    if (this.service.getPeer(intent.id) !== peer) throw Error('CONTROL_ENDED: 手机连接已改变');
    const engine = report?.engine;
    if (!engine || engine.ready && (engine.root || !engine.revoked && engine.authorized!==false)) return;
    if(engine.root){
      if(engine.ready)return;
      await peer.request('permission-prepare',{permissions:['input',...(intent.screen?['screen']:[])]},4000);
      throw Error('PERMISSION_REQUIRED: 手机 Root 助手尚未就绪，请在手机检查 Root 授权');
    }
    if (engine.recovering && !engine.revoked && engine.authorized!==false) throw Error('PERMISSION_REQUIRED: 手机输入助手正在恢复，请稍候');
    this.notice = { ...intent, requestId: randomUUID(), name: peer.info.name, message: engine.message || '', revoked:engine.revoked===true, unauthorized:engine.authorized===false };
    await this.promptRevoked(peer,this.notice);
    this.service.emit('change');
    throw Error('ADB_SETUP_REQUIRED: 需要先为手机启用 ADB，请按连接引导操作');
  }
  async promptRevoked(peer,notice) {
    if(!(notice.revoked || notice.unauthorized) || notice.promptSent)return;
    notice.promptSent=true;
    try {await peer.request('adb-permission-request',{},4000);}catch{notice.promptSent=false;}
  }
  async status(requestId) {
    const notice = this.notice;
    if (!notice || notice.requestId !== requestId) return { ended: true };
    const peer = this.service.peers.get(notice.id);
    if (!peer || peer.closed) return { ready: false, message: '手机暂时离线，请打开手机上的 LanLink，并保持与电脑在同一网络。' };
    const report = await peer.request('permission-status', {}, 4000);
    if (this.notice !== notice || this.service.peers.get(notice.id) !== peer) return { ended: true };
    notice.revoked=report?.engine?.revoked===true && !report?.engine?.root;
    notice.unauthorized=report?.engine?.authorized===false && !report?.engine?.root;
    await this.promptRevoked(peer,notice);
    return { ready: report?.engine?.ready === true && !notice.revoked && !notice.unauthorized, revoked:notice.revoked, unauthorized:notice.unauthorized, message: notice.revoked?'请在手机上允许 ADB 授权，允许后可继续连接':report?.engine?.root ? 'Root 助手正在准备，请在手机上检查 Root 授权。' : notice.unauthorized?'手机尚未完成 ADB 授权。请在手机允许授权，并连接数据线后点击连接。':report?.engine?.message || '等待手机授权…' };
  }
  async complete(requestId) {
    const notice = this.notice;
    const status = await this.status(requestId);
    if (status.ended || this.notice !== notice) throw Error('CONTROL_ENDED: 连接引导已结束');
    if (!status.ready) throw Error('ADB_SETUP_REQUIRED: 手机尚未准备就绪');
    this.notice = null; this.service.emit('change');
    return notice;
  }
  cancel(requestId) {
    if (!requestId || this.notice?.requestId === requestId) this.generation++;
    if (this.notice && (!requestId || this.notice.requestId === requestId)) {
      if(this.notice.promptSent)this.service.peers.get(this.notice.id)?.request('permission-cancel',{},3000).catch(()=>{});
      this.notice = null; this.service.edge.waitForLeave(); this.service.emit('change');
    }
    return {};
  }
}
