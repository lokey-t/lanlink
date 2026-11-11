import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,writeFile,readFile,mkdir,rm,copyFile} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {randomBytes} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {setTimeout as delay} from 'node:timers/promises';
import {gunzipSync,gzipSync} from 'node:zlib';
import {delta,hash,reconstruct,newer,safePath} from '../src/update-package.js';
import {buildApkDelta} from '../scripts/build-apk-delta.js';
import {Updater,assetBytes,release,prepareUpdate,verifyMacUpdate} from '../src/updater.js';
const fullAsset = {name:'full', size:16, sha256:'a'.repeat(64)};
const fullLink = {name:'full',browser_download_url:'https://example.com/full'};
const desktopManifest = version => ({format:1,version,targets:{'win32-x64':{full:fullAsset}}});
test('binary patch validates base, target, ranges, numeric versions and paths',()=>{
 const base=Buffer.alloc(200000,12),next=Buffer.concat([base,Buffer.from('changed')]);const entry={base:hash(base),sha256:hash(next),ops:delta(base,next)};
 assert.deepEqual(reconstruct(base,entry),next);assert.throws(()=>reconstruct(Buffer.from('bad'),entry));assert.throws(()=>reconstruct(base,{ops:[[999999,2]],sha256:hash(next)}));assert.throws(()=>reconstruct(base,{...entry,sha256:'0'.repeat(64)}));
 assert(newer('0.3.100','0.3.9'));assert(!newer('0.3.9','0.3.9'));assert(!newer('0.3.9-beta','0.3.8'));
 for(const name of ['../x','/x','a/../x','C:/x','a\\x'])assert.throws(()=>safePath(os.tmpdir(),name));
});
test('release generator builds isolated target deltas and retains a compatible full package', async () => {
 const dir = await mkdtemp(path.join(os.tmpdir(),'lanlink-update-test-'));
 const original = globalThis.fetch;
 try {
  const previous = path.join(dir,'old'), current = path.join(dir,'new'), out = path.join(dir,'out');
  const version = '0.3.99', previousVersion = '0.3.98';
  for (const [root, number] of [[previous,previousVersion],[current,version]]) {
   await mkdir(path.join(root,'Contents/Resources'),{recursive:true});
   for (const file of ['build-info.json','Contents/Resources/build-info.json']) {
    await writeFile(path.join(root,file),JSON.stringify({version:number}));
   }
  }
  const base = Buffer.alloc(131072,7), next = Buffer.concat([base,Buffer.from('new')]);
  await writeFile(path.join(previous,'app'),base);
  await writeFile(path.join(previous,'deleted'),'gone');
  await writeFile(path.join(current,'app'),next);
  const targets = {'win32-x64':{previous,current},'darwin-arm64':{previous,current}};
  const input = path.join(dir,'input.json');
  await writeFile(input,JSON.stringify({version,previousVersion,targets}));
  const args = ['scripts/build-update.js',input,out];
  assert.match(spawnSync(process.execPath,args,{encoding:'utf8'}).stderr,/must include Windows, macOS and Android/);
  const build = spawnSync(process.execPath,[...args,'--partial'],{encoding:'utf8'});
  assert.equal(build.status,0,build.stderr);
  const manifest = JSON.parse(await readFile(path.join(out,'lanlink-update.json')));
  assert.equal(manifest.format,1);
  assert.equal(manifest.delta,undefined);
  for (const [target,{delta,full,deltaFrom}] of Object.entries(manifest.targets)) {
   assert.equal(deltaFrom,previousVersion);
   const bytes = await readFile(path.join(out,delta.name));
   assert.equal(hash(bytes),delta.sha256);
   assert.deepEqual(Object.keys(JSON.parse(gunzipSync(bytes)).targets),[target]);
   const downloaded = [];
   const releases = [{assets:[delta,full].map(({name})=>({name,browser_download_url:'https://example.com/'+name}))}];
   globalThis.fetch = async url => {
    const name = path.basename(url.pathname);
    downloaded.push(name);
    return new Response(await readFile(path.join(out,name)));
   };
   for (const [baseVersion, asset] of [[previousVersion,delta],['0.3.97',full]]) {
    const stage = path.join(dir,target+'-'+baseVersion);
    await prepareUpdate({root:previous,stage,target,version:baseVersion,manifest,releases});
    assert.deepEqual(await readFile(path.join(stage,'app')),next);
    await assert.rejects(readFile(path.join(stage,'deleted')));
    assert.deepEqual(downloaded.splice(0),[asset.name]);
   }
  }
 } finally {
  globalThis.fetch = original;
  await rm(dir,{recursive:true,force:true});
 }
});

const jdkTool = name => process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin',
 name + (process.platform === 'win32' ? '.exe' : '')) : name;

