import test from 'node:test';
import assert from 'node:assert/strict';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {mkdtemp,readdir,writeFile,rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
const run=promisify(execFile);

test('macOS bridge producers remain responsive under output backpressure and preserve reliable replies', {skip:process.platform!=='darwin',timeout:30000}, async t=>{
  const dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-bridge-output-'));t.after(()=>rm(dir,{recursive:true,force:true}));
  const binary=path.join(dir,'probe');
  await run('xcrun',['swiftc','-swift-version','5','-module-cache-path',path.join(os.tmpdir(),'lanlink-native-latency-cache'),'native/macos/BridgeOutput.swift','tests/BridgeOutputProbe.swift','-o',binary]);
  const result=await run(binary,[],{timeout:10000});assert.match(result.stdout,/MAC_BRIDGE_OUTPUT_OK/);t.diagnostic(result.stdout.trim());
});

test('Windows bridge dispatch and workers run independently with ordered bounded output', {skip:!process.env.LANLINK_DOTNET,timeout:30000}, async t=>{
  const dotnet=process.env.LANLINK_DOTNET,home=path.dirname(dotnet),sdk=(await readdir(path.join(home,'sdk'))).filter(s=>/^\d/.test(s)).sort().at(-1);
  const pack=(await readdir(path.join(home,'packs/Microsoft.NETCore.App.Ref'))).filter(s=>/^\d/.test(s)).sort().at(-1),framework='net'+pack.split('.').slice(0,2).join('.');
  const refs=path.join(home,'packs/Microsoft.NETCore.App.Ref',pack,'ref',framework),dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-bridge-work-'));t.after(()=>rm(dir,{recursive:true,force:true}));
  const binary=path.join(dir,'probe.dll');
  await run(dotnet,[path.join(home,'sdk',sdk,'Roslyn/bincore/csc.dll'),'/nologo','/target:exe','/langversion:5','/out:'+binary,...(await readdir(refs)).filter(s=>s.endsWith('.dll')).map(s=>'/reference:'+path.join(refs,s)),'native/windows/BridgeWork.cs','tests/BridgeWorkProbe.cs']);
  await writeFile(path.join(dir,'probe.runtimeconfig.json'),JSON.stringify({runtimeOptions:{tfm:framework,framework:{name:'Microsoft.NETCore.App',version:pack}}}));
  const result=await run(dotnet,[binary],{timeout:10000});assert.match(result.stdout,/WINDOWS_BRIDGE_WORK_OK/);t.diagnostic(result.stdout.trim());
});

test('Windows native bridge compiles against shipping Framework 4.8 and C# 5', {skip:!process.env.LANLINK_DOTNET||!process.env.LANLINK_NET48_REFERENCES,timeout:30000}, async t=>{
  const dotnet=process.env.LANLINK_DOTNET,home=path.dirname(dotnet),sdk=(await readdir(path.join(home,'sdk'))).filter(s=>/^\d/.test(s)).sort().at(-1),dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-native-net48-'));t.after(()=>rm(dir,{recursive:true,force:true}));
  const result=await run(dotnet,[path.join(home,'sdk',sdk,'Roslyn/bincore/csc.dll'),'/nologo','/target:library','/langversion:5','/out:'+path.join(dir,'Bridge.dll'),...['mscorlib.dll','System.dll','System.Core.dll','System.Windows.Forms.dll','System.Drawing.dll','System.Web.Extensions.dll'].map(s=>'/reference:'+path.join(process.env.LANLINK_NET48_REFERENCES,s)),'native/windows/BridgeWork.cs','native/windows/Bridge.cs']);
  assert.equal(result.stderr,'');
});
