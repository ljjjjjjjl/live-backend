/* =========================================================================
   修复验证脚本（临时工具，可删）
   覆盖本轮修的 P0/P1/P2：
     1  创建直播间不再接受客户端 id（+ 被强制下播的房间会结算人气）
     2  WS 弹幕昵称不再采信前端
     3  WS 弹幕的长度/颜色/频率校验
     4  unlike 不存在的视频不再污染 Redis
     6  在线人数 ZSet + 心跳
     13 礼物榜 top=0 不再返回全部
     P2 敏感词/计数接口需要登录、异常状态码、推荐去重、WS 一次性票据
   运行：node verify-fixes.js      （后端默认需要在 18080 端口起来，可用 BASE 覆盖）
      PowerShell: $env:BASE='http://127.0.0.1:8080'; node verify-fixes.js
   ========================================================================= */

const BASE = process.env.BASE || 'http://127.0.0.1:18080';
const WS_BASE = BASE.replace('http', 'ws');

let pass = 0, fail = 0;
const failures = [];
function check(name, cond, extra = '') {
    if (cond) { pass++; console.log(`  PASS  ${name}`); }
    else { fail++; failures.push(name); console.log(`  FAIL  ${name} ${extra}`); }
}
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

async function http(path, { method = 'GET', token, body } = {}) {
    const headers = {};
    if (token) headers['Authorization'] = 'Bearer ' + token;
    if (body != null) headers['Content-Type'] = 'application/json';
    const res = await fetch(BASE + path, {
        method, headers, body: body != null ? JSON.stringify(body) : undefined
    });
    const raw = await res.text();
    let data = null;
    try { data = JSON.parse(raw); } catch (e) { data = { raw }; }
    return { status: res.status, data };
}

async function login(label) {
    const phone = '139' + String(Math.floor(10000000 + Math.random() * 89999999));
    const s = await http('/api/auth/sms/send', { method: 'POST', body: { phone } });
    const l = await http('/api/auth/sms/login', {
        method: 'POST', body: { phone, code: s.data.data.mockCode }
    });
    if (l.data.code !== 0) throw new Error(`${label} 登录失败: ${JSON.stringify(l.data)}`);
    console.log(`  · ${label}: userId=${l.data.data.userId} name=${l.data.data.name}`);
    return { token: l.data.data.token, userId: l.data.data.userId, name: l.data.data.name };
}

/** 建立一个 WS 连接并收集所有消息（mode=ticket 走"一次性票据"，mode=token 走兼容的 ?token=） */
async function openWs(roomId, token, { mode = 'ticket' } = {}) {
    const url = `${WS_BASE}/ws/danmaku/${roomId}`;
    let full;
    if (mode === 'ticket') {
        const t = await http('/api/danmaku/ws-ticket', { method: 'POST', token });
        if (t.data.code !== 0) throw new Error('取票据失败: ' + JSON.stringify(t.data));
        full = `${url}?ticket=${encodeURIComponent(t.data.data.ticket)}`;
    } else {
        full = `${url}?token=${encodeURIComponent(token)}`;
    }
    const ws = new WebSocket(full);
    const got = [];
    ws.addEventListener('message', (e) => { try { got.push(JSON.parse(e.data)); } catch (_) { } });
    const opened = new Promise((res, rej) => {
        const t = setTimeout(() => rej(new Error('握手超时')), 8000);
        ws.addEventListener('open', () => { clearTimeout(t); res(true); });
        ws.addEventListener('error', () => { clearTimeout(t); rej(new Error('握手失败')); });
    });
    return { ws, got, opened };
}