test('Android applies the generated delta and rejects damaged or mismatched APKs',
 {skip:spawnSync(jdkTool('javac'),['-version']).status !== 0}, async () => {
 const dir = await mkdtemp(path.join(os.tmpdir(),'lanlink-apk-delta-'));
 try {
  const base = randomBytes(256*1024);
  const next = Buffer.concat([base.subarray(0,21000),Buffer.from('shift APK entries'),base.subarray(21000)]);
  const patch = buildApkDelta(base,next);
  assert(patch.length < next.length / 3);
  const runner = path.join(dir,'ApplyDelta.java');
  await writeFile(runner,`
package com.lanlink.app;
import java.io.File;
public class ApplyDelta {
  public static void main(String[] args) throws Exception {
    ApkDelta.apply(new File(args[0]), new File(args[1]), new File(args[2]), Long.parseLong(args[3]), args[4]);
  }
}
`);
  const compiled = spawnSync(jdkTool('javac'),['-d',dir,runner,
   'android/app/src/main/java/com/lanlink/app/ApkDelta.java'],{encoding:'utf8'});
  assert.equal(compiled.status,0,compiled.stderr);
  const patchFile = path.join(dir,'patch.gz'), baseFile = path.join(dir,'base.apk'), output = path.join(dir,'out.apk');
  await writeFile(patchFile,patch);
  await writeFile(baseFile,base);
  const apply = (sha = hash(next)) => spawnSync(jdkTool('java'),['-cp',dir,'com.lanlink.app.ApplyDelta',
   patchFile,baseFile,output,String(next.length),sha],{encoding:'utf8'});
  const applied = apply();
  assert.equal(applied.status,0,applied.stderr);
  assert.deepEqual(await readFile(output),next);
  assert.match(apply('0'.repeat(64)).stderr,/Reconstructed APK checksum mismatch/);
  await writeFile(patchFile,patch.subarray(0,-5));
  assert.equal(apply().status,1);
  await writeFile(patchFile,patch);
  base[0] ^= 1;
  await writeFile(baseFile,base);
  assert.match(apply().stderr,/Installed APK checksum mismatch/);
 } finally {
  await rm(dir,{recursive:true,force:true});
 }
});
test('mirror failure retries, asset corruption is rejected, disabled automatic checks are quiet',async()=>{
 const original=globalThis.fetch;let calls=0;try{globalThis.fetch=async url=>{calls++;if(String(url).includes('github'))throw Error('offline');if(String(url).includes('manifest'))return new Response(JSON.stringify(desktopManifest('0.3.99')));return new Response(JSON.stringify({tag_name:'v0.3.99',assets:[{name:'lanlink-update.json',browser_download_url:'https://example.com/manifest'},fullLink]}));};assert.equal((await release('stable','win32-x64')).manifest.version,'0.3.99');assert.equal(calls,3);
 globalThis.fetch=async()=>new Response('broken');await assert.rejects(assetBytes([{assets:[{name:'patch',browser_download_url:'https://example.com/patch'}]}],{name:'patch',size:6,sha256:hash(Buffer.from('actual'))}));
 const updater=new Updater({config:{autoCheckUpdates:false}});let checked=false;updater.check=()=>{checked=true;return Promise.resolve();};updater.automatic();assert.equal(checked,false);
 }finally{globalThis.fetch=original;}
});
test('broken delta automatically downloads full tree, failed full leaves original untouched',async()=>{
 const dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-fallback-')),original=globalThis.fetch;
 try{const root=path.join(dir,'installed'),stage=path.join(dir,'stage');await mkdir(root);await mkdir(stage);await writeFile(path.join(root,'app'),'old');
 const full=gzipSync(JSON.stringify({format:1,version:'0.4.0',files:{app:{sha256:hash(Buffer.from('new')),ops:[Buffer.from('new').toString('base64')]}}}));const bad=Buffer.from('broken');
 const manifest={version:'0.4.0',delta:{name:'delta',size:bad.length,sha256:hash(bad)},targets:{'win32-x64':{full:{name:'full',size:full.length,sha256:hash(full)}}}};
 const releases=[{assets:[{name:'delta',browser_download_url:'https://example.com/delta'},{name:'full',browser_download_url:'https://example.com/full'}]}];let fallback=false;
 globalThis.fetch=async url=>new Response(String(url).endsWith('delta')?bad:full);
 await prepareUpdate({root,stage,target:'win32-x64',version:'0.3.0',manifest,releases,onFallback:()=>{fallback=true;}});assert(fallback);assert.equal(await readFile(path.join(stage,'app'),'utf8'),'new');assert.equal(await readFile(path.join(root,'app'),'utf8'),'old');
 globalThis.fetch=async()=>new Response('invalid');await assert.rejects(prepareUpdate({root,stage,target:'win32-x64',version:'0.3.0',manifest,releases}));assert.equal(await readFile(path.join(root,'app'),'utf8'),'old');
 }finally{globalThis.fetch=original;await rm(dir,{recursive:true,force:true});}
});

