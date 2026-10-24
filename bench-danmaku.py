#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
弹幕链路压测 / 性能测量脚本 —— 纯标准库（DB 校验可选 pymysql）。

目的：产出可以写进简历、并且面试时能被追问的**量化数字**。两个模式：

  latency  单连接延迟分位数（P50/P95/P99）—— 最稳定的数字
  scale    多连接并发能力（握手成功率 / 总吞吐 / 延迟劣化）
  （--db 可叠加在任意模式上）数据库写入消除量

设计要点（都是踩过的坑）：
  · 服务端对【每条连接】限速 MAX_MESSAGES_PER_SECOND = 5 条/秒，
    所以单连接发不快；要压量必须靠多连接。--rate 默认 5，超过会被限速，
    脚本会统计"被限速"次数，提醒你别拿污染过的数据写简历。
  · 握手票据是【一次性】的（服务端 Lua 原子消费）：并发建连必须
    【每条连接各换一张票】。复用票据会得到「握手失败：HTTP/1.1 200」——
    服务端对失败握手返回 200 而不是 4xx，这个失败极容易被误读成"并发上限"。
    （v2 修复：v1 只换一张票给所有连接用，导致 50 条连接里 49 条握手失败。）
  · 广播回来的载荷里没有客户端生成的 id，无法直接配对请求。
    所以用 content 里嵌唯一串（probe-<tag>-<seq>）来匹配"发出"与"收回"。
  · 用 perf_counter 计单调时钟，避免系统时间跳变污染延迟。

用法：
    # ① 单连接延迟（最常用，45 秒出结果）
    python bench-danmaku.py --mode latency --count 200 --db

    # ② 50 条连接，每条发 20 条，测并发能力
    python bench-danmaku.py --mode scale --conns 50 --count 20

    # ③ 叠加数据库校验：证明 danmaku 表一条都没写
    python bench-danmaku.py --mode scale --conns 50 --count 20 --db

    # ④ 做 A/B 对照（注意记得 pop 回来）
    git stash push -m "danmaku-nodb"
    python bench-danmaku.py --mode latency --count 200 --db   # 旧版：会落库
    git stash pop
    python bench-danmaku.py --mode latency --count 200 --db   # 新版：不落库

前置：后端已用 dev profile 启动
    mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
