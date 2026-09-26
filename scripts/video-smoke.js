// Synthetic pictures only. Exercises the production encoder, encrypted channel and decoder.
import { spawn } from 'node:child_process';
import { writeFile } from 'node:fs/promises';
import { ffmpegPath, chooseEncoder, encoderArgs, AnnexBParser } from '../src/video-codec.js';
import { VideoHost, VideoViewer } from '../src/video.js';
const exe = ffmpegPath(); if (!exe) throw Error('Set LANLINK_FFMPEG to an absolute executable path');
const encoder = await chooseEncoder(exe); if (!encoder) throw Error('No H.264 encoder');
const peer = { remote: { id: 'video-fixture' } };
const source = (_native, _h264, emit, failure, options = {}) => {
  const p = spawn(exe, ['-hide_banner', '-loglevel', 'error', '-re', '-f', 'lavfi', '-i', 'testsrc2=size=320x240:rate=30', '-an', '-c:v', encoder, ...encoderArgs(encoder), '-bf', '0', '-g', '30', '-b:v', String(options.bitrate || 4000000), '-bsf:v', 'h264_metadata=aud=insert', '-f', 'h264', '-flush_packets', '1', 'pipe:1'], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  const parser = new AnnexBParser((data, key) => emit({ data, key, codec: 'h264', width: 320, height: 240 })); p.stdout.on('data', d => parser.push(d)); p.stderr.on('data', d => process.stderr.write(d)); p.on('error', failure);
  return { close() { p.kill(); } };
};
const host = new VideoHost({ native: {}, options: { host: '127.0.0.1', videoSource: source }, authorize(p) { if (p !== peer) throw Error('Unknown fixture'); } });
const control = { socket: { remoteAddress: '127.0.0.1' }, request: async (method, args) => method === 'videoOpen' ? host.open(peer, args) : method === 'videoKeep' ? host.keep(peer, args.id, args.feedback) : host.stop(peer, args.id) };
if (process.argv.includes('--android')) {
  const info = await host.open(peer, { h264: true }); await writeFile('.tools/video-test-session.json', JSON.stringify(info)); console.log('Android synthetic stream ready; encoder=' + encoder);
  const keep = setInterval(() => { try { host.keep(peer, info.id); } catch {} }, 2500);
  setTimeout(() => { clearInterval(keep); host.closeAll(); }, 90000);
} else {
  const viewer = new VideoViewer({ getPeer: () => control }); const times = [], start = performance.now(); let firstFrame;
  try {
    await viewer.open('fixture');
    for (let i = 0; i < 120; i++) { if (i === 50) { const id=[...host.sessions.keys()][0]; const update=host.keep(peer,id,{delayMs:180,rttMs:20}); if(update.bitrate!==2600000) throw Error('Adaptive bitrate failed'); } const frame = await viewer.next('fixture'); if (frame.codec !== 'h264' || frame.width !== 320 || !Buffer.from(frame.data, 'base64').subarray(0, 2).equals(Buffer.from([255,216]))) throw Error('Decoded frame invalid'); firstFrame ??= performance.now(); if (i >= 30) times.push(performance.now()); }
    const fps = 89 * 1000 / (times.at(-1) - times[0]); if (fps < 15) throw Error('Synthetic throughput below 15 fps: ' + fps);
    console.log(JSON.stringify({ result: 'PASS', encoder, adaptiveBitrate: '4.0 → 2.6 Mbps; decoder continued after encoder restart', decodedFrames: times.length, firstFrameMs: Math.round(firstFrame - start), decodedFps: Math.round(fps * 10) / 10, note: 'Loopback synthetic throughput, not input-to-display latency' }));
  } finally { await viewer.closeAll(); host.closeAll(); }
}
