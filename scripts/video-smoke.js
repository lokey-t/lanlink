// Synthetic pictures only. Exercises the production encoder, encrypted channel and decoder.
import { spawn } from 'node:child_process';
import { writeFile,mkdtemp,rm } from 'node:fs/promises';
import {EventEmitter} from 'node:events';
import os from 'node:os';
import path from 'node:path';
import {QuicManager} from '../src/quic.js';
import { ffmpegPath, chooseEncoder, encoderArgs, AnnexBParser } from '../src/video-codec.js';
import { VideoHost, VideoViewer } from '../src/video.js';
const exe = ffmpegPath(); if (!exe) throw Error('Set LANLINK_FFMPEG to an absolute executable path');
const encoder = await chooseEncoder(exe); if (!encoder) throw Error('No H.264 encoder');
const peer = { remote: { id: 'video-fixture' } };
let dimensions = [320,240];
const source = (_native, _h264, emit, failure, options = {}) => {
  const [width,height]=dimensions;
  const p = spawn(exe, ['-hide_banner', '-loglevel', 'error', '-re', '-f', 'lavfi', '-i', `testsrc2=size=${width}x${height}:rate=30`, '-an', '-c:v', encoder, ...encoderArgs(encoder), '-bf', '0', '-g', '30', '-b:v', String(options.bitrate || 4000000), '-bsf:v', 'h264_metadata=aud=insert', '-f', 'h264', '-flush_packets', '1', 'pipe:1'], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  const parser = new AnnexBParser((data, key) => emit({ data, key, codec: 'h264', width, height })); p.stdout.on('data', d => parser.push(d)); p.stderr.on('data', d => process.stderr.write(d)); p.on('error', failure);
  return { close() { p.kill(); } };
};
const hostService=Object.assign(new EventEmitter(),{native:{},options:{host:'127.0.0.1',videoSource:source},authorize(p){if(p!==peer)throw Error('Unknown fixture');}});
const host = new VideoHost(hostService);
const control = Object.assign(new EventEmitter(),{socket:{remoteAddress:'127.0.0.1'},send(){},request:async(method,args)=>method==='quicOpen'?hostService.quic.offer(peer):method==='videoOpen'?host.open(peer,args):method==='videoKeep'?host.keep(peer,args.id,args.feedback):method==='videoKey'?host.keep(peer,args.id,{needKey:true}):method==='linkPing'||method==='release'?{}:host.stop(peer,args.id)});
let quicDir,quicClient;const viewerService={getPeer:()=>control};
if(process.argv.includes('--quic')){
  quicDir=await mkdtemp(path.join(os.tmpdir(),'lanlink-quic-video-'));peer.remote.id='a';control.remote={id:'b'};control.info={capabilities:{quic:1}};peer.info={capabilities:{quic:1}};Object.setPrototypeOf(peer,EventEmitter.prototype);EventEmitter.call(peer);peer.send=()=>{};
  peer.request=async()=>({});peer.socket={remoteAddress:'127.0.0.1'};
  const base=(id,p)=>({dir:path.join(quicDir,id),config:{id},group:{excluded:()=>false},peers:new Map([[p.remote.id,p]]),log(){},async returnLocal(){}});
  Object.assign(hostService,base('b',peer));hostService.handle=async(p,method,args)=>control.tcpFixture(method,args);control.tcpFixture=control.request;
  quicClient=Object.assign(new EventEmitter(),base('a',control),{options:{host:'127.0.0.1'},async handle(){return {};}});
  hostService.quic=new QuicManager(hostService);quicClient.quic=new QuicManager(quicClient);await hostService.quic.start();await quicClient.quic.start();await quicClient.quic.connect(control);if(!control.quicConn)throw Error('Native QUIC negotiation failed');control.request=control.lanRequest;viewerService.quic=quicClient.quic;
}
if(process.argv.includes('--rtc'))control.info={capabilities:{rtcVideo:1}};
if (process.argv.includes('--android')) {
  const info = await host.open(peer, { h264: true }); await writeFile('.tools/video-test-session.json', JSON.stringify(info)); console.log('Android synthetic stream ready; encoder=' + encoder);
  const keep = setInterval(() => { try { host.keep(peer, info.id); } catch {} }, 2500);
  setTimeout(() => { clearInterval(keep); host.closeAll(); }, 90000);
} else {
  const viewer = new VideoViewer(viewerService); const times = [], start = performance.now(); let firstFrame;
  try {
    await viewer.open('fixture');if(process.argv.includes('--rtc')&&!viewer.sessions.get('fixture').rtc)throw Error('WebRTC must be negotiated');if(process.argv.includes('--quic')&&!viewer.sessions.get('fixture').rtc?.manager)throw Error('QUIC must be negotiated');
    for (let i = 0; i < 120; i++) { if (i === 50) { const id=[...host.sessions.keys()][0]; const update=host.keep(peer,id,{delayMs:180,rttMs:20}); if(update.bitrate!==2600000) throw Error('Adaptive bitrate failed'); } const frame = await viewer.next('fixture'); if (frame.codec !== 'h264' || frame.width !== 320 || !Buffer.from(frame.data, 'base64').subarray(0, 2).equals(Buffer.from([255,216]))) throw Error('Decoded frame invalid'); firstFrame ??= performance.now(); if (i >= 30) times.push(performance.now()); }
    const fps = 89 * 1000 / (times.at(-1) - times[0]); if (fps < 15) throw Error('Synthetic throughput below 15 fps: ' + fps);
    if(process.argv.includes('--soak')){
      dimensions=[720,1600];[...host.sessions.values()][0].restart();
      const buckets=[],end=Date.now()+180000;let at=performance.now(),count=0,measured=[];
      while(Date.now()<end){const frame=await viewer.next('fixture');if(frame.unchanged)continue;count++;measured.push(frame.fps);
        // A short consumer scheduling stall must not repeatedly lose the IDR.
        if(count%90===0)await new Promise(r=>setTimeout(r,90));
        const now=performance.now();if(now-at>=15000){const actual=count*1000/(now-at),reported=measured.reduce((a,b)=>a+b,0)/measured.length;buckets.push({fps:+actual.toFixed(1),reported:+reported.toFixed(1),pending:viewer.sessions.get('fixture').rtc?.queue.frames.length||0});console.log(JSON.stringify({soakBucket:buckets.at(-1)}));if(actual<15)throw Error('Sustained stream below 15 fps');if(Math.abs(actual-reported)>6)throw Error('Recent delivered FPS diverges from throughput');at=now;count=0;measured=[];}}
      console.log(JSON.stringify({soak:'PASS',seconds:180,resolution:'720x1600',buckets}));dimensions=[320,240];[...host.sessions.values()][0].restart();
    }
    const session=[...host.sessions.values()][0], connection=viewer.sessions.get('fixture').connection;
    for(const next of [[240,640],[640,240],[320,240]]) {
      dimensions=next;session.restart();let matched=0,deadline=Date.now()+8000;
      while(matched<8 && Date.now()<deadline) {
        const frame=await viewer.next('fixture');if(frame.width!==next[0] || frame.height!==next[1])continue;
        const jpeg=Buffer.from(frame.data,'base64');let size;
        for(let offset=2;offset+9<jpeg.length;) { const marker=jpeg[offset+1],length=jpeg.readUInt16BE(offset+2);if([0xc0,0xc1,0xc2].includes(marker)){size=[jpeg.readUInt16BE(offset+7),jpeg.readUInt16BE(offset+5)];break;} if(length<2)break;offset+=2+length; }
        if(!size || size[0]!==next[0] || size[1]!==next[1])throw Error('Decoded JPEG retained stale dimensions: '+size+' expected '+next);
        matched++;
      }
      if(matched<8 || viewer.sessions.get('fixture').connection!==connection)throw Error('Rotation must recover on the same media connection');
    }
    // Drop the live media socket, then negotiate a new encrypted stream and decoder.
    for (const session of host.sessions.values()){if(session.rtc)session.rtc.close();else session.active?.destroy();}
    await new Promise(resolve => setTimeout(resolve,100));
    await viewer.close('fixture'); await viewer.open('fixture');
    const recovered = await viewer.next('fixture');
    if(recovered.codec !== 'h264' || recovered.width !== 320) throw Error('H.264 recovery failed');
    console.log(JSON.stringify({ result: 'PASS', encoder, adaptiveBitrate: '4.0 → 2.6 Mbps; decoder continued after encoder restart', rotation: '240x640 → 640x240 → 320x240, decoded pixels match metadata, same connection', decodedFrames: times.length, firstFrameMs: Math.round(firstFrame - start), decodedFps: Math.round(fps * 10) / 10, note: 'Loopback synthetic throughput, not input-to-display latency' }));
  } finally { await viewer.closeAll(); host.closeAll();if(quicClient){quicClient.quic.close();hostService.quic.close();await rm(quicDir,{recursive:true,force:true});} }
}
