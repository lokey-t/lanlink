import os from 'node:os';
import path from 'node:path';
import { readFile, rename, writeFile, rm } from 'node:fs/promises';
import { randomBytes } from 'node:crypto';
import { buildInfo } from './build-info.js';
import { parseDevices } from './adb.js';

// Application probes measure responsiveness, not packet loss or bandwidth.
export function summarizeQuality(samples, attempts = 10) {
  const averageMs = samples.length ? samples.reduce((a,b)=>a+b,0)/samples.length : null;
  const jitterMs = samples.length > 1 ? samples.slice(1).reduce((s,n,i)=>s+Math.abs(n-samples[i]),0)/(samples.length-1) : null;
  const timeoutPercent = 100*(attempts-samples.length)/attempts;
  const grade = averageMs === null ? '无法连接' : timeoutPercent >= 20 || averageMs >= 150 || jitterMs >= 50 ? '较差' : timeoutPercent > 0 || averageMs >= 60 || jitterMs >= 20 ? '一般' : '良好';
  const metric = n => n === null ? '—' : n.toFixed(1)+' ms';
  const advice = grade === '良好' ? '连接稳定，适合键鼠和屏幕协作。' : '建议靠近路由器、减少网络占用，或使用有线网络 / USB 后重测。';
  return {averageMs,jitterMs,timeoutPercent,attempts,received:samples.length,grade,message:`${grade}\n平均往返延迟：${metric(averageMs)}\n抖动：${metric(jitterMs)}\n探测超时率：${timeoutPercent.toFixed(0)}%（${attempts} 次）\n${advice}\n仅测量加密 TCP 控制通道；超时率不等于网络丢包率，不代表传输带宽。`};
}
const running = new WeakSet();
export async function analyzeConnection(peer) {
  if (!peer || peer.closed) throw Error('设备未连接');
  if (running.has(peer)) throw Error('正在分析此设备的连接质量');
  running.add(peer);
  try {
    const samples = [];
    for(let i=0;i<10;i++) {
      const start=performance.now();
      try { await (peer.tcpRequest || peer.lanRequest || peer.request).call(peer,'linkPing',{},1000); samples.push(performance.now()-start); } catch {}
      if(i<9) await new Promise(r=>setTimeout(r,100));
    }
    return summarizeQuality(samples);
  } finally { running.delete(peer); }
}

export const qualityBuild = buildInfo.sourceFingerprint
  ? buildInfo.version + '+' + buildInfo.sourceFingerprint.slice(0,12) : buildInfo.version;
const latencyCount = 8;
const latencyTimeout = 800;
const chunkBytes = 64 * 1024;
const transferCount = 6;
const transferTimeout = 2200;
const probePayload = randomBytes(chunkBytes).toString('base64');
const remoteLimits = new WeakMap();
const usbLabels = {
  connected:'USB 通道已验证',
  adb_device:'ADB 已授权，USB 通道未建立',
  adb_unauthorized:'等待手机确认 USB 调试',
  adb_offline:'ADB 设备离线',
  adb_no_permissions:'电脑没有访问 USB 设备的权限',
  adb_missing:'未找到 ADB，无法检查数据线',
  not_detected:'未检测到 USB 调试设备',
  unverified_device_detected:'检测到 USB 设备，尚无法确认是这台手机'
};

// Fixed-size synthetic traffic only. No access to clipboard or files is needed.
export function qualityTransfer(peer, args) {
  const now = Date.now();
  let budget = remoteLimits.get(peer);
  if (!budget || now - budget.at >= 1000) remoteLimits.set(peer, budget = {at:now, count:0});
  if (++budget.count > 24) throw Error('QUALITY_RATE_LIMIT');
  if (args.direction === 'download') return {data:probePayload};
  if (args.direction === 'upload' && typeof args.data === 'string' && args.data.length === probePayload.length
      && Buffer.from(args.data,'base64').length === chunkBytes) return {bytes:chunkBytes};
  throw Error('QUALITY_INVALID_PAYLOAD');
}

