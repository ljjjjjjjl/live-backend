/* =========================================================
   直播间页逻辑（与视频功能已拆分为两个页面）
   接口：
     GET  /api/live/recommend?userId=&size=               推荐直播间（公开）
     GET  /api/live/rooms?pageNum=&pageSize=&category=    直播间列表（公开，分页）
     POST /api/live/rooms                                创建直播间（需 Token）
     POST /api/live/rooms/{id}/start|stop                 开播 / 关播（需 Token）
   ========================================================= */

App.renderNav('live');
App.flushLog();

const $ = (id) => document.getElementById(id);

const CATEGORIES = ['游戏', '知识', '美食', '影视', '音乐', '舞蹈', '娱乐', '户外'];
const MY_ROOM_KEY = 'ls_my_room';        // { id, title, category, living }

const state = {
    category: '',
    page: 1,
    pageSize: 12,
    pages: 1,
    cateCounts: {}      // { '': 总数, '游戏': 3, ... }
};

/* ==================== 分区 Tab ==================== */
/**
 * 拉取各分区的房间数量，用于在 Tab 上显示"点了有没有生效"。
 *
 * <p>演示数据只有十几个房间，所以这里直接拉全量在客户端 GROUP BY。
 * 真实项目应该让后端出一个聚合接口（`SELECT category, COUNT(*) ... GROUP BY category`）。
 */
async function loadCategoryCounts() {
    const all = await App.fetchAllRooms();       // 自动翻页，别用 pageSize=200（后端 @Max(50) 会拒）
    const counts = {};
    all.forEach(r => {
        const c = r.category || '未分类';
        counts[c] = (counts[c] || 0) + 1;
    });
    counts[''] = all.length;
    state.cateCounts = counts;
}

/**
 * 渲染分区 Tab。
 *
 * <p>Tab 列表 =「全部」+ 预置分区 + <b>数据里实际出现过的分区</b>，
 * 这样即使某个房间用了预置列表之外的分区，也能被筛出来（不会有点不到的房间）。
 */
function renderCateTabs() {
    const box = $('cateTabs');
    box.innerHTML = '';

    const present = Object.keys(state.cateCounts).filter(k => k !== '');
    const names = ['全部', ...new Set([...CATEGORIES, ...present])];

    names.forEach(name => {
        const value = name === '全部' ? '' : name;
        const count = state.cateCounts[value];

        const b = document.createElement('button');
        b.textContent = count == null ? name : `${name} ${count}`;
        if (value === state.category) b.className = 'on';
        if (count === 0 && value !== '') b.style.opacity = '.5';

        b.addEventListener('click', () => {
            state.category = value;
            state.page = 1;
            renderCateTabs();
            loadLiveRooms();
        });
        box.appendChild(b);
    });

    // 创建直播间时的分区下拉（用完整预置列表，不受筛选影响）
    const sel = $('newCategory');
    const keep = sel.value;
    sel.innerHTML = '';
    CATEGORIES.forEach(c => {
        const o = document.createElement('option');
        o.value = c; o.textContent = c;
        sel.appendChild(o);
    });
    if (keep) sel.value = keep;
}

/** 房间数据变了（新建/开播/关播）→ 数量统计和列表一起刷新 */
async function refreshList() {
    await loadCategoryCounts();
    renderCateTabs();
    await loadLiveRooms();
}

/* ==================== 通用 ==================== */
function fillGrid(boxId, list, builder, emptyText) {
    const box = $(boxId);
    box.innerHTML = '';
    if (!list || list.length === 0) {
        box.innerHTML = `<div class="empty" style="grid-column:1/-1;"><span class="big">📭</span>${App.escapeHtml(emptyText)}</div>`;
        return;
    }
    list.forEach(item => box.appendChild(builder(item)));
}

