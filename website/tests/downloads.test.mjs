import assert from 'node:assert/strict';
import test from 'node:test';
import { sourceForCountry, detectSource, releaseLinks, loadRelease, resolveDownload } from '../downloads.js';

test('IP region determines source independently of UI language', () => {
  assert.equal(sourceForCountry('CN'), 'gitee');
  for (const country of ['US', 'JP', 'HK', 'TW', undefined]) assert.equal(sourceForCountry(country), 'github');
});
test('region detection and network failure fallback', async () => {
  assert.equal(await detectSource(async () => ({ ok: true, text: async () => 'ip=1.2.3.4\nloc=CN\n' })), 'gitee');
  assert.equal(await detectSource(async () => { throw Error('offline'); }), 'github');
});
const release = (host, version = '0.91.0') => ({ tag_name: 'v' + version, assets: [
  `LanLink-${version}-windows-x64-setup.exe`, `LanLink-${version}-macos-arm64.zip`, `LanLink-${version}-android.apk`, `LanLink-${version}-win32-x64.full.json.gz`
].map(name => ({ name, browser_download_url: `https://${host}/lokey-t/lanlink/releases/download/v${version}/${name}` })) });
test('both sources resolve installers and follow future stable versions', async () => {
  for (const [source, host] of [['gitee', 'gitee.com'], ['github', 'github.com']]) {
    for (const version of ['0.91.0', '1.0.0']) {
      const result = await loadRelease(source, async () => ({ ok: true, json: async () => release(host, version) }));
      assert.equal(result.version, version);
      assert.equal(Object.keys(result.links).length, 4);
      assert.ok(result.links.windows.endsWith('-setup.exe'));
    }
  }
});
test('missing assets keep release fallback and untrusted URLs are rejected', () => {
  assert.equal(releaseLinks({ tag_name: 'v1.0.0', assets: [] }, 'github').links.windowsPortable, 'https://github.com/lokey-t/lanlink/releases/download/v1.0.0/LanLink-1.0.0-windows-x64.zip');
  assert.equal(releaseLinks(release('evil.example'), 'github').links.windows, undefined);
  assert.throws(() => releaseLinks({ ...release('github.com'), prerelease: true }, 'github'));
});
import { readFile } from 'node:fs/promises';
test('every platform offers separate release and direct download links', async () => {
  const html = await readFile(new URL('../index.html', import.meta.url), 'utf8');
  const cards = [...html.matchAll(/<article class="download-card"[^>]*>([\s\S]*?)<\/article>/g)];
  assert.equal(cards.length, 3);
  for (const [, card] of cards) {
    assert.ok(card.indexOf('data-release-link') < card.indexOf('data-download-link'));
    assert.match(card, /<a[^>]*data-release-link/);
    assert.match(card, /<(?:button|summary)[^>]*data-download-link/);
  }
  assert.match(html, /data-i18n="downloadFallback"/);
});

test('each click resolves the latest version and generates both Windows download variants', async () => {
  let version = '0.91.0';
  let calls = 0;
  const fetcher = async () => { calls++; return { ok: true, json: async () => ({ tag_name: 'v' + version, assets: [] }) }; };
  assert.ok((await resolveDownload('gitee', 'windows', fetcher)).url.endsWith('/LanLink-0.91.0-windows-x64-setup.exe'));
  version = '1.0.0';
  assert.equal((await resolveDownload('gitee', 'windowsPortable', fetcher)).url, 'https://gitee.com/lokey-t/lanlink/releases/download/v1.0.0/LanLink-1.0.0-windows-x64.zip');
  assert.equal(calls, 2);
  await assert.rejects(resolveDownload('github', 'android', async () => { throw Error('offline'); }));
});
import { websiteQrUrl } from '../downloads.js';
test('website QR uses the public download page and excludes local preview addresses', () => {
  assert.equal(websiteQrUrl('https://lanlink.sites.show/', 'file:///local/index.html'), 'https://lanlink.sites.show/#get');
  assert.equal(websiteQrUrl('', 'https://example.com/app/?tracking=1#old'), 'https://example.com/app/#get');
  for (const url of ['file:///local/index.html', 'http://localhost:4173', 'http://127.0.0.1:4173', 'http://192.168.1.2', 'javascript:alert(1)']) assert.equal(websiteQrUrl('', url), null);
});
