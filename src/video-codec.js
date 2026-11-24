import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const root = fileURLToPath(new URL('../', import.meta.url));
const stopping = new WeakMap();
const accessUnitEnd = Buffer.from([0, 0, 0, 1, 9, 240]);
// FFmpeg can stay blocked on pipe input after SIGTERM. Close owned pipes first,
// then bound its shutdown so repeated stream changes cannot leak codec workers.
export function stopVideoProcess(child) {
  if (!child) return Promise.resolve();
  if (stopping.has(child)) return stopping.get(child);
  const done = new Promise(resolve => {
    let timer;
    const finished = () => { clearTimeout(timer); resolve(); };
    child.once('close', finished);
    child.stdin?.destroy(); child.stdout?.destroy(); child.stderr?.destroy();
    if (child.exitCode !== null || child.signalCode !== null || !child.pid) { finished(); return; }
    child.kill('SIGTERM');
    timer = setTimeout(() => {
      if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
    }, 750); timer.unref();
  });
  stopping.set(child, done); return done;
}
export function ffmpegPath() {
  // Only local administrator/user-selected executables; never accept a remote path or arguments.
  const candidates = [process.env.LANLINK_FFMPEG, path.join(root, 'runtime', process.platform === 'win32' ? 'ffmpeg.exe' : 'ffmpeg'), ...(process.platform === 'darwin' ? [path.join(root, 'runtime/video/bin/ffmpeg')] : [])];
  return candidates.find(p => p && path.isAbsolute(p) && existsSync(p));
}
export class JpegParser {
  constructor(emit) { this.emit = emit; this.buffer = Buffer.alloc(0); }
  push(data) {
    this.buffer = Buffer.concat([this.buffer, data]);
    if (this.buffer.length > 4 * 1024 * 1024) throw Error('JPEG 缓冲溢出');
    for (;;) { const end = this.buffer.indexOf(Buffer.from([255, 217])); if (end < 0) return; const frame = this.buffer.subarray(0, end + 2); this.buffer = this.buffer.subarray(end + 2); if (frame[0] !== 255 || frame[1] !== 216) throw Error('JPEG 数据无效'); this.emit(frame); }
  }
}
// FFmpeg inserts AUD before every access unit; retain SPS/PPS with its following IDR.
export class AnnexBParser {
  constructor(emit) { this.emit = emit; this.buffer = Buffer.alloc(0); }
  push(data) {
    this.buffer = Buffer.concat([this.buffer, data]); if (this.buffer.length > 4 * 1024 * 1024) throw Error('H.264 缓冲溢出');
    let starts = [];
    for (let i = 0; i + 4 < this.buffer.length; i++) if (this.buffer[i] === 0 && this.buffer[i + 1] === 0) {
      let n = this.buffer[i + 2] === 1 ? i + 3 : this.buffer[i + 2] === 0 && this.buffer[i + 3] === 1 ? i + 4 : -1;
      if (n >= 0) { if ((this.buffer[n] & 31) === 9) starts.push(i); i = n; }
    }
    while (starts.length > 1) { const offset = starts[1], frame = this.buffer.subarray(0, offset); this.emit(frame, hasIdr(frame)); this.buffer = this.buffer.subarray(offset); starts = starts.slice(1).map(i => i - offset); }
  }
}
export function hasIdr(data) { for (let i = 2; i + 1 < data.length; i++) if (data[i] === 1 && data[i - 1] === 0 && data[i - 2] === 0 && (data[i + 1] & 31) === 5) return true; return false; }
export const encoderArgs = name => name === 'h264_nvenc' ? ['-preset', 'p1', '-tune', 'ull', '-zerolatency', '1', '-rc-lookahead', '0', '-delay', '0'] : name === 'h264_qsv' ? ['-preset', 'veryfast', '-look_ahead', '0', '-async_depth', '1'] : name === 'h264_amf' ? ['-usage', 'ultralowlatency', '-quality', 'speed'] : name === 'h264_videotoolbox' ? ['-realtime', '1', '-allow_sw', '1'] : [];
const probeCache = new Map();
async function probe(exe, encoder, signal) {
  return new Promise(resolve => {
    if (signal.aborted) { resolve(false); return; }
    const p = spawn(exe, ['-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'color=size=320x240:rate=30', '-frames:v', '2', '-pix_fmt', 'yuv420p', '-c:v', encoder, ...encoderArgs(encoder), '-f', 'null', '-'], { windowsHide: true, stdio: 'ignore' });
    const finish = result => { clearTimeout(timer); signal.removeEventListener('abort', cancel); resolve(result); };
    const cancel = () => { stopVideoProcess(p).then(() => finish(false)); };
    const timer = setTimeout(cancel, 2000);
    signal.addEventListener('abort', cancel, { once: true });
    p.on('error', () => finish(false)); p.on('close', code => finish(!signal.aborted && code === 0));
  });
}
export async function chooseEncoder(exe, signal) {
  if (!exe || signal?.aborted) return null;
  let entry = probeCache.get(exe);
  if (!entry) {
    entry = { controller: new AbortController(), consumers: new Set(), settled: false };
    const pending = entry;
    entry.promise = (async () => {
      for (const encoder of process.platform === 'darwin' ? ['h264_videotoolbox', 'libopenh264'] : ['h264_nvenc', 'h264_qsv', 'h264_amf', 'h264_mf', 'libopenh264']) {
        if (pending.controller.signal.aborted) return null;
        if (await probe(exe, encoder, pending.controller.signal)) return encoder;
      }
      return null;
    })().finally(() => { pending.settled = true; });
    probeCache.set(exe, entry);
  }
  // Share a probe across viewers, but stop it when every interested capture has
  // closed. A cancelled probe must not cache "unavailable" for the next stream.
  const consumer = {}; entry.consumers.add(consumer);
  const release = () => {
    entry.consumers.delete(consumer);
    if (!entry.settled && !entry.consumers.size) {
      if (probeCache.get(exe) === entry) probeCache.delete(exe);
      entry.controller.abort();
    }
  };
  signal?.addEventListener('abort', release, { once: true });
  try { const result = await entry.promise; return signal?.aborted ? null : result; }
  finally { signal?.removeEventListener('abort', release); release(); }
}
class RawFrameParser {
  constructor(size, emit) { this.size = size; this.emit = emit; this.offset = 0; }
  push(data) {
    while (data.length) {
      this.buffer ??= Buffer.allocUnsafe(this.size);
      const count = Math.min(data.length, this.size - this.offset);
      data.copy(this.buffer, this.offset, 0, count);
      data = data.subarray(count);
      this.offset += count;
      if (this.offset !== this.size) continue;
      const frame = this.buffer;
      this.buffer = null;
      this.offset = 0;
      this.emit(frame);
    }
  }
}
export function createDecoder(exe, onFrame, onError, raw) {
  if (raw && (!Number.isInteger(raw.width) || !Number.isInteger(raw.height) ||
      raw.width < 1 || raw.height < 1 || raw.width > 4096 || raw.height > 4096 ||
      raw.width * raw.height > 4096 * 2048)) throw Error('VIDEO_SURFACE_FAILED: 视频尺寸超出显示缓冲');
  const output = raw
    ? ['-vf', `scale=${raw.width}:${raw.height}:flags=fast_bilinear`, '-pix_fmt', 'bgra', '-c:v', 'rawvideo', '-threads:v', '1', '-f', 'rawvideo']
    : ['-c:v', 'mjpeg', '-threads:v', '1', '-q:v', '3', '-f', 'image2pipe'];
  // Frame threading retains reference frames before output. One decode thread
  // with slice threading presents immediately, including a static final frame.
  const p = spawn(exe, ['-hide_banner', '-loglevel', 'error', '-flags', 'low_delay', '-probesize', '32', '-analyzeduration', '0', '-threads', '1', '-thread_type', 'slice', '-f', 'h264', '-i', 'pipe:0', '-an', ...output, '-fps_mode', 'passthrough', '-flush_packets', '1', 'pipe:1'], { windowsHide: true, stdio: ['pipe', 'pipe', 'ignore'] });
  let stopped = false, needKey = true;
  const parser = raw ? new RawFrameParser(raw.width * raw.height * 4, onFrame) : new JpegParser(onFrame);
  p.stdout.on('data', d => { if (!stopped) try { parser.push(d); } catch (e) { onError(e); } }); p.stdin.on('error', () => {}); p.on('error', e => { if (!stopped) onError(e); }); p.on('exit', () => { if (!stopped) onError(Error('视频解码器已退出')); });
  return { push(frame) { if (stopped || p.stdin.destroyed || !p.stdin.writable) return false; if (p.stdin.writableLength > 256 * 1024) { needKey = true; return false; } if (needKey && !frame.key) return false; needKey = false;
    // The authenticated channel already delimits access units. Preserve that
    // boundary in FFmpeg's byte stream so its parser need not await another image.
    p.stdin.write(Buffer.concat([frame.data, accessUnitEnd])); return true;
  }, close() { stopped = true; parser.buffer = Buffer.alloc(0); return stopVideoProcess(p); } };
}
