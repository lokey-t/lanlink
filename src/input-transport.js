import { masterSelection } from './master.js';
import { createHash, randomUUID } from 'node:crypto';
import { hostname } from 'node:os';
import { commitConfig } from './identity.js';
import { HidReports, neutralReports, reportMap } from './hid-report.js';
import { InputQueue } from './input-queue.js';

export const modes = ['auto_bt_first', 'bluetooth_only', 'network_only'];
export function selectTransport(preference, bluetooth, network) {
  if (preference !== 'network_only' && bluetooth) return 'bluetooth';
  if (preference !== 'bluetooth_only' && network) return 'network';
  return 'none';
}

export class InputTransport {
  constructor(service) {
    this.service = service;
    this.hosts = new Map();
    this.claims = new Map();
    this.pairing = null;
    const stable = createHash('sha256').update('LanLink group HID ' + service.config.id).digest('hex');
    this.serviceId = stable.slice(0,8) + '-' + stable.slice(8,12) + '-' + stable.slice(12,16) + '-' +
      stable.slice(16,20) + '-' + stable.slice(20,32);
  }
  get records() { return this.service.config.inputTargets || {}; }
  record(id) {
    const record = Object.hasOwn(this.records,id) ? this.records[id] : null;
    return record && (record.external || record.group === this.service.group.data.id && !this.service.group.excluded(id)) ? record : {};
  }
  groupEnabled() { return this.service.config.groupHidEnabled !== false; }
  preference(id) {
    const record = this.record(id);
    const android = this.service.peers.get(id)?.info?.platform === 'android';
    return !record.external && (android || !this.groupEnabled()) ? 'network_only' : record.preference || modes[0];
  }
  async setGroupEnabled(enabled) {
    const s = this.service;
    if (!this.role() || typeof enabled !== 'boolean') throw Error('请在主设备设置 HID 通道');
    if (!enabled && (s.activeSession?.transport === 'bluetooth' && !s.activeSession.external ||
        s.controlIntent?.transport === 'bluetooth' && !s.controlIntent.external)) await s.returnLocal();
    await commitConfig(s.dir, s.config, () => ({ groupHidEnabled:enabled }));
    if (!enabled) {
      this.pairing = null;
      this.claims.clear();
      if (this.starting) await this.starting.catch(() => {});
      if (!Object.keys(this.records).some(id => this.record(id).external)) {
        this.started = this.manual = false;
        await s.native.call('hid-stop', {}, 2000).catch(() => {});
        await this.refresh();
      }
    } else await this.advertise(false).catch(() => {});
    s.emit('change');
    return {};
  }
  async openMasterBluetooth() {
    if (!this.role()) throw Error('请在主设备设置 HID 通道');
    if (!this.groupEnabled()) throw Error('请先开启 HID 通道');
    await this.advertise(false);
    return this.masterStatus();
  }
  async masterStatus() {
    await this.refresh();
    return {
      enabled:this.groupEnabled(),
      ready:this.groupEnabled() && this.adapter?.hidServiceReady === true,
      reason:this.adapter?.reason || '',
      connected:[...this.service.peers.keys()].filter(id => this.ready(id)),
    };
  }
  async guideTarget(id) {
    if (!this.role()) throw Error('请在主设备设置 HID 通道');
    if (!this.groupEnabled()) throw Error('请先开启 HID 通道');
    const { name } = await this.preparePairing(id, false);
    const peer = this.service.getPeer(id);
    try {
      const result = await peer.request('hid-connect-guide', { name, serviceId:this.serviceId }, 4000);
      if (!result?.opened) throw Error('请升级目标设备后重试');
      return { name };
    } catch (error) {
      if (this.pairing?.peer === peer) this.pairing = null;
      throw error;
    }
  }
  receiveGuide(peer, name, serviceId) {
    const s = this.service;
    if (masterSelection(s)?.id !== peer.remote.id || !s.currentPeer(peer) ||
        typeof name !== 'string' || !name.trim() || name.length > 120) throw Error('请求不是来自本组主设备');
    this.guide = { requestId:randomUUID(), id:peer.remote.id, name,
      direct:s.native.capabilities.hidDirectPairing === true && typeof serviceId === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(serviceId),
      serviceId, expires:Date.now() + 120000 };
    s.emit('change');
    return { opened:true };
  }
  guideNotice() {
    const s = this.service, guide = this.guide, peer = s.peers.get(guide?.id);
    return guide?.expires > Date.now() && masterSelection(s)?.id === guide.id &&
      peer && s.currentPeer(peer) && !s.group.excluded(guide.id) ? guide : null;
  }
  async finishGuide(requestId, cancel) {
    const guide = this.guideNotice();
    if (!guide || guide.requestId !== requestId) throw Error('请求已结束，请重新打开配对引导');
    if (!cancel) {
      const result = await this.requestPairing(guide.id, true);
      if (!result?.connected) throw Error('蓝牙键鼠尚未就绪');
    }
    if (this.guide === guide) this.guide = null;
    this.service.emit('change');
    return {};
  }
  async connectGuide(requestId) {
    const guide = this.guideNotice(), s = this.service;
    if (!guide?.direct || guide.requestId !== requestId) throw Error('请求已结束，请重新打开配对引导');
    if (this.connectingGuide) throw Error('正在连接蓝牙');
    this.connectingGuide = true;
    try {
      return await this.connectTargetGuide(guide, s.getPeer(guide.id));
    } finally { this.connectingGuide = false; }
  }
  async connectTargetGuide(guide, peer) {
    const s = this.service;
    const { serviceId } = await peer.request('bluetooth-offer', {}, 8500);
    if (serviceId !== guide.serviceId || this.guideNotice() !== guide) throw Error('蓝牙连接凭证无效');
    const device = await s.native.call('hid-find-device', { serviceId }, 15000);
    if (this.guideNotice() !== guide) throw Error('请求已结束，请重新打开配对引导');
    await peer.request('bluetooth-claim', { serviceId, nonce:device.nonce }, 3000);
    if (this.guideNotice() !== guide) throw Error('请求已结束，请重新打开配对引导');
    const { paired } = await s.native.call('hid-pair-device', { address:device.address, addressType:device.addressType }, 55000);
    if (!paired) throw Error('无法直接连接蓝牙，请在系统蓝牙设置中连接');
    for (let attempt = 0; attempt < 8 && this.guideNotice() === guide; attempt++) {
      const status = await peer.request('hid-link-status', {}, 1500);
      if (status?.ready) return this.finishGuide(guide.requestId, false);
      await new Promise(resolve => setTimeout(resolve, 500));
    }
    throw Error('蓝牙键鼠尚未就绪');
  }
  binding(id) {
    const s = this.service, peer = s.peers.get(id), claim = this.claims.get(id);
    if (peer?.info?.capabilities?.autoHidTarget === 1 && !this.record(id).host) {
      return claim?.peer === peer && claim.role === this.role() ? claim.host : undefined;
    }
    return this.record(id).host;
  }
  host(id) { return this.hosts.get(this.binding(id)); }
  role() {
    const s = this.service, choice = masterSelection(s);
    return choice?.id === s.config.id && !s.closing && !s.changingGroup
      ? JSON.stringify([choice.group, choice.revision, choice.origin]) : null;
  }
  checkGroupPeer(peer, role) {
    const s = this.service, id = peer.remote.id;
    if (!role || role !== this.role() || !s.currentPeer(peer) || s.group.excluded(id) ||
        peer.info?.capabilities?.autoHidTarget !== 1 || this.preference(id) === 'network_only')
      throw Error('蓝牙自动连接仅对当前主设备和已授权组员开放');
  }
  async offer(peer) {
    const role = this.role();
    this.checkGroupPeer(peer, role);
    if (!this.started || !this.adapter?.rendezvousReady) {
      if (Date.now() - (this.startAttempt || 0) > 5000) await this.advertise(false);
      else if (this.starting) await this.starting;
    }
    this.checkGroupPeer(peer, role);
    if (this.adapter?.rendezvousReady !== true) return {};
    return {serviceId:this.serviceId};
  }
  async claim(peer, args) {
    const role = this.role();
    this.checkGroupPeer(peer, role);
    if (args.serviceId !== this.serviceId || typeof args.nonce !== 'string' || !/^[a-f0-9]{32}$/.test(args.nonce))
      throw Error('蓝牙连接凭证无效');
    const result = await this.service.native.call('hid-claim', {nonce:args.nonce}, 1000);
    this.checkGroupPeer(peer, role);
    if (typeof result?.host !== 'string') throw Error('蓝牙连接凭证已过期');
    const host = this.hostKey(result.host), id = peer.remote.id;
    for (const [other, claim] of this.claims) {
      if (other !== id && claim.role === role && claim.host === host && this.service.currentPeer(claim.peer))
        throw Error('蓝牙连接已绑定到其他组员');
    }
    if (Object.keys(this.records).some(other => other !== id && this.record(other).host === host))
      throw Error('蓝牙连接已手动绑定到其他设备');
    this.claims.set(id, {peer, role, host});
    await this.refresh();
    return {};
  }
  hostKey(id) {
    return createHash('sha256').update(this.service.config.id).update(id).digest('hex');
  }
  masterChanged() {
    this.claims.clear();
    this.pairing = null;
    if (!this.role() && this.started && !this.manual) {
      this.started = false;
      this.adapter = {};
      this.hosts.clear();
      this.service.native.call('hid-stop', {}, 2000).catch(() => {});
    }
  }
  connected(id) {
    if (!this.groupEnabled() && !this.record(id).external) return false;
    const binding = this.binding(id), host = this.hosts.get(binding);
    return Date.now() - this.checkedAt < 4000 && this.adapter?.hidServiceReady === true &&
      host?.hostConnected === true && host.reportSubscribed === true && host.bonded !== false &&
      (this.claims.get(id)?.host === binding || this.record(id).verified === true);
  }
  ready(id) { return this.connected(id) && this.host(id)?.ready === true; }
  async refresh() {
    if (this.refreshing) return this.refreshing;
    this.refreshing = this.service.native.call('hid-status', {}, 800).then(state => {
      this.checkedAt = Date.now();
      this.adapter = state || {};
      this.hosts = new Map((state?.hosts || []).map(host => [
        this.hostKey(host.id),
        host,
      ]));
      return this.state();
    }).catch(() => { this.adapter = {}; this.hosts.clear(); return this.state(); })
      .finally(() => { this.refreshing = null; });
    return this.refreshing;
  }
  async advertise(manual = true) {
    this.manual ||= manual;
    if (this.starting) return this.starting;
    this.startAttempt = Date.now();
    this.starting = this.service.native.call('hid-start', { reportMap, serviceId:this.serviceId }, 6000)
      .then(async () => {
        if (!this.manual && !this.role()) {
          await this.service.native.call('hid-stop', {}, 2000).catch(() => {});
          return this.state();
        }
        this.started = true;
        return this.refresh();
      })
      .finally(() => { this.starting = null; });
    return this.starting;
  }
  async save(id, args) {
    const s = this.service, prior = this.record(id);
    const target = id || randomUUID();
    const external = prior.external === true || !id && args.external === true;
    if (!external && !s.group.data.members[target] && !s.config.knownDevices?.[target]) s.getPeer(target);
    if (!external && s.group.excluded(target)) throw Error('目标已退出本组');
    if (args.host && !args.systemPairing && s.peers.get(target)?.info?.capabilities?.autoHidTarget === 1)
      throw Error('该组设备随主设备自动连接蓝牙');
    if (args.preference !== undefined && !modes.includes(args.preference)) throw Error('键鼠通道偏好无效');
    const name = typeof args.name === 'string' ? args.name.trim().slice(0,80) : prior.name;
    if (external && !name && args.remove !== true) throw Error('请输入外部设备名称');
    const group = external ? undefined : s.group.data.id;
    const binding = args.host === undefined ? prior.host : args.host || undefined;
    if (args.host && (args.confirmed !== true || !(await this.refresh()).hosts.some(host => host.id === binding && host.reportSubscribed))) throw Error('请先确认目标已配对并订阅键鼠报告');
    if (args.host && Object.keys(this.records).some(key => key !== target && this.record(key).host === binding)) throw Error('这个蓝牙设备已绑定到其他目标');
    if (external && args.remove !== true && !binding) throw Error('外部设备必须绑定到已订阅的蓝牙目标');
    if ((!args.keepSession || args.preference === 'network_only' &&
        (s.activeSession?.transport === 'bluetooth' || s.controlIntent?.transport === 'bluetooth')) &&
        (s.active === target || s.controlIntent?.id === target)) await s.returnLocal();
    if (args.host) {
      if (s.active || s.switching || s.returning) throw Error('请先结束当前键鼠控制再绑定蓝牙目标');
      const bound = new Set(Object.keys(this.records).map(key => this.record(key).host).filter(Boolean));
      const unbound = [...this.hosts].filter(([key, host]) => !bound.has(key) && host.reportSubscribed);
      const claim = this.claims.get(target);
      const proven = args.systemPairing && claim?.host === binding && claim.role === this.role() && s.currentPeer(claim.peer);
      if (!proven && !bound.has(binding) && (unbound.length !== 1 || unbound[0][0] !== binding))
        throw Error('请断开其他尚未绑定的蓝牙设备后重试');
      const host = this.hosts.get(binding);
      if (!host?.ready || host.bonded === false) throw Error('目标蓝牙报告尚未就绪');
    }
    if (group && (s.group.data.id !== group || s.group.excluded(target))) throw Error('目标已退出本组');
    await commitConfig(s.dir, s.config, current => {
      const inputTargets = { ...current.inputTargets };
      if (args.remove === true) delete inputTargets[target];
      else inputTargets[target] = { group, external, name, host: binding, verified: !!binding && (args.host !== undefined ? args.confirmed === true : prior.verified === true), preference: args.preference || prior.preference || modes[0] };
      return { inputTargets };
    });
    s.emit('change');
    return this.state();
  }
  async groupHid() {
    const s = this.service;
    await this.refresh();
    const members = { ...s.group.data.members, ...s.config.knownDevices };
    for (const [id, peer] of s.peers) members[id] = peer.info || {};
    const ids = Object.keys(members).filter(id => id !== s.config.id && members[id]?.platform !== 'android' && !s.group.excluded(id));
    const group = s.group.data.id;
    const devices = await Promise.all(ids.map(async id => {
      const peer = s.peers.get(id), info = members[id];
      let incoming = null;
      if (peer?.info?.capabilities?.hidSettings === 1) {
        const status = await peer.request('hid-link-status', {}, 1500).catch(() => null);
        if (s.currentPeer(peer) && typeof status?.connected === 'boolean') incoming = status.connected;
      }
      return {
        id, name:info.name || '设备', platform:info.platform,
        incoming, outgoing:this.connected(id),
        enabled:this.preference(id) !== 'network_only',
      };
    }));
    const masterId = masterSelection(s)?.id;
    const local = masterId === s.config.id;
    const masterPeer = s.peers.get(masterId);
    const masterStatus = local ? await this.masterStatus() :
      await masterPeer?.request('hid-master-status', {}, 1500).catch(() => null);
    const readyTargets = new Set(Array.isArray(masterStatus?.connected) ? masterStatus.connected : []);
    return {
      devices: s.group.data.id === group ? devices.filter(d => !s.group.excluded(d.id)) : [],
      master: masterId && { id:masterId, local, name:local ? s.config.name : members[masterId]?.name || '设备', ...masterStatus },
      targets: s.group.data.id === group ? Object.entries({ [s.config.id]:{ name:s.config.name, platform:process.platform }, ...members })
        .filter(([id, info]) => id !== masterId && info?.platform !== 'android' && !s.group.excluded(id))
        .map(([id, info]) => ({ id, name:info.name || '设备', platform:info.platform, online:id === s.config.id || s.peers.has(id),
          connected:readyTargets.has(id) })) : [],
    };
  }
  async requestPairing(id, confirm) {
    const peer = this.service.getPeer(id);
    if (peer.info?.capabilities?.hidSettings !== 1) throw Error('请升级目标设备后重试');
    return peer.request(confirm ? 'hid-pair-confirm' : 'hid-pair-start', {}, 9000);
  }
  async preparePairing(id, manual = true) {
    const s = this.service, peer = s.getPeer(id);
    if (peer.info?.capabilities?.hidSettings !== 1) throw Error('请升级目标设备后重试');
    if (!['darwin', 'win32'].includes(process.platform)) throw Error('本机不支持蓝牙 HID 配对');
    if (!this.groupEnabled()) throw Error('请先开启 HID 通道');
    let {adapter} = await this.advertise(manual);
    for (let i = 0; i < 3 && !adapter.hidServiceReady && !['unauthorized', 'powered_off', 'unsupported'].includes(adapter.reason); i++) {
      await new Promise(resolve => setTimeout(resolve, 350));
      ({adapter} = await this.refresh());
    }
    if (adapter.peripheralSupported === false || !adapter.hidServiceReady ||
        ['unauthorized', 'powered_off', 'unsupported'].includes(adapter.reason))
      throw Error('蓝牙服务未就绪');
    if (!s.currentPeer(peer) || s.group.excluded(id)) throw Error('设备连接已结束');
    this.pairing = {peer, until:Date.now() + 120000};
    return { name:process.platform === 'darwin' ? 'LanLink' : hostname() };
  }
  async pairIncoming(peer, confirm) {
    const s = this.service, id = peer.remote.id;
    if (this.pairing?.peer !== peer || this.pairing.until < Date.now())
      throw Error('请先在对方设备的蓝牙 HID 键鼠页面开启配对');
    if (!confirm) return { name:process.platform === 'darwin' ? 'LanLink' : hostname() };
    if (this.connected(id)) {
      const claim = this.claims.get(id);
      if (!this.record(id).host && claim?.peer === peer && claim.role === this.role()) {
        await this.save(id, { host:claim.host, confirmed:true, systemPairing:true });
      }
    } else {
      const snapshot = await this.refresh();
      const reserved = new Set([
        ...Object.keys(this.records).map(key => this.record(key).host).filter(Boolean),
        ...[...this.claims.values()].filter(claim => s.currentPeer(claim.peer)).map(claim => claim.host),
      ]);
      const hosts = snapshot.hosts.filter(host => host.hostConnected && host.reportSubscribed && !reserved.has(host.id));
      if (hosts.length !== 1) throw Error('请确认系统已连接该蓝牙键鼠，并一次只配对一台设备');
      if (!s.currentPeer(peer) || this.pairing?.peer !== peer || this.pairing.until < Date.now() || s.group.excluded(id)) throw Error('设备连接已结束');
      await this.save(id, { host:hosts[0].id, confirmed:true, systemPairing:true });
    }
    this.pairing = null;
    return { connected:this.connected(id) };
  }
  describe(id, permission) {
    const record = this.record(id), host = this.host(id);
    const appAllowed = permission?.appEnabled === true && permission?.peerAllowed === true;
    const external = record.external === true;
    const peer = this.service.peers.get(id);
    const bluetooth = (external || appAllowed && peer?.info?.capabilities?.bluetoothControl === 1) && this.ready(id);
    const network = !external && appAllowed && permission?.supported !== false && permission?.systemGranted === true;
    const capability = this.adapter?.peripheralSupported;
    const active = this.service.active === id ? this.service.activeSession?.transport || 'network' : 'none';
    const selected = active === 'none' ? selectTransport(this.preference(id), bluetooth, network) : active;
    return {
      schemaVersion: 1, preference: this.preference(id), automaticGroup:peer?.info?.capabilities?.autoHidTarget === 1,
      authorization: external || appAllowed ? 'allowed' : 'denied',
      capabilities: { bluetooth: capability === true ? 'supported' : capability === false ? 'unsupported' : 'unknown', network: external || permission?.supported === false ? 'unsupported' : permission ? 'supported' : 'unknown' },
      readiness: {
        bluetooth: bluetooth ? 'ready' : !this.adapter?.hidServiceReady ? 'unavailable' : !this.binding(id) ? 'pairing_required' : host?.reportSubscribed ? 'degraded' : 'connecting',
        network: network ? 'ready' : 'unavailable',
      },
      bluetooth: {
        bonded: host?.bonded ?? null, hostConnected: host?.hostConnected === true,
        reportSubscribed: host?.reportSubscribed === true, inputVerified: this.ready(id),
        standby: bluetooth && active !== 'bluetooth' && this.preference(id) !== 'network_only',
      },
      availability: bluetooth ? network ? 'both' : 'bluetooth_only' : network ? 'network_only' : 'unavailable',
      active, selected,
    };
  }
  state() {
    const s = this.service;
    const peers = [...s.peers].filter(([,p]) => p.info?.platform !== 'android')
      .map(([id,p]) => ({ id, name:p.info?.name || '设备', ...this.describe(id, p.access?.permissions?.input) }));
    const external = Object.keys(this.records).filter(id => this.record(id).external).map(id => ({ id, name:this.record(id).name, external:true, ...this.describe(id) }));
    const { adapterPresent, peripheralSupported, hidServiceReady, advertising, reason } = this.adapter || {};
    return {
      adapter: { adapterPresent, peripheralSupported, hidServiceReady, advertising, reason },
      guide:this.guideNotice(),
      hosts: [...this.hosts].map(([id,h]) => ({ id, bonded:h.bonded, hostConnected:h.hostConnected, reportSubscribed:h.reportSubscribed })),
      targets: [...peers, ...external].map(target => ({ ...target, host:this.binding(target.id) })),
    };
  }
  async send(host, reports) {
    if (!reports.length) return;
    if (process.platform === 'darwin' && host?.link === true) {
      const frames = reports.map(report => Array.isArray(report) ? report[1] : report);
      const result = await this.service.native.call('bt-input', { host:host.id, frames }, 1500);
      if (result?.sent !== true) throw Error('蓝牙输入通道尚未就绪');
    } else {
      await this.service.native.call('hid-send', { host:host.id, reports }, 900);
    }
  }
  queue(session) {
    const encoder = new HidReports();
    return new InputQueue(async (_method, event) => {
      if (this.service.activeSession !== session || !session.committed) return;
      if (!session.external && (this.preference(session.id) === 'network_only' || !this.ready(session.id) || this.host(session.id)?.id !== session.host.id || Date.now() >= session.leaseUntil)) throw Error('蓝牙控制租约已过期');
      await this.send(session.host, encoder.encode(event));
    }, { adaptive:false, motionInterval:12 });
  }
  async release(session, requireConfirmation = false) {
    if (session?.transport !== 'bluetooth') return;
    try { await this.send(session.host, neutralReports); }
    catch (error) { if (requireConfirmation) throw error; }
  }
}
