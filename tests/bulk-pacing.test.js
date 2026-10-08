import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {randomUUID} from 'node:crypto';
import {BulkHost,bulkSlice,connectBulk} from '../src/bulk.js';

function fixture(data){
  const peers=[0,1].map(()=>Object.assign(new EventEmitter(),{remote:{id:randomUUID()}}));
  const reads=[],service={options:{host:'127.0.0.1'},videoHost:{sessions:new Map([['video',{adaptive:{rate:500000}}]])},videoViewer:{sessions:new Map()},authorize(){},getPeer(id){return peers.find(peer=>peer.remote.id===id);},log(){},async fileBytes(args){reads.push(args.maxBytes);return data.subarray(args.offset,Math.min(data.length,args.end??data.length,args.offset+(args.maxBytes||192*1024)));}};
  return {peers,reads,service,host:new BulkHost(service)};
}

test('lowest shared file budgets use small records with sub-second scheduling intervals',()=>{
  for(const streams of [1,2,4,16,20]){const rate=8192/streams,length=bulkSlice(rate);assert.ok(length<=Math.max(128,Math.floor(rate/10)));assert.ok(length*1000/rate<1000);}
  assert.equal(bulkSlice(32*1024*1024),192*1024);assert.equal(bulkSlice(32*1024*1024,512),512);
});

test('two weak-link TCP file streams remain responsive within the shared budget',{timeout:15000},async t=>{
  const data=Buffer.alloc(16*1024,7),f=fixture(data);t.after(()=>f.host.closeAll());
  const connections=await Promise.all(f.peers.map(async peer=>connectBulk('127.0.0.1',await f.host.open(peer,{offerId:'o',fileId:peer.remote.id,offset:0,end:data.length,completeAck:true}))));
  t.after(()=>connections.forEach(connection=>connection.close()));
  const started=performance.now();
  const results=await Promise.all(connections.map(async connection=>{let size=0,count=0;const chunks=[];while(size<data.length){const chunk=await connection.next();assert.ok(chunk.length<=937,'small records replace a 192 KiB burst');chunks.push(chunk);size+=chunk.length;count++;}connection.complete(data.length);assert.ok(count>20);return Buffer.concat(chunks);}));
  for(const received of results)assert.deepEqual(received,data);
  assert.ok(performance.now()-started>=2800,'two streams share one budget rather than each using its full allowance');
  assert.ok(f.reads.every(length=>length<=937));
});

test('QUIC requests and TCP sessions share the same file budget and promptly clean up final ranges',async t=>{
  const data=Buffer.alloc(3000,8),f=fixture(data);t.after(()=>f.host.closeAll());
  const pending=await f.host.open(f.peers[0],{offerId:'o',fileId:'tcp',offset:0,end:data.length});assert.ok(pending.port);
  const args={offerId:'o',fileId:'quic',offset:0,end:data.length,maxBytes:192*1024};
  const first=await f.host.quicBytes(f.peers[1],args);assert.ok(first.length<=468);assert.equal(f.host.quicSessions.size,1);
  let offset=first.length;while(offset<data.length){const part=await f.host.quicBytes(f.peers[1],{...args,offset});assert.ok(part.length<=468);offset+=part.length;}
  assert.equal(f.host.quicSessions.size,0);
});

test('a receiver protecting another remote view paces fast QUIC replies without double-throttling',async()=>{
  const {receiveQuicFile}=await import('../src/bulk.js'),data=Buffer.alloc(12*1024,5),chunks=[],requests=[];
  const source={quicConn:1,remote:{id:randomUUID()}},service={videoHost:{sessions:new Map()},videoViewer:{sessions:new Map([['other',{bitrate:500000}]])},allow(){},canShareClipboard(){return true;},quic:{async rpc(_p,_m,args){requests.push(args);await new Promise(resolve=>setTimeout(resolve,40));return data.subarray(args.offset,Math.min(data.length,args.offset+args.maxBytes));}}};
  const started=performance.now();await receiveQuicFile(service,source,{offset:0},data.length,async chunk=>chunks.push(chunk),()=>{});
  assert.deepEqual(Buffer.concat(chunks),data);assert.ok(requests.every(args=>args.maxRate===9375&&args.maxBytes<=937));
  assert.ok(performance.now()-started>=1100);assert.ok(performance.now()-started<2200);
});

test('a TCP receiver renegotiates smaller records from its verified offset when a remote view starts',{timeout:10000},async t=>{
  const {receiveBulk}=await import('../src/bulk.js'),data=Buffer.alloc(200*1024,9),f=fixture(data);f.service.videoHost.sessions.clear();t.after(()=>f.host.closeAll());
  const opens=[],chunks=[],control={socket:{remoteAddress:'127.0.0.1'},async request(method,args){opens.push(args);return f.host.open(f.peers[0],args);}};
  const service={videoHost:{sessions:new Map()},videoViewer:{sessions:new Map()},getPeer(){return control;},allow(){},canShareClipboard(){return true;},group:{excluded(){return false;}}};
  await receiveBulk(service,'source',{offset:0,offerId:'o',fileId:'f'},data.length,async chunk=>{chunks.push(chunk);service.videoViewer.sessions.set('other',{bitrate:500000});},()=>{});
  assert.deepEqual(Buffer.concat(chunks),data);assert.equal(opens.length,2);assert.equal(opens[0].maxRate,32*1024*1024);assert.equal(opens[1].offset,192*1024);assert.equal(opens[1].maxRate,9375);assert.ok(chunks.slice(1).every(chunk=>chunk.length<=937));
});

test('revocation while waiting for the final TCP block prevents writing it', {timeout:5000}, async t => {
  const {receiveBulk}=await import('../src/bulk.js'),data=Buffer.alloc(128,3),f=fixture(data);t.after(()=>f.host.closeAll());
  let enter,release,allowed=true,writes=0;
  const entered=new Promise(resolve=>enter=resolve),gate=new Promise(resolve=>release=resolve),read=f.service.fileBytes.bind(f.service);
  f.service.fileBytes=async args=>{if(args.maxBytes>1){enter();await gate;}return read(args);};
  const control={socket:{remoteAddress:'127.0.0.1'},request(_method,args){return f.host.open(f.peers[0],args);}};
  const service={getPeer(){return control;},allow(){},canShareClipboard(){return allowed;},group:{excluded(){return false;}}};
  const receiving=receiveBulk(service,'source',{offset:0,offerId:'o',fileId:'f'},data.length,async()=>{writes++;},()=>{});
  const rejected=assert.rejects(receiving,/剪贴板共享未授权/);
  await entered;allowed=false;release();await rejected;assert.equal(writes,0);
});
