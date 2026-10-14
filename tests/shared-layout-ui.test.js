import test from 'node:test';
import assert from 'node:assert/strict';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {mkdtemp,readdir,writeFile,rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
const run=promisify(execFile);

test('macOS shared layout renders global chains and sends incremental relative/drag edits without changing master', {skip:process.platform!=='darwin',timeout:180000}, async t=>{
  const dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-shared-layout-'));t.after(()=>rm(dir,{recursive:true,force:true}));
  const fixture=path.join(dir,'host.cjs'),binary=path.join(dir,'probe');
  await writeFile(fixture,`const readline=require('node:readline');const actions=[],masterActions=[];
const state={id:'local',name:'This Mac',master:false,masterId:'A',capabilities:{simulated:true},knownDevices:{outside:{name:'Old group',platform:'win32'}},peers:[],layout:{group:'group',revision:2,origin:'A',positions:{local:{x:0,y:0},A:{x:1,y:0},B:{x:2,y:0},C:{x:1,y:1}}},layoutDevices:[{id:'local',name:'This Mac',platform:'darwin',online:true},{id:'A',name:'Windows A',platform:'win32',online:true},{id:'B',name:'Computer B',platform:'win32',online:true},{id:'C',name:'Android C',platform:'android',online:true},{id:'D',name:'Offline D',platform:'darwin',online:false}]};
readline.createInterface({input:process.stdin}).on('line',line=>{const r=JSON.parse(line);if(r.method==='settings'&&r.args.masterId){masterActions.push(r.args);state.masterId=r.args.masterId;}if(r.method==='layout-place'||r.method==='layout-remove')actions.push({method:r.method,args:r.args});process.stdout.write(JSON.stringify({id:r.id,result:r.method==='state'?state:r.method==='events'?{actions,masterActions}:{}})+'\\n');}).on('close',()=>process.exit(0));`);
  await run('xcrun',['swiftc','-swift-version','5','-D','LANLINK_MODEL_TEST','-module-cache-path',path.join(os.tmpdir(),'lanlink-native-latency-cache'),'-framework','AppKit','-framework','SwiftUI','-framework','ServiceManagement',...['LanLink','SharedLayout','Localization','SingleInstance','Remote','AdbGuide','Staging'].map(name=>'desktop/macos/'+name+'.swift'),'tests/MacSharedLayoutProbe.swift','-o',binary],{timeout:150000});
  const result=await run(binary,[process.execPath,fixture,...(process.env.LANLINK_LAYOUT_PREVIEW?[process.env.LANLINK_LAYOUT_PREVIEW]:[])],{timeout:20000});assert.match(result.stdout,/MAC_SHARED_LAYOUT_OK/);
});

test('Windows shared layout geometry preserves arbitrary chains, branches and bounded grid drops', {skip:!process.env.LANLINK_DOTNET,timeout:30000}, async t=>{
  const dotnet=process.env.LANLINK_DOTNET,home=path.dirname(dotnet),sdk=(await readdir(path.join(home,'sdk'))).filter(s=>/^\d/.test(s)).sort().at(-1);
  const pack=(await readdir(path.join(home,'packs/Microsoft.NETCore.App.Ref'))).filter(s=>/^\d/.test(s)).sort().at(-1),framework='net'+pack.split('.').slice(0,2).join('.');
  const refs=path.join(home,'packs/Microsoft.NETCore.App.Ref',pack,'ref',framework),dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-shared-layout-win-'));t.after(()=>rm(dir,{recursive:true,force:true}));
  const binary=path.join(dir,'probe.dll');
  await run(dotnet,[path.join(home,'sdk',sdk,'Roslyn/bincore/csc.dll'),'/nologo','/target:exe','/langversion:5','/out:'+binary,...(await readdir(refs)).filter(s=>s.endsWith('.dll')).map(s=>'/reference:'+path.join(refs,s)),'desktop/windows/SharedLayout.cs','tests/WindowsSharedLayoutProbe.cs']);
  await writeFile(path.join(dir,'probe.runtimeconfig.json'),JSON.stringify({runtimeOptions:{tfm:framework,framework:{name:'Microsoft.NETCore.App',version:pack}}}));
  const result=await run(dotnet,[binary],{timeout:10000});assert.match(result.stdout,/WINDOWS_SHARED_LAYOUT_OK/);
});
