#!/usr/bin/env node
/**
 * 直播间「送礼物」接口压测 —— POST /api/live/{roomId}/gift?value=N
 *
 * <h2>它和点赞是两种完全不同的写入</h2>
 *   · 点赞：一次 Lua，累加一个**字符串计数器**（INCRBY），一个 key，O(1)
 *   · 送礼：一次 {@code ZINCRBY} 累加**有序集合**里某个成员的分数，一个成员，O(logN)
 * 而且送礼在业务上是"低频高价值"：一个人不会一秒送十次，但每次都要准确。
 * 所以拿它和点赞的吞吐对比，能看出"字符串累加"和"有序集合累加"的单价差。
 *
 * <h2>服务端这条路径干了什么（见 LiveInteractiveController#gift）</h2>
 *   ① 房间存在性校验 —— <b>每个请求都真的 selectById 查一次库</b>（还没做缓存）
 *   ② 校验收礼者必须是本房间主播（不传则由房间推导）
 *   ③ 校验 value 是有限正数、不超过上限
 *   ④ ZINCRBY gift:rank:{roomId}  <主播 userId>  value
 *   ⑤ 打一行 INFO 日志：{@code log.info("用户 {} 在直播间 {} 送出价值 {} 的礼物…")}
 *
 * <p><b>⚠️ 第 ⑤ 条会严重拖累压测结果。</b>控制台写入是全局加锁的串行操作，
 * 每秒几千次日志会把吞吐钉死在一个跟服务端能力无关的数字上。
 * 压测前务必用启动参数把它压掉：
 * <pre>
 *   --logging.level.com.live.controller.LiveInteractiveController=warn
 * </pre>
 *
 * <h2>怎么验证数据没丢</h2>
 * 礼物榜就是 Redis 里那个有序集合的倒序。所以：
 *   跑前读一次 {@code GET /api/live/{roomId}/gift/rank?top=100} 把所有分数加起来，
 *   跑完再读一次，增量必须精确等于「成功请求数 × value」。
 * 对不上就说明 ZINCRBY 丢过、或者有请求其实失败了却被当成成功。
 *
 * <h2>用法</h2>
 *     # 小试
 *     node bench-gift.js --room 204 --users 50 --per-user 20 --concurrency 50
 *
 *     # 正式
 *     node bench-gift.js --room 204 --users 1000 --per-user 20 --concurrency 200
 *
 * 前置：账号池 .like-tokens.json（由 bench-like.py 造号时生成）
 *
 * ⚠️ 副作用：房间的礼物榜分数会被打高（Redis gift:rank:{roomId}），不会自动清理。
 *    想清掉：redis-cli DEL gift:rank:204
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

/** 服务端单次礼物价值上限（LiveInteractiveController.MAX_GIFT_VALUE） */
const MAX_GIFT_VALUE = 1_000_000;