function failureCode(error) {
  const message = String(error?.message);
  if (/QUALITY_(RATE_LIMIT|INVALID_PAYLOAD|INVALID_REPLY|ROUTE_CHANGED|BUSY)/.test(message)) return message.match(/QUALITY_[A-Z_]+/)[0];
  if (/USB 设备不匹配/.test(message)) return 'USB_IDENTITY_UNVERIFIED';
  if (/timeout|timed out|超时/i.test(message)) return 'TIMEOUT';
  if (/closed|disconnect|未连接|已断开|已结束/i.test(message)) return 'DISCONNECTED';
  if (/unsupported|不支持|未知/i.test(message)) return 'UNSUPPORTED';
  if (/busy|繁忙|请求过多/i.test(message)) return 'BUSY';
  return 'REQUEST_FAILED';
}

function latencySummary(samples) {
  const values = samples.filter(s => s.ms != null).map(s => s.ms);
  const summary = summarizeQuality(values, samples.length || latencyCount);
  const sorted = [...values].sort((a,b) => a-b);
  const timeouts = samples.filter(s => s.error === 'TIMEOUT').length;
  return {
    averageMs:summary.averageMs, jitterMs:summary.jitterMs, peakMs:values.length ? Math.max(...values) : null,
    minMs:values.length ? Math.min(...values) : null, attempts:samples.length, received:values.length,
    p95Ms:sorted.length ? sorted[Math.ceil(sorted.length*.95)-1] : null,
    timeouts, timeoutPercent:samples.length ? timeouts*100/samples.length : null, grade:summary.grade
  };
}

function transferSummary(samples) {
  const received = samples.filter(s => s.bytes);
  const bytes = received.reduce((n,s) => n + s.bytes,0);
  const elapsedMs = received.reduce((n,s) => n + s.ms,0);
  return {bytes, elapsedMs, mbps:elapsedMs > 0 ? bytes * 8 / elapsedMs / 1000 : null,
    completed:received.length, attempts:samples.length};
}

