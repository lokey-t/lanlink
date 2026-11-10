import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,writeFile,readFile,mkdir,rm,copyFile} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {setTimeout as delay} from 'node:timers/promises';
import {gunzipSync,gzipSync} from 'node:zlib';
import {delta,hash,reconstruct,newer,safePath,materialize} from '../src/update-package.js';
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
test('release generator creates one combined delta and reconstructs a clean tree',async()=>{
 const dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-update-test-'));try{
 for(const name of ['old','new'])await mkdir(path.join(dir,name));await writeFile(path.join(dir,'old/a'),Buffer.alloc(131072,7));await writeFile(path.join(dir,'old/deleted'),'gone');await writeFile(path.join(dir,'new/a'),Buffer.concat([Buffer.alloc(131072,7),Buffer.from('new')]));await writeFile(path.join(dir,'new/added'),'added');
 const input={version:'0.3.99',targets:{'win32-x64':{current:path.join(dir,'new'),previous:path.join(dir,'old'),from:'0.3.98'}}};await writeFile(path.join(dir,'input.json'),JSON.stringify(input));const r=spawnSync(process.execPath,['scripts/build-update.js',path.join(dir,'input.json'),path.join(dir,'out')],{encoding:'utf8'});assert.equal(r.status,0,r.stderr);
 const manifest=JSON.parse(await readFile(path.join(dir,'out/lanlink-update.json'))),bytes=await readFile(path.join(dir,'out',manifest.delta.name));assert.equal(hash(bytes),manifest.delta.sha256);const bundle=JSON.parse(gunzipSync(bytes));await materialize(path.join(dir,'old'),path.join(dir,'stage'),bundle.targets['win32-x64'].files);assert.deepEqual(await readFile(path.join(dir,'stage/a')),await readFile(path.join(dir,'new/a')));await assert.rejects(readFile(path.join(dir,'stage/deleted')));
 }finally{await rm(dir,{recursive:true,force:true});}
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
   return new Response(JSON.stringify({tag_name:'v'+version, assets}));
  };
  const updater = new Updater({config:{}},'win32-x64');
  const missing = await updater.check();
  assert.equal(missing.phase,'unavailable');
  assert.match(missing.downloadPage,/\/releases\/tag\/v99\.5\.0$/);
  mirrorHasAsset = true;
  assert.equal((await updater.check()).phase,'available');
  assert.equal((await release('stable','win32-x64')).page,'https://gitee.com/lokey-t/lanlink/releases/tag/v99.5.0');
 } finally { globalThis.fetch = original; }
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
