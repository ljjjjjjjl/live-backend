#!/usr/bin/env node
/**
 * 直播间「人气点赞」压测 —— Node.js 版。
 *
 * <h2>为什么要有这个文件（bench-live-like.py 不够用）</h2>
 * 同一份压测先写了 Python 版，压出来稳定在 1000 请求/秒左右，但那个数字是假的：
 *   · Python 有 GIL，一个进程同一时刻只有一个线程能跑，200 个线程等于 200 个人抢一个收银台；
 *   · 压测脚本和被压的服务挤在同一台机器上，Python 忙的时候 Java 也在挨饿；
 *   · 于是 1000 条/秒里绝大部分时间不是在服务器上干活，而是在客户端排队。
 *
 * 反证一下就知道：1114 条/秒 ÷ 200 线程 = 每个请求 180ms，
 * 可服务端一个请求只做「一次 Redis Lua 累加 + 一次 selectById」，正常不到 1ms。
 * 差 180 倍，说明测量工具自己成了瓶颈。
 *
 * Node 是单进程 + 事件循环 + 非阻塞 socket：
 *   · 200 个并发连接在同一个线程里排队等着 IO，没有线程切换开销，也不占 GIL；
 *   · 一个 Node 进程发几千条/秒也不会吃满一个 CPU 核，不会跟 Java 抢资源。
 * 所以这个脚本压出来的数字，才是「服务端真实能扛多少」。
 *
 * <h2>服务端规则（照代码里的实现来，脚本按它设计）</h2>
 *   · POST /api/live/{roomId}/like   body {"delta":1}
 *   · 服务端每个请求都会做一次 selectById 校验房间存在（见 LiveInteractiveController），
 *     所以必须用真实存在的房间 ID，随便编一个会返回「直播间不存在」。
 *   · 限速：同一用户在同一房间每秒最多 20 次（Redis 键 live:like:rate:{room}:{user}，TTL 1 秒）。
 *     单账号打不出高 QPS，必须多账号。
 *   · delta 合法区间 1~50；本脚本固定 delta=1，让「请求数 == 人气增量」，便于跑完校验。
 *   · 返回 code=0 成功；code=1 且 msg 含「太快」表示被限速（业务失败，不是 500）。
 *
 * <h2>并发模型：固定数量的 worker 循环，而不是一次性甩出所有请求</h2>
 * 每个 worker 手头永远只有 1 个请求在飞，收到响应才发下一个。
 * 这样测出来的延迟是「一次请求真实花多久」，不含客户端自己排队的等待时间 ——
 * 和 Python 版（每线程一条长连接）的口径一致，两边数字可以直接比。
 *
 * 如果改成「一次性发出 20000 个请求、靠 agent 限流」，超出的请求会在 Node 内部排队，
 * 那段排队时间会被算进延迟里，P50 会虚高，跟上一版数字就没法比了。
 *
 * <h2>用法（和 Python 版一模一样）</h2>
 *     # 小规模试水
 *     node bench-live-like.js --room 204 --users 50 --per-user 20 --concurrency 50
 *
 *     # 正式：500 个账号，每人 20 次，并发 200
 *     node bench-live-like.js --room 204 --users 500 --per-user 20 --concurrency 200
 *
 * 前置：
 *     · 服务已启动（mvn spring-boot:run -Dspring-boot.run.profiles=dev）
 *     · 账号池 .like-tokens.json 已存在（由 bench-like.py 造号时生成，10000 条）
 *
 * ⚠️ 副作用：会把直播间人气（Redis live:like:{roomId}）打高，
 *    关播时才结算写回 live_room.like_count。想清零就在后台对该房间重新开播一次。
 */

'use strict';

const http = require('http');
const fs = require('fs');
const { performance } = require('perf_hooks');

// ── 配置 ────────────────────────────────────────────────────────────────
const BASE = (process.env.BASE || 'http://127.0.0.1:8080').replace(/\/+$/, '');
const _u = new URL(BASE);
const HOST = _u.hostname;
const PORT = _u.port || 80;
const TOKEN_CACHE = process.env.TOKEN_CACHE || '.like-tokens.json';

/** 进度条只在真终端里画：重定向到文件时不该混进一堆控制字符 */
const SHOW_PROGRESS = process.stdout.isTTY === true;

function parseArgs(argv) {
  const args = { room: 204, users: 500, perUser: 20, concurrency: 200, delta: 1, help: false };
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
    else if (a === '--delta') args.delta = Number(next());
    else if (a === '--help' || a === '-h') args.help = true;
    else {
      console.error('[致命] 未知参数：%s', a);
      process.exit(2);
    }
  }
  return args;
}

/**
 * 百分位（线性插值），和 Python 版 pct() 算法一致，保证两个脚本的 P50/P99 可直接对比。
 * @param {number[]} sorted 已升序排序的数组
 */
