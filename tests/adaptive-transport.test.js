import test from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {AdaptiveMotion, InputQueue} from '../src/input-queue.js';
import {AdaptiveVideo, VideoFeedback} from '../src/adaptive-video.js';
import {MotionSender, MotionReceiver} from '../src/quic-motion.js';
import {RtcFrameQueue} from '../src/rtc-video.js';
import {QuicVideo} from '../src/quic.js';
import {VideoHost} from '../src/video.js';

test('motion pacing reacts to initial high RTT, congestion and gradual recovery independently per peer', () => {
  let now=0;const slow=new AdaptiveMotion(()=>now),fast=new AdaptiveMotion(()=>now);
  slow.feedback({rttMs:200});assert.equal(slow.interval,50);assert.equal(fast.interval,8);
  slow.feedback({rttMs:NaN});assert.equal(slow.interval,50);
  for(let i=0;i<200;i++){now+=300;slow.feedback({rttMs:10});}assert.equal(slow.interval,8);
  now+=300;fast.feedback({queued:65536});assert.ok(fast.interval>=16);assert.equal(slow.interval,8);
});

test('input queue coalesces absolute/raw motion within a fresh window without crossing clicks', async () => {
  let now=0,release;const gate=new Promise(resolve=>release=resolve),sent=[];
  const queue=new InputQueue(async(method,args)=>{sent.push(args);if(sent.length===1)await gate;return {};},{now:()=>now});
  const jobs=[queue.request('input',{kind:'key',code:'KeyA',down:true})];
  jobs.push(queue.request('input',{kind:'move',x:.2,y:.2,dx:800,dy:20}));
  now=200;jobs.push(queue.request('input',{kind:'move',x:.5,y:.8,dx:40,dy:5}));
  jobs.push(queue.request('input',{kind:'button',button:0,down:true}));
  jobs.push(queue.request('input',{kind:'move',x:.7,y:.8,dx:10,dy:0}));
  release();await Promise.all(jobs);
  assert.deepEqual(sent.slice(1),[{kind:'move',x:.5,y:.8,dx:40,dy:5},{kind:'button',button:0,down:true},{kind:'move',x:.7,y:.8,dx:10,dy:0}]);
});

test('stalled input discards old raw displacement but retains the final absolute position', async () => {
  let now=0,release;const sent=[],queue=new InputQueue(async(method,args)=>{sent.push(args);if(sent.length===1)await new Promise(resolve=>release=resolve);return {};},{now:()=>now});
  const first=queue.request('input',{kind:'key',code:'KeyA',down:true}),second=queue.request('input',{kind:'move',x:.9,y:.8,dx:900,dy:-200});
  now=500;release();await Promise.all([first,second]);assert.deepEqual(sent[1],{kind:'move',x:.9,y:.8,dx:0,dy:0});
});

test('pacing coalesces bursts and lets a following button flush its preceding position immediately', async () => {
  const calls=[],queue=new InputQueue(async(method,args)=>{calls.push(args);return {};},{adaptive:true});
  queue.feedback({rttMs:200});await queue.request('input',{kind:'move',x:0,y:0});
  const pending=[];for(let i=1;i<=20;i++)pending.push(queue.request('input',{kind:'move',x:i/20,y:0}));
  assert.equal(calls.length,1);
  pending.push(queue.request('input',{kind:'button',button:0,down:true}));await Promise.all(pending);
  assert.deepEqual(calls,[{kind:'move',x:0,y:0},{kind:'move',x:1,y:0},{kind:'button',button:0,down:true}]);
});

test('reordered cumulative snapshots cannot replace a newer queued mouse position', async () => {
  let release;const sent=[],queue=new InputQueue(async(method,args)=>{sent.push(args);if(sent.length===1)await new Promise(r=>release=r);return {};});
  const blocker=queue.request('__control',{}),epoch=randomUUID();
  const latest=queue.request('input',{kind:'move',motion:{epoch,seq:3},conn:1});
  await queue.request('input',{kind:'move',motion:{epoch,seq:2},conn:1});release();await Promise.all([blocker,latest]);assert.equal(sent[1].motion.seq,3);
});

test('cumulative motion recovers brief loss but retains only live displacement after a long outage', () => {
  let now=1000;const sender=new MotionSender(()=>now),receiver=new MotionReceiver();
  receiver.accept(sender.move({kind:'relative',dx:10,dy:0}));
  now+=50;sender.move({kind:'relative',dx:40,dy:0});now+=40;
  assert.equal(receiver.accept(sender.move({kind:'relative',dx:20,dy:0}))[0].dx,60);
  for(let i=0;i<20;i++){now+=50;sender.move({kind:'relative',dx:10,dy:0});}
  assert.equal(receiver.accept(sender.snapshot).reduce((sum,m)=>sum+m.dx,0),30);
  now+=10;assert.equal(receiver.accept(sender.move({kind:'relative',dx:-5,dy:0}))[0].dx,-5);
});

test('a late click consumes the motion baseline without replaying an old raw trajectory', () => {
  let now=100;const sender=new MotionSender(()=>now),receiver=new MotionReceiver();
  receiver.accept(sender.move({kind:'move',x:.5,y:.5}));sender.move({kind:'move',x:1,y:1,dx:700,dy:300});
  now+=5000;assert.deepEqual(receiver.accept(sender.barrier()),[{kind:'move',x:1,y:1,dx:0,dy:0}]);
  assert.deepEqual(receiver.accept(sender.snapshot),[]);now+=10;
  assert.equal(receiver.accept(sender.move({kind:'move',x:1,y:1,dx:15,dy:0}))[0].dx,15);
});

