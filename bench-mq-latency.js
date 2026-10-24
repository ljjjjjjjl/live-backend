#!/usr/bin/env node
/**
 * 消息队列「端到端延迟」测量 —— 一条点赞从"发出请求"到"Redis 计数真正变化"隔了多久。
 *
 * <h2>为什么这个指标才是异步化真正该测的</h2>
 * 开启 MQ 后，`POST /api/video/{id}/like` 的返回值<b>是旧值</b>：
 * 请求线程只写权威表就返回了，Redis 计数要等消费者处理完才变。
 * 所以"接口多快"已经不能代表用户看到的数据什么时候正确 —— 真正要回答的是：
 * <b>这次点赞，过多久才会出现在点赞数里？</b>
 *
 * <h2>为什么不直接用 HTTP 接口轮询</h2>
 * `GET /api/video/{id}/like` 一次往返要 5~6ms（含鉴权、Redis 读、可能的库读）。
 * 如果消费者只花 3ms 就算完了，你用 6ms 的探针去问，测出来永远是 6ms 起步 ——
 * <b>测的是探针的反应速度，不是链路的延迟</b>。
 *
 * 所以这里直接对 Redis 说协议：一条 `GET video:like:count:{id}` 走本地回环，
 * 往返通常在 0.1~0.3ms，比 HTTP 探针精确一到两个数量级。
 *
 * <h2>两种模式</h2>
 * <ul>
 *   <li><b>串行模式</b>（默认）：一次点一个赞，测<b>单条消息</b>的端到端延迟分布，
 *       输出 P50/P95/P99。这是"最终一致到底有多最终"的答案。</li>
 *   <li><b>突发模式</b>（{@code --burst N}）：一口气打 N 个赞，测<b>整体消化时间</b>
 *       和消化速率（条/秒）。这是"削峰能力"的答案。</li>
 * </ul>
 *
 * <h2>顺带会拆出一个关键差值</h2>
 * 每次测量同时记录：
 *   · {@code tHttp}  —— 接口响应时间（用户点击后多久拿到响应）
 *   · {@code tTotal} —— 端到端时间（到 Redis 计数生效）
 *   · {@code tTotal - tHttp} —— <b>接口返回之后又等了多久</b>，这才是异步真正引入的延迟
 *
 * <h2>用法</h2>
 *     # 串行：20 轮，看单条消息延迟分布
 *     node bench-mq-latency.js --video 103 --rounds 20
 *
 *     # 突发：500 次点赞，看消化时间与速率
 *     node bench-mq-latency.js --video 103 --burst 500 --concurrency 50
 *
 * 前置：
 *   · 服务端必须开着 MQ（{@code -Dspring-boot.run.profiles=dev,mq}），否则不会走异步链路，
 *     测出来的就是"同步"数据（tTotal ≈ tHttp）
 *   · 必须能连上 Redis（默认 127.0.0.1:6379，可用 REDIS_HOST / REDIS_PORT 覆盖）
 *   · dev profile（要用 Mock 短信登录现场造账号）
 */

'use strict';

const http = require('http');
const net = require('net');
const { performance } = require('perf_hooks');

const BASE = (process.env.BASE || 'http://127.0.0.1:8080').replace(/\/+$/, '');
const _u = new URL(BASE);
const HOST = _u.hostname;
const PORT = _u.port || 80;

const REDIS_HOST = process.env.REDIS_HOST || '127.0.0.1';
const REDIS_PORT = Number(process.env.REDIS_PORT || 6379);

// ── 参数 ────────────────────────────────────────────────────────────────
function parseArgs(argv) {
  const args = {
    video: 103,
    rounds: 20,
    burst: 0,
    concurrency: 50,
    timeoutMs: 15000,
  };
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
    if (a === '--video') args.video = Number(next());
    else if (a === '--rounds') args.rounds = Number(next());
    else if (a === '--burst') args.burst = Number(next());
    else if (a === '--concurrency') args.concurrency = Number(next());
    else if (a === '--timeout-ms') args.timeoutMs = Number(next());
    else {
      console.error('[致命] 未知参数：%s', a);
      process.exit(2);
    }
  }
  if (args.burst > 0 && args.rounds > 0) args.rounds = 0; // 突发模式优先
  return args;
}

