import {createHash} from 'node:crypto';
import {readFile,readdir,writeFile,mkdir} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
export async function buildInfo(root){
  const inputs=['package.json','package-lock.json','src','desktop','scripts','native/macos','native/windows','native/quic/src','native/quic/Cargo.toml','native/quic/Cargo.lock','android/app/build.gradle','android/build.gradle','android/settings.gradle','android/gradle.properties','android/app/src/main','android/gradle','vendor','assets/icons'];
  const sources=[];
  async function visit(relative){if(/(?:^|\/)(?:build-info\.json|LanLinkBridge|\.DS_Store|README\.md)$/.test(relative)||relative.includes('/jniLibs/'))return;
    const full=path.join(root,relative);let children;try{children=await readdir(full,{withFileTypes:true});}catch(error){if(error.code==='ENOENT')return;if(error.code!=='ENOTDIR')throw error;const bytes=await readFile(full);sources.push({path:relative.replaceAll(path.sep,'/'),sha256:createHash('sha256').update(bytes).digest('hex')});return;}
    for(const entry of children.sort((a,b)=>a.name.localeCompare(b.name,'en')))if(!entry.isSymbolicLink())await visit(path.join(relative,entry.name));
  }
  for(const input of inputs)await visit(input);sources.sort((a,b)=>a.path.localeCompare(b.path,'en'));
  const version=JSON.parse(await readFile(path.join(root,'package.json'),'utf8')).version;
  const sourceFingerprint=createHash('sha256').update(sources.map(s=>s.path+'\0'+s.sha256+'\n').join('')).digest('hex');
  let revision='source-archive';try{revision=execFileSync('git',['rev-parse','--short=12','HEAD'],{cwd:root,stdio:['ignore','pipe','ignore']}).toString().trim();if(execFileSync('git',['status','--porcelain','--untracked-files=no'],{cwd:root,stdio:['ignore','pipe','ignore']}).length)revision+='-modified';}catch{}
  return {version,revision,sourceFingerprint,builtAt:new Date().toISOString(),buildId:version+'+'+sourceFingerprint.slice(0,12),sources};
}
export async function writeBuildInfo(root){const info=await buildInfo(root),data=JSON.stringify(info,null,2)+'\n';for(const relative of ['build-info.json','android/app/src/main/assets/build-info.json']){await mkdir(path.dirname(path.join(root,relative)),{recursive:true});await writeFile(path.join(root,relative),data);}return info;}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){const info=await writeBuildInfo(path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..'));console.log('Build '+info.buildId+' ('+info.revision+')');}
