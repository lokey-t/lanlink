import {Updater} from './updater.js';
import {buildInfo} from './build-info.js';
import {applySettings} from './settings.js';
import {languagePreference} from './language.js';
import { ClipboardSync } from './clipboard-sync.js';
import {Staging} from './staging.js';
import {BulkHost,receiveBulk,receiveQuicFile} from './bulk.js';
import { masterChoices, masterSelection, applyMaster, initializeMaster } from './master.js';
import {layoutSelection,layoutDevices,layoutEdges,initializeLayout,applyLayout,layoutExchange,rememberLayoutVersion} from './layout.js';
import net from 'node:net';
import { EventEmitter } from 'node:events';
import { randomUUID, createHash } from 'node:crypto';
import { mkdir, open, stat, rename, rm } from 'node:fs/promises';
import path from 'node:path';
import { Peer } from './wire.js';
import { parseToken, saveConfig, commitConfig, accountId } from './identity.js';
import { startDiscovery } from './discovery.js';
import { EdgeSwitch } from './edge.js';
import { InputQueue } from './input-queue.js';
import { Pairing } from './pairing.js';
import { Group } from './group.js';
import { Consent, permissionNames } from './consent.js';
import { VideoHost, VideoViewer } from './video.js';
import { permissionReport, showPermissionGuide, receivePermissionToggle } from './permissions.js';
import { AdbManager } from './adb.js';
import { LinkManager } from './links.js';
import {QuicManager} from './quic.js';
import { AdbGuide } from './adb-guide.js';
import { Access } from './access.js';
import { AndroidRecovery } from './android-recovery.js';