function pct(sorted, p) {
  if (sorted.length === 0) return 0;
  const k = ((sorted.length - 1) * p) / 100;
  const f = Math.floor(k);
  const c = Math.min(f + 1, sorted.length - 1);
  return f === c ? sorted[f] : sorted[f] + (sorted[c] - sorted[f]) * (k - f);
}

// ── HTTP ────────────────────────────────────────────────────────────────

/**
 * 整个压测共用这一个 agent，也就是共用一个长连接池。
 * maxSockets = 并发数：每个 worker 固定分到一条，不用反复三次握手。
 * Windows 上每次新建 TCP 连接的成本远大于一次 Redis 往返，不复用的话测的根本不是服务端。
 */
function makeAgent(concurrency) {
  return new http.Agent({
    keepAlive: true,
    maxSockets: concurrency,
    maxFreeSockets: concurrency,
    scheduling: 'fifo',
  });
}

/**
 * 发一个请求，resolve 成 { status, body }。
 * @param {http.Agent} agent
 * @param {string} method
 * @param {string} urlPath
 * @param {{token?: string, body?: object}} [opts]
 */
function request(agent, method, urlPath, opts) {
  const o = opts || {};
  return new Promise((resolve, reject) => {
    const headers = {};
    let payload = null;
    if (o.body !== undefined) {
      payload = Buffer.from(JSON.stringify(o.body), 'utf8');
      headers['Content-Type'] = 'application/json';
      headers['Content-Length'] = String(payload.length);
    }
    if (o.token) headers['Authorization'] = 'Bearer ' + o.token;

    const req = http.request(
      { host: HOST, port: PORT, path: urlPath, method, agent, headers },
      (res) => {
        res.setEncoding('utf8');
        let raw = '';
        res.on('data', (c) => {
          raw += c;
        });
        res.on('end', () => {
          let parsed;
          try {
            parsed = JSON.parse(raw);
          } catch (e) {
            parsed = { raw: raw.slice(0, 200) };
          }
          resolve({ status: res.statusCode, body: parsed });
        });
      }
    );
    req.on('error', reject);
    req.end(payload);
  });
}

/** 读当前人气（游客可读，不需要 token）；读不到返回 null，不影响压测本体 */
async function readCount(agent, room) {
  try {
    const r = await request(agent, 'GET', '/api/live/' + room + '/like');
    if (r.status === 200 && r.body && r.body.code === 0) return r.body.data;
  } catch (e) {
    console.log('[warn] 读人气失败：%s', e.message);
  }
  return null;
}

