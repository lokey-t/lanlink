import {randomUUID,createHash} from 'node:crypto';
import {mkdir,readFile,writeFile,rename,rm,lstat,readdir,realpath,open} from 'node:fs/promises';
import path from 'node:path';
import {receiveBulk,receiveQuicFile} from './bulk.js';
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
  constructor(service){this.service=service;this.rows=new Map();this.exports=new Map();this.tasks=new Map();this.ended=new Set();this.tail=Promise.resolve();this.write=Promise.resolve();}
  get root(){return path.join(this.service.dir,'staging');}
  location(row){return path.join(this.root,row.id,row.offer.name);}
  async load(){
    await mkdir(this.root,{recursive:true});
    try{const disk=JSON.parse(await readFile(path.join(this.root,'index.json'),'utf8'));for(const r of disk.slice(0,200)){try{if(!UUID.test(r.id)||typeof r.source!=='string'||r.ephemeral&&r.state==='pending')continue;r.offer=validateStage(r.offer);if(r.state==='receiving')r.state='failed';if(r.state==='ready'&&!(await lstat(this.location(r))).isSymbolicLink()){this.rows.set(r.id,r);}else if(r.state!=='ready')this.rows.set(r.id,r);}catch{}}}catch{}
    try{const disk=JSON.parse(await readFile(path.join(this.root,'outgoing.json'),'utf8'));for(const o of disk.slice(0,200)){try{const offer=validateStage(o.offer);if(o.group!==this.service.group.data.id||o.until<Date.now()||o.ephemeral&&!o.started||typeof o.target!=='string')continue;const entries=new Map(o.entries);if([...entries.values()].some(e=>typeof e.location!=='string'||!path.isAbsolute(e.location)))continue;this.exports.set(offer.id,o);this.service.offers.set(offer.id,{entries,until:o.until,target:o.target});}catch{}}}catch{}
  }
  save(){if(this.closed)return this.write;const rows=[...this.rows.values()],exports=[...this.exports.values()];this.write=this.write.catch(()=>{}).then(async()=>{await mkdir(this.root,{recursive:true});for(const [file,data]of [['index.json',rows],['outgoing.json',exports]]){const dest=path.join(this.root,file);await writeFile(dest+'.tmp',JSON.stringify(data));await rename(dest+'.tmp',dest);}});return this.write;}
  async close(){this.closed=true;await Promise.allSettled([...this.tasks.values()]);await this.write;}
  list(){return [...this.rows.values()].sort((a,b)=>b.created-a.created).map(r=>({id:r.id,name:r.offer.name,type:r.offer.type,size:r.offer.size,count:r.offer.files.length,source:r.source,sourceName:r.sourceName,created:r.created,state:r.state==='pending'&&this.tasks.has(r.id)?'queued':r.state,error:r.error||'',received:r.received||0,online:!!this.service.peers.get(r.source)&&!this.service.peers.get(r.source).closed,files:r.offer.files.map(f=>({name:f.name,relative:f.relative,size:f.size}))}));}
  async prepare(paths,target){
    const s=this.service,p=s.getPeer(target);s.authorize(p,'clipboard');if(p.info?.capabilities?.staging!==1)throw Error('请先更新接收设备以使用暂存文件');
    if(!Array.isArray(paths)||!paths.length||paths.length>100||paths.some(p=>typeof p!=='string'||!path.isAbsolute(p)))throw Error('请选择有效的文件或文件夹');
    const result=[],prepared=[];
    for(const [id,o]of this.exports)if(o.until<Date.now()){this.exports.delete(id);s.offers.delete(id);}
    if(this.exports.size+paths.length>200)throw Error("发送记录已满，请先移除旧的分享记录或稍后重试");
    for(const location of [...new Set(paths)]){
      const info=await lstat(location);if(info.isSymbolicLink()||!info.isFile()&&!info.isDirectory())throw Error('不支持符号链接或特殊文件');
      const name=safeName(path.basename(location)),id=randomUUID(),files=[],directories=[],entries=new Map();
      const walk=async(local,relative)=>{if(files.length+directories.length>=COUNT)throw Error('单个分享项目最多包含 1000 个文件和目录');const st=await lstat(local);if(st.isSymbolicLink())throw Error('文件夹含符号链接，请移除后再分享');if(st.isDirectory()){directories.push(stagePath(relative));for(const child of await readdir(local))await walk(path.join(local,child),relative+'/'+safeName(child));}else if(st.isFile()){const file={id:randomUUID(),name:safeName(path.basename(local)),relative:stagePath(relative),size:st.size};files.push(file);entries.set(file.id,{location:await realpath(local),size:st.size,mtime:st.mtimeMs});}else throw Error('不支持特殊文件');};
      await walk(location,name);const offer=validateStage({id,name,type:info.isDirectory()?'folder':'file',files,directories});
      const until=Date.now()+7*86400000;prepared.push({offer,entries:[...entries],until,target,group:s.group.data.id,rootLocation:location});result.push(offer);
    }
    for(const o of prepared){this.exports.set(o.offer.id,o);s.offers.set(o.offer.id,{entries:new Map(o.entries),until:o.until,target});}
    await this.save();return result;
  }
  async share(target,paths,start=true,session=null){
    if(!start){session ||= this.service.active===target?this.service.activeSession?.edgeSession:null;if(!UUID.test(session||''))throw Error('请在本次拖动或鼠标控制会话中发送待传输文件');if(this.ended.has(session))throw Error('本次鼠标操作已结束');}
    const offers=await this.prepare(paths,target),peer=this.service.getPeer(target);
    if(!start)for(const offer of offers){this.exports.get(offer.id).ephemeral=session;this.service.offers.get(offer.id).ephemeral=session;}
    try{for(const offer of offers){if(!start&&this.ended.has(session))throw Error('本次鼠标操作已结束');await peer.request('stage-offer',{offer,start,...(!start?{ephemeral:session}:{})},15000);if(!start&&this.ended.has(session))throw Error('本次鼠标操作已结束');}}
    catch(e){if(!start)this.end(session);throw e;}
    await this.save();return {message:start?'文件已发送，接收设备正在传输到暂存区':'待传输文件已添加到对方暂存区'};
  }
  async accept(peer,args){
    this.service.authorize(peer,'clipboard');const offer=validateStage(args.offer);let row=[...this.rows.values()].find(r=>r.source===peer.remote.id&&r.offer.id===offer.id);
    if(args.ephemeral!==undefined&&!UUID.test(args.ephemeral))throw Error('拖动会话无效');
    if(!row){if(this.rows.size>=200)throw Error('暂存区已满，请先保存或移除文件');row={id:randomUUID(),offer,source:peer.remote.id,sourceName:peer.info?.name||'设备',created:Date.now(),state:'pending',received:0,...(args.ephemeral?{ephemeral:args.ephemeral}:{})};this.rows.set(row.id,row);await this.save();this.service.emit('change');}
    else if(JSON.stringify(row.offer)!==JSON.stringify(offer))throw Error('分享项目已改变');
    if(args.start===true&&row.state!=='ready')this.start(row.id).catch(e=>this.service.log(e.message));return {id:row.id};
  }
  start(id){if(this.tasks.has(id))return this.tasks.get(id);const work=this.tail.catch(()=>{}).then(()=>this.rows.has(id)?this.download(id):{}).catch(async e=>{const r=this.rows.get(id);if(r&&r.state!=='ready'){if(r.ephemeral&&!r.started)this.rows.delete(id);else{r.state='failed';r.error=e.message;}await this.save();this.service.emit('change');}throw e;});this.tasks.set(id,work);this.tail=work;work.finally(()=>this.tasks.delete(id)).catch(()=>{});return work;}
  async download(id){
    if(this.closed||this.service.closing)throw Error('传输已取消');const r=this.rows.get(id);if(!r)throw Error('暂存文件不存在');if(r.state==='ready')return {message:'文件已接收'};
    const s=this.service,p=s.getPeer(r.source);s.authorize(p,'clipboard');r.state='receiving';r.received=0;r.error='';await p.request('stage-begin',{id:r.offer.id},4000);r.started=true;await this.save();s.emit('change');
    const folder=path.join(this.root,r.id),partial=folder+'.part';
    try{
      await rm(partial,{recursive:true,force:true});await mkdir(partial,{recursive:true});
      for(const dir of r.offer.directories)await mkdir(path.join(partial,...dir.split('/')),{recursive:true});
      for(const f of r.offer.files){const dest=path.join(partial,...f.relative.split('/'));await mkdir(path.dirname(dest),{recursive:true});const h=await open(dest,'wx');let base=r.received;
        const check=()=>{if(this.rows.get(id)!==r||s.closing)throw Error('传输已取消');s.authorize(s.getPeer(r.source),'clipboard');};
        const write=async b=>{check();await h.writeFile(b);},progress=n=>{r.received=base+n;s.emit('change');};
        try{const args={offerId:r.offer.id,fileId:f.id,offset:0};if(p.quicConn&&!p.fastLink)await receiveQuicFile(s,p,args,f.size,write,progress);else if(p.info?.capabilities?.bulk===1)await receiveBulk(s,r.source,args,f.size,write,progress);else{let offset=0;while(offset<f.size){check();const chunk=await s.getPeer(r.source).request('fileChunk',{...args,offset});const b=Buffer.from(chunk.data,'base64');if(!b.length||b.length>192*1024||offset+b.length>f.size||createHash('sha256').update(b).digest('hex')!==chunk.sha256)throw Error('文件校验失败');await write(b);offset+=b.length;progress(offset);}}check();}finally{await h.close();}
      }
      if(this.rows.get(id)!==r)throw Error('传输已取消');await rename(partial,folder);r.state='ready';r.received=r.offer.size;r.error='';await this.save();s.emit('change');await p.request('stage-complete',{id:r.offer.id},4000).catch(()=>{});return {message:'文件已接收到暂存区'};
    }catch(e){await rm(partial,{recursive:true,force:true});if(this.rows.get(id)===r){r.state='failed';r.error=e.message;await this.save();s.emit('change');}throw e;}
  }
  async ready(id){const r=this.rows.get(id);if(!r||r.state!=='ready')throw Error('文件尚未传输完成');const location=this.location(r);const resolved=await realpath(location),root=await realpath(this.root);if(!resolved.startsWith(root+path.sep))throw Error('暂存文件路径无效');return {path:location,name:r.offer.name,type:r.offer.type};}
  async remove(id){const r=this.rows.get(id);if(!r)return {};if(r.state==='receiving')throw Error('正在传输，请等待完成后移除');this.rows.delete(id);await this.save();await rm(path.join(this.root,r.id),{recursive:true,force:true});this.service.emit('change');return {message:'已移除暂存文件，发送端原文件保留'};}
  async available(peer,id){const entry=this.service.offers.get(id);if(!entry||entry.target!==peer.remote.id||entry.until<Date.now())return {available:false};try{const location=this.exports.get(id)?.rootLocation;if(location)await lstat(location);for(const file of entry.entries.values()){const st=await lstat(file.location);if(!st.isFile()||st.size!==file.size||st.mtimeMs!==file.mtime)return {available:false};}return {available:true};}catch{return {available:false};}}
  async retract(peer,session){for(const r of [...this.rows.values()])if(r.source===peer.remote.id&&r.ephemeral===session&&r.state==='pending')this.rows.delete(r.id);await this.save();this.service.emit('change');return {};}
  async begin(peer,id){const o=this.exports.get(id);if(!o||o.target!==peer.remote.id||(await this.available(peer,id)).available!==true)throw Error('源文件已删除或分享已结束');o.started=true;await this.save();return {};}
  async complete(peer,id){const o=this.exports.get(id);if(o?.target===peer.remote.id){this.exports.delete(id);this.service.offers.delete(id);await this.save();}return {};}
  end(session){if(!session)return;this.ended.add(session);if(this.ended.size>512)this.ended.delete(this.ended.values().next().value);const targets=new Set();for(const [id,o]of this.exports)if(o.ephemeral===session){targets.add(o.target);if(!o.started){this.exports.delete(id);this.service.offers.delete(id);}}for(const target of targets)this.service.peers.get(target)?.request('stage-retract',{session},3000).catch(()=>{});this.save().catch(()=>{});}
  async tick(){if(this.polling)return;this.polling=true;try{let changed=false;for(const r of [...this.rows.values()].filter(r=>r.ephemeral&&r.state==='pending')){let available=false;try{available=(await this.service.getPeer(r.source).request('stage-status',{id:r.offer.id},2500)).available===true;}catch{}if(!available&&r.state==='pending'){this.rows.delete(r.id);changed=true;}}if(changed){await this.save();this.service.emit('change');}}finally{this.polling=false;}}
  clearExports(){for(const id of this.exports.keys())this.service.offers.delete(id);this.exports.clear();this.save().catch(()=>{});}
}
