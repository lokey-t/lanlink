import net from 'node:net';
import { randomBytes, randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { packFrame, serverHandshake, connectVideo } from './video-wire.js';
import { ffmpegPath, chooseEncoder, encoderArgs, AnnexBParser, createDecoder } from './video-codec.js';
import { AdaptiveVideo, VideoFeedback } from './adaptive-video.js';
const pause = ms => new Promise(r => setTimeout(r, ms));

export function captureVideo(native, h264, emit, failure, options = {}) {
  let stopped = false, process, timer;
  const task = (async () => {
    const exe = ffmpegPath(), encoder = h264 && !native.disabled ? await chooseEncoder(exe) : null;
    if (stopped) return;
    const caps = native.capabilities, originalWidth = Math.round(caps.width || 1280), originalHeight = Math.round(caps.height || 720);
    const scale = Math.min(1, 1920 / Math.max(originalWidth, originalHeight));
    const width = Math.max(2, Math.floor(originalWidth * scale / 2) * 2), height = Math.max(2, Math.floor(originalHeight * scale / 2) * 2);
    if (encoder && ['win32', 'darwin'].includes(globalThis.process.platform)) {
      const windows = globalThis.process.platform === 'win32';
      const source = windows ? ['-f', 'gdigrab', '-framerate', '30', '-draw_mouse', '1', '-offset_x', '0', '-offset_y', '0', '-video_size', `${originalWidth}x${originalHeight}`, '-i', 'desktop'] : ['-f', 'image2pipe', '-framerate', '30', '-i', 'pipe:0'];
      const rate = Math.max(500000, Math.min(8000000, options.bitrate || 4000000));
      const args = ['-hide_banner', '-loglevel', 'error', ...source, '-an', '-vf', `scale=${width}:${height}:flags=fast_bilinear,format=yuv420p`, '-c:v', encoder, ...encoderArgs(encoder), '-bf', '0', '-g', '30', '-b:v', String(rate), '-maxrate', String(rate), '-bufsize', String(Math.max(100000, Math.round(rate / 6))), '-bsf:v', 'h264_metadata=aud=insert', '-f', 'h264', '-flush_packets', '1', 'pipe:1'];
      process = spawn(exe, args, { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
      let error = ''; process.stderr.on('data', d => { error = (error + d).slice(-1200); }); process.stdin.on('error', () => {});
      const parser = new AnnexBParser((data, key) => emit({ codec: 'h264', width, height, key, data, encoder }));
      process.stdout.on('data', d => { try { parser.push(d); } catch (e) { failure(e); } }); process.on('error', failure); process.on('exit', () => { if (!stopped) failure(Error('H.264 编码不可用，正在降级：' + error.slice(-180))); });
      if (windows) return;
      // ScreenCaptureKit retains the existing macOS screen permission identity.
      while (!stopped) { const start = Date.now(); const frame = await native.call('screen'); if (!stopped && process.stdin.writableLength < 256 * 1024) process.stdin.write(Buffer.from(frame.data, 'base64')); await pause(Math.max(1, 33 - (Date.now() - start))); }
    } else {
      while (!stopped) { const start = Date.now(); const frame = await native.call('screen'); if (!stopped) emit({ codec: 'jpeg', width: frame.width, height: frame.height, data: Buffer.from(frame.data, 'base64'), key: true }); await pause(Math.max(1, 50 - (Date.now() - start))); }
    }
  })(); task.catch(e => { if (!stopped) failure(e); });
  return { close() { stopped = true; clearTimeout(timer); process?.kill(); } };
}

export class VideoHost {
  constructor(service) { this.service = service; this.sessions = new Map(); }
  async open(peer, options = {}) {
    const s = this.service; s.authorize(peer, 'screen'); this.closePeer(peer.remote.id);
    if (this.sessions.size >= 2) throw Error('屏幕串流会话已满');
    const id = randomUUID(), secret = randomBytes(32), session = { id, peer, expires: Date.now() + 10000, sockets: new Set(), adaptive: new AdaptiveVideo(), generation: 0 };
    this.sessions.set(id, session);
    const fail = () => this.close(id);
    session.timer = setInterval(() => { try { if (Date.now() > session.expires) throw Error(); s.authorize(peer, 'screen'); if (Date.now() - (session.lastSent || 0) > 1000) session.ping?.(); } catch { fail(); } }, 500);
    session.server = net.createServer(async socket => {
      if (session.sockets.size >= 2 || session.active) { socket.destroy(); return; }
      session.sockets.add(socket); socket.setNoDelay(true); socket.setTimeout(5000, () => socket.destroy()); socket.on('error', () => {});
      socket.on('close', () => { session.sockets.delete(socket); if (session.active === socket) fail(); });
      try {
        const key = await serverHandshake(socket, secret); s.authorize(peer, 'screen');
        if (!this.sessions.has(id) || session.active) throw Error();
        session.active = socket; socket.setTimeout(0); session.server.close(); let sequence = 0, waitingKey = true, blockedAt = 0;
        const emit = frame => {
          if (!this.sessions.has(id)) return;
          try {
            s.authorize(peer, 'screen');
            // Never queue seconds of stale video. Recover H.264 only at a complete IDR.
            if (socket.writableLength > 128 * 1024) { waitingKey = true; blockedAt ||= Date.now(); if (Date.now() - blockedAt > 2000) fail(); return; }
            blockedAt = 0; if (waitingKey && frame.codec === 'h264' && !frame.key) return; if (frame.codec !== 'ping') waitingKey = false;
            session.lastSent = Date.now();
            socket.write(packFrame(key, sequence++, frame));
          } catch { fail(); }
        };
        session.ping = () => emit({ codec: 'ping', width: 1, height: 1, key: false, data: Buffer.alloc(0) });
        session.restart = () => { const generation = ++session.generation; session.source?.close(); waitingKey = true; session.source = (s.options.videoSource || captureVideo)(s.native, options.h264 === true, frame => { if (generation === session.generation) emit(frame); }, () => { if (generation === session.generation) fail(); }, { bitrate: session.adaptive.rate }); };
        session.restart();
      } catch { socket.destroy(); }
    });
    session.server.on('error', fail);
    try { await new Promise((resolve, reject) => { session.server.once('error', reject); session.server.listen(0, s.options.host || '0.0.0.0', resolve); }); s.authorize(peer, 'screen'); if (!this.sessions.has(id)) throw Error('串流请求已结束'); return { protocol: 1, id, port: session.server.address().port, key: secret.toString('hex') }; }
    catch (e) { fail(); throw e; }
  }
  keep(peer, id, feedback) { this.service.authorize(peer, 'screen'); const session = this.sessions.get(id); if (!session || session.peer !== peer) throw Error('串流已结束'); session.expires = Date.now() + 10000; if (feedback && session.adaptive.feedback(feedback, session.active?.writableLength || 0)) session.restart?.(); return { bitrate: session.adaptive.rate }; }
  stop(peer, id) { if (this.sessions.get(id)?.peer === peer) this.close(id); return {}; }
  close(id) { const session = this.sessions.get(id); if (!session) return; this.sessions.delete(id); clearInterval(session.timer); session.source?.close(); for (const socket of session.sockets) socket.destroy(); session.server?.close(); }
  closePeer(id) { for (const session of this.sessions.values()) if (session.peer.remote.id === id) this.close(session.id); }
  closeAll() { for (const id of this.sessions.keys()) this.close(id); }
}

// A single latest decoded frame per viewer. No frame history or unbounded UI queue.
export class VideoViewer {
  constructor(service) { this.service = service; this.sessions = new Map(); this.opening = new Map(); }
  async open(id, jpegOnly = false) {
    if (this.opening.has(id)) throw Error('CONTROL_CONFLICT: 正在连接该设备的画面');
    // Reserve the outgoing direction before awaiting close or negotiation. Otherwise
    // two peers can both accept each other's incoming request while opening a view.
    const intent = this.service.beginOutgoing?.(id) || { id, cancelled: false }; this.opening.set(id, intent);
    const check = () => { if (intent.cancelled) throw Error('CONTROL_ENDED: 画面请求已取消'); this.service.checkOutgoing?.(intent); };
    let peer, info, session;
    try {
    await this.close(id, false); check(); peer = this.service.getPeer(id); const exe = ffmpegPath();
    info = await peer.request('videoOpen', { h264: !!exe && !jpegOnly }); check();
    session = { peer, info, abort: new AbortController(), until: Date.now() + 10000, frame: null, waiter: null, closed: false, count: 0, started: Date.now(), feedback: new VideoFeedback() };
    this.sessions.set(id, session);
    session.connection = await connectVideo(peer.socket.remoteAddress, info, session.abort.signal); check();
    if (this.sessions.get(id) !== session || session.closed) throw Error('CONTROL_ENDED: 画面请求已取消');
    session.timer = setInterval(async () => { if (this.sessions.get(id) !== session || session.closed || session.keeping) return; if (Date.now() > session.until) { await this.close(id); return; } session.keeping = true; const start = Date.now(); try { const result = await peer.request('videoKeep', { id: info.id, feedback: session.feedback.report() }, 3000); session.feedback.rtt = Date.now() - start; session.bitrate = result.bitrate; } catch (e) { this.fail(session, e); } finally { session.keeping = false; } }, 1000);
    const publish = (data, frame) => {
      if (session.closed) return; session.count++; const elapsed = Date.now() - session.started;
      session.frame = { data: data.toString('base64'), width: frame.width, height: frame.height, codec: frame.codec, fps: Math.round(session.count * 1000 / Math.max(elapsed, 1)), bitrate: session.bitrate || 4000000 };
      session.lastFrame = session.frame;
      if (session.waiter) { const waiter = session.waiter; session.waiter = null; const value = session.frame; session.frame = null; waiter.resolve(value); }
    };
    (async () => { try { while (!session.closed) {
      const frame = await session.connection.next();
      session.feedback.receive(frame);
      if (frame.codec === 'ping') continue;
      if (frame.codec === 'jpeg') publish(frame.data, frame);
      else {
        if (!exe) throw Error('本机缺少视频解码组件');
        if (!session.decoder) session.decoder = createDecoder(exe, data => publish(data, session.meta), e => this.fail(session, e));
        session.meta = frame; session.decoder.push(frame);
      }
    } } catch (e) { this.fail(session, e); } })();
    return { mode: 'stream' };
    } catch (e) {
      if (session && this.sessions.get(id) === session) await this.close(id, false);
      else if (info && peer) await peer.request('videoStop', { id: info.id }, 1500).catch(() => {});
      throw e;
    } finally { if (this.opening.get(id) === intent) this.opening.delete(id); this.service.endOutgoing?.(intent); }
  }
  fail(session, e) { if (session.closed) return; session.error = e; session.closed = true; session.abort.abort(); session.decoder?.close(); clearInterval(session.timer); session.waiter?.reject(e); session.waiter = null; }
  async next(id) {
    const session = this.sessions.get(id); if (!session) throw Error('串流未开始'); if (session.error) throw session.error; session.until = Date.now() + 10000;
    if (session.frame) { const frame = session.frame; session.frame = null; return frame; }
    if (session.waiter) throw Error('重复的画面读取');
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { session.waiter = null; if (session.lastFrame && !session.closed) resolve({ ...session.lastFrame, fps: 0, unchanged: true }); else reject(Error('等待视频画面超时')); }, 5000);
      session.waiter = { resolve: v => { clearTimeout(timer); resolve(v); }, reject: e => { clearTimeout(timer); reject(e); } };
    });
  }
  async close(id, cancelPending = true) { if (cancelPending && this.opening.has(id)) this.opening.get(id).cancelled = true; const session = this.sessions.get(id); if (!session) return; this.sessions.delete(id); this.fail(session, Error('串流已关闭')); await session.peer.request('videoStop', { id: session.info.id }, 1500).catch(() => {}); }
  async closeAll() { for (const intent of this.opening.values()) intent.cancelled = true; await Promise.all([...this.sessions.keys()].map(id => this.close(id))); }
}
