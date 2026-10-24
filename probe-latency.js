#!/usr/bin/env node
/**
 * 单请求延迟探针 —— 零并发，逐条打，测「一次请求在服务端到底花多久」。
 *
 * <h2>为什么需要它</h2>
 * 压测只知道「饱和吞吐」，不知道时间花在哪一段。同样是 6ms，可能是：
 *   · 业务代码慢（查库、Redis）
 *   · 框架慢（鉴权、JSON、拦截器）
 *   · 根本不是应用的事（TCP 回环、杀毒软件、Windows 网络栈）
 * 这三个原因的修法完全不同，所以先把它劈开。
 *
 * <h2>劈法：找几组「只差一个变量」的端点，逐个测</h2>
 *
 *   ① GET /__probe_nothing__         → 404，连业务代码都不进。测的是「Tomcat + HTTP 本身」有多快
 *   ② GET /api/live/{room}/like      → 只读一次 Redis，不查库、游客可读
 *   ③ GET /api/live/{room}/online    → 走一段 Redis Lua，不查库、游客可读
 *   ④ POST /api/live/{room}/like     → 不带 Token，被拦截器打回（401）。测鉴权入口开销
 *   ⑤ POST /api/live/{room}/enter    → 带 Token，只写一次 Redis，不查库
 *   ⑥ POST /api/live/{room}/like     → 带 Token，完整路径
 *
 * ⑤ 和 ② 都是「一次 Redis 往返」，唯一差别是「要不要鉴权」——
 * 它俩的差值就是鉴权链路的净开销。
 *
 * <h2>--login：用全新 Token 排除「滑动续期」的干扰</h2>
 * 服务端有滑动续期：Token 剩余有效期 ≤ jwt.refresh-threshold 时，
 * 每个请求都会**重新签一个 Token** 并写一行日志。
 * 账号池里的 Token 都放很久了，会 100% 触发续期，把「每小时一次」放大成「每次请求一次」。
 * 加 --login 会先走一遍 Mock 短信登录拿一个满有效期的新 Token，此时不触发续期。
 *
 * 于是：同一个端点，--token-index 打一次、--login 打一次，两者差值就是续期的代价。
 *
 * <h2>用法</h2>
 *     # 不带 Token 的端点
 *     node probe-latency.js --path /api/live/204/like --count 100
 *
 *     # 带 Token 的端点（索引指向 .like-tokens.json 里的第几个账号）
 *     node probe-latency.js --path /api/live/204/like --method POST --count 100 --token-index 0
 *
 *     # 用全新 Token（不触发续期）
 *     node probe-latency.js --path /api/live/204/enter --method POST --count 100 --login
 *
 *     # 完全不存在的路径
 *     node probe-latency.js --path /__probe_nothing__ --count 100
 */

'use strict';

const http = require('http');
const fs = require('fs');
const { performance } = require('perf_hooks');

const BASE = (process.env.BASE || 'http://127.0.0.1:8080').replace(/\/+$/, '');
const _u = new URL(BASE);
const HOST = _u.hostname;
const PORT = _u.port || 80;
const TOKEN_CACHE = process.env.TOKEN_CACHE || '.like-tokens.json';

function parseArgs(argv) {
  const args = { path: null, method: 'GET', count: 100, tokenIndex: -1, delta: 1, login: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => argv[++i];
    if (a === '--path') args.path = next();
    else if (a === '--method') args.method = String(next()).toUpperCase();
    else if (a === '--count') args.count = Number(next());
    else if (a === '--token-index') args.tokenIndex = Number(next());
    else if (a === '--delta') args.delta = Number(next());
    else if (a === '--login') args.login = true;
    else {
      console.error('[致命] 未知参数：%s', a);
      process.exit(2);
    }
  }
  if (!args.path) {
    console.error('用法：node probe-latency.js --path /api/live/204/like [--method POST] [--count 100] [--token-index 0 | --login]');
    process.exit(2);
  }
  return args;
}

function pct(sorted, p) {
  if (sorted.length === 0) return 0;
  const k = ((sorted.length - 1) * p) / 100;
  const f = Math.floor(k);
  const c = Math.min(f + 1, sorted.length - 1);
  return f === c ? sorted[f] : sorted[f] + (sorted[c] - sorted[f]) * (k - f);
}

