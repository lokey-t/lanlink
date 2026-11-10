// Runs from a private sibling directory after both UI and service have exited.
import {readFile,rename,writeFile,rm} from 'node:fs/promises';
import {spawn} from 'node:child_process';

const planPath = process.argv[2];
const plan = JSON.parse(await readFile(planPath,'utf8'));
const alive = pid => { try { process.kill(pid,0); return true; } catch (error) { return error.code === 'EPERM'; } };

async function result(phase, message, version = plan.version) {
 if (!plan.resultFile) return;
 const temporary = plan.resultFile + '.' + process.pid + '.tmp';
 await writeFile(temporary,JSON.stringify({phase,message,version,page:plan.page,at:Date.now()}));
 await rename(temporary,plan.resultFile);
}

async function launch() {
 const child = spawn(plan.mac ? '/usr/bin/open' : plan.install+'\\LanLink.exe', plan.mac ? [plan.install] : [],
  {cwd:plan.install,detached:true,stdio:'ignore',windowsHide:true});
 await new Promise((resolve,reject) => {
  child.once('error',reject);
  if (plan.mac) child.once('exit',code => code === 0 ? resolve() : reject(new Error('Unable to open LanLink')));
  else child.once('spawn',resolve);
 });
 child.unref();
}

const deadline = Date.now()+86400000;
while (alive(plan.parent) || alive(plan.host)) {
 if (Date.now() > deadline) {
  await result('failed','未能退出 LanLink，更新尚未安装');
  process.exit(1);
 }
 await new Promise(resolve => setTimeout(resolve,1000));
}

const backup = plan.install+'.backup-'+Date.now();
let moved = false, replaced = false;
try {
 await rename(plan.install,backup);
 moved = true;
 await rename(plan.stage,plan.install);
 replaced = true;
 await result('installed','已安装更新');
 await launch();
} catch (error) {
 let message = '安装更新失败，当前版本仍可使用';
 try {
  if (replaced) await rename(plan.install,plan.stage);
  if (moved) {
   await rename(backup,plan.install);
   message = '安装更新失败，已恢复旧版本';
  }
 } catch (rollbackError) {
  message = '安装更新失败，无法恢复旧版本，请手动安装';
  await writeFile(planPath+'.error',String(error)+'\n'+String(rollbackError)).catch(()=>{});
  await result('failed',message).catch(()=>{});
  process.exit(1);
 }
 await writeFile(planPath+'.error',String(error)).catch(()=>{});
 await result('failed',message).catch(()=>{});
 await launch().catch(()=>{});
 process.exit(1);
}
await rm(planPath,{force:true}).catch(()=>{});