function pct(sorted, p) {
  if (sorted.length === 0) return 0;
  const k = ((sorted.length - 1) * p) / 100;
  const f = Math.floor(k);
  const c = Math.min(f + 1, sorted.length - 1);
  return f === c ? sorted[f] : sorted[f] + (sorted[c] - sorted[f]) * (k - f);
}

// ── 极简 Redis 客户端（只为轮询一个 key，不值得引依赖）──────────────
//
// 用 Redis 的行内命令（inline command）：直接发 "GET key\r\n" 就行，
// 不需要实现完整的 RESP 编码。回复的解析只需要支持 bulk string / integer / 错误。
function makeRedisClient() {
  const sock = net.connect({ host: REDIS_HOST, port: REDIS_PORT });
  sock.setNoDelay(true);

  let buf = '';
  let waiting = null; // { resolve, reject }

  function finish(err, value) {
    const w = waiting;
    waiting = null;
    if (!w) return;
    if (err) w.reject(err);
    else w.resolve(value);
  }

  function tryParse() {
    if (!waiting) return;
    const nl = buf.indexOf('\r\n');
    if (nl < 0) return;
    const head = buf.slice(0, nl);
    const type = head[0];

    if (type === '$') {
      const len = parseInt(head.slice(1), 10);
      if (len < 0) {
        buf = buf.slice(nl + 2);
        finish(null, null); // nil
        return;
      }
      const total = nl + 2 + len + 2;
      if (buf.length < total) return;
      const val = buf.slice(nl + 2, nl + 2 + len);
      buf = buf.slice(total);
      finish(null, val);
    } else if (type === ':') {
      buf = buf.slice(nl + 2);
      finish(null, Number(head.slice(1)));
    } else {
      // "+OK" / "-ERR ..."
      buf = buf.slice(nl + 2);
      finish(type === '-' ? new Error(head) : null, head.slice(1));
    }
  }

  sock.on('data', (d) => {
    buf += d.toString('utf8');
    tryParse();
  });
  sock.on('error', (e) => finish(e));

  return {
    ready() {
      return new Promise((resolve, reject) => {
        sock.once('connect', resolve);
        sock.once('error', reject);
      });
    },
    get(key) {
      return new Promise((resolve, reject) => {
        if (waiting) return reject(new Error('Redis 客户端被并发使用'));
        waiting = { resolve, reject };
        sock.write('GET ' + key + '\r\n');
      });
    },
    close() {
      sock.destroy();
    },
  };
}

