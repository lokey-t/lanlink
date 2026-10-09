import {randomUUID,createHash} from 'node:crypto';
import {mkdir,readFile,writeFile,rename,rm,lstat,readdir,realpath,open,copyFile} from 'node:fs/promises';
import path from 'node:path';
import {constants} from 'node:fs';
import {receiveBulk,receiveQuicFile} from './bulk.js';
import {withAbort} from './abort.js';
import {safeName} from './service.js';
const MAX=2*1024**3,COUNT=1000,UUID=/^[a-f0-9-]{36}$/i;
export function stagePath(value){
  if(typeof value!=='string'||value.length>1024||value.split('/').length>32)throw Error('文件路径无效');
  return value.split('/').map(safeName).join('/');
}
export function validateStage(o){
  if(!o||!UUID.test(o.id)||!['file','folder'].includes(o.type)||!Array.isArray(o.files)||!Array.isArray(o.directories)||o.files.length+o.directories.length>COUNT)throw Error('暂存文件信息无效');
  const name=safeName(o.name),seen=new Set(),ids=new Set();let size=0;
  const claim=p=>{p=stagePath(p);if(p!==name&&!p.startsWith(name+'/'))throw Error('文件超出分享目录');const k=p.toLowerCase();if(seen.has(k))throw Error('文件名称重复');seen.add(k);return p;};
  const directories=o.directories.map(claim),files=o.files.map(f=>{if(!UUID.test(f.id)||ids.has(f.id)||!Number.isSafeInteger(f.size)||f.size<0||f.size>MAX)throw Error('文件信息无效');ids.add(f.id);size+=f.size;return {id:f.id,name:safeName(f.name),relative:claim(f.relative),size:f.size};});
  if(size>MAX||o.type==='file'&&(directories.length||files.length!==1||files[0].relative!==name)||o.type==='folder'&&!directories.includes(name))throw Error('文件分享范围无效或大于 2 GB');
  for(const f of files){if(path.posix.basename(f.relative)!==f.name)throw Error('文件名称不一致');}
  for(const p of [...directories,...files.map(f=>f.relative)]){const parent=path.posix.dirname(p);if(parent!=='.'&&!directories.includes(parent))throw Error('文件父目录无效');}
  return {id:o.id,name,type:o.type,size,files,directories};
}
export class Staging {
  constructor(service){this.service=service;this.rows=new Map();this.exports=new Map();this.sent=new Map();this.tasks=new Map();this.controllers=new Map();this.sharing=new Map();this.saving=new Set();this.ended=new Set();this.tail=Promise.resolve();this.write=Promise.resolve();}
  get root(){return path.join(this.service.dir,'staging');}
  location(row){return path.join(this.root,row.id,row.offer.name);}
  async load(){
    await mkdir(this.root,{recursive:true});
    let snapshot;try{snapshot=JSON.parse(await readFile(path.join(this.root,'state.json'),'utf8'));if(snapshot.version!==1||!['rows','exports','sent'].every(key=>Array.isArray(snapshot[key])))throw Error('记录格式无效');}catch(error){if(error.code!=='ENOENT')throw Error('无法读取暂存记录：'+error.message);}
    try{const disk=snapshot?snapshot.rows:JSON.parse(await readFile(path.join(this.root,'index.json'),'utf8'));for(const r of disk.slice(0,200)){try{if(!UUID.test(r.id)||typeof r.source!=='string'||r.ephemeral&&r.state==='pending')continue;r.offer=validateStage(r.offer);if(r.state==='receiving')r.state='failed';if(r.state==='ready'&&!(await lstat(this.location(r))).isSymbolicLink()){this.rows.set(r.id,r);}else if(r.state!=='ready')this.rows.set(r.id,r);}catch{}}}catch{}
    try{const disk=snapshot?snapshot.exports:JSON.parse(await readFile(path.join(this.root,'outgoing.json'),'utf8'));for(const o of disk.slice(0,200)){try{const offer=validateStage(o.offer);if(o.group!==this.service.group.data.id||o.until<Date.now()||o.ephemeral&&!o.started||typeof o.target!=='string')continue;const entries=new Map(o.entries);if([...entries.values()].some(e=>typeof e.location!=='string'||!path.isAbsolute(e.location)))continue;if(o.state==='sending'||o.state==='queued'){o.state='failed';o.error='上次发送已中断，请重试';}this.exports.set(offer.id,o);this.service.offers.set(offer.id,{entries,until:o.until,target:o.target,cancelled:o.state==='cancelled'});this.sent.set(offer.id,this.summary(o));}catch{}}}catch{}
    try{const disk=snapshot?snapshot.sent:JSON.parse(await readFile(path.join(this.root,'sent.json'),'utf8'));for(const r of disk.slice(0,200))if(UUID.test(r.id)&&typeof r.target==='string'&&!this.sent.has(r.id))this.sent.set(r.id,r);}catch{}
  }
  save(change={}){
    if(this.closed)return this.write;
    this.write=this.write.catch(()=>{}).then(async()=>{
      this.trimHistory();
      const snapshot={version:1,rows:[...this.rows.values()].filter(r=>r.id!==change.removeRow),exports:[...this.exports.values()].filter(o=>o.offer.id!==change.removeOutgoing),sent:[...this.sent.values()].filter(r=>r.id!==change.removeOutgoing)};
      await mkdir(this.root,{recursive:true});const dest=path.join(this.root,'state.json');await writeFile(dest+'.tmp',JSON.stringify(snapshot));await rename(dest+'.tmp',dest);
      // Candidate removals become visible only after the atomic manifest commit.
      if(change.removeRow)this.rows.delete(change.removeRow);
      if(change.removeOutgoing){this.exports.delete(change.removeOutgoing);this.service.offers.delete(change.removeOutgoing);this.sent.delete(change.removeOutgoing);}
    });return this.write;
  }
  summary(o){return {id:o.offer.id,name:o.offer.name,type:o.offer.type,size:o.offer.size,count:o.offer.files.length,target:o.target,targetName:o.targetName||this.service.peers.get(o.target)?.info?.name||this.service.config.knownDevices?.[o.target]?.name||'设备',state:o.state||'pending',error:o.error||'',created:o.created||Date.now(),rootLocation:o.rootLocation};}
  remember(o,state,error=''){o.state=state;o.error=error;this.sent.set(o.offer.id,this.summary(o));this.service.emit('change');}
  trimHistory(){const finished=[...this.sent.values()].filter(r=>!this.exports.has(r.id)).sort((a,b)=>b.created-a.created);for(const r of finished.slice(100))this.sent.delete(r.id);}
  outgoingList(){return [...this.sent.values()].sort((a,b)=>b.created-a.created).map(({rootLocation,...r})=>({...r,online:!!this.service.peers.get(r.target)&&!this.service.peers.get(r.target).closed}));}
  async close(){for(const controller of this.controllers.values())controller.abort();await Promise.allSettled([...this.tasks.values()]);await this.save();this.closed=true;}
  list(){return [...this.rows.values()].sort((a,b)=>b.created-a.created).map(r=>({id:r.id,name:r.offer.name,type:r.offer.type,size:r.offer.size,count:r.offer.files.length,source:r.source,sourceName:r.sourceName,created:r.created,state:r.state==='pending'&&this.tasks.has(r.id)?'queued':r.state,error:r.error||'',received:r.received||0,online:!!this.service.peers.get(r.source)&&!this.service.peers.get(r.source).closed,files:r.offer.files.map(f=>({name:f.name,relative:f.relative,size:f.size}))}));}
  prepare(paths,target){const work=(this.preparing||Promise.resolve()).catch(()=>{}).then(()=>this.prepareBatch(paths,target));this.preparing=work;return work;}
  async prepareBatch(paths,target){
    const s=this.service,p=s.getPeer(target);s.authorize(p,'clipboard');if(p.info?.capabilities?.staging!==1)throw Error('请先更新接收设备以使用暂存文件');
    if(!Array.isArray(paths)||!paths.length||paths.length>100||paths.some(p=>typeof p!=='string'||!path.isAbsolute(p)))throw Error('请选择有效的文件或文件夹');
    const result=[],prepared=[];
    for(const [id,o]of this.exports)if(o.until<Date.now()){this.exports.delete(id);s.offers.delete(id);}
    if(this.exports.size+new Set(paths).size>200)throw Error("发送记录已满，请先移除旧的分享记录或稍后重试");
    for(const location of [...new Set(paths)]){
      const info=await lstat(location);if(info.isSymbolicLink()||!info.isFile()&&!info.isDirectory())throw Error('不支持符号链接或特殊文件');
      const name=safeName(path.basename(location)),id=randomUUID(),files=[],directories=[],entries=new Map();
      const walk=async(local,relative)=>{if(files.length+directories.length>=COUNT)throw Error('单个分享项目最多包含 1000 个文件和目录');const st=await lstat(local);if(st.isSymbolicLink())throw Error('文件夹含符号链接，请移除后再分享');if(st.isDirectory()){directories.push(stagePath(relative));for(const child of await readdir(local))await walk(path.join(local,child),relative+'/'+safeName(child));}else if(st.isFile()){const file={id:randomUUID(),name:safeName(path.basename(local)),relative:stagePath(relative),size:st.size};files.push(file);entries.set(file.id,{location:await realpath(local),size:st.size,mtime:st.mtimeMs});}else throw Error('不支持特殊文件');};
      await walk(location,name);const offer=validateStage({id,name,type:info.isDirectory()?'folder':'file',files,directories});
      const until=Date.now()+7*86400000;prepared.push({offer,entries:[...entries],until,target,group:s.group.data.id,rootLocation:location,created:Date.now(),state:'pending',targetName:p.info?.name});result.push(offer);
    }
    for(const o of prepared){this.exports.set(o.offer.id,o);this.remember(o,'pending');s.offers.set(o.offer.id,{entries:new Map(o.entries),until:o.until,target});}
    try{await this.save();}catch(error){for(const o of prepared){this.exports.delete(o.offer.id);s.offers.delete(o.offer.id);this.sent.delete(o.offer.id);}throw error;}return result;
  }
  share(target,paths,start=true,session=null){const key=JSON.stringify([target,paths,start,session]);if(this.sharing.has(key))return this.sharing.get(key);const work=this.shareBatch(target,paths,start,session);this.sharing.set(key,work);work.finally(()=>this.sharing.delete(key)).catch(()=>{});return work;}
  async shareBatch(target,paths,start=true,session=null){
    if(!start){session ||= this.service.active===target?this.service.activeSession?.edgeSession:null;if(!UUID.test(session||''))throw Error('请在本次拖动或鼠标控制会话中发送待传输文件');if(this.ended.has(session))throw Error('本次鼠标操作已结束');}
    const offers=await this.prepare(paths,target);let attempted=null;const accepted=new Set();
    if(!start)for(const offer of offers){this.exports.get(offer.id).ephemeral=session;this.service.offers.get(offer.id).ephemeral=session;}
    try{const peer=this.service.getPeer(target);for(const offer of offers){if(!start&&this.ended.has(session))throw Error('本次鼠标操作已结束');const o=this.exports.get(offer.id);if(!o||o.state==='cancelled')continue;attempted=offer.id;this.remember(o,start?'queued':'pending');await peer.request('stage-offer',{offer,start,...(!start?{ephemeral:session}:{})},15000);accepted.add(offer.id);if(!start&&this.ended.has(session))throw Error('本次鼠标操作已结束');}}
    catch(error){for(const offer of offers){const o=this.exports.get(offer.id);if(!o||accepted.has(offer.id))continue;const ambiguous=offer.id===attempted&&(/响应超时|连接已断开|设备未连接|设备不在线/.test(error.message)||o.started);this.remember(o,'failed',ambiguous?'未能确认接收状态，可重试或取消':error.message);if(!ambiguous){this.exports.delete(offer.id);this.service.offers.delete(offer.id);}}if(!start)this.end(session);await this.save();throw error;}
    await this.save();return {message:!accepted.size?'发送已取消':start?'已开始发送，接收完成后会更新状态':'待传输文件已添加到对方暂存区'};
  }
  async accept(peer,args){
    this.service.authorize(peer,'clipboard');const offer=validateStage(args.offer);let row=[...this.rows.values()].find(r=>r.source===peer.remote.id&&r.offer.id===offer.id);
    if(args.ephemeral!==undefined&&!UUID.test(args.ephemeral))throw Error('拖动会话无效');
    if(!row){if(this.rows.size>=200)throw Error('暂存区已满，请先保存或移除文件');row={id:randomUUID(),offer,source:peer.remote.id,sourceName:peer.info?.name||'设备',created:Date.now(),state:'pending',received:0,...(args.ephemeral?{ephemeral:args.ephemeral}:{})};this.rows.set(row.id,row);await this.save();this.service.emit('change');}
    else if(JSON.stringify(row.offer)!==JSON.stringify(offer))throw Error('分享项目已改变');
    if(row.state==='ready')peer.request('stage-complete',{id:offer.id},4000).catch(()=>{});
    if(args.start===true&&row.state!=='ready'&&(row.state!=='cancelled'||args.resume===true))this.start(row.id,args.resume===true).catch(e=>this.service.log(e.message));return {id:row.id};
  }
  start(id,resume=true){
    if(this.saving.has(id))return Promise.reject(Error('文件正在保存或移除，请稍后重试'));
    if(this.tasks.has(id)){if(this.controllers.get(id)?.signal.aborted)return this.tasks.get(id).catch(()=>{}).then(()=>this.start(id,resume));return this.tasks.get(id);}
    const controller=new AbortController();this.controllers.set(id,controller);const r=this.rows.get(id);if(r&&r.state!=='ready'){r.state='pending';r.error='';}const turn=this.tail.catch(()=>{});const work=withAbort(turn,controller.signal).then(()=>{controller.signal.throwIfAborted();return this.rows.has(id)?this.download(id,controller.signal,resume):{};}).catch(async error=>{const r=this.rows.get(id);if(r&&r.state!=='ready'){if(r.ephemeral&&!r.started&&!controller.signal.aborted)this.rows.delete(id);else{r.state=controller.signal.aborted?'cancelled':'failed';r.error=controller.signal.aborted?'已取消，可重新传输':error.message;}await this.save();this.service.emit('change');}throw error;});this.tasks.set(id,work);this.tail=Promise.allSettled([turn,work]);work.finally(()=>{this.tasks.delete(id);this.controllers.delete(id);}).catch(()=>{});return work;
  }
  async download(id,signal,resume){
    if(this.closed||this.service.closing)throw Error('传输已取消');const r=this.rows.get(id);if(!r)throw Error('暂存文件不存在');if(r.state==='ready')return {message:'文件已接收'};
    const s=this.service,p=s.getPeer(r.source);s.authorize(p,'clipboard');r.state='receiving';r.received=0;r.error='';await withAbort(p.request('stage-begin',{id:r.offer.id,resume},4000),signal);signal?.throwIfAborted();r.started=true;await this.save();s.emit('change');
    const folder=path.join(this.root,r.id),partial=folder+'.part';
    try{
      await rm(partial,{recursive:true,force:true});await rm(folder,{recursive:true,force:true});await mkdir(partial,{recursive:true});
      for(const dir of r.offer.directories)await mkdir(path.join(partial,...dir.split('/')),{recursive:true});
      for(const f of r.offer.files){const dest=path.join(partial,...f.relative.split('/'));await mkdir(path.dirname(dest),{recursive:true});const h=await open(dest,'wx');let base=r.received;
        const check=()=>{if(signal?.aborted||this.rows.get(id)!==r||s.closing)throw Error('传输已取消');s.authorize(s.getPeer(r.source),'clipboard');};
        const write=async b=>{check();await h.writeFile(b);},progress=n=>{r.received=base+n;s.emit('change');};
        try{const args={offerId:r.offer.id,fileId:f.id,offset:0};if(p.quicConn&&!p.fastLink)await receiveQuicFile(s,p,args,f.size,write,progress,signal);else if(p.info?.capabilities?.bulk===1)await receiveBulk(s,r.source,args,f.size,write,progress,undefined,signal);else{let offset=0;while(offset<f.size){check();const chunk=await withAbort(s.getPeer(r.source).request('fileChunk',{...args,offset}),signal);const b=Buffer.from(chunk.data,'base64');if(!b.length||b.length>192*1024||offset+b.length>f.size||createHash('sha256').update(b).digest('hex')!==chunk.sha256)throw Error('文件校验失败');await write(b);offset+=b.length;progress(offset);}}check();}finally{await h.close();}
      }
      if(signal?.aborted||this.rows.get(id)!==r)throw Error('传输已取消');await rename(partial,folder);r.state='ready';r.received=r.offer.size;r.error='';await this.save();s.emit('change');await p.request('stage-complete',{id:r.offer.id},4000).catch(()=>{});return {message:'文件已接收到暂存区'};
    }catch(e){await rm(partial,{recursive:true,force:true});if(this.rows.get(id)===r){r.state=signal?.aborted?'cancelled':'failed';r.error=signal?.aborted?'已取消，可重新传输':e.message;await this.save();s.emit('change');}if(!signal?.aborted)p.request('stage-failed',{id:r.offer.id,error:e.message},3000).catch(()=>{});throw e;}
  }
  async ready(id){const r=this.rows.get(id);if(!r||r.state!=='ready')throw Error('文件尚未传输完成');const location=this.location(r);const resolved=await realpath(location),root=await realpath(this.root);if(!resolved.startsWith(root+path.sep))throw Error('暂存文件路径无效');return {path:location,name:r.offer.name,type:r.offer.type};}
  async remove(id){
    const r=this.rows.get(id);if(!r)return {};if(this.saving.has(id))throw Error('正在保存，请稍后移除');this.saving.add(id);
    try{if(r.state==='cancelled'&&this.tasks.has(id))await this.tasks.get(id).catch(()=>{});else if(this.tasks.has(id)&&r.state!=='ready'||r.state==='receiving')throw Error('正在传输，请先取消后移除');await this.save({removeRow:id});await rm(path.join(this.root,r.id),{recursive:true,force:true});this.service.emit('change');return {message:'已移除暂存文件，发送端原文件保留'};}finally{this.saving.delete(id);}
  }
  async available(peer,id){const entry=this.service.offers.get(id);if(!entry||entry.target!==peer.remote.id||entry.until<Date.now()||entry.cancelled)return {available:false};try{const location=this.exports.get(id)?.rootLocation;if(location)await lstat(location);for(const file of entry.entries.values()){const st=await lstat(file.location);if(!st.isFile()||st.size!==file.size||st.mtimeMs!==file.mtime)return {available:false};}return {available:true};}catch{return {available:false};}}
  async retract(peer,session){for(const r of [...this.rows.values()])if(r.source===peer.remote.id&&r.ephemeral===session&&r.state==='pending')this.rows.delete(r.id);await this.save();this.service.emit('change');return {};}
  async begin(peer,id,resume=false){const o=this.exports.get(id),entry=this.service.offers.get(id);if(o?.target===peer.remote.id&&entry?.cancelled&&!resume)throw Error('发送已取消，请手动重新传输');if(o?.target===peer.remote.id&&entry&&resume)entry.cancelled=false;if(!o||o.target!==peer.remote.id||(await this.available(peer,id)).available!==true)throw Error('源文件已删除或分享已结束');o.started=true;this.remember(o,'sending');await this.save();return {};}
  async complete(peer,id){const o=this.exports.get(id);if(o?.target===peer.remote.id){this.remember(o,'completed');this.exports.delete(id);this.service.offers.delete(id);await this.save();}return {};}
  async failed(peer,id,error){const o=this.exports.get(id);if(o?.target===peer.remote.id){if(o.state!=='cancelled')this.remember(o,'failed',String(error||'接收失败，请重试').slice(0,300));await this.save();}return {};}
  receipt(peer,id){const row=[...this.rows.values()].find(r=>r.source===peer.remote.id&&r.offer.id===id);return {received:!!row,state:row?.state};}
  async cancel(id,notify=true){const r=this.rows.get(id);if(!r)return {};if(r.state==='ready')throw Error('文件已接收完成，可保存或移除');this.controllers.get(id)?.abort();r.state='cancelled';r.error='已取消，可重新传输';await this.save();this.service.emit('change');if(notify)this.service.peers.get(r.source)?.request('stage-cancel',{id:r.offer.id},3000).catch(()=>{});return {message:'传输已取消，可重新传输'};}
  async cancelOutgoing(id,notify=true){const o=this.exports.get(id);if(!o){if(this.sent.has(id)&&this.sent.get(id).state!=='completed'){this.sent.get(id).state='cancelled';await this.save();}return {};}
    const entry=this.service.offers.get(id);if(entry)entry.cancelled=true;this.service.bulk?.closeOffer(o.target,id);this.remember(o,'cancelled','已取消，可重新发送');await this.save();if(notify)this.service.peers.get(o.target)?.request('stage-cancel',{id},3000).catch(()=>{});return {message:'发送已取消，可重新发送'};
  }
  async remoteCancel(peer,id){if(this.exports.get(id)?.target===peer.remote.id)await this.cancelOutgoing(id,false);for(const r of this.rows.values())if(r.source===peer.remote.id&&r.offer.id===id){if(r.state==='ready')peer.request('stage-complete',{id},3000).catch(()=>{});else await this.cancel(r.id,false);}return {};}
  async retryOutgoing(id){const record=this.sent.get(id);if(!record)throw Error('发送记录已不存在');if(record.state==='completed')return {message:'对方已接收完成'};const o=this.exports.get(id);if(!o){const result=await this.share(record.target,[record.rootLocation]);this.sent.delete(id);await this.save();return result;}
    const peer=this.service.getPeer(o.target);this.service.authorize(peer,'clipboard');const entry=this.service.offers.get(id);if(entry)entry.cancelled=false;if(!(await this.available(peer,id)).available)throw Error('源文件已改变或删除，请重新选择发送');this.remember(o,'queued');await this.save();try{await peer.request('stage-offer',{offer:o.offer,start:true,resume:true},15000);}catch(error){this.remember(o,'failed',error.message);await this.save();throw error;}return {message:'已重新开始发送，接收完成后会更新状态'};
  }
  async removeOutgoing(id){const o=this.exports.get(id);if(o)await this.cancelOutgoing(id);await this.save({removeOutgoing:id});this.service.emit('change');return {message:'发送记录已清除，原文件保留'};}
  async cancelPeer(id){for(const r of this.rows.values())if(r.source===id&&['pending','receiving'].includes(r.state))await this.cancel(r.id);for(const o of this.exports.values())if(o.target===id&&['pending','queued','sending'].includes(o.state))await this.cancelOutgoing(o.offer.id);}
  async cancelAll(){for(const id of new Set([...this.rows.values()].map(r=>r.source).concat([...this.exports.values()].map(o=>o.target))))await this.cancelPeer(id);}
  disconnected(id){if(this.service.closing)return;for(const o of this.exports.values())if(o.target===id&&['queued','sending'].includes(o.state))this.remember(o,'failed','设备已离线，重新连接后可重试');this.save().catch(()=>{});}
  async saveTo(id,destination){
    if(typeof destination!=='string'||!path.isAbsolute(destination)||destination.includes('\0'))throw Error('请选择有效的保存位置');if(this.saving.has(id))throw Error('此文件正在保存');this.saving.add(id);let created=false;
    try{const info=await this.ready(id),r=this.rows.get(id),dest=path.resolve(destination),root=await realpath(this.root),parent=await realpath(path.dirname(dest));if(parent===root||parent.startsWith(root+path.sep))throw Error('请选择暂存区以外的保存位置');
      // Exclusive creation protects existing files, including symlinks. Failed
      // saves remove only the new destination and retain the staging original.
      if(info.type==='file'){await copyFile(info.path,dest,constants.COPYFILE_EXCL);created=true;}else{await mkdir(dest);created=true;for(const dir of r.offer.directories){const relative=dir.split('/').slice(1);if(relative.length)await mkdir(path.join(dest,...relative),{recursive:true});}for(const f of r.offer.files)await copyFile(path.join(this.root,r.id,...f.relative.split('/')),path.join(dest,...f.relative.split('/').slice(1)),constants.COPYFILE_EXCL);}
      await this.save({removeRow:id});await rm(path.join(this.root,id),{recursive:true,force:true}).catch(error=>this.service.log('已保存，暂存副本清理失败：'+error.message));this.service.emit('change');return {path:dest,message:'文件已保存'};
    }catch(error){if(created)await rm(destination,{recursive:true,force:true});if(error.code==='EEXIST')throw Error('此位置已有同名文件，请换一个名称');throw error;}finally{this.saving.delete(id);}
  }
  end(session){if(!session)return;this.ended.add(session);if(this.ended.size>512)this.ended.delete(this.ended.values().next().value);const targets=new Set();for(const [id,o]of this.exports)if(o.ephemeral===session){targets.add(o.target);if(!o.started){this.exports.delete(id);this.service.offers.delete(id);this.sent.delete(id);}}for(const target of targets)this.service.peers.get(target)?.request('stage-retract',{session},3000).catch(()=>{});this.save().catch(()=>{});}
  async tick(){if(this.polling)return;this.polling=true;try{let changed=false;for(const r of [...this.rows.values()].filter(r=>r.ephemeral&&r.state==='pending')){let available=false;try{available=(await this.service.getPeer(r.source).request('stage-status',{id:r.offer.id},2500)).available===true;}catch{}if(!available&&r.state==='pending'){this.rows.delete(r.id);changed=true;}}if(changed){await this.save();this.service.emit('change');}}finally{this.polling=false;}}
  clearExports(){for(const id of this.exports.keys())this.service.offers.delete(id);this.exports.clear();this.sent.clear();this.save().catch(()=>{});}
}
