const $ = id => document.getElementById(id);
const desktop = !!window.lanlink;
let token = ''; let pairingTimer;
if (!desktop) {
  token = location.hash.slice(1); if (token) sessionStorage.setItem('lanlink-session', token); else token = sessionStorage.getItem('lanlink-session') || '';
  history.replaceState(null, '', '/');
}
let state, initial = true, remoteId = null, viewing = false, control = false, noticeTimer, polling = false;
async function api(route, data) {
  if (desktop) return window.lanlink[route](data ?? {});
  const r = await fetch('/api/' + route, { method: data === undefined ? 'GET' : 'POST', headers: { 'X-LanLink-Token': token, 'Content-Type': 'application/json' }, body: data === undefined ? undefined : JSON.stringify(data) });
  const value = await r.json(); if (!r.ok) throw new Error(value.error); return value;
}
function notice(s) { $('notice').textContent = s; clearTimeout(noticeTimer); noticeTimer = setTimeout(() => $('notice').textContent = '', 9000); }
function action(id, fn) { $(id).onclick = async () => { $(id).disabled = true; try { await fn(); await refresh(); } catch (e) { notice(e.message); } finally { $(id).disabled = false; } }; }
function button(text, fn, cls = '') { const b = document.createElement('button'); b.textContent = text; b.className = cls; b.onclick = async () => { try { await fn(); } catch (e) { notice(e.message); } }; return b; }
let deviceSignature = '';
async function refresh() {
  if (polling) return; polling = true;
  try {
    state = await api('state'); $('localBadge').textContent = '● ' + state.name + (state.capabilities.simulated ? ' · 模拟模式' : ''); $('layoutName').textContent = state.name; $('account').textContent = state.groupId; $('peerCount').textContent = state.peers.length; $('join').textContent = state.groupEstablished ? '解散当前组并加入其他组' : '加入其他组';
    const sig = JSON.stringify([state.peers, state.active]);
    if (sig !== deviceSignature) {
      deviceSignature = sig; $('deviceList').replaceChildren();
      if (!state.peers.length) { const empty = document.createElement('div'); empty.className = 'empty'; const title = document.createElement('strong'); title.textContent = '等待你的下一台设备'; empty.append(title, document.createTextNode('在同一局域网中启动客户端，并输入另一台设备显示的八位配对码。')); $('deviceList').append(empty); }
      for (const p of state.peers) {
        const card = document.createElement('div'); card.className = 'device' + (state.active === p.id ? ' active' : '');
        const icon = document.createElement('div'); icon.className = 'icon'; icon.textContent = p.platform === 'android' ? '▯' : '▱';
        const name = document.createElement('h3'); name.textContent = p.name || '已配对设备'; const detail = document.createElement('small'); detail.textContent = (p.platform || '') + ' · ' + p.address;
        const actions = document.createElement('div'); actions.className = 'actions';
        actions.append(button('查看屏幕', () => openRemote(p)), button('切换键鼠', () => api('control', { id: p.id, capture: true }), 'secondary'));
        if (['win32', 'darwin'].includes(p.platform)) actions.append(button('关机', async () => { if (confirm('请求 ' + p.name + ' 在 30 秒后关机？目标端可取消，未保存的工作可能阻止关机。')) { await api('shutdown', { id: p.id }); notice('已发送关机请求'); } }, 'danger'), button('取消关机', () => api('cancel-shutdown', { id: p.id }), 'secondary'));
        card.append(icon, name, detail, actions); $('deviceList').append(card);
      }
      for (const edge of ['top', 'left', 'right', 'bottom']) {
        const el = $('edge-' + edge), selected = initial ? state.edges[edge] || '' : el.value;
        el.replaceChildren(new Option(({ top: '上方', left: '左方', right: '右方', bottom: '下方' })[edge] + ' · 不切换', ''));
        for (const p of state.peers) el.add(new Option(p.name || p.id, p.id));
        if (selected && !state.peers.some(p => p.id === selected)) el.add(new Option('已设置设备（离线）', selected)); el.value = selected;
      }
    }
    if (initial) { $('deviceName').value = state.name; $('dwell').value = state.dwell; $('master').checked = state.master; for (const p of Object.keys(state.permissions)) $('perm-' + p).checked = state.permissions[p]; initial = false; }
    $('shutdownWarning').hidden = !state.shutdownAt; $('countdown').textContent = state.shutdownAt ? Math.max(0, Math.ceil((state.shutdownAt - Date.now()) / 1000)) + ' 秒后执行' : '';
    $('clipboardState').textContent = state.clipboard ? `${state.clipboard.files} 个文件 · ${state.clipboard.textLength} 个文本字符 · 切换后可粘贴` : '复制文本或文件后，会自动通知已配对设备';
    $('transfer').textContent = state.transfer ? `正在接收 ${state.transfer.name} · ${Math.round(state.transfer.received / Math.max(1, state.transfer.total) * 100)}%` : '';
    $('logs').replaceChildren(...state.logs.map(l => { const li = document.createElement('li'); li.textContent = new Date(l.time).toLocaleTimeString() + '  ' + l.message; return li; }));
    if (remoteId && state.active !== remoteId) control = false;
    $('remoteStatus').textContent = control ? '正在控制 · 点击画面后输入' : '查看模式';
  } catch (e) { notice(e.message); } finally { polling = false; }
}
action('saveLayout', async () => { const edges = {}; for (const k of ['left', 'right', 'top', 'bottom']) edges[k] = $('edge-' + k).value; await api('settings', { edges, dwell: Number($('dwell').value), master: $('master').checked }); notice('屏幕布局已保存'); });
action('saveSettings', async () => { const permissions = {}; for (const k of ['input', 'screen', 'clipboard', 'shutdown']) permissions[k] = $('perm-' + k).checked; await api('settings', { name: $('deviceName').value, permissions }); notice('本机设置已保存'); });
action('showToken', async () => { if (!$('ownToken').hidden) { $('ownToken').hidden = true; $('ownToken').value = ''; $('qrPanel').hidden = true; $('pairQr').removeAttribute('src'); clearInterval(pairingTimer); await api('pairing-stop', {}); return; } const pairing = await api('pairing', { reveal: true }); $('ownToken').value = pairing.code; $('pairQr').src = pairing.qr; $('qrPanel').hidden = false; $('ownToken').hidden = false; clearInterval(pairingTimer); pairingTimer = setInterval(async () => { if ($('ownToken').hidden) { clearInterval(pairingTimer); return; } try { const p = await api('pairing', { reveal: true }); $('ownToken').value = p.code + ' · ' + Math.ceil(p.remainingMs / 1000) + ' 秒后刷新'; $('pairQr').src = p.qr; } catch { $('ownToken').value = ''; $('pairQr').removeAttribute('src'); } }, 1000); });
action('join', async () => { const current = await api('state'); if (!confirm(current.groupEstablished ? '解散原组后所有成员都将退出；离线成员收到通知后退出。验证新配对码成功后才解散，继续？' : '加入对方的组？')) return; await api('join', { code: $('joinToken').value.trim(), expectedGroup: current.groupId, dissolveCurrentGroup: current.groupEstablished }); $('joinToken').value = ''; $('ownToken').value = ''; $('ownToken').hidden = true; $('qrPanel').hidden = true; $('pairQr').removeAttribute('src'); notice('已加入组，正在发现设备'); });
action('connect', () => api('connect', { host: $('host').value.trim(), port: Number($('port').value) }));
action('returnLocal', () => api('release', {})); action('cancelLocal', () => api('cancel-shutdown', {}));
action('share', async () => { await api('clipboard', { text: $('shareText').value }); notice('文本已共享'); });
action('receive', async () => { await api('receive', {}); notice('内容已放入本机剪贴板，可以粘贴'); });
async function openRemote(p) { remoteId = p.id; control = false; viewing = true; $('remoteName').textContent = p.name; $('screen').removeAttribute('src'); $('remote').showModal(); frameLoop(); }
async function frameLoop() { while (viewing && remoteId) { const id = remoteId; try { const frame = await api('screen', { id }); if (id === remoteId && viewing) $('screen').src = 'data:image/jpeg;base64,' + frame.data; } catch (e) { notice(e.message); await new Promise(r => setTimeout(r, 1500)); } await new Promise(r => setTimeout(r, 150)); } }
async function closeRemote() { viewing = false; remoteId = null; control = false; $('remote').close(); await api('release', {}); }
action('closeRemote', closeRemote); $('remote').addEventListener('cancel', e => { e.preventDefault(); closeRemote().catch(e => notice(e.message)); });
action('startControl', async () => { await api('control', { id: remoteId, capture: false }); control = true; $('screen').focus(); });
action('paste', () => api('paste', { id: remoteId })); action('sendText', async () => { await api('text', { id: remoteId, text: $('remoteText').value }); $('remoteText').value = ''; });
let inputChain = Promise.resolve(), move = null, moveBusy = false;
function sendInput(input) { if (!control || !remoteId) return Promise.resolve(); const id = remoteId; inputChain = inputChain.then(() => api('input', { id, input })).catch(e => { control = false; notice(e.message); }); return inputChain; }
function coordinates(e) { const r = $('screen').getBoundingClientRect(); return { kind: 'move', x: Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)), y: Math.max(0, Math.min(1, (e.clientY - r.top) / r.height)) }; }
$('screen').onpointermove = async e => { if (!control) return; move = coordinates(e); if (moveBusy) return; moveBusy = true; try { while (move && control) { const m = move; move = null; await sendInput(m); } } finally { moveBusy = false; } };
$('screen').onpointerdown = async e => { if (!control) return; e.preventDefault(); $('screen').focus(); $('screen').setPointerCapture(e.pointerId); await sendInput(coordinates(e)); await sendInput({ kind: 'button', button: e.button, down: true }); };
$('screen').onpointerup = e => { if (control) { e.preventDefault(); sendInput({ kind: 'button', button: e.button, down: false }); } };
$('screen').oncontextmenu = e => e.preventDefault();
$('screen').addEventListener('wheel', e => { if (control) { e.preventDefault(); sendInput({ kind: 'wheel', delta: Math.sign(e.deltaY) * -120 }); } }, { passive: false });
for (const name of ['keydown', 'keyup']) $('screen').addEventListener(name, e => { if (!control || e.isComposing) return; e.preventDefault(); if (e.code === 'KeyV' && (e.ctrlKey || e.metaKey)) { if (name === 'keydown' && !e.repeat) api('paste', { id: remoteId }).catch(e => notice(e.message)); return; } sendInput({ kind: 'key', code: e.code, down: name === 'keydown' }); });
window.addEventListener('blur', () => { if (control) { control = false; api('release', {}).catch(() => {}); } });
await refresh(); setInterval(refresh, 1500);