test('video feedback reports delay peaks and stalls, while heartbeats cannot erase congestion or inflate recovery', () => {
  let now=10000;const feedback=new VideoFeedback(()=>now);
  feedback.receive({time:now-3600000,codec:'h264'});now+=100;feedback.receive({time:now-3600180,codec:'h264'});
  now+=100;feedback.receive({time:now-3600005,codec:'h264'});feedback.receive({time:now,codec:'ping'});
  const first=feedback.report();assert.equal(first.delayMs,180);assert.equal(first.frames,3);
  now+=2000;const stalled=feedback.report();assert.equal(stalled.stalledMs,2000);assert.equal(stalled.frames,0);
  const adaptive=new AdaptiveVideo(2000000,()=>now);assert.equal(adaptive.feedback(stalled),true);
  const reduced=adaptive.rate;for(let i=0;i<30;i++){now+=1000;feedback.receive({codec:'ping'});adaptive.feedback(feedback.report());}
  assert.equal(adaptive.rate,reduced);
});

test('video bitrate has an initial high RTT budget, bounded decline, and does not recover from stale reports', () => {
  let now=0;const adaptive=new AdaptiveVideo(4000000,()=>now);
  for(let i=0;i<20;i++){now+=1000;adaptive.feedback({delayMs:0,rttMs:350});}
  assert.ok(adaptive.rate>=1500000&&adaptive.rate<=2000000);
  for(let i=0;i<20;i++){now+=2100;adaptive.feedback({delayMs:200,rttMs:350});}assert.equal(adaptive.rate,500000);
  for(let i=0;i<30;i++){now+=1000;adaptive.feedback({delayMs:0,rttMs:10,frames:0,sampleAgeMs:5000});}assert.equal(adaptive.rate,500000);
  assert.equal(new AdaptiveVideo(Infinity).rate,4000000);assert.equal(new AdaptiveVideo(1).rate,500000);
});

test('a consumer that pauses without further incoming frames discards a stale H264 reference chain', () => {
  let now=0;const assembler={needKey:false},queue=new RtcFrameQueue(assembler,()=>now),frame={codec:'h264',key:true,data:Buffer.alloc(10)};
  queue.push(frame);now=200;assert.equal(queue.shift(),null);assert.equal(assembler.needKey,true);
  queue.push(frame);assert.deepEqual(queue.shift(),frame);
});

test('closing a replaced video channel does not remove its successor or another peer channel', () => {
  const manager={videos:new Map()},old=new QuicVideo(manager,{},1,7),replacement=new QuicVideo(manager,{},1,7),other=new QuicVideo(manager,{},2,7);
  manager.videos.set('1:7',replacement);manager.videos.set('2:7',other);old.close();assert.equal(manager.videos.get('1:7'),replacement);assert.equal(manager.videos.get('2:7'),other);replacement.close();other.close();
});

test('two viewers have independent bitrate controllers, source lifetimes and congestion feedback', async t => {
  const sources=[],service={native:{},authorize(){},options:{host:'127.0.0.1',videoSource(_n,_h,emit,_fail,options){const source={emit,rate:options.bitrate,closed:false,close(){this.closed=true;}};sources.push(source);return source;}},quic:{video(){return {queued:0,send(){return true;},close(){}};}}};
  const host=new VideoHost(service);t.after(()=>host.closeAll());
  const a={remote:{id:randomUUID()},quicConn:1},b={remote:{id:randomUUID()},quicConn:2};
  const first=await host.open(a,{quic:1,h264:true}),second=await host.open(b,{quic:1,h264:true});
  const slow=host.sessions.get(first.id),fast=host.sessions.get(second.id);
  assert.equal(host.keep(a,first.id,{delayMs:200,rttMs:30}).bitrate,2600000);
  assert.equal(fast.adaptive.rate,4000000);assert.equal(sources[0].closed,true);assert.equal(sources[1].closed,false);
  assert.throws(()=>host.keep(b,first.id,{delayMs:0,rttMs:10}));
  slow.rtc.queued=300000;slow.adaptive.changedAt=-Infinity;sources.at(-1).emit({codec:'h264',key:true,width:1,height:1,data:Buffer.from('frame')});await Promise.resolve();assert.ok(slow.adaptive.rate<2600000);
  host.stop(a,first.id);assert.equal(host.sessions.size,1);assert.equal(host.sessions.get(second.id),fast);assert.equal(sources[1].closed,false);
});

test('JPEG fallback requests smaller compressed captures at the weak-link bitrate', async () => {
  const {captureVideo}=await import('../src/video.js');const requests=[];
  const native={capabilities:{width:1920,height:1080},async call(method,args){requests.push({method,args});return {width:640,height:360,data:Buffer.alloc(100000).toString('base64')};}};
  let source;await new Promise((resolve,reject)=>{source=captureVideo(native,false,()=>resolve(),reject,{bitrate:500000});});source.close();
  assert.deepEqual(requests,[{method:'screen',args:{maxWidth:640,quality:.35}}]);
});
