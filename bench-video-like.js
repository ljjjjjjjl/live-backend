#!/usr/bin/env node
/**
 * 视频点赞压测（Node 版）—— 单档压测 + 并发扫描找最优点。
 *
 * <h2>为什么要"扫描并发"</h2>
 * 只测一个并发值（比如 200）得到的是"那个点上的成绩"，不是接口的上限。
 * 真正的结论来自一条曲线：并发往上加，吞吐先涨、到某个点后<b>不再涨</b>，
 * 而延迟继续线性变长 —— 那个拐点就是最优工作点。
 *
 * 直播间人气接口就是这么找出来的（50 → 1211，200 → 2838，600 → 2687），
 * 峰值在 200。视频点赞从来没扫过，只有 200 并发下一个 948。
 *
 * <h2>这个接口的语义约束（脚本按它设计）</h2>
 *   · {@code POST /api/video/{id}/like} 按 (userId, videoId) 幂等：
 *     同一账号对同一视频点第二次会走"已点过"的快速路径，<b>不写库</b>，测出来是假的。
 *   · 所以扫描时：<b>账号只造一次，每档并发换一个视频</b>。
 *     这样同一批账号对每个新视频来说仍然是"第一次点赞"，写路径完全一致，
 *     又能省掉反复造号的开销（造 2000 个号要 30 秒）。
 *
 * <h2>用法</h2>
 *     # 单档
 *     node bench-video-like.js --video 103 --users 2000 --concurrency 200
 *
 *     # 并发扫描：依次用视频 116,117,118,119,120
 *     node bench-video-like.js --start-video 116 --sweep 50,100,200,400,800 --users 2000
 *
 * ⚠️ 副作用：每轮新建 users 个账号，并给每个扫描视频各加 users 条 user_like。
 */

'use strict';

const http = require('http');
const { performance } = require('perf_hooks');

const BASE = (process.env.BASE || 'http://127.0.0.1:8080').replace(/\/+$/, '');
const _u = new URL(BASE);
const HOST = _u.hostname;
const PORT = _u.port || 80;

function parseArgs(argv) {
  const args = {
    video: 103,
    startVideo: 0,
    sweep: [],
    users: 2000,
    concurrency: 200,
    timeoutMs: 20000,
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
    else if (a === '--start-video') args.startVideo = Number(next());
    else if (a === '--sweep') args.sweep = String(next()).split(',').map(Number);
    else if (a === '--users') args.users = Number(next());
    else if (a === '--concurrency') args.concurrency = Number(next());
    else if (a === '--timeout-ms') args.timeoutMs = Number(next());
    else {
      console.error('[致命] 未知参数：%s', a);
      process.exit(2);
    }
  }
  if (args.sweep.length > 0 && args.startVideo === 0) args.startVideo = args.video;
  return args;
}

function pct(sorted, p) {
  if (sorted.length === 0) return 0;
  const k = ((sorted.length - 1) * p) / 100;
  const f = Math.floor(k);
  const c = Math.min(f + 1, sorted.length - 1);
  return f === c ? sorted[f] : sorted[f] + (sorted[c] - sorted[f]) * (k - f);
}

const f2 = (v) => Number(v).toFixed(2).padStart(8);

function request(agent, method, urlPath, body, token) {
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
            reject(new Error('非 JSON 响应'));
          }
        });
      }
    );
    req.on('error', reject);
    req.end(payload);
  });
}

