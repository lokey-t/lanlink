import { generateKeyPairSync, createPublicKey, publicEncrypt, privateDecrypt, sign, verify, randomBytes, constants } from 'node:crypto';
import dgram from 'node:dgram';
import { accountId, newToken, saveConfig, parseToken } from './identity.js';
import { seal, unseal } from './wire.js';
import { broadcasts } from './pairing.js';

export const RECOVERY_PORT = 45834;
const validId = id => typeof id === 'string' && /^[a-f0-9-]{36}$/.test(id);
const keyOptions = key => ({ key: Buffer.from(key, 'base64'), format: 'der', type: 'spki' });
const privateOptions = key => ({ key: Buffer.from(key, 'base64'), format: 'der', type: 'pkcs8' });
export function validMember(m) {
  try { return m && validId(m.id) && typeof m.key === 'string' && m.key.length < 800 && createPublicKey(keyOptions(m.key)).asymmetricKeyDetails.modulusLength === 2048; } catch { return false; }
}
export function boxTranscript(b) { return ['lanlink-group-v1', b.group, b.to, b.revision, b.issuer, b.wrapped, b.body].join('|'); }
export function dissolutionTranscript(b) { return ['lanlink-group-dissolved-v1', b.group, b.revision, b.issuer].join('|'); }

