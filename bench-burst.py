#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
点赞接口【突发冲击】测试 —— 回答"1 秒内到底能接多少"。

普通压测只给一个总 QPS，回答不了这个问题。本脚本做两件别的事：

  1. 一次性砸出 N 个请求（所有线程在同一个闸门后同时出发）
  2. 按【秒】统计完成数 —— 直接看出第 1 秒消化了多少、第 2 秒多少，
     以及失败从第几秒开始出现（那就是排队超过 3 秒超时的时刻）

三种服务端表现，一眼能分开：
  · 第 1 秒完成数 ≈ 每秒稳定吞吐        → 系统在匀速处理，没被压垮
  · 第 1 秒完成数很少、后面越来越多      → 启动有预热开销
  · 某秒开始出现失败                     → 队列超过 3 秒超时，这是硬上限

用法：
    # 三轮递增：600 / 1200 / 1800，每轮用不同的账号（避免重复点赞走快路径）
    python bench-burst.py --video 102 --sizes 600,1200,1800

    # 单轮，更大冲击
    python bench-burst.py --video 102 --sizes 3000 --concurrency 500

前置：
    mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
    且已经跑过 bench-like.py，存在 .like-tokens.json（复用账号）

⚠️ 每轮消耗一批【没用过】的账号。账号总数不够时会自动截断。
   跑完记得 --cleanup 或换个视频，否则这些账号已经点过这个视频了。
