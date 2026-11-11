// npm run package:update -- release-input.json output-directory
import {readFile,writeFile,mkdir,readdir} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import path from 'node:path';
import {gzipSync} from 'node:zlib';
import {snapshot,delta,hash,newer} from '../src/update-package.js';
import {buildApkDelta} from './build-apk-delta.js';

const input = JSON.parse(await readFile(process.argv[2], 'utf8'));
const out = path.resolve(process.argv[3]);
const {version, previousVersion, targets} = input;
if (!newer(version, previousVersion)) throw Error('The release version must be newer than previousVersion');
const entries = Object.entries(targets ?? {});
if (!entries.length) throw Error('No release targets');
const partial = process.argv[4] === '--partial';
if (!partial && (!targets.android || !entries.some(([target]) => target.startsWith('win32-')) ||
    !entries.some(([target]) => target.startsWith('darwin-')))) {
 throw Error('A release must include Windows, macOS and Android targets');
}
const manifest = {format:1, version, targets:{}};
const assetLimit = 512 * 1024 * 1024;

async function asset(name, bytes) {
 if (!bytes.length || bytes.length > assetLimit) throw Error('Unsupported asset size: ' + name);
 await writeFile(path.join(out, name), bytes, {flag:'wx'});
 return {name, sha256:hash(bytes), size:bytes.length};
}

async function inspectApks(spec) {
 const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
 if (!sdk) throw Error('Set ANDROID_HOME to verify the two signed release APKs');
 const tools = path.join(sdk, 'build-tools');
 const versions = await readdir(tools);
 const buildTools = versions.filter(name => /^\d+\.\d+\.\d+$/.test(name))
  .sort((a,b) => newer(a,b) ? -1 : newer(b,a) ? 1 : 0)[0];
 if (!buildTools) throw Error('Android SDK Build Tools are required');
 const dir = path.join(tools, buildTools);
 const suffix = process.platform === 'win32' ? '.exe' : '';
 const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME,'bin','java'+suffix) : 'java';
 function inspect(file, expectedVersion) {
  const badging = execFileSync(path.join(dir, 'aapt'+suffix), ['dump','badging',file], {encoding:'utf8'});
  const info = /^package: name='com\.lanlink\.app' versionCode='(\d+)' versionName='([^']+)'/m.exec(badging);
  if (info?.[2] !== expectedVersion) throw Error('Unexpected Android package or version in ' + file);
  const minSdk = Number(/^sdkVersion:'(\d+)'/m.exec(badging)?.[1]);
  if (!minSdk) throw Error('Cannot read Android minSdk for ' + file);
  const abiLine = /^native-code: (.+)$/m.exec(badging)?.[1] ?? '';
  const abis = [...abiLine.matchAll(/'([^']+)'/g)].map(match => match[1]);
  const signatures = execFileSync(java, ['-jar',path.join(dir,'lib','apksigner.jar'),'verify','--print-certs',file], {encoding:'utf8'});
  const signers = [...signatures.matchAll(/^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})\r?$/gm)]
   .map(match => match[1]).sort();
  if (!signers.length) throw Error('Cannot read APK signing certificates for ' + file);
  return {code:BigInt(info[1]), minSdk, abis, signers:signers.join(',')};
 }
 const previous = inspect(spec.previous, previousVersion), current = inspect(spec.current, version);
 if (current.code <= previous.code) throw Error('Android versionCode must increase');
 if (current.signers !== previous.signers) throw Error('Android release signing certificates must match the previous release');
 if (current.minSdk > previous.minSdk || previous.abis.some(abi => !current.abis.includes(abi))) {
  throw Error('The Android APK must support the previous release\'s SDK and ABIs');
 }
}

for (const [target, spec] of entries) {
 if (!/^(android|(?:win32|darwin)-(?:x64|arm64))$/.test(target)) throw Error('Unsupported target: ' + target);
 if (spec.from !== undefined && spec.from !== previousVersion) throw Error('Wrong base version for ' + target);
 if (target === 'android') {
  await inspectApks(spec);
  continue;
 }
 const identity = target.startsWith('darwin') ? 'Contents/Resources/build-info.json' : 'build-info.json';
 for (const [dir, expected] of [[spec.previous, previousVersion], [spec.current, version]]) {
  const info = JSON.parse(await readFile(path.join(dir, identity), 'utf8'));
  if (info.version !== expected) throw Error('Unexpected build version in ' + dir + ', expected ' + expected);
 }
}

await mkdir(path.dirname(out), {recursive:true});
await mkdir(out);
for (const [target, spec] of entries) {
 const prefix = `LanLink-${version}-${target}`;
 let full, patch;
 if (target === 'android') {
  const previous = await readFile(spec.previous), current = await readFile(spec.current);
  if (previous.length > assetLimit || current.length > assetLimit) throw Error('Unsupported APK size');
  full = await asset(prefix+'.apk', current);
  patch = await asset(prefix+'.delta.apk.gz', buildApkDelta(previous, current));
 } else {
  const current = await snapshot(spec.current), previous = await snapshot(spec.previous);
  const files = Object.create(null), changes = Object.create(null);
  for (const [name,file] of Object.entries(current)) {
   if (file.link !== undefined) {
    files[name] = changes[name] = {link:file.link};
    continue;
   }
   const before = previous[name]?.bytes;
   files[name] = {sha256:hash(file.bytes), mode:file.mode, ops:[file.bytes.toString('base64')]};
   changes[name] = before ? {...files[name], base:hash(before), ops:delta(before, file.bytes)} : files[name];
  }
  full = await asset(prefix+'.full.json.gz', gzipSync(JSON.stringify({format:1, version, files})));
  const payload = {format:1, version, targets:{[target]:{from:previousVersion, files:changes}}};
  patch = await asset(prefix+'.delta.json.gz', gzipSync(JSON.stringify(payload)));
 }
 manifest.targets[target] = {deltaFrom:previousVersion, delta:patch, full};
}
await writeFile(path.join(out,'lanlink-update.json'), JSON.stringify(manifest,null,2)+'\n', {flag:'wx'});
console.log('Publish lanlink-update.json and every listed target asset to both Releases.');
