// 传奇 1.76 单机网页版的服务端：发页面和素材、登录、存档。零依赖，Node 18+。
//
// 环境变量：
//   PORT=18176  PUBLIC_DIR=../public  DATA_DIR=./data
//   MIR_PASSWORD_HASH=scrypt$<salt hex>$<hash hex>   （用 `node server.mjs hash <密码>` 生成）
//   MIR_SESSION_SECRET=<随机串>
//
// 除登录页本身（index.html / style.css / app.js）外，素材和存档都要登录。
import { createServer } from 'node:http';
import { createReadStream, promises as fs } from 'node:fs';
import { createHmac, randomBytes, scryptSync, timingSafeEqual } from 'node:crypto';
import { createGzip } from 'node:zlib';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));

if (process.argv[2] === 'hash') {
  const salt = randomBytes(16);
  const hash = scryptSync(process.argv[3] ?? '', salt, 32);
  console.log(`scrypt$${salt.toString('hex')}$${hash.toString('hex')}`);
  process.exit(0);
}

const PORT = Number(process.env.PORT ?? 18176);
const PUBLIC_DIR = path.resolve(process.env.PUBLIC_DIR ?? path.join(here, '../public'));
const DATA_DIR = path.resolve(process.env.DATA_DIR ?? path.join(here, 'data'));
const PASSWORD_HASH = process.env.MIR_PASSWORD_HASH ?? '';
const SECRET = process.env.MIR_SESSION_SECRET ?? '';
if (!PASSWORD_HASH.startsWith('scrypt$') || SECRET.length < 16) {
  console.error('缺少 MIR_PASSWORD_HASH 或 MIR_SESSION_SECRET');
  process.exit(1);
}

const PUBLIC_FILES = new Set(['/', '/index.html', '/style.css', '/app.js', '/app.js.map', '/favicon.ico']);
const SESSION_DAYS = 90;
const MAX_SAVE = 2 * 1024 * 1024;

const TYPES = {
  '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.map': 'application/json', '.json': 'application/json', '.png': 'image/png', '.webp': 'image/webp',
  '.bin': 'application/octet-stream', '.wav': 'audio/wav', '.ico': 'image/x-icon',
};
const COMPRESSIBLE = new Set(['.html', '.css', '.js', '.map', '.json', '.bin']);

// ---------------- 登录 ----------------

function checkPassword(pw) {
  const [, saltHex, hashHex] = PASSWORD_HASH.split('$');
  const want = Buffer.from(hashHex, 'hex');
  const got = scryptSync(String(pw), Buffer.from(saltHex, 'hex'), want.length);
  return timingSafeEqual(want, got);
}

function sign(payload) {
  return createHmac('sha256', SECRET).update(payload).digest('base64url');
}

function makeToken() {
  const exp = Date.now() + SESSION_DAYS * 86400_000;
  const payload = `u1.${exp}`;
  return `${payload}.${sign(payload)}`;
}

function validToken(tok) {
  if (!tok) return false;
  const i = tok.lastIndexOf('.');
  const payload = tok.slice(0, i);
  const sig = tok.slice(i + 1);
  const want = sign(payload);
  if (sig.length !== want.length || !timingSafeEqual(Buffer.from(sig), Buffer.from(want))) return false;
  const exp = Number(payload.split('.')[1]);
  return exp > Date.now();
}

function cookie(req, name) {
  for (const part of (req.headers.cookie ?? '').split(';')) {
    const [k, ...v] = part.trim().split('=');
    if (k === name) return v.join('=');
  }
  return '';
}

const authed = (req) => validToken(cookie(req, 'mir_sess'));

// 登录失败限流：每个 IP 10 分钟内最多 10 次
const fails = new Map();
function clientIp(req) {
  return String(req.headers['x-forwarded-for'] ?? req.socket.remoteAddress ?? '').split(',')[0].trim();
}
function limited(ip) {
  const now = Date.now();
  const list = (fails.get(ip) ?? []).filter((t) => now - t < 600_000);
  fails.set(ip, list);
  return list.length >= 10;
}

// ---------------- 工具 ----------------

function send(res, code, body, headers = {}) {
  res.writeHead(code, { 'content-type': 'application/json', 'cache-control': 'no-store', ...headers });
  res.end(typeof body === 'string' ? body : JSON.stringify(body));
}

async function readBody(req, limit) {
  const chunks = [];
  let n = 0;
  for await (const c of req) {
    n += c.length;
    if (n > limit) throw Object.assign(new Error('too large'), { code: 413 });
    chunks.push(c);
  }
  return Buffer.concat(chunks);
}

// ---------------- 存档 ----------------

const saveFile = () => path.join(DATA_DIR, 'save.json');