const ROOM_TILE = {
    '游戏': ['#FFE9F0', '#FB7299'],
    '知识': ['#E8F3FF', '#3D7EFF'],
    '美食': ['#FFF1E6', '#FF8A3D'],
    '影视': ['#F0EAFF', '#7B5BFF'],
    '音乐': ['#E6FBF4', '#12B886'],
    '舞蹈': ['#FFF6E0', '#E8A33D'],
    '娱乐': ['#FFEFF7', '#E8579B'],
    '户外': ['#EAF7EC', '#3AA655']
};

function buildRoomCard(r) {
    const card = document.createElement('div');
    card.className = 'rcard';
    card.addEventListener('click', () => location.href = '/room.html?id=' + r.id);

    const cover = document.createElement('div');
    cover.className = 'cover';
    const [bg, fg] = ROOM_TILE[r.category] || ['#EDEFF2', '#8A93A3'];
    if (r.isLiving === 1) {
        cover.setAttribute('style', `background:${bg};color:${fg};`);
    } else {
        cover.className = 'cover off';
    }
    cover.textContent = r.category || '直播间';

    if (r.isLiving === 1) {
        const dot = document.createElement('span');
        dot.className = 'live-dot';
        dot.textContent = '直播中';
        cover.appendChild(dot);
    }
    const online = document.createElement('span');
    online.className = 'online';
    online.textContent = App.shortNum(r.onlineCount) + ' 人在看';
    cover.appendChild(online);

    const body = document.createElement('div');
    body.className = 'body';

    const title = document.createElement('div');
    title.className = 'title';
    title.textContent = r.title || ('直播间 #' + r.id);

    const foot = document.createElement('div');
    foot.className = 'foot';
    const meta = document.createElement('span');
    meta.className = 'meta';
    meta.textContent = r.reason || (r.isLiving === 1 ? '直播中' : '已下播');

    const enter = document.createElement('span');
    enter.className = 'pill pink';
    enter.textContent = '进入';

    foot.append(meta, enter);
    body.append(title, foot);
    card.append(cover, body);
    return card;
}

/* ==================== 加载 ==================== */
async function loadRecommendRooms() {
    const userId = App.isLogin() ? App.user.userId : '';
    const { data } = await App.api(`/api/live/recommend?userId=${userId}&size=6`);
    if (data.code !== 0) {
        fillGrid('roomGrid', [], null, '推荐加载失败：' + (data.msg || ''));
        return;
    }
    const list = data.data || [];
    $('roomMore').textContent = '按在线人数 + 人气排序';
    $('roomStat').textContent = App.isLogin()
        ? `${list.length} 条 · 已按你的观看偏好召回（${App.user.name || App.user.username}）`
        : `${list.length} 条 · 未登录展示全站热门`;
    fillGrid('roomGrid', list, buildRoomCard, '暂无推荐直播间');
}

async function loadLiveRooms() {
    const q = `pageNum=${state.page}&pageSize=${state.pageSize}` +
        (state.category ? `&category=${encodeURIComponent(state.category)}` : '');
    const { data } = await App.api('/api/live/rooms?' + q);
    if (data.code !== 0) {
        fillGrid('liveGrid', [], null, '加载失败：' + (data.msg || ''));
        return;
    }
    const page = data.data || {};
    const records = page.records || [];
    state.pages = page.pages || 1;

    $('listStat').textContent =
        `共 ${page.total || 0} 个${state.category ? '「' + state.category + '」' : ''}直播间`;
    $('pageInfo').textContent = `第 ${page.current || state.page} / ${state.pages} 页`;
    $('prevPage').disabled = (page.current || 1) <= 1;
    $('nextPage').disabled = (page.current || 1) >= state.pages;

    fillGrid('liveGrid', records, buildRoomCard, '该分区暂无直播中的直播间');
}

/* ==================== 我的直播间 ==================== */
function readMyRoom() {
    try { return JSON.parse(localStorage.getItem(MY_ROOM_KEY) || 'null'); } catch (e) { return null; }
}
function writeMyRoom(v) { localStorage.setItem(MY_ROOM_KEY, JSON.stringify(v)); }

