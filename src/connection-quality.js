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
      try { await (peer.lanRequest || peer.request).call(peer,'linkPing',{},1000); samples.push(performance.now()-start); } catch {}
      if(i<9) await new Promise(r=>setTimeout(r,100));
    }
    return summarizeQuality(samples);
  } finally { running.delete(peer); }
}
