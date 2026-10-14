import test from 'node:test';
import assert from 'node:assert/strict';
import dgram from 'node:dgram';
import {once} from 'node:events';
import {mkdtemp,rm} from 'node:fs/promises';
import {randomBytes,randomUUID} from 'node:crypto';
import os from 'node:os';
import path from 'node:path';
import {QuicNative,quicPath} from '../src/quic-native.js';
import {MotionSender} from '../src/quic-motion.js';

const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const wire=value=>Buffer.from(JSON.stringify(value)).toString('base64');
async function until(check,timeout=3000){const started=performance.now();while(!check()){if(performance.now()-started>timeout)throw Error('QUIC test timed out');await pause(5);}}
async function engines(t,count) {
  const directory=await mkdtemp(path.join(os.tmpdir(),'lanlink-quic-pressure-')),all=[],hold=setInterval(()=>{},1000);
  t.after(async()=>{for(const engine of all)engine.close();clearInterval(hold);await rm(directory,{recursive:true,force:true});});
  for(let i=0;i<count;i++){const engine=new QuicNative();all.push(engine);await engine.start(path.join(directory,String(i)),'127.0.0.1:0');}
  return all;
}
async function connect(sender,receiver,port=receiver.info.port){
  const ticket=randomBytes(32).toString('hex');await receiver.call('ticket',{ticket,owner:randomUUID()});
  const accepted=once(receiver,'connected');
  const link=await sender.call('connect',{address:`127.0.0.1:${port}`,ticket,fingerprint:receiver.info.fingerprint,owner:randomUUID()},3000);
  const [remote]=await accepted;return {local:link.conn,remote:remote.conn};
}

test('a short network outage resumes with recent mouse positions',{skip:!quicPath(),timeout:10000},async t=>{
  const [a,b]=await engines(t,2),relay=dgram.createSocket('udp4');let client,paused=false;
  relay.on('message',(packet,source)=>{
    if(source.port===b.info.port){if(client&&!paused)relay.send(packet,client.port,'127.0.0.1');}
    else {client=source;if(!paused)relay.send(packet,b.info.port,'127.0.0.1');}
  });
  relay.bind(0,'127.0.0.1');await once(relay,'listening');t.after(()=>relay.close());
  const conn=await connect(a,b,relay.address().port),sender=new MotionSender(),received=[];
  b.on('datagram',event=>received.push(JSON.parse(Buffer.from(event.data,'base64'))));
  await a.call('datagram',{conn:conn.local,data:wire(sender.move({kind:'move',x:0,y:0}))});await until(()=>received.length===1);
  paused=true;
  for(let i=1;i<=100;i++){await a.call('datagram',{conn:conn.local,data:wire(sender.move({kind:'move',x:i/101,y:0}))});await pause(4);}
  const latest=sender.move({kind:'move',x:1,y:0});
  await a.call('datagram',{conn:conn.local,data:wire(latest)});
  paused=false;await until(()=>received.some(move=>move.seq===latest.seq));
  const stale=received.filter(move=>move.seq>1&&move.time<latest.time-120);
  assert.equal(stale.length,0,`replayed ${stale.length} positions older than the live input window`);
});

test('two busy peers cannot exhaust the shared native command slots needed by mouse control',{skip:!quicPath(),timeout:10000},async t=>{
  const [a,b,c]=await engines(t,3),ab=await connect(a,b),ac=await connect(a,c),held=[];
  for(const receiver of [b,c])receiver.on('request',event=>{
    const request=JSON.parse(Buffer.from(event.data,'base64'));
    if(request.method==='blockedWork')held.push({receiver,event});
    else receiver.call('reply',{conn:event.conn,stream:event.stream,data:wire({ok:true})}).catch(()=>{});
  });
  const work=[],rejected=[];for(const conn of [ab.local,ac.local])for(let i=0;i<32;i++)work.push(a.call('request',{conn,data:wire({method:'blockedWork'})},5000).catch(error=>{rejected.push(error);return null;}));
  try {
    await until(()=>held.length>=48);
    const motion=once(b,'datagram',{signal:AbortSignal.timeout(1000)});
    await a.call('datagram',{conn:ab.local,data:wire(new MotionSender().move({kind:'move',x:.7,y:.4}))},1000);
    await motion;
    const click=await a.call('request',{conn:ab.local,priority:0,data:wire({method:'input',args:{kind:'button',button:0,down:true}})},1000);
    assert.deepEqual(JSON.parse(Buffer.from(click.data,'base64')),{ok:true});
    assert.equal(held.filter(item=>item.receiver===b).length,24);
    assert.equal(held.filter(item=>item.receiver===c).length,24);
    assert.equal(rejected.length,16);
    assert.ok(rejected.every(error=>error.code==='QUIC_BUSY'));
    assert.equal(a.pending.size,48,'background RPCs stay bounded while both control operations complete');
  } finally {
    await Promise.all(held.map(({receiver,event})=>receiver.call('reply',{conn:event.conn,stream:event.stream,data:wire({ok:true})}).catch(()=>{})));
    await Promise.all(work);
  }
});

test('urgent network RPCs leave bounded native capacity for replies, motion and close',{skip:!quicPath(),timeout:10000},async t=>{
  const [a,b,c]=await engines(t,3),ab=await connect(a,b),ac=await connect(a,c),held=[],rejected=[];
  for(const receiver of [b,c])receiver.on('request',event=>held.push({receiver,event}));
  const work=[];
  for(const conn of [ab.local,ac.local])for(let i=0;i<32;i++)work.push(a.call('request',{conn,priority:0,data:wire({method:'blockedControl'})},5000).catch(error=>{rejected.push(error);return null;}));
  try {
    await until(()=>held.length===56);
    assert.equal(held.filter(item=>item.receiver===b).length,32,'the first peer respects the native per-connection limit');
    assert.equal(held.filter(item=>item.receiver===c).length,24);
    assert.equal(rejected.length,8);assert.ok(rejected.every(error=>error.code==='QUIC_BUSY'));
    assert.equal(a.pending.size,56);
    const incoming=once(a,'request',{signal:AbortSignal.timeout(1000)});
    const response=b.call('request',{conn:ab.remote,data:wire({method:'needsReply'})},1000);
    const [request]=await incoming;
    await a.call('reply',{conn:request.conn,stream:request.stream,data:wire({ok:true})},1000);
    assert.deepEqual(JSON.parse(Buffer.from((await response).data,'base64')),{ok:true});
    const motion=once(c,'datagram',{signal:AbortSignal.timeout(1000)});
    await a.call('datagram',{conn:ac.local,data:wire(new MotionSender().move({kind:'move',x:.2,y:.8}))},1000);await motion;
    assert.equal(a.pending.size,56,'short operations do not increase the retained network workload');
    await a.call('close',{conn:ab.local},1000);
    await until(()=>a.pending.size===24);
  } finally {
    await Promise.all(held.filter(item=>item.receiver===c).map(({receiver,event})=>receiver.call('reply',{conn:event.conn,stream:event.stream,data:wire({ok:true})}).catch(()=>{})));
    await Promise.all(work);
  }
});
