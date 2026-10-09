import { parseToken, saveConfig } from './identity.js';
import { startDiscovery } from './discovery.js';
import qrcode from '../vendor/qrcode.mjs';
import { Group } from './group.js';
import { AdbManager } from './adb.js';
import { groupPermissions } from './permissions.js';

// Shared application operations. Desktop calls them directly through restricted IPC;
// the optional developer HTTP harness delegates to the same implementation.
export function createActions(service) {
  const adb = service.adb || new AdbManager(service);
  const actions = {
    state: () => service.state(),
    'stage-list':()=>({files:service.staging.list(),outgoing:service.staging.outgoingList()}),
    'stage-outgoing-list':()=>({files:service.staging.outgoingList()}),
    'stage-outgoing-retry':a=>service.staging.retryOutgoing(a.id),
    'stage-outgoing-cancel':a=>service.staging.cancelOutgoing(a.id),
    'stage-outgoing-remove':a=>service.staging.removeOutgoing(a.id),
    'stage-cancel':a=>service.staging.cancel(a.id),
    'stage-save':a=>service.staging.saveTo(a.id,a.destination),
    'stage-share':a=>service.staging.share(a.id,a.files,a.start!==false,a.session),
    'stage-drag-end':a=>{service.staging.end(a.session);return {};},
    'stage-edge':async a=>{if(!service.config.master||service.config.edges?.[a.edge]!==a.id)throw Error('设备已不在此边缘');if(service.config.switchMode==='hotkey'&&!service.edgeHotkeyHeld())throw Error('请按住设置的切换快捷键');const deadline=Date.now()+8000;while(service.switching&&Date.now()<deadline)await new Promise(r=>setTimeout(r,30));if(service.active!==a.id)await service.switchTo(a.id,a.edge,true,false,false,false,true);await service.native.call('file-drag-cancel');return service.staging.share(a.id,a.files,false);},
    'stage-start':a=>{if(!service.staging.rows.has(a.id))throw Error('暂存文件不存在');service.staging.start(a.id).catch(e=>service.log(e.message));return {message:'正在接收文件…'};},
    'stage-ready':a=>service.staging.ready(a.id),
    'stage-remove':a=>service.staging.remove(a.id),
    'clipboard-upload':()=>service.clipboardSync.uploadNow(),
    'clipboard-download':()=>service.clipboardSync.downloadNow(),
    'clipboard-copy':a=>service.clipboardSync.copy(a.id),
    'group-permissions': () => groupPermissions(service),
    'adb-forget': a => adb.forget(a.id),
    settings: a => service.settings(a),
    'device-remove': a => service.group.remove(a.id),
    'stop-inbound': () => service.stopInbound(),
    'adb-devices': () => adb.devices(),
    'adb-group': () => adb.groupDevices(),
    'adb-authorize': a => adb.authorize(a),
    'adb-match': a => adb.match(a.id),
    'adb-guide-open': a => service.adbGuide.open(a.id),
    'adb-local-devices': () => adb.localDevices(),
    'adb-local-authorize': a => adb.authorizeLocal(a.serial),
    'adb-connect': async a => { const match=await adb.match(a.id);if(!match.connected)throw Error(match.message);return adb.authorize({id:a.id,serial:match.serial}); },
    'feature-prepare': a => service.access.prepare(a.id,a.permissions),
    'access-request': a => service.access.request(a.id,a.permissions),
    'access-status': a => service.access.status(a.requestId),
    'access-complete': a => service.access.complete(a.requestId),
    'access-cancel': a => (a.requestId || service.access.notice?.id===a.id || service.controlIntent?.id===a.id) ? service.access.cancel(a.requestId) : {},
    'adb-guide-status': a => service.adbGuide.status(a.requestId),
    'adb-guide-complete': a => service.adbGuide.complete(a.requestId),
    'adb-guide-cancel': a => (a.requestId || service.adbGuide.notice?.id === a.id || service.controlIntent?.id === a.id) ? service.adbGuide.cancel(a.requestId) : {},
    'fallback-dismiss': () => { service.fallbackNotice = null; service.edge.reset(); },
    'consent-decide': a => service.decideConsent(a),
    'consent-revoke': a => service.revokeConsent(a.id),
    'consent-set': a => service.setConsent(a.id,a.permissions),
    'permission-dismiss': () => { service.permissionNotice = null; },
    pairing: a => {
      const info = { ...service.pairing.show(), groupId: service.group.data.id, groupEstablished: service.group.established };
      if (a.reveal) {
        if (!service.pairing.invite.qr) {
          const qr = qrcode(0, 'M');
          qr.addData('lanlink://pair?v=2&code=' + info.code + '&id=' + info.id); qr.make(); service.pairing.invite.qr = qr.createDataURL(5, 20);
        }
        info.qr = service.pairing.invite.qr;
      }
      return info;
    },
    'pairing-stop': () => service.pairing.hide(),
    join: async a => {
      const previousGroup = service.group.data.id;
      if (a.expectedGroup && a.expectedGroup !== previousGroup) throw Error('组已发生变化，请重新打开添加设备');
      if (service.group.established && a.dissolveCurrentGroup !== true) throw Error('请先确认解散当前组，所有原组成员都将退出');
      const result = a.code !== undefined ? await service.pairing.join(a.code, a.id) : { token: a.token };
      const secret = parseToken(result.token);
      if (service.group.data.id !== previousGroup) throw Error('组已发生变化，请重新配对');
      if (result.group?.id === previousGroup) return { alreadyInGroup: true };
      if (result.group) { const check = new Group({ ...service, config: structuredClone(service.config) }); check.adopt(result.group, result.token); }
      if (service.group.established) {
        if (a.dissolveCurrentGroup !== true) throw Error('组已有新成员，请重新确认解散');
        service.config.pendingGroupJoin = { previousGroup, ...result }; await saveConfig(service.dir, service.config);
        await service.group.dissolve();
      }
      await service.returnLocal(); service.stopDiscovery?.(); service.pairing.hide();
      for (const p of service.sockets) p.close();
      service.config.knownDevices = {}; service.config.edges = {}; service.config.deviceGrants = {}; service.consent.reset(service.config.deviceGrants); service.permissionNotice = null; service.secret = secret; service.config.token = result.token.trim(); service.config.pausedPeers = {}; if (result.group) service.group.adopt(result.group, result.token); else { service.config.group = { id: (await import('./identity.js')).accountId(result.token), revision: 0, members: {}, removed: [], history: [], anchors: {} }; service.group.data = service.config.group; service.group.data.members[service.config.id] = service.group.own; service.group.refreshBoxes(); } service.offers.clear(); service.clipboard = null;service.clipboardSync.clear();
      delete service.config.pendingGroupJoin; await saveConfig(service.dir, service.config);
      if (!service.options.noDiscovery) service.stopDiscovery = startDiscovery(service.identity(), secret, service.port,
        (b, host) => { if (service.config.id < b.id && !service.peers.has(b.id)) service.connect(host, b.port); }, e => service.log(e.message));
      if (result.host) service.connect(result.host, result.port);
    },
    connect: a => service.connect(a.host, Number(a.port || 45831)),
    control: a => service.switchTo(a.id, a.edge || 'right', !!a.capture, a.newSession === true, a.fallbackAccepted === true, a.screen === true),
    release: a => !a?.id || service.active === a.id || service.controlIntent?.id === a.id ? service.returnLocal() : {},
    stop: () => { setTimeout(() => service.emit('stop'), 50); },
    input: a => { if (service.active !== a.id) throw new Error('请先开始控制'); return service.getPeer(a.id).request('input', a.input); },
    text: a => { if (service.active !== a.id) throw new Error('请先开始控制'); return service.getPeer(a.id).request('text', { text: a.text }); },
    screen: a => service.screenFrom(a.id),
    'stream-open': a => service.openStream(a.id, a.jpegOnly === true),
    'stream-next': a => service.videoViewer.next(a.id),
    'stream-close': a => service.closeStream(a.id),
    paste: a => service.pasteTo(a.id),
    clipboard: async a => {
      await service.native.call('clipboardSet', { text: String(a.text || '').slice(0, 1000000) });
      return service.snapshotClipboard(true,true);
    },
    receive: () => { if (!service.clipboard) throw new Error('没有共享内容'); return service.materialize(service.clipboard); },
    shutdown: a => service.getPeer(a.id).request('shutdown'),
    'cancel-shutdown': a => a.id ? service.getPeer(a.id).request('cancelShutdown') : service.cancelShutdown(),
  };
  return async (method, args = {}) => {
    if (!Object.hasOwn(actions, method)) throw new Error('接口不存在');
    if (!args || typeof args !== 'object' || Array.isArray(args) || JSON.stringify(args).length > 2 * 1024 * 1024) throw new Error('请求数据无效或过大');
    return await actions[method](args) ?? {};
  };
}
