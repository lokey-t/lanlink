import { EventEmitter } from 'node:events';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const root = fileURLToPath(new URL('../', import.meta.url));
export class NativeBridge extends EventEmitter {
  constructor({ disabled = false } = {}) { super(); this.disabled = disabled; this.pending = new Map(); this.seq = 0; this.capabilities = {}; }
  async start() {
    if (this.disabled) { this.capabilities = { simulated: true }; return; }
    let command, args;
    if (process.platform === 'win32') { command = 'powershell.exe'; args = ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', path.join(root, 'native/windows/bridge.ps1')]; }
    else if (process.platform === 'darwin') { command = path.join(root, 'native/macos/LanLinkBridge'); args = []; }
    else throw new Error('此版本桌面端支持 Windows 和 macOS');
    this.child = spawn(command, args, { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    this.child.on('error', e => this.fail(e)); this.child.on('exit', () => this.fail(new Error('原生控制模块已退出')));
    this.child.stdin.on('error', e => this.fail(e));
    this.child.stderr.on('data', d => this.emit('diagnostic', String(d).slice(0, 1000)));
    createInterface({ input: this.child.stdout }).on('line', line => {
      try { const m = JSON.parse(line); if (m.replyTo) { const p = this.pending.get(m.replyTo); if (p) { clearTimeout(p.timer); this.pending.delete(m.replyTo); m.error ? p.reject(new Error(m.error)) : p.resolve(m.result); } } else this.emit('event', m); } catch {}
    });
    this.capabilities = await this.call('capabilities', {}, 25000);
  }
  fail(e) { for (const p of this.pending.values()) { clearTimeout(p.timer); p.reject(e); } this.pending.clear(); this.emit('diagnostic', e.message); }
  call(method, args = {}, timeout = 8000) {
    if (this.disabled) {
      if (method === 'clipboardSet') this.simulatedClip = { text: args.text || '', files: args.files || [] };
      if (method === 'screen') return Promise.reject(new Error('模拟模式不提供真实屏幕'));
      return Promise.resolve(method === 'clipboardGet' ? this.simulatedClip || { text: '', files: [] } : method === 'capabilities' ? { simulated: true } : {});
    }
    if (!this.child || this.child.exitCode !== null) return Promise.reject(new Error('原生模块不可用'));
    return new Promise((resolve, reject) => { const id = String(++this.seq); const timer = setTimeout(() => { this.pending.delete(id); reject(new Error('原生操作超时')); }, timeout); this.pending.set(id, { resolve, reject, timer }); this.child.stdin.write(JSON.stringify({ id, method, args }) + '\n'); });
  }
  close() { this.child?.kill(); this.fail(new Error('服务停止')); }
}
