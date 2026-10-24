#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
弹幕「去落库」改造的端到端验证脚本 —— 纯标准库，零依赖，python 直接跑。

背景：本轮按需求删掉了弹幕落库功能（见 docs/plan/danmaku-no-persist.md）。
      处理链从「DFA 校验 → INSERT → 广播」变成「DFA 校验 → 构造载荷 → 广播」。

验证 9 件事：
  1. 登录（dev profile 的 mock 短信 + 回显验证码）拿到 Token
  2. GET  /api/danmaku            不再返回历史数据          ← 接口已删除
  3. POST /api/danmaku            已登录 → 404              ← 接口已删除
  4. POST /api/danmaku/ws-ticket  仍然可用                  ← 不该被误删
  5. WS 一次性票据握手成功
  6. 正常弹幕被广播回来（不落库也能实时送达）
  7. 广播的昵称是服务端值（不采信前端）
  8. 敏感词弹幕被拒且不广播；超长被拦；非法颜色归一化
  9. danmaku 表行数前后不变（DB=1 时，需 pymysql）          ← 核心证据

为什么 WebSocket 自己实现：
  websockets / websocket-client 不是标准库，机器上不一定装了。
  本项目只用到「文本帧 + 小消息 + 本地连接」，手写握手和帧编解码约 70 行，
  换来的是不需要 pip install 任何东西。

用法：
    python ws-test.py
    BASE=http://127.0.0.1:8080 python ws-test.py
    ROOM=201 python ws-test.py
    DB=1 python ws-test.py                 # 额外校验表行数（需 pymysql）

前置：后端已用 dev profile 启动，否则 mock 短信关掉、拿不到验证码：
    mvn spring-boot:run -Dspring-boot.run.profiles=dev

