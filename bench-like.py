#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
视频点赞并发压测 —— 纯标准库（数据库校验可选 pymysql）。

⚠️ 关键前提：视频点赞是【按人去重】的（uk_user_video 唯一索引）。
   同一个账号点 10000 次，数据库也只会有 1 行、计数只 +1。
   所以"10000 人同时点赞"必须有 10000 个不同账号 —— 本脚本就用
   dev profile 的 mock 短信批量注册这批账号（登录即注册）。

三个阶段：
  1. 造号：批量登录 N 个账号，拿 N 个 token（可缓存复用，避免重复造）
  2. 压测：N 个点赞请求，用 --concurrency 条同时"在飞"，统计 RPS 与延迟分位
  3. 校验：user_like 行数必须正好 +N（少一个都说明有请求没成功）

用法：
    # 完整跑一遍：1 万个账号点赞视频 111，并发 200
    python bench-like.py --video 111 --users 10000 --concurrency 200

    # 小规模先试水（30 秒内出结果，确认脚本没问题）
    python bench-like.py --video 111 --users 200 --concurrency 50

    # 复用上次造好的 token，跳过造号阶段
    python bench-like.py --users 10000 --skip-setup

    # 测完把赞取消掉，恢复现场
    python bench-like.py --users 10000 --skip-setup --cleanup

前置：
    mvn spring-boot:run "-Dspring-boot.run.profiles=dev"

⚠️ 副作用（务必知道）：
    · 会在 user 表插入 N 个账号（测试用户，手机号 139xxxxxxxx）
    · 会在 user_like 表插入 N 行
    · video.like_count 是【派生列】，由对账任务每 30 秒刷一次，
      所以压测刚结束时它还是旧值，别拿它当校验依据 —— 用 user_like 行数