// ── HTTP ────────────────────────────────────────────────────────────────
function httpJson(agent, method, urlPath, body, token) {
  return new Promise((resolve, reject) => {
    const headers = {};
    let payload = null;
    if (body !== undefined) {
      payload = Buffer.from(JSON.stringify(body), 'utf8');
      headers['Content-Type'] = 'application/json';
      headers['Content-Length'] = String(payload.length);
    }
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
          try {
            resolve({ status: res.statusCode, body: JSON.parse(raw) });
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

/** 走 Mock 短信登录造账号（dev profile 下验证码在响应里回显） */
async function loginMany(agent, count, concurrency) {
  const tokens = new Array(count);
  let idx = 0;
  const base = Date.now() % 100000;
  let seq = 0;

  async function worker() {
    for (;;) {
      const i = idx++;
      if (i >= count) return;
      const phone = '136' + String((base + seq++ * 7919) % 100000000).padStart(8, '0');
      const sent = await httpJson(agent, 'POST', '/api/auth/sms/send', { phone });
      const code = sent.body && sent.body.data && sent.body.data.mockCode;
      if (!code) throw new Error('没拿到 mockCode（需要 dev profile）');
      const logged = await httpJson(agent, 'POST', '/api/auth/sms/login', { phone, code });
      const token = logged.body && logged.body.data && logged.body.data.token;
      if (!token) throw new Error('登录未返回 token');
      tokens[i] = token;
    }
  }

  const workers = [];
  for (let w = 0; w < Math.min(concurrency, count); w++) workers.push(worker());
  await Promise.all(workers);
  return tokens;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * 数字格式化：右对齐到 7 位、保留两位小数。
 * 为什么不用 printf 风格的 "%6.2f"：Node 的 console.log 只支持 %s/%d/%i/%f/%o/%O/%j/%c，
 * 不认识宽度和精度，写成 %6.2f 会被当成普通文本输出、参数被追加到行尾。
 */
const f2 = (v) => Number(v).toFixed(2).padStart(7);

/** 轮询 Redis 直到值 >= target（或超时）。返回 { elapsedMs, polls } */
async function waitUntil(redis, key, target, timeoutMs, intervalMs) {
  const t0 = performance.now();
  let polls = 0;
  for (;;) {
    const raw = await redis.get(key);
    polls++;
    const v = raw === null ? null : Number(raw);
    const elapsed = performance.now() - t0;
    if (v !== null && v >= target) return { elapsedMs: elapsed, polls, value: v };
    if (elapsed > timeoutMs) return { elapsedMs: elapsed, polls, value: v, timedOut: true };
    await sleep(intervalMs);
  }
}

// ── 主流程 ──────────────────────────────────────────────────────────────
async function main() {
  const args = parseArgs(process.argv.slice(2));

  const agent = new http.Agent({ keepAlive: true, maxSockets: Math.max(args.concurrency, 20) });
  const redis = makeRedisClient();
  await redis.ready();

  const likePath = '/api/video/' + args.video + '/like';
  const redisKey = 'video:like:count:' + args.video;

  console.log('消息队列端到端延迟 —— 服务=%s  Redis=%s:%d  视频=%d',
    BASE, REDIS_HOST, REDIS_PORT, args.video);

  // ① 先登录一个账号，用它读一次 HTTP 计数 —— 顺便把 Redis 那个 key "捂热"。
  //    如果 key 本来不存在，直接 GET 会拿到 nil，轮询就没法判断"涨没涨"。
  const [reader] = await loginMany(agent, 1, 1);
  const g = await httpJson(agent, 'GET', likePath, undefined, reader);
  if (!g.body || g.body.code !== 0) {
    console.log('[致命] 读取初始计数失败：%o', g.body);
    return 2;
  }
  let current = g.body.data.likeCount;
  console.log('初始计数：%d', current);

  const fromRedis = await redis.get(redisKey);
  if (fromRedis === null) {
    console.log('[致命] Redis 里没有 %s —— 服务端可能没连到这个 Redis', redisKey);
    return 2;
  }
  console.log('Redis 确认：%s = %s', redisKey, fromRedis);
  console.log('  （轮询走 Redis 原始协议，单次约 0.1~0.3ms，比 HTTP 探针精确得多）\n');

  // ── 模式一：串行，测单条消息的延迟分布 ──
  if (args.rounds > 0) {
    console.log('=== 串行模式：%d 轮 ===', args.rounds);
    const tokens = await loginMany(agent, args.rounds, 20);

    const tHttp = [];
    const tTotal = [];
    const stale = [];
    let failures = 0;

    for (let i = 0; i < args.rounds; i++) {
      const before = current;
      const target = before + 1;

      const s0 = performance.now();
      let r;
      try {
        r = await httpJson(agent, 'POST', likePath, undefined, tokens[i]);
      } catch (e) {
        failures++;
        continue;
      }
      const httpMs = performance.now() - s0;

      if (!r.body || r.body.code !== 0) {
        failures++;
        continue;
      }
      // 记一下：接口响应里给的计数是不是旧值（是旧值才说明真的走了异步）
      const returned = r.body.data ? r.body.data.likeCount : null;
      if (returned !== null) stale.push(returned < target ? 1 : 0);

      const w = await waitUntil(redis, redisKey, target, args.timeoutMs, 1);
      if (w.timedOut) {
        console.log('  第 %d 轮超时：等 %.0fms 仍未达到 %d（当前 %s）',
          i + 1, w.elapsedMs, target, w.value);
        failures++;
        continue;
      }

      tHttp.push(httpMs);
      tTotal.push(httpMs + w.elapsedMs);
      current = w.value;

      if ((i + 1) % 5 === 0 || args.rounds <= 10) {
        console.log('  第 %s 轮：接口 %s ms | 计数生效 %s ms | 异步额外 +%s ms',
          String(i + 1).padStart(2),
          f2(httpMs),
          f2(httpMs + w.elapsedMs),
          f2(w.elapsedMs));
      }
    }

    console.log('\n' + '='.repeat(64));
    console.log('结果（成功 %d / %d，失败 %d）', tTotal.length, args.rounds, failures);
    console.log('='.repeat(64));
    if (tHttp.length > 0) {
      tHttp.sort((a, b) => a - b);
      tTotal.sort((a, b) => a - b);
      const extra = tTotal.map((v, i) => v - tHttp[i]).sort((a, b) => a - b);

      console.log('接口响应时间     P50 %s  P95 %s  P99 %s ms',
        f2(pct(tHttp, 50)), f2(pct(tHttp, 95)), f2(pct(tHttp, 99)));
      console.log('端到端(含异步)   P50 %s  P95 %s  P99 %s ms',
        f2(pct(tTotal, 50)), f2(pct(tTotal, 95)), f2(pct(tTotal, 99)));
      console.log('其中异步那一段   P50 %s  P95 %s  P99 %s ms  <- MQ 引入的延迟',
        f2(pct(extra, 50)), f2(pct(extra, 95)), f2(pct(extra, 99)));

      const staleCount = stale.reduce((a, b) => a + b, 0);
      console.log('-'.repeat(64));
      if (staleCount === stale.length) {
        console.log('✅ %d 次响应返回的计数全部是旧值 → 确认走的是异步链路（不是同步降级）',
          staleCount);
      } else if (staleCount === 0) {
        console.log('⚠️ 所有响应返回的都是新值 → 说明没走异步（MQ 没开？或走了降级同步分支）');
      } else {
        console.log('⚠️ %d/%d 次响应是旧值，混合状态，建议确认服务端是否稳定开着 MQ',
          staleCount, stale.length);
      }
    }
    console.log('='.repeat(64));
  }

  // ── 模式二：突发，测整体消化时间 ──
  if (args.burst > 0) {
    console.log('=== 突发模式：%d 次点赞，客户端并发 %d ===', args.burst, args.concurrency);
    const tokens = await loginMany(agent, args.burst, 20);
    const before = current;
    const target = before + args.burst;

    let idx = 0;
    let ok = 0;
    const t0 = performance.now();
    async function worker() {
      for (;;) {
        const i = idx++;
        if (i >= tokens.length) return;
        try {
          const r = await httpJson(agent, 'POST', likePath, undefined, tokens[i]);
          if (r.body && r.body.code === 0) ok++;
        } catch (e) {
          /* 计入失败即可 */
        }
      }
    }
    const workers = [];
    for (let w = 0; w < Math.min(args.concurrency, args.burst); w++) workers.push(worker());
    await Promise.all(workers);
    const sendMs = performance.now() - t0;
    console.log('发送完毕：成功 %d/%d，耗时 %s ms（%s 条/秒）',
      ok, args.burst, f2(sendMs), f2((ok / sendMs) * 1000));

    const w = await waitUntil(redis, redisKey, target, args.timeoutMs, 5);
    console.log('-'.repeat(64));
    console.log('最终计数：%s（目标 %d）', w.value, target);
    if (w.timedOut) {
      console.log('⚠️ 超时：%d ms 内没消化完，说明消费速率跟不上生产速率（积压了）', args.timeoutMs);    } else {
      console.log('发送完成 -> 全部生效：%s ms', f2(w.elapsedMs));
      console.log('端到端总耗时     : %s ms', f2(sendMs + w.elapsedMs));
      console.log('消费消化速率     : %s 条/秒（%d 条 / %s 秒）',
        f2(ok / (w.elapsedMs / 1000)), ok, (w.elapsedMs / 1000).toFixed(2));
    }
    console.log('='.repeat(64));
  }

  redis.close();
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