function renderMyRoom() {
    const section = $('myRoomSection');
    if (!App.isLogin()) { section.classList.add('hidden'); return; }
    section.classList.remove('hidden');

    const room = readMyRoom();
    $('createBox').classList.toggle('hidden', !!room);
    $('manageBox').classList.toggle('hidden', !room);

    if (!room) {
        $('myRoomHint').textContent = '还没有直播间，创建一个试试';
        return;
    }
    $('myRoomHint').textContent = '创建 / 开播 / 关播（同一主播同时只能有一个直播中）';
    $('myRoomId').textContent = 'roomId ' + room.id;
    $('myRoomTitle').textContent = room.title || '';

    const st = $('myRoomState');
    st.textContent = room.living ? '直播中' : '已下播';
    st.className = 'pill ' + (room.living ? 'green' : '');

    $('enterMyRoom').href = '/room.html?id=' + room.id;
    $('enterMyRoom').className = room.living ? 'btn primary' : 'btn';
}

$('createRoomBtn').addEventListener('click', async () => {
    if (!App.guard()) return;
    const title = $('newTitle').value.trim();
    const category = $('newCategory').value;
    const tags = $('newTags').value.trim();

    if (!title) return App.toast('请填写直播标题', 'warn');

    App.setLoading($('createRoomBtn'), true, '创建中');
    const { data } = await App.api('/api/live/rooms', {
        method: 'POST', auth: true,
        body: { title, category, tags }
    });
    App.setLoading($('createRoomBtn'), false);

    if (data.code !== 0) return App.toast(data.msg || '创建失败', 'error');

    writeMyRoom({ id: data.data, title, category, living: true });
    App.toast(`直播间已创建（roomId=${data.data}），已自动开播`, 'success', '创建成功');
    renderMyRoom();
    refreshList();
});

$('startBtn').addEventListener('click', () => roomAction('start'));
$('stopBtn').addEventListener('click', () => roomAction('stop'));

async function roomAction(action) {
    if (!App.guard()) return;
    const room = readMyRoom();
    if (!room) return;

    const btn = action === 'start' ? $('startBtn') : $('stopBtn');
    App.setLoading(btn, true, action === 'start' ? '开播中' : '关播中');
    const { data } = await App.api(`/api/live/rooms/${room.id}/${action}`, { method: 'POST', auth: true });
    App.setLoading(btn, false);

    if (data.code !== 0) return App.toast(data.msg || '操作失败', 'error');

    room.living = action === 'start';
    writeMyRoom(room);
    renderMyRoom();
    App.toast(action === 'start' ? '已开播' : '已关播', 'success');
    refreshList();
}

$('forgetRoomBtn').addEventListener('click', () => {
    localStorage.removeItem(MY_ROOM_KEY);
    renderMyRoom();
    App.toast('已清除本地记录（数据库里的直播间还在）', 'info');
});

/* ==================== 分页 ==================== */
$('prevPage').addEventListener('click', () => {
    if (state.page > 1) {
        state.page--;
        loadLiveRooms();
        window.scrollTo({ top: $('live').offsetTop - 70, behavior: 'smooth' });
    }
});
$('nextPage').addEventListener('click', () => {
    if (state.page < state.pages) {
        state.page++;
        loadLiveRooms();
        window.scrollTo({ top: $('live').offsetTop - 70, behavior: 'smooth' });
    }
});

/* ==================== 事件 ==================== */
$('reloadRecommend').addEventListener('click', loadRecommendRooms);
$('clearLog').addEventListener('click', (e) => { e.preventDefault(); $('logBox').textContent = ''; });

/* ==================== 初始化 ==================== */
(async function init() {
    renderMyRoom();
    await loadCategoryCounts();   // 先把各分区数量拿到，Tab 上才能显示"游戏 3"
    renderCateTabs();

    await Promise.all([loadRecommendRooms(), loadLiveRooms()]);

    if (!App.isLogin()) {
        App.log('ℹ 未登录：可以浏览直播间列表和在线人数，但进入直播间后不能发弹幕/送礼', 'sys');
    }
})();
