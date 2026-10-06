import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {randomBytes,randomUUID} from 'node:crypto';
import {mkdtemp,mkdir,writeFile,readFile,rm} from 'node:fs/promises';
import path from 'node:path';import os from 'node:os';
import {connectBulk} from '../src/bulk.js';
import {Service} from '../src/service.js';import {newToken} from '../src/identity.js';
const pause=ms=>new Promise(r=>setTimeout(r,ms));
async function until(check){const end=Date.now()+6000;while(!check()){if(Date.now()>end)throw Error('connection timed out');await pause(10);}}
class Native extends EventEmitter{capabilities={host:true,input:true,screen:true,clipboard:true};clip={text:'',files:[]};async start(){}async call(method,args){if(method==='clipboardGet')return this.clip;if(method==='clipboardSet')this.clip=args;return {};}close(){}}
async function pair(t){const folder=await mkdtemp(path.join(os.tmpdir(),'lanlink-transfer-')),token=newToken(),list=[];t.after(async()=>{await Promise.all(list.map(s=>s.close()));await rm(folder,{recursive:true,force:true});});for(const name of ['A','B']){const dir=path.join(folder,name);await mkdir(dir);const s=new Service({id:randomUUID(),name,token,permissions:{input:true,screen:true,clipboard:true},edges:{}},dir,new Native(),{host:'127.0.0.1',port:0,noDiscovery:true,noQuic:true,videoSource:(native,h264,emit)=>{const timer=setInterval(()=>emit({codec:'jpeg',key:true,width:10,height:10,data:Buffer.from('encoded-frame')}),20);return {close(){clearInterval(timer);}};}});list.push(s);await s.start();}const [a,b]=list;a.connect('127.0.0.1',b.port);await until(()=>a.peers.get(b.config.id)?.info&&b.peers.get(a.config.id)?.info);a.config.deviceGrants[b.config.id]={screen:true,input:true,clipboard:true};b.config.deviceGrants[a.config.id]={screen:true,input:true,clipboard:true};return list;}
test('file payload uses separate binary sockets while the control connection remains responsive',async t=>{
  const [a,b]=await pair(t),data=randomBytes(2*1024*1024),location=path.join(a.dir,'binary.dat');await writeFile(location,data);a.native.clip={files:[location]};const offer=await a.snapshotClipboard(false);let legacy=0;a.fileChunk=()=>{legacy++;throw Error('no legacy payload');};
  const receive=b.materialize(offer);await until(()=>a.bulk.sessions.size>0);assert.deepEqual(await b.getPeer(a.config.id).request('linkPing',{}),{});await receive;
  assert.equal(legacy,0);assert.deepEqual(await readFile(b.native.clip.files[0]),data);
  b.config.deviceGrants[a.config.id].clipboard=false;await assert.rejects(a.getPeer(b.config.id).request('bulkOpen',{offerId:offer.id,fileId:offer.files[0].id,offset:0,end:data.length}),/剪贴板|未允许|授权/);
});
test('native viewer negotiates WebRTC and publishes current decoded frame metadata',async t=>{
  const [a,b]=await pair(t);await b.openStream(a.config.id,true);const frame=await b.videoViewer.next(a.config.id);assert.equal(frame.transport,'webrtc');assert.equal(Buffer.from(frame.data,'base64').toString(),'encoded-frame');await b.closeStream(a.config.id);assert.equal(a.videoHost.sessions.size,0);
});
test('failed RTC negotiation falls back to a fresh authorized TCP stream',async t=>{
  const [a,b]=await pair(t),open=a.videoHost.open.bind(a.videoHost);let failed=0;a.videoHost.open=async(peer,options)=>{if(options.rtc){failed++;throw Error('RTC unavailable fixture');}return open(peer,options);};
  await b.openStream(a.config.id,true);assert.equal(failed,1);assert.equal((await b.videoViewer.next(a.config.id)).transport,'tcp');assert.equal(a.videoHost.sessions.size,1);await b.closeStream(a.config.id);
});

test('interrupted binary transfer resumes from the last verified offset without replaying input',async t=>{
  const [a,b]=await pair(t),data=randomBytes(1024*1024),location=path.join(a.dir,'resume.dat');await writeFile(location,data);a.native.clip={files:[location]};const offer=await a.snapshotClipboard(false),original=a.fileBytes.bind(a),opens=[],hostOpen=a.bulk.open.bind(a.bulk);let failed=false;
  a.bulk.open=(peer,args)=>{opens.push(args.offset);return hostOpen(peer,args);};
  a.fileBytes=async args=>{if(!failed&&args.offset>=192*1024){failed=true;throw Error('network break fixture');}return original(args);};
  await b.materialize(offer);assert.equal(opens.length,2);assert.equal(opens[0],0);assert.ok(opens[1]>=192*1024);assert.deepEqual(await readFile(b.native.clip.files[0]),data);
});

test('two-file parallel batches keep original clipboard order and bound file sessions',async t=>{
  const [a,b]=await pair(t),files=[],contents=[];for(let i=0;i<3;i++){const dir=path.join(a.dir,'folder'+i);await mkdir(dir);const file=path.join(dir,'same-name.bin'),data=Buffer.alloc(700000,i+1);await writeFile(file,data);files.push(file);contents.push(data);}a.native.clip={files};const offer=await a.snapshotClipboard(false),open=a.bulk.open.bind(a.bulk);let maximum=0;a.bulk.open=async(peer,args)=>{const result=await open(peer,args);maximum=Math.max(maximum,a.bulk.sessions.size);return result;};
  await b.materialize(offer);assert.equal(maximum,2);for(let i=0;i<3;i++)assert.deepEqual(await readFile(b.native.clip.files[i]),contents[i]);
});

test('binary file sender keeps the final payload open until the receiver acknowledges consumption',async t=>{const [a,b]=await pair(t),data=randomBytes(510001),location=path.join(a.dir,'tail.bin');await writeFile(location,data);const [offer]=await a.staging.prepare([location],b.config.id),file=offer.files[0];const info=await b.getPeer(a.config.id).request('bulkOpen',{offerId:offer.id,fileId:file.id,offset:0,end:data.length,completeAck:true});const connection=await connectBulk('127.0.0.1',info);try{const chunks=[await connection.next()];await pause(90);assert.equal(a.bulk.sessions.size,1,'sender must retain the final payload until consumed');let count=chunks[0].length;while(count<data.length){const chunk=await connection.next();chunks.push(chunk);count+=chunk.length;}assert.deepEqual(Buffer.concat(chunks),data);connection.complete(data.length);await until(()=>a.bulk.sessions.size===0);}finally{connection.close();}});
