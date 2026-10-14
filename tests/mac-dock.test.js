import test from 'node:test';
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

const run = promisify(execFile);

// Requires the logged-in macOS WindowServer and temporarily takes keyboard focus.
// Run live UI tests with --test-concurrency=1 so their windows do not compete.
test('macOS saves Dock visibility offline and reopens its window through the menu bar', { skip: process.platform !== 'darwin' || process.env.LANLINK_MAC_UI_TESTS !== '1', timeout: 240000 }, async t => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'lanlink-dock-probe-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const executable = path.join(root, 'probe');
  await run('xcrun', ['swiftc', '-swift-version', '5', '-D', 'LANLINK_MODEL_TEST', '-module-cache-path', path.join(root, 'modules'), '-framework', 'AppKit', '-framework', 'SwiftUI', '-framework', 'ServiceManagement', 'desktop/macos/LanLink.swift', 'desktop/macos/SharedLayout.swift', 'desktop/macos/Localization.swift', 'desktop/macos/SingleInstance.swift', 'desktop/macos/Remote.swift', 'desktop/macos/AdbGuide.swift', 'desktop/macos/Staging.swift', 'tests/MacDockProbe.swift', '-o', executable], { timeout: 210000 });
  const result = await run(executable, [], { timeout: 25000 });
  assert.match(result.stdout, /MAC_DOCK_PROBE_OK/, result.stderr);
});