export class ConnectionQuality {
  constructor(service) {
    this.service=service;
    this.file=service.dir ? path.join(service.dir,'connection-quality.json') : null;
    this.writes=Promise.resolve();
  }
  load() {
    if (!this.file) return Promise.resolve();
    return this.loading ||= (async () => {
      const group=this.service.group;
      try {
        const stored=JSON.parse(await readFile(this.file,'utf8'));
        if (!this.session && group === this.service.group && stored.format === 1 && stored.group === group.data.id
            && Array.isArray(stored.result?.devices) && stored.result.finishedAt) {
          this.session={group,result:stored.result};
        }
      } catch (error) {
        if (error.code !== 'ENOENT') this.service.log('无法读取最近一次连接质量报告');
      }
    })();
  }
  save(session) {
    if (!this.file || session.group !== this.service.group) return this.writes;
    const bytes=JSON.stringify({format:1,group:session.group.data.id,result:session.result});
    this.writes=this.writes.then(async () => {
      const temporary=this.file+'.tmp';
      try {
        await writeFile(temporary,bytes,{mode:0o600});
        await rename(temporary,this.file);
      } catch {
        await rm(temporary,{force:true}).catch(() => {});
        this.service.log('无法保存最近一次连接质量报告');
      }
    });
    return this.writes;
  }
  devices() {
    const {config,group,peers} = this.service;
    return Object.entries(group.data.members)
      .filter(([id]) => id !== config.id && !group.excluded(id))
      .map(([id,member]) => {
        const peer = peers.get(id);
        return {id, name:peer?.info?.name || member.name, platform:peer?.info?.platform || member.platform, online:!!peer && !peer.closed};
      });
  }
  state() {
    const result = this.session?.group === this.service.group ? this.session.result : null;
    const session = result && {...result,devices:result.devices.map(device => ({
      ...device,usbMessage:device.usb ? usbLabels[device.usb] || '无法检查 USB 调试状态' : undefined
    }))};
    return {devices:this.devices(),session};
  }
  start(id) {
    if (this.session?.result.status === 'running') throw Error('正在测试连接质量');
    if (id != null && typeof id !== 'string') throw Error('请选择本组设备');
    const devices = this.devices().filter(device => !id || device.id === id);
    if (!devices.length) throw Error('请选择本组设备');
    const {config,active,owner} = this.service;
    const result = {status:'running', startedAt:new Date().toISOString(), scope:id ? 'device' : 'group',
      host:{name:config.name, platform:process.platform, osRelease:os.release(), architecture:process.arch,
        version:buildInfo.version, build:qualityBuild, controlling:!!active, controlled:!!owner},
      devices:devices.map(d => ({...d,status:'pending',routes:[]}))};
    const session = this.session = {group:this.service.group,result};
    const task=this.run(session).catch(error => {
      result.error=failureCode(error);
      return this.finish(session,'failed');
    });
    this.service.trackTask?.(task);
    return this.state();
  }
  finish(session,status) {
    session.result.status=status;
    session.result.finishedAt=new Date().toISOString();
    delete session.result.currentDevice;
    delete session.result.stage;
    delete session.result.cancelling;
    if (status === 'cancelled') {
      for (const device of session.result.devices) if (device.status === 'pending') device.status='cancelled';
    }
    return this.save(session);
  }
  stop() {
    if (this.session?.result.status === 'running') this.session.result.cancelling = true;
    return this.state();
  }
  stopped(session) {
    return session.result.cancelling || this.service.closing || this.service.changingGroup || session.group !== this.service.group;
  }
  async inspectUsb() {
    try {
      const adb = this.service.adb;
      const exe = await adb?.locate();
      if (!exe) return {state:'adb_missing',devices:[]};
      const devices = parseDevices(await adb.command(exe,['devices','-l'],3000))
        .filter(d => /^[a-zA-Z0-9._-]+$/.test(d.serial) && !d.serial.startsWith('emulator-') && !d.serial.includes('_adb-tls-'));
      return {state:'checked',devices};
    } catch (error) { return {state:failureCode(error),devices:[]}; }
  }
  routes(peer) {
    const current = () => this.service.currentPeer(peer);
    const route = (kind,protocol,request,alive,mouse=false) => ({kind,protocol,request,alive:() => current() && alive(),mouse});
    const fast = peer.fastLink?.peer.closed ? null : peer.fastLink;
    const routes = [route('lan','TCP / LanLink AES-GCM',peer.tcpRequest || peer.lanRequest || peer.request.bind(peer),
      () => true,!fast && !peer.quicConn)];
    if (peer.quicConn) {
      const conn=peer.quicConn;
      routes.push(route('lan','QUIC / UDP',(m,a,t) => this.service.quic.rpc(peer,m,a,t),() => peer.quicConn === conn,!fast));
    }
    for (const link of this.service.links.links) {
      if (link.primary !== peer || !link.peer.ready || link.peer.closed || link.peer.remote?.id !== peer.remote.id) continue;
      routes.push(route(link.serial ? 'usb' : 'lan-alt',link.serial ? 'ADB USB tunnel / TCP / LanLink AES-GCM' : 'TCP / LanLink AES-GCM',
        link.peer.request.bind(link.peer),() => !link.peer.closed,link === fast));
    }
    return routes;
  }
  usbState(device, usb) {
    const binding = this.service.config.adbBindings?.[device.id];
    const matched = binding && this.service.adb?.validBinding(device.id,binding)
      && usb?.devices.find(d => d.serial === binding.serial);
    return {matched, state:matched ? 'adb_' + matched.status.replaceAll(' ','_')
      : usb?.devices.length ? 'unverified_device_detected' : usb?.state === 'checked' ? 'not_detected' : usb?.state};
  }
  async inspectRoute(session,peer,device,usb,matched) {
    if (!device.capabilities.dualLink) return;
    const candidates=usb?.devices.filter(d => d.status === 'device') || [];
    if (matched?.status === 'device') candidates.sort((a,b) => Number(b===matched)-Number(a===matched));
    session.result.stage='usb';
    device.usbAttempts=[];
    for (const candidate of candidates.slice(0,4)) {
      if (this.stopped(session)) return;
      const start=performance.now();
      try {
        const link=await this.service.links.openVerifiedUsb(peer,candidate.serial);
        device.usbAttempts.push({ms:performance.now()-start, result:'verified', savedBinding:candidate===matched});
        return link;
      } catch (error) {
        device.usbAttempts.push({ms:performance.now()-start, error:failureCode(error), savedBinding:candidate===matched});
      }
    }
  }
  async run(session) {
    const {result} = session;
    const usb = result.devices.some(d => d.platform === 'android') ? await this.inspectUsb() : null;
    if (this.stopped(session)) return this.finish(session,'cancelled');
    if (usb) result.usbScan = {state:usb.state, adbStates:usb.devices.map(d => d.status)};
    for (const device of result.devices) {
      if (this.stopped(session)) break;
      result.currentDevice = device.id;
      const peer = this.service.peers.get(device.id);
      const usbState = device.platform === 'android' ? this.usbState(device,usb) : null;
      if (usbState) device.usb = usbState.state;
      if (!peer || peer.closed) { device.status = 'offline'; continue; }
      device.status = 'testing';
      const caps = peer.info?.capabilities || {};
      device.capabilities = {quality:caps.qualityProbe === 1, quic:caps.quic === 1, dualLink:caps.dualLink === true,
        motionV2:caps.motionV2 === 2, input:caps.input === true, screen:caps.screen === true};
      if (/^[\w.+-]{1,80}$/.test(caps.qualityBuild || '')) device.build = caps.qualityBuild;
      const routes = this.routes(peer);
      let temporaryUsb;
      try {
        if (usbState) {
          if (routes.some(r => r.kind === 'usb')) device.usb = 'connected';
          else {
            temporaryUsb = await this.inspectRoute(session,peer,device,usb,usbState.matched);
            if (temporaryUsb) {
              device.usb = 'connected';
              const tunnel=temporaryUsb.peer;
              routes.push({kind:'usb',protocol:'ADB USB tunnel / TCP / LanLink AES-GCM',mouse:false,
                request:tunnel.request.bind(tunnel),alive:() => !tunnel.closed && this.service.peers.get(device.id) === peer && !peer.closed});
            }
          }
        }
        for (const route of routes) {
          if (this.stopped(session)) break;
          await this.testRoute(session,peer,device,route);
        }
      } finally { await temporaryUsb?.close(); }
      device.status = this.stopped(session) ? 'cancelled'
        : device.routes.some(r => r.latency.received > 0) ? 'completed' : 'unreachable';
    }
    return this.finish(session,this.stopped(session) ? 'cancelled' : 'completed');
  }
  async testRoute(session, peer, device, route) {
    const result = {kind:route.kind,protocol:route.protocol,mouseRoute:route.mouse,samples:[],upload:[],download:[]};
    device.routes.push(result);
    result.latency = latencySummary([]);
    const take = async (work) => {
      const start = performance.now();
      try {
        if (!route.alive()) throw Error('QUALITY_ROUTE_CHANGED');
        const extra = await work();
        if (!route.alive()) throw Error('QUALITY_ROUTE_CHANGED');
        return {atMs:Date.now()-Date.parse(session.result.startedAt),ms:performance.now()-start,...extra};
      } catch (error) { return {atMs:Date.now()-Date.parse(session.result.startedAt),error:failureCode(error)}; }
    };
    const samples = async (stage,count,array,work) => {
      session.result.stage=stage;
      let consecutiveTimeouts=0;
      for (let i=0;i<count && !this.stopped(session);i++) {
        const sample=await take(work);
        array.push(sample);
        if (sample.error && sample.error !== 'TIMEOUT') break;
        consecutiveTimeouts=sample.error === 'TIMEOUT' ? consecutiveTimeouts+1 : 0;
        if ((stage === 'upload' || stage === 'download') && consecutiveTimeouts >= 2) break;
        if (stage === 'latency' || stage === 'mouse') await new Promise(resolve => setTimeout(resolve,80));
      }
    };
    await samples('latency',latencyCount,result.samples,() => route.request('linkPing',{},latencyTimeout));
    result.latency=latencySummary(result.samples);
    if (route.mouse && route.protocol.startsWith('QUIC') && device.capabilities.quality && result.latency.received) {
      result.mouseSamples=[];
      await samples('mouse',latencyCount,result.mouseSamples,() => this.service.quic.qualityPing(peer,latencyTimeout));
      result.mouse=latencySummary(result.mouseSamples);
    } else if (route.mouse && !route.protocol.startsWith('QUIC')) {
      result.mouse=result.latency;
      result.mouseSamples=result.samples;
    }
    if (device.capabilities.quality && result.latency.received) {
      for (const direction of ['upload','download']) {
        await samples(direction,transferCount,result[direction],async () => {
          if (this.service.active || this.service.owner) throw Error('QUALITY_BUSY');
          const response=await route.request('qualityTransfer',direction === 'upload' ? {direction,data:probePayload} : {direction},transferTimeout);
          if (direction === 'upload' ? response?.bytes !== chunkBytes
            : typeof response?.data !== 'string' || response.data.length !== probePayload.length || Buffer.from(response.data,'base64').length !== chunkBytes)
            throw Error('QUALITY_INVALID_REPLY');
          return {bytes:chunkBytes};
        });
      }
    }
    result.uploadSummary=transferSummary(result.upload);
    result.downloadSummary=transferSummary(result.download);
    result.routeStillAvailable=route.alive();
  }
  export() {
    const result=this.state().session;
    if (!result || result.status === 'running') throw Error('请先完成连接质量测试');
    const format = value => typeof value === 'number' ? value.toFixed(2) : '—';
    const details = result.devices.flatMap(d => [
      `- ${JSON.stringify(d.name)} (${d.platform || 'unknown'}) · ${d.status}${d.usb ? ' · USB: '+d.usb : ''}`,
      ...d.routes.map(r => `  ${r.kind} | ${r.protocol} | RTT avg/peak: ${format(r.latency.averageMs)}/${format(r.latency.peakMs)} ms | jitter: ${format(r.latency.jitterMs)} ms | replies: ${r.latency.received}/${r.latency.attempts} | mouse RTT: ${format(r.mouse?.averageMs)} ms | upload/download: ${format(r.uploadSummary?.mbps)}/${format(r.downloadSummary?.mbps)} Mb/s`)
    ]);
    const issues=result.devices.flatMap(d => [
      ...(d.usbAttempts || []).filter(attempt => attempt.error).map(attempt =>
        `- ${JSON.stringify(d.name)} | USB identity / setup | ${attempt.error}`),
      ...d.routes.flatMap(r => [
        ['RTT',r.samples],['Mouse RTT',r.mouseSamples],['Upload',r.upload],['Download',r.download]
      ].flatMap(([stage,samples]) => {
        const counts={};
        for (const sample of samples || []) if (sample.error) counts[sample.error]=(counts[sample.error] || 0)+1;
        return Object.entries(counts).map(([code,count]) =>
          `- ${JSON.stringify(d.name)} | ${r.kind} / ${r.protocol} | ${stage} | ${code} × ${count}`);
      }))
    ]);
    const safe = {...result,devices:result.devices.map(({id,...device}) => device)};
    return {text:[
      'LanLink 连接质量诊断 / Connection quality diagnostic · format 1',
      `UTC: ${result.startedAt} → ${result.finishedAt} | ${result.scope} | ${result.status}`,
      `Host: ${JSON.stringify(result.host.name)} | ${result.host.platform} ${result.host.osRelease} ${result.host.architecture} | ${result.host.build}`,
      ...details,'',
      'Failed stages:', ...(issues.length ? issues : ['- None recorded']), '',
      `Method: ${latencyCount} RTT samples per route (timeout ${latencyTimeout} ms); throughput up to ${transferCount} acknowledged ${chunkBytes}-byte synthetic blocks per direction (timeout ${transferTimeout} ms).`,
      'QUIC mouse RTT uses Datagram echo; TCP mouse RTT uses an echo on the selected TCP transport. Neither measures OS input injection or rendering.',
      'Throughput is application payload goodput, including request overhead. Timeout counts indicate missed probe replies, not network packet loss.',
      'QUALITY_BUSY: throughput probes skipped while remote input was active. QUALITY_ROUTE_CHANGED: the tested connection changed or closed.',
      'An absent throughput measurement on an older peer means the peer does not advertise the probe protocol.',
      'USB is an authenticated ADB tunnel, not a measurement of the USB bus line rate. Unverified ADB devices cannot be attributed to a group peer.',
      'Raw observations (ms, bytes, decimal Mb/s):',JSON.stringify(safe,null,2),''
    ].join('\n')};
  }
}
