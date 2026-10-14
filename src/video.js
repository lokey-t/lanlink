import net from 'node:net';
import {performance} from 'node:perf_hooks';
import { randomBytes, randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { packFrame, serverHandshake, connectVideo } from './video-wire.js';
import { ffmpegPath, chooseEncoder, encoderArgs, AnnexBParser, createDecoder, stopVideoProcess } from './video-codec.js';
import { AdaptiveVideo, VideoFeedback } from './adaptive-video.js';
import {RtcVideo} from './rtc-video.js';
import {FrameRate} from './frame-rate.js';

// A replaced stream can leave its codec stopping after it leaves sessions. Keep
// every shutdown until completion so an immediate application exit cannot orphan it.
function closeCodec(codec, pending) {
  if (!codec) return;
  let done;
  try { done = Promise.resolve(codec.close()); } catch (error) { done = Promise.reject(error); }
  pending.add(done);
  const finished = () => pending.delete(done);
  done.then(finished, finished);
  return done;
}
async function drainCodecs(pending) {
  while (pending.size) await Promise.allSettled([...pending]);
}

export function captureVideo(native, h264, emit, failure, options = {}) {
  let stopped = false, process, timer, wake, closing;
  const abort = new AbortController();
  const pause = ms => stopped ? Promise.resolve() : new Promise(resolve => { wake=resolve;timer=setTimeout(()=>{wake=null;timer=null;resolve();},ms); });
  const task = (async () => {
    const exe = ffmpegPath(), encoder = h264 && !native.disabled ? await chooseEncoder(exe, abort.signal) : null;
    if (stopped) return;
    const caps = native.capabilities, originalWidth = Math.round(caps.width || 1280), originalHeight = Math.round(caps.height || 720);
    const rate = Math.max(500000, Math.min(8000000, options.bitrate || 4000000));
    const fps = rate < 1000000 ? 20 : 30, maxSide = rate < 1000000 ? 960 : rate < 2000000 ? 1280 : 1920;
    const scale = Math.min(1, maxSide / Math.max(originalWidth, originalHeight));
    const width = Math.max(2, Math.floor(originalWidth * scale / 2) * 2), height = Math.max(2, Math.floor(originalHeight * scale / 2) * 2);
    if (encoder && ['win32', 'darwin'].includes(globalThis.process.platform)) {
      const windows = globalThis.process.platform === 'win32';
      // JPEG dimensions arrive in the first image. FFmpeg's default stream
      // analysis otherwise buffers about five seconds before encoding it.
      const source = windows ? ['-f', 'gdigrab', '-framerate', String(fps), '-draw_mouse', '1', '-offset_x', '0', '-offset_y', '0', '-video_size', `${originalWidth}x${originalHeight}`, '-i', 'desktop'] : ['-probesize', '32', '-analyzeduration', '0', '-f', 'image2pipe', '-framerate', String(fps), '-c:v', 'mjpeg', '-i', 'pipe:0'];
      const args = ['-hide_banner', '-loglevel', 'error', ...source, '-an', '-vf', `scale=${width}:${height}:flags=fast_bilinear,format=yuv420p`, '-c:v', encoder, ...encoderArgs(encoder), '-bf', '0', '-g', String(fps), '-b:v', String(rate), '-maxrate', String(rate), '-bufsize', String(Math.max(100000, Math.round(rate / 6))), '-bsf:v', 'h264_metadata=aud=insert', '-f', 'h264', '-flush_packets', '1', 'pipe:1'];
      process = spawn(exe, args, { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
      let error = ''; process.stderr.on('data', d => { error = (error + d).slice(-1200); }); process.stdin.on('error', () => {});
      const parser = new AnnexBParser((data, key) => emit({ codec: 'h264', width, height, key, data, encoder }));
      process.stdout.on('data', d => { try { parser.push(d); } catch (e) { failure(e); } }); process.on('error', failure); process.on('exit', () => { if (!stopped) failure(Error('H.264 编码不可用，正在降级：' + error.slice(-180))); });
      if (windows) return;
      // ScreenCaptureKit retains the existing macOS screen permission identity.
      while (!stopped) { const start = Date.now(); const frame = await native.call('screen'); if (!stopped && process.stdin.writableLength < 256 * 1024) process.stdin.write(Buffer.from(frame.data, 'base64')); await pause(Math.max(1, 1000 / fps - (Date.now() - start))); }
    } else {
      const jpeg = rate <= 500000 ? {maxWidth:640,quality:.35} : rate < 1000000 ? {maxWidth:960,quality:.45} : rate < 2000000 ? {maxWidth:1280,quality:.55} : {maxWidth:1440,quality:.65};
      while (!stopped) { const start = Date.now(); const frame = await native.call('screen',jpeg), data = Buffer.from(frame.data, 'base64'); if (!stopped) emit({ codec: 'jpeg', width: frame.width, height: frame.height, data, key: true }); await pause(Math.max(1, Math.max(50, data.length * 8000 / rate) - (Date.now() - start))); }
    }
  })(); task.catch(e => { if (!stopped) failure(e); });
  return { close() { if (closing) return closing; stopped = true; abort.abort(); clearTimeout(timer); wake?.();wake=null;return closing=Promise.allSettled([task,stopVideoProcess(process)]); } };
}

export class VideoHost {
  constructor(service) { this.service = service; this.sessions = new Map(); this.stopping = new Set(); }
  async open(peer, options = {}) {
    const s = this.service; s.authorize(peer, 'screen'); this.closePeer(peer.remote.id);
    if (this.sessions.size >= 2) throw Error('屏幕串流会话已满');
    const id = randomUUID(), secret = randomBytes(32), session = { id, peer, expires: Date.now() + 10000, sockets: new Set(), adaptive: new AdaptiveVideo(), generation: 0 };
    this.sessions.set(id, session);
    const fail = () => this.close(id);
    const startSource=(send,queued)=>{
      let waitingKey=true,blockedAt=0,h264=options.h264===true;
      const emit=frame=>{if(!this.sessions.has(id))return;try{
        if(frame.codec!=='ping')session.sourceReady=true;
        s.authorize(peer,'screen');const backlog=queued(),budget=Math.max(32768,Math.min(128*1024,session.adaptive.rate/8*.2));
        if(backlog>budget){waitingKey=true;blockedAt||=Date.now();if(session.adaptive.congested(backlog))queueMicrotask(()=>{if(this.sessions.has(id))session.restart?.();});if(Date.now()-blockedAt>5000)fail();return;}
        blockedAt=0;if(waitingKey&&frame.codec==='h264'&&!frame.key)return;
        session.lastSent=Date.now();if(send(frame)===false){waitingKey=true;if(session.adaptive.congested(Math.max(backlog,128*1024)))queueMicrotask(()=>{if(this.sessions.has(id))session.restart?.();});}else if(frame.codec!=='ping')waitingKey=false;
      }catch{fail();}};
      session.ping=()=>emit({codec:'ping',width:1,height:1,key:false,data:Buffer.alloc(0)});
      session.restart=(force=false)=>{
        // A newly started encoder already owes the receiver a keyframe. Repeated
        // needKey feedback must not keep cancelling it before its first image.
        if(!this.sessions.has(id)||!force&&session.source&&!session.sourceReady)return;
        const generation=++session.generation;closeCodec(session.source,this.stopping);
        waitingKey=true;session.sourceReady=false;session.sourceRate=session.adaptive.rate;session.keyAt=Date.now();
        session.source=(s.options.videoSource||captureVideo)(s.native,h264,frame=>{if(generation===session.generation)emit(frame);},error=>{
          queueMicrotask(()=>{
            if(generation!==session.generation||!this.sessions.has(id))return;
            if(h264){h264=false;s.log?.('视频编码不可用，已切换兼容画面：'+(error?.message||'编码器已退出'));session.restart(true);}
            else fail();
          });
        },{bitrate:session.sourceRate});
      };session.restart();
    };
    session.timer = setInterval(() => { try { if (Date.now() > session.expires) throw Error(); s.authorize(peer, 'screen'); if (Date.now() - (session.lastSent || 0) > 1000) session.ping?.(); } catch { fail(); } }, 500);
    session.server = net.createServer(async socket => {
      if (session.sockets.size >= 2 || session.active) { socket.destroy(); return; }
      session.sockets.add(socket); socket.setNoDelay(true); socket.setTimeout(5000, () => socket.destroy()); socket.on('error', () => {});
      socket.on('close', () => { session.sockets.delete(socket); if (session.active === socket) fail(); });
      try {
        const key = await serverHandshake(socket, secret); s.authorize(peer, 'screen');
        if (!this.sessions.has(id) || session.active) throw Error();
        session.active = socket; socket.setTimeout(0); session.server.close();let sequence=0;
        startSource(frame=>{socket.write(packFrame(key,sequence++,frame));return true;},()=>socket.writableLength);
      } catch { socket.destroy(); }
    });
    session.server.on('error', fail);
    try { await new Promise((resolve, reject) => { session.server.once('error', reject); session.server.listen(0, s.options.host || '0.0.0.0', resolve); }); s.authorize(peer, 'screen'); if (!this.sessions.has(id)) throw Error('串流请求已结束');
      const info={protocol:1,id,port:session.server.address().port,key:secret.toString('hex')};
      if(Number.isInteger(options.quic)&&options.quic>0&&peer.quicConn){session.rtc=s.quic.video(peer,options.quic);session.active=session.rtc;info.quic=options.quic;session.server.close();session.rtc.onSendDrop=()=>{if(Date.now()-(session.keyAt||0)>1000){session.keyAt=Date.now();session.restart?.();}};startSource(frame=>session.rtc.send(frame),()=>session.rtc.queued);}
      else if(typeof options.rtc==='string' && options.rtc.length<200000){session.rtc=new RtcVideo();info.rtc=await session.rtc.answer(options.rtc);session.rtc.ready().then(()=>{s.authorize(peer,'screen');if(!this.sessions.has(id))return;session.active=session.rtc;session.server.close();startSource(frame=>session.rtc.send(frame),()=>session.rtc.queued);}).catch(fail);}
      return info; }
    catch (e) { fail(); throw e; }
  }
  keep(peer, id, feedback) { this.service.authorize(peer, 'screen'); const session = this.sessions.get(id); if (!session || session.peer !== peer) throw Error('串流已结束'); session.expires = Date.now() + 10000;const changed=feedback&&session.adaptive.feedback(feedback,session.rtc?.queued||session.active?.writableLength||0);if(changed || session.sourceRate!==undefined&&session.sourceRate!==session.adaptive.rate || feedback?.needKey && Date.now()-(session.keyAt||0)>1000){session.restart?.();} return { bitrate: session.adaptive.rate }; }
  stop(peer, id) { if (this.sessions.get(id)?.peer === peer) this.close(id); return {}; }
  close(id) { const session = this.sessions.get(id); if (!session) return; this.sessions.delete(id); clearInterval(session.timer); closeCodec(session.source,this.stopping);session.rtc?.close(); session.tunnel?.close(); for (const socket of session.sockets) socket.destroy(); session.server?.close(); }
  closePeer(id) { for (const session of this.sessions.values()) if (session.peer.remote.id === id) this.close(session.id); }
  closeAll() { for (const id of this.sessions.keys()) this.close(id); return drainCodecs(this.stopping); }
}

// A single latest decoded frame per viewer. No frame history or unbounded UI queue.
export class VideoViewer {
  constructor(service) { this.service = service; this.sessions = new Map(); this.opening = new Map();this.rtcCooldown=new Map();this.stopping=new Set(); }
  async open(id, jpegOnly = false) {
    if (this.opening.has(id)) throw Error('CONTROL_CONFLICT: 正在连接该设备的画面');
    // Reserve the outgoing direction before awaiting close or negotiation. Otherwise
    // two peers can both accept each other's incoming request while opening a view.
    const intent = this.service.beginOutgoing?.(id) || { id, cancelled: false }; this.opening.set(id, intent);
    const check = () => { if (intent.cancelled) throw Error('CONTROL_ENDED: 画面请求已取消'); this.service.checkOutgoing?.(intent); };
    let peer, info, session,rtc;
    try {
    await this.close(id, false); check(); peer = this.service.getPeer(id); const exe = ffmpegPath();
    if(!peer.fastLink&&peer.quicConn&&(this.rtcCooldown.get(id)||0)<Date.now()){try{const channel=randomBytes(4).readUInt32BE()||1;rtc=this.service.quic.video(peer,channel);info=await peer.request('videoOpen',{h264:!!exe&&!jpegOnly,quic:channel});check();if(info.quic!==channel)throw Error('QUIC 串流未协商');}catch{rtc?.close();rtc=null;this.rtcCooldown.set(id,Date.now()+30000);if(info)await peer.request('videoStop',{id:info.id},1500).catch(()=>{});check();}}
    const preferRtc=!rtc&&!this.service.options?.noRtc && !peer.fastLink && peer.info?.capabilities?.rtcVideo===1 && (this.rtcCooldown.get(id)||0)<Date.now();
    if(preferRtc){try{rtc=new RtcVideo();info=await peer.request('videoOpen',{h264:!!exe&&!jpegOnly,rtc:await rtc.offer()});check();if(!info.rtc)throw Error('对方未提供 RTC');await rtc.accept(info.rtc);await rtc.ready();check();}catch(error){rtc?.close();rtc=null;this.rtcCooldown.set(id,Date.now()+30000);if(info)await peer.request('videoStop',{id:info.id},1500).catch(()=>{});check();}}
    if(!rtc)info = await peer.request('videoOpen', { h264: !!exe && !jpegOnly }); check();
    session = { targetId:id,peer, info, abort: new AbortController(), until: Date.now() + 10000, frame: null, waiter: null, closed: false, delivered: new FrameRate(), feedback: new VideoFeedback(Date.now, () => performance.now()) };
    this.sessions.set(id, session);
    if(rtc){session.rtc=rtc;session.connection=rtc;rtc.onNeedKey=()=>peer.request('videoKey',{id:info.id},1500).catch(()=>{});}else{session.route = await this.service.links?.videoEndpoint(peer, info);session.connection = await connectVideo(session.route?.host || peer.socket.remoteAddress, session.route?.info || info, session.abort.signal);} check();
    if (this.sessions.get(id) !== session || session.closed) throw Error('CONTROL_ENDED: 画面请求已取消');
    session.timer = setInterval(async () => { if (this.sessions.get(id) !== session || session.closed || session.keeping) return; if (Date.now() > session.until) { await this.close(id); return; } session.keeping = true; const start = Date.now(); try { const feedback = {...session.feedback.report(),needKey:!!session.rtc?.assembler.needKey}; const result = await peer.request('videoKeep', { id: info.id, feedback }, 3000); session.feedback.rtt = Date.now() - start; session.bitrate = result.bitrate; } catch (e) { if (e.code !== 'QUIC_BUSY') this.fail(session, e); } finally { session.keeping = false; } }, 1000);
    const publish = (data, frame) => {
      if (session.closed) return;
      session.frame = { data: data.toString('base64'), width: frame.width, height: frame.height, codec: frame.codec,transport:rtc?.manager?'quic':rtc?'webrtc':'tcp', bitrate: session.bitrate || 4000000 };
      session.lastFrame = session.frame;
      if (session.waiter) { const waiter = session.waiter; session.waiter = null; const value = {...session.frame,fps:session.delivered.tick()}; session.frame = null; waiter.resolve(value); }
    };
    (async () => { try { while (!session.closed) {
      const frame = await session.connection.next();
      if (session.closed) break;
      session.feedback.receive(frame);
      if (frame.codec === 'ping') continue;
      if (frame.codec === 'jpeg') publish(frame.data, frame);
      else {
        if (!exe) throw Error('本机缺少视频解码组件');
        if (!session.decoder || session.meta.width !== frame.width || session.meta.height !== frame.height) {
          // Recreate on rotation: a fixed FFmpeg output graph may retain the old
          // size/aspect, letterbox the new picture or stop producing frames.
          closeCodec(session.decoder,this.stopping);
          const generation = Symbol('decoder'), meta = { width: frame.width, height: frame.height, codec: frame.codec };
          session.decoderGeneration = generation; session.meta = meta;
          session.decoder = createDecoder(exe, data => { if(session.decoderGeneration===generation) publish(data, meta); }, e => { if(session.decoderGeneration===generation) this.fail(session, e); });
        }
        if(session.decoder.push(frame)===false&&Date.now()-(session.decodeKeyAt||0)>1000){session.decodeKeyAt=Date.now();peer.request('videoKey',{id:info.id},1500).catch(()=>{});}
      }
    } } catch (e) { this.fail(session, e); } })();
    return { mode: 'stream', h264Only: !!info.h264Only || !!peer.info?.capabilities?.videoH264Only };
    } catch (e) {
      rtc?.close();if (session && this.sessions.get(id) === session) await this.close(id, false);
      else if (info && peer) await peer.request('videoStop', { id: info.id }, 1500).catch(() => {});
      throw e;
    } finally { if (this.opening.get(id) === intent) this.opening.delete(id); this.service.endOutgoing?.(intent); }
  }
  fail(session, e) { if (session.closed) return; session.error = e; session.closed = true; if(e.message !== '串流已关闭'){session.route?.failed?.();if(session.rtc)this.rtcCooldown.set(session.targetId,Date.now()+30000);}session.rtc?.close(); session.abort.abort(); session.route?.close(); session.decoderStop=closeCodec(session.decoder,this.stopping); clearInterval(session.timer); session.waiter?.reject(e); session.waiter = null; }
  async next(id) {
    const session = this.sessions.get(id); if (!session) throw Error('串流未开始'); if (session.error) throw session.error; session.until = Date.now() + 10000;
    if (session.frame) { const frame = {...session.frame,fps:session.delivered.tick()}; session.frame = null; return frame; }
    if (session.waiter) throw Error('重复的画面读取');
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { session.waiter = null; if (session.lastFrame && !session.closed) resolve({ ...session.lastFrame, fps: 0, unchanged: true }); else reject(Error('等待视频画面超时')); }, 5000);
      session.waiter = { resolve: v => { clearTimeout(timer); resolve(v); }, reject: e => { clearTimeout(timer); reject(e); } };
    });
  }
  async close(id, cancelPending = true) { if (cancelPending && this.opening.has(id)) this.opening.get(id).cancelled = true; const session = this.sessions.get(id); if (!session) return; this.sessions.delete(id); this.fail(session, Error('串流已关闭')); await Promise.allSettled([session.decoderStop,session.peer.request('videoStop', { id: session.info.id }, 1500)]); }
  async closeAll() { for (const intent of this.opening.values()) intent.cancelled = true; await Promise.all([...this.sessions.keys()].map(id => this.close(id))); await drainCodecs(this.stopping); }
}
