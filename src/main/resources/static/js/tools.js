/* =========================================================
   工具台逻辑
   接口：
     GET  /api/sensitive/check?text=      敏感词检测
     GET  /api/sensitive/filter?text=     敏感词替换
     GET  /api/sensitive/count            词库数量
     POST /api/sensitive/reload           词库热更新
     POST /api/count/{id}/{type}          计数 +1（演示用，无鉴权）
     GET  /api/count/{id}                 读全部计数
     GET  /api/video/{id}/like            点赞状态 + 赞数（需 Token）
     GET  /api/video/liked-ids            我赞过的视频（需 Token）
   ========================================================= */

App.renderNav('tools');
App.flushLog();

const $ = (id) => document.getElementById(id);
const out = (id, text) => { $(id).textContent = typeof text === 'string' ? text : JSON.stringify(text, null, 2); };

/* ==================== 敏感词 ==================== */
/* ⚠️ 这些接口现在都要登录（后端把 /api/sensitive/** 纳入了 JWT 拦截）——
   之前它们完全裸奔，任何人循环 POST /reload 就能让服务端不停地全表读 + 重建 Trie。 */
async function loadWordCount() {
    const { data, status } = await App.api('/api/sensitive/count', { auth: true, silent: true });
    $('wordCount').textContent = data.code === 0
        ? `当前词库 ${data.data} 个词`
        : (status === 401 ? '未登录（管理接口需要登录）' : `词库读取失败（HTTP ${status}）`);
}

$('swCheck').addEventListener('click', async () => {
    if (!App.guard()) return;
    const text = $('swText').value;
    const { data } = await App.api('/api/sensitive/check?text=' + encodeURIComponent(text), { auth: true });
    if (data.code !== 0) return out('swResult', '失败：' + data.msg);
    const d = data.data;
    out('swResult', d.pass
        ? `✅ 通过（未命中任何敏感词）\n文本：${d.text}`
        : `🚫 命中敏感词「${d.hit}」\n文本：${d.text}`);
});

$('swFilter').addEventListener('click', async () => {
    if (!App.guard()) return;
    const text = $('swText').value;
    const { data } = await App.api('/api/sensitive/filter?text=' + encodeURIComponent(text), { auth: true });
    if (data.code !== 0) return out('swResult', '失败：' + data.msg);
    out('swResult', `原文：  ${data.data.text}\n过滤后：${data.data.filtered}`);
});

$('swReload').addEventListener('click', async () => {
    if (!App.guard()) return;
    App.setLoading($('swReload'), true, '加载中');
    const { data } = await App.api('/api/sensitive/reload', { method: 'POST', auth: true });
    App.setLoading($('swReload'), false);
    if (data.code !== 0) return App.toast(data.msg || '热更新失败', 'error');
    App.toast(data.data, 'success', '词库已热更新');
    loadWordCount();
});

/* ==================== 计数缓存 ==================== */
$('cntIncr').addEventListener('click', async () => {
    if (!App.guard()) return;
    const id = $('cntTarget').value.trim();
    const type = $('cntType').value;
    const { data } = await App.api(`/api/count/${id}/${type}`, { method: 'POST', auth: true });
    if (data.code !== 0) return out('cntResult', '失败：' + data.msg);
    out('cntResult', `${type} 计数 +1 成功，当前值 = ${data.data}`);
    App.toast(`${id} 的 ${type} 现在是 ${data.data}`, 'success');
});

$('cntRead').addEventListener('click', async () => {
    if (!App.guard()) return;
    const id = $('cntTarget').value.trim();
    const { data } = await App.api(`/api/count/${id}`, { auth: true });
    if (data.code !== 0) return out('cntResult', '失败：' + data.msg);
    out('cntResult', data.data);
});

/* ==================== 视频点赞 ==================== */
$('likeStatus').addEventListener('click', async () => {
    if (!App.guard()) return;
    const id = $('likeVideoId').value.trim();
    const { data, status } = await App.api(`/api/video/${id}/like`, { auth: true });
    if (data.code !== 0) {
        return out('likeResult', status === 401 ? '未登录或 Token 已失效' : '失败：' + data.msg);
    }
    out('likeResult', `视频 ${id}\n  liked     = ${data.data.liked}\n  likeCount = ${data.data.likeCount}`);
});

$('likeMine').addEventListener('click', async () => {
    if (!App.guard()) return;
    const { data, status } = await App.api('/api/video/liked-ids', { auth: true });
    if (data.code !== 0) {
        return out('likeResult', status === 401 ? '未登录或 Token 已失效' : '失败：' + data.msg);
    }
    out('likeResult', `我赞过 ${data.data.length} 个视频：\n${JSON.stringify(data.data)}`);
});

/* ==================== 数据库现状（用现有接口能拿到的真实数字） ==================== */
async function loadStats() {
    const grid = $('statGrid');
    grid.innerHTML = '<div class="loading" style="grid-column:1/-1;">统计中…</div>';

    const [wordsRes, roomsRes, likedRes] = await Promise.all([
        App.api('/api/sensitive/count', { silent: true }),
        App.api('/api/live/rooms?pageNum=1&pageSize=1', { silent: true }),
        App.isLogin() ? App.api('/api/video/liked-ids', { auth: true, silent: true }) : Promise.resolve(null)
    ]);

    const stats = [
        { label: '敏感词', value: wordsRes.data.code === 0 ? wordsRes.data.data : '-', unit: '个' },
        { label: '直播中房间', value: roomsRes.data.code === 0 ? (roomsRes.data.data.total || 0) : '-', unit: '个' },
        { label: '我赞过的视频', value: likedRes && likedRes.data.code === 0 ? likedRes.data.data.length : '-', unit: '个' },
        { label: '登录用户', value: App.isLogin() ? (App.user.name || App.user.username) : '未登录', unit: '' },
        { label: 'userId', value: App.isLogin() ? App.user.userId : '-', unit: '' },
        { label: 'Token', value: App.isLogin() ? App.token.slice(0, 18) + '…' : '-', unit: '' }
    ];

    grid.innerHTML = '';
    stats.forEach(s => {
        const card = document.createElement('div');
        card.className = 'card';
        card.style.padding = '14px';
        card.innerHTML = `<div class="muted" style="font-size:12px;">${App.escapeHtml(s.label)}</div>
                          <div style="font-size:20px;font-weight:700;margin-top:4px;word-break:break-all;">
                            ${App.escapeHtml(String(s.value))}<span class="muted" style="font-size:12px;font-weight:400;margin-left:3px;">${s.unit}</span>
                          </div>`;
        grid.appendChild(card);
    });
}

$('clearLog').addEventListener('click', (e) => { e.preventDefault(); $('logBox').textContent = ''; });

/* ==================== 初始化 ==================== */
loadWordCount();
loadStats();
