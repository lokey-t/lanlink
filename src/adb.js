import { execFile } from 'node:child_process';
import { access } from 'node:fs/promises';
import { promisify } from 'node:util';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { saveConfig } from './identity.js';

const execute = promisify(execFile);
const root = fileURLToPath(new URL('../', import.meta.url));
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const serialPattern = /^[a-zA-Z0-9][a-zA-Z0-9._:\[\]-]{0,199}$/;

export async function findAdb({ env = process.env, platform = process.platform, home = os.homedir(), exists = access } = {}) {
  const name = platform === 'win32' ? 'adb.exe' : 'adb';
  const paths = platform === 'win32' ? path.win32 : path.posix;
  const sdkRoots = [env.ANDROID_SDK_ROOT, env.ANDROID_HOME,
    platform === 'win32' && env.LOCALAPPDATA ? paths.join(env.LOCALAPPDATA, 'Android', 'Sdk') : '',
    platform === 'darwin' ? paths.join(home, 'Library', 'Android', 'sdk') : paths.join(home, 'Android', 'Sdk')];
  const candidates = [env.LANLINK_ADB, paths.join(root, 'runtime', 'platform-tools', name),
    ...sdkRoots.filter(Boolean).map(p => paths.join(p, 'platform-tools', name)),
    ...(env.PATH || env.Path || '').split(platform === 'win32' ? ';' : ':').filter(Boolean).map(p => paths.join(p.replace(/^"|"$/g, ''), name))];
  for (const candidate of candidates.filter(Boolean)) {
    if (!paths.isAbsolute(candidate)) continue;
    try { await exists(candidate); return candidate; } catch {}
  }
  return null;
}

export function parseDevices(output) {
  const devices = [];
  for (const line of String(output).split(/\r?\n/)) {
    const match = /^(\S+)\s+(device|unauthorized|offline|no permissions)(?:\s|$)(.*)$/.exec(line.trim());
    if (!match || !serialPattern.test(match[1])) continue;
    const model = /(?:^|\s)model:(\S+)/.exec(match[3]);
    devices.push({ serial: match[1], status: match[2], model: model ? model[1].replaceAll('_', ' ') : match[1] });
  }
  return devices;
}

// All variable fields are strictly validated. The APK must also match `pm path`
// on the explicitly selected transport; peers never supply an executable command.
export function helperCommand(bootstrap, installed) {
  const { apkPath, port, secret } = bootstrap || {};
  if (typeof apkPath !== 'string' || apkPath.length > 600 || !/^\/data\/app\/[a-zA-Z0-9_./=+~-]+\/base\.apk$/.test(apkPath) || apkPath.split('/').includes('..') ||
      !Number.isInteger(port) || port < 1 || port > 65535 || typeof secret !== 'string' || !/^lanlink1:[a-zA-Z0-9_-]{43}$/.test(secret)) throw Error('手机返回的 ADB 助手信息无效，请更新手机端');
  const packages = String(installed).split(/\r?\n/).filter(line => line.startsWith('package:')).map(line => line.slice(8).trim());
  if (!packages.includes(apkPath)) throw Error('所选 USB／调试设备与目标手机的应用不匹配，请核对设备后重试');
  return `CLASSPATH='${apkPath}' app_process /system/bin com.lanlink.app.ShellAgent ${port} ${secret} >/dev/null 2>&1 </dev/null &`;
}

