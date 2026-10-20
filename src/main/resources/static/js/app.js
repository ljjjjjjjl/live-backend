/* =========================================================
   登录页逻辑（B 站风格版）
   接口：
     POST /api/user/register    {username,password,name}
     POST /api/user/login       {username,password}  -> LoginVO
     POST /api/auth/sms/send    {phone}              -> {phoneMasked,expireSeconds[,mockCode]}
     POST /api/auth/sms/login   {phone,code}         -> LoginVO
     POST /api/auth/phone/bind  {phone,code[,oldCode]} (需 Token；换绑必须带 oldCode)
   注意：服务端只回传**脱敏**手机号（phoneMasked），mockCode 只在 dev profile 下出现。
   ========================================================= */

App.renderNav('login');

const $ = (id) => document.getElementById(id);
const PHONE_RE = /^1\d{10}$/;

/* ---------------- 分段 Tab 滑动指示块 ---------------- */
const thumb = $('segThumb');

function moveThumb() {
    const active = document.querySelector('.seg.active');
    if (!active) return;
    thumb.style.width = active.offsetWidth + 'px';
    thumb.style.transform = `translateX(${active.offsetLeft}px)`;
}

function switchTab(name) {
    document.querySelectorAll('.seg').forEach(s => s.classList.toggle('active', s.dataset.tab === name));
    $('pwdForm').classList.toggle('active', name === 'pwd');
    $('smsForm').classList.toggle('active', name === 'sms');
    $('regForm').classList.remove('active');
    moveThumb();
}

document.querySelectorAll('.seg').forEach(seg =>
    seg.addEventListener('click', () => switchTab(seg.dataset.tab)));

/* ---------------- 输入校验高亮 ---------------- */
function invalid(input, message) {
    const wrap = input.closest('.input-wrap');
    if (wrap) {
        wrap.classList.add('invalid');
        setTimeout(() => wrap.classList.remove('invalid'), 1700);
    }
    input.focus();
    if (message) App.toast(message, 'warn');
    return false;
}

/* ---------------- 会话渲染 ---------------- */
function renderSession() {
    const logged = App.isLogin();
    $('sessionCard').classList.toggle('hidden', !logged);
    $('authCard').classList.toggle('hidden', logged);

    if (!logged) return;

    const u = App.user || {};
    const display = u.name || u.username || 'U';
    $('avatar').textContent = display.trim().charAt(0).toUpperCase() || 'U';
    $('sName').textContent = display;
    $('sMeta').textContent = `userId ${u.userId} · ${u.username || '-'}`;
    $('tokenText').textContent = App.token;
}

/* ---------------- 登录成功后的统一处理 ---------------- */
function onLoggedIn(vo) {
    App.saveSession(vo);
    renderSession();
    App.toast(`欢迎回来，${vo.name || vo.username}`, 'success', '登录成功');
    // 连到首页
    setTimeout(() => location.href = '/home.html', 800);
}

/* ---------------- 账号密码登录 ---------------- */
$('pwdForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const uEl = $('pwdUsername'), pEl = $('pwdPassword');
    const username = uEl.value.trim(), password = pEl.value;

    if (!username) return invalid(uEl, '请输入用户名');
    if (!password) return invalid(pEl, '请输入密码');

    const btn = $('pwdSubmit');
    App.setLoading(btn, true, '登录中');
    const { data } = await App.api('/api/user/login', { method: 'POST', body: { username, password } });
    App.setLoading(btn, false);

    if (data.code === 0) onLoggedIn(data.data);
    else App.toast(data.msg || '登录失败', 'error', '登录失败');
});

/* ---------------- 注册 ---------------- */
$('toggleRegister').addEventListener('click', () => {
    $('pwdForm').classList.remove('active');
    $('regForm').classList.add('active');
});
$('cancelRegister').addEventListener('click', () => {
    $('regForm').classList.remove('active');
    $('pwdForm').classList.add('active');
});

$('regForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const uEl = $('regUsername'), pEl = $('regPassword'), nEl = $('regName');
    const username = uEl.value.trim(), password = pEl.value, name = nEl.value.trim();

    if (!username) return invalid(uEl, '请输入用户名');
    if (!password) return invalid(pEl, '请输入密码');
    if (!name) return invalid(nEl, '请输入昵称（注册必填）');

    const btn = $('regSubmit');
    App.setLoading(btn, true, '注册中');
    const { data } = await App.api('/api/user/register', { method: 'POST', body: { username, password, name } });
    App.setLoading(btn, false);

    if (data.code === 0) {
        App.toast(`账号创建成功（userId=${data.data}），已填好登录信息`, 'success', '注册成功');
        $('pwdUsername').value = username;
        $('pwdPassword').value = password;
        $('regForm').classList.remove('active');
        $('pwdForm').classList.add('active');
        moveThumb();
    } else {
        App.toast(data.msg || '注册失败', 'error', '注册失败');
    }
});

