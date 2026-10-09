// Integration fixture: refuses physical phones and never modifies the user's LanLink group.
import {execFile} from 'node:child_process';import {promisify} from 'node:util';
import {EventEmitter} from 'node:events';import {mkdtemp,rm,mkdir,writeFile,readFile} from 'node:fs/promises';
import {randomUUID,createHash} from 'node:crypto';import os from 'node:os';import path from 'node:path';import assert from 'node:assert/strict';
import {Service} from '../src/service.js';import {loadConfig} from '../src/identity.js';import {findAdb} from '../src/adb.js';
const exec=promisify(execFile),adb=await findAdb(),serial=process.env.LANLINK_SERIAL||'emulator-5580';
const run=(args,timeout=15000)=>exec(adb,['-s',serial,...args],{windowsHide:true,timeout,maxBuffer:2*1024*1024});
assert.match((await run(['shell','getprop','ro.hardware'])).stdout,/ranchu|goldfish/);
const sleep=ms=>new Promise(r=>setTimeout(r,ms));async function until(fn){const deadline=Date.now()+20000;while(!await fn()){if(Date.now()>deadline)throw Error('staging integration timed out');await sleep(100);}}
const hash=b=>createHash('sha256').update(b).digest('hex');
class Native extends EventEmitter{capabilities={clipboard:true,input:false,screen:false,host:false};writes=0;async start(){}async call(m){if(m==='clipboardGet')return {text:'untouched clipboard',files:[]};if(m==='clipboardSet')this.writes++;return {};}close(){}}
const dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-stage-smoke-')),config=await loadConfig(dir);config.name='文件分享测试电脑';config.id='ffffffff-ffff-4fff-bfff-ffffffffffff';config.permissions={clipboard:true,input:false,screen:false};config.clipboardUpload=false;config.clipboardDownload=false;
const native=new Native(),service=new Service(config,dir,native,{host:'0.0.0.0',port:0,noDiscovery:true,noQuic:process.argv.includes('--tcp')});let instrumentation,peer,finished=false;const saveName='LanLinkStagingProbe-'+randomUUID();
try{
  await run(["shell","mkdir","-p",'/sdcard/Download/'+saveName]);await service.start();let quicReads=0;const rpc=service.quic.rpc.bind(service.quic);service.quic.rpc=(p,m,a,...rest)=>{if(m==="quicFile")quicReads++;return rpc(p,m,a,...rest);};instrumentation=run(['shell','am','instrument','-w','-e','stagingSmokePort',String(service.port),'-e','stagingSmokeToken',config.token,'com.lanlink.app.test/com.lanlink.app.PermissionFlowTest'],210000).then(r=>r.stdout);instrumentation.catch(()=>{});
  await until(()=>{peer=[...service.peers.values()].find(p=>p.info?.platform==='android');return peer;});if(!process.argv.includes('--tcp'))await until(()=>peer.quicConn);
  const videoEndpoint=service.links.videoEndpoint.bind(service.links);service.links.videoEndpoint=async(p,info)=>{if(p!==peer)return videoEndpoint(p,info);const port=Number((await run(['forward','tcp:0','tcp:'+info.port])).stdout.trim());return {host:'127.0.0.1',info:{...info,port},close:()=>run(['forward','--remove','tcp:'+port]).catch(()=>{})};};
  const step=(step,args={})=>(peer.tcpRequest||peer.lanRequest||peer.request.bind(peer))('testStaging',{step,...args},25000),rows=async()=>(await step('status')).files;
  await step('ui');
  const file=path.join(dir,'电脑发送.txt'),bytes=Buffer.alloc(510001,72);await writeFile(file,bytes);
  const folder=path.join(dir,'资料文件夹');await mkdir(path.join(folder,'空文件夹'),{recursive:true});await mkdir(path.join(folder,'子目录'));await writeFile(path.join(folder,'子目录','内容.txt'),'电脑 → 手机文件夹');
  await service.staging.share(peer.remote.id,[file,folder]);await until(async()=>{const r=await rows();return r.length===2&&r.every(f=>f.state==='ready');});let received=await rows();assert.equal(received.find(r=>r.name==='电脑发送.txt').files[0].sha256,hash(bytes));assert.equal(received.find(r=>r.type==='folder').files[0].sha256,hash(Buffer.from('电脑 → 手机文件夹')));
  const status=await step('status');assert.equal(status.owner,null);assert.equal(service.active,null);await step('ui');
  const single=received.find(r=>r.type==='file');await step('delete-protection',{id:single.id});await step('save-failure',{id:single.id});assert.equal((await rows()).length,2);const saved=await step('save',{id:single.id});assert.equal(saved.sha256,hash(bytes));assert.equal((await rows()).length,1);assert.deepEqual(await readFile(file),bytes);
  console.log('PASS: desktop → Android file/folder reliable transfer, provider read, failed-save retention, successful-save cleanup without input control.');
  const pending=path.join(dir,'待传输.txt');await writeFile(pending,'metadata only');let reads=0;const original=service.fileBytes.bind(service);service.fileBytes=async(...a)=>{reads++;return original(...a);};let session=randomUUID();await service.staging.share(peer.remote.id,[pending],false,session);assert.equal(reads,0);assert.ok((await rows()).some(r=>r.state==='pending'));const reloaded=await step('reload');assert.equal(reloaded.files.filter(r=>r.state==='pending').length,0);await rm(pending);await step('poll');await until(async()=>!(await rows()).some(r=>r.state==='pending'));
  await writeFile(pending,'mouse session');session=randomUUID();await service.staging.share(peer.remote.id,[pending],false,session);service.staging.end(session);await until(async()=>!(await rows()).some(r=>r.state==='pending'));
  await service.staging.share(peer.remote.id,[pending],false,randomUUID());const entry=(await rows()).find(r=>r.state==='pending');await step('start',{id:entry.id});await until(async()=>(await rows()).find(r=>r.id===entry.id)?.state==='ready');
  console.log('PASS: pending drag metadata, deleted source removal, restart cleanup, mouse session retract, receiver-initiated transfer.');
  const batch=[];for(let i=0;i<8;i++){const f=path.join(dir,`批量${i}.bin`);await writeFile(f,Buffer.alloc(240001+i,i+1));batch.push(f);}await service.staging.share(peer.remote.id,batch);await until(async()=>{const list=(await rows()).filter(r=>r.name.startsWith('批量'));return list.length===8&&list.every(r=>r.state==='ready');});for(const r of (await rows()).filter(r=>r.name.startsWith('批量'))){const i=Number(r.name.match(/[0-9]+/)[0]);assert.equal(r.files[0].sha256,hash(Buffer.alloc(240001+i,i+1)));await step('remove',{id:r.id});}console.log('PASS: eight-file queue preserves bytes and keeps the control RPC responsive.');
  const folderRow=(await rows()).find(r=>r.type==='folder');const savedFolder=await step('save-folder',{id:folderRow.id,folderName:saveName});assert.equal(savedFolder.sha256,hash(Buffer.from('电脑 → 手机文件夹')));
  // Hold the receiver queue so metadata acknowledgement cannot masquerade as completed delivery.
  const startDownload=service.staging.start.bind(service.staging);let hold=true;const held=new Set();
  service.staging.start=(id)=>{if(hold){held.add(id);return Promise.resolve({});}return startDownload(id);};
  let offered=0;const accept=service.staging.accept.bind(service.staging);service.staging.accept=(p,a)=>{offered++;return accept(p,a);};
  const originalHandle=service.handle.bind(service);service.handle=async(p,m,a)=>{if(m==='permission-status')await sleep(600);return originalHandle(p,m,a);};
  await step('share-cancel-ui');await sleep(1200);assert.equal(offered,0,'dismissing pending chooser must prevent new offers');
  await step('share-ui');await until(()=>service.staging.list().length===2);await sleep(900);
  assert.equal(offered,2,'rapid target taps create one two-file batch');
  let outgoing=(await step('status')).outgoing;assert.equal(outgoing.length,2);assert.ok(outgoing.every(r=>r.state==='queued'&&r.received===0),'metadata ack must remain waiting, never completed');
  await step('sending-ui');
  console.log('PASS: cancelled chooser emits no offer; rapid repeat taps produce one batch and slow receiver stays waiting.');
  // Sender cancellation reaches the receiver; retry reuses the existing offer and row.
  const cancelled=outgoing[0];await step('cancel-outgoing',{id:cancelled.id});
  await until(()=>service.staging.list().some(r=>r.state==='cancelled'));
  hold=false;await step('retry-outgoing',{id:cancelled.id});
  for(const id of held){const row=service.staging.rows.get(id);if(row?.state!=='ready')startDownload(id).catch(()=>{});}
  await until(()=>service.staging.list().length===2&&service.staging.list().every(r=>r.state==='ready'));
  await until(async()=>(await step('status')).outgoing.every(r=>r.state==='completed'));
  for(let i=0;i<2;i++){const row=service.staging.list().find(r=>r.name===`手机分享${i}.txt`);assert.ok(row);assert.equal(hash(await readFile((await service.staging.ready(row.id)).path)),hash(Buffer.alloc(400001+i,41+i)));}
  console.log('PASS: sender cancel/retry preserves offer identity; only receiver completion marks outgoing completed.');
  for(const r of service.staging.list())await service.staging.remove(r.id);await step('outgoing-clear');
  await step('prepare-failure');assert.equal((await step('status')).outgoing.length,0,'unreadable later URI rolls back the prepared batch');
  const accepted=service.staging.accept;service.staging.accept=async()=>{throw Error('测试接收拒绝');};
  await assert.rejects(step('share-idempotent'),/测试接收拒绝/);service.staging.accept=accepted;
  let rejected=(await step('status')).outgoing;assert.equal(rejected.length,2);assert.ok(rejected.every(r=>r.state==='failed'),'rejected offers are visible as failed');
  for(const row of rejected)await step('retry-outgoing',{id:row.id});await until(()=>service.staging.list().length===2&&service.staging.list().every(r=>r.state==='ready'));await until(async()=>(await step('status')).outgoing.every(r=>r.state==='completed'));
  for(const r of service.staging.list())await service.staging.remove(r.id);await step('outgoing-clear');assert.equal((await step('status')).outgoing.length,0);
  console.log('PASS: unreadable batch rolls back; rejected send remains visible, retries successfully and clears its records.');
  const beforeIdempotent=offered;await step('share-idempotent');await until(()=>service.staging.list().length===2&&service.staging.list().every(r=>r.state==='ready'));assert.equal(offered-beforeIdempotent,2,'same logical request id remains idempotent');
  await until(async()=>(await step('status')).outgoing.every(r=>r.state==='completed'));
  for(const r of service.staging.list())await service.staging.remove(r.id);await step('outgoing-clear');
  // Cancel a live receiver and verify its worker and incomplete file both stop, then retry.
  const large=path.join(dir,'取消后重试.bin');const largeBytes=Buffer.alloc(8*1024*1024,83);await writeFile(large,largeBytes);
  const readOriginal=service.fileBytes.bind(service);let slowReads=true;service.fileBytes=async(...args)=>{if(slowReads)await sleep(80);return readOriginal(...args);};
  await service.staging.share(peer.remote.id,[large]);let active;
  await until(async()=>{active=(await rows()).find(r=>r.name==='取消后重试.bin');return active?.state==='receiving'&&active.received>0;});
  await step('cancel',{id:active.id});await until(async()=>(await rows()).find(r=>r.id===active.id)?.state==='cancelled');await sleep(500);await step('partial-clean',{id:active.id});
  slowReads=false;await step('start',{id:active.id});await until(async()=>(await rows()).find(r=>r.id===active.id)?.state==='ready');assert.equal((await rows()).find(r=>r.id===active.id).files[0].sha256,hash(largeBytes));
  console.log('PASS: receiving cancel stops the worker, clears partial data and retries to matching bytes.');
  if(!process.argv.includes('--tcp'))assert.ok(quicReads>=6,'Android outbound staging must use native QUIC reliable streams');
  await step('finish');finished=true;const result=await instrumentation;assert.match(result,/PASS: Android\/Desktop/);console.log(result.trim());console.log('PASS: Android share-sheet chooser → desktop staging, both multiple files preserve hashes; '+(process.argv.includes('--tcp')?'encrypted TCP':'QUIC reliable streams')+'.');
}finally{if(!finished)console.error("File transfer diagnostics:",JSON.stringify({files:service.staging.list().map(({name,state,error})=>({name,state,error})),logs:service.logs.slice(-8).map(l=>l.message)}));if(!finished)await run(['shell','am','force-stop','com.lanlink.app']).catch(()=>{});await service.close();if(instrumentation)await instrumentation.catch(()=>{});await rm(dir,{recursive:true,force:true});}
