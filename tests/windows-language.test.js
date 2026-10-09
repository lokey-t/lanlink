import test from 'node:test';
import assert from 'node:assert/strict';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {mkdtemp,readdir,rm,writeFile} from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {fileURLToPath} from 'node:url';

const run=promisify(execFile),root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
test('Windows resolves native system language, Chinese scripts, regional languages and explicit overrides', {skip:!process.env.LANLINK_DOTNET}, async()=>{
  const dotnet=process.env.LANLINK_DOTNET,home=path.dirname(dotnet);
  const sdk=(await readdir(path.join(home,'sdk'))).filter(name=>/^\d/.test(name)).sort().at(-1);
  const pack=(await readdir(path.join(home,'packs/Microsoft.NETCore.App.Ref'))).filter(name=>/^\d/.test(name)).sort().at(-1);
  const framework='net'+pack.split('.').slice(0,2).join('.');
  const references=path.join(home,'packs/Microsoft.NETCore.App.Ref',pack,'ref',framework);
  const dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-windows-language-'));
  try {
    const output=path.join(dir,'WindowsLanguageTest.dll');
    const args=['/nologo','/target:exe','/langversion:5','/out:'+output,
      ...(await readdir(references)).filter(name=>name.endsWith('.dll')).map(name=>'/reference:'+path.join(references,name)),
      path.join(root,'desktop/windows/LanguageRules.cs'),path.join(root,'tests/windows-language.cs')];
    await run(dotnet,[path.join(home,'sdk',sdk,'Roslyn/bincore/csc.dll'),...args]);
    await writeFile(path.join(dir,'WindowsLanguageTest.runtimeconfig.json'),JSON.stringify({runtimeOptions:{tfm:framework,framework:{name:'Microsoft.NETCore.App',version:pack}}}));
    const result=await run(dotnet,[output]);assert.match(result.stdout,/Windows language resolution passed \(41 cases\)/);
  } finally { await rm(dir,{recursive:true,force:true}); }
});
