import { createHash } from 'node:crypto';

const TTL=600000, LIMIT=30;
const newer=(a,b)=>!b || a.at>b.at || a.at===b.at && a.id>b.id;
export class ClipboardSync {
  constructor(service,now=()=>Date.now()){this.service=service;this.now=now;this.records=new Map();this.latest=null;this.applied=null;this.lastLocal=null;this.writes=Promise.resolve();}
  get upload(){return this.service.config.clipboardUpload!==false;}
  get download(){return this.service.config.clipboardDownload!==false;}
  allowed(id){return this.service.config.permissions.clipboard && this.service.canShareClipboard(id) && !this.service.group.excluded(id);}
  prune(){const now=this.now();for(const [id,r] of this.records)if(r.at<=now-TTL || !this.allowed(r.source))this.records.delete(id);const sorted=[...this.records.values()].sort((a,b)=>b.at-a.at || b.id.localeCompare(a.id));for(const r of sorted.slice(LIMIT))this.records.delete(r.id);}
  list(){this.prune();return [...this.records.values()].sort((a,b)=>b.at-a.at || b.id.localeCompare(a.id));}
  state(){return {upload:this.upload,download:this.download,shortcuts:!!this.service.config.clipboardShortcuts,showShortcuts:!!this.service.config.clipboardShortcuts && (!this.upload || !this.download),history:this.list().map(r=>({...r,text:r.text.slice(0,2000),length:r.text.length}))};}
  fingerprint(clip){return createHash('sha256').update(JSON.stringify([clip.text||'',clip.files||[]])).digest('hex');}
  suppress(clip){const key=this.fingerprint(clip);return key===this.applied || key===this.lastLocal;}
  markApplied(clip){this.applied=this.fingerprint(clip);this.lastLocal=null;}
  validate(record){
    if(!record || typeof record.id!=='string' || record.id.length>64 || typeof record.source!=='string' || record.source.length>64 || typeof record.text!=='string' || record.text.length>1000000 || !Number.isSafeInteger(record.at) || record.at<=this.now()-TTL || record.at>this.now()+30000 || this.service.group.excluded(record.source))return null;
    const known=record.source===this.service.config.id || this.service.group.data.members[record.source] || this.service.config.knownDevices?.[record.source] || this.service.peers.has(record.source);
    if(!known || !this.allowed(record.source))return null;
    return {id:record.id,source:record.source,name:String(record.name||'设备').slice(0,80),at:record.at,text:record.text};
  }
  add(record){const r=this.validate(record);if(!r || !r.text || record.files?.length || this.records.has(r.id))return false;this.records.set(r.id,r);this.prune();return this.records.has(r.id);}
  async publish(offer,raw,manual=false){
    if(!offer.text && !offer.files.length){if(manual)throw Error('本机剪贴板暂无可上传内容');return false;}
    if(manual && offer.id===this.latest?.id && this.lastLocal===this.fingerprint(raw) && this.latest?.source===this.service.config.id && this.latest.at>this.now()-TTL){
      this.service.clipboard=this.latest;for(const p of this.service.peers.values())if(this.allowed(p.remote.id))p.send({type:'clipboard',offer:this.latest});return true;
    }
    if(!manual && (!this.upload || this.suppress(raw))){if(this.latest)this.service.clipboard=this.latest;return false;}
    this.lastLocal=this.fingerprint(raw);this.applied=null;
    const at=Math.max(this.now(),(this.latest?.at||0)+1);Object.assign(offer,{at,name:this.service.config.name,source:this.service.config.id});
    this.latest=offer;this.add(offer);
    for(const p of this.service.peers.values())if(this.allowed(p.remote.id))p.send({type:'clipboard',offer});
    this.service.emit('change');return true;
  }
  async receive(peer,offer){
    if(!this.allowed(peer.remote.id))return;
    const data={...this.service.validateOffer(offer),source:peer.remote.id,name:peer.info?.name||offer.name||'设备',at:Number.isSafeInteger(offer.at)?offer.at:this.now()};
    if(data.at>this.now()+30000 || data.at<this.now()-TTL)return;
    this.add(data);await this.adopt(data);this.service.emit('change');
  }
  async adopt(data){
    if(!newer(data,this.latest))return;
    this.latest=data;this.service.clipboard=data;
    if(this.download)await this.apply(data,true).catch(e=>this.service.log('自动同步剪贴板：'+e.message));
  }
  apply(offer,automatic=false){this.writes=this.writes.catch(()=>{}).then(async()=>{
    if(automatic && (!this.download || this.latest?.id!==offer.id))return;
    if(!this.allowed(offer.source))throw Error('来源设备未允许剪贴板共享');
    await this.service.materialize(offer,()=>!automatic || this.download && this.latest?.id===offer.id);
  });return this.writes;}
  sendInventory(peer){if(!this.allowed(peer.remote.id))return;this.prune();peer.send({type:'clipboard-inventory',ids:this.list().map(r=>r.id)});}
  async inventory(peer,ids){
    if(!this.allowed(peer.remote.id) || !Array.isArray(ids) || ids.length>LIMIT || ids.some(id=>typeof id!=='string'||id.length>64))return;
    const have=new Set(ids);
    // Bound each batch so long text history cannot flood the control socket.
    let sent=0;for(const record of this.list())if(!have.has(record.id) && this.allowed(record.source)){peer.send({type:'clipboard-record',record});if(++sent===4)break;}
  }
  async record(peer,record){if(!this.allowed(peer.remote.id) || record?.files?.length)return;const r=this.validate(record);if(!r)return;if(this.add(r)){await this.adopt({...r,files:[]});this.service.emit('change');}}
  async uploadNow(){this.service.allow('clipboard');await this.service.snapshotClipboard(true,true);const peers=[...this.service.peers.values()].filter(p=>this.allowed(p.remote.id)).length;return {message:peers?'剪贴板已上传到组':'剪贴板已记录，其他设备上线后补同步',peers};}
  async downloadNow(){
    this.service.allow('clipboard');
    await Promise.all([...this.service.peers.values()].filter(p=>this.allowed(p.remote.id)).map(async p=>{
      try{const result=await p.request('clipboard-sync',{ids:this.list().map(r=>r.id)});const data=result.latest;
        if(!data || !this.allowed(data.source))return;
        if(data.source===p.remote.id)await this.receive(p,data);
        else if(!data.files?.length){const record=this.validate(data);if(record){this.add(record);await this.adopt({...record,files:[]});}}
      }catch(e){this.service.log('增量同步剪贴板：'+e.message);}
    }));
    if(!this.latest)throw Error('组内暂无可下载的剪贴板内容');
    await this.apply(this.latest);return {message:'组剪贴板已同步到本机'};
  }
  async copy(id){const entry=this.list().find(r=>r.id===id);if(!entry)throw Error('该剪贴板记录已过期');this.markApplied({text:entry.text});await this.service.native.call('clipboardSet',{text:entry.text});return {message:'已复制到本机剪贴板'};}
  clear(){this.records.clear();this.latest=null;this.lastLocal=null;this.applied=null;}
}