退出码：0 = 全部通过；1 = 有失败；2 = 环境问题（连不上 / 拿不到 mockCode）
"""

import base64
import json
import os
import random
import socket
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get("BASE", "http://127.0.0.1:8080").rstrip("/")
WS_BASE = os.environ.get("WS_BASE") or BASE.replace("https://", "wss://").replace("http://", "ws://")
ROOM = os.environ.get("ROOM", "201")

_passed = 0
_failed = 0
_failures = []


def check(name, cond, extra=""):
    global _passed, _failed
    if cond:
        _passed += 1
        print("  PASS  " + name)
    else:
        _failed += 1
        _failures.append(name)
        print("  FAIL  " + name + " " + str(extra))


def section(title):
    print("\n== " + title + " ==")


# ─────────────────────────── HTTP（urllib） ───────────────────────────
def http(path, method="GET", token=None, body=None, timeout=10):
    """返回 (status, json_or_None)。4xx/5xx 不抛异常，照常返回状态码。"""
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8", "replace")
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}
    except urllib.error.URLError as e:
        print("\n[致命] 连不上 %s —— 后端没起来？(%s)" % (BASE, e.reason))
        sys.exit(2)


def login():
    """手机号 + mock 验证码登录（登录即注册）。返回 (token, 服务端展示名)。"""
    phone = "139" + "%08d" % random.randrange(10 ** 8)   # 每次新号，避开 60 秒重发锁
    st, s = http("/api/auth/sms/send", "POST", body={"phone": phone})
    code = ((s or {}).get("data") or {}).get("mockCode")
    if not code:
        print("\n[致命] 没拿到 mockCode —— 后端不是 dev profile？")
        print("  期望启动方式：mvn spring-boot:run -Dspring-boot.run.profiles=dev")
        print("  实际响应：%s %s" % (st, s))
        sys.exit(2)
    st, l = http("/api/auth/sms/login", "POST", body={"phone": phone, "code": code})
    data = (l or {}).get("data") or {}
    if (l or {}).get("code") != 0 or not data.get("token"):
        print("\n[致命] 登录失败：%s %s" % (st, l))
        sys.exit(2)
    return data["token"], data.get("name") or data.get("username")


# ─────────────────── 极简 WebSocket 客户端（RFC 6455 子集） ───────────────────
class MiniWS:
    """只支持文本帧、无压缩、无分片 —— 足够测本项目。"""

    def __init__(self, ws_url, timeout=10.0):
        u = urllib.parse.urlparse(ws_url)
        if u.scheme != "ws":
            raise ValueError("本客户端只支持 ws://，收到 " + u.scheme)
        self.host = u.hostname
        self.port = u.port or 80
        self.path = u.path + (("?" + u.query) if u.query else "")
        self.timeout = timeout
        self.sock = None
        self.buf = b""

    def connect(self):
        self.sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        req = (
            "GET %s HTTP/1.1\r\n"
            "Host: %s:%d\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            "Sec-WebSocket-Key: %s\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        ) % (self.path, self.host, self.port, key)
        self.sock.sendall(req.encode())
        while b"\r\n\r\n" not in self.buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("WS 握手期间连接被关闭")
            self.buf += chunk
        head, self.buf = self.buf.split(b"\r\n\r\n", 1)
        status_line = head.split(b"\r\n", 1)[0].decode("latin1")
        if " 101 " not in status_line:
            raise ConnectionError("WS 握手失败：" + status_line)

    def _read(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("WS 连接已关闭")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def _send_frame(self, opcode, payload=b""):
        mask = os.urandom(4)
        n = len(payload)
        frame = bytearray([0x80 | opcode])          # FIN=1
        if n < 126:
            frame.append(0x80 | n)
        elif n < 65536:
            frame.append(0x80 | 126)
            frame += n.to_bytes(2, "big")
        else:
            frame.append(0x80 | 127)
            frame += n.to_bytes(8, "big")
        frame += mask
        frame += bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(frame))

    def send_text(self, text):
        self._send_frame(0x1, text.encode("utf-8"))

    def recv_text(self):
        """阻塞读一条文本消息；ping 自动回 pong；收到 close 抛 ConnectionError。"""
        while True:
            b1, b2 = self._read(2)
            opcode = b1 & 0x0F
            masked = b2 & 0x80
            ln = b2 & 0x7F
            if ln == 126:
                ln = int.from_bytes(self._read(2), "big")
            elif ln == 127:
                ln = int.from_bytes(self._read(8), "big")
            mask = self._read(4) if masked else None
            payload = self._read(ln) if ln else b""
            if mask:
                payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
            if opcode == 0x8:
                raise ConnectionError("服务端关闭了连接")
            if opcode == 0x9:
                self._send_frame(0xA, payload)
                continue
            if opcode == 0xA:
                continue
            if opcode in (0x1, 0x2):
                return payload.decode("utf-8", "replace")

    def close(self):
        try:
            self._send_frame(0x8)
        except Exception:
            pass
        try:
            self.sock.close()
        except Exception:
            pass


class Collector(threading.Thread):
    """后台收消息线程：避免边发边收时 recv 超时把帧读坏。"""

    def __init__(self, ws):
        threading.Thread.__init__(self)
        self.daemon = True
        self.ws = ws
        self.msgs = []
        self.error = None
        self._stop = False

    def run(self):
        try:
            self.ws.sock.settimeout(1.0)
            while not self._stop:
                try:
                    raw = self.ws.recv_text()
                except socket.timeout:
                    continue
                try:
                    self.msgs.append(json.loads(raw))
                except Exception:
                    pass
        except Exception as e:
            self.error = e

    def stop(self):
        self._stop = True

    def chats(self):
        return [m for m in self.msgs if m.get("type") == "CHAT"]

    def sys_texts(self):
        return [str(m.get("data")) for m in self.msgs if m.get("type") == "SYSTEM"]

    def latest_chat(self):
        c = self.chats()
        return c[-1] if c else None


# ─────────────────────────── 可选的数据库校验 ───────────────────────────
def danmaku_count():
    """返回 danmaku 表行数；pymysql 不可用时返回 None（跳过）。"""
    try:
        import pymysql
    except ImportError:
        print("  [skip] 没装 pymysql，跳过表行数校验（想校验就 pip install pymysql）")
        return None
    try:
        conn = pymysql.connect(
            host=os.environ.get("DB_HOST", "127.0.0.1"),
            port=int(os.environ.get("DB_PORT", "3306")),
            user=os.environ.get("DB_USERNAME", "root"),
            password=os.environ.get("DB_PASSWORD", "123456"),
            database=os.environ.get("DB_NAME", "db1"),
            charset="utf8mb4",
            connect_timeout=5,
        )
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT COUNT(*) FROM danmaku")
                return cur.fetchone()[0]
        finally:
            conn.close()
    except Exception as e:
        print("  [warn] 查 danmaku 表失败，跳过行数校验：%s" % e)
        return None


# ─────────────────────────────── 主流程 ───────────────────────────────
def main():
    print("弹幕「去落库」验证 —— 后端 %s，房间 %s" % (BASE, ROOM))

    do_db = os.environ.get("DB") == "1"
    baseline = None
    if do_db:
        section("danmaku 表基线")
        baseline = danmaku_count()
        print("  baseline = %s" % baseline)

    section("登录（mock 短信）")
    token, name = login()
    print("  · 服务端展示名 = %s" % name)
    check("mock 短信登录成功", bool(token))

    section("已删除的接口")
    st_anon, body_anon = http("/api/danmaku?roomId=%s" % ROOM)
    # 未登录时拦截器先挡（401）；即便落到 404 也说明接口没了。关键是不能再返回历史数据。
    check("GET /api/danmaku 不再返回历史数据",
          st_anon in (401, 404), "status=%s body=%s" % (st_anon, body_anon))

    st_get, body_get = http("/api/danmaku?roomId=%s" % ROOM, token=token)
    check("GET /api/danmaku 已登录 → 404", st_get == 404,
          "status=%s body=%s" % (st_get, body_get))

    st_post, body_post = http("/api/danmaku", "POST", token=token,
                              body={"roomId": int(ROOM), "content": "这条不该再落库"})
    check("POST /api/danmaku 已登录 → 404", st_post == 404,
          "status=%s body=%s" % (st_post, body_post))

    section("保留下来的 ws-ticket 接口")
    st_t, body_t = http("/api/danmaku/ws-ticket", "POST", token=token)
    ticket = ((body_t or {}).get("data") or {}).get("ticket")
    check("POST /api/danmaku/ws-ticket 返回票据",
          st_t == 200 and (body_t or {}).get("code") == 0 and bool(ticket),
          "status=%s body=%s" % (st_t, body_t))
    if not ticket:
        return summary()

    section("WebSocket：票据握手 / 广播 / 敏感词 / 长度 / 颜色")
    ws = MiniWS("%s/ws/danmaku/%s?ticket=%s"
                % (WS_BASE, ROOM, urllib.parse.quote(ticket)))
    try:
        ws.connect()
    except Exception as e:
        check("WS 用一次性票据握手成功", False, str(e))
        return summary()
    check("WS 用一次性票据握手成功", True)

    col = Collector(ws)
    col.start()
    time.sleep(0.4)          # 等一条 "已进入直播间" 的系统提示

    # ① 正常弹幕：证明不落库也能实时广播
    ws.send_text(json.dumps({"content": "python 验证弹幕", "color": "#FB7299"}))
    time.sleep(0.7)
    chat = col.latest_chat()
    check("正常弹幕被广播回来（不落库也能实时送达）",
          bool(chat) and (chat.get("data") or {}).get("content") == "python 验证弹幕",
          "实际 %s" % chat)
    if name:
        check("广播的昵称是服务端值（不采信前端）",
              bool(chat) and (chat.get("data") or {}).get("nickname") == name,
              "期望 %s，实际 %s" % (name, (chat or {}).get("data", {}).get("nickname")))

    # ② 敏感词：必须被拒、且不能广播出去（DFA 校验没跟着落库一起消失）
    ws.send_text(json.dumps({"content": "fuck you", "color": "#ffffff"}))
    time.sleep(0.7)
    check("敏感词弹幕未被广播",
          not any("fuck" in str(m.get("data") or "").lower() for m in col.chats()),
          "实际 chats=%s" % [m.get("data") for m in col.chats()])
    check("敏感词弹幕给出 SYSTEM 失败提示",
          any("发送失败" in t for t in col.sys_texts()), "实际 %s" % col.sys_texts())

    # ③ 超长：拦在广播前，而不是数据库报错
    ws.send_text(json.dumps({"content": "x" * 300}))
    time.sleep(0.7)
    check("超长弹幕被拦（提示而不是数据库报错）",
          any("不能超过 200" in t for t in col.sys_texts()), "实际 %s" % col.sys_texts())

    # ④ 非法颜色：归一化，避免注入到前端 style
    ws.send_text(json.dumps({"content": "颜色注入", "color": "red;}body{display:none"}))
    time.sleep(0.7)
    c4 = col.latest_chat()
    check("非法颜色被归一化为 #ffffff",
          bool(c4) and (c4.get("data") or {}).get("color") == "#ffffff", "实际 %s" % c4)

    col.stop()
    ws.close()
    time.sleep(0.3)

    if do_db:
        section("核心证据：danmaku 表不再增长")
        after = danmaku_count()
        if baseline is None or after is None:
            print("  [skip] 没拿到行数，无法比对")
        else:
            check("表行数前后一致（baseline=%s, after=%s）" % (baseline, after),
                  baseline == after, "增加了 %s 行" % (after - baseline))

    return summary()


def summary():
    print("\n" + "=" * 46)
    print("通过 %d 项，失败 %d 项" % (_passed, _failed))
    if _failures:
        print("失败项：")
        for f in _failures:
            print("  - " + f)
    print("=" * 46)
    sys.exit(0 if _failed == 0 else 1)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n[中断]")
        sys.exit(130)
