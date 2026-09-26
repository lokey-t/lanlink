import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp,rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';
import { loadConfig } from '../src/identity.js';
import { Service } from '../src/service.js';
import { NativeBridge } from '../src/native.js';
import { findAdb } from '../src/adb.js';
const exec=promisify(execFile),serial='emulator-5580',adb=await findAdb();
const hardware=(await exec(adb,['-s',serial,'shell','getprop','ro.hardware'],{windowsHide:true})).stdout.trim();
if(!/ranchu|goldfish/.test(hardware))throw Error('Disposable emulator required');
const dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-touchpad-')),config=await loadConfig(dir);
config.permissions={input:true,screen:false,clipboard:false,shutdown:false};
const native=new NativeBridge(),service=new Service(config,dir,native,{port:0,host:'0.0.0.0',noDiscovery:true});
let before, instrument,peer;
try {
  await service.start();before=await native.call('cursor');
  instrument=exec(adb,['-s',serial,'shell','am','instrument','-w','-e','touchpadPort',String(service.port),'-e','touchpadToken',config.token,'com.lanlink.app.test/com.lanlink.app.PermissionFlowTest'],{windowsHide:true,timeout:65000}).then(r=>r.stdout,e=>String(e));
  const deadline=Date.now()+15000;
  while(!(peer=[...service.peers.values()].find(p=>p.info?.platform==='android'))) {if(Date.now()>deadline)throw Error('Phone fixture missing');await new Promise(r=>setTimeout(r,100));}
  service.config.deviceGrants[peer.remote.id]={input:true,screen:false,clipboard:false,shutdown:false};
  const left=before.x>before.width/2;
  assert.equal((await peer.request('testMove',{left},15000)).moved,true);
  const after=await native.call('cursor');
  assert.ok(left ? after.x<before.x-5 : after.x>before.x+5,'Android touchpad moved actual Windows cursor');
  await peer.request('testDone');
  const report=await instrument;assert.match(report,/PASS: touchpad/);assert.doesNotMatch(report,/FAIL|crashed/);
  console.log('PASS: real Android touchpad -> encrypted group connection -> Windows cursor moved; no click/key generated.');
} finally {
  if(before)await native.call('warp',{x:before.x/(before.width-1),y:before.y/(before.height-1)}).catch(()=>{});
  if(peer&&!peer.closed)await peer.request('testDone').catch(()=>{});
  await service.close();if(instrument)await instrument;await rm(dir,{recursive:true,force:true});
}
