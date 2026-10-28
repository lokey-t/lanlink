import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,writeFile,readFile,mkdir,rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {gunzipSync,gzipSync} from 'node:zlib';
import {delta,hash,reconstruct,newer,safePath,materialize} from '../src/update-package.js';
import {Updater,assetBytes,release,prepareUpdate} from '../src/updater.js';
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
 const original=globalThis.fetch;let calls=0;try{globalThis.fetch=async url=>{calls++;if(String(url).includes('github'))throw Error('offline');if(String(url).includes('manifest'))return new Response(JSON.stringify({format:1,version:'0.3.99'}));return new Response(JSON.stringify({tag_name:'v0.3.99',assets:[{name:'lanlink-update.json',browser_download_url:'https://example.com/manifest'}]}));};assert.equal((await release()).manifest.version,'0.3.99');assert.equal(calls,3);
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
