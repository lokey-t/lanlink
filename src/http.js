import http from 'node:http';
import { randomBytes } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { equal } from './wire.js';
import { createActions } from './actions.js';

export async function startUI(service, port = 45830) {
  const token = randomBytes(32).toString('hex');
  const invoke = createActions(service);
  const assets = { '/': ['index.html', 'text/html; charset=utf-8'], '/app.js': ['app.js', 'text/javascript; charset=utf-8'], '/style.css': ['style.css', 'text/css; charset=utf-8'] };
  let origin;
  const server = http.createServer(async (req, res) => {
    const headers = { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Content-Security-Policy': "default-src 'self'; img-src 'self' data: blob:; style-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'", 'Referrer-Policy': 'no-referrer' };
    const respond = (code, data) => { res.writeHead(code, { ...headers, 'Content-Type': 'application/json; charset=utf-8' }); res.end(JSON.stringify(data)); };
    try {
      if (req.headers.host !== new URL(origin).host || (req.headers.origin && req.headers.origin !== origin)) return respond(403, { error: '来源不被允许' });
      const url = new URL(req.url, origin);
      if (req.method === 'GET' && assets[url.pathname]) { const [name, type] = assets[url.pathname]; const body = await readFile(fileURLToPath(new URL('../web/' + name, import.meta.url))); res.writeHead(200, { ...headers, 'Content-Type': type }); res.end(body); return; }
      if (!equal(req.headers['x-lanlink-token'], token)) return respond(401, { error: '请从启动窗口提供的完整地址打开控制台' });
      if (req.method === 'GET' && url.pathname === '/api/state') return respond(200, service.state());
      if (req.method !== 'POST') return respond(404, { error: '接口不存在' });
      let size = 0, body = ''; for await (const chunk of req) { size += chunk.length; if (size > 2 * 1024 * 1024) { respond(413, { error: '请求过大' }); return; } body += chunk; }
      const a = JSON.parse(body || '{}');
      const result = await invoke(url.pathname.slice('/api/'.length), a);
      respond(200, result);
    } catch (e) { if (!res.headersSent) respond(400, { error: e.message }); }
  });
  server.requestTimeout = 150000; server.headersTimeout = 10000;
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolve); });
  origin = 'http://127.0.0.1:' + server.address().port;
  return { server, token, url: origin + '/#' + token };
}
