export const runtimeLogLimit = 100;

// Keep each entry on one line even if a peer sends control characters.
const line = (value, length) => String(value ?? '').slice(0, length).replace(/[\p{Cc}\p{Cf}\u2028\u2029]/gu, ' ');

export async function exportLogs(service) {
  const { config, group, peers } = service;
  const groupId = group.data.id;
  const token = config.token;
  const started = new Date();
  const local = { id: config.id, name: config.name, logs: service.logs.slice(0, runtimeLogLimit) };
  const devices = { ...config.knownDevices, ...group.data.members };
  for (const [id, peer] of peers) devices[id] = peer.info || devices[id];
  const remote = await Promise.all(Object.entries(devices).filter(([id]) =>
    id !== local.id && !group.excluded(id)
  ).map(async ([id, info]) => {
    const row = { id, name: info?.name };
    const peer = peers.get(id);
    if (!peer || !service.currentPeer(peer)) return { ...row, missing: 'Offline' };
    try {
      // Send snapshots on the primary encrypted TCP connection.
      const result = await (peer.tcpRequest || peer.lanRequest)('runtime-logs', {}, 5000);
      if (service.currentPeer(peer) && !group.excluded(id) && Array.isArray(result?.logs))
        return { ...row, logs: result.logs.slice(0, runtimeLogLimit) };
    } catch {}
    return { ...row, missing: 'Logs unavailable (request failed or device needs an update)' };
  }));
  if (service.group !== group || group.data.id !== groupId || config.token !== token)
    throw Error('组已发生变化，请重新导出日志');

  const rows = [local, ...remote];
  const content = ['LanLink group logs', 'Exported: ' + started.toISOString()];
  for (const row of rows) {
    content.push('', '=== ' + (line(row.name, 80) || 'Device') + ' (' + line(row.id, 80) + ') ===');
    if (row.missing) { content.push(row.missing); continue; }
    const entries = row.logs.filter(entry => Number.isSafeInteger(entry?.time) &&
      entry.time >= 0 && entry.time <= 8640000000000000 && typeof entry.message === 'string');
    if (!entries.length) content.push('No activity recorded');
    for (const { time, message } of entries) content.push(new Date(time).toISOString() + '  ' + line(message, 300));
  }
  return {
    name: 'LanLink-logs-' + started.toISOString().slice(0, 19).replace(/:/g, '-') + 'Z.txt',
    text: content.join('\n') + '\n',
    incomplete: remote.some(row => row.missing)
  };
}