test('downloads stream to disk, report speed, and reset bytes on mirror retry', async () => {
 const dir = await mkdtemp(path.join(os.tmpdir(), 'lanlink-stream-'));
 const original = globalThis.fetch;
 const bytes = Buffer.from('verified package bytes');
 const descriptor = {name:'update', size:bytes.length, sha256:hash(bytes)};
 const releases = ['primary','mirror'].map(source => ({
  assets:[{name:'update',browser_download_url:'https://example.com/'+source}],
 }));
 try {
  globalThis.fetch = async url => {
   if (String(url).endsWith('primary')) return new Response(Buffer.alloc(bytes.length, 1));
   return new Response(new ReadableStream({
    async start(stream) {
     stream.enqueue(bytes.subarray(0, 4));
     await delay(550);
     stream.enqueue(bytes.subarray(4));
     stream.close();
    },
   }));
  };
  const file = path.join(dir, 'download');
  const updates = [];
  await assetBytes(releases, descriptor, {file, onProgress: update => updates.push(update)});
  assert.deepEqual(await readFile(file), bytes);
  assert.deepEqual(updates.filter(update => update.phase === 'downloading' && update.received === 0)
   .map(update => update.attempt), [1, 2]);
  assert(updates.some(update => update.attempt === 2 && update.phase === 'downloading' && update.speed > 0));
  assert.equal(updates.at(-1).phase, 'verifying');
  assert.equal(updates.at(-1).received, bytes.length);
 } finally {
  globalThis.fetch = original;
  await rm(dir,{recursive:true,force:true});
 }
});

test('update assets cannot redirect from HTTPS to HTTP', async () => {
 const original = globalThis.fetch;
 try {
  globalThis.fetch = async url => {
   assert.equal(url.protocol, 'https:');
   return Response.redirect('http://example.com/update');
  };
  await assert.rejects(assetBytes([{assets:[{name:'update',browser_download_url:'https://example.com/update'}]}],
   {name:'update',size:1,sha256:'0'.repeat(64)}), /HTTPS required/);
 } finally { globalThis.fetch = original; }
});

test('macOS validates a staged bundle and rejects a different identity or a changed bridge', {skip:process.platform!=='darwin'}, async () => {
 const dir = await mkdtemp(path.join(os.tmpdir(), 'lanlink-mac-signature-'));
 const bridge = 'Contents/Resources/native/macos/LanLinkBridge';
 function sign(file, id) {
  const result = spawnSync('/usr/bin/codesign',['--force','--sign','-','--identifier',id,file],{encoding:'utf8'});
  assert.equal(result.status,0,result.stderr);
 }
 async function bundle(name,id) {
  const app = path.join(dir,name+'.app');
  await mkdir(path.join(app,'Contents/MacOS'),{recursive:true});
  await mkdir(path.dirname(path.join(app,bridge)),{recursive:true});
  await writeFile(path.join(app,'Contents/Info.plist'),
   `<?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict><key>CFBundleIdentifier</key><string>${id}</string><key>CFBundleExecutable</key><string>LanLink</string><key>CFBundlePackageType</key><string>APPL</string></dict></plist>`);
  await copyFile('/usr/bin/true',path.join(app,'Contents/MacOS/LanLink'));
  await copyFile('/usr/bin/true',path.join(app,bridge));
  sign(path.join(app,bridge),'com.lanlink.native.bridge');
  sign(app,id);
  return app;
 }
 try {
  const installed = await bundle('installed','com.lanlink.native');
  const staged = await bundle('staged','com.lanlink.native');
  await verifyMacUpdate(installed,staged);
  const other = await bundle('other','com.example.other');
  await assert.rejects(verifyMacUpdate(installed,other));
  await writeFile(path.join(staged,bridge),'changed');
  await assert.rejects(verifyMacUpdate(installed,staged));
 } finally { await rm(dir,{recursive:true,force:true}); }
});

test('stable and beta use separate releases, and switching channels hides the old result', async () => {
 const original = globalThis.fetch;
 const manifestAsset = {name:'lanlink-update.json', browser_download_url:'https://example.com/manifest'};
 const entry = (version, prerelease, draft=false) => ({tag_name:'v'+version, prerelease, draft, assets:[
  {...manifestAsset, browser_download_url:manifestAsset.browser_download_url+'/'+version}, fullLink
 ]});
 try {
  globalThis.fetch = async url => {
   const value = String(url);
   const response = value.startsWith(manifestAsset.browser_download_url)
    ? desktopManifest(value.split('/').at(-1))
    : value.endsWith('/latest') ? entry('99.1.0',false)
    : [entry('99.4.0',true,true),entry('99.3.0',false),entry('99.2.0',true)];
   return new Response(JSON.stringify(response));
  };
  const service = {config:{}};
  const updater = new Updater(service,'win32-x64');
  assert.equal((await updater.check()).version,'99.1.0');
  service.config.updateChannel = 'beta';
  assert.equal(updater.state().phase,'idle');
  assert.equal((await updater.check()).version,'99.2.0');
  service.config.updateChannel = 'stable';
  assert.equal(updater.state().phase,'idle');
 } finally { globalThis.fetch = original; }
});