function parseArgs(argv) {
  const args = { room: 204, users: 1000, perUser: 20, concurrency: 200, value: 100, fresh: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => {
      const v = argv[++i];
      if (v === undefined) {
        console.error('[致命] 参数 %s 缺少值', a);
        process.exit(2);
      }
      return v;
    };
    if (a === '--room') args.room = Number(next());
    else if (a === '--users') args.users = Number(next());
    else if (a === '--per-user') args.perUser = Number(next());
    else if (a === '--concurrency') args.concurrency = Number(next());
    else if (a === '--value') args.value = Number(next());
    else if (a === '--fresh') args.fresh = true;
    else {
      console.error('[致命] 未知参数：%s', a);
      process.exit(2);
    }
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

function request(agent, method, urlPath, token) {
  return new Promise((resolve, reject) => {
    const headers = {};
    if (token) headers['Authorization'] = 'Bearer ' + token;
    const req = http.request(
      { host: HOST, port: PORT, path: urlPath, method, agent, headers },
      (res) => {
        res.setEncoding('utf8');
        let raw = '';
        res.on('data', (c) => {
          raw += c;
        });
        res.on('end', () => {
          let body;
          try {
            body = JSON.parse(raw);
          } catch (e) {
            body = { raw: raw.slice(0, 200) };
          }
          resolve({ status: res.statusCode, body });
        });
      }
    );
    req.on('error', reject);
    req.end();
  });
}

/**
 * 读这个房间当前的礼物总价值。
 *
 * <p>礼物榜是一个 ZSet（member = 收礼人 userId，score = 累计价值），
 * 所以把所有成员的分数加起来就是这个房间送出去的总价值。
 * top 取 100（服务端 GIFT_TOP_MAX 的上限），一个房间正常情况下只有主播一个成员。
 */
async function readTotalGift(agent, room) {
  try {
    const r = await request(agent, 'GET', '/api/live/' + room + '/gift/rank?top=100');
    if (r.status === 200 && r.body && r.body.code === 0 && Array.isArray(r.body.data)) {
      let sum = 0;
      for (const e of r.body.data) sum += Number(e.value || 0);
      return sum;
    }
  } catch (e) {
    console.log('[warn] 读礼物榜失败：%s', e.message);
  }
  return null;
}

/** 发一个 JSON POST，resolve 成解析后的响应体 */
function postJson(agent, urlPath, body) {
  return new Promise((resolve, reject) => {
    const payload = Buffer.from(JSON.stringify(body), 'utf8');
    const req = http.request(
      {
        host: HOST,
        port: PORT,
        path: urlPath,
        method: 'POST',
        agent,
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': String(payload.length),
        },
      },
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
 * 走 Mock 短信登录批量造新账号，返回它们的 Token。
 *
 * <p>为什么需要它：账号池里的 Token 是**签发后 2 小时过期**的。压测隔了半天再跑，
 * 就会发现全部返回 401「Token无效或已过期」—— 那不是接口坏了，是账号过期了。
 * 加 --fresh 就会现场重新登录一批，不依赖账号池里的旧 Token。
 *
 * <p>依赖 dev profile 的 app.sms.mock-enabled + expose-mock-code（验证码在响应里回显）。
 */
async function loginMany(count, concurrency) {
  const agent = new http.Agent({ keepAlive: true, maxSockets: concurrency });
  const phones = new Set();
  while (phones.size < count) {
    phones.add('139' + String(Math.floor(Math.random() * 1e8)).padStart(8, '0'));
  }
  const list = Array.from(phones);
  const tokens = new Array(count);
  let idx = 0;

  async function worker() {
    for (;;) {
      const i = idx++;
      if (i >= count) return;
      const phone = list[i];
      const sent = await postJson(agent, '/api/auth/sms/send', { phone });
      const code = sent && sent.data && sent.data.mockCode;
      if (!code) {
        throw new Error('没拿到 mockCode（需要 dev profile + app.sms.expose-mock-code=true）');
      }
      const logged = await postJson(agent, '/api/auth/sms/login', { phone, code });
      const token = logged && logged.data && logged.data.token;
      if (!token) {
        throw new Error('登录未返回 token：' + JSON.stringify(logged).slice(0, 120));
      }
      tokens[i] = token;
    }
  }

  const workers = [];
  for (let w = 0; w < Math.min(concurrency, count); w++) workers.push(worker());
  try {
    await Promise.all(workers);
  } finally {
    agent.destroy();
  }
  return tokens;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));

  if (!(args.value > 0) || args.value > MAX_GIFT_VALUE) {
    console.log('[致命] value 必须在 0~%d 之间（服务端 MAX_GIFT_VALUE）', MAX_GIFT_VALUE);
    return 2;
  }

  let tokens;
  if (args.fresh) {
    console.log('正在通过 Mock 短信登录现场造 %d 个新账号…', args.users);
    try {
      tokens = await loginMany(args.users, 20);
    } catch (e) {
      console.log('[致命] 造号失败：%s', e.message);
      return 2;
    }
    console.log('造号完成，%d 个账号就绪', tokens.length);
  } else {
    if (!fs.existsSync(TOKEN_CACHE)) {
      console.log('[致命] 找不到 %s，请先跑一次 bench-like.py 造号，或加 --fresh', TOKEN_CACHE);
      return 2;
    }
    const allTokens = JSON.parse(fs.readFileSync(TOKEN_CACHE, 'utf8'));
    tokens = allTokens.slice(0, args.users);
    if (tokens.length === 0) {
      console.log('[致命] 账号池是空的');
      return 2;
    }
  }

  const total = tokens.length * args.perUser;

  console.log('直播间送礼压测 —— %s  房间=%d', BASE, args.room);
  console.log(
    '账号=%d  每人=%d 次  总请求=%d  并发=%d  每次价值=%d',
    tokens.length,
    args.perUser,
    total,
    args.concurrency,
    args.value
  );
  console.log('  ⚠️ 若没压掉 LiveInteractiveController 的 INFO 日志，测出来的是控制台写入能力，不是接口能力');

  const agent = new http.Agent({
    keepAlive: true,
    maxSockets: Math.min(args.concurrency, total) + 1,
    maxFreeSockets: Math.min(args.concurrency, total) + 1,
    scheduling: 'fifo',
  });

  const before = await readTotalGift(agent, args.room);
  console.log('跑前礼物总价值：%s', before);

  // 轮转排队：第 1 轮每人送 1 次、第 2 轮每人送 1 次……避免同一账号连发
  const jobs = [];
  for (let r = 0; r < args.perUser; r++) {
    for (let i = 0; i < tokens.length; i++) jobs.push(tokens[i]);
  }
  const n = jobs.length;

  const lat = [];
  const errors = [];
  let cursor = 0;
  let retries = 0;
  const urlPath = '/api/live/' + args.room + '/gift?value=' + args.value;

  let fire;
  const gate = new Promise((res) => {
    fire = res;
  });

  async function worker() {
    await gate;
    for (;;) {
      const i = cursor++;
      if (i >= n) return;
      const token = jobs[i];
      const t0 = performance.now();
      let r;
      try {
        r = await request(agent, 'POST', urlPath, token);
      } catch (e) {
        // 长连接被服务端 keep-alive 超时关掉时，客户端会拿到一条已死的空闲连接，
        // 此时请求根本没到服务端，重发一次即可（不重发会把这项计成真实失败）
        retries++;
        try {
          r = await request(agent, 'POST', urlPath, token);
        } catch (e2) {
          errors.push({ status: 0, msg: e2.code || e2.message });
          continue;
        }
      }
      const dt = performance.now() - t0;
      const code = r.body ? r.body.code : undefined;
      const msg = String((r.body && r.body.msg) || '');
      if (r.status === 200 && code === 0) {
        lat.push(dt);
      } else {
        errors.push({ status: r.status, code, msg: msg.slice(0, 80) });
      }
    }
  }

  const workers = [];
  for (let i = 0; i < Math.min(args.concurrency, n); i++) workers.push(worker());
  await new Promise((res) => setImmediate(res));
  const t0 = performance.now();
  fire();
  await Promise.all(workers);
  const elapsed = (performance.now() - t0) / 1000;

  lat.sort((a, b) => a - b);
  const qps = elapsed > 0 ? lat.length / elapsed : 0;

  console.log('\n' + '='.repeat(62));
  console.log('结果');
  console.log('='.repeat(62));
  console.log('总请求              : %d', n);
  console.log('成功                : %d', lat.length);
  console.log('其他错误            : %d', errors.length);
  if (errors.length > 0) {
    console.log('错误样例            : %o', errors[0]);
  }
  if (retries > 0) {
    console.log('长连接重试          : %d（keep-alive 空闲连接被关闭，已自动重发）', retries);
  }
  console.log('总耗时              : %s s', elapsed.toFixed(2));
  console.log('实测 QPS            : %s 请求/秒', qps.toFixed(0));
  if (lat.length > 0) {
    console.log(
      '延迟 P50/P95/P99    : %s / %s / %s ms',
      pct(lat, 50).toFixed(2),
      pct(lat, 95).toFixed(2),
      pct(lat, 99).toFixed(2)
    );
  }

  // ── 校验：礼物榜总分增量必须 = 成功请求数 × value ──
  await new Promise((res) => setTimeout(res, 300));
  const after = await readTotalGift(agent, args.room);
  console.log('-'.repeat(62));
  console.log('校验');
  console.log('  跑前礼物总价值    : %s', before);
  console.log('  跑后礼物总价值    : %s', after);
  if (before !== null && after !== null) {
    const got = after - before;
    const expect = lat.length * args.value;
    console.log('  实际增量          : %d（期望 %d）', got, expect);
    if (got === expect) {
      console.log('  ✅ 礼物榜增量与成功请求数完全一致，有序集合累加零丢失');
    } else {
      console.log('  ⚠️ 对不上：可能有人同时在送礼物，或长连接重试导致重复计数');
    }
  }

  console.log('\n' + '='.repeat(62));
  console.log('怎么读这个数：');
  console.log('  这条路径 = 1 次 MySQL 查库（房间存在性，未缓存）');
  console.log('           + 1 次 Redis ZINCRBY（有序集合累加）');
  console.log('  和点赞（1 次 Redis Lua，4 条命令，不查库）对比，');
  console.log('  差值就是「一次查库 + 有序集合写入 vs 字符串累加」的代价。');
  console.log('='.repeat(62));

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
