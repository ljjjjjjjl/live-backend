/* =========================================================
   直播间页逻辑
   接口：
     GET  /api/live/rooms?pageSize=50              从列表里找本房间信息（无单房间详情接口）
     GET  /api/live/{roomId}/online               在线人数（Redis SCARD）
     GET  /api/live/{roomId}/like                 当前人气（Redis INCRBY 计数）
     POST /api/live/{roomId}/like  {"delta":N}    点赞/人气累加（需 Token，可连点、有每秒限速）
     GET  /api/live/{roomId}/gift/rank?top=10     礼物榜（Redis ZSet）
     POST /api/live/{roomId}/enter               进入（在线+1，需 Token；身份取 Token）
     POST /api/live/{roomId}/leave               离开（在线-1，需 Token；身份取 Token）
     POST /api/live/{roomId}/gift?value=         送礼（需 Token；收礼者=本房间主播，服务端推导）
     GET  /api/danmaku?roomId=&pageNum=&pageSize= 历史弹幕
     WS   /ws/danmaku/{roomId}?token=            实时弹幕（握手校验 Token）
   ========================================================= */

App.renderNav('live');
App.flushLog();

const $ = (id) => document.getElementById(id);
const roomId = App.qs('id');
const MAX_DM = 200;          // DOM 里最多保留的弹幕条数

let ws = null;
let entered = false;         // 是否已 enter（避免重复加入在线集合）
let dmCount = 0;
let currentRoom = null;      // 列表里查到的本房间信息（含 anchorId，送礼时用来展示收礼者）

/* ==================== 房间信息 ==================== */
async function loadRoomInfo() {
    $('roomIdPill').textContent = 'roomId ' + roomId;

    // 没有"单房间详情"接口，只能从列表里找（自动翻页，规避后端 pageSize ≤ 50 的限制）
    const records = await App.fetchAllRooms();
    const room = records.find(r => String(r.id) === String(roomId));

    if (!room) {
        $('roomTitle').textContent = '直播间 #' + roomId + '（列表里没找到，可能已下播）';
        $('liveDot').style.display = 'none';
        return null;
    }

    $('roomTitle').textContent = room.title || ('直播间 #' + room.id);
    $('roomCatePill').textContent = room.category || '未分类';
    $('scorePill').textContent = '热度 ' + App.shortNum(room.liveScore);
    $('playerTxt').textContent = (room.category || '') + ' · 画面占位';
    $('liveDot').style.display = room.isLiving === 1 ? '' : 'none';

    // ⭐ 收礼者 = 本房间主播：anchorId 现在由后端 VO 下发，不用再让用户手填 userId
    currentRoom = room;
    if ($('giftReceiver')) $('giftReceiver').value = room.anchorId != null ? room.anchorId : '';
    return room;
}

/* ==================== 在线人数 ==================== */
async function refreshOnline() {
    const { data } = await App.api(`/api/live/${roomId}/online`, { silent: true });
    if (data.code === 0) {
        const n = data.data;
        $('onlinePill').textContent = '在线 ' + n;
        $('onlinePill2').textContent = n + ' 人';
    }
}

/* ==================== 人气（直播间点赞） ==================== */
let pendingLike = 0;         // 本地攒着、还没上报的点赞数
let likeTotal = 0;           // 服务端返回的权威人气

function renderLike() {
    $('likePill').textContent = App.shortNum(likeTotal + pendingLike);
}

async function refreshLike() {
    const { data } = await App.api(`/api/live/${roomId}/like`, { silent: true });
    if (data.code === 0) {
        likeTotal = data.data || 0;
        renderLike();
    }
}

// 点击：只在本地 +1（立刻有反馈），真正的请求交给 500ms 的定时器合并上报
function likeRoom() {
    if (!App.isLogin()) return App.toast('登录后才能点赞', 'warn', '未登录');
    pendingLike++;
    renderLike();
}

// 攒批上报：100 次点击 → 1 次请求（服务端一次 INCRBY delta）
async function flushLike() {
    if (pendingLike <= 0) return;
    const delta = pendingLike;
    pendingLike = 0;                       // 先清零再发请求，避免定时器重入把同一批发两次
    const { data } = await App.api(`/api/live/${roomId}/like`,
        { method: 'POST', body: { delta }, auth: true, silent: true });
    if (data.code === 0) {
        likeTotal = data.data || 0;
    } else {
        // 被限速（或其它失败）时把这批丢掉、不重试：继续攒着只会一直撞限速
        App.toast(data.msg || '点赞失败', 'warn');
    }
    renderLike();
}