test('an unsupported release offers its version page without claiming an in-app update', async () => {
 const original = globalThis.fetch;
 const version = '99.5.0';
 let mirrorHasAsset = false;
 try {
  globalThis.fetch = async url => {
   const value = String(url);
   if (value.includes('example.com')) return new Response(JSON.stringify(desktopManifest(version)));
   const assets = [{name:'lanlink-update.json',browser_download_url:'https://example.com/manifest'}];
   if (mirrorHasAsset && value.includes('gitee.com')) assets.push(fullLink);
   return new Response(JSON.stringify({tag_name:'v'+version, assets, body:value.includes('github.com')?'Fix device discovery':''}));
  };
  const updater = new Updater({config:{}},'win32-x64');
  const missing = await updater.check();
  assert.equal(missing.phase,'unavailable');
  assert.equal(missing.notes,'Fix device discovery');
  assert.match(missing.downloadPage,/\/releases\/tag\/v99\.5\.0$/);
  mirrorHasAsset = true;
  const available = await updater.check();
  assert.equal(available.phase,'available');
  assert.equal(available.notes,'Fix device discovery');
  assert.equal((await release('stable','win32-x64')).page,'https://gitee.com/lokey-t/lanlink/releases/tag/v99.5.0');
 } finally { globalThis.fetch = original; }
});

test('update reminder choices persist per channel and version', async () => {
 const original = globalThis.fetch;
 const dir = await mkdtemp(path.join(os.tmpdir(),'lanlink-update-reminders-'));
 let version = '99.5.0';
 try {
  globalThis.fetch = async url => {
   const beta = String(url).includes('?');
   const entry = {tag_name:'v'+version, prerelease:beta, body:beta?'Beta notes':'Stable notes'};
   return new Response(JSON.stringify(beta?[entry]:entry));
  };
  const service = {dir, config:{}};
  const updater = new Updater(service,'win32-x64');
  assert.equal((await updater.check(false)).prompt,true);
  assert.equal(updater.state().notes,'Stable notes');
  assert.equal((await updater.deferPrompt({channel:'stable',version,choice:'skip'})).prompt,false);

  service.config.updateChannel = 'beta';
  assert.equal((await updater.check(false)).prompt,true);
  assert.equal(updater.state().notes,'Beta notes');
  assert.equal((await updater.deferPrompt({channel:'beta',version,choice:'later'})).prompt,false);
  assert.equal((await updater.check(false)).prompt,false);
  assert.equal((await updater.check()).prompt,true);

  const config = JSON.parse(await readFile(path.join(dir,'config.json'),'utf8'));
  const restarted = new Updater({dir,config},'win32-x64');
  assert.equal((await restarted.check(false)).prompt,false);
  config.updateReminders.beta[version] = Date.now() - 1;
  assert.equal(restarted.state().prompt,true);
  config.updateChannel = 'stable';
  assert.equal((await restarted.check()).prompt,false);
  version = '99.5.1';
  assert.equal((await restarted.check(false)).prompt,true);
 } finally {
  globalThis.fetch = original;
  await rm(dir,{recursive:true,force:true});
 }
});

test('a failed desktop replacement restores the old app and persists an actionable result', async () => {
 const dir = await mkdtemp(path.join(os.tmpdir(),'lanlink-update-result-'));
 try {
  const install = path.join(dir,'old'), stage = path.join(dir,'missing'), plan = path.join(dir,'plan.json');
  const resultFile = path.join(dir,'update-result.json');
  await mkdir(install);
  await writeFile(path.join(install,'existing'),'old application');
  await writeFile(plan,JSON.stringify({
   install,stage,resultFile,version:'99.1.0',parent:2147483000,host:2147483001,
   page:'https://github.com/lokey-t/lanlink/releases/tag/v99.1.0',
  }));
  const result = spawnSync(process.execPath,['src/update-install.js',plan],{timeout:10000});
  assert.equal(result.status,1);
  assert.equal(await readFile(path.join(install,'existing'),'utf8'),'old application');
  const updater = new Updater({config:{},dir});
  await updater.loadResult();
  assert.equal(updater.state().result.phase,'failed');
  assert.match(updater.state().result.message,/已恢复旧版本/);
 } finally { await rm(dir,{recursive:true,force:true}); }
});
