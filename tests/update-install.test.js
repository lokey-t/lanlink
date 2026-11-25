import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,readFile,rm} from 'node:fs/promises';
import {spawn,spawnSync} from 'node:child_process';
import {once} from 'node:events';
import os from 'node:os';
import path from 'node:path';
import {setTimeout as delay} from 'node:timers/promises';

test('Windows update waits out an inherited working-directory lock and reopens the same profile',
 {skip:process.platform!=='win32',timeout:30000},async t=>{
 const dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-update-lock-'));
 const install=path.join(dir,'LanLink'),stage=path.join(dir,'new'),dataDir=path.join(dir,'profile');
 for(const folder of [install,stage,dataDir])await mkdir(folder);
 let holder,updater;
 t.after(async()=>{
  for(const child of [holder,updater])if(child&&child.exitCode===null&&child.signalCode===null){const done=once(child,'close');child.kill();await done;}
  await rm(dir,{recursive:true,force:true});
 });
 await writeFile(path.join(install,'old.txt'),'0.92.1');
 await writeFile(path.join(stage,'new.txt'),'0.93.0');
 await writeFile(path.join(dataDir,'settings.keep'),'paired');
 const source=path.join(dir,'Fixture.cs');
 await writeFile(source,'using System.IO;class Fixture{static void Main(string[] a){if(a.Length==2&&a[0]=="--data")File.WriteAllText(Path.Combine(a[1],"reopened.txt"),"yes");}}');
 const compiler=path.join(process.env.WINDIR,'Microsoft.NET/Framework64/v4.0.30319/csc.exe');
 const built=spawnSync(compiler,['/nologo','/target:winexe','/out:'+path.join(stage,'LanLink.exe'),source],{encoding:'utf8'});
 assert.equal(built.status,0,built.stdout+built.stderr);
 holder=spawn(process.execPath,['-e',"console.log('ready');setInterval(()=>{},1000)"],{cwd:install,windowsHide:true,stdio:['ignore','pipe','ignore']});
 await once(holder.stdout,'data');
 const plan=path.join(dir,'plan.json'),resultFile=path.join(dir,'result.json');
 await writeFile(plan,JSON.stringify({install,stage,dataDir,resultFile,version:'0.93.0',parent:2147483000,host:2147483001,mac:false}));
 updater=spawn(process.execPath,['src/update-install.js',plan],{windowsHide:true,stdio:'ignore'});
 const exited=once(updater,'close');
 await delay(500);assert.equal(updater.exitCode,null,'update must keep waiting for the lock');
 assert.equal(await readFile(path.join(install,'old.txt'),'utf8'),'0.92.1');
 const released=once(holder,'close');holder.kill();await released;
 assert.equal((await exited)[0],0);
 assert.equal(JSON.parse(await readFile(resultFile,'utf8')).phase,'installed');
 assert.equal(await readFile(path.join(install,'new.txt'),'utf8'),'0.93.0');
 assert.equal(await readFile(path.join(dataDir,'settings.keep'),'utf8'),'paired');
 const deadline=Date.now()+5000;
 while(Date.now()<deadline){if(await readFile(path.join(dataDir,'reopened.txt'),'utf8').catch(()=>null)==='yes')return;await delay(50);}
 assert.fail('the installed app did not reopen with its original profile');
});
