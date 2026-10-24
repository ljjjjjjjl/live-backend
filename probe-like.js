#!/usr/bin/env node
/**
 * 视频点赞的最小操作工具 —— 只做两件事：看计数、点一次赞。
 *
 * <p>为什么需要它：`GET /api/video/{id}/like` 和 `POST /api/video/{id}/like`
 * 都<b>需要登录</b>（不在游客可读白名单里），手工测一次要"发验证码 → 拿验证码 →
 * 登录 → 拿 token → 带 Bearer 请求"，四步。这个脚本把这几步包起来，
 * 让"验证幂等 / 验证降级"这类测试不被人为步骤打断。
 *
 * <p>它走的是 dev profile 的 Mock 短信（验证码在响应里回显），所以只能在 dev 环境用。
 *
 * <h2>用法</h2>
 *   # 看某个视频当前点赞数（读 Redis 派生计数）
 *   node probe-like.js count --video 104
 *
 *   # 点一次赞，打印耗时 + 响应里的计数 + 3 秒后的真实计数
 *   node probe-like.js like --video 104
 *
 * <p>服务不在 8080 时：`set BASE=http://127.0.0.1:18080`
 */

'use strict';

const http = require('http');
const { performance } = require('perf_hooks');

const BASE = (process.env.BASE || 'http://127.0.0.1:8080').replace(/\/+$/, '');
const _u = new URL(BASE);
const HOST = _u.hostname;
const PORT = _u.port || 80;

function parseArgs(argv) {
  const args = { cmd: argv[0] || 'count', video: 104 };
  for (let i = 1; i < argv.length; i++) {
    if (argv[i] === '--video') args.video = Number(argv[++i]);
  }
  return args;
}

function request(method, urlPath, body, token) {
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
      { host: HOST, port: PORT, path: urlPath, method, headers },
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

/** 走 Mock 短信登录，返回 token */
async function login() {
  const phone = '131' + String(Math.floor(Math.random() * 1e8)).padStart(8, '0');
  const sent = await request('POST', '/api/auth/sms/send', { phone });
  const code = sent.body && sent.body.data && sent.body.data.mockCode;
  if (!code) {
    throw new Error('没拿到 mockCode —— 确认服务是用 dev profile 启动的');
  }
  const logged = await request('POST', '/api/auth/sms/login', { phone, code });
  const token = logged.body && logged.body.data && logged.body.data.token;
  if (!token) throw new Error('登录未返回 token');
  return token;
}

async function readCount(video, token) {
  const r = await request('GET', '/api/video/' + video + '/like', undefined, token);
  if (!r.body || r.body.code !== 0) {
    throw new Error('读计数失败：' + JSON.stringify(r.body).slice(0, 120));
  }
  return r.body.data.likeCount;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  const args = parseArgs(process.argv.slice(2));

  if (args.cmd === 'count') {
    const token = await login();
    const c = await readCount(args.video, token);
    console.log('视频 %d 点赞数 = %d', args.video, c);
    return 0;
  }

  if (args.cmd === 'like') {
    // 读计数和点赞用不同账号：读的那个保持"没点过赞"，方便反复读
    const reader = await login();
    const before = await readCount(args.video, reader);
    const liker = await login();

    const t0 = performance.now();
    const r = await request('POST', '/api/video/' + args.video + '/like', undefined, liker);
    const ms = performance.now() - t0;

    if (!r.body || r.body.code !== 0) {
      console.log('点赞失败：%o', r.body);
      return 1;
    }
    const returned = r.body.data && r.body.data.likeCount;

    console.log('点赞前        : %d', before);
    console.log('接口耗时      : %s ms', ms.toFixed(0));
    console.log('响应里的计数  : %s  %s', returned,
      returned === before ? '<-- 旧值，说明走了 MQ 异步' : '<-- 新值，说明是同步算的');

    // 最多等 5 秒看计数什么时候追上
    const t1 = performance.now();
    for (let i = 0; i < 50; i++) {
      const c = await readCount(args.video, reader);
      if (c >= before + 1) {
        console.log('计数生效      : %s ms 后变成 %d', (performance.now() - t1).toFixed(0), c);
        return 0;
      }
      await sleep(100);
    }
    console.log('⚠️ 5 秒内计数没变化 —— 检查消费者是否正常（或对账还没跑）');
    return 1;
  }

  console.error('未知子命令：%s（可用：count / like）', args.cmd);
  return 2;
}

main().then(
  (code) => process.exit(code),
  (e) => {
    console.error('[错误] %s', e.message);
    process.exit(1);
  }
);
