// Disposable emulator only: exercises real encrypted Node/Android clipboard traffic.
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {EventEmitter} from 'node:events';
import {mkdtemp,rm,readFile,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';
import {Service} from '../src/service.js';
import {loadConfig} from '../src/identity.js';
import {findAdb,AdbManager} from '../src/adb.js';
import {RtcVideo} from '../src/rtc-video.js';
const exec=promisify(execFile),adb=await findAdb(),serial=process.env.LANLINK_SERIAL||'emulator-5580';
if(!adb)throw Error('ADB missing');
const run=(args,timeout=15000)=>exec(adb,['-s',serial,...args],{windowsHide:true,timeout,maxBuffer:2*1024*1024});
assert.match((await run(['shell','getprop','ro.hardware'])).stdout,/ranchu|goldfish/);
class Native extends EventEmitter{
  capabilities={clipboard:true,input:false,host:false,screen:false};clip={text:'',files:[]};
  async start(){}async call(method,args){if(method==='clipboardGet')return this.clip;if(method==='clipboardSet'){this.clip={text:args.text||'',files:args.files||[]};queueMicrotask(()=>this.emit('event',{type:'clipboardChanged'}));}return {};}
  copy(text){this.clip={text,files:[]};this.emit('event',{type:'clipboardChanged'});}close(){}
}
async function until(fn){const end=Date.now()+15000;while(!await fn()){if(Date.now()>end)throw Error('clipboard smoke timed out');await new Promise(r=>setTimeout(r,100));}}
const dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-clip-smoke-')),config=await loadConfig(dir),native=new Native();
const quicTest=process.argv.includes('--quic');if(quicTest)config.id='ffffffff-ffff-4fff-bfff-ffffffffffff';
const service=new Service(config,dir,native,{host:'0.0.0.0',port:0,noDiscovery:true});let instrument,peer;
try{
  await service.start();
  instrument=run(['shell','am','instrument','-w','-e','clipboardSmokePort',String(service.port),'-e','clipboardSmokeToken',config.token,'com.lanlink.app.test/com.lanlink.app.PermissionFlowTest'],120000).then(r=>r.stdout);
  await until(()=>{peer=[...service.peers.values()].find(p=>p.info?.platform==='android');return peer;});
  if(quicTest){await until(()=>peer.quicConn);console.log('PASS: Android/Desktop native QUIC negotiated over authenticated primary membership.');}
  const step=(step,args={})=>(peer.tcpRequest||peer.lanRequest||peer.request.bind(peer))('testClipboard',{step,...args},20000);
  native.copy('电脑自动上传 → 手机');await until(async()=>{const r=await step('status');return r.latest?.text==='电脑自动上传 → 手机';});
  await step('copy',{text:'手机自动上传 → 电脑'});await until(()=>native.clip.text==='手机自动上传 → 电脑');const count=service.clipboardSync.list().length;
  await new Promise(r=>setTimeout(r,1500));assert.equal(service.clipboardSync.list().length,count,'system write must not echo');
  await step('mode',{upload:false,download:false});await step('copy',{text:'手机手动上传'});await new Promise(r=>setTimeout(r,300));assert.notEqual(native.clip.text,'手机手动上传');await step('manual-upload');await until(()=>native.clip.text==='手机手动上传');
  await step('ui');
  if(process.argv.includes('--transport')){
    for(const phoneOffers of [false,true]){
      const rtc=new RtcVideo();try{
        if(phoneOffers){const offer=await step('rtc-offer');const answer=await rtc.answer(offer.sdp);await step('rtc-accept',{sdp:answer});}else{const offer=await rtc.offer();const answer=await step('rtc-answer',{sdp:offer});await rtc.accept(answer.sdp);}
        await Promise.all([rtc.ready(),step('rtc-ready')]);
        const frame={codec:'h264',key:true,width:1080,height:1920,time:Date.now(),data:Buffer.alloc(36000,7)};
        const mobileRead=step('rtc-next');rtc.send(frame);const received=await mobileRead;assert.equal(received.data,frame.data.toString('base64'));assert.equal(received.time,frame.time);
        const desktopRead=rtc.next();await step('rtc-send',{...frame,data:frame.data.toString('base64')});assert.deepEqual(await desktopRead,frame);
        console.log('PASS: native Android/desktop WebRTC frame fragments, '+(phoneOffers?'phone':'desktop')+' offerer, 36 KB H.264 payload both directions.');
      }finally{rtc.close();await step('rtc-close');}
    }
    // The emulator's inbound TCP ports require an explicit ADB mapping.
    const videoEndpoint=service.links.videoEndpoint.bind(service.links);
    service.links.videoEndpoint=async(p,info)=>{if(p!==peer)return videoEndpoint(p,info);const port=Number((await run(['forward','tcp:0','tcp:'+info.port])).stdout.trim());return {host:'127.0.0.1',info:{...info,port},close:()=>run(['forward','--remove','tcp:'+port]).catch(()=>{})};};
    const source=await step('file-source');await service.materialize({...source.offer,source:peer.remote.id});assert.equal(createHash('sha256').update(await readFile(native.clip.files[0])).digest('hex'),source.sha256);
    await step('mode',{upload:false,download:false});const data=Buffer.alloc(850000,6),file=path.join(dir,'desktop-file.bin');await writeFile(file,data);native.clip={text:'',files:[file]};const offer=await service.snapshotClipboard(false);const result=await step('file-receive',{offer});assert.equal(result.length,data.length);assert.equal(result.sha256,createHash('sha256').update(data).digest('hex'));service.links.videoEndpoint=videoEndpoint;
    if(quicTest)assert.ok(peer.quicConn,'file transfer retained QUIC');
    console.log('PASS: Android/desktop '+(quicTest?'QUIC reliable streams':'AES-GCM binary file channels')+', 800 KB and 850 KB, SHA-256 and system file clipboard both directions.');
    await step('mode',{upload:true,download:true});
  }
  await new AdbManager(service).authorizeLocal(serial);await until(async()=> (await step('status')).helper);
  if(process.argv.includes('--transport')){
    await step('screen-grant');await service.openStream(peer.remote.id);assert.ok(service.videoViewer.sessions.get(peer.remote.id).rtc,'actual phone screen must use native stream transport');
    if(quicTest){
      await step('input-surface');await peer.request('acquire',{screen:true,pointer:false,newSession:true});
      await peer.request('input',{kind:'move',x:.35,y:.4});await peer.request('input',{kind:'button',button:0,down:true});await peer.request('input',{kind:'button',button:0,down:false});
      let input;await until(async()=>{input=await step('input-result');return input.action===1;});assert.ok(Math.abs(input.x-.35*(input.width-1))<3);assert.ok(Math.abs(input.y-.4*(input.height-1))<3);assert.ok(peer.quicConn&&!peer.fastLink,'mouse used native QUIC rather than USB');await peer.request('release',{});await step('input-restore');console.log('PASS: real Android ADB helper injected Datagram mouse position and reliable click at the expected screen coordinates.');
    }
    for(let i=0;i<10;i++){const frame=await service.videoViewer.next(peer.remote.id);assert.equal(frame.codec,'h264');assert.equal(frame.transport,quicTest?'quic':'webrtc');assert.ok(Buffer.from(frame.data,'base64').subarray(0,2).equals(Buffer.from([255,216])),'decoded native screen JPEG');assert.ok(frame.width>100&&frame.height>100);}
    await service.closeStream(peer.remote.id);console.log('PASS: actual emulator ADB screen H.264 -> native '+(quicTest?'QUIC Datagram':'WebRTC')+' -> desktop FFmpeg decoder, 10 rendered screen frames.');
  }
  await step('background');native.copy('后台助手写入');await until(async()=> (await step('status')).system?.text==='后台助手写入');
  await step('helper-copy',{text:'后台助手监听复制'});await until(()=>native.clip.text==='后台助手监听复制');
  const record=service.clipboardSync.list().find(r=>r.text==='手机手动上传');await step('copy-record',{id:record.id});assert.equal((await step('status')).system.text,'手机手动上传');
  await step('foreground');await step('finish');const output=await instrument;assert.match(output,/PASS: Android\/desktop/);console.log(output.trim());
  console.log('PASS: encrypted desktop/Android clipboard roundtrip, automatic toggles, manual actions, background ADB helper and history copy.');
}finally{
  if(peer && !peer.closed)await peer.request('testClipboard',{step:'finish'},1000).catch(()=>{});
  await service.close();await rm(dir,{recursive:true,force:true});
}
