// Disposable emulator only: exercise real Root Surface/MediaCodec encoding, not Magisk consent.
import { spawn, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdir, writeFile } from 'node:fs/promises';
import { findAdb } from '../src/adb.js';
import { ffmpegPath, hasIdr } from '../src/video-codec.js';
const shellCapture = process.argv.includes('--adb');
const serialOption = process.argv.find(a => a.startsWith('--serial='));
const exec = promisify(execFile), adb = await findAdb(), serial = serialOption?.slice(9) || 'emulator-5580', ffmpeg = ffmpegPath();
if (!adb || !ffmpeg) throw Error('ADB and LANLINK_FFMPEG required');
const run = args => exec(adb, ['-s', serial, ...args], { windowsHide: true, timeout: 15000 });
const emulator = /ranchu|goldfish/.test((await run(['shell', 'getprop', 'ro.hardware'])).stdout);
if (!emulator && (!shellCapture || process.argv.includes('--rotate') || process.argv.includes('--apps'))) throw Error('Physical phone test supports ADB capture only, without settings/app changes');
const apk = (await run(['shell', 'pm', 'path', 'com.lanlink.app'])).stdout.trim().replace(/^package:/, '');
if (!/^\/data\/app\/[a-zA-Z0-9_./=+~-]+\/base\.apk$/.test(apk)) throw Error('Invalid APK path');
await run(['shell', 'input', 'keyevent', 'KEYCODE_WAKEUP']);
await run(['shell', 'am', 'start', '-n', 'com.lanlink.app/.MainActivity']);
const rotation = (await run(['shell', 'settings', 'get', 'system', 'user_rotation'])).stdout.trim();
const autoRotation = (await run(['shell', 'settings', 'get', 'system', 'accelerometer_rotation'])).stdout.trim();
if (!/^(null|[0-3])$/.test(rotation) || !/^(null|[01])$/.test(autoRotation)) throw Error('Unexpected emulator rotation settings');
const rotate = process.argv.includes('--rotate');
const apps = process.argv.includes('--apps');
const switches = apps ? 'am start -a android.settings.SETTINGS >/dev/null 2>&1; sleep 2; echo key; am start -n com.lanlink.app/.MainActivity >/dev/null 2>&1; sleep 2; echo key; am start -a android.settings.SETTINGS >/dev/null 2>&1; sleep 2; echo key;' : '';
const turns = switches + (rotate ? 'settings put system accelerometer_rotation 0; settings put system user_rotation 1; sleep 2; settings put system user_rotation 0;' : '');
const child = spawn(adb, ['-s', serial, 'exec-out', `${shellCapture ? '' : 'su 0 '}sh -c '{ sleep 2; echo "rate 800000"; sleep 1; echo key; ${turns} sleep 2; echo stop; } | CLASSPATH=${apk} app_process /system/bin com.lanlink.app.RootStream'`], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
const chunks = []; let size = 0, stderr = '';
child.stdout.on('data', chunk => { size += chunk.length; if (size > 64 * 1024 * 1024) child.kill(); else chunks.push(chunk); });
child.stderr.on('data', chunk => { stderr = (stderr + chunk).slice(-4000); });
const timer = setTimeout(() => child.kill(), 30000);
try {
  await new Promise((resolve, reject) => { child.on('error', reject); child.on('close', code => code === 0 ? resolve() : reject(Error(`Root stream failed (${code}): ${stderr}`))); });
  const data = Buffer.concat(chunks), frames = [];
  if (data.length < 4 || data.readUInt32BE(0) !== 0x4c525332) throw Error('Invalid header: ' + data.subarray(0, 200).toString());
  let offset = 4;
  while (offset < data.length) {
    if (offset + 16 > data.length) throw Error('Truncated frame header');
    const length = data.readInt32BE(offset), width = data.readInt32BE(offset + 4), height = data.readInt32BE(offset + 8), key = data.readInt32BE(offset + 12);
    if (length < 1 || length > 16 * 1024 * 1024 || width < 100 || height < 100 || width > 8192 || height > 8192 || ![0, 1].includes(key) || offset + 16 + length > data.length) throw Error(`Invalid frame at ${offset}: ${data.subarray(offset, offset + 300).toString()}`);
    const bytes = data.subarray(offset + 16, offset + 16 + length);
    if (key && !hasIdr(bytes)) throw Error('Key frame has no IDR');
    frames.push({ bytes, width, height, key }); offset += 16 + length;
  }
  if (frames.length < 10 || frames.filter(f => f.key).length < 2 || !frames.some(f => !f.key)) throw Error(`Insufficient continuous H.264 frames: ${frames.length}, keys=${frames.filter(f => f.key).length}, lengths=${frames.map(f => f.bytes.length)}`);
  if (rotate && !(frames.some(f => f.width > f.height) && frames.some(f => f.height > f.width))) throw Error('Rotation did not rebuild the encoder dimensions');
  await mkdir('.tools/root-stream-check', { recursive: true });
  await writeFile('.tools/root-stream-check/capture.h264', Buffer.concat(frames.map(f => f.bytes)));
  await exec(ffmpeg, ['-hide_banner', '-loglevel', 'error', '-xerror', '-f', 'h264', '-i', '.tools/root-stream-check/capture.h264', '-f', 'null', '-'], { windowsHide: true, timeout: 15000 });
  await exec(ffmpeg, ['-hide_banner', '-loglevel', 'error', '-y', '-f', 'h264', '-i', '.tools/root-stream-check/capture.h264', '-frames:v', '1', '.tools/root-stream-check/preview.png'], { windowsHide: true, timeout: 15000 });
  console.log(`PASS: ${shellCapture ? 'ADB' : 'Root'} Surface H.264 stream on ${serial}: ${frames.length} frames, ${frames[0].width} × ${frames[0].height}, ${frames.filter(f => f.key).length} IDRs; ${apps ? 'Settings/LanLink app switching; ' : ''}${rotate ? 'portrait/landscape rotation' : 'static-screen IDR recovery'}, rate/key commands, full decode and clean stop.`);
} finally {
  clearTimeout(timer); child.kill();
  if (rotate) for (const [key, value] of [['user_rotation', rotation], ['accelerometer_rotation', autoRotation]]) await run(['shell', 'settings', value === 'null' ? 'delete' : 'put', 'system', key, ...(value === 'null' ? [] : [value])]);
}
