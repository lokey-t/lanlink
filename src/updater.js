import {readFile,writeFile,mkdir,mkdtemp,copyFile,rm} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import os from 'node:os';
import {spawn} from 'node:child_process';
import {gunzipSync} from 'node:zlib';
import {hash,newer,materialize} from './update-package.js';
import {buildInfo} from './build-info.js';
export const releaseFeeds=['https://api.github.com/repos/lokey-t/lanlink/releases/latest','https://gitee.com/api/v5/repos/lokey-t/lanlink/releases/latest'];
export async function download(url,limit=512*1024*1024){if(new URL(url).protocol!=='https:')throw Error('HTTPS required');const r=await fetch(url,{signal:AbortSignal.timeout(120000),headers:{'User-Agent':'LanLink-Updater'}});if(!r.ok)throw Error('HTTP '+r.status);const chunks=[];let size=0;for await(const b of r.body){size+=b.length;if(size>limit)throw Error('Update too large');chunks.push(b);}return Buffer.concat(chunks);}
export async function release(){let last;for(const feed of releaseFeeds)try{const r=JSON.parse((await download(feed,2*1024*1024)).toString());if(r.draft||r.prerelease)continue;const assets=r.assets||[],asset=assets.find(a=>a.name==='lanlink-update.json');if(!asset)throw Error('Release has no update manifest');const manifest=JSON.parse((await download(asset.browser_download_url,2*1024*1024)).toString());if(manifest.format!==1||r.tag_name.replace(/^v/,'')!==manifest.version)throw Error('Invalid manifest');return {manifest,assets};}catch(e){last=e;}throw last||Error('No release');}
export async function assetBytes(releases,descriptor){if(!descriptor||!/^\w[\w.\-]*$/.test(descriptor.name)||!/^[a-f0-9]{64}$/.test(descriptor.sha256))throw Error('Invalid asset');let error;for(const r of releases){try{const a=r.assets.find(a=>a.name===descriptor.name);if(!a)continue;const bytes=await download(a.browser_download_url);if(bytes.length!==descriptor.size||hash(bytes)!==descriptor.sha256)throw Error('Asset checksum mismatch');return bytes;}catch(e){error=e;}}throw error||Error('Missing asset');}
export class Updater{
 constructor(service){this.service=service;this.status={phase:'idle'};}
 state(){return {...this.status,autoCheckUpdates:this.service.config.autoCheckUpdates!==false};}
 start(){this.timer=setInterval(()=>this.automatic(),6*3600000);this.timer.unref();this.automatic();}
 automatic(){if(this.service.config.autoCheckUpdates!==false)this.check().catch(()=>{});}
 close(){clearInterval(this.timer);}
 async check(){if(this.busy)return this.state();this.busy=true;try{const r=await release();this.releases=[r];this.status=newer(r.manifest.version,buildInfo.version)?{phase:'available',version:r.manifest.version}:{phase:'current'};return this.state();}catch(e){this.status={phase:'error',message:e.message};throw e;}finally{this.busy=false;}}
 async install(){if(this.status.phase==='ready')return this.state();if(this.busy)throw Error('Update already running');if(this.status.phase!=='available')await this.check();if(this.status.phase!=='available')return this.state();this.busy=true;let work;try{
 const root=path.resolve(fileURLToPath(new URL('..',import.meta.url))),mac=process.platform==='darwin',install=mac?path.resolve(root,'../..'):root;
 if(!mac&&process.platform!=='win32')throw Error('Unsupported update platform');
 // Source checkouts must never be replaced by the updater.
 if(mac?!install.endsWith('.app'):!(await readFile(path.join(root,'runtime/node.exe')).catch(()=>null)))throw Error('请使用打包后的客户端进行更新');
 const target=process.platform+'-'+process.arch,manifest=this.releases[0].manifest,spec=manifest.targets[target];if(!spec)throw Error('No update for this architecture');
 this.status={phase:'downloading',version:manifest.version};work=await mkdtemp(path.join(path.dirname(install),'.lanlink-update-'));const stage=path.join(work,'new');await mkdir(stage);
 // Add the other mirror when reachable; its asset must match the selected checksum.
 for(const feed of releaseFeeds)try{const r=JSON.parse((await download(feed,2*1024*1024)).toString());if(r.tag_name.replace(/^v/,'')===manifest.version)this.releases.push({assets:r.assets||[]});}catch{}
 await prepareUpdate({root:install,stage,target,version:buildInfo.version,manifest,releases:this.releases,onFallback:e=>{this.status={phase:'full-download',version:manifest.version,message:e.message};}});
 const helper=path.join(work,'install.mjs'),runtime=path.join(work,mac?'node':'node.exe');await copyFile(process.execPath,runtime);const {chmod}=await import('node:fs/promises');await chmod(runtime,493);await copyFile(fileURLToPath(new URL('./update-install.js',import.meta.url)),helper);
 await writeFile(path.join(work,'plan.json'),JSON.stringify({install,stage,mac,parent:process.ppid,host:process.pid}));
 const child=spawn(runtime,[helper,path.join(work,'plan.json')],{detached:true,stdio:'ignore',windowsHide:true});await new Promise((resolve,reject)=>{child.once('spawn',resolve);child.once('error',reject);});child.unref();this.status={phase:'ready',version:manifest.version,message:'更新已准备好，请退出软件以完成安装'};return this.state();
 }catch(e){if(work)await rm(work,{recursive:true,force:true});this.status={phase:'error',message:e.message};throw e;}finally{this.busy=false;}}
}

export async function prepareUpdate({root,stage,target,version,manifest,releases,onFallback=()=>{}}){
 try{const bundle=JSON.parse(gunzipSync(await assetBytes(releases,manifest.delta),{maxOutputLength:1024*1024*1024}));const patch=bundle.targets[target];if(bundle.format!==1||bundle.version!==manifest.version||patch.from!==version)throw Error('Unsupported base version');await materialize(root,stage,patch.files);}
 catch(e){onFallback(e);await rm(stage,{recursive:true,force:true});await mkdir(stage);const full=JSON.parse(gunzipSync(await assetBytes(releases,manifest.targets[target].full),{maxOutputLength:1024*1024*1024}));if(full.format!==1||full.version!==manifest.version)throw Error('Invalid full package');await materialize(root,stage,full.files);}
}
