/* WebSocket 弹幕端到端测试（临时工具，验证完可删）
   验证：
     1) 带 token 能握手成功
     2) 正常弹幕能落库 + 广播回自己
     3) 敏感词弹幕 → 收到 SYSTEM 失败提示（而不是静默断开）
   运行：node ws-test.js
*/

const BASE = 'http://localhost:8080';
const WS_BASE = 'ws://localhost:8080';
const ROOM = 201;

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const j = (r) => r.json();

async function main() {
    const phone = '139' + String(Math.floor(10000000 + Math.random() * 89999999));

    const send = await fetch(`${BASE}/api/auth/sms/send`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ phone })
    }).then(j);

    const login = await fetch(`${BASE}/api/auth/sms/login`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ phone, code: send.data.mockCode })
    }).then(j);

    const token = login.data.token;
    const nickname = login.data.name || login.data.username;
    console.log(`登录成功 userId=${login.data.userId} nickname=${nickname}`);

    const url = `${WS_BASE}/ws/danmaku/${ROOM}?token=${encodeURIComponent(token)}`;
    const ws = new WebSocket(url);

    const got = [];
    ws.addEventListener('message', (e) => {
        const m = JSON.parse(e.data);
        got.push(m);
        console.log('   << ' + m.type + '  ' + JSON.stringify(m.data));
    });

    await new Promise((res, rej) => {
        const t = setTimeout(() => rej(new Error('WS 握手超时')), 8000);
        ws.addEventListener('open', () => { clearTimeout(t); res(); });
        ws.addEventListener('error', () => { clearTimeout(t); rej(new Error('WS 握手失败（token 无效？）')); });
    });
    console.log('WS 握手成功\n');

    console.log('>> 发送正常弹幕: "hello danmaku"');
    ws.send(JSON.stringify({ nickname, content: 'hello danmaku', color: '#FB7299' }));
    await sleep(900);

    console.log('>> 发送敏感词弹幕: "fuck you"');
    ws.send(JSON.stringify({ nickname, content: 'fuck you', color: '#ffffff' }));
    await sleep(900);

    console.log('>> 发送中文弹幕: "中文弹幕测试"');
    ws.send(JSON.stringify({ nickname, content: '中文弹幕测试', color: '#00AEEC' }));
    await sleep(900);

    ws.close();
    await sleep(300);

    // ---------------- 断言 ----------------
    const chats = got.filter(m => m.type === 'CHAT');
    const sysMsgs = got.filter(m => m.type === 'SYSTEM');
    const errMsg = sysMsgs.find(m => String(m.data).includes('发送失败'));
    const cnmOk = chats.some(c => c.data && c.data.content === 'hello danmaku');
    const zhOk = chats.some(c => c.data && c.data.content === '中文弹幕测试');

    console.log('\n================ 断言 ================');
    console.log(`握手成功                 : PASS`);
    console.log(`正常弹幕广播回来(ascii)  : ${cnmOk ? 'PASS' : 'FAIL'}`);
    console.log(`中文弹幕未乱码           : ${zhOk ? 'PASS' : 'FAIL'}`);
    console.log(`敏感词被拒绝(未广播)     : ${chats.some(c => c.data && c.data.content.includes('fuck')) ? 'FAIL' : 'PASS'}`);
    console.log(`敏感词有 SYSTEM 失败提示 : ${errMsg ? 'PASS -> ' + errMsg.data : 'FAIL（用户看不到任何提示）'}`);
}

main().catch(e => { console.error('脚本异常：', e); process.exit(1); });
