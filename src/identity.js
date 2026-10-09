import { randomBytes, randomUUID, createHash } from 'node:crypto';
import { mkdir, readFile, writeFile, rename } from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';

export function parseToken(token) {
  if (typeof token !== 'string' || !/^lanlink1:[A-Za-z0-9_-]{43}$/.test(token.trim())) throw new Error('请输入完整的 lanlink1: 配对口令');
  const secret = Buffer.from(token.trim().slice(9), 'base64url');
  if (secret.length !== 32 || secret.toString('base64url') !== token.trim().slice(9)) throw new Error('配对口令无效');
  return secret;
}
export const newToken = () => 'lanlink1:' + randomBytes(32).toString('base64url');
export const accountId = token => createHash('sha256').update(parseToken(token)).digest('hex').slice(0, 24);
const saves = new Map();
function enqueueConfig(dir,work) {
  const key=path.resolve(dir),next=(saves.get(key)||Promise.resolve()).catch(()=>{}).then(work);
  saves.set(key,next);next.finally(()=>{if(saves.get(key)===next)saves.delete(key);}).catch(()=>{});return next;
}
async function writeConfig(dir,config){
  const content=JSON.stringify(config,null,2);await mkdir(dir,{recursive:true,mode:0o700});
  await writeFile(path.join(dir,'config.json.tmp'),content,{mode:0o600});await rename(path.join(dir,'config.json.tmp'),path.join(dir,'config.json'));
}
export function saveConfig(dir,config){return enqueueConfig(dir,()=>writeConfig(dir,config));}
// Read the latest configuration only when the queued transaction owns the disk
// writer, then commit memory before the next writer can observe it.
export function commitConfig(dir,config,update){return enqueueConfig(dir,async()=>{const patch=update(config);await writeConfig(dir,{...config,...patch});Object.assign(config,patch);return patch;});}
export async function loadConfig(dir) {
  try { const config = JSON.parse(await readFile(path.join(dir, 'config.json'), 'utf8')); parseToken(config.token); return config; }
  catch (e) { if (e.code !== 'ENOENT') throw e; }
  const config = { id: randomUUID(), name: os.hostname(), token: newToken(), language: 'system', dwell: 100, edges: {}, deviceGrants: {}, permissions: { input: true, screen: true, clipboard: true, shutdown: true } };
  await saveConfig(dir, config); return config;
}
