import test from 'node:test';
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

const run = promisify(execFile);
test('macOS resolves and restores language preferences without translating user content', { skip: process.platform !== 'darwin', timeout: 90000 }, async t => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'lanlink-locale-probe-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const executable = path.join(root, 'probe');
  await run('xcrun', ['swiftc', '-swift-version', '5', '-module-cache-path', path.join(root, 'modules'), 'desktop/macos/Localization.swift', 'tests/MacLocalizationProbe.swift', '-o', executable]);
  assert.match((await run(executable)).stdout, /MAC_LOCALIZATION_PROBE_OK/);
});
