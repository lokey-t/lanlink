import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { stopVideoProcess, createDecoder, chooseEncoder } from '../src/video-codec.js';
import { existsSync } from 'node:fs';
import { mkdtemp, rm, writeFile, readFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { VideoViewer, captureVideo } from '../src/video.js';

test('owned codec workers stop even when SIGTERM is ignored; repeated close shares completion', { timeout: 5000 }, async t => {
  const child = spawn(process.execPath, ['-e', "process.on('SIGTERM',()=>{});process.stdin.resume();setInterval(()=>{},100);process.stdout.write('ready');"], { stdio: ['pipe', 'pipe', 'pipe'] });
  t.after(() => { if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL'); });
  await once(child.stdout, 'data');
  const first = stopVideoProcess(child), second = stopVideoProcess(child);
  assert.equal(first, second); await first;
  assert.equal(child.stdin.destroyed, true); assert.equal(child.stdout.destroyed, true);
  assert.ok(child.exitCode !== null || child.signalCode !== null);
  if (process.platform !== 'win32') assert.equal(child.signalCode, 'SIGKILL');
});

const ffmpeg = process.env.LANLINK_FFMPEG || path.resolve('.tools/video-macos-dist/bin/ffmpeg');
test('actual idle FFmpeg decoder closes its input and exits without leaving a worker', { skip: !existsSync(ffmpeg), timeout: 5000 }, async () => {
  const decoder = createDecoder(ffmpeg, () => assert.fail('closed decoder must not publish'), () => {});
  const first = decoder.close(); assert.equal(decoder.close(), first); await first;
  assert.equal(decoder.push({ key: true, data: Buffer.from([0, 0, 1, 9]) }), false);
});

test('service shutdown drains active and retired codecs before its parent exits immediately', { timeout: 10000 }, async t => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'lanlink-codec-exit-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const probePath = path.join(dir, 'probe.cjs'), probePids = path.join(dir, 'probe-pids');
  const testProbe = process.platform !== 'win32';
  if (testProbe) await writeFile(probePath, `#!${process.execPath}\nrequire('node:fs').writeFileSync(${JSON.stringify(probePids)}, String(process.pid));process.on('SIGTERM',()=>{});setInterval(()=>{},100);`, { mode: 0o700 });
  const code = `
    import { spawn } from 'node:child_process';
    import { once } from 'node:events';
    import { Service } from ${JSON.stringify(new URL('../src/service.js', import.meta.url).href)};
    import { NativeBridge } from ${JSON.stringify(new URL('../src/native.js', import.meta.url).href)};
    import { loadConfig } from ${JSON.stringify(new URL('../src/identity.js', import.meta.url).href)};
    import { stopVideoProcess } from ${JSON.stringify(new URL('../src/video-codec.js', import.meta.url).href)};
    import { captureVideo } from ${JSON.stringify(new URL('../src/video.js', import.meta.url).href)};
    import { readFile } from 'node:fs/promises';
    const service = new Service(await loadConfig(process.argv[1]), process.argv[1], new NativeBridge({ disabled: true }));
    const workers = await Promise.all(Array.from({ length: 4 }, async () => {
      const child = spawn(process.execPath, ['-e', "process.on('SIGTERM',()=>{});process.stdin.resume();setInterval(()=>{},100);process.stdout.write('ready');"], { stdio: ['pipe', 'pipe', 'pipe'] });
      await once(child.stdout, 'data');
      return child;
    }));
    const pids = workers.map(child => child.pid);
    if (${testProbe}) {
      const source = captureVideo({ capabilities: {} }, true, () => {}, () => {});
      service.videoHost.sessions.set('probing', { source, sockets: new Set() });
      for (let attempt = 0; attempt < 100; attempt++) {
        try { pids.push(Number(await readFile(${JSON.stringify(probePids)}, 'utf8'))); break; }
        catch { await new Promise(resolve => setTimeout(resolve, 10)); }
      }
      if (pids.length !== 5) throw Error('probe did not start');
    }
    process.stdout.write(JSON.stringify(pids) + '\\n');
    const codec = child => ({ close: () => stopVideoProcess(child) });
    service.videoHost.sessions.set('active', { source: codec(workers[0]), sockets: new Set() });
    service.videoHost.sessions.set('retired', { source: codec(workers[1]), sockets: new Set() });
    service.videoHost.close('retired');
    const viewer = child => ({ decoder: codec(child), peer: { request: async () => ({}) }, info: { id: 'stream' }, abort: new AbortController() });
    service.videoViewer.sessions.set('active', viewer(workers[2]));
    const retired = viewer(workers[3]);
    service.videoViewer.fail(retired, Error('decoder failed before shutdown'));
    await service.close();
    process.exit(0);
  `;
  const parent = spawn(process.execPath, ['--input-type=module', '-e', code, dir], { stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, ...(testProbe ? { LANLINK_FFMPEG: probePath } : {}) } });
  let pids = [], output = '', errors = '';
  parent.stdout.on('data', data => { output += data; if (output.includes('\n')) pids = JSON.parse(output.trim()); });
  parent.stderr.on('data', data => { errors += data; });
  t.after(() => { parent.kill('SIGKILL'); for (const pid of pids) try { process.kill(pid, 'SIGKILL'); } catch {} });
  const [exitCode, signal] = await once(parent, 'close');
  assert.equal(exitCode, 0, `${signal || ''} ${errors}`);
  assert.equal(pids.length, testProbe ? 5 : 4, output);
  for (const pid of pids) assert.throws(() => process.kill(pid, 0), { code: 'ESRCH' }, `codec ${pid} outlived the service parent`);
});

test('shared encoder probe stops only after every capture cancels and a later stream can retry', { skip: process.platform === 'win32', timeout: 10000 }, async t => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'lanlink-probe-cancel-')), exe = path.join(dir, 'probe.cjs'), pidFile = path.join(dir, 'pids');
  const pids = [];
  t.after(async () => { for (const pid of pids) try { process.kill(pid, 'SIGKILL'); } catch {} await rm(dir, { recursive: true, force: true }); });
  await writeFile(exe, `#!${process.execPath}\nprocess.on('SIGTERM',()=>{});require('node:fs').appendFileSync(${JSON.stringify(pidFile)},process.pid+'\\n');setInterval(()=>{},100);`, { mode: 0o700 });
  const waitPid = async count => {
    for (let attempt = 0; attempt < 100; attempt++) {
      try { const found = (await readFile(pidFile, 'utf8')).trim().split('\n').map(Number); if (found.length >= count) { pids.splice(0, pids.length, ...found); return found.at(-1); } } catch {}
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    assert.fail('probe did not start');
  };
  const first = new AbortController(), second = new AbortController();
  const a = chooseEncoder(exe, first.signal), b = chooseEncoder(exe, second.signal);
  const pid = await waitPid(1);
  first.abort(); process.kill(pid, 0);
  second.abort(); assert.deepEqual(await Promise.all([a, b]), [null, null]);
  assert.throws(() => process.kill(pid, 0), { code: 'ESRCH' });
  const third = new AbortController(), c = chooseEncoder(exe, third.signal);
  const nextPid = await waitPid(2);
  third.abort(); assert.equal(await c, null);
  assert.throws(() => process.kill(nextPid, 0), { code: 'ESRCH' });
  assert.equal((await readFile(pidFile, 'utf8')).trim().split('\n').length, 2, 'cancelled probes must not try another encoder');
});

