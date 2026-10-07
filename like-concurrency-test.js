/* 点赞并发幂等验证脚本（临时工具，验证完可删）
   用途：对同一个视频并发发 10 个点赞请求，验证数据库唯一索引 uk_user_video
        真的能挡住重复插入 —— 计数只应 +1。
   运行：node like-concurrency-test.js   （需后端已启动在 8080） */

const BASE = 'http://localhost:8080';
const VID = 111;

const j = (r) => r.json();
const auth = (token) => ({ Authorization: 'Bearer ' + token });

async function main() {
    const phone = '139' + String(Math.floor(10000000 + Math.random() * 89999999));

    // 1) 短信 mock 登录，拿一个全新用户（点赞状态干净）
    const send = await fetch(BASE + '/api/auth/sms/send', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ phone })
    }).then(j);

    const login = await fetch(BASE + '/api/auth/sms/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ phone, code: send.data.mockCode })
    }).then(j);

    const token = login.data.token;
    console.log(`新用户 userId=${login.data.userId}`);

    // 2) 并发前状态
    const before = await fetch(`${BASE}/api/video/${VID}/like`, { headers: auth(token) }).then(j);
    console.log(`并发前：liked=${before.data.liked}  count=${before.data.likeCount}`);

    // 3) 同时发出 10 个点赞请求（Promise.all = 真正并发）
    const results = await Promise.all(
        Array.from({ length: 10 }, () =>
            fetch(`${BASE}/api/video/${VID}/like`, { method: 'POST', headers: auth(token) }).then(j)
        )
    );
    console.log('10 个并发请求 code  = ' + results.map(r => r.code).join(','));
    console.log('10 个并发请求 count = ' + results.map(r => r.data && r.data.likeCount).join(','));

    // 4) 并发后状态
    const after = await fetch(`${BASE}/api/video/${VID}/like`, { headers: auth(token) }).then(j);
    console.log(`并发后：liked=${after.data.liked}  count=${after.data.likeCount}`);

    const expected = before.data.likeCount + 1;
    if (after.data.likeCount === expected && after.data.liked === true) {
        console.log(`PASS 并发 10 次点赞只 +1（${before.data.likeCount} → ${after.data.likeCount}），唯一索引兜底成功`);
    } else {
        console.log(`FAIL 期望 count=${expected}，实际 ${after.data.likeCount}`);
    }

    // 5) 收尾，恢复现场
    await fetch(`${BASE}/api/video/${VID}/like`, { method: 'DELETE', headers: auth(token) });
    console.log('已收尾（取消点赞，恢复现场）');
}

main().catch(e => { console.error('脚本异常：', e); process.exit(1); });