"""

import argparse
import http.client
import json
import os
import sys
import threading
import time
import urllib.parse

BASE = os.environ.get("BASE", "http://127.0.0.1:8080").rstrip("/")
_u = urllib.parse.urlparse(BASE)
HOST = _u.hostname or "127.0.0.1"
PORT = _u.port or 80
TOKEN_CACHE = os.environ.get("TOKEN_CACHE", ".like-tokens.json")

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


def do_like(video, token):
    headers = {"Authorization": "Bearer " + token}
    c = conn()
    try:
        c.request("POST", "/api/video/%d/like" % video, headers=headers)
        r = c.getresponse()
        body = r.read()
        return r.status, body
    except Exception:
        drop_conn()
        raise


def pct(vals, p):
    if not vals:
        return 0.0
    k = (len(vals) - 1) * p / 100.0
    f = int(k)
    c = min(f + 1, len(vals) - 1)
    return vals[f] if f == c else vals[f] + (vals[c] - vals[f]) * (k - f)


def burst(video, tokens, concurrency):
    """同时出发，跑完 tokens。返回每个请求的 (完成时刻秒, 延迟毫秒, 是否成功)。"""
    n = len(tokens)
    concurrency = min(concurrency, n)
    records = []
    lock = threading.Lock()
    gate = threading.Event()
    cursor = [0]

    def worker():
        gate.wait()
        while True:
            with lock:
                if cursor[0] >= n:
                    return
                i = cursor[0]
                cursor[0] += 1
            t0 = time.perf_counter()
            ok = False
            try:
                st, body = do_like(video, tokens[i])
                # HTTP 200 且业务 code=0 才算成功
                if st == 200:
                    try:
                        ok = json.loads(body.decode("utf-8", "replace")).get("code") == 0
                    except Exception:
                        ok = False
            except Exception:
                ok = False
            t1 = time.perf_counter()
            with lock:
                records.append((t1, (t1 - t0) * 1000.0, ok))

    threads = [threading.Thread(target=worker) for _ in range(concurrency)]
    for t in threads:
        t.start()
    time.sleep(0.15)                 # 等所有线程就位
    t_start = time.perf_counter()
    gate.set()
    for t in threads:
        t.join()
    t_end = time.perf_counter()

    # 把完成时刻换算成相对秒数
    records = [(t - t_start, lat, ok) for (t, lat, ok) in records]
    return records, (t_end - t_start)


def report(records, elapsed, size, concurrency, window):
    ok_recs = [r for r in records if r[2]]
    fail_recs = [r for r in records if not r[2]]
    lats = sorted(r[1] for r in ok_recs)

    print("\n" + "=" * 62)
    print("冲击 %d 个请求（客户端并发 %d）" % (size, concurrency))
    print("=" * 62)
    print("全部完成耗时        : %.2f s" % elapsed)
    print("成功 / 失败         : %d / %d" % (len(ok_recs), len(fail_recs)))
    if ok_recs:
        print("整体吞吐            : %.0f 请求/秒" % (len(ok_recs) / elapsed if elapsed else 0))
        print("延迟 P50/P95/P99    : %.0f / %.0f / %.0f ms"
              % (pct(lats, 50), pct(lats, 95), pct(lats, 99)))

    # ── 按时间窗口统计完成数：这是本脚本的核心输出 ──
    buckets = {}
    for (done_at, _lat, ok) in records:
        if not ok:
            continue
        b = int(done_at // window)
        buckets[b] = buckets.get(b, 0) + 1

    if buckets:
        print("-" * 62)
        print("每 %g 秒完成数（从请求发出的那一刻开始计时）" % window)
        max_b = max(buckets)
        cum = 0
        for b in range(max_b + 1):
            c = buckets.get(b, 0)
            cum += c
            bar = "#" * min(60, int(c / max(1, max(buckets.values()) / 60.0)))
            print("  第 %2d 个窗口 (%4.1f~%4.1f s): %5d 个累计 %5d  %s"
                  % (b + 1, b * window, (b + 1) * window, c, cum, bar))

        first = buckets.get(0, 0)
        print("-" * 62)
        print("★ 第 1 个 %g 秒窗口内完成 : %d 个" % (window, first))
        if fail_recs:
            first_fail = min(r[0] for r in fail_recs)
            print("★ 首个失败出现在        : %.2f s（说明排队开始超过超时阈值）"
                  % first_fail)
        else:
            print("★ 全程零失败            : 排队没有超过服务端超时阈值")

    return len(ok_recs), len(fail_recs)


def cleanup(video, tokens, concurrency):
    print("\n[收尾] 取消 %d 个赞..." % len(tokens))
    sem = threading.Semaphore(concurrency)
    lock = threading.Lock()
    cnt = [0]

    def work(tk):
        with sem:
            try:
                c = conn()
                c.request("DELETE", "/api/video/%d/like" % video,
                          headers={"Authorization": "Bearer " + tk})
                c.getresponse().read()
                with lock:
                    cnt[0] += 1
            except Exception:
                pass

    ts = [threading.Thread(target=work, args=(t,)) for t in tokens]
    for t in ts:
        t.start()
        while threading.active_count() > concurrency + 8:
            time.sleep(0.002)
    for t in ts:
        t.join()
    print("已取消 %d 个赞" % cnt[0])


def main():
    ap = argparse.ArgumentParser(description="点赞接口突发冲击测试（按秒统计完成数）")
    ap.add_argument("--video", type=int, default=102, help="目标视频 ID（换一个没点过的）")
    ap.add_argument("--sizes", default="600,1200,1800",
                    help="各轮冲击的请求数，逗号分隔，如 600,1200,1800")
    ap.add_argument("--concurrency", type=int, default=500,
                    help="客户端同时在飞的请求数（线程上限，建议 300~800）")
    ap.add_argument("--window", type=float, default=1.0, help="统计窗口（秒）")
    ap.add_argument("--cleanup", action="store_true", help="结束后取消这些赞")
    args = ap.parse_args()

    sizes = [int(s) for s in args.sizes.split(",") if s.strip()]

    if not os.path.exists(TOKEN_CACHE):
        print("[致命] 找不到 %s，请先跑一次 bench-like.py 造号" % TOKEN_CACHE)
        return 2
    with open(TOKEN_CACHE, "r", encoding="utf-8") as f:
        all_tokens = json.load(f)
    print("账号池：%d 个（来自 %s）" % (len(all_tokens), TOKEN_CACHE))

    total_need = sum(sizes)
    if total_need > len(all_tokens):
        print("[warn] 需要 %d 个账号，池里只有 %d 个 —— 会截断后面的轮次"
              % (total_need, len(all_tokens)))

    print("目标视频：%d（务必换一个这些账号没点过的，否则会走'已点赞'快路径）"
          % args.video)
    print("计划轮次：%s，客户端并发 %d\n" % (sizes, args.concurrency))

    offset = 0
    used = []
    summary = []
    for size in sizes:
        chunk = all_tokens[offset:offset + size]
        if not chunk:
            print("[skip] 账号池已用尽，跳过 %d 这一轮" % size)
            break
        actual = len(chunk)
        if actual < size:
            print("[warn] 第 %d 轮只凑到 %d 个账号（计划 %d）" % (len(summary) + 1, actual, size))
        print("\n>>> 第 %d 轮：冲击 %d 个请求" % (len(summary) + 1, actual))
        recs, el = burst(args.video, chunk, args.concurrency)
        ok, bad = report(recs, el, actual, min(args.concurrency, actual), args.window)
        summary.append((actual, ok, bad, el))
        used.extend(chunk)
        offset += actual
        time.sleep(1.0)          # 两轮之间歇一下，避免影响下一轮

    print("\n" + "=" * 62)
    print("汇总")
    print("=" * 62)
    print("%8s %8s %8s %10s %12s" % ("冲击量", "成功", "失败", "耗时(s)", "第1窗口完成"))
    for (size, ok, bad, el) in summary:
        print("%8d %8d %8d %10.2f" % (size, ok, bad, el))

    if args.cleanup:
        cleanup(args.video, used, min(args.concurrency, 500))
    else:
        print("\n提示：加 --cleanup 可取消这些赞；或下次换个 --video 继续测。")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n[中断]")
        sys.exit(130)