"""

import argparse
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


# ───────────────────────────── HTTP ─────────────────────────────
def http(path, method="GET", token=None, body=None, timeout=15):
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
        print("[致命] 连不上 %s —— 后端没起来？(%s)" % (BASE, e.reason))
        sys.exit(2)


def login():
    phone = "139" + "%08d" % random.randrange(10 ** 8)
    st, s = http("/api/auth/sms/send", "POST", body={"phone": phone})
    code = ((s or {}).get("data") or {}).get("mockCode")
    if not code:
        print("[致命] 拿不到 mockCode —— 后端不是 dev profile？")
        print("  期望：mvn spring-boot:run \"-Dspring-boot.run.profiles=dev\"")
        print("  实际：%s %s" % (st, s))
        sys.exit(2)
    st, l = http("/api/auth/sms/login", "POST", body={"phone": phone, "code": code})
    data = (l or {}).get("data") or {}
    if (l or {}).get("code") != 0 or not data.get("token"):
        print("[致命] 登录失败：%s %s" % (st, l))
        sys.exit(2)
    return data["token"]


def ws_ticket(token, quiet=False):
    """换一张 WS 握手票据。

    ⚠️ 票据是【一次性】的：一条连接消费掉就作废。并发建连必须每条连接各换一张。
       复用会让除第一条外的所有连接握手失败，而服务端返回的是 HTTP 200。
    """
    st, body = http("/api/danmaku/ws-ticket", "POST", token=token)
    ticket = ((body or {}).get("data") or {}).get("ticket")
    if not ticket:
        if not quiet:
            print("[致命] 取 ws-ticket 失败：%s %s" % (st, body))
        return None
    return ticket


# ───────────────────── 极简 WebSocket（RFC 6455 子集） ─────────────────────
class MiniWS:
    def __init__(self, ws_url, timeout=15.0):
        u = urllib.parse.urlparse(ws_url)
        if u.scheme != "ws":
            raise ValueError("只支持 ws://，收到 " + u.scheme)
        self.host, self.port = u.hostname, (u.port or 80)
        self.path = u.path + (("?" + u.query) if u.query else "")
        self.timeout = timeout
        self.sock = None
        self.buf = b""

    def connect(self):
        self.sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        req = ("GET %s HTTP/1.1\r\nHost: %s:%d\r\nUpgrade: websocket\r\n"
               "Connection: Upgrade\r\nSec-WebSocket-Key: %s\r\n"
               "Sec-WebSocket-Version: 13\r\n\r\n") % (self.path, self.host, self.port, key)
        self.sock.sendall(req.encode())
        while b"\r\n\r\n" not in self.buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("握手期间连接被关闭")
            self.buf += chunk
        head, self.buf = self.buf.split(b"\r\n\r\n", 1)
        status = head.split(b"\r\n", 1)[0].decode("latin1")
        if " 101 " not in status:
            raise ConnectionError("握手失败：" + status)

    def _read(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("连接已关闭")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def _frame(self, opcode, payload=b""):
        mask = os.urandom(4)
        n = len(payload)
        f = bytearray([0x80 | opcode])
        if n < 126:
            f.append(0x80 | n)
        elif n < 65536:
            f.append(0x80 | 126)
            f += n.to_bytes(2, "big")
        else:
            f.append(0x80 | 127)
            f += n.to_bytes(8, "big")
        f += mask
        f += bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(f))

    def send_text(self, text):
        self._frame(0x1, text.encode("utf-8"))

    def recv_text(self):
        while True:
            b1, b2 = self._read(2)
            op, masked, ln = b1 & 0x0F, b2 & 0x80, b2 & 0x7F
            if ln == 126:
                ln = int.from_bytes(self._read(2), "big")
            elif ln == 127:
                ln = int.from_bytes(self._read(8), "big")
            mask = self._read(4) if masked else None
            payload = self._read(ln) if ln else b""
            if mask:
                payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
            if op == 0x8:
                raise ConnectionError("服务端关闭")
            if op == 0x9:
                self._frame(0xA, payload)
                continue
            if op == 0xA:
                continue
            if op in (0x1, 0x2):
                return payload.decode("utf-8", "replace")

    def close(self):
        try:
            self._frame(0x8)
        except Exception:
            pass
        try:
            self.sock.close()
        except Exception:
            pass


# ─────────────────────── 单条连接：读线程 + 打点 ───────────────────────
class Probe:
    """一条 WS 连接 + 一个读线程。发送节奏由外部驱动。"""

    def __init__(self, url, tag):
        self.url = url
        self.tag = tag
        self.ws = None
        self.pending = {}            # content -> t0(perf_counter)
        self.rtts = []               # 毫秒
        self.chat_seen = 0           # 收到的 CHAT 总数（含别人发的，用于算广播扇出）
        self.rate_limited = 0
        self.rejected = 0
        self.read_error = None
        self._stop = False
        self._th = None

    def connect(self):
        self.ws = MiniWS(self.url)
        self.ws.connect()
        self._th = threading.Thread(target=self._read_loop)
        self._th.daemon = True
        self._th.start()

    def _read_loop(self):
        try:
            self.ws.sock.settimeout(1.0)
            while not self._stop:
                try:
                    raw = self.ws.recv_text()
                except socket.timeout:
                    continue
                try:
                    m = json.loads(raw)
                except Exception:
                    continue
                t = m.get("type")
                if t == "CHAT":
                    self.chat_seen += 1
                    content = (m.get("data") or {}).get("content") or ""
                    t0 = self.pending.pop(content, None)
                    if t0 is not None:
                        self.rtts.append((time.perf_counter() - t0) * 1000.0)
                elif t == "SYSTEM":
                    txt = str(m.get("data") or "")
                    if "发得太快" in txt:
                        self.rate_limited += 1
                    elif "发送失败" in txt:
                        self.rejected += 1
        except Exception as e:
            self.read_error = e

    def send_probe(self, seq):
        content = "probe-%s-%d" % (self.tag, seq)
        self.pending[content] = time.perf_counter()
        self.ws.send_text(json.dumps({"content": content, "color": "#FB7299"}))

    def stop(self):
        self._stop = True
        if self.ws:
            self.ws.close()


# ─────────────────────────── 数据库快照 ───────────────────────────
def db_snapshot():
    """返回 (danmaku 的 INSERT 执行次数, danmaku 表行数)；pymysql 不可用返回 None。"""
    try:
        import pymysql
    except ImportError:
        return None
    try:
        conn = pymysql.connect(
            host=os.environ.get("DB_HOST", "127.0.0.1"),
            port=int(os.environ.get("DB_PORT", "3306")),
            user=os.environ.get("DB_USERNAME", "root"),
            password=os.environ.get("DB_PASSWORD", "123456"),
            database=os.environ.get("DB_NAME", "db1"),
            charset="utf8mb4", connect_timeout=5)
        try:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT COALESCE(SUM(COUNT_STAR),0) "
                    "FROM performance_schema.events_statements_summary_by_digest "
                    "WHERE DIGEST_TEXT LIKE 'INSERT%danmaku%'")
                ins = cur.fetchone()[0]
                cur.execute("SELECT COUNT(*) FROM danmaku")
                rows = cur.fetchone()[0]
                return int(ins), int(rows)
        finally:
            conn.close()
    except Exception as e:
        print("[warn] 数据库快照失败：%s" % e)
        return None


# ───────────────────────────── 统计 ─────────────────────────────
def pct(vals, p):
    if not vals:
        return 0.0
    k = (len(vals) - 1) * p / 100.0
    f = int(k)
    c = min(f + 1, len(vals) - 1)
    if f == c:
        return vals[f]
    return vals[f] + (vals[c] - vals[f]) * (k - f)


def describe(vals):
    if not vals:
        return None
    s = sorted(vals)
    return {"n": len(s), "min": s[0], "p50": pct(s, 50), "p95": pct(s, 95),
            "p99": pct(s, 99), "max": s[-1], "mean": sum(s) / len(s)}


# ───────────────────────────── 主流程 ─────────────────────────────
def main():
    ap = argparse.ArgumentParser(
        description="弹幕链路压测：延迟分位 / 并发能力 / 数据库写入消除")
    ap.add_argument("--mode", choices=["latency", "scale"], default="latency")
    ap.add_argument("--conns", type=int, default=1, help="连接数（scale 模式用）")
    ap.add_argument("--count", type=int, default=100, help="每条连接发多少条")
    ap.add_argument("--rate", type=float, default=5.0,
                    help="每条连接每秒发多少条（服务端上限 5，超过会被限速）")
    ap.add_argument("--timeout", type=float, default=15.0, help="等收齐结果的秒数")
    ap.add_argument("--db", action="store_true", help="叠加数据库写入校验（需 pymysql）")
    args = ap.parse_args()

    conns = args.conns if args.mode == "scale" else 1
    if args.rate > 5.0:
        print("[提醒] rate=%.1f 超过服务端限速 5 条/秒/连接，会有消息被拒。"
              "压测可以用，但别拿被限速的数据写简历。\n" % args.rate)

    print("弹幕压测 —— 后端 %s，房间 %s" % (BASE, ROOM))
    print("模式=%s  连接数=%d  每连接=%d 条  速率=%.1f 条/秒/连接\n"
          % (args.mode, conns, args.count, args.rate))

    token = login()

    before = db_snapshot() if args.db else None
    if args.db:
        if before is None:
            print("[skip] 数据库校验不可用（没装 pymysql 或连不上）\n")
        else:
            print("数据库基线：danmaku INSERT 累计=%d，表行数=%d\n" % before)

    # ① 换票：每条连接一张（一次性票据，不能复用）
    t_tk0 = time.perf_counter()
    tickets, ticket_fail = [], 0
    for _ in range(conns):
        tk = ws_ticket(token, quiet=True)
        if tk:
            tickets.append(tk)
        else:
            ticket_fail += 1
    t_ticket = (time.perf_counter() - t_tk0) * 1000.0
    if tickets:
        print("换票：%d 张，耗时 %.0f ms（平均 %.2f ms/张）"
              % (len(tickets), t_ticket, t_ticket / len(tickets)))
    if ticket_fail:
        print("  换票失败：%d 张" % ticket_fail)
    if not tickets:
        print("[致命] 一张票据都没换到")
        return 2

    # ② 建连（失败率才是并发上限的证据——前提是票据没被复用）
    probes, handshake_fail = [], 0
    t_conn0 = time.perf_counter()
    for i, tk in enumerate(tickets):
        p = Probe("%s/ws/danmaku/%s?ticket=%s"
                  % (WS_BASE, ROOM, urllib.parse.quote(tk)), "c%d" % i)
        try:
            p.connect()
            probes.append(p)
        except Exception as e:
            handshake_fail += 1
            if handshake_fail <= 3:
                print("  连接 %d 失败：%s" % (i, e))
    t_conn = (time.perf_counter() - t_conn0) * 1000.0
    print("建连：成功 %d / %d，耗时 %.0f ms（平均 %.2f ms/条）"
          % (len(probes), conns, t_conn, t_conn / max(1, len(tickets))))
    if not probes:
        print("[致命] 一条连接都没建起来")
        return 1
    if handshake_fail:
        print("  ⚠️ 有 %d 条连接失败。若出现「握手失败：HTTP/1.1 200」，"
              "先确认票据没有复用（每条连接必须各换一张）" % handshake_fail)

    # ③ 并行发送
    interval = (1.0 / args.rate) if args.rate > 0 else 0.0
    send_errors = []
    start = time.perf_counter()

    def sender(p):
        for seq in range(args.count):
            try:
                p.send_probe(seq)
            except Exception as e:
                send_errors.append(str(e))
                return
            if interval:
                time.sleep(interval)

    threads = [threading.Thread(target=sender, args=(p,)) for p in probes]
    for th in threads:
        th.start()
    for th in threads:
        th.join()
    send_elapsed = time.perf_counter() - start

    # ④ 等收齐（或超时）
    deadline = time.time() + args.timeout
    while time.time() < deadline:
        if not any(p.pending for p in probes):
            break
        time.sleep(0.05)

    # ⑤ 汇总
    all_rtts, rate_limited, rejected, pending_left, chat_seen = [], 0, 0, 0, 0
    for p in probes:
        all_rtts.extend(p.rtts)
        chat_seen += p.chat_seen
        rate_limited += p.rate_limited
        rejected += p.rejected
        pending_left += len(p.pending)
        p.stop()

    total_sent = len(probes) * args.count
    d = describe(all_rtts)

    print("\n" + "=" * 58)
    print("结果")
    print("=" * 58)
    print("连接数              : %d / %d 成功" % (len(probes), conns))
    print("总发送              : %d 条（%d 条连接 × 每条 %d 条）"
          % (total_sent, len(probes), args.count))
    print("收到回执            : %d 条" % len(all_rtts))
    print("未收到回执          : %d 条%s"
          % (pending_left, "" if pending_left == 0 else "  ← 收不全，延迟数字不可信"))
    print("广播投递总量        : %d 条（扇出约 %.1f 倍）"
          % (chat_seen, (chat_seen / float(total_sent)) if total_sent else 0))
    if send_errors:
        print("发送异常            : %d 次（首个：%s）" % (len(send_errors), send_errors[0]))
    print("被限速拒绝          : %d 条%s"
          % (rate_limited, "" if rate_limited == 0 else "  ← 超 5 条/秒/连接，请调低 --rate"))
    print("被内容审核拒绝      : %d 条" % rejected)
    print("发送阶段耗时        : %.2f s" % send_elapsed)
    if send_elapsed > 0:
        print("实测吞吐            : %.0f 条/秒" % (len(all_rtts) / send_elapsed))
    if d:
        print("-" * 58)
        print("端到端延迟（发出→广播收回，毫秒）")
        print("  P50=%.2f  P95=%.2f  P99=%.2f  max=%.2f  mean=%.2f  n=%d"
              % (d["p50"], d["p95"], d["p99"], d["max"], d["mean"], d["n"]))

    if args.db and before is not None:
        after = db_snapshot()
        if after is None:
            print("[skip] 数据库后快照失败")
        else:
            print("-" * 58)
            print("数据库写入（关键证据）")
            print("  danmaku INSERT 累计 : %d → %d（增量 %d）"
                  % (before[0], after[0], after[0] - before[0]))
            print("  danmaku 表行数      : %d → %d（增量 %d）"
                  % (before[1], after[1], after[1] - before[1]))
            if after[0] - before[0] == 0 and after[1] - before[1] == 0:
                print("  ✅ 发 %d 条弹幕，数据库零写入" % total_sent)
            else:
                print("  → 有写入。改造前的版本属预期；改造后不该出现")

    print("=" * 58)
    print("\n注意：「实测吞吐」= 发送条数 ÷ 发送耗时，受 --rate 节流，"
          "不代表服务端上限。")
    print("写简历时记得带环境说明：单机 / MySQL+Redis+应用同机 / 内网回环。")
    print("A/B 对照：git stash push -m x → 重跑 → git stash pop")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n[中断]")
        sys.exit(130)
