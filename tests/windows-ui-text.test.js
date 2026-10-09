import test from 'node:test';
import assert from 'node:assert/strict';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {mkdtemp,readFile,readdir,rm,writeFile} from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {fileURLToPath} from 'node:url';
const run=promisify(execFile),root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');

// Optional .NET SDK is kept outside the product; these tests execute the actual
// C# rules without requiring WPF, a Windows VM, or a change to the user's group.
test('Windows pairing input, readable errors, and truthful file states', {skip:!process.env.LANLINK_DOTNET}, async()=>{
  const dotnet=process.env.LANLINK_DOTNET, home=path.dirname(dotnet);
  const sdk=(await readdir(path.join(home,'sdk'))).filter(name=>/^\d/.test(name)).sort().at(-1);
  const pack=(await readdir(path.join(home,'packs/Microsoft.NETCore.App.Ref'))).filter(name=>/^\d/.test(name)).sort().at(-1);
  const framework='net'+pack.split('.').slice(0,2).join('.');
  const references=path.join(home,'packs/Microsoft.NETCore.App.Ref',pack,'ref',framework),dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-windows-logic-'));
  try{
    const output=path.join(dir,'WindowsUiTextTest.dll');
    const args=['/nologo','/target:exe','/langversion:5','/out:'+output,...(await readdir(references)).filter(name=>name.endsWith('.dll')).map(name=>'/reference:'+path.join(references,name)),path.join(root,'desktop/windows/UiText.cs'),path.join(root,'tests/windows-ui-text.cs')];
    await run(dotnet,[path.join(home,'sdk',sdk,'Roslyn/bincore/csc.dll'),...args]);
    await writeFile(path.join(dir,'WindowsUiTextTest.runtimeconfig.json'),JSON.stringify({runtimeOptions:{tfm:framework,framework:{name:'Microsoft.NETCore.App',version:pack}}}));
    const result=await run(dotnet,[output]);assert.match(result.stdout,/Windows text and state regressions passed/);
  }finally{await rm(dir,{recursive:true,force:true});}
});

test('Windows application compiles against .NET Framework with the shipping C# language level', {skip:!process.env.LANLINK_DOTNET || !process.env.LANLINK_NET48_REFERENCES}, async()=>{
  const dotnet=process.env.LANLINK_DOTNET,home=path.dirname(dotnet),sdk=(await readdir(path.join(home,'sdk'))).filter(name=>/^\d/.test(name)).sort().at(-1);
  const dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-windows-build-'));
  try{
    const build=await readFile(path.join(root,'desktop/windows/build.ps1'),'utf8');
    const names=['mscorlib.dll',...new Set([...build.matchAll(/'([A-Za-z.]+\.dll)'/g)].map(match=>match[1]))];
    const sources=(await readdir(path.join(root,'desktop/windows'))).filter(name=>name.endsWith('.cs'));
    for(const file of sources)assert.ok(build.includes("'"+file+"'"),'Shipping build includes '+file);
    const result=await run(dotnet,[path.join(home,'sdk',sdk,'Roslyn/bincore/csc.dll'),'/nologo','/target:winexe','/platform:x64','/langversion:5','/win32manifest:'+path.join(root,'desktop/windows/app.manifest'),'/win32icon:'+path.join(root,'assets/icons/LanLink.ico'),'/out:'+path.join(dir,'LanLink.exe'),...names.map(name=>'/reference:'+path.join(process.env.LANLINK_NET48_REFERENCES,name)),...sources.map(file=>path.join(root,'desktop/windows',file))]);
    assert.equal(result.stderr,'');
  }finally{await rm(dir,{recursive:true,force:true});}
});
