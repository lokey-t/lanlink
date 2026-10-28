// node scripts/build-update.js release-input.json output-directory
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import path from 'node:path';
import {gzipSync} from 'node:zlib';
import {snapshot,delta,hash} from '../src/update-package.js';
const input=JSON.parse(await readFile(process.argv[2],'utf8')),out=path.resolve(process.argv[3]);
if(!/^\d+\.\d+\.\d+$/.test(input.version))throw Error('Invalid version');
await mkdir(out,{recursive:true});
const bundle={format:1,version:input.version,targets:{}},manifest={format:1,version:input.version,targets:{}};
async function asset(name,bytes){await writeFile(path.join(out,name),bytes,{flag:'wx'});return {name,sha256:hash(bytes),size:bytes.length};}
for(const [target,spec]of Object.entries(input.targets)){
 const android=target==='android',current=android?{'app.apk':{bytes:await readFile(spec.current),mode:420}}:await snapshot(spec.current);
 const previous=android?{'app.apk':{bytes:await readFile(spec.previous)}}:await snapshot(spec.previous);
 const full={},patch={};for(const [name,file]of Object.entries(current)){if(file.link!==undefined){full[name]={link:file.link};patch[name]={link:file.link};continue;}const before=previous[name]?.bytes;full[name]={sha256:hash(file.bytes),mode:file.mode,ops:[file.bytes.toString('base64')]};patch[name]={...full[name],...(before?{base:hash(before),ops:delta(before,file.bytes)}:{})};}
 bundle.targets[target]={from:spec.from,files:patch};
 manifest.targets[target]={full:await asset(`LanLink-${input.version}-${target}.full${android?'.apk':'.json.gz'}`,android?current['app.apk'].bytes:gzipSync(JSON.stringify({format:1,version:input.version,files:full})))};
}
manifest.delta=await asset(`LanLink-${input.version}-delta.json.gz`,gzipSync(JSON.stringify(bundle)));
await writeFile(path.join(out,'lanlink-update.json'),JSON.stringify(manifest,null,2),{flag:'wx'});
console.log('Publish lanlink-update.json, the single delta asset and all full assets to both Releases.');
