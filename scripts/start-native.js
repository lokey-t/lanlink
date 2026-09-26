import { spawn } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../', import.meta.url));
const { version } = JSON.parse(readFileSync(path.join(root, 'package.json'), 'utf8'));
const target = process.platform === 'win32' ? path.join(root, `dist/LanLink-${version}-windows-x64/LanLink.exe`) : path.join(root, `dist/LanLink-${version}-macos-${process.arch}/LanLink.app`);
if (!existsSync(target)) { console.error('请先运行对应平台的 scripts/package-windows.ps1 或 scripts/package-macos.sh 构建原生软件。'); process.exit(1); }
const child = process.platform === 'win32' ? spawn(target, [], { detached: true, stdio: 'ignore', windowsHide: false }) : spawn('open', [target], { stdio: 'ignore' });
child.on('error', e => { console.error(e.message); process.exitCode = 1; }); child.unref();
