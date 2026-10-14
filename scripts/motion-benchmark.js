// Local transport benchmark only: no OS input is injected and no Wi-Fi is measured.
import {mkdtemp,rm} from 'node:fs/promises';
import {randomBytes,randomUUID} from 'node:crypto';
import os from 'node:os';
import path from 'node:path';
import {QuicNative} from '../src/quic-native.js';
import {MotionSender} from '../src/quic-motion.js';
import {encodeMotion,decodeMotion} from '../src/motion-wire.js';
const samples=Number(process.env.LANLINK_BENCH_SAMPLES||200);
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const directory=await mkdtemp(path.join(os.tmpdir(),'lanlink-motion-bench-'));
const a=new QuicNative(),b=new QuicNative(),hold=setInterval(()=>{},1000),results=[];
try{
  await a.start(path.join(directory,'a'),'127.0.0.1:0');const info=await b.start(path.join(directory,'b'),'127.0.0.1:0');
  if(!a.binaryMotion||!b.binaryMotion)throw Error('Rebuild the native QUIC library before benchmarking');
  const ticket=randomBytes(32).toString('hex');await b.call('ticket',{ticket,owner:randomUUID()});
  const connection=await a.call('connect',{address:`127.0.0.1:${info.port}`,ticket,fingerprint:info.fingerprint,owner:randomUUID()});
  for(const video of [false,true])for(const binary of [false,true]){
    const sender=new MotionSender(),delays=[];let waiting,videoTimer;
    const arrived=e=>{const m=e.bytes?decodeMotion(e.bytes):JSON.parse(Buffer.from(e.data,'base64'));if(waiting?.seq===m.seq){delays.push(performance.now()-waiting.at);waiting.resolve();waiting=null;}};
    b.on('motion',arrived);b.on('datagram',arrived);
    if(video)videoTimer=setInterval(()=>a.call('video',{conn:connection.conn,channel:1,data:Buffer.alloc(24000,1).toString('base64')}).catch(()=>{}),40);
    try{
      for(let i=0;i<samples+20;i++){
        const snapshot=sender.move({kind:'relative',dx:.5,dy:-.25});
        const received=new Promise((resolve,reject)=>{const timeout=setTimeout(()=>reject(Error('Motion benchmark timed out')),3000);waiting={seq:snapshot.seq,at:performance.now(),resolve(){clearTimeout(timeout);resolve();}};});
        if(binary)a.sendMotion(connection.conn,encodeMotion({...snapshot,afterInput:0}));
        else await a.call('datagram',{conn:connection.conn,data:Buffer.from(JSON.stringify(snapshot)).toString('base64')});
        await received;await pause(4);
      }
    }finally{clearInterval(videoTimer);b.off('motion',arrived);b.off('datagram',arrived);}
    const sorted=delays.slice(20).sort((x,y)=>x-y),percentile=p=>+sorted[Math.min(sorted.length-1,Math.floor(sorted.length*p))].toFixed(3);
    results.push({transport:binary?'binary':'JSON/Base64 compatibility',video,samples:sorted.length,p50Ms:percentile(.5),p95Ms:percentile(.95),p99Ms:percentile(.99)});
    await pause(200);
  }
  console.log(JSON.stringify({scope:'same-host QUIC delivery; excludes Wi-Fi, OS injection and display',results},null,2));
}finally{a.close();b.close();clearInterval(hold);await rm(directory,{recursive:true,force:true});}
