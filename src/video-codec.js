import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const root = fileURLToPath(new URL('../', import.meta.url));
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
async function probe(exe, encoder) {
  return new Promise(resolve => {
    const p = spawn(exe, ['-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'color=size=320x240:rate=30', '-frames:v', '2', '-pix_fmt', 'yuv420p', '-c:v', encoder, ...encoderArgs(encoder), '-f', 'null', '-'], { windowsHide: true, stdio: 'ignore' });
    const timer = setTimeout(() => { p.kill(); resolve(false); }, 2000); p.on('error', () => { clearTimeout(timer); resolve(false); }); p.on('exit', code => { clearTimeout(timer); resolve(code === 0); });
  });
}
export async function chooseEncoder(exe) {
  if (!exe) return null; if (probeCache.has(exe)) return probeCache.get(exe);
  const pending = (async () => { for (const encoder of process.platform === 'darwin' ? ['h264_videotoolbox', 'libopenh264'] : ['h264_nvenc', 'h264_qsv', 'h264_amf', 'h264_mf', 'libopenh264']) if (await probe(exe, encoder)) return encoder; return null; })(); probeCache.set(exe, pending); return pending;
}
export function createDecoder(exe, onFrame, onError) {
  const p = spawn(exe, ['-hide_banner', '-loglevel', 'error', '-flags', 'low_delay', '-probesize', '32', '-analyzeduration', '0', '-f', 'h264', '-i', 'pipe:0', '-an', '-threads', '2', '-c:v', 'mjpeg', '-q:v', '3', '-fps_mode', 'passthrough', '-f', 'image2pipe', '-flush_packets', '1', 'pipe:1'], { windowsHide: true, stdio: ['pipe', 'pipe', 'ignore'] });
  let stopped = false, needKey = true; const parser = new JpegParser(onFrame);
  p.stdout.on('data', d => { try { parser.push(d); } catch (e) { onError(e); } }); p.stdin.on('error', () => {}); p.on('error', onError); p.on('exit', () => { if (!stopped) onError(Error('视频解码器已退出')); });
  return { push(frame) { if (p.stdin.writableLength > 256 * 1024) { needKey = true; return false; } if (needKey && !frame.key) return false; needKey = false; p.stdin.write(frame.data); return true; }, close() { stopped = true; p.kill(); } };
}
