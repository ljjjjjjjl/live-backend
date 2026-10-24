#!/usr/bin/env node
/**
 * 直播间「在线人数」接口压测 —— GET /api/live/{roomId}/online
 *
 * <h2>这个接口为什么值得单独压</h2>
 * 它是这条项目里**真实流量最大**的接口：前端每 5 秒轮询一次，
 * 1000 个观众在线就是 200 QPS 的常驻背景流量 —— 比点赞高一个量级。
 * 点赞只在用户真的点了才发生，而它是"只要有人在看就一直在跑"。
 *
 * <h2>服务端做了什么（看 RedisRankService#onlineCount）</h2>
 * 一次 Lua 里干两件事：
 *   ① ZREMRANGEBYSCORE  online:{roomId}  -inf  当前时间-60秒   → 踢掉掉线的人
 *   ② ZCARD             online:{roomId}                        → 数剩下几个
 * 全程只碰 Redis，不碰数据库、不写任何东西。所以压出来的就是**纯 Redis 读能力**。
 *
 * <h2>和点赞接口的对照意义</h2>
 *   · 点赞：一次 Lua，4 条命令（INCR/EXPIRE/INCRBY/EXPIRE），**写**
 *   · 在线：一次 Lua，2 条命令（ZREMRANGEBYSCORE/ZCARD），**读**
 * 两个都只需要一次网络往返，唯一差别是命令数和读写属性。
 * 拿两者的饱和吞吐对比，就能估算出「一次 Redis 往返 + N 条命令」的单价。
 *
 * <h2>用法</h2>
 *     # 小试
 *     node bench-online.js --room 204 --requests 2000 --concurrency 50
 *
 *     # 正式，找拐点
 *     node bench-online.js --room 204 --requests 20000 --concurrency 200
 *     node bench-online.js --room 204 --requests 20000 --concurrency 600
 *
 * 不需要 Token（游客可读），也不会产生任何副作用。
 */

'use strict';

const http = require('http');
const { performance } = require('perf_hooks');

const BASE = (process.env.BASE || 'http://127.0.0.1:8080').replace(/\/+$/, '');
const _u = new URL(BASE);
const HOST = _u.hostname;
const PORT = _u.port || 80;

function parseArgs(argv) {
  const args = { room: 204, requests: 20000, concurrency: 200 };
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
    else if (a === '--requests') args.requests = Number(next());
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

function request(agent, method, urlPath) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: HOST, port: PORT, path: urlPath, method, agent },
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

async function readCount(agent, room) {
  try {
    const r = await request(agent, 'GET', '/api/live/' + room + '/online');
    if (r.status === 200 && r.body && r.body.code === 0) return r.body.data;
  } catch (e) {
    console.log('[warn] 读在线人数失败：%s', e.message);
  }
  return null;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));

  console.log('直播间在线人数压测 —— %s  房间=%d', BASE, args.room);
  console.log(
    'GET /api/live/%d/online   总请求=%d  并发=%d   （无 Token，纯读，无副作用）',
    args.room,
    args.requests,
    args.concurrency
  );

  // maxSockets = 并发数 + 1：多留一条给压测前后的读数请求，避免它跟压测抢连接
  const agent = new http.Agent({
    keepAlive: true,
    maxSockets: args.concurrency + 1,
    maxFreeSockets: args.concurrency + 1,
    scheduling: 'fifo',
  });

  const before = await readCount(agent, args.room);
  console.log('跑前在线人数：%s', before);

  const lat = [];
  const errors = [];
  let cursor = 0;
  const urlPath = '/api/live/' + args.room + '/online';
  const total = args.requests;

  let fire;
  const gate = new Promise((res) => {
    fire = res;
  });

  async function worker() {
    await gate;
    for (;;) {
      const i = cursor++;
      if (i >= total) return;
      const t0 = performance.now();
      try {
        const r = await request(agent, 'GET', urlPath);
        const dt = performance.now() - t0;
        if (r.status === 200 && r.body && r.body.code === 0) {
          lat.push(dt);
        } else {
          errors.push({ status: r.status, body: JSON.stringify(r.body).slice(0, 80) });
        }
      } catch (e) {
        errors.push({ status: 0, msg: e.code || e.message });
      }
    }
  }

  const workers = [];
  for (let i = 0; i < Math.min(args.concurrency, total); i++) workers.push(worker());
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
  console.log('总请求              : %d', total);
  console.log('成功                : %d', lat.length);
  console.log('其他错误            : %d', errors.length);
  if (errors.length > 0) {
    console.log('错误样例            : %o', errors[0]);
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

  await new Promise((res) => setTimeout(res, 200));
  const after = await readCount(agent, args.room);
  console.log('-'.repeat(62));
  console.log('校验（这是个纯读接口，压测本身不该改变任何数据）');
  console.log('  跑前在线人数      : %s', before);
  console.log('  跑后在线人数      : %s', after);
  if (before !== null && after !== null) {
    if (before === after) {
      console.log('  ✅ 前后一致，确认该接口无副作用');
    } else {
      console.log('  ⚠️ 前后不一致：likely 有别的人在进房/离房/心跳，不是压测造成的');
    }
  }

  console.log('\n' + '='.repeat(62));
  console.log('怎么读这个数：');
  console.log('  它是一条「一次网络往返 + 2 条 Redis 命令（清理过期 + 数人数）」的读路径，');
  console.log('  不查库、不写 Redis。所以它的 QPS 就是这台机器上「纯 Redis 读」的天花板。');
  console.log('  拿它和 POST /api/live/{id}/like（1 次往返 + 4 条命令 + 写）的 QPS 对比，');
  console.log('  差值就是「多 2 条命令 + 写属性」的代价。');
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
