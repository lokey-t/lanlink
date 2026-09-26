import test from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
import { randomBytes, randomUUID } from 'node:crypto';
import { EventEmitter } from 'node:events';
import { packFrame, unpackFrame, connectVideo, serverHandshake } from '../src/video-wire.js';
import { AnnexBParser, JpegParser } from '../src/video-codec.js';
import { VideoHost, VideoViewer } from '../src/video.js';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const fixture = { data: Buffer.from([255, 216, 1, 255, 217]), codec: 'jpeg', width: 640, height: 480, key: true, time: 100 };

test('binary media preserves frames, rejects tampering/replay/oversize and invalid dimensions', () => {
  const key = randomBytes(32), packet = packFrame(key, 0, fixture);
  assert.deepEqual(unpackFrame(key, 0, packet.subarray(4)), fixture);
  assert.throws(() => unpackFrame(key, 1, packet.subarray(4)), /序号/);
  const changed = Buffer.from(packet); changed[35] ^= 1; assert.throws(() => unpackFrame(key, 0, changed.subarray(4)));
  assert.throws(() => packFrame(key, 1, { ...fixture, width: 99999 }), /无效/);
  assert.throws(() => unpackFrame(key, 0, Buffer.alloc(4 * 1024 * 1024 + 1)), /长度/);
});

test('media channel authenticates both ends and handles fragmented binary frames', async t => {
  const secret = randomBytes(32); let serverSocket;
  const server = net.createServer(async socket => {
    serverSocket = socket; socket.on('error', () => {});
    const key = await serverHandshake(socket, secret), bytes = packFrame(key, 0, fixture);
    for (let i = 0; i < bytes.length; i += 3) socket.write(bytes.subarray(i, i + 3));
  });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  t.after(() => { serverSocket?.destroy(); server.close(); });
  const client = await connectVideo('127.0.0.1', { protocol: 1, port: server.address().port, key: secret.toString('hex') }); t.after(() => client.socket.destroy());
  assert.deepEqual(await client.next(), fixture);
});

test('wrong media session key cannot obtain a video frame', async t => {
  const server = net.createServer(async socket => { socket.on('error', () => {}); try { await serverHandshake(socket, randomBytes(32)); assert.fail('must not authenticate'); } catch { socket.destroy(); } });
  await new Promise(r => server.listen(0, '127.0.0.1', r)); t.after(() => server.close());
  await assert.rejects(connectVideo('127.0.0.1', { protocol: 1, port: server.address().port, key: randomBytes(32).toString('hex') }));
});

test('H.264 parser keeps SPS/PPS and IDR together across arbitrary chunk boundaries', () => {
  const frames = [], parser = new AnnexBParser((data, key) => frames.push({ data, key }));
  const first = Buffer.from([0,0,0,1,9,240,0,0,0,1,7,99,0,0,1,8,99,0,0,1,5,23]);
  const second = Buffer.from([0,0,0,1,9,240,0,0,1,1,24]);
  const bytes = Buffer.concat([first, second, second]);
  for (const byte of bytes) parser.push(Buffer.from([byte]));
  assert.equal(frames.length, 2); assert.deepEqual(frames[0], { data: first, key: true }); assert.deepEqual(frames[1], { data: second, key: false });
  const jpeg = []; const jp = new JpegParser(b => jpeg.push(b)); for (const byte of Buffer.concat([fixture.data, fixture.data])) jp.push(Buffer.from([byte])); assert.equal(jpeg.length, 2);
});

function fakeHost() {
  const peer = { remote: { id: randomUUID() } }; let allowed = false, emit, closed = 0;
  const service = { native: {}, options: { host: '127.0.0.1', videoSource: (_n, _h, callback) => { emit = callback; return { close() { closed++; } }; } }, authorize(p) { if (p !== peer || !allowed) throw Error('CONSENT_PENDING'); } };
  const host = new VideoHost(service);
  return { host, peer, service, allow() { allowed = true; }, deny() { allowed = false; }, emit(f = fixture) { emit(f); }, get closed() { return closed; } };
}

test('video host requires approval, rejects foreign keep/stop, and closes on revocation', async t => {
  const f = fakeHost(); t.after(() => f.host.closeAll());
  await assert.rejects(f.host.open(f.peer), /CONSENT/); assert.equal(f.host.sessions.size, 0);
  f.allow(); const info = await f.host.open(f.peer); const client = await connectVideo('127.0.0.1', info); t.after(() => client.socket.destroy());
  await sleep(10); f.emit(); assert.deepEqual(await client.next(), fixture);
  assert.throws(() => f.host.keep({ remote: f.peer.remote }, info.id)); f.host.stop({}, info.id); assert.equal(f.host.sessions.size, 1);
  f.deny(); f.emit(); await sleep(10); assert.equal(f.host.sessions.size, 0); assert.equal(f.closed, 1);
});

test('viewer consumes latest frame without a history queue and stopping releases capture', async t => {
  const f = fakeHost(); f.allow(); const control = new EventEmitter(); control.socket = { remoteAddress: '127.0.0.1' };
  control.request = (method, a) => Promise.resolve(method === 'videoOpen' ? f.host.open(f.peer, { h264: false }) : method === 'videoKeep' ? f.host.keep(f.peer, a.id) : f.host.stop(f.peer, a.id));
  const viewer = new VideoViewer({ getPeer: () => control }); t.after(async () => { await viewer.closeAll(); f.host.closeAll(); });
  await viewer.open('target', true); await sleep(10);
  for (let i = 0; i < 20; i++) f.emit({ ...fixture, data: Buffer.from('frame-' + i) });
  await sleep(50); const latest = await viewer.next('target'); assert.equal(Buffer.from(latest.data, 'base64').toString(), 'frame-19');
  const pending = viewer.next('target'); const rejection = assert.rejects(pending, /关闭/); await viewer.close('target'); await rejection; assert.equal(f.closed, 1); assert.equal(f.host.sessions.size, 0);
});

test('media lease expiry stops capture and stale negotiation cannot remain open', async t => {
  const f = fakeHost(); f.allow(); t.after(() => f.host.closeAll()); const info = await f.host.open(f.peer);
  f.host.sessions.get(info.id).expires = 0; await sleep(550); assert.equal(f.host.sessions.size, 0);
});

test('static video sends authenticated keepalive without resetting the image or requiring new captures', async t => {
  const f = fakeHost(); f.allow(); t.after(() => f.host.closeAll());
  const info = await f.host.open(f.peer); const client = await connectVideo('127.0.0.1', info); t.after(() => client.socket.destroy());
  const frame = await client.next(); assert.equal(frame.codec, 'ping'); assert.equal(frame.data.length, 0);
  f.emit(); assert.deepEqual(await client.next(), fixture);
});
