// Runs from a private sibling directory, after both UI and service have exited.
import {readFile,rename,writeFile} from 'node:fs/promises';
import {spawn} from 'node:child_process';
const plan=JSON.parse(await readFile(process.argv[2],'utf8'));
const alive=pid=>{try{process.kill(pid,0);return true;}catch{return false;}};
const deadline=Date.now()+86400000;
while(alive(plan.parent)||alive(plan.host)){if(Date.now()>deadline)process.exit(1);await new Promise(r=>setTimeout(r,1000));}
const backup=plan.install+'.backup-'+Date.now();let moved=false;
try{await rename(plan.install,backup);moved=true;await rename(plan.stage,plan.install);}
catch(e){if(moved)await rename(backup,plan.install);await writeFile(process.argv[2]+'.error',String(e));process.exit(1);}
const child=spawn(plan.mac?'/usr/bin/open':plan.install+'\\LanLink.exe',plan.mac?[plan.install]:[],{detached:true,stdio:'ignore',windowsHide:true});child.unref();