/* ---------------- 发送验证码（通用） ---------------- */
function startCountdown(btn) {
    if (btn.dataset.cooling === '1') return;
    btn.dataset.cooling = '1';
    const origin = btn.textContent.trim();
    let n = 60;
    btn.disabled = true;
    btn.textContent = n + 's';
    const timer = setInterval(() => {
        n--;
        if (n <= 0) {
            clearInterval(timer);
            btn.disabled = false;
            btn.textContent = origin;
            btn.dataset.cooling = '0';
        } else btn.textContent = n + 's';
    }, 1000);
}

async function sendCode(btn, phoneInput, codeInput) {
    const phone = phoneInput.value.trim();
    if (!PHONE_RE.test(phone)) return invalid(phoneInput, '手机号格式不正确（11 位、1 开头）');

    App.setLoading(btn, true, '发送中');
    const { data } = await App.api('/api/auth/sms/send', { method: 'POST', body: { phone } });
    App.setLoading(btn, false);

    if (data.code === 0) {
        startCountdown(btn);
        const mock = data.data && data.data.mockCode;
        const masked = (data.data && data.data.phoneMasked) || phone;
        if (mock && codeInput) codeInput.value = mock;   // 仅 dev profile 会回显
        App.toast(mock ? `Mock 验证码 ${mock} 已自动填入` : `验证码已发送至 ${masked}`,
            'success', '验证码已发送');
    } else {
        App.toast(data.msg || '发送失败', 'error', '发送失败');
    }
}

$('sendCodeBtn').addEventListener('click', (e) =>
    sendCode(e.currentTarget, $('smsPhone'), $('smsCode')));
$('bindSendBtn').addEventListener('click', (e) =>
    sendCode(e.currentTarget, $('bindPhone'), $('bindCode')));
$('bindOldSendBtn').addEventListener('click', (e) =>
    sendCode(e.currentTarget, $('bindOldPhone'), $('bindOldCode')));

/* ---------------- 手机号验证码登录 ---------------- */
$('smsForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const phoneEl = $('smsPhone'), codeEl = $('smsCode');
    const phone = phoneEl.value.trim(), code = codeEl.value.trim();

    if (!PHONE_RE.test(phone)) return invalid(phoneEl, '手机号格式不正确');
    if (!code) return invalid(codeEl, '请输入验证码');

    const btn = $('smsSubmit');
    App.setLoading(btn, true, '登录中');
    const { data } = await App.api('/api/auth/sms/login', { method: 'POST', body: { phone, code } });
    App.setLoading(btn, false);

    if (data.code === 0) onLoggedIn(data.data);
    else App.toast(data.msg || '登录失败', 'error', '登录失败');
});

/* ---------------- 绑定手机号 ---------------- */
$('bindBtn').addEventListener('click', async () => {
    const phoneEl = $('bindPhone'), codeEl = $('bindCode');
    const phone = phoneEl.value.trim(), code = codeEl.value.trim();
    const oldPhone = ($('bindOldPhone').value || '').trim();
    const oldCode = ($('bindOldCode').value || '').trim();

    if (!PHONE_RE.test(phone)) return invalid(phoneEl, '手机号格式不正确');
    if (!code) return invalid(codeEl, '请输入验证码（每个码只能用一次，请重新获取）');
    // 换绑（已绑过手机号）必须额外验证原手机号，否则服务端会拒绝
    if (oldPhone && !PHONE_RE.test(oldPhone)) return invalid($('bindOldPhone'), '原手机号格式不正确');
    if (oldPhone && !oldCode) return invalid($('bindOldCode'), '换绑需要原手机号验证码');

    const body = { phone, code };
    if (oldCode) body.oldCode = oldCode;

    App.setLoading($('bindBtn'), true, '绑定中');
    const { status, data } = await App.api('/api/auth/phone/bind',
        { method: 'POST', body, auth: true });
    App.setLoading($('bindBtn'), false);

    if (data.code === 0) {
        const masked = phone.slice(0, 3) + '****' + phone.slice(-4);
        App.toast(`手机号 ${masked} 已写入 user 表`, 'success', oldCode ? '换绑成功' : '绑定成功');
    }
    else if (status === 401) App.toast('登录已失效，请重新登录', 'error', '未登录');
    else App.toast(data.msg || '绑定失败', 'error', '绑定失败');
});

/* ---------------- 其它按钮 ---------------- */
$('logoutBtn').addEventListener('click', () => {
    App.clearSession();
    renderSession();
    App.toast('已退出登录，本地 Token 已清除', 'info', '已退出');
});

$('goHome').addEventListener('click', () => location.href = '/home.html');

$('copyTokenBtn').addEventListener('click', async () => {
    if (!App.token) return App.toast('当前没有 Token', 'warn');
    try {
        await navigator.clipboard.writeText(App.token);
        App.toast('Token 已复制到剪贴板', 'success', '复制成功');
    } catch (e) {
        App.toast('复制失败，请手动选中复制', 'error', '复制失败');
    }
});

$('clearLog').addEventListener('click', (e) => {
    e.preventDefault();
    $('logBox').textContent = '';
});

/* ---------------- 初始化 ---------------- */
App.flushLog();
renderSession();
moveThumb();
window.addEventListener('resize', moveThumb);
setTimeout(moveThumb, 60);
App.log('页面已就绪，后端地址：' + location.origin, 'sys');
