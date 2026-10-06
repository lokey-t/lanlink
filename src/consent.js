import { randomUUID } from 'node:crypto';

export const permissionNames = ['input', 'screen', 'clipboard', 'shutdown'];
// Local UI decisions only. Requests never create persistent grants by themselves.
export class Consent {
  constructor(grants = {}) { this.grants = grants; this.pending = new Map(); this.denied = new Map(); }
  list(now = Date.now()) {
    for (const [id, r] of this.pending) if (now >= r.expires) { this.pending.delete(id); this.denied.set(id, now + 30000); }
    for (const [id, until] of this.denied) if (now >= until) this.denied.delete(id);
    return [...this.pending.values()];
  }
  allowed(id, permission) { return this.grants[id]?.[permission] === true; }
  request(id, name, now=Date.now()) {
    this.list(now);
    if(this.pending.has(id))return;
    if(this.denied.has(id))throw Error('CONSENT_DENIED: 对方暂未同意，请稍后再试');
    if(this.pending.size>=8)throw Error('待确认请求过多');
    this.pending.set(id,{id,requestId:randomUUID(),name:String(name||'组内设备').replace(/[\x00-\x1f]/g,'').slice(0,80),expires:now+120000});
  }
  require(id, name, permission, now = Date.now()) {
    this.list(now);
    if (Object.hasOwn(this.grants, id)) {
      if (!this.allowed(id, permission)) throw new Error('CONSENT_DENIED: 此设备未被允许使用该功能，请在被控设备修改设备授权');
      return;
    }
    if (this.denied.has(id)) throw new Error('CONSENT_DENIED: 被控设备暂未同意，请稍后重新发起');
    if (!this.pending.has(id)) {
      if (this.pending.size >= 8) throw new Error('待确认请求过多，请稍后重试');
      this.pending.set(id, { id, requestId: randomUUID(), name: String(name || '已配对设备').replace(/[\x00-\x1f]/g, '').slice(0, 80), expires: now + 120000 });
    }
    throw new Error('CONSENT_PENDING: 等待被控设备同意首次控制');
  }
  decide(id, requestId, accepted, permissions, now = Date.now()) {
    this.list(now);
    const request = this.pending.get(id);
    if (!request || request.requestId !== requestId) throw new Error('请求已结束，请让对方重新发起');
    this.pending.delete(id);
    if (!accepted) { this.denied.set(id, now + 120000); return; }
    this.grants[id] = Object.fromEntries(permissionNames.map(k => [k, permissions?.[k] === true]));
  }
  disconnect(id) { this.pending.delete(id); }
  reset(grants = {}) { this.grants = grants; this.pending.clear(); this.denied.clear(); }
}
