// Isolated emulator only: real Android virtual pointer while another identity owns USB transport.
import assert from 'node:assert/strict';
import net from 'node:net';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {EventEmitter} from 'node:events';
import {mkdtemp,rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {loadConfig,newToken,parseToken} from '../src/identity.js';
import {Service} from '../src/service.js';
import {Peer} from '../src/wire.js';
import {findAdb} from '../src/adb.js';
import {createActions} from '../src/actions.js';
import {randomUUID} from 'node:crypto';

const serial=process.env.LANLINK_SERIAL;
assert.match(serial||'',/^emulator-\d+$/,'explicit disposable emulator serial required');
const exec=promisify(execFile),adb=await findAdb(),command=args=>exec(adb,['-s',serial,...args],{timeout:150000,maxBuffer:1024*1024});
assert.match((await command(['shell','getprop','ro.hardware'])).stdout,/ranchu|goldfish/);
class LocalNative extends EventEmitter {capabilities={input:false,screen:false,host:false};async start(){}async call(){return {};}close(){}}
const computers=[],directories=[],token=newToken(),pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
let instrument,peers,heartbeat,renewal;
try {
  for(const name of ['Windows USB fixture','Mac LAN fixture']) {
    const dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-usb-owner-'));directories.push(dir);
    const config=await loadConfig(dir);config.token=token;config.name=name;
    const service=new Service(config,dir,new LocalNative(),{host:'0.0.0.0',port:0,noDiscovery:true,noQuic:true,noRtc:true});
    service.links.tick=async()=>{};computers.push(service);await service.start();
  }
  const [windows,mac]=computers;
  mac.connect('127.0.0.1',windows.port);
  const meshUntil=Date.now()+10000;while(!mac.peers.has(windows.config.id)||!windows.peers.has(mac.config.id)){assert.ok(Date.now()<meshUntil);await pause(50);}
  instrument=command(['shell','am','instrument','-w','-e','multiComputerPorts',computers.map(s=>s.port).join(','),'-e','multiComputerToken',token,'com.lanlink.app.test/com.lanlink.app.PermissionFlowTest']).then(r=>r.stdout);
  const until=Date.now()+20000;
  while(computers.some(s=>![...s.peers.values()].some(p=>p.info?.platform==='android'))){assert.ok(Date.now()<until);await pause(100);}
  peers=computers.map(s=>[...s.peers.values()].find(p=>p.info?.platform==='android'));
  const [usb,lan]=peers,id=usb.remote.id;assert.equal(lan.remote.id,id);
  const actions=createActions(mac);
  await actions('settings',{masterId:mac.config.id});
  await actions('layout-place',{id:mac.config.id,x:0,y:0});
  await actions('layout-place',{id:windows.config.id,x:1,y:0});
  await actions('layout-place',{id,x:2,y:0});
  const waitGraph=async(expected)=>{const until=Date.now()+10000;for(;;){const state=await lan.request('probe-counts');if(state.masterSelection?.id===mac.config.id&&JSON.stringify(state.layout.positions)===JSON.stringify(expected)&&JSON.stringify(windows.config.layout?.positions)===JSON.stringify(expected))return;assert.ok(Date.now()<until,'Mac, Windows and Android graph converges');await pause(100);}};
  await waitGraph(mac.config.layout.positions);
  await lan.request('probe-layout-place',{id,x:0,y:1});
  const edited={...mac.config.layout.positions,[id]:{x:0,y:1}};
  await waitGraph(edited);
  assert.deepEqual(mac.config.layout.positions,edited);
  console.log('PASS: real encrypted three-device mesh shares a multilevel graph; Android placement updates both computers without moving other devices.');
  await windows.adb.authorize({id,serial});
  mac.adb.locate=async()=>{throw Error('Mac fixture may not access USB');};
  assert.equal((await mac.adb.authorize({id})).mode,'adb/root');
  const offer=await usb.request('linkOpen'),tunnel=await windows.links.forward(serial,offer.port);
  const socket=net.connect({host:'127.0.0.1',port:tunnel.port});socket.on('error',()=>{});
  const aux=new Peer(socket,windows.identity(),parseToken(offer.secret));
  const link=windows.links.bind(usb,aux,{serial,tunnel,kind:'usb'});
  await new Promise((resolve,reject)=>{aux.once('ready',resolve);aux.once('closed',()=>reject(Error('USB auxiliary closed')));});
  assert.equal(aux.remote.id,id);await aux.request('linkUse');windows.links.activate(usb,link);
  await assert.rejects(usb.request('prepare',{pointer:true}),/MASTER_REQUIRED|CONTROL_CONFLICT/);
  const session=randomUUID(),ended=[];lan.on('message',message=>{if(message.type==='control-ended')ended.push(message);});
  await lan.request('acquire',{newSession:true,pointer:true,edgeSession:session});
  heartbeat=setInterval(()=>{renewal=lan.request('keepControl').catch(e=>{renewal=e;});},750);
  const initial=await lan.request('probe-counts');assert.equal(initial.mice.length,1,'actual Android virtual mouse was created');
  const mouse=initial.mice[0];
  const inputPath=(await command(['shell','getevent','-lp'])).stdout.match(/add device \d+: (\/dev\/input\/event\d+)\s+name:\s+"LanLink Virtual Mouse"/)?.[1];
  assert.ok(inputPath,'kernel virtual mouse device exists');
  const recording=command(['shell','timeout','12','getevent','-lt',inputPath]).catch(error=>({stdout:error.stdout||''}));
  await pause(200);
  await lan.request('input',{kind:'move',x:.5,y:.5});
  await lan.request('linkOpen'); // Intentionally unused: the Android offer expires after six seconds.
  for(let i=0;i<38;i++) {
    await lan.request('input',{kind:'move',x:.5+(i%2)*.06,y:.5,dx:i%2?12:-12,dy:0});
    const count=await lan.request('probe-counts');assert.deepEqual(count.mice,[mouse],'unused Mac offer must not remove/recreate its active pointer');
    assert.equal(usb.fastLink,link,'Windows USB transport remains independently active');
    await pause(200);
  }
  await windows.links.drop(link);
  for(let i=0;i<4;i++){await lan.request('input',{kind:'move',x:.5+(i%2)*.06,y:.5,dx:i%2?12:-12,dy:0});await pause(100);}
  const final=await lan.request('probe-counts');assert.deepEqual(final.mice,[mouse]);
  const nativeEvents=(await recording).stdout.match(/EV_REL\s+REL_X/g)||[];
  assert.ok(nativeEvents.length>=40,'actual kernel mouse received movement throughout the route changes');
  await lan.request('keepControl');clearInterval(heartbeat);await renewal;
  console.log(`PASS: actual Android virtual mouse ${mouse} delivered ${nativeEvents.length} kernel REL_X events; Windows USB remained active; unused Mac offer expiry and Windows USB close did not replace/release Mac pointer. App-local hover events: ${final.motion-initial.motion}.`);
  await lan.request('probe-master',{id:windows.config.id});
  const stopped=Date.now()+5000;while(!ended.some(message=>message.session===session)){assert.ok(Date.now()<stopped,'master switch ends only the prior edge session');await pause(50);}
  await assert.rejects(lan.request('acquire',{newSession:true,pointer:true}),/MASTER_REQUIRED|CONTROL_CONFLICT/);
  await lan.request('acquire',{newSession:true,pointer:false});await lan.request('release');
  console.log('PASS: Android master switch releases prior pointer with its session tag, denies the old master’s pointer, and permits manual non-pointer control.');
  await lan.request('probe-done');const report=await instrument;assert.match(report,/PASS:/);assert.doesNotMatch(report,/FAIL:|INSTRUMENTATION_FAILED/);console.log(report.trim());
} finally {
  clearInterval(heartbeat);if(renewal instanceof Promise)await renewal.catch(()=>{});
  if(peers?.[0]&&!peers[0].closed)await peers[0].request('probe-done').catch(()=>{});
  await Promise.all(computers.map(s=>s.close()));if(instrument)await instrument.catch(()=>{});
  await Promise.all(directories.map(dir=>rm(dir,{recursive:true,force:true})));
}