const CHUNK = 192 * 1024, MAX_FILE = 2 * 1024 ** 3;
export function safeName(name) {
  if (typeof name !== 'string' || !name || name.length > 180 || /[<>:"/\\|?*\x00-\x1f]/.test(name) || /[. ]$/.test(name) || /^\.+$/.test(name) || /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(name)) throw new Error('文件名不安全或不兼容');
  return name;
}
export function validateInput(a) {
  if (!a || typeof a !== 'object') throw new Error('输入无效');
  if (a.kind === 'relative' && [a.dx, a.dy].every(n => typeof n === 'number' && Number.isFinite(n) && Math.abs(n) <= 1000)) return a;
  if (a.kind === 'move' && [a.x, a.y].every(n => typeof n === 'number' && Number.isFinite(n) && n >= 0 && n <= 1)) return a;
  if (a.kind === 'key' && /^(Key[A-Z]|Digit[0-9]|Numpad[0-9]|F([1-9]|1[0-2])|Arrow(Left|Right|Up|Down)|Control(Left|Right)|Shift(Left|Right)|Alt(Left|Right)|Meta(Left|Right)|Backspace|Tab|Enter|Escape|Space|Delete|Insert|Home|End|PageUp|PageDown|CapsLock|Semicolon|Equal|Comma|Minus|Period|Slash|Backquote|BracketLeft|BracketRight|Backslash|Quote)$/.test(a.code) && typeof a.down === 'boolean') return a;
  if (a.kind === 'button' && [0, 1, 2].includes(a.button) && typeof a.down === 'boolean') return a;
  if (a.kind === 'wheel' && Number.isFinite(a.delta) && Math.abs(a.delta) <= 2400) return a;
  throw new Error('输入事件无效');
}
export class Service extends EventEmitter {
  constructor(config, dir, native, options = {}) {
    super(); Object.assign(this, { config, dir, native, options }); this.peers = new Map(); this.sockets = new Set(); this.connecting = new Map(); this.edge = new EdgeSwitch(); this.offers = new Map(); this.position = { x: .5, y: .5 }; this.keys = new Set(); this.logs = []; this.owner = null; this.active = null;
    this.config.deviceGrants ||= {}; this.consent = new Consent(this.config.deviceGrants); this.permissionNotice = null;
    this.group = new Group(this);this.clipboardSync=new ClipboardSync(this);this.staging=new Staging(this); this.config.pausedPeers ||= {};
    this.adb = new AdbManager(this); this.links = new LinkManager(this);this.quic=new QuicManager(this); this.adbGuide = new AdbGuide(this); this.access = new Access(this);
    this.outgoingIntents = new Set(); this.screenViewers = new Map(); this.screenTargets = new Map(); this.returning = 0; this.messageTasks = new Set();
    this.videoHost = new VideoHost(this); this.videoViewer = new VideoViewer(this);this.bulk=new BulkHost(this);
    this.androidRecovery = new AndroidRecovery(this);
    if (this.config.master === undefined && Object.values(this.config.edges || {}).some(Boolean)) this.config.master = true;
    this.native.on('event', e => this.nativeEvent(e).catch(err => this.log(err.message)));
    this.native.on('diagnostic', m => this.log(m));
  }
  log(message) { this.logs.unshift({ time: Date.now(), message: String(message).slice(0, 300) }); this.logs.length = Math.min(this.logs.length, 30); this.emit('change'); }
  trackTask(task) { this.messageTasks.add(task); const done = () => this.messageTasks.delete(task); task.then(done, done); return task; }
  identity() { return { id: this.config.id, name: this.config.name, platform: process.platform, masterSelection:masterSelection(this),layout:layoutSelection(this),capabilities: { ...this.native.capabilities,staging:1,quic:this.quic.enabled?1:0,motionV2:this.quic.native?.binaryMotion?2:0,bulk:1,rtcVideo:1, controlPrepare: true, dualLink: true, memberKey: this.group.own.key } }; }
  async start() {
    this.updater=new Updater(this);
    if (this.config.pendingGroupMerge) {
      const pendingMerge = this.config.pendingGroupMerge;
      this.group.adopt(pendingMerge.group, pendingMerge.token);
      for (const id of this.group.data.removed) this.group.forget(id);
      delete this.config.pendingGroupMerge;
    }
    const pending = this.config.pendingGroupJoin;
    if (this.config.dissolvedGroups?.[this.group.data.id] || pending && this.config.dissolvedGroups?.[pending.previousGroup]) {
      if (pending?.group) this.group.adopt(pending.group, pending.token);
      else { const { newToken } = await import('./identity.js'); this.config.token = newToken(); delete this.config.group; this.group = new Group(this);this.clipboardSync=new ClipboardSync(this); }
      this.config.knownDevices = {}; this.config.deviceGrants = {}; this.config.edges = {}; this.config.pausedPeers = {};delete this.config.layout;delete this.config.masterSelection;this.config.master=false;this.consent.reset(this.config.deviceGrants);
    }
    delete this.config.pendingGroupJoin;
    await initializeMaster(this);await initializeLayout(this);await saveConfig(this.dir, this.config);await this.staging.load(); this.group.start(); await this.native.start(); this.secret = parseToken(this.config.token);
    this.server = net.createServer(s => this.attach(s));
    await new Promise((resolve, reject) => { this.server.once('error', reject); this.server.listen(this.options.port ?? 45831, this.options.host || '0.0.0.0', resolve); });
      this.server.on('error', e => this.log('网络监听失败：' + e.message)); this.port = this.server.address().port;
    this.pairing = new Pairing(this); await this.pairing.start();
    await this.quic.start();
    if (!this.options.noDiscovery) this.stopDiscovery = startDiscovery(this.identity(), this.secret, this.port, (b, address) => {
      if (this.config.id < b.id) { if (!this.peers.has(b.id)) this.connect(address, b.port); else if (this.peers.get(b.id).socket.remoteAddress !== address) this.links.candidate(b.id, address); }
    }, e => this.log('自动发现不可用，可手动连接：' + e.message));
    this.adbTimer = setInterval(() => this.adb.tick().catch(e => this.log(e.message)), 10000); this.adbTimer.unref();
    this.linkTimer = setInterval(() => this.links.tick().catch(() => {}), 4000); this.linkTimer.unref();
    this.heartbeat = setInterval(() => this.tick().catch(e => this.log(e.message)), 1000);
    this.edgeTimer = setInterval(() => { if (this.active && this.capture && !this.switching) this.checkReturnEdge(); }, 25);
    if (this.config.permissions.clipboard && this.clipboardSync.upload) await this.snapshotClipboard().catch(e => this.log(e.message));
  }
  attach(socket, outgoing = false) {
    if (this.sockets.size >= 32) { socket.destroy(); return; }
    const peer = new Peer(socket, this.identity(), this.secret); peer.groupId=this.group.data.id;peer.outgoing = outgoing; this.sockets.add(peer);
    peer.on('ready', () => {
      if (!this.peerGroupCurrent(peer)||this.group.excluded(peer.remote.id)) { peer.close(); return; }
      const prior = this.peers.get(peer.remote.id);
      if (prior && !prior.closed && prior.socket.remoteAddress !== peer.socket.remoteAddress) {
        this.links.candidate(peer.remote.id, peer.socket.remoteAddress).catch(() => {});
        peer.close(); return;
      }
      if (prior && !prior.closed) { const preferOutgoing = this.config.id < peer.remote.id; if (peer.outgoing === preferOutgoing && prior.outgoing !== preferOutgoing) { this.peers.set(peer.remote.id, peer); prior.close(); } else { peer.close(); return; } }
      this.peers.set(peer.remote.id, peer); this.links.wrap(peer); peer.lastSeen = Date.now(); this.log('已建立加密连接');
      this.clipboardSync.sendInventory(peer);if (this.config.permissions.clipboard && this.canShareClipboard(peer.remote.id) && this.clipboardSync.latest?.source === this.config.id) peer.send({ type: 'clipboard', offer: this.clipboardSync.latest });
    });
    peer.on('message', m => {
      this.trackTask(this.message(peer, m).catch(e => this.log(e.message)));
    });
    peer.on('closed', () => {
      this.sockets.delete(peer);
      if (this.peers.get(peer.remote?.id) !== peer) return;
      this.androidRecovery.disconnected(peer);
      this.peers.delete(peer.remote.id);this.staging.disconnected(peer.remote.id);
      this.screenViewers.delete(peer.remote.id); this.screenTargets.delete(peer.remote.id);
      for (const intent of this.outgoingIntents) if (intent.id === peer.remote.id) intent.cancelled = true;
      if (this.controlIntent?.id === peer.remote.id) this.controlIntent.cancelled = true;
      this.videoHost.closePeer(peer.remote.id); this.videoViewer.close(peer.remote.id).catch(() => {});
      this.consent.disconnect(peer.remote.id); if (this.permissionNotice?.id === peer.remote.id) this.permissionNotice = null;
      if (this.active === peer.remote.id) this.returnLocal().catch(e => this.log(e.message));
      if (this.owner?.id === peer.remote.id) { this.clearInboundEdge(); this.owner = null; this.native.call('release').catch(() => {}); }
      this.emit('change');
    });
    return peer;
  }
  connect(host, port = 45831) {
    if (typeof host !== 'string' || !/^[a-zA-Z0-9.:_-]{1,253}$/.test(host) || !Number.isInteger(port) || port < 1 || port > 65535) throw new Error('设备地址或端口无效');
    if(this.closing)return Promise.reject(Error('服务正在退出'));
    const key=host+':'+port,existing=this.connecting.get(key);if(existing)return existing.promise;
    const socket=net.connect({host,port});let peer,timer;
    const promise=new Promise((resolve,reject)=>{
      let settled=false;
      const finish=(error,result)=>{if(settled)return;settled=true;clearTimeout(timer);this.connecting.delete(key);if(error){socket.destroy();reject(error);}else resolve(result);};
      const connected=()=>{const active=peer?.remote && this.peers.get(peer.remote.id);if(active&&!active.closed)finish(null,{id:peer.remote.id,message:'已建立加密连接'});};
      timer=setTimeout(()=>finish(Error('连接超时，请确认地址、端口及对方已启动 LanLink')),6500);
      socket.once('connect',()=>{peer=this.attach(socket,true);if(!peer){finish(Error('连接数量已满，请稍后重试'));return;}peer.once('ready',connected);peer.once('closed',()=>{connected();finish(Error(peer.failure==='Unauthorized'?'配对认证失败，请确认两台设备属于同一组':'设备连接已结束，请确认配对状态'));});});
      socket.once('error',error=>finish(Error(error.code==='ECONNREFUSED'?'连接被拒绝，请确认对方已启动 LanLink 且端口正确':error.code==='ENOTFOUND'?'找不到此设备地址，请检查输入':'连接失败：'+error.message)));
      socket.once('close',()=>{connected();finish(Error('连接已结束，请检查设备地址及配对状态'));});
    });
    this.connecting.set(key,{promise,socket});promise.catch(()=>{});return promise;
  }
  peerGroupCurrent(peer){
    if(this.peerToken!==this.config.token){this.peerSecret=parseToken(this.config.token);this.peerToken=this.config.token;}
    return peer.groupId===this.group.data.id&&Buffer.isBuffer(peer.secret)&&peer.secret.equals(this.peerSecret);
  }
  currentPeer(peer){return !peer.closed&&this.peers.get(peer.remote.id)===peer&&this.peerGroupCurrent(peer);}
  getPeer(id) { const p = this.peers.get(id); if (!p || !this.currentPeer(p)) throw new Error('设备不在线'); return p; }
  allow(name) { if (!this.config.permissions[name]) throw new Error('目标设备尚未允许此功能：' + name); }
  liveScreens(map) { for (const [id, until] of map) if (until <= Date.now()) map.delete(id); return [...map.keys()]; }
  assertCanReceiveControl() {
    if (this.closing || this.changingGroup) throw Error('CONTROL_ENDED: 当前连接正在结束');
    if (this.active || this.switching || this.returning || this.outgoingIntents.size || this.liveScreens(this.screenTargets).length || [...this.videoViewer.sessions.values()].some(s => !s.closed)) throw Error('CONTROL_CONFLICT: 本设备正在控制或查看其他设备，请先结束当前会话');
  }
  assertCanSendControl() {
    if (this.inputDesktopBlocked) throw Error('CONTROL_ENDED: 系统弹窗正在接管输入，请在本机完成操作后重新连接');
    if (this.closing || this.changingGroup) throw Error('CONTROL_ENDED: 当前连接正在结束');
    if (this.owner?.until > Date.now() || this.capturing || this.videoHost.sessions.size || this.liveScreens(this.screenViewers).length) throw Error('CONTROL_CONFLICT: 本设备正在被控制或查看，请先结束被控会话');
  }
  beginOutgoing(id) { this.assertCanSendControl(); this.getPeer(id); const intent = { id, cancelled: false }; this.outgoingIntents.add(intent); return intent; }
  checkOutgoing(intent) { if (intent.cancelled) throw Error('CONTROL_ENDED: 控制请求已取消'); this.assertCanSendControl(); this.getPeer(intent.id); }
  endOutgoing(intent) { this.outgoingIntents.delete(intent); }
  async screenFrom(id) {
    const intent = this.beginOutgoing(id);
    try { const frame = await this.getPeer(id).request('screen'); this.checkOutgoing(intent); this.screenTargets.set(id, Date.now() + 4000); return frame; }
    catch (e) { if (intent.cancelled) await this.peers.get(id)?.request('screenStop', {}, 1500).catch(() => {}); throw e; }
    finally { this.endOutgoing(intent); }
  }
  openStream(id, jpegOnly = false) { return this.videoViewer.open(id, jpegOnly); }
  async closeStream(id) { const hadScreen = this.screenTargets.delete(id); for (const intent of this.outgoingIntents) if (intent.id === id) intent.cancelled = true; await this.videoViewer.close(id); if (hadScreen) await this.peers.get(id)?.request('screenStop', {}, 1500).catch(() => {}); }
  showFallback(id, edge, capture) { this.fallbackNotice = { id, edge, capture }; this.edge.reset(); this.emit('change'); }
  async motionInputs(peer,inputs){
    if(inputs.length<2||this.native.capabilities.inputBatch!==1){for(const input of inputs)await this.handle(peer,'input',input);return;}
    this.requireOwner(peer);const checked=inputs.map(validateInput);
    if(checked.length>64||checked.some(input=>!['move','relative'].includes(input.kind)))throw Error('无效批量鼠标输入');
    this.edge.suppress();this.cancelEdgeFeedback();await this.native.call('inputBatch',{inputs:checked});
  }
  needsFallback(peer, capabilities = peer.info?.capabilities) { return peer.info?.platform === 'android' && capabilities?.inputMode === 'accessibility-limited'; }
  canShareClipboard(id) { return !Object.hasOwn(this.config.deviceGrants,id) || this.consent.allowed(id, 'clipboard'); }
  authorize(peer, permission) {
    if (this.config.dissolvedGroups?.[this.group.data.id]) throw Error('当前组已解散');
    if (this.group.excluded(peer.remote.id) || !this.currentPeer(peer)) throw new Error('设备连接已结束');
    if (this.config.pausedPeers[peer.remote.id]) throw Error('CONTROL_ENDED: 被控设备已结束控制，请重新请求');
    if (permission === 'input' || permission === 'screen') this.assertCanReceiveControl();
    // A local user who is actively controlling a target may transfer their clipboard to it.
    // This is session-scoped and never overrides an explicit saved per-device denial.
    if (permission !== 'clipboard' || !this.canShareClipboard(peer.remote.id)) this.consent.require(peer.remote.id, peer.info?.name, permission);
    if (!this.config.permissions[permission] || this.native.capabilities[permission] === false) {
      this.permissionNotice = { requestId: randomUUID(), id: peer.remote.id, name: peer.info?.name || '已配对设备', permission, expires: Date.now() + 120000 };
      throw new Error('PERMISSION_REQUIRED: 被控设备尚未允许此功能或缺少系统权限，请在被控设备开启');
    }
  }
  decideConsent(a){return this.configure(()=>this.applyConsentDecision(a));}
  async applyConsentDecision(a) {
    this.getPeer(a.id); const priorGrant = this.config.deviceGrants[a.id]; this.consent.decide(a.id, a.requestId, a.accepted === true, a.permissions);
    const previous = { ...this.config.permissions };
    if (a.accepted === true) delete this.config.pausedPeers[a.id];
    if (a.accepted === true) for (const k of ['input', 'screen', 'clipboard', 'shutdown']) if (a.permissions?.[k] === true) this.config.permissions[k] = true;
    try { await saveConfig(this.dir, this.config); } catch (e) { this.config.permissions = previous; if (priorGrant === undefined) delete this.config.deviceGrants[a.id]; else this.config.deviceGrants[a.id] = priorGrant; throw e; } this.emit('change');
  }
  async revokeConsent(id) {
    if (typeof id !== 'string' || !Object.hasOwn(this.config.deviceGrants,id) && !this.peers.has(id) && !this.group.data.members[id] && !this.config.knownDevices?.[id]) return;
    return this.setConsent(id,Object.fromEntries(permissionNames.map(key=>[key,false])));
  }
  setConsent(id,permissions){return this.configure(()=>this.applyConsent(id,permissions));}
  async applyConsent(id,permissions) {
    if(typeof id!=='string'||id===this.config.id||this.group.excluded(id)||!this.peers.has(id)&&!this.group.data.members[id]&&!this.config.knownDevices?.[id])throw Error('请选择本组的其他设备');
    if(!permissions||typeof permissions!=='object'||Array.isArray(permissions)||!Object.keys(permissions).length||Object.entries(permissions).some(([key,value])=>!permissionNames.includes(key)||typeof value!=='boolean'))throw Error('设备权限无效');
    const grant=Object.fromEntries(permissionNames.map(key=>[key,Object.hasOwn(permissions,key)?permissions[key]:this.consent.allowed(id,key)]));
    const pausedPeers={...this.config.pausedPeers};if(Object.values(grant).some(Boolean))delete pausedPeers[id];
    await commitConfig(this.dir,this.config,current=>({deviceGrants:{...current.deviceGrants,[id]:grant},pausedPeers}));
    this.consent.grants=this.config.deviceGrants;this.consent.disconnect(id);this.consent.denied.delete(id);
    if(Object.values(grant).some(Boolean))delete this.config.pausedPeers[id];
    const warnings=[];const effect=async work=>{try{await work();}catch(error){warnings.push(error.message);this.log('授权已保存，运行状态恢复失败：'+error.message);}};
    const endsInput=!grant.input&&this.owner?.id===id;
    const endsScreen=!grant.screen&&(this.liveScreens(this.screenViewers).includes(id)||[...this.videoHost.sessions.values()].some(session=>session.peer.remote.id===id));
    if(endsInput||endsScreen)try{this.peers.get(id)?.send({type:'control-ended',...(!endsScreen&&this.owner?.edgeSession?{session:this.owner.edgeSession}:{})});}catch{}
    if(!grant.screen){this.screenViewers.delete(id);this.videoHost.closePeer(id);}
    if(!grant.input&&this.owner?.id===id){this.clearInboundEdge();this.owner=null;await effect(()=>this.native.call('release'));}
    if(!grant.clipboard){if(this.clipboard?.source===id)this.clipboard=null;await effect(()=>this.staging.cancelPeer(id));}
    if(!grant.shutdown)this.cancelShutdown();this.emit('change');return warnings.length?{message:'授权已保存，部分运行状态未能恢复，请重启服务',warning:warnings.join('；')}:{};
  }
  requireOwner(peer) { this.authorize(peer, 'input'); if (this.owner?.id !== peer.remote.id || this.owner.until < Date.now()) throw new Error('请先取得控制权'); this.owner.until = Date.now() + 4000; }
  async message(peer, m) {
    if(this.closing) return;
    if(!this.currentPeer(peer))return;
    peer.lastSeen = Date.now();
    if (this.group.excluded(peer.remote.id)) { peer.close(); return; }
    if (m.type === 'group-dissolved') { await this.group.acceptDissolution(m.notice); return; }
    if (m.type === 'group-members') { await this.group.merge(m);if(!this.currentPeer(peer))return;await initializeMaster(this);await initializeLayout(this);return; }
    if (m.type === 'group-removed') { await this.leaveGroup(); return; }
    if (m.type === 'control-ended') {
      if(m.session && m.session!==this.activeSession?.edgeSession && m.session!==this.controlIntent?.edgeSession)return;
      this.controlEnded = { id: peer.remote.id, at: Date.now() }; if (this.active === peer.remote.id || this.controlIntent?.id === peer.remote.id) await this.returnLocal(); await this.closeStream(peer.remote.id); return;
    }
    if (m.type === 'edge-glow') { this.inboundEdge(peer,m);return; }
    if(m.type==='identity')queueMicrotask(()=>this.quic.connect(peer).catch(()=>{}));
    if (m.type === 'identity') {
      peer.info={name:String(m.name).slice(0,80),platform:String(m.platform).slice(0,30),capabilities:m.capabilities||{}};this.androidRecovery.identity(peer);
      if(this.active===peer.remote.id&&this.needsFallback(peer)&&!this.fallbackAccepted){const edge=this.enteredEdge,capture=this.capture;await this.returnLocal();if(!this.currentPeer(peer))return;this.showFallback(peer.remote.id,edge,capture);}
      this.config.knownDevices||={};const old=this.config.knownDevices[peer.remote.id],info={name:peer.info.name,platform:peer.info.platform};this.config.knownDevices[peer.remote.id]=info;
      if(JSON.stringify(old)!==JSON.stringify(info))await saveConfig(this.dir,this.config);
      if(!this.currentPeer(peer))return;
      if(this.group.learn({id:peer.remote.id,...info,key:m.capabilities?.memberKey})){await saveConfig(this.dir,this.config);if(!this.currentPeer(peer))return;this.group.publish();}
      await initializeMaster(this);if(!this.currentPeer(peer))return;await initializeLayout(this);if(!this.currentPeer(peer))return;
      if(m.masterSelection)await applyMaster(this,m.masterSelection);if(m.layout)await applyLayout(this,m.layout);this.emit('change');return;
    }
    if(m.type==='master-selection'){await applyMaster(this,m.choice);return;}
    if(m.type==='layout-update'){await applyLayout(this,m.layout);return;}
    if (m.type === 'ping'||m.type==='pong') { rememberLayoutVersion(this,peer,m.layoutVersion);if(m.masterSelection)await applyMaster(this,m.masterSelection);if(m.layout)await applyLayout(this,m.layout);if(m.type==='ping'&&this.currentPeer(peer))peer.send({ type: 'pong',masterSelection:masterSelection(this),...layoutExchange(this,peer) });return; }
    if(m.type==='clipboard'){await this.clipboardSync.receive(peer,m.offer);return;}
    if(m.type==='clipboard-inventory'){await this.clipboardSync.inventory(peer,m.ids);return;}
    if(m.type==='clipboard-record'){await this.clipboardSync.record(peer,m.record);return;}
    if (m.type !== 'request' || typeof m.id !== 'string' || m.id.length > 64) return;
    peer.inFlight = (peer.inFlight || 0) + 1;
    if (peer.inFlight > 32) { peer.close(); return; }
    try { const result = await this.handle(peer, m.method, m.args || {}); if (!peer.closed) peer.send({ type: 'response', id: m.id, result }); }
    catch (e) { if (!peer.closed) peer.send({ type: 'response', id: m.id, error: e.message }); }
    finally { peer.inFlight--; }
  }
  async handle(peer, method, a) {
    if (this.group.excluded(peer.remote.id)) throw Error('设备已移出组');
    if (!this.currentPeer(peer)) throw Error('设备连接已结束');
    if (method === 'permission-guide-open') return showPermissionGuide(this, a.permission, peer.remote.id, peer.info?.name);
    if (method === 'permission-toggle') return receivePermissionToggle(this, peer, a.permission, a.enabled);
    if(method==='permission-request') { const keys = Array.isArray(a.permissions) ? [...new Set(a.permissions.filter(key => permissionNames.includes(key)))] : permissionNames; if (!keys.length) throw Error('请选择需要的权限'); const ready = this.consent.request(peer.remote.id,peer.info?.name,Date.now(),keys) === true;this.emit('change');return {ready}; }
    if(method==='permission-cancel') {this.consent.disconnect(peer.remote.id);this.emit('change');return {};}
    if(method==='permission-prepare') {
      await this.refreshCapabilities();
      if(!this.currentPeer(peer))throw Error('设备连接已结束');
      const permission=(a.permissions||[]).find(k=>['input','screen','clipboard','shutdown'].includes(k) && (!this.config.permissions[k] || !this.native.capabilities[k]));
      if(permission)this.permissionNotice={requestId:randomUUID(),id:peer.remote.id,name:peer.info?.name,permission,expires:Date.now()+120000};
      this.emit('change');return {};
    }
    if (method === 'permission-status') {
      await this.refreshCapabilities();if(!this.currentPeer(peer))throw Error('设备连接已结束');return permissionReport(this, peer.remote.id);
    }
    if (method === 'group-update') { await this.group.accept(a.box); return {}; }
    if (method === 'group-dissolve') { await this.group.acceptDissolution(a.notice); return {}; }
    if (method === 'linkOpen') return this.links.offer(peer);
    if(method==='quicOpen')return this.quic.offer(peer);
    if (method === 'linkPing') return {};
    if(method==='bulkOpen'){this.authorize(peer,'clipboard');if(!Number.isSafeInteger(a.end)||a.end<a.offset)throw Error('文件范围无效');return this.bulk.open(peer,a);}
    if (method === 'inputReset') { this.requireOwner(peer); await this.native.call('release'); return {}; }
    if (method === 'videoOpen') return this.links.reverseVideo(peer, await this.videoHost.open(peer, a));
    if (method === 'videoKeep') return this.videoHost.keep(peer, a.id, a.feedback);
    if(method==='videoKey')return this.videoHost.keep(peer,a.id,{needKey:true});
    if (method === 'videoStop') return this.videoHost.stop(peer, a.id);
    if (method === 'screenStop') { this.screenViewers.delete(peer.remote.id); return {}; }
    if (method === 'prepare' || method === 'acquire') {
      this.assertCanReceiveControl();
      if (a.pointer === true && masterSelection(this) && masterSelection(this).id !== peer.remote.id) throw Error('CONTROL_CONFLICT: 只有本组选择的主控鼠标可以跨屏控制');
      if (a.newSession === true && this.config.pausedPeers[peer.remote.id]) { delete this.config.pausedPeers[peer.remote.id]; await saveConfig(this.dir, this.config); }
      this.consent.require(peer.remote.id, peer.info?.name, 'input');
      if (a.screen === true) this.consent.require(peer.remote.id, peer.info?.name, 'screen');
      await this.refreshCapabilities();
      this.authorize(peer, 'input');
      if (a.screen === true) this.authorize(peer, 'screen');
      if (a.pointer === true && masterSelection(this) && masterSelection(this).id !== peer.remote.id) throw Error('CONTROL_CONFLICT: 只有本组选择的主控鼠标可以跨屏控制');
      if (this.owner && this.owner.id !== peer.remote.id && this.owner.until > Date.now()) throw new Error('CONTROL_CONFLICT: 设备正在被其他来源控制');
      if (method === 'prepare') return { ready: true };
      this.clearInboundEdge();this.owner = { id: peer.remote.id, until: Date.now() + 4000, pointer:a.pointer===true, edgeSession:typeof a.edgeSession==='string'&&a.edgeSession.length===36?a.edgeSession:null, glowSeq:0 }; return this.native.capabilities;
    }
    if (method === 'release') { this.screenViewers.delete(peer.remote.id); if (this.owner?.id === peer.remote.id) { if(!a.edgeExit || !this.inboundEdge(peer,a.edgeExit,true))this.clearInboundEdge();this.owner = null; await this.native.call('release'); } return {}; }
    if (method === 'keepControl') { this.requireOwner(peer); await this.native.call('heartbeat'); return {}; }
    if (method === 'input') {
      this.requireOwner(peer); const input = validateInput(a);
      // Network motion (including loss recovery and click snapshots) is not a
      // local edge gesture. Keep an injected boundary position disarmed even
      // if this control lease ends before the next native cursor sample.
      if (input.kind === 'move' || input.kind === 'relative') { this.edge.suppress(); this.cancelEdgeFeedback(); }
      return this.native.call('input', input);
    }
    if (method === 'text') { this.requireOwner(peer); if (typeof a.text !== 'string' || a.text.length > 100000) throw new Error('文本过长'); return this.native.call('text', a); }
    if (method === 'screen') { this.authorize(peer, 'screen'); if (this.capturing) throw new Error('屏幕正在捕获'); this.capturing = true; this.screenViewers.set(peer.remote.id, Date.now() + 4000); try { const frame = await this.native.call('screen'); this.authorize(peer, 'screen'); return frame; } catch (e) { this.screenViewers.delete(peer.remote.id); throw e; } finally { this.capturing = false; } }
    if(method==='clipboard-sync'){if(!this.clipboardSync.allowed(peer.remote.id))throw Error('剪贴板共享未授权');await this.clipboardSync.inventory(peer,a.ids);return {latest:this.clipboardSync.latest};}
    if (method === 'clipboardGet') { this.authorize(peer, 'clipboard'); return this.snapshotClipboard(false); }
    if (method === 'stage-preview') return this.staging.applyPreview(peer,a.preview);
    if (method === 'stage-offer') return this.staging.accept(peer,a);
    if (method === 'stage-status') {this.authorize(peer,'clipboard');return this.staging.available(peer,a.id);}
    if (method === 'stage-begin') {this.authorize(peer,'clipboard');return this.staging.begin(peer,a.id,a.resume===true);}
    if (method === 'stage-complete') return this.staging.complete(peer,a.id);
    if (method === 'stage-cancel') return this.staging.remoteCancel(peer,a.id);
    if (method === 'stage-failed') return this.staging.failed(peer,a.id,a.error);
    if (method === 'stage-receipt') return this.staging.receipt(peer,a.id);
    if (method === 'stage-retract') return this.staging.retract(peer,a.session);
    if (method === 'fileChunk') { this.authorize(peer, 'clipboard'); return this.fileChunk(a,peer.remote.id); }
    if (method === 'paste') { this.requireOwner(peer); this.authorize(peer, 'clipboard'); if (this.pasting) throw new Error('正在准备粘贴'); this.pasting = true; try { await this.materialize({ ...this.validateOffer(a.offer), source: a.offer.source }); this.requireOwner(peer); this.authorize(peer, 'clipboard'); await this.native.call('paste'); return {}; } finally { this.pasting = false; } }
    if (method === 'shutdown') { this.authorize(peer, 'shutdown'); if (this.shutdownAt) throw new Error('已有待执行关机'); this.shutdownAt = Date.now() + 30000; this.shutdownTimer = setTimeout(() => { this.shutdownAt = null; this.native.call('shutdown').catch(e => this.log('关机未执行：' + e.message)); }, 30000); this.log('收到关机请求，30 秒内可取消'); return { at: this.shutdownAt }; }
    if (method === 'cancelShutdown') { this.cancelShutdown(); return {}; }
    throw new Error('不支持的操作');
  }
  cancelShutdown() { clearTimeout(this.shutdownTimer); this.shutdownAt = null; this.emit('change'); }
  async refreshCapabilities() {
    if (this.capabilityRefresh) return this.capabilityRefresh;
    this.capabilityRefresh = this.native.call('capabilities').then(caps => { this.native.capabilities = { ...this.native.capabilities, ...caps }; }).finally(() => { this.capabilityRefresh = null; });
    return this.capabilityRefresh;
  }
  validateOffer(o) {
    if (!o || typeof o.id !== 'string' || o.id.length > 64 || typeof o.text !== 'string' || o.text.length > 1000000 || !Array.isArray(o.files) || o.files.length > 100) throw new Error('剪贴板数据无效');
    let total = 0;
    for (const f of o.files) { safeName(f.name); if (typeof f.id !== 'string' || f.id.length > 64 || !Number.isSafeInteger(f.size) || f.size < 0 || f.size > MAX_FILE) throw new Error('文件无效或大于 2 GB'); total += f.size; }
    if (total > MAX_FILE) throw new Error('单次传输总量不能超过 2 GB');
    return { id: o.id, text: o.text, files: o.files.map(f => ({ id: f.id, name: f.name, size: f.size })) };
  }
  async snapshotClipboard(broadcast = true,manual = false) {
    this.allow('clipboard');const clip=await this.native.call('clipboardGet'),files=[],entries=new Map();
    if((clip.files||[]).length>100)throw Error('每次最多复制 100 个文件');
    for(const location of clip.files||[]){const s=await stat(location);if(!s.isFile())throw Error('目录请先压缩为 ZIP，再复制');const file={id:randomUUID(),name:safeName(path.basename(location)),size:s.size};files.push(file);entries.set(file.id,{location,size:s.size,mtime:s.mtimeMs});}
    const latest=this.clipboardSync.latest,previous=latest?.source===this.config.id?this.offers.get(latest.id):null;
    const reusable=previous&&previous.until>Date.now()&&latest.text===(clip.text||'')&&JSON.stringify([...previous.entries.values()])===JSON.stringify([...entries.values()]);
    const candidate=reusable?latest:{...this.validateOffer({id:randomUUID(),text:clip.text||'',files}),source:this.config.id};
    // Only allocate an offer that will actually be advertised. Suppressed file
    // notifications must never evict the source of the currently advertised item.
    if(broadcast&&!manual&&this.clipboardSync.suppress(clip)&&!reusable&&files.length&&this.clipboardSync.lastLocal===this.clipboardSync.fingerprint(clip))this.clipboardSync.lastLocal=null;
    this.clipboard=candidate;
    const published=!broadcast||await this.clipboardSync.publish(candidate,clip,manual);
    if(published){this.offers.set(candidate.id,reusable?previous:{entries,until:Date.now()+3600000});const ordinary=[...this.offers].filter(([key,o])=>!o.target&&key!==this.clipboardSync.latest?.id&&key!==candidate.id);for(const [key]of ordinary.slice(0,Math.max(0,ordinary.length-18)))this.offers.delete(key);}
    return this.clipboard;
  }
  async fileChunk(a,requester=null) {
    const data=await this.fileBytes(a,requester);return {data:data.toString('base64'),sha256:createHash('sha256').update(data).digest('hex')};
  }
  async fileBytes(a,requester=null) {
    const offer = this.offers.get(a.offerId), file = offer?.entries.get(a.fileId);
    if(offer?.target && offer.target!==requester)throw Error('此文件仅分享给指定设备');
    if (!file || offer.cancelled || offer.until < Date.now() || !Number.isSafeInteger(a.offset) || a.offset < 0 || a.offset > file.size || a.end!==undefined && (!Number.isSafeInteger(a.end)||a.end>file.size||a.end<a.offset) || a.maxBytes!==undefined && (!Number.isSafeInteger(a.maxBytes)||a.maxBytes<1||a.maxBytes>CHUNK)) throw new Error('文件分享已过期或范围无效，请重新复制');
    const s = await stat(file.location); if (s.size !== file.size || s.mtimeMs !== file.mtime) throw new Error('源文件已经变化，请重新复制');
    const h = await open(file.location, 'r'); try { const buffer = Buffer.alloc(Math.min(a.maxBytes ?? CHUNK, file.size - a.offset, a.end===undefined ? CHUNK : a.end-a.offset)); const { bytesRead } = await h.read(buffer, 0, buffer.length, a.offset);return buffer.subarray(0,bytesRead); } finally { await h.close(); }
  }
  materialize(offer,shouldApply=()=>true) {
    this.materializing ||= new Map();
    if(this.materializing.has(offer.id))return this.materializing.get(offer.id);
    const work=(this.clipboardWork||Promise.resolve()).catch(()=>{}).then(()=>this.materializeOffer(offer,shouldApply));
    this.clipboardWork=work;this.materializing.set(offer.id,work);
    work.finally(()=>this.materializing.delete(offer.id)).catch(()=>{});return work;
  }
  async materializeOffer(offer,shouldApply) {
    const requireSource = () => { this.allow('clipboard'); if (offer.source !== this.config.id && (!this.canShareClipboard(offer.source) || this.group.excluded(offer.source))) throw Error('剪贴板共享未授权'); };
    requireSource(); this.validateOffer(offer);
    if (!offer.files.length) {if(!shouldApply())return;this.clipboardSync.markApplied({text:offer.text});return this.native.call('clipboardSet', { text: offer.text });}
    if (this.receiving) throw new Error('正在接收其他文件，请稍后重试');
    const source = offer.source === this.config.id ? null : this.getPeer(offer.source);
    this.receiving = true;
    const folder = path.join(this.dir, 'received', randomUUID()); const paths = [];
    try {
      await mkdir(folder, { recursive: true });
      const receiveFile=async(f,index)=>{
        const fileDir = path.join(folder, String(index)); await mkdir(fileDir);
        const destination = path.join(fileDir, safeName(f.name)), temp = destination + '.part';
        const h = await open(temp, 'wx'); let offset = 0;
        try {if(source?.quicConn&&!source.fastLink){await receiveQuicFile(this,source,{offerId:offer.id,fileId:f.id,offset:0},f.size,data=>h.writeFile(data),offset=>this.transfer={name:f.name,received:offset,total:f.size});}
        else if(source?.info?.capabilities?.bulk===1){await receiveBulk(this,source.remote.id,{offerId:offer.id,fileId:f.id,offset:0},f.size,data=>h.writeFile(data),offset=>this.transfer={name:f.name,received:offset,total:f.size});}
        else while (offset < f.size) {
          requireSource();
          const args = { offerId: offer.id, fileId: f.id, offset };
          const chunk=source ? await source.request('fileChunk', args) : await this.fileChunk(args);
          requireSource();
          const data = Buffer.from(chunk.data, 'base64');
          if (!data.length || data.length > CHUNK || offset + data.length > f.size || chunk && createHash('sha256').update(data).digest('hex') !== chunk.sha256) throw new Error('文件传输校验失败');
          await h.writeFile(data); offset += data.length; this.transfer = { name: f.name, received: offset, total: f.size };
        }} finally {await h.close();}
        await rename(temp, destination);paths[index]=destination;
      };
      for(let base=0;base<offer.files.length;base+=2){const results=await Promise.allSettled(offer.files.slice(base,base+2).map((file,index)=>receiveFile(file,base+index)));const failed=results.find(result=>result.status==='rejected');if(failed)throw failed.reason;}
      requireSource();if(!shouldApply()){await rm(folder,{recursive:true,force:true});return;}this.clipboardSync.markApplied({files:paths}); await this.native.call('clipboardSet', { files: paths });
    } catch (e) { await rm(folder, { recursive: true, force: true }); throw e; } finally { this.transfer = null; this.receiving = false; }
  }
  async nativeEvent(e) {
    if (e.type === 'input' && e.synthetic) return;
    if(e.type==='input' && this.controlIntent?.transferring)return this.controlIntent.transferInputs.request('input',{...e,queuedAt:performance.now()});
    if (e.type === 'cursor' && e.synthetic) { this.cancelPendingEdge(); this.edge.suppress(); this.cancelEdgeFeedback(); }
    if(e.type==='cursor' && !e.synthetic && this.controlIntent?.edgeTrigger && !this.controlIntent.committed){
      const side=this.controlIntent.edge;
      const touching=side==='left'?e.x<=1:side==='right'?e.x>=e.width-2:side==='top'?e.y<=1:e.y>=e.height-2;
      if(!touching)this.cancelPendingEdge();
      return;
    }
    if (e.type === 'modifiers') { this.modifierMask = Number(e.mask) & 15; if(this.config.switchMode === 'hotkey' && !this.edgeHotkeyHeld()){this.cancelPendingEdge();this.edge.candidate=null;this.edge.progress=null;this.cancelEdgeFeedback();} return; }
    if (e.type === 'input-interrupted') {
      this.modifierMask=0;this.cancelEdgeFeedback();
      this.inputDesktopBlocked = e.blocked === true;
      const id = this.active || this.controlIntent?.id;
      for (const intent of this.outgoingIntents) intent.cancelled = true;
      if (id) this.controlEnded = { id, at: Date.now(), message: '系统接管输入，键鼠已返回本机' };
      await this.returnLocal();
      if (id) await this.closeStream(id).catch(() => {});
      this.edge.waitForLeave();this.log('系统接管输入，已结束本次控制并返回本机');this.emit('change');return;
    }
    if (e.type === 'input-restored') { this.inputDesktopBlocked = false; this.edge.waitForLeave(); this.emit('change'); return; }
    if (e.type === 'cursor' && this.owner && Date.now() - (this.cursorSentAt || 0) >= 32) {
      const peer = this.peers.get(this.owner.id);
      if (peer && !peer.closed && this.owner.until > Date.now() && this.config.permissions.input && this.consent.allowed(this.owner.id,'input') && e.width > 1 && e.height > 1) {
        this.cursorSentAt = Date.now();
        peer.send({ type: 'cursor', x: Math.max(0, Math.min(1, e.x / (e.width - 1))), y: Math.max(0, Math.min(1, e.y / (e.height - 1))) });
      }
      return;
    }
    if (e.type === 'error') { this.log(e.message || '原生操作失败'); return; }
    if (e.type === 'emergency') { this.modifierMask=0; if (this.owner) { this.clearInboundEdge();this.owner = null; await this.native.call('release'); } await this.returnLocal(); return; }
    if (e.type === 'clipboardChanged' && this.config.permissions.clipboard && this.clipboardSync.upload) { await this.snapshotClipboard(); return; }
    if (e.type === 'cursor' && e.synthetic) return;
    if (e.type === 'cursor' && !this.inputDesktopBlocked && !this.returning && !this.active && this.isEdgeMaster() && !this.owner && !this.switching) {
      const targets=Object.fromEntries(Object.entries(layoutEdges(this)).filter(([,id])=>this.peers.has(id) && !this.peers.get(id).closed));
      const next = this.updateEdge(e.x, e.y, e.width, e.height, targets);
      if (next) {try {await this.switchTo(next.target, next.edge,true,false,false,false,true,{position:{x:e.x/Math.max(1,e.width-1),y:e.y/Math.max(1,e.height-1)}});} finally {this.cancelEdgeFeedback();}} return;
    }
    if (e.type !== 'input' || !this.active) return;
    const p = this.getPeer(this.active);
    if (e.kind === 'move') {
      const width = p.info?.capabilities?.width || 1920, height = p.info?.capabilities?.height || 1080;
      this.position.x = Math.max(0, Math.min(1, this.position.x + e.dx / width)); this.position.y = Math.max(0, Math.min(1, this.position.y + e.dy / height));
      const intent=this.controlIntent;
      if(intent?.fromSession===this.activeSession && intent.edgeTrigger && !intent.committed && !this.positionTouches(intent.edge,width,height))this.cancelPendingEdge();
      // The per-peer input queue coalesces movement and adapts its rate. Enqueue
      // immediately so clicks remain behind their position and switching peers
      // cannot redirect an old pending move to a newly selected device.
      await p.request('input', { kind: 'move', ...this.position, dx: e.dx, dy: e.dy }); return;
    }
    if (e.kind === 'key') {
      if (e.down) this.keys.add(e.code); else this.keys.delete(e.code);
      if (e.code === 'KeyV' && (this.keys.has('ControlLeft') || this.keys.has('ControlRight') || this.keys.has('MetaLeft') || this.keys.has('MetaRight'))) { if (e.down) await this.pasteTo(this.active); return; }
    }
    await p.request('input', validateInput(e));
  }
  edgeHotkeyHeld(){const mask={'ctrl-alt':3,'ctrl-shift':5,'alt-shift':6,'meta-shift':12}[this.config.edgeHotkey||'ctrl-alt'];return ((this.modifierMask||0)&mask)===mask;}
  isEdgeMaster(){const choice=masterSelection(this);return choice?choice.id===this.config.id:!!this.config.master;}
  positionTouches(edge,width,height){return edge==='left'?this.position.x*width<=1:edge==='right'?this.position.x*width>=width-2:edge==='top'?this.position.y*height<=1:this.position.y*height>=height-2;}
  cancelEdgeFeedback(){if(this.edgeFeedback){this.edgeFeedback=null;this.emit('edge-feedback',{phase:'cancel'});}}
  cancelPendingEdge(){const intent=this.controlIntent;if(!intent?.edgeTrigger || intent.committed || intent.cancelled)return;intent.cancelled=true;this.edge.reset();this.cancelEdgeFeedback();this.adbGuide.cancel();this.access.cancel();this.emit('change');}
  clearInboundEdge(){if(this.inboundGlow){this.inboundGlow=false;this.emit('edge-feedback',{phase:'clear'});}}
  inboundEdge(peer,m,exiting=false){
    const owner=this.owner;
    if(!owner || owner.id!==peer.remote.id || owner.until<Date.now() || !owner.pointer || !this.config.permissions.input || !this.consent.allowed(owner.id,'input') || !owner.edgeSession || m.session!==owner.edgeSession || !Number.isSafeInteger(m.seq) || m.seq<=owner.glowSeq)return false;
    if(!['left','right','top','bottom'].includes(m.edge) || !(exiting?m.phase==='depart':['countdown','cancel'].includes(m.phase)) || m.phase==='countdown'&&(!Number.isFinite(m.progress)||m.progress<0||m.progress>1))return false;
    owner.glowSeq=m.seq;
    if(this.config.edgeGlow!==false){this.inboundGlow=m.phase!=='cancel';this.emit('edge-feedback',{phase:m.phase,edge:m.edge,progress:m.progress,hideAway:true});}
    return true;
  }
  remoteEdge(phase,edge,progress){
    const session=this.activeSession,p=this.peers.get(this.active);if(!session?.committed || !this.capture || !p || p.closed)return null;
    if(phase==='cancel' && !session.remoteGlow)return null;
    if(phase==='countdown' && session.remoteGlow && Date.now()-(session.glowAt||0)<50 && progress<1)return null;
    const message={type:'edge-glow',phase,edge,progress,session:session.edgeSession,seq:session.glowSeq=(session.glowSeq||0)+1};
    session.remoteGlow=phase==='countdown';session.glowAt=Date.now();
    if(phase!=='depart')try{p.send(message);}catch{}
    return message;
  }
  updateEdge(x,y,width,height,targets){
    const hotkey=this.config.switchMode==='hotkey';
    const next=this.edge.update(x,y,width,height,targets,hotkey?0:(this.config.dwell??100),!hotkey||this.edgeHotkeyHeld());
    if(!this.active && next && !hotkey && this.config.edgeGlow!==false){this.edgeFeedback={phase:'countdown',edge:next.edge,progress:1};this.emit('edge-feedback',this.edgeFeedback);}
    else if(!this.active && this.edge.progress && !hotkey && this.config.edgeGlow!==false){this.edgeFeedback={phase:'countdown',...this.edge.progress};this.emit('edge-feedback',this.edgeFeedback);}else this.cancelEdgeFeedback();
    return next;
  }
  checkReturnEdge() {
    if (!this.active || !this.capture || this.switching || this.returning) return;
    if(masterSelection(this) && !this.isEdgeMaster())return this.returnLocal().catch(e=>this.log(e.message));
    const p = this.peers.get(this.active); if (!p) return;
    const width = p.info?.capabilities?.width || 1920, height = p.info?.capabilities?.height || 1080;
    const opposite = { left: 'right', right: 'left', top: 'bottom', bottom: 'top' }[this.enteredEdge];
    const shared=layoutSelection(this).revision>0;
    const adjacent=shared?layoutEdges(this,this.active):{[opposite]:this.config.id};
    const targets=Object.fromEntries(Object.entries(adjacent).filter(([,id])=>id===this.config.id||this.peers.has(id)&&!this.peers.get(id).closed));
    const next=this.updateEdge(this.position.x * width, this.position.y * height, width, height,targets);
    if(next){
      this.remoteEdge('countdown',next.edge,1);
      const transition={fromSession:this.activeSession,fromId:this.active,position:{...this.position},edge:next.edge};
      const work=next.target===this.config.id?this.returnLocal(null,true,shared?transition:null):this.switchTo(next.target,next.edge,true,false,false,false,true,transition);
      return work.catch(e=>this.log(e.message));
    }
    else if(this.edge.progress)this.remoteEdge('countdown',this.edge.progress.edge,this.edge.progress.progress);
    else this.remoteEdge('cancel',opposite);
  }
  entryPosition(edge,position={x:.5,y:.5}) {
    const clamp=value=>Math.max(.015,Math.min(.985,Number.isFinite(value)?value:.5));
    return {x:edge==='right'?.015:edge==='left'?.985:clamp(position.x),y:edge==='bottom'?.015:edge==='top'?.985:clamp(position.y)};
  }
  async switchTo(id, edge = 'right', capture = true, newSession = false, fallbackAccepted = false, screen = false, edgeTrigger = false, transition = null) {
    if (this.switching || this.returning) throw Error('CONTROL_CONFLICT: 正在切换控制，请稍后重试'); this.assertCanSendControl();
    if(capture && masterSelection(this) && !this.isEdgeMaster())throw Error('CONTROL_CONFLICT: 只有本组选择的主控鼠标可以跨屏控制');
    if (capture && !this.native.capabilities.host) throw new Error('本机尚未具备全局键鼠权限');
    const p = this.getPeer(id);
    if (this.needsFallback(p) && !fallbackAccepted) { this.showFallback(id, edge, capture); throw Error('FALLBACK_CONFIRMATION: 该手机仅可使用无障碍备用模式，请先确认操作限制'); }
    const handoff=transition?.fromSession;
    if(handoff && (!capture || this.activeSession!==handoff || this.active!==transition.fromId))throw Error('CONTROL_ENDED: 跨屏会话已改变');
    if(!capture || this.edgeFeedback?.edge!==edge)this.cancelEdgeFeedback();const intent = { id, edge, edgeTrigger, fromSession:handoff, edgeSession:randomUUID(), cancelled: false }; this.controlIntent = intent; this.switching = true;
    const check = () => { if (intent.cancelled || this.controlIntent !== intent || capture&&masterSelection(this)&&!this.isEdgeMaster()) throw Error('CONTROL_ENDED: 控制请求已取消'); this.assertCanSendControl(); if (this.getPeer(id) !== p) throw Error('设备连接已结束'); };
    try {
      if (this.active && !handoff) await this.returnLocal(intent); check();
      await this.adbGuide.check(p, { id, edge, capture, newSession, fallbackAccepted, screen }); check();
      await this.access.check(p, { id, edge, capture, newSession, fallbackAccepted, screen }); check();
      if (p.info?.capabilities?.controlPrepare) { await p.request('prepare', { newSession, screen, pointer: capture }); check(); }
      const capabilities = await p.request('acquire', { newSession, fallbackAccepted, screen, pointer: capture, edge, edgeSession:intent.edgeSession }); intent.acquired = true; check();
      if ((this.needsFallback(p) || this.needsFallback(p, capabilities)) && !fallbackAccepted) { this.showFallback(id, edge, capture); throw Error('FALLBACK_CONFIRMATION: 该手机仅可使用无障碍备用模式，请先确认操作限制'); }
      if(handoff){
        if(this.activeSession!==handoff)throw Error('CONTROL_ENDED: 跨屏会话已改变');
        intent.transferring=true;
        const ready=new Promise(resolve=>intent.resumeInput=resolve);
        intent.transferInputs=new InputQueue(async(_method,args)=>{
          await ready;
          if(this.activeSession!==intent || !intent.committed)return;
          const {queuedAt,...event}=args;
          if(event.kind==='move' && performance.now()-queuedAt>120)return;
          return this.nativeEvent(event);
        });
        const exit=this.remoteEdge('depart',edge,1);
        await this.getPeer(transition.fromId).request('release',exit?{edgeExit:exit}:{});check();
        this.staging.end(handoff.edgeSession);this.screenTargets.delete(transition.fromId);
        intent.carriedKeys=[...this.keys].filter(code=>/^(Control|Shift|Alt|Meta)(Left|Right)$/.test(code));this.keys.clear();
      }
      this.active = id; this.activeSession = intent; this.capture = capture; this.enteredEdge = edge; this.fallbackAccepted = fallbackAccepted; this.fallbackNotice = null;
      this.position = this.entryPosition(edge,transition?.position);
      if (capture) await p.request('input', { kind: 'move', ...this.position }); check(); if (capture) await this.native.call('mode', { remote: true }); check();
      for(const code of intent.carriedKeys||[]){await p.request('input',{kind:'key',code,down:true});this.keys.add(code);check();}
      intent.committed=true;this.edge.reset(); this.log('控制已切换到 ' + (p.info?.name || id));
      if (capture && !handoff) {this.edgeFeedback=null;this.edgeTransition = { id: randomUUID(), edge, at: Date.now() };if(this.config.edgeGlow!==false)this.emit('edge-feedback',{phase:'depart',edge,hideAway:!!this.config.edgeGlowHideAway});}
    } catch (e) {
      this.cancelEdgeFeedback();
      if (!intent.cancelled && /^FALLBACK_CONFIRMATION:/.test(e.message)) this.showFallback(id, edge, capture);
      if (this.active === id) await this.returnLocal(intent);
      else if (intent.acquired) await p.request('release').catch(() => {});
      if(handoff && this.activeSession===handoff)await this.returnLocal(intent);
      // Readiness can change between the initial preflight and prepare/acquire.
      // Re-enter setup only for an Android permission failure, after releasing
      // local capture; never convert denial of this peer's control grant to ADB.
      if(!intent.cancelled && p.info?.platform==='android' && !this.adbGuide.notice && /^(PERMISSION_REQUIRED|INPUT_RECOVERING|CONSENT_DENIED):/.test(e.message)){
        try {await this.adbGuide.check(p,{id,edge,capture,newSession,fallbackAccepted,screen});}
        catch(setupError){if(setupError.message.startsWith('ADB_SETUP_REQUIRED:'))throw setupError;}
      }
      throw e;
    } finally { if (this.controlIntent === intent) { this.controlIntent = null; this.switching = false; } intent.transferring=false;intent.resumeInput?.();this.emit('change'); }
  }
  async returnLocal(preserveIntent = null, edgeExit = false, transition = null) {
    this.staging?.end(this.activeSession?.edgeSession);
    const outgoingEdge=transition?.edge||{left:'right',right:'left',top:'bottom',bottom:'top'}[this.enteredEdge];
    const exitFeedback=edgeExit?this.remoteEdge('depart',outgoingEdge):null;
    if(!edgeExit)this.remoteEdge('cancel',outgoingEdge);
    this.cancelEdgeFeedback();
    if(this.active && this.capture && (!this.activeSession?.edgeTrigger || this.activeSession.committed) && this.config.edgeGlow!==false)this.emit('edge-feedback',{phase:'return',edge:this.enteredEdge,hideAway:!!this.config.edgeGlowHideAway});
    // Detach the active session before dismissing permission guides: their
    // cancel callbacks can otherwise recursively start a second return.
    const previous = this.active; this.active = null; this.activeSession = null; this.capture = false; this.fallbackAccepted = false; this.keys.clear(); this.pendingMove = null; this.edge.reset(); this.screenTargets.delete(previous); this.returning++;
    if (!preserveIntent) this.adbGuide.cancel();
    if (!preserveIntent) this.access.cancel();
    if (this.controlIntent && this.controlIntent !== preserveIntent) this.controlIntent.cancelled = true;
    try {
      await this.native.call('mode', { remote: false }).catch(() => {});
      if(transition)await this.native.call('warp',this.entryPosition(transition.edge,transition.position)).catch(()=>{});
      const p = this.peers.get(previous); if (p) await p.request('release',exitFeedback?{edgeExit:exitFeedback}:{}).catch(() => {});
      if (previous) this.edge.waitForLeave();
      if (previous && this.config.permissions.clipboard && this.clipboardSync.download && this.clipboard && this.clipboard.source !== this.config.id) await this.materialize(this.clipboard).catch(e => this.log('返回本机后同步剪贴板失败：' + e.message));
    } finally { this.returning--; this.emit('change'); }
  }
  async pasteTo(id) {
    if (!this.clipboard) await this.snapshotClipboard();
    return this.getPeer(id).request('paste', { offer: this.clipboard }, 120000);
  }
  async tick() {
    this.staging.tick().catch(()=>{});
    this.access.poll().catch(()=>{});
    this.clipboardSync.prune();if(this.config.permissions.clipboard)for(const p of this.peers.values())this.clipboardSync.sendInventory(p);
    this.consent.list(); if (this.permissionNotice?.expires <= Date.now()) this.permissionNotice = null;
    if (this.permissionNotice) { try { this.native.capabilities = await this.native.call('capabilities'); } catch {} }
    for (const p of this.peers.values()) { if(!this.currentPeer(p))continue;if (Date.now() - p.lastSeen > 10000) p.close(); else p.send({ type: 'ping',masterSelection:masterSelection(this),...layoutExchange(this,p) }); }
    if (this.active) {
      const session = this.activeSession, id = this.active, edge = this.enteredEdge, capture = this.capture;
      try { await this.getPeer(id).request('keepControl', {}, 2500); if (this.activeSession === session && this.capture) await this.native.call('heartbeat'); }
      catch (e) { if (e.code !== 'QUIC_BUSY' && this.activeSession === session) { await this.returnLocal(); if (!this.active && !this.switching && /^FALLBACK_CONFIRMATION:/.test(e.message)) this.showFallback(id, edge, capture); } }
    }
    if (this.owner && this.owner.until < Date.now()) { this.clearInboundEdge();this.owner = null; await this.native.call('release'); }
    for (const [id, offer] of this.offers) if (offer.until < Date.now()) this.offers.delete(id);
  }
  configure(run){const work=(this.configurationWork||Promise.resolve()).catch(()=>{}).then(run);this.configurationWork=work;return work;}
  settings(a) {return this.configure(()=>applySettings(this,a));}
  state() { return { update:this.updater?.state(),autoCheckUpdates:this.config.autoCheckUpdates!==false, buildInfo, language:languagePreference(this.config.language), theme:['system','dark','light'].includes(this.config.theme)?this.config.theme:'system', clipboardSettings:this.clipboardSync.state(), masterId:masterSelection(this)?.id||(this.config.master?this.config.id:""), masterDevices:masterChoices(this), edgeGlowHideAway:!!this.config.edgeGlowHideAway, switchMode:this.config.switchMode||"dwell", edgeHotkey:this.config.edgeHotkey||"ctrl-alt", edgeGlowEnabled: this.config.edgeGlow !== false, edgeTransition: this.edgeTransition, id: this.config.id, name: this.config.name, groupId: this.group.data.id, groupEstablished: this.group.established, groupMemberCount: Object.keys(this.group.data.members).length, account: this.group.data.id, controlEnded: this.controlEnded, androidDisconnectNotice: this.androidRecovery.notice, fallbackNotice: this.fallbackNotice, stageIncoming:this.staging.incoming(), adbGuide: this.adbGuide.notice, adbAuthorization: this.adb.authorizationNotice(), accessNotice: this.access.notice, viewers: [...new Set([...this.videoHost.sessions.values()].map(s => s.peer.remote.id).concat(this.liveScreens(this.screenViewers)))], port: this.port, dwell: this.config.dwell ?? 100, edges: layoutEdges(this), layout:layoutSelection(this),layoutDevices:layoutDevices(this),consentRequests: this.consent.list(), deviceGrants: this.config.deviceGrants, permissionNotice: this.permissionNotice, permissions: this.config.permissions, master: !!this.config.master, capabilities: this.native.capabilities, knownDevices: this.config.knownDevices || {}, peers: [...this.peers].map(([id, p]) => ({ id, ...p.info, address: p.socket.remoteAddress, transport: this.links.status(p), access: p.access || {status:"未就绪",permissions:{}} })), active: this.active, pendingControl: this.controlIntent?.id, owner: this.owner?.id, shutdownAt: this.shutdownAt, transfer: this.transfer, clipboard: this.clipboard ? { source: this.clipboard.source, files: this.clipboard.files.length, textLength: this.clipboard.text.length } : null, logs: this.logs }; }
  async stopInbound() {
    const ids = new Set([...this.videoHost.sessions.values()].map(s => s.peer.remote.id).concat(this.liveScreens(this.screenViewers))); this.screenViewers.clear(); if (this.owner) ids.add(this.owner.id);
    for (const id of ids) { this.config.pausedPeers[id] = true; try { this.peers.get(id)?.send({ type: 'control-ended',...(this.owner?.id===id&&this.owner.edgeSession?{session:this.owner.edgeSession}:{}) }); } catch {} }
    this.videoHost.closeAll(); this.clearInboundEdge();this.owner = null; await this.native.call('release'); this.cancelShutdown(); await saveConfig(this.dir, this.config); this.emit('change');
  }
  async groupChanged() {
    this.changingGroup = true;await initializeMaster(this);await initializeLayout(this);await this.quic.reset();this.bulk.closeAll();
    try {
    if (this.controlIntent) this.controlIntent.cancelled = true; for (const intent of this.outgoingIntents) intent.cancelled = true; this.screenTargets.clear(); this.screenViewers.clear(); this.fallbackNotice = null;
    this.secret = parseToken(this.config.token);this.staging.clearExports(); this.stopDiscovery?.(); this.videoHost.closeAll(); await this.videoViewer.closeAll(); await this.returnLocal(); this.clearInboundEdge();this.owner = null; await this.native.call('release'); this.offers.clear(); this.clipboard = null;this.clipboardSync.clear(); this.permissionNotice = null; this.cancelShutdown(); this.consent.reset(this.config.deviceGrants);
    const old = [...this.sockets]; for (const peer of old) peer.intentionalClose = true; setTimeout(() => old.forEach(p => p.close()), 100);
    if (!this.options.noDiscovery) this.stopDiscovery = startDiscovery(this.identity(), this.secret, this.port, (b, host) => {
      if (!this.group.excluded(b.id) && this.config.id < b.id) {
        if (!this.peers.has(b.id)) this.connect(host, b.port);
        else if (this.peers.get(b.id).socket.remoteAddress !== host) this.links.candidate(b.id, host).catch(() => {});
      }
    }, e => this.log(e.message));
    this.emit('change');
    } finally { this.changingGroup = false; }
  }
  async leaveGroup() {
    this.pairing?.hide(); const { newToken } = await import('./identity.js'); this.config.token = newToken(); delete this.config.group; this.group.close(); this.group = new Group(this);this.clipboardSync=new ClipboardSync(this); this.config.knownDevices = {}; this.config.deviceGrants = {}; this.config.edges = {}; this.config.pausedPeers = {};delete this.config.layout;delete this.config.masterSelection;this.config.master=false;await initializeMaster(this);await initializeLayout(this);await saveConfig(this.dir, this.config); await this.groupChanged(); this.group.start(); this.log('本设备已移出组，需要重新配对');
  }
  async close() { this.updater?.close(); this.closing = true;for(const item of this.connecting.values())item.socket.destroy();this.quic.close();this.bulk.closeAll(); clearInterval(this.adbTimer); clearInterval(this.linkTimer); await this.links.close(); this.group.close(); clearInterval(this.heartbeat); clearInterval(this.edgeTimer); this.cancelShutdown(); await Promise.all([this.videoHost.closeAll(), this.videoViewer.closeAll()]); await this.returnLocal(); this.stopDiscovery?.(); await this.pairing?.close(); for (const p of this.sockets) p.close(); if (this.server) await new Promise(r => this.server.close(r)); await Promise.allSettled([...this.messageTasks]); this.native.close(); await this.staging.close(); await saveConfig(this.dir, this.config); }
}