"""

import argparse
import http.client
import json
import os
import random
import sys
import threading
import time
import urllib.parse

BASE = os.environ.get("BASE", "http://127.0.0.1:8080").rstrip("/")
_u = urllib.parse.urlparse(BASE)
HOST = _u.hostname or "127.0.0.1"
PORT = _u.port or 80
TOKEN_CACHE = os.environ.get("TOKEN_CACHE", ".like-tokens.json")

# 每个线程复用自己的 keep-alive 连接，避免 1 万次 TCP 握手污染延迟测量
_local = threading.local()


def conn():
    c = getattr(_local, "c", None)
    if c is None:
        c = http.client.HTTPConnection(HOST, PORT, timeout=30)
        _local.c = c
    return c


def drop_conn():
    c = getattr(_local, "c", None)
    if c is not None:
        try:
            c.close()
        except Exception:
            pass
    _local.c = None


def request(method, path, token=None, body=None, timeout=30):
    """返回 (status, parsed_json)。失败抛异常，由调用方决定怎么算。"""
    headers = {}
    payload = None
    if body is not None:
        payload = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    c = conn()
    try:
        c.request(method, path, body=payload, headers=headers)
        r = c.getresponse()
        raw = r.read()
        try:
            return r.status, json.loads(raw.decode("utf-8", "replace"))
        except Exception:
            return r.status, {"raw": raw.decode("utf-8", "replace")}
    except Exception:
        drop_conn()
        raise


# ─────────────────────────── 阶段 1：造号 ───────────────────────────
def setup_users(n, concurrency):
    """批量注册/登录 n 个账号，返回 token 列表。"""
    print("\n[阶段 1] 造号：需要 %d 个不同账号（视频点赞按人去重）" % n)
    tokens = [None] * n
    phones = set()
    while len(phones) < n:
        phones.add("139" + "%08d" % random.randrange(10 ** 8))
    phones = list(phones)

    done = [0]
    failed = [0]
    lock = threading.Lock()

    def work(i):
        try:
            st, s = request("POST", "/api/auth/sms/send", body={"phone": phones[i]})
            code = ((s or {}).get("data") or {}).get("mockCode")
            if not code:
                raise RuntimeError("拿不到 mockCode（后端不是 dev profile？）：%s %s"
                                   % (st, s))
            st, l = request("POST", "/api/auth/sms/login",
                            body={"phone": phones[i], "code": code})
            data = (l or {}).get("data") or {}
            if (l or {}).get("code") != 0 or not data.get("token"):
                raise RuntimeError("登录失败：%s %s" % (st, l))
            tokens[i] = data["token"]
        except Exception as e:
            with lock:
                failed[0] += 1
                if failed[0] <= 3:
                    print("  造号失败：%s" % e)
        finally:
            with lock:
                done[0] += 1
                if done[0] % max(1, n // 10) == 0 or done[0] == n:
                    print("  进度 %d / %d" % (done[0], n))

    t0 = time.perf_counter()
    _run_pool(range(n), work, concurrency)
    elapsed = time.perf_counter() - t0

    ok = [t for t in tokens if t]
    print("造号完成：成功 %d / %d，耗时 %.2f s（%.0f 账号/秒）"
          % (len(ok), n, elapsed, len(ok) / elapsed if elapsed else 0))
    return ok


def _run_pool(items, fn, concurrency):
    """固定并发跑完 items。用线程 + 秒表，避免开太多线程。"""
    sem = threading.Semaphore(concurrency)
    threads = []

    def wrapped(x):
        with sem:
            fn(x)

    for x in items:
        th = threading.Thread(target=wrapped, args=(x,))
        th.start()
        threads.append(th)
        # 限制同时存活的线程数（不能让 1 万个线程同时存在）
        while threading.active_count() > concurrency + 8:
            time.sleep(0.002)
    for th in threads:
        th.join()


# ─────────────────────────── 阶段 2：压测 ───────────────────────────
def run_burst(tokens, video, concurrency):
    print("\n[阶段 2] 压测：%d 个点赞请求，同时最多 %d 条在飞"
          % (len(tokens), concurrency))
    if concurrency > 2000:
        print("  ⚠️ 并发被限制到 2000（Python 线程撑不住更多）")
        concurrency = 2000

    latencies = []
    errors = []
    codes = {}
    lock = threading.Lock()
    gate = threading.Event()
    cursor = [0]
    n = len(tokens)

    def worker():
        gate.wait()          # 所有线程就绪后一起出发
        while True:
            with lock:
                if cursor[0] >= n:
                    return
                i = cursor[0]
                cursor[0] += 1
            t0 = time.perf_counter()
            try:
                st, body = request("POST", "/api/video/%d/like" % video,
                                   token=tokens[i])
                dt = (time.perf_counter() - t0) * 1000.0
                with lock:
                    latencies.append(dt)
                    key = "http_%d" % st
                    codes[key] = codes.get(key, 0) + 1
                    if st != 200 or (body or {}).get("code") != 0:
                        errors.append((st, str(body)[:120]))
            except Exception as e:
                with lock:
                    errors.append((0, repr(e)[:120]))

    workers = [threading.Thread(target=worker) for _ in range(min(concurrency, n))]
    for w in workers:
        w.start()
    time.sleep(0.2)
    t_start = time.perf_counter()
    gate.set()
    for w in workers:
        w.join()
    elapsed = time.perf_counter() - t_start

    return latencies, errors, codes, elapsed


def pct(vals, p):
    if not vals:
        return 0.0
    k = (len(vals) - 1) * p / 100.0
    f = int(k)
    c = min(f + 1, len(vals) - 1)
    return vals[f] if f == c else vals[f] + (vals[c] - vals[f]) * (k - f)


# ─────────────────────────── 阶段 3：数据库校验 ───────────────────────────
def db_counts(video):
    try:
        import pymysql
    except ImportError:
        return None
    try:
        c = pymysql.connect(
            host=os.environ.get("DB_HOST", "127.0.0.1"),
            port=int(os.environ.get("DB_PORT", "3306")),
            user=os.environ.get("DB_USERNAME", "root"),
            password=os.environ.get("DB_PASSWORD", "123456"),
            database=os.environ.get("DB_NAME", "db1"),
            charset="utf8mb4", connect_timeout=5)
        try:
            with c.cursor() as cur:
                cur.execute("SELECT COUNT(*) FROM user_like WHERE video_id=%s", (video,))
                ul = cur.fetchone()[0]
                cur.execute("SELECT like_count FROM video WHERE id=%s", (video,))
                row = cur.fetchone()
                vc = row[0] if row else None
                return int(ul), (int(vc) if vc is not None else None)
        finally:
            c.close()
    except Exception as e:
        print("[warn] 数据库校验失败：%s" % e)
        return None


# ─────────────────────────── 收尾：取消赞 ───────────────────────────
def cleanup(tokens, video, concurrency):
    print("\n[收尾] 取消这 %d 个账号的赞，恢复现场" % len(tokens))
    sem = threading.Semaphore(concurrency)
    ok = [0]

    def work(tk):
        with sem:
            try:
                request("DELETE", "/api/video/%d/like" % video, token=tk)
                with lock_ok:
                    ok[0] += 1
            except Exception:
                pass

    lock_ok = threading.Lock()
    threads = [threading.Thread(target=work, args=(tk,)) for tk in tokens]
    for th in threads:
        th.start()
        while threading.active_count() > concurrency + 8:
            time.sleep(0.002)
    for th in threads:
        th.join()
    print("已取消 %d 个赞" % ok[0])


def main():
    ap = argparse.ArgumentParser(description="视频点赞 1 万人并发压测")
    ap.add_argument("--video", type=int, default=111, help="目标视频 ID")
    ap.add_argument("--users", type=int, default=10000, help="账号数（= 同时点赞的人数）")
    ap.add_argument("--concurrency", type=int, default=200, help="同时在飞的请求数")
    ap.add_argument("--skip-setup", action="store_true", help="复用缓存 token，跳过造号")
    ap.add_argument("--cleanup", action="store_true", help="压测后取消所有赞")
    ap.add_argument("--no-db", action="store_true", help="跳过数据库校验")
    args = ap.parse_args()

    print("视频点赞压测 —— %s  视频=%d  账号=%d  并发=%d"
          % (BASE, args.video, args.users, args.concurrency))

    # ① 拿 token（缓存优先）
    tokens = None
    if args.skip_setup and os.path.exists(TOKEN_CACHE):
        with open(TOKEN_CACHE, "r", encoding="utf-8") as f:
            tokens = json.load(f)
        print("\n[阶段 1] 复用缓存的 %d 个 token（%s）" % (len(tokens), TOKEN_CACHE))
        if len(tokens) < args.users:
            print("  ⚠️ 缓存里的账号不够 %d 个，只会用 %d 个" % (args.users, len(tokens)))
    else:
        tokens = setup_users(args.users, max(20, args.concurrency))
        try:
            with open(TOKEN_CACHE, "w", encoding="utf-8") as f:
                json.dump(tokens, f)
            print("  token 已缓存到 %s，下次可加 --skip-setup 复用" % TOKEN_CACHE)
        except Exception as e:
            print("  [warn] token 缓存失败：%s" % e)

    if not tokens:
        print("[致命] 一个可用账号都没有")
        return 2

    # ② 基线
    before = None if args.no_db else db_counts(args.video)
    if before:
        print("\n基线：user_like=%d，video.like_count=%s" % before)

    # ③ 压测
    lat, errors, codes, elapsed = run_burst(tokens, args.video, args.concurrency)
    lat.sort()

    print("\n" + "=" * 60)
    print("结果")
    print("=" * 60)
    print("发送请求            : %d" % len(tokens))
    print("成功响应            : %d" % len(lat))
    print("失败                : %d" % len(errors))
    if codes:
        print("HTTP 状态分布       : %s"
              % ", ".join("%s=%d" % (k, v) for k, v in sorted(codes.items())))
    if errors:
        print("失败样例            : %s" % errors[0])
    print("总耗时              : %.2f s" % elapsed)
    if elapsed > 0:
        print("实测 RPS            : %.0f 请求/秒" % (len(lat) / elapsed))
    if lat:
        print("-" * 60)
        print("单请求延迟（毫秒）")
        print("  P50=%.1f  P95=%.1f  P99=%.1f  max=%.1f  mean=%.1f"
              % (pct(lat, 50), pct(lat, 95), pct(lat, 99), lat[-1],
                 sum(lat) / len(lat)))

    # ④ 校验
    if before and not args.no_db:
        print("-" * 60)
        after = db_counts(args.video)
        if after:
            delta = after[0] - before[0]
            print("校验（关键证据）")
            print("  user_like 行数     : %d → %d（增量 %d，期望 %d）"
                  % (before[0], after[0], delta, len(lat)))
            print("  video.like_count   : %s → %s"
                  % (before[1], after[1]))
            if delta == len(lat):
                print("  ✅ 每个账号正好写入 1 行，幂等与并发都正确")
            else:
                print("  ⚠️ 增量不等于成功数：可能账号被复用（之前已点过这个视频）")
            if after[1] == before[1]:
                print("  📌 video.like_count 没变是【正常的】：它是派生列，"
                      "由对账任务每 30 秒刷一次")

    print("=" * 60)
    if args.cleanup:
        cleanup(tokens, args.video, max(20, args.concurrency))
    else:
        print("\n提示：加 --cleanup 可以把这些赞取消掉，恢复现场。")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n[中断]")
        sys.exit(130)
