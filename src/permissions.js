const labels = { input: '键鼠标操作', screen: '屏幕共享', clipboard: '剪贴板文件共享', shutdown: '远程关机' };

export function permissionReport(service, requester = service.config.id) {
  const caps = service.native.capabilities, local = requester === service.config.id;
  return { version: 1, requestPending: service.consent.pending?.has(requester) === true, requestRejected: service.consent.denied?.has(requester) === true, permissions: Object.fromEntries(Object.keys(labels).map(key => [key, {
    supported: true, appEnabled: service.config.permissions[key] === true,
    systemGranted: caps.simulated ? null : typeof caps[key] === 'boolean' ? caps[key] : null,
    peerAllowed: local ? true : key === 'clipboard' ? service.canShareClipboard(requester) : service.consent.allowed(requester, key),
  }])) };
}

export function permissionRows(report) {
  return Object.entries(labels).map(([key, label]) => {
    const p = report?.version === 1 ? report.permissions?.[key] : null;
    const supported = p?.supported !== false;
    const app = p ? (p.appEnabled === true ? '已开启' : p.appEnabled === false ? '未开启' : '未知') : '未知';
    const system = p ? (p.systemGranted === true ? '已就绪' : p.systemGranted === false ? '未就绪' : '未知') : '未知';
    const consent = p ? (p.peerAllowed === true ? '已允许' : p.peerAllowed === false ? '未允许' : '待首次确认') : '未知';
    return { key, label, supported, app, system, consent,
      status: !supported ? '不支持' : !p ? '状态未知' : p.appEnabled === false ? '应用未开启' : p.systemGranted === false ? '系统未就绪' : p.peerAllowed === false ? '未允许此电脑' : p.peerAllowed == null ? '待首次确认' : p.appEnabled && p.systemGranted ? '已就绪' : '状态未知' };
  });
}

export async function groupPermissions(service) {
  const records = new Map([[service.config.id, { name: service.config.name, platform: process.platform }]]);
  for (const [id, value] of Object.entries(service.group.data.members)) if (!service.group.excluded(id)) records.set(id, value);
  for (const [id, value] of Object.entries(service.config.knownDevices || {})) if (!service.group.excluded(id)) records.set(id, value);
  return { devices: await Promise.all([...records].map(async ([id, info]) => {
    const local = id === service.config.id, peer = service.peers.get(id), online = local || !!peer && !peer.closed;
    let report, message = online ? '' : '设备离线，无法读取当前权限';
    try {
      if (local) { await service.refreshCapabilities(); report = permissionReport(service); }
      else if (online) report = await peer.request('permission-status', {}, 3000);
      if (online && report?.version !== 1) message = '请升级此设备以查看权限';
    } catch { message = '暂时无法读取，请确认设备在线并已升级'; }
    return { id, name: local ? service.config.name : peer?.info?.name || info.name || '设备', platform: local ? process.platform : peer?.info?.platform || info.platform,
      local, online, message, permissions: permissionRows(report), engine: report?.engine || null };
  })) };
}
