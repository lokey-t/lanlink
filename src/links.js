import net from 'node:net';
import { randomBytes } from 'node:crypto';
import { Peer } from './wire.js';
import { parseToken } from './identity.js';
import { parseDevices } from './adb.js';
import {InputQueue,motionRttSample} from './input-queue.js';

export const controlMethods = new Set(['input', 'text', 'acquire', 'release', 'keepControl', 'inputReset']);
const validPort = n => Number.isInteger(n) && n > 0 && n < 65536;
const usbSerial = s => typeof s === 'string' && /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,199}$/.test(s) && !s.startsWith('emulator-') && !s.includes('_adb-tls-');

// A second authenticated link shares permissions/ownership with its existing LAN peer.
// Input is never raced or replayed. A lost link resets held keys through the LAN path.
export class LinkManager {
  constructor(service) {
    this.service = service; this.links = new Set(); this.servers = new Set();
    this.pending = new Set(); this.retries = new Map(); this.closed = false;
  }
  wrap(primary) {
    if (primary.lanRequest) return;
    primary.lanRequest = primary.request.bind(primary);
    const send=async(method,args,timeout)=>{
        const link = primary.fastLink;
        if (!link || link.peer.closed) return primary.lanRequest(method, args, timeout);
        try { return await link.peer.request(method, args, Math.min(timeout || 15000, 1500)); }
        catch (error) {
          if (link.peer.closed || error.message === '设备响应超时') {
            await this.drop(link);
            await primary.lanRequest('inputReset', {}, 1500).catch(() => {});
          }
          throw error; // Never repeat a possibly delivered key/click/text operation.
        }
    };
    const measure=(method,args)=>motionRttSample(method,args)&&(!primary.quicConn||!!primary.fastLink);
    primary.inputScheduler=new InputQueue(send,{adaptive:true,measure});
    let heartbeat;
    primary.request = (method,args={},timeout)=>{
      // Renewal has no input ordering semantics. Waiting for its network reply
      // must not pause Datagram movement or clicks on a high-latency connection.
      if(method==='keepControl') {
        if(!heartbeat) {
          const start=performance.now(),measureRtt=measure(method,args);
          heartbeat=send(method,args,timeout).then(result=>{
            if(measureRtt)primary.inputScheduler.feedback({rttMs:performance.now()-start});
            return result;
          }).finally(()=>{heartbeat=null;});
        }
        return heartbeat;
      }
      return controlMethods.has(method)?primary.inputScheduler.request(method,args,timeout):primary.lanRequest(method,args,timeout);
    };
  }
  valid(primary) {
    return !this.closed && !this.service.closing && !this.service.changingGroup && !primary.closed
      && this.service.peers.get(primary.remote.id) === primary && !this.service.group.excluded(primary.remote.id);
  }
  activate(primary, link) {
    if (!this.valid(primary)) throw Error('设备连接已结束');
    this.wrap(primary); primary.fastLink = link; this.service.emit('change');
  }
  async dispatch(primary, link, m) {
    if (!this.valid(primary)) { await this.drop(link); return; }
    if (m.type !== 'request') return;
    if ((link.inFlight = (link.inFlight || 0) + 1) > 32) { await this.drop(link); return; }
    try {
      let result;
      if (m.method === 'linkPing') result = {};
      else if (m.method === 'linkUse') { this.activate(primary, link); result = {}; }
      else if (controlMethods.has(m.method)) result = await this.service.handle(primary, m.method, m.args || {});
      else throw Error('辅助通道不支持此操作');
      if (!link.peer.closed) link.peer.send({ type: 'response', id: m.id, result });
    } catch (error) { if (!link.peer.closed) link.peer.send({ type: 'response', id: m.id, error: error.message }); }
    finally { link.inFlight--; }
  }
  bind(primary, peer, meta) {
    const link = { primary, peer, ...meta }; this.links.add(link); this.wrap(primary);
    peer.on('message', m => this.dispatch(primary, link, m).catch(() => this.drop(link)));
    peer.on('closed', () => this.drop(link));
    link.onPrimaryClosed = () => this.drop(link);
    primary.once('closed', link.onPrimaryClosed);
    return link;
  }
  async offer(primary) {
    if (!this.valid(primary)) throw Error('设备连接已结束');
    if (this.servers.size >= 8) throw Error('辅助通道繁忙');
    const secret = randomBytes(32), server = net.createServer();
    const close = () => { clearTimeout(timer); primary.off('closed', close); server.close(); this.servers.delete(server); };
    const timer = setTimeout(close, 6000); timer.unref(); this.servers.add(server);
    primary.once('closed', close);
    server.on('connection', socket => {
      if (!this.valid(primary)) { socket.destroy(); return; }
      const peer = new Peer(socket, this.service.identity(), secret);
      const link = this.bind(primary, peer, { host: socket.remoteAddress, kind: 'network' });
      peer.on('ready', () => { if (peer.remote.id !== primary.remote.id) this.drop(link); else close(); });
    });
    await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, this.service.options.host || '0.0.0.0', resolve); });
    return { port: server.address().port, secret: 'lanlink1:' + secret.toString('base64url') };
  }
  async forward(serial, port, reverse = false) {
    if (!validPort(port)) throw Error('辅助通道端口无效');
    const adb = this.service.adb, exe = await adb.locate(); if (!exe) throw Error('未找到 ADB');
    const method = reverse ? 'reverse' : 'forward';
    const assigned = Number((await adb.command(exe, ['-s', serial, method, 'tcp:0', `tcp:${port}`], 3000)).trim());
    if (!validPort(assigned)) throw Error('数据线通道未就绪');
    return { port: assigned, close: () => adb.command(exe, ['-s', serial, method, '--remove', `tcp:${assigned}`], 2000).catch(() => {}) };
  }
  async candidate(id, host, serial) {
    const primary = this.service.peers.get(id);
    if (!primary || !this.valid(primary) || primary.info?.capabilities?.dualLink !== true || this.pending.has(id)
        || [...this.links].filter(l => l.primary === primary).length >= 3) return;
    const key = id + '|' + (serial || host);
    if ((this.retries.get(key) || 0) > Date.now()) return;
    this.retries.set(key, Date.now() + 10000);
    if ([...this.links].some(l => l.primary === primary && !l.peer.closed && (serial ? l.serial === serial : l.host === host))) return;
    this.pending.add(id); let tunnel, link;
    try {
      const info = await primary.request('linkOpen', {}, 3000);
      if (!validPort(info.port)) throw Error('辅助通道协商失败');
      if (serial) tunnel = await this.forward(serial, info.port);
      if (!this.valid(primary)) throw Error('设备连接已结束');
      const socket = net.connect({ host: serial ? '127.0.0.1' : host, port: tunnel?.port || info.port });
      socket.on('error', () => {});
      const peer = new Peer(socket, this.service.identity(), parseToken(info.secret));
      link = this.bind(primary, peer, { host, serial, tunnel, kind: serial ? 'usb' : 'network' });
      await new Promise((resolve, reject) => { peer.once('ready', resolve); peer.once('closed', () => reject(Error('辅助通道连接失败'))); });
      if (peer.remote.id !== id || !this.valid(primary)) throw Error('辅助设备身份不匹配');
      const measure = async fn => { const start = performance.now(); await fn(); return performance.now() - start; };
      let fast = 0, lan = 0;
      for (let i = 0; i < 3; i++) {
        fast += await measure(() => peer.request('linkPing', {}, 1500));
        lan += await measure(() => primary.lanRequest('linkPing', {}, 1500));
      }
      link.rtt = fast / 3; link.lanRtt = lan / 3;
      if (fast <= lan + 6 && (!primary.fastLink || primary.fastLink.peer.closed || link.rtt <= primary.fastLink.rtt + 1)) {
        await peer.request('linkUse', {}, 1500); this.activate(primary, link);
      }
      this.service.emit('change');
    } catch { if (link) await this.drop(link); else await tunnel?.close(); }
    finally { this.pending.delete(id); }
  }
  async verifyUsb(id, serial) {
    const primary=this.service.getPeer(id);let tunnel,peer,timer;
    try {
      const info=await primary.request('linkOpen',{},3000);
      tunnel=await this.forward(serial,info.port);
      const socket=net.connect({host:'127.0.0.1',port:tunnel.port});socket.on('error',()=>{});
      peer=new Peer(socket,this.service.identity(),parseToken(info.secret));
      await new Promise((resolve,reject)=>{timer=setTimeout(()=>reject(Error('USB 身份验证超时')),2500);peer.once('ready',resolve);peer.once('closed',()=>reject(Error('USB 设备不匹配')));});
      if(peer.remote.id!==id || !this.valid(primary))return false;
      await peer.request('linkPing',{},1500);return true;
    } catch {return false;} finally {clearTimeout(timer);peer?.close();await tunnel?.close();}
  }
  async tick() {
    if (this.closed || this.scanning) return;
    this.scanning = true;
    try {
      for (const link of [...this.links]) {
        if (!this.valid(link.primary)) { await this.drop(link); continue; }
        try {
          const start = performance.now(); await link.peer.request('linkPing', {}, 1200);
          link.rtt = performance.now() - start;
          if (link.primary.fastLink === link) {
            const lanStart = performance.now(); await link.primary.lanRequest('linkPing', {}, 1200);
            if (link.rtt > (performance.now() - lanStart) * 1.5 + 5) await this.drop(link);
          } else if (!link.primary.fastLink) {
            const lanStart = performance.now(); await link.primary.lanRequest('linkPing', {}, 1200);
            if (link.rtt <= performance.now() - lanStart + 2) {
              await link.peer.request('linkUse', {}, 1200); this.activate(link.primary, link);
            }
          }
        } catch { await this.drop(link); }
      }
      const android = [...this.service.peers].filter(([, p]) => p.info?.platform === 'android' && p.info.capabilities?.dualLink === true);
      if (!android.length) return;
      const exe = await this.service.adb.locate(); if (!exe) return;
      const devices = parseDevices(await this.service.adb.command(exe, ['devices', '-l'], 3000));
      for (const [id, primary] of android) {
        if (primary.fastLink?.serial) continue;
        const saved = this.service.config.adbBindings?.[id]?.serial;
        for (const device of devices.filter(d => d.status === 'device' && usbSerial(d.serial)).sort((a, b) => Number(b.serial === saved) - Number(a.serial === saved)).slice(0, 4)) {
          await this.candidate(id, null, device.serial);
          if (primary.fastLink?.serial) break;
        }
      }
    } finally { this.scanning = false; }
  }
  async videoEndpoint(primary, info) {
    const link = primary.fastLink;
    if (!link || link.peer.closed) return null;
    if (!link.serial) return { host: link.host, info, close: async () => {}, failed: () => this.drop(link) };
    try {
      const tunnel = await this.forward(link.serial, info.port);
      return { host: '127.0.0.1', info: { ...info, port: tunnel.port }, close: tunnel.close, failed: () => this.drop(link) };
    } catch { await this.drop(link); return null; }
  }
  async reverseVideo(primary, info) {
    const link = primary.fastLink;
    if (!link?.serial || link.peer.closed) return info;
    try {
      const tunnel = await this.forward(link.serial, info.port, true);
      const session = this.service.videoHost.sessions.get(info.id);
      if (!session) { await tunnel.close(); return info; }
      session.tunnel = tunnel;
      return { ...info, usbPort: tunnel.port };
    } catch { await this.drop(link); return info; }
  }
  status(primary) {
    const link = primary.fastLink;
    return link && !link.peer.closed ? (link.serial ? 'USB + 局域网' : '双网络 · 低延迟通道') : primary.quicConn?'QUIC · 局域网':'局域网';
  }
  async drop(link) {
    if (!this.links.delete(link)) return;
    link.primary.off('closed', link.onPrimaryClosed);
    if (link.primary.fastLink === link) {
      link.primary.fastLink = null;
      if (this.service.owner?.id === link.primary.remote.id) this.service.native.call('release').catch(() => {});
    }
    link.peer.close(); await link.tunnel?.close(); this.service.emit('change');
  }
  async close() {
    this.closed = true; for (const server of this.servers) server.close(); this.servers.clear();
    await Promise.all([...this.links].map(link => this.drop(link)));
  }
}