async function loginMany(agent, count, concurrency) {
  const tokens = new Array(count);
  let idx = 0;
  const stamp = Date.now() % 100000;
  let seq = 0;

  async function worker() {
    for (;;) {
      const i = idx++;
      if (i >= count) return;
      const phone = '139' + String((stamp + seq++ * 7919) % 100000000).padStart(8, '0');
      const sent = await request(agent, 'POST', '/api/auth/sms/send', { phone });
      const code = sent.body && sent.body.data && sent.body.data.mockCode;
      if (!code) throw new Error('没拿到 mockCode（需要 dev profile）');
      const logged = await request(agent, 'POST', '/api/auth/sms/login', { phone, code });
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

async function readCount(agent, video, token) {
  const r = await request(agent, 'GET', '/api/video/' + video + '/like', undefined, token);
  if (!r.body || r.body.code !== 0) return null;
  return r.body.data.likeCount;
}

/** 跑一轮：用 tokens 以指定并发打 video */
async function runRound(agent, video, tokens, concurrency, timeoutMs) {
  const before = await readCount(agent, video, tokens[0]);
  if (before === null) return { error: '读不到基线计数' };

  const lat = [];
  let errors = 0;
  let cursor = 0;

  async function worker() {
    for (;;) {
      const i = cursor++;
      if (i >= tokens.length) return;
      const t0 = performance.now();
      try {
        const r = await request(agent, 'POST', '/api/video/' + video + '/like', undefined, tokens[i]);
        const dt = performance.now() - t0;
        if (r.status === 200 && r.body && r.body.code === 0) lat.push(dt);
        else errors++;
      } catch (e) {
        errors++;
      }
    }
  }

  const t0 = performance.now();
  const workers = [];
  for (let w = 0; w < Math.min(concurrency, tokens.length); w++) workers.push(worker());
  await Promise.all(workers);
  const elapsed = (performance.now() - t0) / 1000;

  lat.sort((a, b) => a - b);

  // 等派生计数追平（MQ 关闭时几乎立刻，MQ 开启时要等消费者）
  const expect = before + lat.length;
  const tWait = performance.now();
  let final = null;
  for (let i = 0; i < Math.round(timeoutMs / 50); i++) {
    final = await readCount(agent, video, tokens[0]);
    if (final !== null && final >= expect) break;
    await sleep(50);
  }
  const waitMs = performance.now() - tWait;

  return {
    ok: lat.length,
    errors,
    elapsed,
    qps: lat.length / elapsed,
    p50: pct(lat, 50),
    p95: pct(lat, 95),
    p99: pct(lat, 99),
    expect,
    final,
    waitMs,
  };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));

  const maxConcurrency = args.sweep.length > 0 ? Math.max(...args.sweep) : args.concurrency;
  const agent = new http.Agent({
    keepAlive: true,
    maxSockets: maxConcurrency + 2,
    maxFreeSockets: maxConcurrency + 2,
  });

  if (args.sweep.length > 0) {
    console.log('视频点赞 · 并发扫描 —— %s', BASE);
    console.log('每档请求数：%d   并发档位：%s', args.users, args.sweep.join(' / '));
    console.log('视频：%d 起，每档换一个（避免命中幂等快速路径）\n', args.startVideo);
  } else {
    console.log('视频点赞压测（Node）—— %s  视频=%d  请求=%d  并发=%d',
      BASE, args.video, args.users, args.concurrency);
  }

  console.log('正在造 %d 个账号…', args.users);
  const tLogin = performance.now();
  const tokens = await loginMany(agent, args.users, 20);
  console.log('造号完成，耗时 %ss\n', ((performance.now() - tLogin) / 1000).toFixed(1));

  const results = [];
  const plan = args.sweep.length > 0
    ? args.sweep.map((c, i) => ({ concurrency: c, video: args.startVideo + i }))
    : [{ concurrency: args.concurrency, video: args.video }];

  for (const p of plan) {
    const r = await runRound(agent, p.video, tokens, p.concurrency, args.timeoutMs);
    if (r.error) {
      console.log('并发 %d / 视频 %d : 失败 —— %s', p.concurrency, p.video, r.error);
      continue;
    }
    console.log('─'.repeat(64));
    console.log('并发 %d / 视频 %d', p.concurrency, p.video);
    console.log('  成功 / 错误     : %d / %d', r.ok, r.errors);
    console.log('  耗时            : %s s', r.elapsed.toFixed(2));
    console.log('  吞吐            : %s 请求/秒', r.qps.toFixed(0));
    console.log('  延迟 P50/P95/P99: %s / %s / %s ms', f2(r.p50), f2(r.p95), f2(r.p99));
    const okCount = r.final === r.expect;
    console.log('  计数校验        : %s（期望 %d，追平耗时 %s ms）%s',
      r.final, r.expect, r.waitMs.toFixed(0), okCount ? ' ✅' : ' ⚠️');
    results.push({ ...p, ...r });
    // 轮之间歇一下，让连接池和服务端线程池回到静止
    await sleep(1500);
  }

  if (results.length > 1) {
    console.log('\n' + '='.repeat(64));
    console.log('汇总');
    console.log('='.repeat(64));
    console.log('  并发    视频     吞吐       P50        P99');
    for (const r of results) {
      console.log('  %s  %s  %s  %s  %s',
        String(r.concurrency).padStart(4),
        String(r.video).padStart(4),
        (r.qps.toFixed(0) + ' /s').padStart(9),
        (r.p50.toFixed(1) + ' ms').padStart(9),
        (r.p99.toFixed(1) + ' ms').padStart(9));
    }
    const best = results.reduce((a, b) => (b.qps > a.qps ? b : a));
    console.log('-'.repeat(64));
    console.log('  最优点：并发 %d（视频 %d）→ %s 请求/秒，P50 %s ms',
      best.concurrency, best.video, best.qps.toFixed(0), best.p50.toFixed(1));
    console.log('  判据：再看并发更高那档的吞吐是否不再增长 —— 不增长就说明已经到拐点。');
    console.log('='.repeat(64));
  }

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
