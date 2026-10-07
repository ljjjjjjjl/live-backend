/* =========================================================
   live-backend 前端 · 公共逻辑
   提供：会话管理 / 统一请求 / Toast / 顶部导航 / 小工具
   所有页面都先引这个，再引自己的页面脚本。
   ========================================================= */

const App = (function () {

    /* ---------------- 会话（localStorage，与旧版 app.js 同一套 key） ---------------- */
    const TOKEN_KEY = 'ls_token';
    const USER_KEY = 'ls_user';

    let token = localStorage.getItem(TOKEN_KEY) || '';
    let user = parseJson(localStorage.getItem(USER_KEY));

    function parseJson(s) {
        try { return s ? JSON.parse(s) : null; } catch (e) { return null; }
    }

    function saveSession(vo) {
        token = vo.token;
        user = vo;
        localStorage.setItem(TOKEN_KEY, token);
        localStorage.setItem(USER_KEY, JSON.stringify(vo));
    }

    function clearSession() {
        token = '';
        user = null;
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(USER_KEY);
    }

    /* ---------------- Toast ---------------- */
    const ICON = { success: '✓', error: '✕', warn: '!', info: 'i' };
    const TITLE = { success: '操作成功', error: '操作失败', warn: '请注意', info: '提示' };

    function toast(message, type = 'info', title = '', duration = 2800) {
        let box = document.getElementById('toasts');
        if (!box) {
            box = document.createElement('div');
            box.id = 'toasts';
            box.className = 'toasts';
            document.body.appendChild(box);
        }
        while (box.children.length >= 4) box.removeChild(box.firstElementChild);

        const el = document.createElement('div');
        el.className = 'toast ' + type;

        const icon = document.createElement('div');
        icon.className = 'toast-icon';
        icon.textContent = ICON[type] || 'i';

        const body = document.createElement('div');
        body.className = 'toast-body';
        const strong = document.createElement('strong');
        strong.textContent = title || TITLE[type];
        const span = document.createElement('span');
        span.textContent = message == null ? '' : String(message);
        body.append(strong, span);

        const close = document.createElement('button');
        close.className = 'toast-close';
        close.textContent = '×';

        el.append(icon, body, close);
        box.appendChild(el);

        let timer = setTimeout(dismiss, duration);
        function dismiss() {
            clearTimeout(timer);
            if (!el.isConnected) return;
            el.classList.add('out');
            el.addEventListener('animationend', () => el.remove(), { once: true });
        }
        close.addEventListener('click', dismiss);
        el.addEventListener('mouseenter', () => clearTimeout(timer));
        el.addEventListener('mouseleave', () => { timer = setTimeout(dismiss, 1400); });
    }

    /* ---------------- 统一请求 ---------------- */
    const logBuffer = [];

    function log(text, cls) {
        logBuffer.push({ text, cls });
        const box = document.getElementById('logBox');
        if (!box) return;
        const div = document.createElement('div');
        if (cls) div.className = cls;
        div.textContent = text;
        box.appendChild(div);
        box.scrollTop = box.scrollHeight;
    }

    /**
     * 统一请求封装。
     * @param path  接口路径
     * @param opts  { method, body, auth, silent }
     *              auth=true 时带 Authorization 头，缺 Token 直接失败不发请求
     *              silent=true 不写请求日志
     */
    async function api(path, { method = 'GET', body = null, auth = false, silent = false } = {}) {
        const headers = {};
        if (body != null) headers['Content-Type'] = 'application/json';
        if (auth) {
            if (!token) {
                if (!silent) log('✗ 未登录，无法调用：' + path, 'err');
                return { status: 401, data: { code: 1, msg: '未登录，请先登录' } };
            }
            headers['Authorization'] = 'Bearer ' + token;
        }

        let res;
        try {
            res = await fetch(path, { method, headers, body: body != null ? JSON.stringify(body) : undefined });
        } catch (e) {
            if (!silent) log('✗ 网络异常：' + e.message, 'err');
            return { status: 0, data: { code: -1, msg: '网络异常，后端启动了吗？' } };
        }

        const raw = await res.text();
        let data;
        try { data = JSON.parse(raw); } catch (e) { data = { code: -1, msg: raw || ('HTTP ' + res.status) }; }

        // 滑动续期：服务端在 Token 临近过期时通过响应头下发新 Token
        const fresh = res.headers.get('X-Refresh-Token');
        if (fresh && fresh !== token) {
            token = fresh;
            localStorage.setItem(TOKEN_KEY, fresh);
            if (!silent) log('🔄 Token 已滑动续期，本地已更新', 'sys');
        }

        if (!silent) {
            log(`${method} ${path} → HTTP ${res.status} ${JSON.stringify(data)}`,
                res.ok && data.code === 0 ? 'ok' : 'err');
        }
        return { status: res.status, data };
    }

    /* ---------------- 顶部导航 ---------------- */
    const NAV = [
        { key: 'video', text: '视频', href: '/home.html' },
        { key: 'live', text: '直播间', href: '/live.html' },
        { key: 'tools', text: '工具台', href: '/tools.html' },
        { key: 'like-demo', text: '点赞压测', href: '/like.html' }
    ];

    function renderNav(active) {
        const root = document.getElementById('navRoot');
        if (!root) return;

        const nav = document.createElement('nav');
        nav.className = 'bili-nav';

        const logo = document.createElement('a');
        logo.className = 'bili-logo';
        logo.href = '/home.html';
        logo.innerHTML = '<span class="mark">L</span><span>直播系统</span><span class="sub">Live</span>';

        const menu = document.createElement('div');
        menu.className = 'bili-menu';
        NAV.forEach(item => {
            const a = document.createElement('a');
            a.href = item.href;
            a.textContent = item.text;
            if (item.key === active) a.className = 'on';
            menu.appendChild(a);
        });

        const userBox = document.createElement('div');
        userBox.className = 'bili-user';

        if (token && user) {
            const display = user.name || user.username || 'U';
            const avatar = document.createElement('div');
            avatar.className = 'bili-avatar';
            avatar.textContent = display.trim().charAt(0).toUpperCase() || 'U';

            const name = document.createElement('span');
            name.className = 'name';
            name.textContent = display;

            const logout = document.createElement('button');
            logout.className = 'btn small';
            logout.textContent = '退出';
            logout.addEventListener('click', () => {
                clearSession();
                toast('已退出登录', 'info', '已退出');
                setTimeout(() => location.href = '/index.html', 600);
            });

            userBox.append(avatar, name, logout);
        } else {
            const login = document.createElement('a');
            login.className = 'btn primary small';
            login.href = '/index.html';
            login.textContent = '登录';
            userBox.appendChild(login);
        }

        nav.append(logo, menu, userBox);
        root.innerHTML = '';
        root.appendChild(nav);
    }

    /* ---------------- 小工具 ---------------- */
    function qs(name, def = '') {
        return new URLSearchParams(location.search).get(name) || def;
    }

    function escapeHtml(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g,
            c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    }

    /** 12345 → 1.2万 */
    function shortNum(n) {
        const v = Number(n || 0);
        if (v >= 100000000) return (v / 100000000).toFixed(1) + '亿';
        if (v >= 10000) return (v / 10000).toFixed(1) + '万';
        return String(v);
    }

    /** 按钮 loading 态 */
    function setLoading(btn, loading, text = '处理中') {
        if (!btn) return;
        if (loading) {
            if (!btn.dataset.origin) btn.dataset.origin = btn.textContent.trim();
            btn.disabled = true;
            btn.innerHTML = '<span class="spinner"></span>' + text;
        } else {
            btn.disabled = false;
            btn.textContent = btn.dataset.origin || btn.textContent;
        }
    }

    /** 未登录时跳回登录页 */
    function guard() {
        if (!token) {
            toast('请先登录', 'warn', '未登录');
            setTimeout(() => location.href = '/index.html', 700);
            return false;
        }
        return true;
    }

    /**
     * 拉取【全部】直播间（自动翻页）。
     *
     * <p>⚠️ 为什么必须翻页、不能一把梭？
     * 后端 `LiveRoomController.list` 对 pageSize 有 `@Max(50)` 校验，
     * 传 `pageSize=200` 会直接返回 `{code:1, msg:"pageSize不能大于50"}` —— 数据是空的，
     * 而且不会有任何异常，非常容易踩（页面只是"没数据"，看不出是参数被拒了）。
     *
     * <p>用途：首页统计各分区房间数、直播间页根据 id 找房间信息。
     *
     * @param category 可选，按分区过滤
     * @returns {Promise<Array>} 全部房间 VO
     */
    async function fetchAllRooms(category) {
        const SIZE = 50;            // 后端上限
        const MAX_PAGES = 20;       // 安全阀：最多 1000 个房间，演示足够
        let page = 1, all = [], total = Infinity;

        while (all.length < total && page <= MAX_PAGES) {
            const q = `/api/live/rooms?pageNum=${page}&pageSize=${SIZE}`
                + (category ? `&category=${encodeURIComponent(category)}` : '');
            const { data } = await api(q, { silent: true });
            if (data.code !== 0 || !data.data) break;

            total = data.data.total || 0;
            const records = data.data.records || [];
            if (records.length === 0) break;

            all = all.concat(records);
            page++;
        }
        return all;
    }

    /** 回放进入本页之前缓冲的日志（页面引用 logBox 时调用） */
    function flushLog() {
        const box = document.getElementById('logBox');
        if (!box) return;
        logBuffer.forEach(({ text, cls }) => {
            const div = document.createElement('div');
            if (cls) div.className = cls;
            div.textContent = text;
            box.appendChild(div);
        });
        box.scrollTop = box.scrollHeight;
    }

    return {
        get token() { return token; },
        get user() { return user; },
        isLogin: () => !!token,
        saveSession, clearSession,
        api, toast, log, flushLog,
        renderNav, setLoading,
        qs, escapeHtml, shortNum, guard, fetchAllRooms
    };
})();
