import { createRequire } from 'node:module';
import { mkdtemp, rm, mkdir } from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';
import { Service } from '../src/service.js';
import { NativeBridge } from '../src/native.js';
import { startUI } from '../src/http.js';
import { newToken } from '../src/identity.js';

const require = createRequire(import.meta.url);
const modulePath = process.env.LANLINK_TEST_MODULES;
const { chromium } = require(modulePath ? path.join(modulePath, 'playwright') : 'playwright');
const folder = await mkdtemp(path.join(os.tmpdir(), 'lanlink-ui-'));
const token = newToken(); let browser, ui; const services = [];
try {
  for (const name of ['Windows 工作台', 'Mac 测试设备']) {
    const s = new Service({ id: randomUUID(), name, token, dwell: 450, edges: {}, master: true, permissions: { input: true, screen: true, clipboard: true, shutdown: false } }, path.join(folder, name), new NativeBridge({ disabled: true }), { port: 0, host: '127.0.0.1', noDiscovery: true });
    await s.start(); services.push(s);
  }
  services[0].connect('127.0.0.1', services[1].port);
  ui = await startUI(services[0], 0);
  browser = await chromium.launch({ channel: process.env.LANLINK_BROWSER || 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1200 } }); const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.goto(ui.url); await page.getByRole('heading', { name: 'Mac 测试设备' }).waitFor();
  await page.locator('#dwell').fill('600'); await page.locator('#saveLayout').click(); await page.waitForFunction(() => document.getElementById('notice').textContent.includes('已保存'));
  assert.equal(services[0].config.dwell, 600);
  await page.locator('#showToken').click(); await page.locator('#pairQr').waitFor({ state: 'visible' }); assert.equal(await page.locator('#ownToken').inputValue(), token);
  assert(await page.locator('#pairQr').evaluate(img => img.complete && img.naturalWidth > 50));
  await page.locator('#showToken').click(); await page.locator('#shareText').fill('UI 端到端共享验证'); await page.locator('#share').click();
  await page.waitForFunction(() => document.getElementById('notice').textContent.includes('已共享'));
  assert.equal(services[0].clipboard.text, 'UI 端到端共享验证');
  await page.waitForFunction(() => !document.getElementById('notice').textContent);
  await page.evaluate(() => scrollTo(0, 0));
  await mkdir('dist', { recursive: true });
  await page.screenshot({ path: 'dist/desktop-preview.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 }); await page.evaluate(() => scrollTo(0, 0)); await page.screenshot({ path: 'dist/mobile-layout-preview.png', fullPage: true });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, 'Mobile viewport overflow');
  assert.deepEqual(errors, []); console.log('UI smoke passed: pairing QR, save layout, shared text, responsive layout, no browser exceptions.');
} finally { await browser?.close(); if (ui) await new Promise(r => ui.server.close(r)); await Promise.all(services.map(s => s.close())); await rm(folder, { recursive: true, force: true }); }
