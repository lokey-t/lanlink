import path from 'node:path';
import os from 'node:os';
import { loadConfig } from './identity.js';
import { NativeBridge } from './native.js';
import { Service } from './service.js';
import { startUI } from './http.js';

const arg = name => { const i = process.argv.indexOf(name); return i < 0 ? undefined : process.argv[i + 1]; };
const dir = path.resolve(arg('--data') || path.join(os.homedir(), '.lanlink'));
const service = new Service(await loadConfig(dir), dir, new NativeBridge({ disabled: process.argv.includes('--simulate') }), { port: Number(arg('--port') || 45831), noDiscovery: process.argv.includes('--no-discovery') });
let ui, closing = false, parentTimer;
async function close() { if (closing) return; closing = true; clearInterval(parentTimer); ui?.server.close(); await service.close().catch(() => {}); }
try {
  await service.start(); ui = await startUI(service, Number(arg('--ui-port') || 45830));
  service.once('stop', close);
  const parentPid = Number(arg('--parent-pid'));
  if (Number.isInteger(parentPid) && parentPid > 0) parentTimer = setInterval(() => { try { process.kill(parentPid, 0); } catch { close(); } }, 2000);
  console.log('\nLanLink · 局域网设备协作\n');
  console.log('在本机浏览器打开：\n' + ui.url + '\n');
  console.log('紧急返回本机：Ctrl + Alt + F12。关闭本窗口将停止服务。');
  if (process.argv.includes('--simulate')) console.log('当前为模拟模式，未启用原生键鼠、录屏和关机。');
  process.on('SIGINT', () => close()); process.on('SIGTERM', () => close());
} catch (e) { console.error('启动失败：' + e.message); await close(); process.exitCode = 1; }
