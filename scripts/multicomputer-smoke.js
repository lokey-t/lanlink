// Disposable emulator: two real Node services share one Android helper and video encoder.
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { EventEmitter } from 'node:events';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { loadConfig, newToken } from '../src/identity.js';
import { Service } from '../src/service.js';
import { findAdb } from '../src/adb.js';
import { connectVideo } from '../src/video-wire.js';
import { createDecoder, ffmpegPath } from '../src/video-codec.js';
import { RtcVideo } from '../src/rtc-video.js';

const execute = promisify(execFile), serial = process.env.LANLINK_SERIAL || 'emulator-5554';
const adb = await findAdb();
const command = args => execute(adb, ['-s', serial, ...args], { timeout: 150000, maxBuffer: 1024 * 1024 });
assert.match((await command(['shell', 'getprop', 'ro.hardware'])).stdout, /ranchu|goldfish/, 'only a disposable emulator may be targeted');
class LocalNative extends EventEmitter {
  capabilities = { input: false, screen: false, host: false };
  async start() {} async call() { return {}; } close() {}
}
const token = newToken(), directories = [], computers = [], streams = [];
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
let instrument, peers;
try {
  for (const name of ['Desktop A', 'Desktop B']) {
    const dir = await mkdtemp(path.join(os.tmpdir(), 'lanlink-multicomputer-')); directories.push(dir);
    const config = await loadConfig(dir); config.token = token; config.name = name;
    const computer = new Service(config, dir, new LocalNative(), { host: '0.0.0.0', port: 0, noDiscovery: true, noQuic: true, noRtc: true });
    computer.links.tick = async () => {}; // Transport setup belongs to this emulator harness.
    computers.push(computer); await computer.start();
  }
  const [a, b] = computers;
  instrument = command(['shell', 'am', 'instrument', '-w', '-e', 'multiComputerPorts', computers.map(s => s.port).join(','), '-e', 'multiComputerToken', token, 'com.lanlink.app.test/com.lanlink.app.PermissionFlowTest']).then(r => r.stdout);
  const until = Date.now() + 20000;
  while (computers.some(s => ![...s.peers.values()].some(p => p.info?.platform === 'android'))) {
    assert.ok(Date.now() < until, 'both actual desktop services receive the Android connection'); await pause(100);
  }
  peers = computers.map(s => [...s.peers.values()].find(p => p.info?.platform === 'android'));
  const [pa, pb] = peers, id = pa.remote.id;
  assert.equal(pb.remote.id, id); assert.notEqual(a.config.id, b.config.id);
  await a.adb.authorize({ id, serial });
  b.adb.locate = async () => { throw Error('second computer must not access ADB or USB'); };
  assert.equal((await b.adb.match(id)).ready, true);
  assert.equal((await b.adb.authorize({ id })).mode, 'adb/root');
  assert.equal(b.config.adbBindings?.[id], undefined);
  for (const peer of peers) {
    const report = await peer.request('permission-status');
    assert.equal(report.engine.ready, true); assert.equal(report.permissions.input.peerAllowed, true); assert.equal(report.permissions.screen.peerAllowed, true);
    assert.equal((await peer.request('probe-counts')).consentPending, false);
  }
  console.log('PASS: two authenticated computer identities reuse the same live Android helper; the second never locates ADB/USB and no first-use prompt appears.');
  await pa.request('acquire', { newSession: true, pointer: false });
  await assert.rejects(pb.request('acquire', { newSession: true, pointer: false }), /CONTROL_CONFLICT/);
  await pa.request('input', { kind: 'key', code: 'KeyA', down: true });
  await pa.request('input', { kind: 'key', code: 'KeyA', down: false });
  await pa.request('release');
  await pb.request('acquire', { newSession: true, pointer: false });
  await pb.request('input', { kind: 'key', code: 'KeyA', down: true });
  await pb.request('input', { kind: 'key', code: 'KeyA', down: false });
  await pb.request('release');
  await pause(150);
  assert.ok((await pb.request('probe-counts')).keys >= 4, 'both computers delivered real Android key events');
  await pa.request('probe-permission', { id: b.config.id, key: 'input', enabled: false });
  await assert.rejects(pb.request('acquire', { newSession: true, pointer: false }), /CONSENT_DENIED/);
  assert.equal((await pa.request('permission-status')).permissions.input.peerAllowed, true);
  await pa.request('acquire', { newSession: true, pointer: false }); await pa.request('release');
  await pa.request('probe-permission', { id: b.config.id, key: 'input', enabled: true });
  console.log('PASS: exclusive input ownership, actual keyboard events from both computers and per-computer explicit denial remain isolated.');
  for (const peer of peers) {
    if (peer === pb) {
      const rtc = new RtcVideo();
      try {
        const offer = await rtc.offer();
        await pa.request('acquire', { newSession: true, pointer: false });
        const negotiation = pb.request('videoOpen', { h264: true, rtc: offer }, 15000);
        await pause(30);
        const started = performance.now();
        await pa.request('keepControl', {}, 1500);
        const elapsed = performance.now() - started;
        assert.ok(elapsed < 1200, `another viewer's RTC negotiation must not freeze control (${Math.round(elapsed)} ms)`);
        const pending = await negotiation;
        await pb.request('videoStop', { id: pending.id });
        await pa.request('release');
        console.log(`PASS: another computer's real RTC negotiation leaves existing input responsive (${Math.round(elapsed)} ms).`);
      } finally { rtc.close(); }
    }
    const info = await peer.request('videoOpen', { h264: true });
    // Emulator networking needs harness port forwarding; no new ADB authorization is performed.
    const tunnel = await a.links.forward(serial, info.port);
    const wire = await connectVideo('127.0.0.1', { ...info, port: tunnel.port });
    const stream = { peer, info, wire, tunnel, frames: 0, decoded: 0, stopped: false };
    const exe = ffmpegPath(); assert.ok(exe, 'bundled video decoder exists');
    stream.decoder = createDecoder(exe, () => stream.decoded++, error => { stream.failure ??= error; });
    stream.timer = setInterval(() => peer.request('videoKeep', { id: info.id }, 3000).catch(error => { stream.failure ??= error; }), 1000);
    stream.reading = (async () => {
      try { while (!stream.stopped) {
        const frame = await wire.next(); if (frame.codec === 'ping') continue;
        assert.equal(frame.codec, 'h264'); assert.ok(frame.width >= 100 && frame.height >= 100);
        stream.lastTime = frame.time; stream.frames++; stream.decoder.push(frame);
      } } catch (error) { if (!stream.stopped) stream.failure ??= error; }
    })();
    streams.push(stream);
  }
  const deadline = Date.now() + 20000;
  while (streams.some(stream => stream.decoded < 3)) {
    for (const stream of streams) if (stream.failure) throw Error(`viewer ${streams.indexOf(stream)} ended after ${stream.frames} encoded / ${stream.decoded} decoded frames: ${stream.failure.message}`, { cause: stream.failure });
    assert.ok(Date.now() < deadline, 'both viewers decode live Android H.264 frames'); await pause(100);
  }
  const stop = async stream => {
    stream.stopped = true; clearInterval(stream.timer); stream.wire.socket.destroy(); stream.decoder.close();
    await stream.peer.request('videoStop', { id: stream.info.id }); await stream.tunnel.close(); await stream.reading;
  };
  await assert.rejects(pb.request('videoKeep', { id: streams[0].info.id }), /串流已结束/);
  await stop(streams[0]);
  const before = streams[1].decoded, continued = Date.now() + 10000;
  while (streams[1].decoded < before + 5) {
    if (streams[1].failure) throw streams[1].failure;
    assert.ok(Date.now() < continued, 'closing the first viewer leaves the second decoding fresh frames'); await pause(100);
  }
  console.log(`PASS: both computers decoded actual Android H.264 frames (${streams.map(s => s.decoded).join(', ')}); closing one viewer preserved the other, and stream IDs are isolated.`);
  await stop(streams[1]);
  await pa.request('probe-done');
  const report = await instrument; assert.match(report, /PASS:/); assert.doesNotMatch(report, /FAIL:|INSTRUMENTATION_FAILED/);
  console.log('PASS: Android instrumentation completed and restored fixture preferences.');
} finally {
  for (const stream of streams) {
    stream.stopped = true; clearInterval(stream.timer); stream.wire?.socket.destroy(); stream.decoder?.close(); await stream.tunnel?.close();
  }
  if (peers?.[0] && !peers[0].closed) await peers[0].request('probe-done').catch(() => {});
  await Promise.all(computers.map(s => s.close()));
  if (instrument) await instrument.catch(() => {});
  await Promise.all(directories.map(dir => rm(dir, { recursive: true, force: true })));
}