// ── 主流程 ──────────────────────────────────────────────────────────────

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    console.log('用法：node bench-live-like.js --room 204 --users 500 --per-user 20 --concurrency 200');
    return 0;
  }

  if (args.delta < 1 || args.delta > 50) {
    console.log('[致命] delta 必须在 1~50（服务端 MAX_DELTA=50）');
    return 2;
  }

  // 账号池：bench-like.py 造号时写下来的，每个元素是一个已登录账号的 JWT
  if (!fs.existsSync(TOKEN_CACHE)) {
    console.log('[致命] 找不到 %s，请先跑一次 bench-like.py 造号', TOKEN_CACHE);
    return 2;
  }
  const allTokens = JSON.parse(fs.readFileSync(TOKEN_CACHE, 'utf8'));
  const tokens = allTokens.slice(0, args.users);
  if (tokens.length === 0) {
    console.log('[致命] 账号池是空的');
    return 2;
  }

  const total = tokens.length * args.perUser;
  console.log('直播间人气压测（Node）—— %s  房间=%d', BASE, args.room);
  console.log(
    '账号=%d  每人=%d 次  总请求=%d  并发=%d  delta=%d',
    tokens.length,
    args.perUser,
    total,
    args.concurrency,
    args.delta
  );
  if (args.perUser > 20) {
    console.log(
      '  ⚠️ 每人 %d 次 > 服务端限速 20 次/秒，会有一部分被拒绝（属预期）',
      args.perUser
    );
  }

  const agent = makeAgent(Math.min(args.concurrency, total) + 1);

  const before = await readCount(agent, args.room);
  console.log('基线人气：%s', before);

  // 轮转排队：第 1 轮每人 1 次、第 2 轮每人 1 次……
  // 这样同一账号的请求会分散在整场压测里，而不是在同一毫秒内连发 20 次，
  // 既更贴近真实流量，也避免被人为制造出的突刺误判成「被限速」。
  const jobs = [];
  for (let r = 0; r < args.perUser; r++) {
    for (let i = 0; i < tokens.length; i++) jobs.push(tokens[i]);
  }
  const n = jobs.length;

  const lat = [];          // 成功请求的延迟（毫秒）
  const rateLimited = [];  // 被限速的请求数
  const errors = [];       // 其它错误

  let cursor = 0;          // 单线程事件循环里 cursor++ 是安全的，不需要锁
  let retries = 0;
  let done = 0;
  const urlPath = '/api/live/' + args.room + '/like';
  const body = { delta: args.delta };

  // 发令枪：所有 worker 先挂起，等同一个 Promise 一起放行，
  // 保证测的是「同时开打」而不是「启动快的线程先跑完一半」。
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
        r = await request(agent, 'POST', urlPath, { token, body });
      } catch (e) {
        // 长连接被服务端 keep-alive 超时单方面关掉时，会在没有收到任何响应前抛 EPIPE/ECONNRESET。
        // 这不是服务端算错，只是客户端拿到了一条已经死掉的空闲连接，重发一次即可。
        // 计一下次数，方便事后判断「增量 == 成功数」那个校验有没有被重试影响。
        retries++;
        try {
          r = await request(agent, 'POST', urlPath, { token, body });
        } catch (e2) {
          errors.push({ status: 0, msg: e2.code || e2.message });
          done++;
          continue;
        }
      }
      const dt = performance.now() - t0;
      done++;

      const code = r.body ? r.body.code : undefined;
      const msg = String((r.body && r.body.msg) || '');
      if (r.status === 200 && code === 0) {
        lat.push(dt);
      } else if (code === 1 && (msg.includes('太快') || msg.includes('慢一点'))) {
        rateLimited.push(dt);
      } else {
        errors.push({ status: r.status, code, msg: msg.slice(0, 80) });
      }
    }
  }

  let ticker = null;
  if (SHOW_PROGRESS) {
    ticker = setInterval(() => {
      process.stdout.write('\r  进行中… ' + done + '/' + n + '  ');
    }, 1000);
  }

  const threads = [];
  for (let i = 0; i < Math.min(args.concurrency, n); i++) threads.push(worker());
  // 给 workers 一个 tick 把自己挂到 gate 上，再一起放行
  await new Promise((res) => setImmediate(res));
  const tStart = performance.now();
  fire();
  await Promise.all(threads);
  const elapsed = (performance.now() - tStart) / 1000;

  if (ticker) {
    clearInterval(ticker);
    process.stdout.write('\r' + ' '.repeat(40) + '\r');
  }

  lat.sort((a, b) => a - b);
  const qps = elapsed > 0 ? lat.length / elapsed : 0;

  console.log('\n' + '='.repeat(62));
  console.log('结果');
  console.log('='.repeat(62));
  console.log('总请求              : %d', n);
  console.log('成功                : %d', lat.length);
  console.log('被限速拒绝          : %d', rateLimited.length);
  console.log('其他错误            : %d', errors.length);
  if (errors.length > 0) {
    console.log('错误样例            : %o', errors[0]);
  }
  if (retries > 0) {
    console.log('长连接重试          : %d（keep-alive 空闲连接被关闭，已自动重发）', retries);
  }
  console.log('总耗时              : %s s', elapsed.toFixed(2));
  // 注意：Node 的 console.log 不支持 %.0f 这种 C 风格精度，只能用 %s + toFixed
  console.log('实测 QPS            : %s 请求/秒', qps.toFixed(0));
  if (lat.length > 0) {
    console.log(
      '延迟 P50/P95/P99    : %s / %s / %s ms',
      pct(lat, 50).toFixed(2),
      pct(lat, 95).toFixed(2),
      pct(lat, 99).toFixed(2)
    );
  }

  // ── 校验：人气增量应等于 成功请求数 × delta ──
  await new Promise((res) => setTimeout(res, 300));
  const after = await readCount(agent, args.room);
  console.log('-'.repeat(62));
  console.log('校验');
  console.log('  基线人气          : %s', before);
  console.log('  跑完人气          : %s', after);
  if (before !== null && after !== null) {
    const got = after - before;
    const expect = lat.length * args.delta;
    console.log('  实际增量          : %d（期望 %d）', got, expect);
    if (got === expect) {
      console.log('  ✅ 人气增量与成功请求数完全一致，限速与计数原子性正确');
    } else {
      console.log('  ⚠️ 对不上：可能有别的客户端同时在点这个房间，或 delta 配置不一致');
      if (retries > 0) {
        console.log('     （本次有 %d 次长连接重试，可能是这里造成多算了一次）', retries);
      }
    }
  }

  console.log('\n' + '='.repeat(62));
  console.log('和 Python 版比一比：');
  console.log('  Python 版（GIL + 同机抢 CPU） : 约 1000～1100 请求/秒 ← 那是客户端的上限');
  console.log('  Node 版（上面的实测 QPS）    : ← 这才是服务端的上限');
  console.log('  视频点赞（MySQL 事务写入）   : 约 600 请求/秒');
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
