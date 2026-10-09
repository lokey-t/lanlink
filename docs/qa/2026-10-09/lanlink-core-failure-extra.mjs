import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {mkdtemp,writeFile,readFile,rm} from 'node:fs/promises';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {Service} from '/Users/alice/Documents/项目/LLM/project/lanlink/src/service.js';
import {newToken,loadConfig,saveConfig} from '/Users/alice/Documents/项目/LLM/project/lanlink/src/identity.js';
import {Access} from '/Users/alice/Documents/项目/LLM/project/lanlink/src/access.js';
class Native extends EventEmitter {capabilities={clipboard:true};async call(){return {}}close(){}}
const dir=await mkdtemp('/tmp/lanlink-failure-extra-');
const config={id:randomUUID(),name:'isolated sender',token:newToken(),permissions:{clipboard:true},edges:{}};
const s=new Service(config,dir,new Native(),{noDiscovery:true}),target=randomUUID();let remoteRequests=0;
s.peers.set(target,{remote:{id:target},info:{capabilities:{staging:1}},closed:false,request:async()=>{remoteRequests++;throw Error('CONSENT_DENIED: 目标未允许共享剪贴板文件');}});
try{
 const files=[];for(let i=0;i<100;i++){const file=path.join(dir,`file-${i}.txt`);await writeFile(file,'payload');files.push(file);}
 for(let i=0;i<2;i++)await assert.rejects(s.staging.share(target,files),/CONSENT_DENIED/);
 assert.equal(s.staging.exports.size,200);assert.equal(remoteRequests,2);
 let thirdError;try{await s.staging.share(target,[files[0]]);}catch(e){thirdError=e.message;}
 assert.match(thirdError,/发送记录已满/);await saveConfig(dir,s.config);
 const restarted=new Service(await loadConfig(dir),dir,new Native(),{noDiscovery:true});await restarted.staging.load();assert.equal(restarted.staging.exports.size,200);await restarted.staging.close();
 const result={id:'two-denied-100-file-attempts-lock-sender',confirmed:true,attempts:2,selectedPerAttempt:100,remoteRequests,outgoingAfterFailure:s.staging.exports.size,outgoingAfterReload:restarted.staging.exports.size,thirdError,remainingOriginalFiles:files.length};console.log(JSON.stringify(result));
 // The status API should enforce the advertised request deadline even if the peer went offline.
 const service=new EventEmitter();service.peers=new Map();const access=new Access(service);access.notice={id:'offline-peer',requestId:'expired',keys:['input'],expires:Date.now()-1};
 const status=await access.status('expired');assert.equal(status.ended,undefined);console.log(JSON.stringify({id:'offline-access-never-times-out',confirmed:true,expiresInPast:true,status}));
}finally{s.peers.clear();await s.staging.close();await rm(dir,{recursive:true,force:true});}
