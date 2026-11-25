// Runs from a private sibling directory after both UI and service have exited.
import {readFile,rename,writeFile,rm,mkdir,readdir,lstat} from 'node:fs/promises';
import {spawn} from 'node:child_process';
import path from 'node:path';

const planPath = process.argv[2];
const plan = JSON.parse(await readFile(planPath,'utf8'));
const alive = pid => { try { process.kill(pid,0); return true; } catch (error) { return error.code === 'EPERM'; } };

async function result(phase, message, details = {}) {
 if (!plan.resultFile) return;
 const temporary = plan.resultFile + '.' + process.pid + '.tmp';
 await writeFile(temporary,JSON.stringify({phase,message,version:plan.version,page:plan.page,at:Date.now(),...details}));
 await rename(temporary,plan.resultFile);
}

async function move(from, to, timeout = 20000) {
 const deadline = Date.now() + timeout;
 for (;;) {
  try { await rename(from, to); return; }
  catch (error) {
   if (!['EBUSY','EPERM','EACCES'].includes(error.code) || Date.now() >= deadline) throw error;
   await new Promise(resolve => setTimeout(resolve,200));
  }
 }
}

async function launch() {
 if (plan.noLaunch) return;
 const args = plan.dataDir ? ['--data',plan.dataDir] : [];
 if (plan.mac && Number.isInteger(plan.port) && plan.port >= 0 && plan.port <= 65535) args.push('--port',String(plan.port));
 if (plan.noDiscovery) args.push('--no-discovery');
 const child = spawn(plan.mac ? '/usr/bin/open' : plan.install+'\\LanLink.exe', plan.mac ? ['-n','-a',plan.install,...(args.length ? ['--args',...args] : [])] : args,
  {cwd:plan.install,detached:true,stdio:'ignore',windowsHide:true});
 await new Promise((resolve,reject) => {
  child.once('error',reject);
  if (plan.mac) child.once('exit',code => code === 0 ? resolve() : reject(new Error('Unable to open LanLink')));
  else child.once('spawn',resolve);
 });
 child.unref();
}

const deadline = Date.now()+86400000;
while (plan.waitForExit !== false && (alive(plan.parent) || alive(plan.host))) {
 if (Date.now() > deadline) {
  await result('failed','未能退出 LanLink，更新尚未安装');
  process.exit(1);
 }
 await new Promise(resolve => setTimeout(resolve,1000));
}

const backup = plan.install+'.backup-'+Date.now();
const journal = planPath+'.transaction.json';
const exists = file => lstat(file).then(()=>true,error=>{if(error.code==='ENOENT')return false;throw error;});
async function files(root, prefix = '') {
 const result = [];
 for (const entry of await readdir(path.join(root,prefix),{withFileTypes:true})) {
  const name=path.join(prefix,entry.name);
  if (entry.isDirectory()) result.push(...await files(root,name)); else result.push(name);
 }
 return result;
}
function file(root,name) {
 const resolved=path.resolve(root,name),relative=path.relative(root,resolved);
 if (!relative || relative==='..' || relative.startsWith('..'+path.sep) || path.isAbsolute(relative)) throw Error('Invalid transaction path');
 return resolved;
}
async function transfer(from,to,name) {
 const destination=file(to,name);await mkdir(path.dirname(destination),{recursive:true});await move(file(from,name),destination);
}
async function saveJournal(transaction) {
 await writeFile(journal+'.tmp',JSON.stringify(transaction),{flush:true});await rename(journal+'.tmp',journal);
}
async function restoreFiles(transaction) {
 if (transaction.install!==plan.install || transaction.stage!==plan.stage || !transaction.backup.startsWith(plan.install+'.backup-')) throw Error('Invalid transaction journal');
 if (transaction.phase==='installing') {
  for (const name of [...transaction.newFiles].reverse()) {
   if (!await exists(file(plan.stage,name)) && await exists(file(plan.install,name))) await transfer(plan.install,plan.stage,name);
  }
 }
 for (const name of transaction.oldFiles) if (await exists(file(transaction.backup,name))) await transfer(transaction.backup,plan.install,name);
 await rm(journal,{force:true});
}
// The recovery journal lives outside the installation.
if (await exists(journal)) {
 try { await restoreFiles(JSON.parse(await readFile(journal,'utf8')));await result('failed','上次更新中断，已恢复旧版本，请重新检查更新');await launch(); }
 catch(error) { await writeFile(planPath+'.error',String(error));await result('failed','更新恢复失败，请从备份恢复或手动安装',{detail:String(error)}); }
 process.exit(1);
}
let moved = false, replaced = false, transaction;
try {
 if (await exists(plan.install)) {
  try { await move(plan.install,backup,process.platform==='win32'?2000:20000);moved=true; }
  catch(error) {
   if (plan.mac || process.platform!=='win32' || !['EBUSY','EPERM','EACCES'].includes(error.code)) throw error;
   // electron-builder/NSIS un.atomicRMDir + un.restoreFiles: back up files,
   // retaining directory objects held by Explorer, and restore on failure.
   // https://github.com/electron-userland/electron-builder/blob/master/packages/app-builder-lib/templates/nsis/uninstaller.nsh
   transaction={install:plan.install,stage:plan.stage,backup,oldFiles:await files(plan.install),newFiles:await files(plan.stage),phase:'backing-up'};
   await mkdir(backup);await saveJournal(transaction);
   for (const name of transaction.oldFiles) await transfer(plan.install,backup,name);
   transaction.phase='installing';await saveJournal(transaction);
   for (const name of transaction.newFiles) await transfer(plan.stage,plan.install,name);
  }
 }
 if (!transaction) await move(plan.stage,plan.install);
 replaced = true;
 await result('installed','已安装更新');
 await launch();
 if (transaction) await rm(journal,{force:true});
} catch (error) {
 let message = ['EBUSY','EPERM','EACCES'].includes(error.code) ? '安装目录仍被占用或无法写入，请退出相关程序后重试或手动安装' : '安装更新失败，当前版本仍可使用';
 try {
  if (transaction) { await restoreFiles(transaction);message='安装更新失败，已恢复旧版本'; }
  else if (replaced) await move(plan.install,plan.stage);
  if (!transaction && moved) {
   await move(backup,plan.install);
   message = '安装更新失败，已恢复旧版本';
  }
 } catch (rollbackError) {
  message = '安装更新失败，无法恢复旧版本，请手动安装';
  await writeFile(planPath+'.error',String(error)+'\n'+String(rollbackError)).catch(()=>{});
  await result('failed',message,{code:error.code,detail:String(error),rollbackError:String(rollbackError)}).catch(()=>{});
  process.exit(1);
 }
 await writeFile(planPath+'.error',String(error)).catch(()=>{});
 await result('failed',message,{code:error.code,detail:String(error)}).catch(()=>{});
 await launch().catch(()=>{});
 process.exit(1);
}
await rm(planPath,{force:true}).catch(()=>{});
