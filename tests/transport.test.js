import test from 'node:test';
import assert from 'node:assert/strict';
import {randomBytes} from 'node:crypto';
import {RtcVideo,RtcAssembler,RtcFrameQueue,splitRtcFrame} from '../src/rtc-video.js';
import {packBulk,unpackBulk} from '../src/bulk.js';
import {InputQueue} from '../src/input-queue.js';
import {FrameRate} from '../src/frame-rate.js';
test('frame rate follows recent delivered frames and falls to zero after a stall',()=>{
  let now=0;const rate=new FrameRate(()=>now);
  for(let i=0;i<3000;i++){now+=1000/30;rate.tick();}
  assert.ok(rate.read()>=29&&rate.read()<=31);now+=1001;assert.equal(rate.read(),0);
  for(let i=0;i<3;i++){now+=300;rate.tick();}assert.equal(rate.read(),3);
});

const frame={codec:'h264',key:true,width:1080,height:1920,time:100,data:Buffer.alloc(28000,4)};
test('RTC preserves reference frames across brief consumer stalls and bounds prolonged backlog',()=>{
  let now=0;const assembler=new RtcAssembler(),queue=new RtcFrameQueue(assembler,()=>now);assembler.needKey=false;
  queue.push(frame);queue.push({...frame,key:false,time:101});queue.push({...frame,key:false,time:102});queue.push({...frame,codec:'ping',data:Buffer.alloc(0)});
  assert.deepEqual([queue.shift().time,queue.shift().time,queue.shift().time],[100,101,102]);assert.equal(assembler.needKey,false);
  queue.push(frame);now=151;queue.push({...frame,key:false});assert.equal(queue.shift(),null);assert.equal(assembler.needKey,true);
  queue.push(frame);assert.deepEqual(queue.shift(),frame);
  for(let i=0;i<9;i++)queue.push({...frame,key:false});assert.equal(queue.frames.length,0);assert.equal(queue.bytes,0);
});
test('RTC fragments reassemble out of order, dedupe, expire gaps and recover only at a keyframe',()=>{
  let time=0;const assembler=new RtcAssembler(()=>time),parts=splitRtcFrame(frame,0);
  assert.equal(assembler.push(parts[1]),null);assert.equal(assembler.push(parts[1]),null);assert.equal(assembler.push(parts[0]),null);assert.deepEqual(assembler.push(parts[2]),frame);
  const missing=splitRtcFrame({...frame,key:false},1);assembler.push(missing[0]);time=201;
  const skipped=splitRtcFrame({...frame,key:false},2);let result;for(const packet of skipped)result=assembler.push(packet);assert.equal(result,null);assert.equal(assembler.needKey,true);
  for(const packet of splitRtcFrame(frame,3))result=assembler.push(packet);assert.deepEqual(result,frame);assert.equal(assembler.needKey,false);
  const bad=Buffer.from(parts[0]);bad.writeUInt32BE(0xffffffff,8);assert.throws(()=>assembler.push(bad));
});
test('real LAN WebRTC exchanges existing encoded frame bytes without external ICE servers', {timeout:15000},async t=>{
  const a=new RtcVideo(),b=new RtcVideo();t.after(()=>{a.close();b.close();});
  const offer=await a.offer(),answer=await b.answer(offer);await a.accept(answer);await Promise.all([a.ready(),b.ready()]);
  assert.equal(a.pc.getConfiguration().iceServers.length,0);
  assert.ok(a.pc.iceTransports.every(transport=>transport.connection.stunServer===undefined));assert.ok(b.pc.iceTransports.every(transport=>transport.connection.stunServer===undefined));
  assert.equal(a.send(frame),true);assert.deepEqual(await b.next(),frame);
  const jpeg={...frame,codec:'jpeg',data:Buffer.from('return'),key:true};assert.equal(b.send(jpeg),true);assert.deepEqual(await a.next(),jpeg);
});
test('binary file records preserve bytes and reject tampering and wrong sequence',()=>{
  const key=randomBytes(32),data=randomBytes(192*1024),packet=packBulk(key,0,data);assert.deepEqual(unpackBulk(key,0,packet.subarray(4)),data);
  assert.equal(packet.length,data.length+52);assert.throws(()=>unpackBulk(key,1,packet.subarray(4)));packet[60]^=1;assert.throws(()=>unpackBulk(key,0,packet.subarray(4)));
});
test('input scheduling merges relative distance without crossing key and click barriers',async()=>{
  const calls=[];let release;const gate=new Promise(r=>release=r);const queue=new InputQueue(async(method,args)=>{calls.push(args);if(calls.length===1)await gate;return {};});
  const pending=[queue.request('input',{kind:'key',code:'KeyA',down:true}),queue.request('input',{kind:'relative',dx:3,dy:4}),queue.request('input',{kind:'relative',dx:5,dy:6}),queue.request('input',{kind:'button',button:0,down:true}),queue.request('input',{kind:'relative',dx:7,dy:8})];release();await Promise.all(pending);
  assert.equal(calls.length,4);assert.deepEqual(calls[1],{kind:'relative',dx:8,dy:10});assert.equal(calls[2].kind,'button');assert.equal(calls[3].dx,7);
});
