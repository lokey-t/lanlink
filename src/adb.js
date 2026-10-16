import { execFile } from 'node:child_process';
import { access } from 'node:fs/promises';
import { promisify } from 'node:util';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomUUID } from 'node:crypto';
import { saveConfig } from './identity.js';

const execute = promisify(execFile);
const root = fileURLToPath(new URL('../', import.meta.url));
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const serialPattern = /^[a-zA-Z0-9][a-zA-Z0-9._:\[\]-]{0,199}$/;
const usbSerial = serial => typeof serial === 'string' && /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,199}$/.test(serial) && !serial.startsWith('emulator-') && !serial.includes('_adb-tls-');

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
    this.usbPrompted = new Map(); this.ticking = false;
    this.usbRetries = new Map(); this.authorizationNotices = [];
  }
  authorizationNotice() {
    const service = this.service;
    this.authorizationNotices = this.authorizationNotices.filter(item => service.group === item.group && !service.group.excluded(item.notice.id) && service.group.data.members[item.notice.id]?.key === item.key);
    return this.authorizationNotices[0]?.notice || null;
  }
  dismissAuthorization(requestId) {
    this.authorizationNotices = this.authorizationNotices.filter(item => item.notice.requestId !== requestId);
    this.service.emit('change'); return {};
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
    if (this.busy || this.ticking || this.service.closing) return;
    this.ticking = true;
    try {
      try { await this.scanUsb({ includeNetwork: true }); } catch { /* A temporary ADB scan failure must not stop saved recovery. */ }
      await this.recover();
    } finally { this.ticking = false; }
  }
  async recover() {
    for (const [id, binding] of Object.entries(this.service.config.adbBindings || {})) {
      const peer = this.service.peers.get(id);
      if (!peer || peer.closed || peer.info?.platform !== 'android' || peer.info.capabilities?.memberKey !== binding.key || this.service.group.excluded(id)) continue;
      if (Date.now() < (this.retries.get(id) || 0)) continue;
      this.retries.set(id, Date.now() + 30000);
      try {
        const report = await peer.request('permission-status', {}, 3000);
        if (report?.engine?.recovering || report?.engine?.root || report?.engine?.revoked) continue;
        const permission = report?.permissions?.input;
        if (permission?.peerAllowed !== true || permission.appEnabled !== true || permission.systemGranted === true) continue;
        await this.authorize({ id, serial: binding.serial, automatic: true });
      }
      catch { /* Retry saved, explicitly approved transports only; never pick another phone. */ }
      break;
    }
  }
  async usbDevice(serial) {
    if (!usbSerial(serial)) return null;
    const executable = await this.locate();
    if (!executable) return null;
    return parseDevices(await this.command(executable, ['devices', '-l'], 3000)).find(device => device.serial === serial) || null;
  }
  async scanUsb({ includeNetwork = false } = {}) {
    const service = this.service, guide = service.adbGuide;
    if (!guide?.usbDetected || this.busy || service.closing || service.changingGroup) return;
    const group = service.group;
    const members = Object.entries(group?.data?.members || {}).filter(([id, member]) => member.platform === 'android' && typeof member.key === 'string' && member.key && !group.excluded(id));
    if (!members.length) { this.usbPrompted.clear(); return; }
    const currentMember = (id, key) => service.group === group && !group.excluded(id) && group.data.members[id]?.key === key;
    const executable = await this.locate();
    if (!executable) return;
    const devices = parseDevices(await this.command(executable, ['devices', '-l'], 3000)).filter(device => usbSerial(device.serial) || includeNetwork && !device.serial.startsWith('emulator-'));
    const attached = new Set(devices.map(device => device.serial));
    for (const serial of this.usbPrompted.keys()) if (!attached.has(serial)) this.usbPrompted.delete(serial);
    for (const serial of this.usbRetries.keys()) if (!attached.has(serial)) this.usbRetries.delete(serial);
    if ((guide.notice && !(guide.notice.trigger === 'usb' && guide.notice.setupOnly)) || this.busy || service.closing || service.changingGroup) return;
    for (const [id, member] of members) {
      if (guide.notice && guide.notice.id !== id) continue;
      const peer = service.peers.get(id);
      // Only authenticated, current group identities can supply live status.
      if (peer && !peer.closed && peer.info?.capabilities?.memberKey !== member.key) continue;
      const binding = service.config.adbBindings?.[id];
      const bound = binding?.key === member.key && this.validBinding(id, binding) ? binding.serial : null;
      let report;
      for (const device of [...devices].sort((a, b) => Number(b.serial === bound) - Number(a.serial === bound))) {
        const noticeKey = `${id}:${member.key}`;
        if (this.usbPrompted.get(device.serial)?.has(noticeKey) && guide.notice?.id !== id) continue;
        let verified = false;
        if (device.status === 'unauthorized') {
          // ADB cannot read an application's identity before Android's system
          // consent. Only a previously verified serial/key binding is evidence.
          if (device.serial !== bound) continue;
          const owners = Object.entries(service.config.adbBindings || {}).filter(([otherId, other]) => other.serial === device.serial && currentMember(otherId, other.key) && this.validBinding(otherId, other));
          if (owners.length !== 1) continue;
          verified = true;
        } else if (device.status === 'device' && peer && !peer.closed) {
          if (!report) try { report = await peer.request('permission-status', {}, 3000); } catch { break; }
          const engine = report?.engine;
          if (!engine || engine.root || !engine.revoked && (engine.ready || engine.recovering)) break;
          verified = await service.links?.verifyUsb(id, device.serial);
        }
        if (!verified || this.busy || service.closing || service.changingGroup || !currentMember(id, member.key)) continue;
        if (peer && (peer.closed || service.peers.get(id) !== peer || peer.info?.capabilities?.memberKey !== member.key)) continue;
        if (device.status === 'device' && peer && !report?.engine?.revoked && Date.now() >= (this.usbRetries.get(device.serial) || 0)) {
          this.usbRetries.set(device.serial, Date.now() + 30000);
          const generation = guide.generation;
          try {
            const result = await this.authorize({ id, serial: device.serial, automatic: true, rememberBinding: true });
            if (generation !== guide.generation) return;
            if (result?.mode && !result.skipped && !service.closing && !service.changingGroup && currentMember(id, member.key) && service.peers.get(id) === peer && !peer.closed && peer.info?.capabilities?.memberKey === member.key && generation === guide.generation) {
              if (guide.notice?.trigger === 'usb' && guide.notice.id === id) await guide.complete(guide.notice.requestId);
              if (!currentMember(id, member.key) || service.peers.get(id) !== peer || peer.closed || service.closing || service.changingGroup) return;
              if (!this.usbPrompted.has(device.serial)) this.usbPrompted.set(device.serial, new Set());
              this.usbPrompted.get(device.serial).add(noticeKey);
              this.authorizationNotice();
              this.authorizationNotices.push({ group, key: member.key, notice: { id, name: member.name, requestId: randomUUID(), message: '设备已授权，已经可以正常使用。' } });
              this.authorizationNotices = this.authorizationNotices.slice(-16);
              service.emit('change'); return;
            }
          } catch { /* Keep the existing consent/setup guide available after a failed attempt. */ }
        }
        if (!usbSerial(device.serial) || guide.notice || !currentMember(id, member.key) || (peer && (service.peers.get(id) !== peer || peer.closed || peer.info?.capabilities?.memberKey !== member.key)) || service.closing || service.changingGroup) continue;
        const opened = await guide.usbDetected({ id, name: member.name, serial: device.serial, usbUnauthorized: device.status === 'unauthorized', report });
        if (!opened) continue;
        if (!this.usbPrompted.has(device.serial)) this.usbPrompted.set(device.serial, new Set());
        this.usbPrompted.get(device.serial).add(noticeKey);
        return;
      }
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
    const bindings = await Promise.all(Object.entries(this.service.config?.adbBindings || {}).map(async ([id, binding]) => {
      if (!this.validBinding(id, binding)) return null;
      const peer = this.service.peers.get(id);
      if (peer && !peer.closed) try {
        const report = await peer.request('permission-status', {}, 3000);
        if (report?.engine?.revoked && !report.engine.root) return null;
      } catch {}
      return binding.serial;
    }));
    for (const device of devices) device.authorized = bindings.includes(device.serial);
    return { available: true, devices, message: devices.length ? '选择与局域网目标对应的手机；未授权时请在手机上允许 USB 调试。' : '未发现调试设备。请用数据线连接手机，开启 USB 调试并允许此电脑。' };
  }
  validBinding(id, binding = this.service.config?.adbBindings?.[id]) {
    if (!binding || this.service.group?.excluded(id)) return false;
    const key = this.service.peers.get(id)?.info?.capabilities?.memberKey || this.service.group?.data?.members?.[id]?.key;
    return !!key && key === binding.key;
  }
  async groupDevices() {
    const known = { ...this.service.group?.data?.members, ...this.service.config?.knownDevices };
    for (const [id, peer] of this.service.peers) known[id] = peer.info;
    const devices = await Promise.all(Object.entries(known).filter(([id, info]) => info.platform === 'android' && !this.service.group?.excluded(id)).map(async ([id, info]) => {
      const peer = this.service.peers.get(id); let report;
      if (peer && !peer.closed) try { report = await peer.request('permission-status', {}, 3000); } catch {}
      const ready = report?.engine?.ready === true && (report?.engine?.root===true || !report?.engine?.revoked && report?.engine?.authorized!==false);
      const revoked = report?.engine?.revoked === true && !report?.engine?.root;
      const authorized = !revoked && (report?.engine?.root === true || report?.engine?.authorized!==false && (ready || report?.engine?.authorized === true || this.validBinding(id)));
      return { id, name: info.name, online: !!peer && !peer.closed, authorized, ready, root: report?.engine?.root === true,
        message: revoked ? '手机已解除 ADB 授权，请点击连接授权，在手机确认后重新配置。' : report?.engine?.authorized===false && !report?.engine?.root ? 'ADB 未授权，请点击连接授权完成配置。' : !ready ? report?.engine?.message || '' : '',
        status: ready ? (report?.engine?.root ? '已就绪（Root方式连接）' : '已就绪（ADB方式连接）') : '未就绪' };
    }));
    return { devices };
  }
  async authorize({ id, serial, automatic = false, rememberBinding = false }) {
    if (typeof id !== 'string' || serial !== undefined && (typeof serial !== 'string' || !serialPattern.test(serial))) throw Error('请选择目标手机和调试设备');
    if (this.busy) throw Error('正在为手机启用 ADB 键鼠，请稍候');
    this.busy = true;
    try {
      const peer = this.service.getPeer(id);
      if (peer.info?.platform !== 'android') throw Error('ADB 键鼠增强仅适用于已连接的安卓设备');
      if (automatic) {
        const report = await peer.request('permission-status', {}, 3000);
        if (report?.engine?.recovering || report?.engine?.root || report?.engine?.revoked) return { skipped: true };
        const permission = report?.permissions?.input;
        if (permission?.peerAllowed !== true || permission.appEnabled !== true) return { skipped: true };
      }
      const before = await peer.request('adbStatus', {}, 5000);
      if (this.service.getPeer(id) !== peer) throw Error('手机连接已改变，请重试');
      if (before.ready === true) {
        peer.info.capabilities = { ...peer.info.capabilities, input: true, inputMode: 'adb/root' };
        this.service.emit('change');
        // The helper belongs to the phone, not the computer that started it.
        // A ready LAN session does not prove trust of a local USB serial.
        return { mode: 'adb/root', message: '手机的 ADB／Root 输入助手已就绪，本组电脑可直接使用，无需重新连接数据线。' };
      }
      if (typeof serial !== 'string') throw Error('手机助手尚未就绪，请连接已信任的 USB／无线调试设备以恢复助手');
      const executable = await this.locate();
      if (!executable) throw Error('未找到 ADB，请先安装 Android SDK Platform-Tools');
      const devices = parseDevices(await this.command(executable, ['devices', '-l']));
      const device = devices.find(d => d.serial === serial);
      if (!device) throw Error('所选调试设备已断开，请刷新设备列表');
      if (device.status !== 'device') throw Error(device.status === 'unauthorized' ? '请先在手机弹窗中允许此电脑进行 USB 调试，再重试' : '所选调试设备尚未就绪，请重新连接并刷新');
      if (!await this.service.links?.verifyUsb(id, serial)) throw Error('所选调试设备与目标手机的身份不匹配，请核对设备后重试');
      if (this.service.getPeer(id) !== peer) throw Error('手机连接已改变，请重试');
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
          if (this.service.getPeer(id) !== peer) throw Error('手机连接已改变，请重试');
          if (!automatic || rememberBinding) await this.remember(id, serial, peer);
          return { mode: 'adb/root', message: '已保存恢复绑定。助手就绪时，本组设备无需重复授权或连接数据线即可使用。手机重启后，需通过已信任的调试连接恢复助手。' };
        }
      }
      throw Error('未收到目标手机的助手确认。请核对所选调试设备，并检查手机系统是否限制 ADB 输入');
    } finally { this.busy = false; }
  }
  async match(id) {
    const peer=this.service.getPeer(id);
    if(peer.info?.platform!=='android')throw Error('请选择组内安卓设备');
    const notice = this.service.adbGuide?.notice;
    if (notice?.id === id && notice.trigger === 'usb') {
      const device = await this.usbDevice(notice.serial);
      if (this.service.getPeer(id) !== peer) throw Error('设备连接已改变');
      if (!device || device.status !== 'device') return { connected: false, message: device?.status === 'unauthorized' ? '已检测到这台组内手机，请解锁手机，允许 USB 调试并勾选始终允许此电脑' : '手机的数据线连接尚未就绪，请重新连接并允许 USB 调试' };
    }
    const status = await peer.request('adbStatus', {}, 5000);
    if(this.service.getPeer(id)!==peer)throw Error('设备连接已改变');
    if(status.ready===true)return {connected:true,ready:true,name:peer.info.name,message:'手机助手已就绪，本组电脑可直接使用，无需连接数据线'};
    const executable=await this.locate();
    if(!executable)return {connected:false,message:'请在 Android Studio 的 SDK Tools 中安装 Android SDK Platform-Tools'};
    const devices=parseDevices(await this.command(executable,['devices','-l'],3000));
    let outdated=false,unmatched=false;
    for(const d of devices.filter(d=>d.status==='device' && !d.serial.includes(':') && !d.serial.includes('_adb-tls-'))) {
      if(await this.service.links.verifyUsb(id,d.serial)) {
        if(this.service.getPeer(id)!==peer)throw Error('设备连接已改变');
        return {connected:true,serial:d.serial,name:peer.info.name,message:'已识别 '+peer.info.name+'，点击连接自动配置 ADB'};
      }
      unmatched=true;
      try {const pkg=await this.command(executable,['-s',d.serial,'shell','dumpsys','package','com.lanlink.app'],3000);const version=/versionCode=(\d+)/.exec(pkg);if(version && Number(version[1])<22)outdated=true;}catch{}
    }
    return {connected:false,message:outdated?'已检测到 ADB 设备，但其中手机的 LanLink 版本过旧。请更新手机端至 0.3.21 或更新版本，再打开应用重试':devices.some(d=>d.status==='unauthorized')?'请解锁手机，允许 USB 调试并勾选始终允许此电脑':unmatched?'已检测到 ADB 设备，但尚未与这台组内手机核对成功。请确认连接的是目标手机，并在手机打开最新版 LanLink':'尚未检测到数据线连接，请连接手机并开启 USB 调试'};
  }
  async localCall(exe,serial,method) {
    const output=await this.command(exe,['-s',serial,'shell','content','call','--uri','content://com.lanlink.app.adbsetup','--method',method],5000);
    const encoded=/\bdata=([A-Za-z0-9+/=]+)/.exec(output);
    if(!encoded)throw Error(/\berror=([^}]+)/.exec(output)?.[1] || '请在手机打开新版 LanLink，并开启 USB 调试');
    try {return JSON.parse(Buffer.from(encoded[1],'base64').toString('utf8'));}catch{throw Error('手机设置响应无效');}
  }
  async localDevices() {
    const result=await this.devices();const exe=await this.locate();
    for(const d of result.devices) {d.authorized=false;if(d.status==='device')try{const r=await this.localCall(exe,d.serial,'status');d.authorized=r.ready===true;d.root=r.root===true;}catch{}}
    return result;
  }
  async authorizeLocal(serial) {
    if(typeof serial!=='string' || !serialPattern.test(serial))throw Error('请选择本机 ADB 连接的设备');
    if(this.busy)throw Error('正在配置其他手机，请稍后重试');this.busy=true;
    try {
      const exe=await this.locate();if(!exe)throw Error('请安装 Android SDK Platform-Tools');
      if(!parseDevices(await this.command(exe,['devices','-l'])).some(d=>d.serial===serial && d.status==='device'))throw Error('设备未连接或尚未允许 USB 调试');
      if((await this.localCall(exe,serial,'status')).ready)return {message:'该手机已就绪，无需重复授权'};
      const bootstrap=await this.localCall(exe,serial,'bootstrap');
      const command=helperCommand(bootstrap,await this.command(exe,['-s',serial,'shell','pm','path','com.lanlink.app']));
      const processes=await this.command(exe,['-s',serial,'shell','ps','-A','-o','PID,ARGS']);
      for(const pid of boundHelperPids(processes,bootstrap))await this.command(exe,['-s',serial,'shell','kill',pid]);
      await this.command(exe,['-s',serial,'shell',command]);
      for(let i=0;i<16;i++){await this.wait(250);if((await this.localCall(exe,serial,'status')).ready)return {message:'ADB 授权已完成。手机所属组和设备控制权限保持不变'};}
      throw Error('手机助手尚未就绪，请检查 USB 调试（安全设置）');
    } finally {this.busy=false;}
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
