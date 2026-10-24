#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
直播间「人气点赞」压测 —— 纯标准库，产出可与视频点赞对比的 QPS。

为什么单独测这个接口：
  它和「视频点赞」是同一个词、完全不同的实现 ——
    视频点赞：MySQL 事务写入（权威表 user_like）   → 慢，受连接池/事务限制
    直播间人气：Redis Lua 原子累加（纯内存计数器）   → 快，不碰数据库
  压出来的差距，比任何单个数字都能说明「为什么要把热点计数从 MySQL 挪到 Redis」。

服务端规则（都在代码里，脚本按它设计）：
  · POST /api/live/{roomId}/like   body {"delta":1}
  · 限速：同一用户在同一房间每秒最多 20 次
    （Redis 键 live:like:rate:{room}:{user}，TTL 1 秒）
    → 单账号打不出高 QPS，必须多账号。
  · delta 合法区间 1~50；本脚本固定 delta=1，让「请求数 == 人气增量」，便于校验。
  · 返回 code=0 成功；code=1 且 msg 含「太快」表示被限速。

请求顺序：轮转（第 1 轮每人 1 次、第 2 轮每人 1 次…）。
  这样同一个账号的请求会分散在整场压测里，而不是在同一毫秒内连发 20 次，
  更贴近真实流量，也避免人为制造突刺。

校验方式（不需要连 Redis）：
  用 GET /api/live/{roomId}/like（游客可读）读人气值，跑前跑后各一次，
  增量必须等于「成功请求数 × delta」。

用法：
    # 先看有哪些直播间
    curl http://localhost:8080/api/live/rooms

    # 小规模试水
    python bench-live-like.py --room 201 --users 50 --per-user 20 --concurrency 50

    # 正式：1000 个账号，每人 20 次（贴着限速上限），并发 300
    python bench-live-like.py --room 201 --users 1000 --per-user 20 --concurrency 300

前置：
    mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
    且已跑过 bench-like.py 生成 .like-tokens.json（复用账号）

⚠️ 副作用：会把这个直播间的人气（Redis live:like:{roomId}）打高，
   关播时才会结算写回 live_room.like_count。
   想恢复：对该房间重新开播一次（开播会把人气清零）。
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


def req(method, path, token=None, body=None):
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


def pct(vals, p):
    if not vals:
        return 0.0
    k = (len(vals) - 1) * p / 100.0
    f = int(k)
    c = min(f + 1, len(vals) - 1)
    return vals[f] if f == c else vals[f] + (vals[c] - vals[f]) * (k - f)


def read_count(room):
    """读当前人气（游客可读，不需要 token）。"""
    try:
        st, body = req("GET", "/api/live/%d/like" % room)
        if st == 200 and (body or {}).get("code") == 0:
            return body.get("data")
    except Exception as e:
        print("[warn] 读人气失败：%s" % e)
    return None


def main():
    ap = argparse.ArgumentParser(description="直播间人气点赞 QPS 压测")
    ap.add_argument("--room", type=int, default=201, help="直播间 ID")
    ap.add_argument("--users", type=int, default=1000, help="用多少个账号")
    ap.add_argument("--per-user", type=int, default=20,
                    help="每个账号打多少次（服务端限速 20 次/秒/用户，超过 20 会大量被拒）")
    ap.add_argument("--concurrency", type=int, default=300, help="同时在飞的请求数")
    ap.add_argument("--delta", type=int, default=1, help="每次点赞的人气增量（1~50）")
    args = ap.parse_args()

    if args.delta < 1 or args.delta > 50:
        print("[致命] delta 必须在 1~50（服务端 MAX_DELTA=50）")
        return 2

    if not os.path.exists(TOKEN_CACHE):
        print("[致命] 找不到 %s，请先跑一次 bench-like.py 造号" % TOKEN_CACHE)
        return 2
    with open(TOKEN_CACHE, "r", encoding="utf-8") as f:
        all_tokens = json.load(f)
    tokens = all_tokens[:args.users]
    if not tokens:
        print("[致命] 账号池是空的")
        return 2

    total = len(tokens) * args.per_user
    print("直播间人气压测 —— %s  房间=%d" % (BASE, args.room))
    print("账号=%d  每人=%d 次  总请求=%d  并发=%d  delta=%d"
          % (len(tokens), args.per_user, total, args.concurrency, args.delta))
    if args.per_user > 20:
        print("  ⚠️ 每人 %d 次 > 服务端限速 20 次/秒，会有一部分被拒绝（属预期）"
              % args.per_user)

    before = read_count(args.room)
    print("基线人气：%s" % before)

    # 轮转排队：第 1 轮每人 1 次，第 2 轮每人 1 次……避免同一账号连发
    jobs = []
    for _round in range(args.per_user):
        jobs.extend(tokens)
    n = len(jobs)

    lat, rate_limited, errors = [], [], []
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
            token = jobs[i]
            t0 = time.perf_counter()
            try:
                st, body = req("POST", "/api/live/%d/like" % args.room,
                               token=token, body={"delta": args.delta})
                dt = (time.perf_counter() - t0) * 1000.0
                code = (body or {}).get("code")
                msg = str((body or {}).get("msg") or "")
                with lock:
                    if st == 200 and code == 0:
                        lat.append(dt)
                    elif code == 1 and ("太快" in msg or "慢一点" in msg):
                        rate_limited.append(dt)
                    else:
                        errors.append((st, code, msg[:80]))
            except Exception as e:
                with lock:
                    errors.append((0, repr(e)[:80]))

    threads = [threading.Thread(target=worker)
               for _ in range(min(args.concurrency, n))]
    for t in threads:
        t.start()
    time.sleep(0.2)
    t_start = time.perf_counter()
    gate.set()
    for t in threads:
        t.join()
    elapsed = time.perf_counter() - t_start

    lat.sort()
    print("\n" + "=" * 62)
    print("结果")
    print("=" * 62)
    print("总请求              : %d" % n)
    print("成功                : %d" % len(lat))
    print("被限速拒绝          : %d" % len(rate_limited))
    print("其他错误            : %d" % len(errors))
    if errors:
        print("错误样例            : %s" % errors[0])
    print("总耗时              : %.2f s" % elapsed)
    if elapsed > 0:
        print("实测 QPS            : %.0f 请求/秒" % (len(lat) / elapsed))
    if lat:
        print("延迟 P50/P95/P99    : %.2f / %.2f / %.2f ms"
              % (pct(lat, 50), pct(lat, 95), pct(lat, 99)))

    # ── 校验：人气增量应等于 成功请求数 × delta ──
    time.sleep(0.3)
    after = read_count(args.room)
    print("-" * 62)
    print("校验")
    print("  基线人气          : %s" % before)
    print("  跑完人气          : %s" % after)
    if before is not None and after is not None:
        got = after - before
        expect = len(lat) * args.delta
        print("  实际增量          : %d（期望 %d）" % (got, expect))
        if got == expect:
            print("  ✅ 人气增量与成功请求数完全一致，限速与计数原子性正确")
        else:
            print("  ⚠️ 对不上：可能有别的客户端同时在点这个房间，或 delta 配置不一致")

    print("\n" + "=" * 62)
    print("把它和视频点赞的 QPS 放一起对比：")
    print("  视频点赞（MySQL 事务写入）   约 600 QPS")
    print("  直播间人气（Redis Lua 计数） 见上面「实测 QPS」")
    print("  差距即为「把热点计数从 MySQL 挪到 Redis」的收益")
    print("=" * 62)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n[中断]")
        sys.exit(130)
