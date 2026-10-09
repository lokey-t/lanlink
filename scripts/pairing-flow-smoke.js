// Disposable emulator; ephemeral desktop identity, no real keyboard/clipboard/screen access.
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { EventEmitter } from 'node:events';
import { mkdtemp, rm } from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { loadConfig } from '../src/identity.js';
import { Service } from '../src/service.js';
import { findAdb } from '../src/adb.js';
const exec=promisify(execFile),adb=await findAdb(),serial=process.env.LANLINK_SERIAL||'emulator-5580';
if(!adb||!/ranchu|goldfish/.test((await exec(adb,['-s',serial,'shell','getprop','ro.hardware'],{windowsHide:true})).stdout))throw Error('Disposable emulator required');
class FixtureNative extends EventEmitter{capabilities={host:false,input:false,clipboard:false,screen:false};async start(){}async call(){return {text:'',files:[]};}close(){}}
const dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-pair-flow-')),config=await loadConfig(dir);
config.name='配对测试电脑';config.permissions={input:false,clipboard:false,screen:false,shutdown:false};
const service=new Service(config,dir,new FixtureNative(),{host:'0.0.0.0',port:0,noDiscovery:true});let timer;
try{
 await service.start();const invite=service.pairing.show();timer=setInterval(()=>service.pairing.show(),3000);
 const report=await exec(adb,['-s',serial,'shell','am','instrument','-w','-e','pairFlowPort',String(service.port),'-e','pairFlowInvitePort',String(service.pairing.port),'-e','pairFlowCode',invite.code,'-e','pairFlowId',invite.id,'-e','pairFlowPeer',config.id,'com.lanlink.app.test/com.lanlink.app.PermissionFlowTest'],{windowsHide:true,timeout:40000,maxBuffer:1024*1024});
 if(!report.stdout.includes('PASS: scanned invitation')||/FAIL|Process crashed|INSTRUMENTATION_FAILED/.test(report.stdout))throw Error(report.stdout);
 console.log(report.stdout.trim());
}finally{clearInterval(timer);await service.close();await rm(dir,{recursive:true,force:true});}
