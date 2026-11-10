import { qrcode } from '../vendor/qrcode.mjs';
export const sources = {
  github: { page: 'https://github.com/lokey-t/lanlink/releases/latest', feed: 'https://api.github.com/repos/lokey-t/lanlink/releases/latest' },
  gitee: { page: 'https://gitee.com/lokey-t/lanlink/releases', feed: 'https://gitee.com/api/v5/repos/lokey-t/lanlink/releases/latest' },
};

export function sourceForCountry(country) {
  return country === 'CN' ? 'gitee' : 'github';
}

async function request(url, fetcher) {
  const response = await fetcher(url, { signal: AbortSignal.timeout(5000), cache: 'no-store', credentials: 'omit', referrerPolicy: 'no-referrer' });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response;
}

export async function detectSource(fetcher = fetch) {
  try {
    const trace = await (await request('https://www.cloudflare.com/cdn-cgi/trace', fetcher)).text();
    const country = /^loc=([A-Z]{2})$/m.exec(trace)?.[1];
    if (!country) throw new Error('Missing country');
    return sourceForCountry(country);
  } catch { return 'github'; }
}

export function releaseLinks(release, source) {
  if (release.draft || release.prerelease || !/^v?\d+\.\d+\.\d+(?:\.\d+)?$/.test(release.tag_name)) throw new Error('Invalid stable release');
  const patterns = {
    windows: /-windows-x64-setup\.exe$/i,
    windowsPortable: /-windows-x64\.zip$/i,
    mac: /-macos-arm64\.(zip|dmg)$/i,
    android: /-android\.apk$/i,
  };
  const version = release.tag_name.replace(/^v/, '');
  const links = {};
  for (const [platform, pattern] of Object.entries(patterns)) {
    const asset = (release.assets || []).find(a => a.name?.startsWith(`LanLink-${version}-`) && pattern.test(a.name));
    if (!asset) continue;
    try {
      const url = new URL(asset.browser_download_url);
      const host = source === 'gitee' ? 'gitee.com' : 'github.com';
      if (url.protocol === 'https:' && url.hostname === host && !url.username && !url.password && url.pathname.startsWith('/lokey-t/lanlink/releases/download/')) links[platform] = url.href;
    } catch { /* Keep the release page when an asset URL is invalid. */ }
  }
  return { version, links };
}

export async function loadRelease(source, fetcher = fetch) {
  return releaseLinks(await (await request(sources[source].feed, fetcher)).json(), source);
}

export async function resolveDownload(source, platform, fetcher = fetch) {
  const release = await loadRelease(source, fetcher);
  if (release.links[platform]) return { ...release, url: release.links[platform] };
  const other = source === 'github' ? 'gitee' : 'github';
  const mirror = await loadRelease(other, fetcher).catch(() => null);
  if (mirror?.version === release.version && mirror.links[platform]) {
    return { ...release, links: { ...mirror.links, ...release.links }, url: mirror.links[platform] };
  }
  throw new Error('Missing download');
}

export function setupDownloads({ document, storage, onChange, getText, navigate = url => window.location.assign(url) }) {
  setupWebsiteQr(document, document.location.href);
  const select = document.querySelector('#download-source');
  let preference = storage.get('lanlink.downloadSource') || 'auto';
  if (!['auto', 'github', 'gitee'].includes(preference)) preference = 'auto';
  let generation = 0;
  const state = { source: 'github', version: null, links: {} };
  function render() {
    for (const card of document.querySelectorAll('[data-platform]')) {
      card.querySelector('[data-release-link]').href = sources[state.source].page;

      card.dataset.directDownload = String(Boolean(state.links[card.dataset.platform]));
    }
    onChange(state);
  }
  async function refresh() {
    const id = ++generation;
    const source = preference === 'auto' ? await detected : preference;
    if (id !== generation) return;
    Object.assign(state, { source, version: null, links: {} });
    render();
    try {
      const release = await loadRelease(source);
      if (id !== generation) return;
      Object.assign(state, release);
      render();
    } catch { /* Release pages always remain usable, including API/CORS failures. */ }
  }
  const detected = detectSource();
  const status = document.querySelector('#download-status');
  const packageSelect = document.querySelector('#windows-package');
  packageSelect.addEventListener('change', () => {
    const button = document.querySelector('.windows-download-main');
    button.dataset.downloadTarget = packageSelect.value;
    const label = button.querySelector('.windows-package-label');
    label.dataset.i18n = packageSelect.value === 'windows' ? 'windowsSetup' : 'windowsZip';
    label.textContent = getText(label.dataset.i18n);
  });
  let downloading = false;
  for (const button of document.querySelectorAll('[data-download-target]')) {
    button.addEventListener('click', async () => {
      if (downloading) return;
      downloading = true;
      status.textContent = getText('downloadLoading');
      button.setAttribute('aria-busy', 'true');
      try {
        const source = preference === 'auto' ? await detected : preference;
        const release = await resolveDownload(source, button.dataset.downloadTarget);
        if ((preference === 'auto' ? await detected : preference) === source) {
          Object.assign(state, { source, version: release.version, links: release.links });
          render();
        }
        status.textContent = getText('downloadStarted');
        navigate(release.url);
      } catch {
        status.textContent = getText('downloadError');
      } finally {
        downloading = false;
        button.removeAttribute('aria-busy');
      }
    });
  }
  select.value = preference;
  select.addEventListener('change', () => {
    preference = select.value;
    storage.set('lanlink.downloadSource', preference);
    refresh();
  });
  render();
  refresh();
  return render;
}

export function websiteQrUrl(configured, current) {
  try {
    const url = new URL(configured || current);
    if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password || /^(localhost|127\.|0\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|\[::1\])/i.test(url.hostname) || !url.hostname.includes('.')) return null;
    url.search = '';
    url.hash = 'get';
    return url.href;
  } catch { return null; }
}

export function setupWebsiteQr(document, currentUrl) {
  const trigger = document.querySelector('.qr-trigger');
  const control = document.querySelector('.qr-control');
  const panel = document.querySelector('#website-qr');
  const image = panel.querySelector('.qr-image');
  let generatedUrl;
  function open() {
    const url = websiteQrUrl(document.querySelector('meta[name="website-url"]')?.content, currentUrl);
    if (url && generatedUrl !== url) {
      const code = qrcode(0, 'M');
      code.addData(url);
      code.make();
      image.innerHTML = code.createSvgTag(4, 16);
      generatedUrl = url;
    }
    image.hidden = !url;
    panel.querySelector('.qr-unavailable').hidden = Boolean(url);
    panel.hidden = false;
    trigger.setAttribute('aria-expanded', 'true');
  }
  function close() {
    panel.hidden = true;
    trigger.setAttribute('aria-expanded', 'false');
  }
  control.addEventListener('mouseenter', open);
  control.addEventListener('mouseleave', () => { if (!control.contains(document.activeElement)) close(); });
  control.addEventListener('focusin', open);
  control.addEventListener('focusout', event => { if (!control.contains(event.relatedTarget)) close(); });
  trigger.addEventListener('click', open);
  control.addEventListener('keydown', event => { if (event.key === 'Escape') close(); });
}
