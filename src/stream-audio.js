import net from 'node:net';
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { connectVideo, packFrame, PCM_PACKET_BYTES, serverHandshake } from './video-wire.js';
import { stopVideoProcess } from './video-codec.js';

const helper = fileURLToPath(new URL('../native/windows/audio.ps1', import.meta.url));
const macBridge = fileURLToPath(new URL('../native/macos/LanLinkBridge', import.meta.url));
export const audioAvailable = () => process.platform === 'win32' ? existsSync(helper)
  : process.platform === 'darwin' && existsSync(macBridge);
function audioProcess(capture) {
  const stdio = capture ? ['ignore', 'pipe', 'ignore'] : ['pipe', 'ignore', 'ignore'];
  if (process.platform === 'darwin') return spawn(macBridge, [capture ? '--audio-capture' : '--audio-play'], { stdio });
  return spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
    '-File', helper, ...(capture ? ['-Capture'] : [])],
    { windowsHide: true, stdio });
}

export async function serveAudio(host, authorize) {
  const secret = randomBytes(32), sockets = new Set();
  let active, closed = false;
  const server = net.createServer(async socket => {
    if (closed || sockets.size >= 2 || active) { socket.destroy(); return; }
    sockets.add(socket);
    socket.setNoDelay(true);
    socket.setTimeout(5000, () => socket.destroy());
    socket.on('error', () => {});
    let capture, heartbeat;
    socket.once('close', () => {
      sockets.delete(socket);
      if (active === socket) active = null;
      clearInterval(heartbeat);
      void stopVideoProcess(capture);
    });
    try {
      const key = await serverHandshake(socket, secret);
      authorize();
      if (closed || active || socket.destroyed) { socket.destroy(); return; }
      active = socket;
      socket.setTimeout(0);
      let sequence = 0, pending = Buffer.alloc(0);
      const send = (codec, data) => {
        if (socket.destroyed || socket.writableLength >= PCM_PACKET_BYTES * 3) return;
        socket.write(packFrame(key, sequence++, { codec, data, width: 1, height: 1 }));
      };
      heartbeat = setInterval(() => {
        try { authorize(); send('ping', Buffer.alloc(0)); } catch { socket.destroy(); }
      }, 1000);
      capture = audioProcess(true);
      capture.once('error', () => socket.destroy());
      capture.once('exit', () => socket.destroy());
      capture.stdout.on('data', chunk => {
        try {
          authorize();
          pending = Buffer.concat([pending, chunk]);
          while (pending.length >= PCM_PACKET_BYTES) {
            send('pcm', pending.subarray(0, PCM_PACKET_BYTES));
            pending = pending.subarray(PCM_PACKET_BYTES);
          }
        } catch { socket.destroy(); }
      });
    } catch { socket.destroy(); }
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, host, resolve);
  });
  return {
    info: { protocol: 1, port: server.address().port, key: secret.toString('hex') },
    close() {
      closed = true;
      for (const socket of sockets) socket.destroy();
      server.close();
    },
  };
}

export async function playAudio(routes, signal) {
  let attempt = 0;
  while (!signal.aborted) {
    let connection, player;
    try {
      const route = routes[attempt++ % routes.length];
      connection = await connectVideo(route.host, route.info, signal, true);
      if (signal.aborted) break;
      player = audioProcess(false);
      const stop = () => connection.socket.destroy();
      player.once('error', stop);
      player.once('exit', stop);
      player.stdin.on('error', stop);
      for (;;) {
        const frame = await connection.next();
        if (frame.codec === 'ping') continue;
        if (frame.codec !== 'pcm') break;
        if (player.stdin.writableLength < PCM_PACKET_BYTES * 3) player.stdin.write(frame.data);
      }
    } catch { /* The video session can continue while audio reconnects. */ }
    finally {
      connection?.socket.destroy();
      await stopVideoProcess(player);
    }
    try { await delay(1500, undefined, { signal }); } catch { break; }
  }
}
