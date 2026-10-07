/* =========================================================
   视频页逻辑（与直播间功能已拆分为两个页面）
   接口：
     GET    /api/recommend?userId=&size=   推荐视频（公开）
     GET    /api/video/liked-ids           我赞过的视频（需 Token）
     POST   /api/video/{id}/like           点赞（幂等，需 Token）
     DELETE /api/video/{id}/like           取消点赞（幂等，需 Token）
   ========================================================= */

App.renderNav('video');
App.flushLog();

const $ = (id) => document.getElementById(id);

const state = {
    likedIds: new Set(),   // 当前用户赞过的 videoId
    videos: []
};

/* ==================== 缩略图占位配色（按分类固定，视觉上更好区分） ==================== */
const TILE = {
    '知识': ['#E8F3FF', '#3D7EFF'],
    '美食': ['#FFF1E6', '#FF8A3D'],
    '影视': ['#F0EAFF', '#7B5BFF'],
    '游戏': ['#FFE9F0', '#FB7299'],
    '音乐': ['#E6FBF4', '#12B886'],
    '舞蹈': ['#FFF6E0', '#E8A33D']
};

function tileStyle(category) {
    const [bg, fg] = TILE[category] || ['#EDEFF2', '#8A93A3'];
    return `background:${bg};color:${fg};`;
}

/* ==================== 卡片 ==================== */
function paintLike(btn, liked, count) {
    btn.classList.toggle('on', !!liked);
    btn.textContent = (liked ? '♥ ' : '♡ ') + (count == null ? 0 : count);
}

function buildVideoCard(v) {
    const card = document.createElement('div');
    card.className = 'vcard';

    const cover = document.createElement('div');
    cover.className = 'cover';
    cover.setAttribute('style', tileStyle(v.category));
    cover.textContent = v.category || '未分类';

    const tag = document.createElement('span');
    tag.className = 'tag';
    tag.textContent = v.reason || '推荐';
    cover.appendChild(tag);

    const stat = document.createElement('div');
    stat.className = 'stat';
    const play = document.createElement('span');
    play.textContent = '▶ ' + App.shortNum(v.playCount);
    const heat = document.createElement('span');
    heat.textContent = '🔥 ' + App.shortNum(v.heatScore);
    stat.append(play, heat);
    cover.appendChild(stat);

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
    paintLike(btn, state.likedIds.has(v.id), v.likeCount);
    btn.addEventListener('click', () => toggleLike(v, btn));

    foot.append(meta, btn);
    body.append(title, foot);
    card.append(cover, body);
    return card;
}

/* ==================== 点赞 ==================== */
async function toggleLike(video, btn) {
    const liked = state.likedIds.has(video.id);
    btn.disabled = true;

    const { data } = await App.api(`/api/video/${video.id}/like`, {
        method: liked ? 'DELETE' : 'POST',
        auth: true
    });
    btn.disabled = false;

    if (data.code !== 0) {
        App.toast(data.msg || '操作失败', 'error', '点赞失败');
        return;
    }

    const r = data.data;
    if (r.liked) state.likedIds.add(video.id);
    else state.likedIds.delete(video.id);
    video.likeCount = r.likeCount;

    paintLike(btn, r.liked, r.likeCount);
    paintStat();
    App.toast(r.liked ? '已点赞' : '已取消点赞', 'success');
}

/* ==================== 加载 ==================== */
function paintStat() {
    $('likeStat').textContent = App.isLogin()
        ? `已点赞 ${state.likedIds.size} 个视频 · ${App.user.name || App.user.username}`
        : '未登录：展示全站热门，点赞需先登录';
}

async function loadVideos() {
    App.setLoading($('reloadBtn'), true, '加载中');

    // 先拿"我赞过的视频"，卡片才能正确显示已赞状态
    if (App.isLogin()) {
        const { data } = await App.api('/api/video/liked-ids', { auth: true, silent: true });
        if (data.code === 0) {
            state.likedIds = new Set(data.data || []);
        }
    }

    const userId = App.isLogin() ? App.user.userId : '';
    const { data } = await App.api(`/api/recommend?userId=${userId}&size=12`);
    App.setLoading($('reloadBtn'), false);

    const box = $('videoGrid');
    box.innerHTML = '';

    if (data.code !== 0) {
        box.innerHTML = `<div class="empty" style="grid-column:1/-1;"><span class="big">⚠️</span>加载失败：${App.escapeHtml(data.msg || '')}</div>`;
        return;
    }

    state.videos = data.data || [];
    if (state.videos.length === 0) {
        box.innerHTML = '<div class="empty" style="grid-column:1/-1;"><span class="big">📭</span>没有推荐结果（已点赞的视频会被推荐逻辑排除，可换个账号试试）</div>';
    } else {
        state.videos.forEach(v => box.appendChild(buildVideoCard(v)));
    }

    const mode = App.isLogin() ? '个性化召回（基于你的点赞行为）' : '未登录 · 全站热门兜底';
    $('videoMore').textContent = `${state.videos.length} 条 · ${mode}`;
    paintStat();
}

/* ==================== 事件 ==================== */
$('reloadBtn').addEventListener('click', loadVideos);
$('clearLog').addEventListener('click', (e) => { e.preventDefault(); $('logBox').textContent = ''; });

/* ==================== 初始化 ==================== */
(async function init() {
    await loadVideos();
    if (!App.isLogin()) {
        App.log('ℹ 未登录：推荐走全站热门，点赞按钮会返回 401', 'sys');
    } else {
        App.log('ℹ 提示：已点赞的视频会被推荐逻辑排除，所以点完赞"换一批"后它可能消失 —— 这是推荐的正常行为', 'sys');
    }
})();