async function main() {
    console.log('== 登录两个用户 ==');
    const u1 = await login('用户A');
    const u2 = await login('用户B');

    console.log('\n== P2 管理接口必须登录 ==');
    const noAuth = await http('/api/sensitive/count');
    check('未登录访问 /api/sensitive/count → 401', noAuth.status === 401, `got ${noAuth.status}`);
    const withAuth = await http('/api/sensitive/count', { token: u1.token });
    check('带 Token 访问 /api/sensitive/count → 200', withAuth.status === 200 && withAuth.data.code === 0);
    const r1 = await http('/api/sensitive/reload', { method: 'POST', token: u1.token });
    const r2 = await http('/api/sensitive/reload', { method: 'POST', token: u1.token });
    check('连续 reload 第二次被节流', r1.data.code === 0 && r2.data.code === 1, JSON.stringify(r2.data));

    const noAuthCount = await http('/api/count/1', { token: undefined });
    check('未登录访问 /api/count → 401', noAuthCount.status === 401, `got ${noAuthCount.status}`);

    console.log('\n== P2 异常状态码不再一律 200 ==');
    const badAvatar = await http('/api/user/register', {
        method: 'POST',
        body: { username: 'x' + Date.now(), password: 'pw123456', name: 'n', avatar: 'javascript:alert(1)' }
    });
    check('参数校验失败 → 400', badAvatar.status === 400, `got ${badAvatar.status}`);
    const wrongMethod = await http('/api/user/login');
    check('方法不支持 → 405', wrongMethod.status === 405, `got ${wrongMethod.status}`);
    const notFound = await http('/api/does-not-exist');
    check('路径不存在 → 404', notFound.status === 404, `got ${notFound.status}`);

    console.log('\n== P0-4 unlike 不存在的视频 ==');
    const unlikeGhost = await http('/api/video/99999999/like', { method: 'DELETE', token: u1.token });
    check('取消点赞不存在的视频被拒（不再凭空写 Redis key）',
        unlikeGhost.data.code === 1 && String(unlikeGhost.data.msg).includes('视频不存在'),
        JSON.stringify(unlikeGhost.data));

    console.log('\n== P0-1 创建直播间不接受客户端 id ==');
    const a = await http('/api/live/rooms', {
        method: 'POST', token: u1.token,
        body: { title: 'A 房间', category: '游戏', tags: 't' }
    });
    const roomA = a.data.data;
    check('创建成功且 id 由服务端生成', a.data.code === 0 && roomA != null, JSON.stringify(a.data));
    check('id 在 JS 安全整数范围内（不会在前端被精度截断）',
        Number.isSafeInteger(roomA), `id=${roomA}`);

    // 给 A 攒人气：3 + 2 = 5
    await http(`/api/live/${roomA}/like`, { method: 'POST', token: u1.token, body: { delta: 3 } });
    await http(`/api/live/${roomA}/like`, { method: 'POST', token: u1.token, body: { delta: 2 } });
    const likeBefore = await http(`/api/live/${roomA}/like`, { token: u1.token });
    check('A 房间人气 = 5', likeBefore.data.data === 5, JSON.stringify(likeBefore.data));

    // 关键攻击面：把 A 的 id 塞进创建请求
    const b = await http('/api/live/rooms', {
        method: 'POST', token: u1.token,
        body: { id: roomA, title: 'B 房间', category: '音乐', likeCount: 99999, onlineCount: 88888 }
    });
    const roomB = b.data.data;
    check('创建成功（没有因主键冲突报错）', b.data.code === 0, JSON.stringify(b.data));
    check('新房间 id ≠ 被注入的 id（id 注入失效）', roomB !== roomA, `${roomB} vs ${roomA}`);
    check('新房间人气没有被客户端写成 99999',
        (await http(`/api/live/${roomB}/like`, { token: u1.token })).data.data === 0);

    // P0-5：创建 B 会把 A 强制下播，A 的人气必须被结算写回数据库
    const likeAAfter = await http(`/api/live/${roomA}/like`, { token: u1.token });
    check('A 被强制下播后人气已结算写回（Redis key 删掉后仍读到 5）',
        likeAAfter.data.data === 5, `got ${likeAAfter.data.data}`);

    console.log('\n== P0-6 在线人数（ZSet + 心跳） ==');
    await http(`/api/live/${roomB}/enter`, { method: 'POST', token: u1.token });
    await http(`/api/live/${roomB}/enter`, { method: 'POST', token: u2.token });
    let online = await http(`/api/live/${roomB}/online`);
    check('两名用户进房后在线 = 2', online.data.data === 2, JSON.stringify(online.data));
    const hb = await http(`/api/live/${roomB}/heartbeat`, { method: 'POST', token: u1.token });
    check('心跳接口可用', hb.data.code === 0, JSON.stringify(hb.data));
    await http(`/api/live/${roomB}/leave`, { method: 'POST', token: u2.token });
    online = await http(`/api/live/${roomB}/online`);
    check('用户B 离开后在线 = 1', online.data.data === 1, JSON.stringify(online.data));

    console.log('\n== P1-13 礼物榜边界 ==');
    const giftNaN = await http(`/api/live/${roomB}/gift?value=NaN`, { method: 'POST', token: u1.token });
    check('NaN 礼物价值被拒', giftNaN.data.code === 1, JSON.stringify(giftNaN.data));
    const giftHuge = await http(`/api/live/${roomB}/gift?value=999999999999`, { method: 'POST', token: u1.token });
    check('超大礼物价值被拒', giftHuge.data.code === 1, JSON.stringify(giftHuge.data));
    await http(`/api/live/${roomB}/gift?value=100`, { method: 'POST', token: u1.token });
    await http(`/api/live/${roomB}/gift?value=50`, { method: 'POST', token: u2.token });
    const top0 = await http(`/api/live/${roomB}/gift/rank?top=0`);
    const top10 = await http(`/api/live/${roomB}/gift/rank?top=10`);
    check('top=0 被收敛为 1 条（不再返回整张榜）',
        Array.isArray(top0.data.data) && top0.data.data.length === 1, JSON.stringify(top0.data.data));
    check('top=10 正常返回', Array.isArray(top10.data.data), JSON.stringify(top10.data));

    console.log('\n== P2 REST 发弹幕的昵称不可伪造 ==');
    const dm = await http('/api/danmaku', {
        method: 'POST', token: u2.token,
        body: { roomId: roomB, content: '正常一条弹幕', nickname: '主播本人', color: '#ff0000' }
    });
    check('REST 弹幕的昵称取服务端值（忽略前端传的"主播本人"）',
        dm.data.code === 0 && dm.data.data.nickname === u2.name,
        JSON.stringify(dm.data && dm.data.data));

    console.log('\n== P0-2/P0-3 WebSocket：票据、昵称、长度、颜色、限速 ==');
    const client = await openWs(roomB, u1.token, { mode: 'ticket' });
    try {
        await client.opened;
        check('WS 用一次性票据握手成功', true);

        // 伪造昵称
        client.ws.send(JSON.stringify({ nickname: '主播', content: 'ws 正常弹幕', color: '#00AEEC' }));
        await sleep(600);
        const chat = client.got.filter(m => m.type === 'CHAT').pop();
        check('WS 广播的昵称是服务端值（不是伪造的"主播"）',
            chat && chat.data.nickname === u1.name, JSON.stringify(chat && chat.data));

        // 非法颜色被归一化
        client.ws.send(JSON.stringify({ content: '颜色 injection', color: 'red;}body{display:none' }));
        await sleep(600);
        const c2 = client.got.filter(m => m.type === 'CHAT').pop();
        check('非法颜色被归一化为 #ffffff', c2 && c2.data.color === '#ffffff', JSON.stringify(c2 && c2.data));

        // 超长内容
        client.ws.send(JSON.stringify({ content: 'x'.repeat(300) }));
        await sleep(600);
        const sysLong = client.got.filter(m => m.type === 'SYSTEM').map(m => String(m.data)).join('|');
        check('超长弹幕被拦（提示而不是数据库报错）', sysLong.includes('不能超过 200'), sysLong);

        // 敏感词仍然被拦
        client.ws.send(JSON.stringify({ content: 'fuck you' }));
        await sleep(600);
        const sysBad = client.got.filter(m => m.type === 'SYSTEM').map(m => String(m.data)).join('|');
        check('敏感词弹幕被拒且有 SYSTEM 提示', sysBad.includes('发送失败'), sysBad);
        check('敏感词弹幕没有被广播',
            !client.got.some(m => m.type === 'CHAT' && m.data && String(m.data.content).includes('fuck')));

        // 限速：一口气发 10 条
        for (let i = 0; i < 10; i++) client.ws.send(JSON.stringify({ content: 'burst ' + i }));
        await sleep(1200);
        const sysRate = client.got.filter(m => m.type === 'SYSTEM').map(m => String(m.data)).join('|');
        check('突发 10 条触发限速提示', sysRate.includes('发得太快'), sysRate);
    } finally {
        client.ws.close();
    }

    // 兼容：query 传 token 仍然可用（给 ws-test.js 这类脚本）
    const legacy = await openWs(roomB, u1.token, { mode: 'token' });
    try {
        await legacy.opened;
        check('WS 用 ?token= 兼容方式握手成功', true);
    } finally {
        legacy.ws.close();
    }

    console.log('\n== P1-10 推荐结果不重复 ==');
    await http('/api/video/1/like', { method: 'POST', token: u1.token });
    await http('/api/video/2/like', { method: 'POST', token: u1.token });
    const rec = await http('/api/recommend?size=20', { token: u1.token });
    const ids = (rec.data.data || []).map(v => v.id);
    check('推荐列表有数据', ids.length > 0, `size=${ids.length}`);
    check('推荐列表无重复', new Set(ids).size === ids.length, JSON.stringify(ids));

    console.log('\n================ 结果 ================');
    console.log(`通过 ${pass} 项，失败 ${fail} 项`);
    if (fail > 0) {
        console.log('失败项：\n  - ' + failures.join('\n  - '));
        process.exitCode = 1;
    }
    // 留给"在线人数回写"任务一点时间（30s 一轮）
    console.log(`\n测试房间：A=${roomA} B=${roomB}（B 房间在线 1 人，用于验证 online_count 回写）`);
}

main().catch(e => { console.error('脚本异常：', e); process.exit(1); });
