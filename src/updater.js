import {readFile,writeFile,mkdir,mkdtemp,copyFile,rm} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {execFile,spawn} from 'node:child_process';
import {createWriteStream} from 'node:fs';
import {createHash} from 'node:crypto';
import {pipeline} from 'node:stream/promises';
import {promisify} from 'node:util';
import {gunzip} from 'node:zlib';
import {newer,materialize} from './update-package.js';
import {buildInfo} from './build-info.js';
import {commitConfig} from './identity.js';
export const releaseFeeds=['https://api.github.com/repos/lokey-t/lanlink/releases/latest','https://gitee.com/api/v5/repos/lokey-t/lanlink/releases/latest'];
const packageLimit = 512 * 1024 * 1024;
const checkInterval = 6 * 3600000;
async function fetchHttps(address, signal) {
 let url = new URL(address);
 for (let redirects = 0; redirects < 6; redirects++) {
  if (url.protocol !== 'https:') throw Error('HTTPS required');
  const response = await fetch(url, {signal, redirect:'manual', headers:{'User-Agent':'LanLink-Updater'}});
  if (response.ok) return response;
  const location = response.headers.get('location');
  await response.body?.cancel();
  if (response.status < 300 || response.status >= 400 || !location) {
   throw Object.assign(new Error('HTTP '+response.status), {status:response.status});
  }
  url = new URL(location, url);
 }
 throw Error('Too many redirects');
}
export async function download(url,limit=packageLimit,timeout=12000){const r=await fetchHttps(url,AbortSignal.timeout(timeout));const chunks=[];let size=0;for await(const b of r.body){size+=b.length;if(size>limit)throw Error('Update too large');chunks.push(b);}return Buffer.concat(chunks);}
async function* channelReleases(feed, channel, timeout) {
 const beta = channel === 'beta';
 const url = beta ? feed.replace('/latest', '?per_page=30') : feed;
 const response = JSON.parse((await download(url, 8*1024*1024, timeout)).toString());
 for (const entry of beta ? response : [response]) {
  if (!entry.draft && (entry.prerelease === true) === beta && /^v?\d+\.\d+\.\d+$/.test(entry.tag_name)) yield entry;
 }
}
class UpdateError extends Error {}
const missingPackage = '此版本暂未提供适用于本机的安装包';
const officialDownloads = 'https://lanlink.sites.show/#get';
function validDescriptor(descriptor) {
 return descriptor && /^\w[\w.-]*$/.test(descriptor.name) && /^[a-f0-9]{64}$/.test(descriptor.sha256) &&
     Number.isSafeInteger(descriptor.size) && descriptor.size > 0 && descriptor.size <= packageLimit;
}
function releasePage(feed, tag) {
 const host = new URL(feed).hostname === 'gitee.com' ? 'gitee.com' : 'github.com';
 return 'https://' + host + '/lokey-t/lanlink/releases/tag/' + encodeURIComponent(tag);
}
function listedAsset(release, name) {
 return release.assets.find(asset => {
  if (asset.name !== name) return false;
  try { return new URL(asset.browser_download_url).protocol === 'https:'; }
  catch { return false; }
 });
}
export async function release(channel='stable', target=process.platform+'-'+process.arch, installed=buildInfo.version) {
 const results = await Promise.allSettled(releaseFeeds.map(async feed => {
  const versions = [];
  for await (const entry of channelReleases(feed, channel, 8000)) {
   const assets = Array.isArray(entry.assets) ? entry.assets : [];
   const asset = assets.find(a => a.name === 'lanlink-update.json');
   const notes = typeof entry.body === 'string' ? entry.body.trim() : '';
   const candidate = {version:entry.tag_name.replace(/^v/, ''), assets, notes, page:releasePage(feed, entry.tag_name)};
   versions.push(candidate);
   if (!asset) continue;
   try {
    const manifest = JSON.parse((await download(asset.browser_download_url, 2*1024*1024, 8000)).toString());
    if (manifest.format === 1 && candidate.version === manifest.version) candidate.manifest = manifest;
   } catch { /* A listed release without a readable manifest cannot be downloaded in-app. */ }
   if (candidate.manifest) break;
  }
  return versions;
 }));
 const candidates = results.flatMap(result => result.status === 'fulfilled' ? result.value : []);
 candidates.sort((a,b) => newer(b.version,a.version) ? 1 : newer(a.version,b.version) ? -1 : 0);
 const highest = candidates[0];
 if (!highest) {
  if (results.some(result => result.status === 'rejected')) throw new UpdateError('无法连接更新服务器，请稍后重试');
  throw new UpdateError(channel === 'beta' ? '暂无可用的测试版发行' : '暂无可用的正式版发行');
 }
 for (const candidate of candidates) {
  const full = candidate.manifest?.targets?.[target]?.full;
  const mirrors = candidates.filter(mirror => mirror.version === candidate.version);
  if (!validDescriptor(full) || (candidate.version !== highest.version && !newer(candidate.version,installed))) continue;
  const source = mirrors.find(mirror => listedAsset(mirror,full.name));
  if (source) return {manifest:candidate.manifest,version:candidate.version,releases:mirrors,page:source.page,
   notes:source.notes || mirrors.find(mirror => mirror.notes)?.notes || ''};
 }
 return {version:highest.version,releases:[],page:highest.page,
  notes:candidates.find(candidate => candidate.version === highest.version && candidate.notes)?.notes || ''};
}
export async function assetBytes(releases, descriptor, {file, onProgress = () => {}} = {}) {
 if (!validDescriptor(descriptor)) throw new UpdateError(missingPackage);
 let error, attempt = 0;
 for (const mirror of releases) {
  const asset = listedAsset(mirror,descriptor.name);
  if (!asset) continue;
  try {
   const controller = new AbortController();
   const idle = setTimeout(() => controller.abort(), 30000);
   const digest = createHash('sha256'), chunks = [];
   let received = 0, lastBytes = 0, lastTime = performance.now(), speed = 0;
   const report = phase => onProgress({phase, received, total: descriptor.size, speed, attempt});
   attempt++;
   report('downloading');
   try {
    let response;
    try { response = await fetchHttps(asset.browser_download_url, controller.signal); }
    catch (error) {
     if (error.message === 'HTTPS required') throw error;
     throw new UpdateError(error.status === 404 ? '当前下载源缺少安装包' : '下载连接中断，请重试', {cause:error});
    }
    async function* measuredBody() {
     for await (const chunk of response.body) {
      idle.refresh();
      if (received + chunk.length > descriptor.size) throw new UpdateError('安装包校验失败，请重试');
      digest.update(chunk);
      yield chunk;
      received += chunk.length;
      const now = performance.now(), elapsed = now - lastTime;
      if (elapsed >= 500) {
       const rate = (received - lastBytes) * 1000 / elapsed;
       speed = speed ? speed * 0.65 + rate * 0.35 : rate;
       lastBytes = received;
       lastTime = now;
       report('downloading');
      }
     }
    }
    if (file) await pipeline(measuredBody(), createWriteStream(file, {mode: 0o600}));
    else for await (const chunk of measuredBody()) chunks.push(chunk);
    if (received !== descriptor.size) throw new UpdateError('安装包下载不完整，请重试');
    report('verifying');
    if (digest.digest('hex') !== descriptor.sha256) throw new UpdateError('安装包校验失败，请重试');
    return file ? undefined : Buffer.concat(chunks);
   } catch (error) {
    if (controller.signal.aborted && !(error instanceof UpdateError)) {
     throw new UpdateError('下载连接中断，请重试', {cause:error});
    }
    throw error;
   } finally { clearTimeout(idle); }
  } catch (e) { error = e; }
 }
 throw error || new UpdateError(missingPackage);
}

