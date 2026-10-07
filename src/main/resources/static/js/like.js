/* =========================================================
   点赞并发压测页
   接口：
     GET    /api/recommend?userId=&size=     推荐视频（公开）
     GET    /api/video/liked-ids             我赞过的视频（需 Token）
     POST   /api/video/{id}/like             点赞（幂等，需 Token）
     DELETE /api/video/{id}/like             取消点赞（幂等，需 Token）
   ========================================================= */

App.renderNav('like-demo');
App.flushLog();

const $ = (id) => document.getElementById(id);
const likedIds = new Set();
const cards = new Map();       // videoId -> { btn, el }

$('whoAmI').textContent = App.isLogin()
    ? `当前登录：${App.user.name || App.user.username}（userId ${App.user.userId}）`
    : '未登录 —— 只能浏览，点赞会返回 401';

/* ==================== 卡片 ==================== */
function paint(btn, liked, count) {
    btn.classList.toggle('on', !!liked);
    btn.textContent = (liked ? '♥ ' : '♡ ') + (count == null ? 0 : count);
}

function buildCard(v) {
    const card = document.createElement('div');
    card.className = 'vcard';

    const cover = document.createElement('div');
    cover.className = 'cover';
    cover.textContent = v.category || '未分类';
    const tag = document.createElement('span');
    tag.className = 'tag';
    tag.textContent = v.reason || '推荐';
    cover.appendChild(tag);

    const body = document.createElement('div');
    body.className = 'body';
    const title = document.createElement('div');
    title.className = 'title';
    title.textContent = v.title || ('视频 #' + v.id);

    const foot = document.createElement('div');
    foot.className = 'foot';
    const meta = document.createElement('span');
    meta.className = 'meta';
    meta.textContent = 'ID ' + v.id;

    const btn = document.createElement('button');
    btn.className = 'like-btn';
    paint(btn, likedIds.has(v.id), v.likeCount);
    btn.addEventListener('click', () => toggle(v, btn));

    foot.append(meta, btn);
    body.append(title, foot);
    card.append(cover, body);

    cards.set(v.id, { btn });
    return card;
}

async function toggle(video, btn) {
    const liked = likedIds.has(video.id);
    btn.disabled = true;
    const { data } = await App.api(`/api/video/${video.id}/like`, {
        method: liked ? 'DELETE' : 'POST', auth: true
    });
    btn.disabled = false;
    if (data.code !== 0) return App.toast(data.msg || '操作失败', 'error');

    if (data.data.liked) likedIds.add(video.id); else likedIds.delete(video.id);
    paint(btn, data.data.liked, data.data.likeCount);
}

/* ==================== 加载 ==================== */
async function load() {
    App.setLoading($('loadBtn'), true, '加载中');
    App.log('———— 加载推荐列表 ————', 'sys');

    if (App.isLogin()) {
        const { data } = await App.api('/api/video/liked-ids', { auth: true });
        if (data.code === 0) (data.data || []).forEach(id => likedIds.add(id));
    }

    const userId = App.isLogin() ? App.user.userId : '';
    const { data } = await App.api(`/api/recommend?userId=${userId}&size=12`);
    App.setLoading($('loadBtn'), false);

    const box = $('list');
    box.innerHTML = '';
    cards.clear();

    if (data.code !== 0) {
        box.innerHTML = `<div class="empty" style="grid-column:1/-1;">加载失败：${App.escapeHtml(data.msg || '')}</div>`;
        return;
    }
    const list = data.data || [];
    if (list.length === 0) {
        box.innerHTML = '<div class="empty" style="grid-column:1/-1;">没有数据（已点赞的视频会被推荐逻辑排除，换个账号试试）</div>';
        return;
    }
    list.forEach(v => box.appendChild(buildCard(v)));
    App.log(`✔ 已渲染 ${list.length} 条；其中已点赞 ${likedIds.size} 条`, 'ok');
}

/* ==================== 并发压测 ==================== */
async function stress() {
    const first = cards.keys().next();
    if (first.done) return App.toast('请先加载推荐视频', 'warn');
    if (!App.guard()) return;

    const videoId = first.value;
    const N = 10;

    App.log('———— 并发幂等测试 ————', 'sys');
    App.log(`对视频 ${first.value} 同时发 ${N} 个 POST /like`, 'sys');
    App.log('预期：唯一索引 uk_user_video 兜底，计数只 +1', 'sys');

    // Promise.all = 真正并发，不是 for 循环挨个发
    const results = await Promise.all(
        Array.from({ length: N }, () =>
            App.api(`/api/video/${videoId}/like`, { method: 'POST', auth: true, silent: true }))
    );

    const okCount = results.filter(r => r.data.code === 0).length;
    const counts = results.map(r => (r.data.data ? r.data.data.likeCount : '?'));
    const last = results[results.length - 1];

    App.log(`${N} 个并发请求 code  = ${results.map(r => r.data.code).join(',')}`);
    App.log(`${N} 个并发请求 count = ${counts.join(',')}`);
    App.log(`✔ 全部返回成功（幂等）：${okCount}/${N}`, 'ok');

    if (last.data.code === 0) {
        const ref = cards.get(videoId);
        if (ref) paint(ref.btn, last.data.data.liked, last.data.data.likeCount);
        App.toast(`10 个并发请求，最终赞数 ${last.data.data.likeCount}`, 'success', '压测完成');
    }
}

/* ==================== 事件 ==================== */
$('loadBtn').addEventListener('click', load);
$('stressBtn').addEventListener('click', stress);
$('clearLog').addEventListener('click', () => { $('logBox').textContent = ''; });

if (!App.isLogin()) App.log('ℹ 当前未登录，点赞按钮会返回 401', 'sys');
