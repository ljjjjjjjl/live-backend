#!/usr/bin/env node
/**
 * 视频点赞「突发能力」压测（Node 版）—— 找"一口气打多少个请求会开始失败"。
 *
 * <h2>它和普通压测的区别</h2>
 * 普通压测回答"能稳定跑多少 QPS"；突发压测回答<b>"瞬时来一大波，会不会丢请求"</b>。
 * 这两个是完全不同的问题：一个系统可能稳定 900 QPS，但瞬时来 3000 个请求就有一半超时。
 *
 * <h2>怎么做到"瞬时"</h2>
 * 一次性把 size 个请求全部塞进 worker 池（worker 数 = concurrency，默认 500），
 * 不做任何节流、不等上一秒。于是同一瞬间有 500 个请求在飞，其余排队，
 * 排队时间全部计入后端压力 —— 这正是突发场景的样子。
 *
 * <h2>为什么每轮换一个视频，而不是换一批账号</h2>
 * 视频点赞是<b>按 (userId, videoId) 幂等</b>的：同一个账号对同一个视频点第二次会走
 * "已点过"的快速路径，根本不写库，测出来是假的。
 * 但造 N 个账号很贵（每个要发码+登录两次）。所以这里<b>造一次账号，每轮换一个视频</b>——
 * 同一批账号对新视频来说仍然是第一次点赞，写路径完全一样，省掉 80% 的准备时间。
 *
 * <h2>用法</h2>
 *     node bench-burst.js --start-video 112 --sizes 600,1200,1800,3600 --concurrency 500
 *
 * 每轮依次使用 start-video, start-video+1, ... ，所以目标视频必须真实存在且数量够。
 *
 * ⚠️ 副作用：会新建 max(sizes) 个账号，并给多个视频各增加一批 user_like。
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
    startVideo: 112,
    sizes: [600, 1200, 1800, 3600],
    concurrency: 500,
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
    if (a === '--start-video') args.startVideo = Number(next());
    else if (a === '--sizes') args.sizes = String(next()).split(',').map(Number);
    else if (a === '--concurrency') args.concurrency = Number(next());
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
      const phone = '130' + String((stamp + seq++ * 7919) % 100000000).padStart(8, '0');
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

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const maxSize = Math.max(...args.sizes);
  const agent = new http.Agent({
    keepAlive: true,
    maxSockets: args.concurrency + 2,
    maxFreeSockets: args.concurrency + 2,
  });

  console.log('视频点赞突发压测 —— %s', BASE);
  console.log('每轮大小：%s   客户端并发：%d', args.sizes.join(' / '), args.concurrency);
  console.log('视频：%d 起，每轮换一个\n', args.startVideo);

  console.log('正在造 %d 个账号（每轮复用，只是换视频）…', maxSize);
  const t0 = performance.now();
  const tokens = await loginMany(agent, maxSize, 20);
  console.log('造号完成，耗时 %ss\n', ((performance.now() - t0) / 1000).toFixed(1));

  const summary = [];

  for (let round = 0; round < args.sizes.length; round++) {
    const size = args.sizes[round];
    const video = args.startVideo + round;
    const urlPath = '/api/video/' + video + '/like';

    // 桶：第 i 秒完成了多少个
    const buckets = [];
    const lat = [];
    let fail = 0;
    let firstFailMs = null;
    let cursor = 0;

    const workers = [];
    const workerCount = Math.min(args.concurrency, size);

    async function worker() {
      for (;;) {
        const i = cursor++;
        if (i >= size) return;
        const s = performance.now();
        try {
          const r = await request(agent, 'POST', urlPath, undefined, tokens[i]);
          const dt = performance.now() - s;
          const now = performance.now();
          const b = Math.floor((now - tStart) / 1000);
          if (r.status === 200 && r.body && r.body.code === 0) {
            lat.push(dt);
            buckets[b] = (buckets[b] || 0) + 1;
          } else {
            fail++;
            if (firstFailMs === null) firstFailMs = now - tStart;
          }
        } catch (e) {
          fail++;
          if (firstFailMs === null) firstFailMs = performance.now() - tStart;
        }
      }
    }

    let tStart;
    await new Promise((res) => setImmediate(res));
    tStart = performance.now();
    for (let w = 0; w < workerCount; w++) workers.push(worker());
    await Promise.all(workers);
    const elapsed = (performance.now() - tStart) / 1000;

    lat.sort((a, b) => a - b);
    const ok = lat.length;
    const rps = ok / elapsed;

    console.log('─'.repeat(64));
    console.log('第 %d 轮：视频 %d，一次性打 %d 个请求（并发 %d）', round + 1, video, size, workerCount);
    console.log('  成功 / 失败     : %d / %d', ok, fail);
    console.log('  总耗时          : %s s', elapsed.toFixed(2));
    console.log('  吞吐            : %s 请求/秒', rps.toFixed(0));
    if (ok > 0) {
      console.log('  延迟 P50/P95/P99: %s / %s / %s ms',
        f2(pct(lat, 50)), f2(pct(lat, 95)), f2(pct(lat, 99)));
    }
    console.log('  每秒完成数      : %s', buckets.map((v) => v || 0).join(' / '));
    console.log('  首个失败        : %s', firstFailMs === null ? '没有失败' : firstFailMs.toFixed(0) + ' ms');

    summary.push({ size, ok, fail, rps, p50: ok ? pct(lat, 50) : 0, p99: ok ? pct(lat, 99) : 0 });
    // 轮之间歇一下，让服务端的连接与线程池回到静止状态
    await new Promise((r) => setTimeout(r, 1500));
  }

  console.log('\n' + '='.repeat(64));
  console.log('汇总');
  console.log('='.repeat(64));
  console.log('  请求数    成功    失败     吞吐       P50        P99');
  for (const s of summary) {
    console.log('  %s  %s  %s  %s  %s  %s',
      String(s.size).padStart(6),
      String(s.ok).padStart(6),
      String(s.fail).padStart(6),
      (s.rps.toFixed(0) + ' /s').padStart(8),
      (s.p50.toFixed(1) + ' ms').padStart(9),
      (s.p99.toFixed(1) + ' ms').padStart(9));
  }
  const allOk = summary.every((s) => s.fail === 0);
  console.log('-'.repeat(64));
  console.log(allOk
    ? '✅ 全部轮次零失败：这批突发量级内没有丢请求'
    : '⚠️ 出现了失败，说明超过某个量级后服务端开始拒绝/超时');
  console.log('='.repeat(64));

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
