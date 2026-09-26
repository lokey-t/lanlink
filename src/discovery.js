import dgram from 'node:dgram';
import os from 'node:os';
import { mac, equal } from './wire.js';
export const DISCOVERY_PORT = 45832;
export function discoveryRecord(identity, secret, port, time = Date.now()) {
  const body = JSON.stringify({ v: 1, id: identity.id, port, time }); return { body, proof: mac(secret, 'discovery|' + body) };
}
export function verifyRecord(record, secret, now = Date.now()) {
  if (typeof record.body !== 'string' || record.body.length > 1024 || !equal(record.proof, mac(secret, 'discovery|' + record.body))) return null;
  const b = JSON.parse(record.body);
  return b.v === 1 && /^[a-f0-9-]{36}$/.test(b.id) && Number.isInteger(b.port) && b.port > 0 && b.port <= 65535 && Number.isFinite(b.time) && Math.abs(now - b.time) < 30000 ? b : null;
}
export function startDiscovery(identity, secret, port, onFound, onError) {
  const socket = dgram.createSocket({ type: 'udp4', reuseAddr: true }); let timer;
  socket.on('error', onError);
  socket.on('message', (data, rinfo) => { try { if (data.length > 2048) return; const b = verifyRecord(JSON.parse(data.toString()), secret); if (b && b.id !== identity.id) onFound(b, rinfo.address); } catch {} });
  socket.bind(DISCOVERY_PORT, () => {
    socket.setBroadcast(true);
    const announce = () => {
      const message = Buffer.from(JSON.stringify(discoveryRecord(identity, secret, port)));
      const destinations = new Set(['255.255.255.255']);
      for (const list of Object.values(os.networkInterfaces())) for (const n of list ?? []) {
        if (n.family !== 'IPv4' || n.internal) continue;
        const ip = n.address.split('.').map(Number), mask = n.netmask.split('.').map(Number);
        destinations.add(ip.map((v, i) => v | (255 ^ mask[i])).join('.'));
      }
      for (const dest of destinations) socket.send(message, DISCOVERY_PORT, dest, () => {});
    }; announce(); timer = setInterval(announce, 3000);
  });
  return () => { clearInterval(timer); socket.close(); };
}
