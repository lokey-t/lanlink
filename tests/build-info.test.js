import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,rm,readFile} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {buildInfo,writeBuildInfo} from '../scripts/build-info.js';
test('same version and revision still identify different sources; embedded metadata does not change the fingerprint',async t=>{const root=await mkdtemp(path.join(os.tmpdir(),'lanlink-build-'));t.after(()=>rm(root,{recursive:true,force:true}));await mkdir(path.join(root,'src'));await writeFile(path.join(root,'package.json'),JSON.stringify({version:'0.3.46'}));await writeFile(path.join(root,'src','example.js'),'export const value=1;');const first=await writeBuildInfo(root);const repeated=await buildInfo(root);assert.equal(first.sourceFingerprint,repeated.sourceFingerprint);assert.equal(JSON.parse(await readFile(path.join(root,'android/app/src/main/assets/build-info.json'))).buildId,first.buildId);await writeFile(path.join(root,'src','example.js'),'export const value=2;');const changed=await buildInfo(root);assert.equal(first.version,changed.version);assert.notEqual(first.sourceFingerprint,changed.sourceFingerprint);assert.notEqual(first.buildId,changed.buildId);assert.ok(first.sources.some(r=>r.path==='src/example.js'));});