function updateMessage(error, fallback) {
 if (error instanceof UpdateError) return error.message;
 if (error.code === 'ENOSPC') return '存储空间不足，释放空间后重试';
 if (['EACCES','EPERM','EROFS'].includes(error.code)) return '无法写入更新目录，请手动安装';
 return fallback;
}

export class Updater{
 constructor(service,target=process.platform+'-'+process.arch){
  this.service=service;
  this.status={phase:'idle'};
  this.target=target;
  this.resultFile=service.dir ? path.join(service.dir,'update-result.json') : null;
 }
 async loadResult() {
  if (!this.resultFile) return;
  try {
   const saved = JSON.parse(await readFile(this.resultFile,'utf8'));
   if (saved.phase === 'installed' && saved.version === buildInfo.version) this.result = saved;
   else if (saved.phase === 'failed') this.result = saved;
  } catch (error) {
   if (error.code !== 'ENOENT') this.service.log?.(error.message);
  }
 }
 channel(){return this.service.config.updateChannel === 'beta' ? 'beta' : 'stable';}
 state(){
  const channel=this.channel();
  const status=this.status.phase==='ready'||this.checkedChannel===channel?this.status:{phase:'idle'};
  const autoCheckUpdates=this.service.config.autoCheckUpdates!==false;
  const offer=status.phase==='available'||status.phase==='unavailable';
  const reminder=this.service.config.updateReminders?.[channel]?.[status.version] ?? 0;
  const failed=this.result?.phase==='failed'&&this.result.version===status.version;
  const prompt=offer && reminder!==-1 && (this.manualCheck || (autoCheckUpdates && !failed && reminder<=Date.now()));
  return {...status,result:this.result,updateChannel:channel,autoCheckUpdates,prompt};
 }
 async deferPrompt({channel,version,choice}) {
  if (!['stable','beta'].includes(channel) || typeof version!=='string' || !/^\d+\.\d+\.\d+$/.test(version) ||
      !['later','skip'].includes(choice)) throw Error('无效的更新提醒');
  const until=choice==='skip' ? -1 : Date.now()+checkInterval;
  await commitConfig(this.service.dir,this.service.config,config=>({
   updateReminders:{...config.updateReminders,[channel]:{...config.updateReminders?.[channel],[version]:until}}
  }));
  if (this.checkedChannel===channel && this.status.version===version) this.manualCheck=false;
  return this.state();
 }
 start(){this.timer=setInterval(()=>this.automatic(),checkInterval);this.timer.unref();this.automatic();}
 automatic(){if(this.service.config.autoCheckUpdates!==false)this.check(false).catch(()=>{});}
 close(){clearInterval(this.timer);}
 canChangeChannel(){return !this.installing&&this.status.phase!=='ready';}
 beginInstall({channel,version}={}){
  if (version!==undefined && (channel!==this.channel() || this.state().phase!=='available' || this.status.version!==version))
   throw new UpdateError('更新信息已变化，请重新检查更新');
  if (!this.busy && this.status.phase !== 'ready') void this.install().catch(() => {});
  return this.state();
 }
 async check(manual=true){
  if(this.busy||this.status.phase==='ready'){
   if(manual && this.status.phase==='checking' && this.checkedChannel===this.channel()) this.manualCheck=true;
   return this.state();
  }
  this.busy=true;
  const channel=this.channel();
  this.checkedChannel=channel;
  this.manualCheck=manual;
  this.status={phase:'checking'};
  try{
   const selected=await release(channel,this.target);
   this.selected=selected;
   if(!newer(selected.version,buildInfo.version)) {
    this.status={phase:'current',message:newer(buildInfo.version,selected.version)?'当前版本高于所选通道的发行版':'已是最新版本'};
   } else if(!selected.manifest) {
    this.status={phase:'unavailable',version:selected.version,notes:selected.notes,message:missingPackage,downloadPage:selected.page};
   } else {
    this.status={phase:'available',version:selected.version,notes:selected.notes};
   }
  }catch(error){
   this.service.log?.(error.message);
   this.status={phase:'error',message:updateMessage(error,'无法读取更新信息，请稍后重试'),downloadPage:officialDownloads};
  }finally{this.busy=false;}
  return this.state();
 }
 async install(){if(this.status.phase==='ready')return this.state();if(this.busy)throw Error('Update already running');if(this.state().phase!=='available')await this.check(false);if(this.state().phase!=='available')return this.state();this.busy=this.installing=true;let work;try{
 const root=path.resolve(fileURLToPath(new URL('..',import.meta.url))),mac=process.platform==='darwin',install=mac?path.resolve(root,'../..'):root;
 if(!mac&&process.platform!=='win32')throw Error('Unsupported update platform');
 // Source checkouts must never be replaced by the updater.
 if(mac?!install.endsWith('.app'):!(await readFile(path.join(root,'runtime/node.exe')).catch(()=>null)))throw Error('请使用打包后的客户端进行更新');
 const channel=this.checkedChannel;
 const target=this.target,{manifest,page}=this.selected;
 this.status={phase:'preparing',version:manifest.version};work=await mkdtemp(path.join(path.dirname(install),'.lanlink-update-'));const stage=path.join(work,mac?'LanLink.app':'new');await mkdir(stage);
 const releases=uniqueAssets(this.selected.releases);
 await prepareUpdate({root:install,stage,target,version:buildInfo.version,manifest,releases,
  file:path.join(work,'download'),
  onProgress:(kind, progress)=>{this.status={...progress,kind,version:manifest.version};},
  validate:mac?()=>verifyMacUpdate(install,stage):undefined,
 });
 await rm(path.join(work,'download'),{force:true});
 const helper=path.join(work,'install.mjs'),runtime=path.join(work,mac?'node':'node.exe');await copyFile(process.execPath,runtime);const {chmod}=await import('node:fs/promises');await chmod(runtime,493);await copyFile(fileURLToPath(new URL('./update-install.js',import.meta.url)),helper);
 await writeFile(path.join(work,'plan.json'),JSON.stringify({
  install,stage,mac,parent:process.ppid,host:process.pid,resultFile:this.resultFile,
  version:manifest.version,previousVersion:buildInfo.version,page
 }));
 if(channel!==this.channel())throw Error('更新通道已更改，请重新检查更新');
 const child=spawn(runtime,[helper,path.join(work,'plan.json')],{cwd:work,detached:true,stdio:'ignore',windowsHide:true});await new Promise((resolve,reject)=>{child.once('spawn',resolve);child.once('error',reject);});child.unref();this.status={phase:'ready',version:manifest.version,message:'更新已准备好，请退出软件以完成安装'};return this.state();
 }catch(error){
  if(work)await rm(work,{recursive:true,force:true}).catch(()=>{});
  this.service.log?.(error.message);
  this.status={phase:'error',message:updateMessage(error,'无法完成更新准备，请重试'),
   downloadPage:this.selected?.page ?? officialDownloads,version:this.selected?.version};
  return this.state();
 }finally{this.busy=this.installing=false;}}
}