/* ==================== 礼物榜 ==================== */
async function loadRank() {
    const { data } = await App.api(`/api/live/${roomId}/gift/rank?top=10`, { silent: true });
    const box = $('rankList');
    box.innerHTML = '';

    if (data.code !== 0 || !data.data || data.data.length === 0) {
        box.innerHTML = '<div class="muted" style="font-size:13px;">还没有人送礼，做第一个吧～</div>';
        return;
    }

    data.data.forEach((item, i) => {
        const row = document.createElement('div');
        row.className = 'rank-item';

        const no = document.createElement('span');
        no.className = 'rank-no' + (i < 3 ? ' top' + (i + 1) : '');
        no.textContent = i + 1;

        const name = document.createElement('span');
        name.textContent = '主播 #' + item.userId;

        const val = document.createElement('span');
        val.className = 'val';
        val.textContent = App.shortNum(item.value);

        row.append(no, name, val);
        box.appendChild(row);
    });
}

/* ==================== 弹幕渲染 ==================== */
function pushDanmaku(text, who, color, isSys) {
    const list = $('dmList');
    const el = document.createElement('div');
    el.className = 'dm-item' + (isSys ? ' sys' : '');

    if (who) {
        const w = document.createElement('span');
        w.className = 'who';
        w.textContent = who + '：';
        el.appendChild(w);
    }
    const c = document.createElement('span');
    c.textContent = text;
    if (!isSys && color) c.style.color = color;
    el.appendChild(c);

    list.appendChild(el);

    // 控制 DOM 规模，避免长时间挂机后卡顿
    while (list.children.length > MAX_DM) list.removeChild(list.firstElementChild);
    list.scrollTop = list.scrollHeight;

    dmCount++;
    $('dmStat').textContent = `已收 ${dmCount} 条`;
}

async function loadHistory() {
    const { data } = await App.api(`/api/danmaku?roomId=${roomId}&pageNum=1&pageSize=30`);
    if (data.code !== 0) return;
    const records = (data.data && data.data.records) || [];

    // 服务端顺序不确定，按 id 升序排一遍，保证左侧是旧、右侧是新
    records.sort((a, b) => (a.id || 0) - (b.id || 0));

    if (records.length === 0) {
        pushDanmaku('还没有人发过弹幕，来抢沙发～', null, null, true);
        return;
    }
    records.forEach(d => pushDanmaku(d.content, d.nickname, d.color, false));
}

/* ==================== WebSocket ==================== */
function connectWs() {
    if (!App.isLogin()) {
        $('wsPill').textContent = '未登录 · 不能发弹幕';
        $('wsPill').className = 'pill';
        pushDanmaku('未登录可以看历史弹幕，但连不上实时通道（握手需要 Token）', null, null, true);
        return;
    }

    const proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
    const url = `${proto}${location.host}/ws/danmaku/${roomId}?token=${encodeURIComponent(App.token)}`;

    ws = new WebSocket(url);

    ws.onopen = () => {
        $('wsPill').textContent = '弹幕已连接';
        $('wsPill').className = 'pill green';
        App.log('WS 已连接 ' + url.replace(/token=.*/, 'token=***'), 'ok');
    };

    ws.onmessage = (evt) => {
        let msg;
        try { msg = JSON.parse(evt.data); } catch (e) { return; }

        if (msg.type === 'CHAT') {
            const d = msg.data || {};
            pushDanmaku(d.content, d.nickname, d.color, false);
        } else if (msg.type === 'SYSTEM') {
            pushDanmaku(msg.data, null, null, true);
        } else if (msg.type === 'ONLINE') {
            $('onlinePill').textContent = '在线 ' + msg.data;
            $('onlinePill2').textContent = msg.data + ' 人';
        }
    };

    ws.onclose = () => {
        $('wsPill').textContent = '弹幕已断开';
        $('wsPill').className = 'pill';
    };

    ws.onerror = () => {
        $('wsPill').textContent = '弹幕连接失败';
        $('wsPill').className = 'pill';
    };
}

