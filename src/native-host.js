// Native UI transport: inherited anonymous pipes only, no HTTP listener or browser.
import { createInterface } from 'node:readline';
import os from 'node:os';
import path from 'node:path';
import { Service } from './service.js';
import { NativeBridge } from './native.js';
import { loadConfig } from './identity.js';
import { createActions } from './actions.js';
const args = process.argv.slice(2);
const option = (name, fallback) => { const i = args.indexOf(name); return i < 0 ? fallback : args[i + 1]; };
const dir = path.resolve(option('--data', path.join(os.homedir(), '.lanlink')));
const service = new Service(await loadConfig(dir), dir, new NativeBridge({ disabled: args.includes('--simulate') }),
  { port: Number(option('--port', 45831)), noDiscovery: args.includes('--no-discovery') });
const write = value => { if (!process.stdout.destroyed) process.stdout.write(JSON.stringify(value) + '\n'); };
let closing = false, inFlight = 0, mutations = Promise.resolve();
async function close(exitCode = 0) {
  if (closing) return; closing = true;
  const guard = setTimeout(() => process.exit(1), 8000); guard.unref();
  await service.close().catch(() => {}); process.exit(exitCode);
}
process.stdin.on('end', () => close()); process.on('SIGTERM', () => close()); process.on('SIGINT', () => close());
process.stdout.on('error', () => close()); service.once('stop', () => close());
try { await service.start(); } catch (e) {
  write({ fatal: e.code === 'EADDRINUSE' ? 'LanLink 的连接端口已被占用。请打开已运行的 LanLink；若刚刚退出，请稍后重新启动。' : e.message, code: e.code });
  await close(1);
}
const invoke = createActions(service);
service.on('edge-feedback', feedback => write({event:'edge-feedback',feedback}));
write({ ready: true });
createInterface({ input: process.stdin }).on('line', line => {
  if (closing) return;
  if (line.length > 2 * 1024 * 1024 || inFlight >= 64) { close(); return; }
  let request;
  try { request = JSON.parse(line); if (typeof request.id !== 'string' || request.id.length > 64) throw Error(); }
  catch { write({ error: '无效请求' }); return; }
  inFlight++;
  const run = async () => {
    try {
      let result;
      if (request.method === 'device-paste' || request.method === 'send-files') {
        if (service.active && service.active !== request.args.id) throw Error('请先结束当前设备的控制');
        if (request.method === 'send-files') {
          service.allow('clipboard');
          const files = request.args.files;
          if (!Array.isArray(files) || !files.length || files.length > 100 || files.some(f => typeof f !== 'string' || !path.isAbsolute(f))) throw Error('请选择有效的本机文件');
          await service.native.call('clipboardSet', { files }); await service.snapshotClipboard();
        }
        const temporary = !service.active;
        try { if (temporary) await service.switchTo(request.args.id, 'right', false); result = await service.pasteTo(request.args.id); }
        finally { if (temporary && service.active === request.args.id) await service.returnLocal(); }
      } else result = await invoke(request.method, request.args);
      write({ id: request.id, result: result ?? {} });
    } catch (e) { write({ id: request.id, error: e.message }); }
    finally { inFlight--; }
  };
  if (['settings', 'join', 'device-remove', 'stop-inbound', 'consent-decide', 'consent-revoke', 'send-files', 'device-paste'].includes(request.method)) mutations = mutations.then(run, run);
  else run();
});
