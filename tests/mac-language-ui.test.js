import test from 'node:test';
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, mkdir, writeFile, copyFile, symlink, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
const run = promisify(execFile);

test('macOS saves language live and retains settings drafts when switching language', { skip: process.platform !== 'darwin' || process.env.LANLINK_MAC_UI_TESTS !== '1', timeout: 240000 }, async t => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'lanlink-language-ui-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  await mkdir(path.join(root, 'src')); await mkdir(path.join(root, 'runtime'));
  await symlink(process.execPath, path.join(root, 'runtime', 'node'));
  await copyFile('src/locales.json', path.join(root, 'src', 'locales.json'));
  await writeFile(path.join(root, 'config.json'), JSON.stringify({ language: 'en' }));
  await writeFile(path.join(root, 'src', 'native-host.js'), `
const { createInterface } = require('node:readline');
let language = 'en';
process.stdout.write(JSON.stringify({ready:true})+'\\n');
createInterface({input:process.stdin}).on('line', line => {
 const r=JSON.parse(line);
 if(r.method==='settings' && r.args.language==='fr') { process.stdout.write(JSON.stringify({id:r.id,error:'Failed to save fixture settings'})+'\\n'); return; }
 if(r.method==='settings') language=r.args.language;
 const result=r.method==='state' ? {id:'local',name:'Saved device',language,groupId:'group',capabilities:{simulated:true},masterDevices:[]} : {};
 process.stdout.write(JSON.stringify({id:r.id,result})+'\\n');
}).on('close',()=>process.exit(0));
`);
  const executable = path.join(root, 'probe');
  await run('xcrun', ['swiftc', '-swift-version', '5', '-D', 'LANLINK_MODEL_TEST', '-module-cache-path', path.join(root, 'modules'), '-framework', 'AppKit', '-framework', 'SwiftUI', '-framework', 'ServiceManagement', 'desktop/macos/LanLink.swift', 'desktop/macos/Localization.swift', 'desktop/macos/SingleInstance.swift', 'desktop/macos/Remote.swift', 'desktop/macos/AdbGuide.swift', 'desktop/macos/Staging.swift', 'tests/MacLanguageUIProbe.swift', '-o', executable], { timeout: 210000 });
  const result = await run(executable, [root], { timeout: 20000 });
  assert.match(result.stdout, /MAC_LANGUAGE_UI_PROBE_OK/, result.stderr);
});
