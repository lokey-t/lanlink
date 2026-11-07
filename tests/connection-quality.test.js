import test from 'node:test';
import assert from 'node:assert/strict';
import {summarizeQuality,analyzeConnection,ConnectionQuality,qualityTransfer} from '../src/connection-quality.js';
test('quality grades include missing and unstable replies without claiming packet loss',()=>{
 assert.equal(summarizeQuality([]).grade,'无法连接');
 assert.equal(summarizeQuality(Array(10).fill(8)).grade,'良好');
 assert.equal(summarizeQuality([10,110,10,110,10,110,10,110,10,110]).grade,'较差');
 assert.equal(summarizeQuality(Array(8).fill(8)).timeoutPercent,20);
 assert.equal(summarizeQuality([5]).jitterMs,null);
});
test('probe uses encrypted TCP path and prevents overlapping runs',async()=>{
 let count=0;const peer={lanRequest:async(method,args,timeout)=>{assert.equal(method,'linkPing');assert.equal(timeout,1000);count++;},request:()=>{throw Error('wrong route');}};
 const pending=analyzeConnection(peer);
 await assert.rejects(analyzeConnection(peer),/正在分析/);
 assert.equal((await pending).received,10);assert.equal(count,10);
 await assert.rejects(analyzeConnection(null),/未连接/);
});

async function finished(quality) {
 for (let i=0;i<200;i++) {
  const session=quality.state().session;
  if (session?.status!=='running') return session;
  await new Promise(resolve=>setTimeout(resolve,15));
 }
 throw Error('Test run did not finish');
}

test('group diagnosis measures authenticated USB separately and exports only the latest run',async()=>{
 const id='phone-private-id', serial='private-usb-serial', other='offline-private-id';
 let closed=0;
 const request=async(method,args)=>method==='qualityTransfer'?qualityTransfer({},args):{};
 const peer={remote:{id},info:{name:'Phone',capabilities:{qualityProbe:1,qualityBuild:'0.91.0+test',dualLink:true}},request};
 const peers=new Map([[id,peer]]);
 const service={
  config:{id:'self',name:'Test computer',adbBindings:{[id]:{serial}}},
  group:{data:{members:{[id]:{name:'Phone',platform:'android'},[other]:{name:'Offline',platform:'win32'}}},excluded:()=>false},
  currentPeer:p=>peers.get(p.remote.id)===p&&!p.closed, peers,
  adb:{locate:async()=>'/adb',command:async()=>`${serial}\tdevice model:Test\n`,validBinding:()=>true},
  links:{links:[],async openVerifiedUsb(target,transport){
   assert.equal(target,peer);assert.equal(transport,serial);
   return {peer:{request},close:async()=>{closed++;}};
  }}
 };
 const quality=new ConnectionQuality(service);
 quality.start();
 assert.throws(()=>quality.start(id),/正在测试/);
 const group=await finished(quality);
 assert.equal(group.status,'completed');
 assert.equal(group.devices[0].usb,'connected');
 assert.equal(group.devices[0].routes[0].mouse.received,8);
 const wired=group.devices[0].routes.find(r=>r.kind==='usb');
 assert.equal(wired.latency.received,8);
 assert.equal(wired.uploadSummary.bytes,6*65536);
 assert.equal(wired.downloadSummary.bytes,6*65536);
 assert.equal(group.devices[1].status,'offline');
 assert.equal(closed,1);
 const report=quality.export().text;
 assert.match(report,/ADB USB tunnel/);
 assert.match(report,/"savedBinding": true/);
 assert.doesNotMatch(report,/phone-private-id|offline-private-id|private-usb-serial/);
 quality.start(other);
 const single=await finished(quality);
 assert.equal(single.scope,'device');
 assert.deepEqual(single.devices.map(d=>d.status),['offline']);
 assert.doesNotMatch(quality.export().text,/Phone/);
});

test('probe traffic validates payload sizes and limits a peer burst',()=>{
 const peer={};
 assert.throws(()=>qualityTransfer(peer,{direction:'upload',data:'AA=='}),/QUALITY_INVALID_PAYLOAD/);
 assert.equal(Buffer.from(qualityTransfer(peer,{direction:'download'}).data,'base64').length,65536);
 for(let i=0;i<22;i++) qualityTransfer(peer,{direction:'download'});
 assert.throws(()=>qualityTransfer(peer,{direction:'download'}),/QUALITY_RATE_LIMIT/);
});

test('cancelling preserves the completed sample and stops additional requests',async()=>{
 const id='online';
 let resolve, requests=0;
 const peer={remote:{id},info:{capabilities:{}},request(){
  requests++;
  return new Promise(done=>{resolve=done;});
 }};
 const peers=new Map([[id,peer]]);
 const quality=new ConnectionQuality({
  config:{id:'self',name:'Computer'},peers,currentPeer:()=>true,links:{links:[]},
  group:{data:{members:{[id]:{name:'Peer',platform:'win32'}}},excluded:()=>false}
 });
 quality.start(id);
 assert.equal(quality.stop().session.cancelling,true);
 resolve({});
 const result=await finished(quality);
 assert.equal(result.status,'cancelled');
 assert.equal(result.devices[0].routes[0].latency.received,1);
 assert.equal(requests,1);
 assert.match(quality.export().text,/cancelled/);
});