export class Group {
  constructor(service) {
    this.service = service; const c = service.config;
    if (!c.deviceKey) { const pair = generateKeyPairSync('rsa', { modulusLength: 2048 }); c.deviceKey = { public: pair.publicKey.export({ format: 'der', type: 'spki' }).toString('base64'), private: pair.privateKey.export({ format: 'der', type: 'pkcs8' }).toString('base64') }; }
    c.group ||= { id: accountId(c.token), revision: 0, members: {}, removed: [] };
    this.data = c.group; this.data.anchors ||= {}; this.data.members[c.id] = this.own; this.boxes = new Map(); this.data.history ||= [];
  }
  get own() { const c = this.service.config; return { id: c.id, name: c.name, platform: process.platform, key: c.deviceKey.public, ...(c.group?.members?.[c.id]?.certificate ? { certificate:c.group.members[c.id].certificate } : {}) }; }
  get established() { return this.data.revision > 0 || Object.keys(this.data.members).some(id => id !== this.own.id) || Object.keys(this.service.config.knownDevices || {}).length > 0; }
  dissolutionRecord() {
    const b = { kind: 'lanlink-group-dissolved-v1', group: this.data.id, revision: Math.max(Date.now(), this.data.revision + 1), issuer: this.own.id, chain: this.certificateChain().map(({ id, key, certificate }) => ({ id, key, ...(certificate ? { certificate } : {}) })) };
    b.signature = sign('sha256', Buffer.from(dissolutionTranscript(b)), privateOptions(this.service.config.deviceKey.private)).toString('base64');
    return b;
  }
  verifyDissolution(b) {
    if (!b || b.kind !== 'lanlink-group-dissolved-v1' || b.group !== this.data.id || !Number.isSafeInteger(b.revision) || b.revision <= 0 || b.revision > Date.now() + 86400000 || typeof b.signature !== 'string' || b.signature.length > 500 || JSON.stringify(b).length > 50000) throw Error('解散通知无效或已处理');
    const issuer = this.resolveIssuer(b);
    if (!issuer || !verify('sha256', Buffer.from(dissolutionTranscript(b)), keyOptions(issuer.key), Buffer.from(b.signature, 'base64'))) throw Error('解散通知签名无效');
    return b;
  }
  async rememberDissolution(b) {
    const records = this.service.config.dissolvedGroups ||= {};
    if (!records[b.group] && Object.keys(records).length >= 64) throw Error('保留的解散通知已达上限，请联系支持以保留离线成员更新记录');
    records[b.group] = b; await saveConfig(this.service.dir, this.service.config);
  }
  async dissolve() {
    return this.enqueue(async () => {
      const b = this.dissolutionRecord(); await this.rememberDissolution(b);
      this.service.pairing?.hide();
      await this.service.stopInbound(); await this.service.videoViewer.closeAll(); await this.service.returnLocal();
      await Promise.allSettled([...this.service.peers.values()].map(p => p.request('group-dissolve', { notice: b }, 3000).catch(() => { if (!p.closed) p.send({ type: 'group-removed' }); })));
      await this.service.leaveGroup();
      return b;
    });
  }
  async acceptDissolution(b) {
    return this.enqueue(async () => { if (this.service.group !== this) throw Error('组已发生变化'); this.verifyDissolution(b); await this.rememberDissolution(b); await this.service.leaveGroup(); });
  }
  export() { return { id: this.data.id, revision: this.data.revision, members: this.data.members, removed: this.data.removed, history: this.data.history, anchors: this.data.anchors }; }
  excluded(id) { return this.data.removed.includes(id); }
  learn(member, explicit = false) {
    if (this.service.config.dissolvedGroups?.[this.data.id] || !validMember(member) || member.id === this.own.id || this.excluded(member.id) && !explicit) return false;
    const prior = this.data.members[member.id];
    if (!prior && Object.keys(this.data.members).length >= 16) throw Error('每个组最多支持 16 台设备');
    if (prior && prior.key !== member.key && !explicit) return false;
    this.data.members[member.id] = { id: member.id, key: member.key, name: String(member.name || '设备').slice(0, 80), platform: String(member.platform || '').slice(0, 30), ...((member.certificate || prior?.certificate) && JSON.stringify(member.certificate || prior.certificate).length < 1000 ? {certificate:member.certificate || prior.certificate} : {}) };
    this.data.anchors[member.id] = this.data.members[member.id];
    if (explicit) this.data.removed = this.data.removed.filter(id => id !== member.id);

    return JSON.stringify(prior) !== JSON.stringify(this.data.members[member.id]);
  }
  enqueue(operation) { const next=(this.queue||Promise.resolve()).catch(()=>{}).then(operation); this.queue=next; return next; }
  admit(member) { return this.enqueue(()=>this.admitMember(member)); }
  accept(box) { return this.enqueue(()=>this.acceptUpdate(box)); }
  remove(id) { return this.enqueue(()=>this.removeMember(id)); }
  async admitMember(member) {
    if (this.service.config.dissolvedGroups?.[this.data.id]) throw Error('当前组已解散，请重新打开添加设备');
    if (!validMember(member)) throw Error('请使用支持设备密钥的新版本重新配对');
    this.checkHistory(); const signature=sign('sha256',Buffer.from(['lanlink-member-v1',this.data.id,member.id,member.key].join('|')),privateOptions(this.service.config.deviceKey.private)).toString('base64'); this.learn({...member,certificate:{issuer:this.own.id,signature}}, true); this.data.revision = Math.max(Date.now(), this.data.revision + 1); this.refreshBoxes();
    await saveConfig(this.service.dir, this.service.config); this.publish();
  }
  admitGroup(meta) {
    return this.enqueue(async () => {
      const incoming = Object.values(meta?.members || {});
      if (!incoming.length || incoming.length > 16 || incoming.some(m => !validMember(m))) throw Error('组公钥信息无效');
      const ids = new Set(Object.keys(this.data.members));
      for (const m of incoming) {
        if (this.data.members[m.id] && this.data.members[m.id].key !== m.key) throw Error('设备公钥冲突，请重新配对');
        ids.add(m.id);
      }
      if (ids.size > 16) throw Error('每个组最多支持 16 台设备');
      if (!Array.isArray(meta.removed) || meta.removed.some(id => !validId(id))) throw Error('组成员信息无效');
      const removed = [...new Set([...this.data.removed, ...meta.removed])].filter(id => !ids.has(id));
      if (removed.length > 64) throw Error('组成员变更过多，请创建新组');
      if (this.service.config.dissolvedGroups?.[this.data.id]) throw Error('当前组已解散');
      this.checkHistory();
      for (const m of incoming) if (m.id !== this.own.id) {
        const signature = sign('sha256', Buffer.from(['lanlink-member-v1', this.data.id, m.id, m.key].join('|')), privateOptions(this.service.config.deviceKey.private)).toString('base64');
        this.learn({ ...m, certificate: { issuer: this.own.id, signature } }, true);
      }
      this.data.removed = removed; for (const id of removed) this.forget(id);
      this.data.revision = Math.max(Date.now(), this.data.revision + 1); this.refreshBoxes();
      await saveConfig(this.service.dir, this.service.config); this.publish();
    });
  }
  migration(meta, token) {
    const revision = Math.max(Date.now(), this.data.revision + 1), result = structuredClone(this.service.config.mergedGroups || {});
    result[this.data.id] = Object.values(this.data.members).filter(m => !this.excluded(m.id)).map(m => this.makeBox(m, { token, meta }, revision));
    this.validateMigrations(result); return result;
  }
  validateMigrations(records) {
    if (!records || Array.isArray(records) || typeof records !== 'object' || Object.keys(records).length > 64 || JSON.stringify(records).length > 4000000) throw Error('离线成员合并记录已满');
    for (const [id, boxes] of Object.entries(records)) if (!/^[a-f0-9]{24}$/.test(id) || !Array.isArray(boxes) || boxes.length > 16 || boxes.some(b => b?.group !== id || b.kind !== 'lanlink-group-update-v1' || !validId(b.to) || !validId(b.issuer) || !Number.isSafeInteger(b.revision) || typeof b.signature !== 'string' || b.signature.length > 500 || typeof b.wrapped !== 'string' || b.wrapped.length > 500 || typeof b.body !== 'string' || b.body.length > 40000 || JSON.stringify(b).length > 60000)) throw Error('组合并记录无效');
  }
  rememberMigrations(records) {
    if (!records) return false;
    this.validateMigrations(records);
    const merged = { ...this.service.config.mergedGroups };
    let changed = false;
    for (const [id, boxes] of Object.entries(records)) if (!merged[id] || Math.max(...boxes.map(b => b.revision)) > Math.max(...merged[id].map(b => b.revision))) { merged[id] = boxes; changed = true; }
    this.validateMigrations(merged);
    if (changed) this.service.config.mergedGroups = merged;
    return changed;
  }
  adopt(meta, token) {
    if (this.service.config.dissolvedGroups?.[meta?.id]) throw Error('该组已经解散，请生成新的组邀请');
    if (!meta || !/^[a-f0-9]{24}$/.test(meta.id) || !Number.isSafeInteger(meta.revision) || !meta.members || Object.keys(meta.members).length > 16) throw Error('组成员信息无效');
    const members = {}; for (const m of Object.values(meta.members)) { if (!validMember(m)) throw Error('设备公钥无效'); members[m.id] = m; }
    this.service.config.token = token; this.data = this.service.config.group = { id: meta.id, revision: meta.revision, members, removed: (meta.removed || []).filter(validId).slice(0, 64), history: Array.isArray(meta.history) ? meta.history.slice(-1024) : [], anchors: meta.anchors && Object.keys(meta.anchors).length <= 128 ? meta.anchors : {} }; this.data.members[this.own.id] = this.own; this.data.removed = this.data.removed.filter(id => id !== this.own.id); this.refreshBoxes();
  }
  makeBox(member, replacement, revision = this.data.revision) {
    const key = randomBytes(32), b = { kind: 'lanlink-group-update-v1', group: this.data.id, to: member.id, revision, issuer: this.own.id, chain: this.certificateChain() };
    b.wrapped = publicEncrypt({ ...keyOptions(member.key), oaepHash: 'sha256', padding: constants.RSA_PKCS1_OAEP_PADDING }, key).toString('base64');
    const target = replacement?.meta || this.data;
    b.body = seal(key, 0, { token: replacement?.token || this.service.config.token, removed: target.removed || [], members: target.members, ...(replacement ? { group: { id: target.id, revision: target.revision, anchors: target.anchors || {} } } : {}) });
    if (b.body.length > 40000) throw Error('组更新成员信息过多');
    b.signature = sign('sha256', Buffer.from(boxTranscript(b)), privateOptions(this.service.config.deviceKey.private)).toString('base64'); return b;
  }
  certificateChain() {
    const chain=[], seen=new Set(); let member=this.own;
    while(member && !seen.has(member.id) && chain.length<32) { seen.add(member.id); chain.push(member); member=member.certificate ? this.data.members[member.certificate.issuer] || this.data.anchors[member.certificate.issuer] : null; }
    return chain;
  }
  resolveIssuer(box) {
    const records=Array.isArray(box.chain) && box.chain.length<=32 ? box.chain : [];
    const visiting=new Set();
    const resolve=id=>{
      if(this.excluded(id) || visiting.has(id) || visiting.size>=32) return null;
      visiting.add(id);
      const trusted=this.data.members[id] || this.data.anchors[id]; if(trusted && validMember(trusted)) return trusted;
      const member=records.find(m=>m?.id===id); if(!validMember(member) || !member.certificate || typeof member.certificate.signature!=='string' || member.certificate.signature.length>500) return null;
      const parent=resolve(member.certificate.issuer); if(!parent) return null;
      return verify('sha256',Buffer.from(['lanlink-member-v1',this.data.id,member.id,member.key].join('|')),keyOptions(parent.key),Buffer.from(member.certificate.signature,'base64')) ? member : null;
    };
    return resolve(box.issuer);
  }
  checkHistory() { if (JSON.stringify(this.data.history).length > 1500000 || new Set(this.data.history.map(b => b.revision)).size >= 64) throw Error('离线成员更新记录已满，请先让所有设备上线后创建新组'); }
  refreshBoxes() { this.boxes.clear(); if (this.data.revision > 0) for (const member of Object.values(this.data.members)) if (member.id !== this.own.id && !this.excluded(member.id)) this.boxes.set(member.id, this.makeBox(member)); for (const b of this.boxes.values()) if (!this.data.history.some(x => x.revision === b.revision && x.to === b.to)) this.data.history.push(b); }
  decodeBox(b) {
    if (!b || b.kind !== 'lanlink-group-update-v1' || b.group !== this.data.id || b.to !== this.own.id || !Number.isSafeInteger(b.revision) || b.revision <= this.data.revision || b.revision > Date.now() + 86400000 || typeof b.body !== 'string' || b.body.length > 40000 || typeof b.wrapped !== 'string' || b.wrapped.length > 500 || typeof b.signature !== 'string' || b.signature.length > 500) throw Error('组更新无效或已处理');
    const issuer = this.resolveIssuer(b); if (!issuer || this.excluded(b.issuer) || !verify('sha256', Buffer.from(boxTranscript(b)), keyOptions(issuer.key), Buffer.from(b.signature, 'base64'))) throw Error('组更新签名无效');
    const key = privateDecrypt({ ...privateOptions(this.service.config.deviceKey.private), oaepHash: 'sha256', padding: constants.RSA_PKCS1_OAEP_PADDING }, Buffer.from(b.wrapped, 'base64'));
    const payload = unseal(key, 0, b.body); parseToken(payload.token);
    if (!Array.isArray(payload.removed) || payload.removed.length > 64 || payload.removed.some(id => !validId(id)) || payload.removed.includes(this.own.id)) throw Error('组更新成员无效');
    if (!payload.members || Object.keys(payload.members).length > 16 || Object.values(payload.members).some(m => !validMember(m)) || payload.members[this.own.id]?.key !== this.own.key) throw Error('组公钥信息无效');
    return payload;
  }
  async acceptUpdate(b) {
    if (this.service.config.dissolvedGroups?.[this.data.id]) throw Error('当前组已经解散');
    const payload = this.decodeBox(b), s = this.service;
    if (payload.group) {
      const meta = { ...payload.group, members: payload.members, removed: payload.removed, history: [] };
      const check = new Group({ ...s, config: structuredClone(s.config) }); check.adopt(meta, payload.token);
      this.adopt(meta, payload.token);
      for (const id of payload.removed) this.forget(id);
      await saveConfig(s.dir, s.config); await s.groupChanged(); this.publish(); return;
    }
    for(const m of Object.values(this.data.members)) this.data.anchors[m.id]=m; this.data.members = payload.members; this.data.revision = b.revision; this.data.removed = payload.removed; s.config.token = payload.token;
    for (const id of payload.removed) this.forget(id);
    this.refreshBoxes(); await saveConfig(s.dir, s.config); await s.groupChanged(); this.publish();
  }
  forget(id) { if(this.data.members[id]) this.data.anchors[id]=this.data.members[id]; delete this.data.members[id]; delete this.service.config.knownDevices?.[id]; delete this.service.config.deviceGrants[id]; for (const edge of Object.keys(this.service.config.edges)) if (this.service.config.edges[edge] === id) this.service.config.edges[edge] = ''; }
  async removeMember(id) {
    const s = this.service; if (!validId(id) || id === this.own.id || !s.config.knownDevices?.[id] && !this.data.members[id]) throw Error('请选择组内的其他设备');
    for (const known of Object.keys(s.config.knownDevices || {})) if (known !== id && !this.data.members[known]) throw Error('请先让其余组员升级并上线一次，保存设备公钥后才能保留离线成员身份');
    if (this.data.removed.length >= 64) throw Error('组成员变更过多，请创建新组');
    this.checkHistory(); const peers = [...s.peers.values()], target = s.peers.get(id);
    this.data.revision = Math.max(Date.now(), this.data.revision + 1); this.data.removed = [...new Set([...this.data.removed, id])]; this.forget(id); s.config.token = newToken(); this.refreshBoxes();
    await saveConfig(s.dir, s.config);
    s.videoHost.closePeer(id); if (s.owner?.id === id) { s.owner = null; await s.native.call('release'); }
    try { target?.send({ type: 'group-removed' }); } catch {}
    // Only per-recipient RSA envelopes cross old connections; the removed member knows the old group secret.
    await Promise.allSettled(peers.filter(p => p.remote.id !== id).map(p => { const box = this.boxes.get(p.remote.id); return box ? p.request('group-update', { box }, 3000) : Promise.resolve(); }));
    await s.groupChanged(); this.publish();
  }
  publish() { for (const p of this.service.peers.values()) if (!this.excluded(p.remote.id)) try { p.send({ type: 'group-members', group: this.data.id, members: this.data.members, history: this.data.history, migrations: this.service.config.mergedGroups }); } catch {} }
  async merge(message) { if (message.group !== this.data.id || !message.members || Object.keys(message.members).length > 16) return; let changed = this.rememberMigrations(message.migrations); if (Array.isArray(message.history)) for (const b of message.history.slice(0, 1024)) { const issuer = this.data.members[b.issuer]; if (this.data.history.length < 1024 && issuer && validId(b.to) && Number.isSafeInteger(b.revision) && typeof b.signature === 'string' && b.signature.length < 500 && b.group === this.data.id && b.revision <= this.data.revision && typeof b.body === 'string' && b.body.length < 40000 && !this.data.history.some(x => x.to === b.to && x.revision === b.revision)) { try { if (verify('sha256', Buffer.from(boxTranscript(b)), keyOptions(issuer.key), Buffer.from(b.signature, 'base64'))) { this.data.history.push(b); changed = true; } } catch {} } } for (const m of Object.values(message.members)) changed = this.learn(m) || changed; if (changed) { await saveConfig(this.service.dir, this.service.config); this.publish(); } }
  recoveryQuery() { return { kind: 'lanlink-group-recovery-v1', group: this.data.id, id: this.own.id, revision: this.data.revision }; }
  recoveryResponse(query) {
    if (query?.kind === 'lanlink-group-recovery-v1' && validId(query.id) && Number.isSafeInteger(query.revision) && this.service.config.dissolvedGroups?.[query.group]) return this.service.config.dissolvedGroups[query.group];
    if (query?.kind === 'lanlink-group-recovery-v1' && validId(query.id) && Number.isSafeInteger(query.revision) && this.service.config.mergedGroups?.[query.group]) return this.service.config.mergedGroups[query.group].filter(b => b.to === query.id && b.revision > query.revision).sort((a,b) => a.revision-b.revision)[0] || null;
    if (!query || query.kind !== 'lanlink-group-recovery-v1' || query.group !== this.data.id || !validId(query.id) || this.excluded(query.id) || !Number.isSafeInteger(query.revision) || query.revision < 0) return null;
    return this.data.history.filter(b => b.to === query.id && b.revision > query.revision).sort((a,b) => a.revision-b.revision)[0] || null;
  }
  announceRecovery() {
    if (this.closed || !this.socket) return;
    const data=Buffer.from(JSON.stringify(this.recoveryQuery()));
    for(const address of broadcasts()) this.socket.send(data,RECOVERY_PORT,address,()=>{});
  }
  start() {
    if (this.service.options.noDiscovery) return; this.closed=false; this.refreshBoxes(); this.replies=new Map();
    this.socket=dgram.createSocket({type:'udp4',reuseAddr:true}); this.socket.on('error',e=>this.service.log('组更新发现：'+e.message));
    this.socket.on('message',(data,remote)=>{
      if(this.closed || data.length>60000) return; let box;
      try {
        box=JSON.parse(data);
        if(box.kind==='lanlink-group-recovery-v1') {
          if(remote.port!==RECOVERY_PORT) return;
          const reply=this.recoveryResponse(box); if(!reply) return;
          const key=box.id, previous=this.replies.get(key); if(previous && Date.now()-previous.time<1500 && previous.revision===box.revision) return;
          this.replies.set(key,{time:Date.now(),revision:box.revision});
          const body=Buffer.from(JSON.stringify(reply)); if(body.length<=60000) this.socket.send(body,remote.port,remote.address,()=>{});
          return;
        }
        if(this.applying) return;
        if (box.kind === 'lanlink-group-dissolved-v1') this.verifyDissolution(box); else this.decodeBox(box);
      } catch { return; }
      this.applying=true; (box.kind === 'lanlink-group-dissolved-v1' ? this.acceptDissolution(box) : this.accept(box)).then(()=>this.announceRecovery()).catch(e=>this.service.log(e.message)).finally(()=>{this.applying=false;});
    });
    this.socket.bind(RECOVERY_PORT,()=>{if(this.closed) return;this.socket.setBroadcast(true);this.announceRecovery();this.timer=setInterval(()=>this.announceRecovery(),2000);});
  }
  close() { this.closed = true; clearInterval(this.timer); try { this.socket?.close(); } catch {} }
}