function sendDanmaku() {
    if (!App.guard()) return;
    if (!ws || ws.readyState !== WebSocket.OPEN) {
        return App.toast('弹幕通道未连接', 'warn');
    }
    const input = $('dmInput');
    const content = input.value.trim();
    if (!content) return App.toast('弹幕内容不能为空', 'warn');

    ws.send(JSON.stringify({
        nickname: (App.user && (App.user.name || App.user.username)) || '游客',
        content,
        color: $('dmColor').value
    }));
    input.value = '';
    input.focus();
}

/* ==================== 进入 / 离开 ==================== */
async function enterRoom(silent) {
    if (!App.isLogin()) {
        if (!silent) App.toast('登录后才能加入在线列表', 'warn', '未登录');
        return;
    }
    // ⭐ 身份取 Token（不再传 userId）：后端从 Authorization 解析，前端传了也不作数
    const { data } = await App.api(`/api/live/${roomId}/enter`,
        { method: 'POST', auth: true, silent });
    if (data.code === 0) {
        entered = true;
        if (!silent) App.toast('已加入在线列表（Redis SADD）', 'success');
        refreshOnline();
    }
}

async function leaveRoom(silent) {
    if (!App.isLogin() || !entered) return;
    const { data } = await App.api(`/api/live/${roomId}/leave`,
        { method: 'POST', auth: true, silent });
    if (data.code === 0) {
        entered = false;
        if (!silent) App.toast('已离开（Redis SREM）', 'info');
        refreshOnline();
    }
}

/* ==================== 送礼 ==================== */
async function sendGift() {
    if (!App.guard()) return;
    const value = $('giftValue').value;
    // 收礼者由【服务端从房间推导】= 本房间主播；这里只在本地确认一下，不再由用户填
    const anchorId = currentRoom && currentRoom.anchorId;
    if (!anchorId) return App.toast('还没拿到主播信息，刷新页面再试', 'warn');

    App.setLoading($('giftBtn'), true, '送出中');
    const { data } = await App.api(
        `/api/live/${roomId}/gift?value=${value}`,
        { method: 'POST', auth: true });
    App.setLoading($('giftBtn'), false);

    if (data.code !== 0) return App.toast(data.msg || '送礼失败', 'error');
    App.toast(`已送出价值 ${value} 的礼物`, 'success', '送礼成功');
    loadRank();
}

/* ==================== 事件绑定 ==================== */
$('sendDmBtn').addEventListener('click', sendDanmaku);
$('dmInput').addEventListener('keydown', (e) => { if (e.key === 'Enter') sendDanmaku(); });
$('enterBtn').addEventListener('click', () => enterRoom(false));
$('leaveBtn').addEventListener('click', () => leaveRoom(false));
$('giftBtn').addEventListener('click', sendGift);
$('likeBtn').addEventListener('click', likeRoom);
$('refreshRank').addEventListener('click', loadRank);
$('clearLog').addEventListener('click', (e) => { e.preventDefault(); $('logBox').textContent = ''; });

// 关页面时：补发还没上报的点赞 + 自动离开在线列表（keepalive 让请求在卸载后仍能发出）
window.addEventListener('beforeunload', () => {
    if (!App.isLogin()) return;
    if (pendingLike > 0) {
        fetch(`/api/live/${roomId}/like`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + App.token },
            body: JSON.stringify({ delta: pendingLike }),
            keepalive: true
        });
        pendingLike = 0;
    }
    if (!entered) return;
    fetch(`/api/live/${roomId}/leave`, {
        method: 'POST',
        headers: { Authorization: 'Bearer ' + App.token },
        keepalive: true
    });
});

/* ==================== 初始化 ==================== */
(async function init() {
    if (!roomId) {
        $('roomTitle').textContent = '缺少 roomId 参数';
        return;
    }
    if (!App.isLogin()) {
        $('giftHint').textContent = '未登录：不能送礼 / 不能发弹幕（历史弹幕可看）';
    }

    await loadRoomInfo();
    await Promise.all([refreshOnline(), refreshLike(), loadRank(), loadHistory()]);

    connectWs();
    await enterRoom(true);          // 静默加入在线列表

    setInterval(refreshOnline, 5000);   // 定时刷新在线人数
    setInterval(flushLike, 500);        // 人气：每 500ms 把本地攒的点赞合并上报一次
})();