test('capture close also drains the native screen request already in flight', async () => {
  let finishFrame, emitted = false, closed = false;
  const source = captureVideo({ capabilities: {}, call: () => new Promise(resolve => { finishFrame = resolve; }) }, false, () => { emitted = true; }, () => {});
  const closing = source.close(); assert.equal(source.close(), closing);
  closing.then(() => { closed = true; });
  await new Promise(resolve => setImmediate(resolve)); assert.equal(closed, false);
  finishFrame({ data: '', width: 1, height: 1 }); await closing;
  assert.equal(emitted, false); assert.equal(closed, true);
});

test('a frame already in flight when the viewer closes cannot restart decoding', async () => {
  let arrive;
  const rtc = { next: () => new Promise(resolve => { arrive = resolve; }), close() {} };
  const peer = { quicConn: 1, request: async (method, args) => method === 'videoOpen' ? { id: 'stream', quic: args.quic } : {} };
  const viewer = new VideoViewer({ getPeer: () => peer, quic: { video: () => rtc } });
  await viewer.open('peer');
  const session = viewer.sessions.get('peer');
  let received = 0;
  session.feedback.receive = () => { received++; };
  await viewer.closeAll();
  arrive({ codec: 'h264', width: 640, height: 480, key: true, data: Buffer.alloc(0) });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(received, 0);
  assert.equal(session.decoder, undefined);
});
