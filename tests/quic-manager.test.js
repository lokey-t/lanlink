import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter,once} from 'node:events';
import {mkdtemp,rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {QuicManager} from '../src/quic.js';
import {quicPath} from '../src/quic-native.js';

test('QUIC manager uses Datagram for movement and keeps permission checks and reliable barriers',{skip:!quicPath(),timeout:10000},async()=>{
  const dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-quic-manager-')),hold=setInterval(()=>{},1000),calls=[];
  const service=id=>Object.assign(new EventEmitter(),{dir:path.join(dir,id),config:{id},options:{host:'127.0.0.1'},peers:new Map(),group:{excluded:()=>false},log(){},async returnLocal(){},async message(){},async handle(p,method,args){
    if(method==='acquire'){this.owner=p.remote.id;return {};}
    if(method==='input'){if(this.owner!==p.remote.id||this.denied)throw Error('未授权');calls.push(args);return {};}
    if(method==='inputReset'||method==='release'||method==='linkPing')return {};throw Error('unknown method');
  }});
  const a=service('a'),b=service('b');a.quic=new QuicManager(a);b.quic=new QuicManager(b);
  const peer=(id)=>Object.assign(new EventEmitter(),{remote:{id},info:{capabilities:{quic:1}},socket:{remoteAddress:'127.0.0.1'},send(){}});
  const pa=peer('b'),pb=peer('a');a.peers.set('b',pa);b.peers.set('a',pb);pa.lanRequest=(m)=>b.quic.offer(pb);pb.lanRequest=(m)=>a.quic.offer(pa);
  try{
    await a.quic.start();await b.quic.start();assert.equal(a.quic.enabled,true);assert.equal(b.quic.enabled,true);const accepted=once(b,'change');await a.quic.connect(pa);await accepted;assert.ok(pa.quicConn);assert.ok(pb.quicConn);
    await pa.lanRequest('acquire',{});
    await pa.lanRequest('input',{kind:'relative',dx:14,dy:3});
    await pa.lanRequest('input',{kind:'button',button:0,down:true});
    assert.deepEqual(calls,[{kind:'relative',dx:14,dy:3},{kind:'button',button:0,down:true}]);
    await pa.lanRequest('acquire',{});
    await pa.lanRequest('input',{kind:'move',x:.5,y:.5});
    await pa.lanRequest('input',{kind:'button',button:0,down:false});
    const first=calls.length,originalCall=a.quic.native.call.bind(a.quic.native);let dropped=0;
    a.quic.native.call=async(method,args,...rest)=>{
      if(method==='datagram'&&JSON.parse(Buffer.from(args.data,'base64')).raw&&++dropped%3!==0)return {};
      return originalCall(method,args,...rest);
    };
    for(let i=0;i<8;i++)await pa.lanRequest('input',{kind:'move',x:.5,y:1,dx:0,dy:300});
    await pa.lanRequest('input',{kind:'button',button:0,down:true}); // Recovers final missing packets.
    const moves=calls.slice(first,-1);assert.ok(moves.every(m=>m.kind==='move'&&m.y===1));assert.equal(moves.reduce((sum,m)=>sum+m.dy,0),2400);
    const beforeDenied=calls.length;
    b.denied=true;await pa.lanRequest('input',{kind:'relative',dx:9,dy:1});await assert.rejects(pa.lanRequest('input',{kind:'button',button:0,down:false}),/未授权/);assert.equal(calls.length,beforeDenied);
    await a.quic.reset();assert.equal(pa.quicConn,0);assert.equal(a.quic.connections.size,0);
  }finally{a.quic.close();b.quic.close();clearInterval(hold);await rm(dir,{recursive:true,force:true});}
});
