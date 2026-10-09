import { randomUUID } from 'node:crypto';

export function accessState(report, platform) {
  const keys = platform === 'android' ? ['input','screen','clipboard'] : ['input','screen','clipboard','shutdown'];
  const permissions = Object.fromEntries(keys.map(k => [k, report?.permissions?.[k] || {}]));
  const values = Object.values(permissions).filter(p => p.supported !== false);
  const ready = values.length > 0 && values.every(p => p.appEnabled === true && p.systemGranted === true);
  const allowed = values.filter(p => p.peerAllowed === true).length;
  return { status: !ready ? '未就绪' : !allowed ? '未授权' : allowed < values.length ? '有限授权' : '在线', ready, permissions };
}

export class Access {
  constructor(service) { this.service=service;this.notice=null;this.generation=0; }
  async read(peer) {
    const report=await peer.request('permission-status',{},4000);
    peer.access=accessState(report,peer.info?.platform);peer.accessAt=Date.now();return report;
  }
  async poll() {
    this.expire();
    if(this.polling)return;this.polling=true;
    try { await Promise.all([...this.service.peers.values()].map(async p=>{if(Date.now()-(p.accessAt||0)<3000)return;try{await this.read(p);}catch{p.access=accessState(null,p.info?.platform);p.accessAt=Date.now();}})); }
    finally {this.polling=false;}
  }
  async prepare(id, keys) {
    const peer=this.service.getPeer(id);
    keys=[...new Set((Array.isArray(keys)?keys:[]).filter(k=>['input','screen','clipboard','shutdown'].includes(k)))];
    if(!keys.length)throw Error('请选择需要的权限');
    if(keys.includes('input') || keys.includes('screen')) {
      try {await this.service.adbGuide.check(peer,{id,feature:true,screen:keys.includes('screen')});}
      catch(error){if(error.message.startsWith('ADB_SETUP_REQUIRED:'))return {ready:false,guide:true};throw error;}
    }
    const report=await this.read(peer);
    if(!report.permissions)throw Error('请更新被控设备的 LanLink 后重试');
    if(keys.some(k=>report.permissions[k]?.supported===false))throw Error('被控设备不支持此功能');
    if(keys.some(k=>report.permissions[k]?.appEnabled!==true || report.permissions[k]?.systemGranted!==true)) {
      await peer.request('permission-prepare',{permissions:keys},4000);
      throw Error('PERMISSION_REQUIRED: 设备未就绪，请在被控设备完成权限设置后重试');
    }
    return {ready:true,allowed:keys.every(k=>report.permissions[k]?.peerAllowed===true)};
  }
  async request(id, keys, intent=null) {
    this.expire();
    keys=(Array.isArray(keys)?keys:[]).filter(k=>['input','screen','clipboard','shutdown'].includes(k));
    if(!keys.length)throw Error('请选择需要的权限');
    const p=this.service.getPeer(id),generation=this.generation;
    if(this.notice){if(this.notice.id===id)return this.notice;throw Error('请先完成当前设备的授权');}
    await p.request('permission-request',{permissions:keys},4000);
    if(generation!==this.generation || this.service.getPeer(id)!==p){if(!this.notice)p.request('permission-cancel',{},3000).catch(()=>{});throw Error('CONTROL_ENDED: 授权请求已取消');}
    this.notice={id,name:p.info?.name,keys,intent,requestId:randomUUID(),expires:Date.now()+120000};this.service.emit('change');return this.notice;
  }
  async check(peer,intent) {
    const generation=this.generation,report=await this.read(peer);
    if(generation!==this.generation)throw Error('CONTROL_ENDED: 请求已取消');
    // Older peers still enforce their own prepare/acquire permissions.
    if(!report.permissions)return;
    const keys=intent.screen?['input','screen']:['input'];
    if(keys.some(k=>report.permissions[k]?.appEnabled===false || report.permissions[k]?.systemGranted===false)) {
      await peer.request('permission-prepare',{permissions:keys},4000);
      throw Error('PERMISSION_REQUIRED: 设备未就绪，请在被控设备完成权限设置');
    }
    if(keys.every(k=>report.permissions[k]?.peerAllowed===true))return;
    if(this.notice?.id===intent.id)throw Error('CONSENT_PENDING: 请在被控设备允许本设备控制');
    if(Object.entries(report.permissions).some(([key,p])=>key!=='clipboard' && p.peerAllowed===true))throw Error('CONSENT_DENIED: 此功能尚未获授权，请点击发起授权');
    await this.request(intent.id,keys,intent);
    throw Error('CONSENT_PENDING: 请在被控设备允许本设备控制');
  }
  async status(requestId) {
    const n=this.notice;if(!n || n.requestId!==requestId)return {ended:true};
    if(this.expire())return {ready:false,ended:true,message:'授权请求已过期，请重新发起'};
    const p=this.service.peers.get(n.id);if(!p || p.closed)return {ready:false,message:'设备已离线，等待重新连接'};
    let report;try{report=await this.read(p);}catch(error){if(this.expire())return {ready:false,ended:true,message:'授权请求已过期，请重新发起'};return {ready:false,message:p.closed?'设备已离线，等待重新连接':'暂时无法读取授权状态，正在重试'};}if(this.notice!==n)return {ended:true};
    if(this.expire())return {ready:false,ended:true,message:'授权请求已过期，请重新发起'};
    const ready=n.keys.every(k=>report.permissions?.[k]?.peerAllowed===true);
    const decided=report.requestPending===false && Object.entries(report.permissions||{}).some(([key,p])=>key!=='clipboard' && typeof p.peerAllowed==='boolean');
    const ended=!ready && (report.requestRejected===true || decided);
    if(ended)this.cancel(requestId);
    return {ready,ended,message:ready?'授权已完成':ended?'对方未允许所需权限，请重新发起':'请在被控设备允许本设备控制'};
  }
  expire(){if(this.notice && Date.now()>=this.notice.expires){this.cancel(this.notice.requestId);return true;}return false;}
  async complete(requestId) {
    const n=this.notice,s=await this.status(requestId);
    if(!n || this.notice!==n || !s.ready)throw Error('授权尚未完成');
    this.notice=null;this.service.emit('change');return n.intent || {};
  }
  cancel(requestId) {if(!requestId || this.notice?.requestId===requestId){const id=this.notice?.id;this.generation++;this.notice=null;if(id)this.service.peers.get(id)?.request('permission-cancel',{},3000).catch(()=>{});if(id && this.service.controlIntent?.id===id)this.service.controlIntent.cancelled=true;if(id && this.service.active===id)this.service.returnLocal().catch(()=>{});this.service.edge.waitForLeave();this.service.emit('change');}return {};}
}