async function writeSave(buf) {
  await fs.mkdir(path.join(DATA_DIR, 'backup'), { recursive: true });
  const tmp = `${saveFile()}.tmp`;
  await fs.writeFile(tmp, buf);
  await fs.rename(tmp, saveFile());
  // 每天留一份备份，保留 30 天
  const day = new Date().toISOString().slice(0, 10);
  const daily = path.join(DATA_DIR, 'backup', `save-${day}.json`);
  await fs.copyFile(saveFile(), daily);
  const all = (await fs.readdir(path.join(DATA_DIR, 'backup'))).sort();
  for (const f of all.slice(0, Math.max(0, all.length - 30))) await fs.rm(path.join(DATA_DIR, 'backup', f));
}

// ---------------- 静态文件 ----------------

async function serveFile(req, res, urlPath) {
  const rel = urlPath === '/' ? '/index.html' : urlPath;
  const file = path.join(PUBLIC_DIR, path.normalize(decodeURIComponent(rel)).replace(/^(\.\.[/\\])+/, ''));
  if (!file.startsWith(PUBLIC_DIR + path.sep)) return send(res, 403, { error: 'forbidden' });
  let st;
  try {
    st = await fs.stat(file);
    if (!st.isFile()) throw new Error('not file');
  } catch {
    return send(res, 404, { error: 'not found' });
  }
  const ext = path.extname(file).toLowerCase();
  const etag = `"${st.size.toString(36)}-${Math.floor(st.mtimeMs).toString(36)}"`;
  const isAsset = rel.startsWith('/assets/');
  const headers = {
    'content-type': TYPES[ext] ?? 'application/octet-stream',
    etag,
    // 素材：一周内直接用缓存；页面和代码：每次问一下有没有更新
    'cache-control': isAsset ? 'private, max-age=604800' : 'private, no-cache',
    'x-content-type-options': 'nosniff',
  };
  if (req.headers['if-none-match'] === etag) {
    res.writeHead(304, headers);
    return res.end();
  }
  const gzip = COMPRESSIBLE.has(ext) && /\bgzip\b/.test(req.headers['accept-encoding'] ?? '') && st.size > 1024;
  if (gzip) {
    headers['content-encoding'] = 'gzip';
    headers.vary = 'accept-encoding';
  } else headers['content-length'] = st.size;
  res.writeHead(200, headers);
  if (req.method === 'HEAD') return res.end();
  const stream = createReadStream(file);
  (gzip ? stream.pipe(createGzip({ level: 6 })) : stream).pipe(res);
}

// ---------------- 路由 ----------------

const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url ?? '/', 'http://x');
    const p = url.pathname;
    if (p === '/api/login' && req.method === 'POST') {
      const ip = clientIp(req);
      if (limited(ip)) return send(res, 429, { error: 'too many attempts' });
      const body = JSON.parse((await readBody(req, 4096)).toString() || '{}');
      if (!checkPassword(body.password ?? '')) {
        fails.get(ip).push(Date.now());
        return send(res, 401, { error: 'bad password' });
      }
      const secure = (req.headers['x-forwarded-proto'] ?? '') === 'https' ? '; Secure' : '';
      return send(res, 200, { ok: true }, {
        'set-cookie': `mir_sess=${makeToken()}; HttpOnly; SameSite=Lax; Path=/; Max-Age=${SESSION_DAYS * 86400}${secure}`,
      });
    }
    if (p === '/healthz') return send(res, 200, { ok: true });
    if (PUBLIC_FILES.has(p) && (req.method === 'GET' || req.method === 'HEAD')) return serveFile(req, res, p);
    if (!authed(req)) return send(res, 401, { error: 'login required' });
    if (p === '/api/me') return send(res, 200, { ok: true });
    if (p === '/api/save') {
      if (req.method === 'GET') {
        try {
          return send(res, 200, await fs.readFile(saveFile(), 'utf8'));
        } catch {
          return send(res, 404, { error: 'no save' });
        }
      }
      if (req.method === 'PUT') {
        const buf = await readBody(req, MAX_SAVE);
        const data = JSON.parse(buf.toString());
        if (typeof data !== 'object' || !data.player) return send(res, 400, { error: 'bad save' });
        await writeSave(Buffer.from(JSON.stringify(data)));
        return send(res, 200, { ok: true });
      }
      return send(res, 405, { error: 'method' });
    }
    if (req.method === 'GET' || req.method === 'HEAD') return serveFile(req, res, p);
    return send(res, 405, { error: 'method' });
  } catch (e) {
    const code = e.code === 413 ? 413 : e instanceof SyntaxError ? 400 : 500;
    if (code === 500) console.error(e);
    if (!res.headersSent) send(res, code, { error: String(e.message ?? e) });
    else res.destroy();
  }
});

server.listen(PORT, () => console.log(`mir176 listening on :${PORT}, public=${PUBLIC_DIR}, data=${DATA_DIR}`));
