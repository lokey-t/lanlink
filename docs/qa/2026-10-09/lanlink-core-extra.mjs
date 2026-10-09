import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {mkdtemp,mkdir,writeFile,readFile,rm} from 'node:fs/promises';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {Service} from '/Users/alice/Documents/项目/LLM/project/lanlink/src/service.js';
import {newToken,loadConfig} from '/Users/alice/Documents/项目/LLM/project/lanlink/src/identity.js';

class Native extends EventEmitter {
  capabilities={host:true,input:true,screen:true,clipboard:true,shutdown:true}; clip={text:'',files:[]}; calls=[];
  async start(){} close(){}
  async call(method,args){this.calls.push({method,args});if(method==='clipboardGet')return this.clip;if(method==='clipboardSet')this.clip={text:args.text||'',files:args.files||[]};if(method==='capabilities')return this.capabilities;return {};}
}
const delay=ms=>new Promise(r=>setTimeout(r,ms));
async function until(fn){const end=Date.now()+5000;while(!fn()){if(Date.now()>end)throw Error('timed out');await delay(10);}}
const root=await mkdtemp('/tmp/lanlink-extra-'),services=[],token=newToken();
async function make(name,start=true){const dir=path.join(root,name);await mkdir(dir);const s=new Service({id:randomUUID(),name,token,permissions:{input:true,screen:true,clipboard:true,shutdown:true},edges:{}},dir,new Native(),{host:'127.0.0.1',port:0,noDiscovery:true});services.push(s);if(start)await s.start();return s;}
const results=[];
function report(id,data){const item={id,...data};results.push(item);console.log(JSON.stringify(item));}
try{
 const a=await make('A'),b=await make('B');a.connect('127.0.0.1',b.port);await until(()=>a.peers.size===1&&b.peers.size===1&&a.peers.values().next().value.info&&b.peers.values().next().value.info);
 a.native.clip={text:'same-text-X',files:[]};await a.snapshotClipboard();await until(()=>b.native.clip.text==='same-text-X');
 b.native.clip={text:'intermediate-Y',files:[]};await b.snapshotClipboard();await until(()=>a.native.clip.text==='intermediate-Y');
 a.native.clip={text:'same-text-X',files:[]};await a.snapshotClipboard();await delay(250);
 assert.equal(b.native.clip.text,'intermediate-Y');assert.equal(a.clipboardSync.latest.text,'intermediate-Y');
 report('clipboard-return-to-prior-content',{confirmed:true,expected:'B receives fresh X',actual:{aSystem:a.native.clip.text,bSystem:b.native.clip.text,aLatest:a.clipboardSync.latest.text},repro:'A copies X → B copies Y → A copies X again'});
 const before=b.getPeer(a.config.id).info.name;await a.settings({name:'A-Renamed'});await a.tick();await b.tick();await delay(500);
 assert.equal(a.state().name,'A-Renamed');assert.equal(b.getPeer(a.config.id).info.name,before);
 report('online-rename-stale',{confirmed:true,actual:{local:a.state().name,remote:b.getPeer(a.config.id).info.name,remoteSaved:b.config.knownDevices[a.config.id].name},repro:'Rename online A, refresh/tick both sides'});
 const d=await make('files',false),file=path.join(d.dir,'sample.txt');await writeFile(file,'file payload');d.native.clip={text:'',files:[file]};await d.snapshotClipboard(true,true);const original=d.clipboardSync.latest;
 for(let i=0;i<20;i++)await d.clipboardSync.uploadNow();
 assert.equal(d.clipboardSync.latest.id,original.id);assert.equal(d.offers.has(original.id),false);
 let transferError;try{await d.fileBytes({offerId:original.id,fileId:original.files[0].id,offset:0});}catch(e){transferError=e.message;}assert.match(transferError,/过期/);
 report('unchanged-file-upload-evicts-live-offer',{confirmed:true,actual:{offerCount:d.offers.size,latestOfferPresent:d.offers.has(original.id),transferError},repro:'Copy file, upload original plus 20 unchanged repeated uploads, receiver then fetches latest file'});
 await d.settings({theme:'dark'});let settingsError;try{await d.settings({theme:'light',name:''});}catch(e){settingsError=e.message;}
 const disk=await loadConfig(d.dir);assert.equal(d.config.theme,'light');assert.equal(disk.theme,'dark');
 report('rejected-settings-partially-apply',{confirmed:true,actual:{settingsError,inMemoryTheme:d.config.theme,persistedTheme:disk.theme},repro:'settings(theme:light,name:empty) after theme dark'});
 const e=await make('failed-send',false),target=randomUUID();const peer={remote:{id:target},info:{name:'full-target',capabilities:{staging:1}},closed:false,request:async()=>{throw Error('暂存区已满，请先保存或移除文件');}};e.peers.set(target,peer);const source=path.join(e.dir,'send.txt');await writeFile(source,'payload');
 for(let i=0;i<3;i++)await assert.rejects(e.staging.share(target,[source]),/暂存区已满/);
 assert.equal(e.staging.exports.size,3);assert.equal(e.offers.size,3);
 report('failed-file-send-retains-outgoing-slots',{confirmed:true,actual:{failedAttempts:3,outgoingSlots:e.staging.exports.size,sourceOffers:e.offers.size},repro:'Receiver stage-offer rejects; retry same file three times'});
 // Remove stub before service cleanup; no actual device or local clipboard used.
 e.peers.clear();
 // Independent positive permission boundary: explicit denial blocks every download write.
 const f=await make('revoke-check',false),sourceId=randomUUID();f.config.deviceGrants[sourceId]={clipboard:false};const count=f.native.calls.length;
 await assert.rejects(f.materialize({id:'offer',source:sourceId,text:'denied',files:[]}),/未授权/);assert.equal(f.native.calls.length,count);
 report('clipboard-explicit-denial',{passed:true,actual:'No native call after revoked source attempted text write'});
 await writeFile('/tmp/lanlink-core-extra-results.json',JSON.stringify(results,null,2));
}finally{for(const s of services){await s.close().catch(e=>console.error('cleanup',e.message));}await rm(root,{recursive:true,force:true});}