export class AdbManager {
  constructor(service, { locate = findAdb, run = execute, wait = delay } = {}) {
    Object.assign(this, { service, locate, run, wait }); this.busy = false; this.retries = new Map();
  }
  async remember(id, serial, peer) {
    if (!this.service.config) return;
    const key = peer.info?.capabilities?.memberKey;
    if (!key) return;
    this.service.config.adbBindings ||= {};
    this.service.config.adbBindings[id] = { serial, key };
    await saveConfig(this.service.dir, this.service.config);
  }
  async forget(id) {
    if (this.service.config?.adbBindings) delete this.service.config.adbBindings[id];
    await saveConfig(this.service.dir, this.service.config);
    return { message: '已取消该设备的电脑自动恢复；不会撤销手机系统中的 USB 调试信任。' };
  }
  async tick() {
    if (this.busy || this.service.closing) return;
    for (const [id, binding] of Object.entries(this.service.config.adbBindings || {})) {
      const peer = this.service.peers.get(id);
      if (!peer || peer.closed || peer.info?.platform !== 'android' || peer.info.capabilities?.memberKey !== binding.key || this.service.group.excluded(id)) continue;
      if (Date.now() < (this.retries.get(id) || 0)) continue;
      this.retries.set(id, Date.now() + 30000);
      try {
        const permission = (await peer.request('permission-status', {}, 3000))?.permissions?.input;
        if (permission?.peerAllowed !== true || permission.appEnabled !== true || permission.systemGranted === true) continue;
        await this.authorize({ id, serial: binding.serial, automatic: true });
      }
      catch { /* Retry saved, explicitly approved transports only; never pick another phone. */ }
      break;
    }
  }
  async command(executable, args, timeout = 7000) {
    try { return (await this.run(executable, args, { encoding: 'utf8', timeout, maxBuffer: 256 * 1024, windowsHide: true, shell: false })).stdout || ''; }
    catch (e) {
      // execFile errors include the command (and helper secret); never expose them.
      if (e.killed || e.code === 'ETIMEDOUT') throw Error('ADB 操作超时，请检查数据线和手机调试授权');
      throw Error('ADB 操作失败，请确认手机已解锁、允许此电脑调试且保持连接');
    }
  }
  async devices() {
    const executable = await this.locate();
    if (!executable) return { available: false, devices: [], message: '未找到 ADB。请在 Android Studio 的 SDK Manager 安装 Android SDK Platform-Tools，或将其加入 PATH。' };
    const devices = parseDevices(await this.command(executable, ['devices', '-l']));
    return { available: true, devices, message: devices.length ? '选择与局域网目标对应的手机；未授权时请在手机上允许 USB 调试。' : '未发现调试设备。请用数据线连接手机，开启 USB 调试并允许此电脑。' };
  }
  async authorize({ id, serial, automatic = false }) {
    if (typeof id !== 'string' || typeof serial !== 'string' || !serialPattern.test(serial)) throw Error('请选择目标手机和调试设备');
    if (this.busy) throw Error('正在为手机启用 ADB 键鼠，请稍候');
    this.busy = true;
    try {
      const peer = this.service.getPeer(id);
      if (peer.info?.platform !== 'android') throw Error('ADB 键鼠增强仅适用于已连接的安卓设备');
      const executable = await this.locate();
      if (!executable) throw Error('未找到 ADB，请先安装 Android SDK Platform-Tools');
      const devices = parseDevices(await this.command(executable, ['devices', '-l']));
      const device = devices.find(d => d.serial === serial);
      if (!device) throw Error('所选调试设备已断开，请刷新设备列表');
      if (device.status !== 'device') throw Error(device.status === 'unauthorized' ? '请先在手机弹窗中允许此电脑进行 USB 调试，再重试' : '所选调试设备尚未就绪，请重新连接并刷新');
      if (automatic) {
        const permission = (await peer.request('permission-status', {}, 3000))?.permissions?.input;
        if (permission?.peerAllowed !== true || permission.appEnabled !== true) return { skipped: true };
      }
      const before = await peer.request('adbStatus', {}, 5000);
      if (before.ready === true) {
        peer.info.capabilities = { ...peer.info.capabilities, input: true, inputMode: 'adb/root' };
        this.service.emit('change');
        if (!automatic) await this.remember(id, serial, peer);
        return { mode: 'adb/root', message: '手机的 ADB／Root 输入助手已经连接，可使用增强键鼠控制。' };
      }
      const bootstrap = await peer.request('adbBootstrap', {}, 5000);
      const installed = await this.command(executable, ['-s', serial, 'shell', 'pm', 'path', 'com.lanlink.app']);
      const command = helperCommand(bootstrap, installed);
      // Recheck the same authenticated peer after asynchronous device inspection.
      if (this.service.getPeer(id) !== peer) throw Error('手机连接已改变，请重试');
      if(bootstrap.agentVersion>=3) {
        // Upgrade only helpers carrying this phone's exact private binding.
        const processes=await this.command(executable,['-s',serial,'shell','ps','-A','-o','PID,ARGS']);
        if(this.service.getPeer(id)!==peer)throw Error('手机连接已改变，请重试');
        for(const pid of boundHelperPids(processes,bootstrap)) await this.command(executable,['-s',serial,'shell','kill',pid]);
      }
      if(this.service.getPeer(id)!==peer)throw Error('手机连接已改变，请重试');
      await this.command(executable, ['-s', serial, 'shell', command]);
      for (let attempt = 0; attempt < 12; attempt++) {
        await this.wait(250);
        const status = await peer.request('adbStatus', {}, 3000);
        if (status.ready === true) {
          peer.info.capabilities = { ...peer.info.capabilities, input: true, inputMode: 'adb/root' };
          this.service.emit('change');
          if (!automatic) await this.remember(id, serial, peer);
          return { mode: 'adb/root', message: '已保存授权绑定。应用重启后助手自动重连；手机重启后，已信任的调试连接可用时电脑会自动恢复。屏幕共享仍需手机确认。' };
        }
      }
      throw Error('未收到目标手机的助手确认。请核对所选调试设备，并检查手机系统是否限制 ADB 输入');
    } finally { this.busy = false; }
  }
}

export function boundHelperPids(output, {port,secret}) {
  return String(output).split(/\r?\n/).flatMap(line=>{
    const start=/^\s*(\d+)\s+(?:\/system\/bin\/)?(?:app_process(?:32|64)?|com\.lanlink\.app\.ShellAgent)(?:\s|$)/.exec(line);
    if(!start)return [];
    const args=line.trim().split(/\s+/), at=args.indexOf('com.lanlink.app.ShellAgent');
    return at>=1 && args[at+1]===String(port) && args[at+2]===secret ? [start[1]] : [];
  });
}