/** 一次性发 JSON 请求，resolve 成解析后的响应体 */
function postJson(agent, urlPath, body, token) {
  return new Promise((resolve, reject) => {
    const payload = Buffer.from(JSON.stringify(body), 'utf8');
    const headers = {
      'Content-Type': 'application/json',
      'Content-Length': String(payload.length),
    };
    if (token) headers['Authorization'] = 'Bearer ' + token;
    const req = http.request(
      { host: HOST, port: PORT, path: urlPath, method: 'POST', agent, headers },
      (res) => {
        res.setEncoding('utf8');
        let raw = '';
        res.on('data', (c) => {
          raw += c;
        });
        res.on('end', () => {
          try {
            resolve(JSON.parse(raw));
          } catch (e) {
            reject(new Error('非 JSON 响应：' + raw.slice(0, 120)));
          }
        });
      }
    );
    req.on('error', reject);
    req.end(payload);
  });
}

/**
 * 走一遍 Mock 短信登录，拿一个「满有效期」的新 Token。
 * 依赖 dev profile 的 app.sms.mock-enabled / expose-mock-code（验证码会在响应里回显）。
 */
async function freshToken() {
  const agent = new http.Agent({ keepAlive: true, maxSockets: 1 });
  const phone = '139' + String(Math.floor(Math.random() * 1e8)).padStart(8, '0');
  try {
    const sent = await postJson(agent, '/api/auth/sms/send', { phone });
    const code = sent && sent.data && sent.data.mockCode;
    if (!code) {
      throw new Error('响应里没有 mockCode（需要 dev profile + app.sms.expose-mock-code=true）');
    }
    const logged = await postJson(agent, '/api/auth/sms/login', { phone, code });
    const token = logged && logged.data && logged.data.token;
    if (!token) {
      throw new Error('登录未返回 token：' + JSON.stringify(logged).slice(0, 120));
    }
    return token;
  } finally {
    agent.destroy();
  }
}

function once(agent, args, token) {
  return new Promise((resolve, reject) => {
    const headers = {};
    let payload = null;
    if (args.method === 'POST') {
      payload = Buffer.from(JSON.stringify({ delta: args.delta }), 'utf8');
      headers['Content-Type'] = 'application/json';
      headers['Content-Length'] = String(payload.length);
    }
    if (token) headers['Authorization'] = 'Bearer ' + token;

    const req = http.request(
      { host: HOST, port: PORT, path: args.path, method: args.method, agent, headers },
      (res) => {
        res.setEncoding('utf8');
        let raw = '';
        res.on('data', (c) => {
          raw += c;
        });
        res.on('end', () => resolve({ status: res.statusCode, raw }));
      }
    );
    req.on('error', reject);
    req.end(payload);
  });
}

async function main() {
  const args = parseArgs(process.argv.slice(2));

  let token = null;
  let tokenLabel = '[无 Token]';
  if (args.login) {
    try {
      token = await freshToken();
      tokenLabel = '[新 Token，不会触发续期]';
    } catch (e) {
      console.error('[致命] 取新 Token 失败：%s', e.message);
      return 2;
    }
  } else if (args.tokenIndex >= 0) {
    if (!fs.existsSync(TOKEN_CACHE)) {
      console.error('[致命] 找不到 %s', TOKEN_CACHE);
      return 2;
    }
    const all = JSON.parse(fs.readFileSync(TOKEN_CACHE, 'utf8'));
    token = all[args.tokenIndex];
    if (!token) {
      console.error('[致命] token-index=%d 超出账号池范围（共 %d 个）', args.tokenIndex, all.length);
      return 2;
    }
    tokenLabel = '[旧 Token，可能触发续期]';
  }

  const agent = new http.Agent({ keepAlive: true, maxSockets: 1 });

  // 先打 5 发热身：把类加载、JDBC 连接、Redis 连接这些"只贵一次"的开销排除掉
  let warmStatus = null;
  for (let i = 0; i < 5; i++) {
    const r = await once(agent, args, token);
    warmStatus = r.status;
  }

  const lat = [];
  let lastRaw = '';
  for (let i = 0; i < args.count; i++) {
    const t0 = performance.now();
    const r = await once(agent, args, token);
    lat.push(performance.now() - t0);
    lastRaw = r.raw.slice(0, 120);
  }

  lat.sort((a, b) => a - b);
  const label = args.method + ' ' + args.path + '  ' + tokenLabel;

  console.log('');
  console.log(label);
  console.log('  HTTP 状态   : %s', warmStatus);
  console.log('  min / P50   : %s / %s ms', lat[0].toFixed(2), pct(lat, 50).toFixed(2));
  console.log('  P90 / P99   : %s / %s ms', pct(lat, 90).toFixed(2), pct(lat, 99).toFixed(2));
  console.log('  响应体样例  : %s', lastRaw.replace(/\s+/g, ' '));

  agent.destroy();
  return 0;
}

main().then(
  (code) => process.exit(code),
  (e) => {
    console.error('[崩溃]', e);
    process.exit(1);
  }
);