function uniqueAssets(releases) {
 const seen = new Set();
 return releases.map(({assets}) => ({assets: assets.filter(asset => {
  if (seen.has(asset.browser_download_url)) return false;
  seen.add(asset.browser_download_url);
  return true;
 })}));
}

const run = promisify(execFile);
const decompress = promisify(gunzip);

export async function verifyMacUpdate(installed, staged) {
 const bridge = 'Contents/Resources/native/macos/LanLinkBridge';
 await run('/usr/bin/codesign', ['--verify', '--deep', '--strict', '-R=identifier "com.lanlink.native"', staged]);
 for (const relative of ['', bridge]) {
  const previous = path.join(installed, relative);
  const next = path.join(staged, relative);
  await run('/usr/bin/codesign', ['--verify', '--strict', next]);
  const {stderr} = await run('/usr/bin/codesign', ['-dvv', '-r-', previous]);
  if (/^TeamIdentifier=not set$/m.test(stderr)) continue;
  const requirement = /^designated => (.+)$/m.exec(stderr)?.[1];
  if (!requirement) throw Error('Unable to read the installed signing requirement');
  await run('/usr/bin/codesign', ['--verify', '--strict', '-R='+requirement, next]);
 }
}

export async function prepareUpdate({root,stage,target,version,manifest,releases,file,onFallback=()=>{},onProgress=()=>{},validate=async()=>{}}){
 async function unpack(kind, descriptor) {
  const bytes = await assetBytes(releases, descriptor, {file, onProgress: progress => onProgress(kind, progress)});
  onProgress(kind, {phase:'preparing'});
  return JSON.parse(await decompress(file ? await readFile(file) : bytes, {maxOutputLength:1024*1024*1024}));
 }
 const spec = manifest.targets[target];
 const delta = spec.delta ?? manifest.delta;
 const matchingBase = spec.deltaFrom === version || (!spec.delta && !spec.deltaFrom);
 if (validDescriptor(delta) && delta.size < spec.full.size && matchingBase) {
  try {
   const bundle = await unpack('delta', delta);
   const patch = bundle.targets?.[target];
   if (bundle.format !== 1 || bundle.version !== manifest.version || patch?.from !== version) throw Error('Unsupported base version');
   await materialize(root, stage, patch.files);
   await validate();
   return;
  } catch (e) {
   onFallback(e);
   await rm(stage,{recursive:true,force:true});
   await mkdir(stage);
  }
 }
 const full = await unpack('full', spec.full);
 if (full.format !== 1 || full.version !== manifest.version) throw Error('Invalid full package');
 await materialize(root, stage, full.files);
 await validate();
}
