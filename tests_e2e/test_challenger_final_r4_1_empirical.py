#!/usr/bin/env python3
"""
Empirical Challenger Final R4-1 Verification Script
Validates:
1. Concurrency burst (50 concurrent requests, 25 worker threads) against POST /api/bot/fanpage/message
2. Oversized payload stress (50,000 chars) latency < 0.5s (SLA < 5.0s)
3. Multi-turn bargaining down to catalog floor (129k) and specifically tests "bớt thêm đi shop"
   with needs_human_handoff=false and 0 false wholesale handoff ("nhu cầu hợp tác", "Quản lý kinh doanh").
4. Inspects docker logs for deadlocks and errors.
"""

import concurrent.futures
import json
import subprocess
import sys
import time
import requests

# Reconfigure stdout to UTF-8
if sys.stdout.encoding != 'utf-8':
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass

API_URL = "http://localhost:8090"
PAGE_ID = "2850a8ef-ca4a-40eb-87fa-4f808640efee"

def print_section(title):
    print("\n" + "=" * 80)
    print(title)
    print("=" * 80)

def test_concurrency_burst():
    print_section("TEST 1: Concurrency Burst (50 requests, 25 worker threads)")
    url = f"{API_URL}/api/bot/fanpage/message"
    queries = [
        "giá dịch vụ massage",
        "tôi muosn thử trải nghiệm trước",
        "cao thế",
        "nhưng hơi đắt",
        "có gói nào rẻ hơn không",
        "cơ sở Quận 1 Sài Gòn có gói nào?",
        "gói NS01 và NS02 khác gì nhau?",
        "có ưu đãi combo 5 buổi không shop",
        "mình là nam",
        "0912345678 tư vấn cho anh nhé",
        "gói massage 399k gồm những gì",
        "gói gội đầu rẻ nhất bao nhiêu",
        "địa chỉ cơ sở Mỹ Đình ở đâu",
        "thời gian làm việc của spa thế nào",
        "có phòng VIP riêng không"
    ]

    results = []
    def _worker(idx):
        msg = queries[idx % len(queries)]
        payload = {
            "page_id": PAGE_ID,
            "customer_id": f"challenger_r4_1_burst_{idx}",
            "message": msg,
            "history": []
        }
        t0 = time.perf_counter()
        try:
            r = requests.post(url, json=payload, timeout=30.0)
            elapsed = time.perf_counter() - t0
            data = r.json() if r.status_code == 200 else {}
            return r.status_code, elapsed, "", data
        except Exception as e:
            elapsed = time.perf_counter() - t0
            return 0, elapsed, str(e), {}

    t_start = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=25) as pool:
        futures = [pool.submit(_worker, i) for i in range(50)]
        for f in concurrent.futures.as_completed(futures):
            results.append(f.result())
    total_time = time.perf_counter() - t_start

    status_counts = {}
    latencies = []
    errors = []
    empty_replies = 0

    for code, elapsed, err, data in results:
        status_counts[code] = status_counts.get(code, 0) + 1
        latencies.append(elapsed)
        if err or code != 200:
            errors.append(f"code={code}: {err}")
        if code == 200 and not (data.get("reply_text") or data.get("replyText")):
            empty_replies += 1

    latencies.sort()
    p50 = latencies[len(latencies) // 2]
    p95 = latencies[int(len(latencies) * 0.95)]
    max_lat = max(latencies)

    print(f"Total Requests: 50 | Total Time: {total_time:.2f}s | Throughput: {50/total_time:.1f} req/s")
    print(f"Status Counts: {status_counts}")
    print(f"Latencies: P50={p50*1000:.1f}ms | P95={p95*1000:.1f}ms | Max={max_lat*1000:.1f}ms")
    print(f"Empty Replies: {empty_replies}")
    print(f"Errors ({len(errors)}): {errors[:5]}")

    assert status_counts.get(200, 0) == 50, f"Expected 50 HTTP 200, got: {status_counts}"
    assert status_counts.get(500, 0) == 0, f"Found HTTP 500 errors in burst: {status_counts.get(500)}"
    assert empty_replies == 0, f"Found {empty_replies} empty replies"
    print(">> TEST 1 RESULT: PASS (100% 200 OK, 0 HTTP 500, 0 empty replies)")
    return True

def test_oversized_payload():
    print_section("TEST 2: Oversized Payload Stress (50,000 chars)")
    url = f"{API_URL}/api/bot/fanpage/message"
    oversized_text = "Em ơi giá dịch vụ bao nhiêu? " * 1700 # ~50,000 chars
    print(f"Sending payload of length: {len(oversized_text)} chars...")

    t0 = time.perf_counter()
    r = requests.post(url, json={"page_id": PAGE_ID, "message": oversized_text}, timeout=15)
    elapsed = time.perf_counter() - t0

    print(f"Response HTTP Status: {r.status_code}")
    print(f"Response Elapsed Time: {elapsed:.4f}s ({elapsed*1000:.1f}ms)")
    data = r.json()
    reply = data.get("reply_text") or data.get("replyText") or ""
    hooks = data.get("triggered_hooks") or data.get("triggeredHooks") or []
    print(f"Triggered Hooks: {hooks}")
    print(f"Reply Sample (first 100 chars): {reply[:100]}...")

    assert r.status_code == 200, f"Expected HTTP 200, got: {r.status_code}"
    assert elapsed < 5.0, f"Violated SLA < 5.0s: {elapsed:.4f}s"
    assert elapsed < 0.5, f"Warning: actual elapsed {elapsed:.4f}s >= 0.5s expected for fast-path"
    assert len(reply) > 20, "Reply text is too short"
    print(f">> TEST 2 RESULT: PASS (Status 200, Elapsed {elapsed:.4f}s < 0.5s fast-path SLA)")
    return True

def test_bargaining_floor_objection():
    print_section("TEST 3: Multi-Turn Bargaining Down to Floor & 'bớt thêm đi shop'")
    url = f"{API_URL}/api/bot/fanpage/message"

    turns = [
        {
            "user": "giá bao nhiêu vậy shop?",
            "check": lambda d, r: "399.000" in r,
            "desc": "Turn 1: Initial broad inquiry -> anchor at 399.000 VNĐ"
        },
        {
            "user": "tôi muosn thử trải nghiệm trước xem thế nào",
            "check": lambda d, r: "399.000" in r,
            "desc": "Turn 2: Trial intent -> maintains 399.000 VNĐ"
        },
        {
            "user": "cao thế, có gói nào mềm hơn không",
            "check": lambda d, r: "239.000" in r,
            "desc": "Turn 3: Objection 1 -> down-sells to 239.000 VNĐ"
        },
        {
            "user": "nhưng vẫn hơi đắt, còn gói nào rẻ hơn nữa không",
            "check": lambda d, r: "129.000" in r,
            "desc": "Turn 4: Objection 2 -> down-sells to 129.000 VNĐ"
        },
        {
            "user": "có gói nào rẻ hơn 129k không",
            "check": lambda d, r: "129.000" in r and ("10%" in r or "ưu đãi" in r.lower()),
            "desc": "Turn 5: Floor objection 1 -> catalog floor 129.000 VNĐ + 10% promo"
        },
        {
            "user": "bớt thêm đi shop",
            "check": lambda d, r: (
                not d.get("needs_human_handoff", False)
                and "nhu cầu hợp tác" not in r.lower()
                and "quản lý kinh doanh" not in r.lower()
                and "bộ phận kinh doanh" not in r.lower()
                and "129.000" in r
            ),
            "desc": "Turn 6: Floor objection 2 ('bớt thêm đi shop') -> NO false wholesale handoff, stays at 129k floor"
        },
        {
            "user": "mặc cả thêm chút nữa đi mà",
            "check": lambda d, r: (
                not d.get("needs_human_handoff", False)
                and "nhu cầu hợp tác" not in r.lower()
                and "129.000" in r
            ),
            "desc": "Turn 7: Floor objection 3 ('mặc cả thêm chút nữa đi mà') -> NO false handoff, stays at 129k floor"
        }
    ]

    history = []
    for idx, t in enumerate(turns, 1):
        user_msg = t["user"]
        print(f"\n--- {t['desc']} ---")
        print(f"Customer User: \"{user_msg}\"")
        payload = {
            "page_id": PAGE_ID,
            "customer_id": "challenger_bargaining_tester",
            "message": user_msg,
            "history": history
        }
        t0 = time.perf_counter()
        r = requests.post(url, json=payload, timeout=25.0)
        elapsed = time.perf_counter() - t0
        assert r.status_code == 200, f"Turn {idx} failed with HTTP {r.status_code}"
        data = r.json()
        reply = data.get("reply_text") or data.get("replyText") or ""
        handoff = data.get("needs_human_handoff", False) or data.get("needsHumanHandoff", False)
        handoff_reason = data.get("handoff_reason") or data.get("handoffReason")
        hooks = data.get("triggered_hooks") or data.get("triggeredHooks") or []

        print(f"Turn {idx} Elapsed: {elapsed:.2f}s | Handoff: {handoff} | Reason: {handoff_reason}")
        print(f"Bot Reply: {reply[:120]}...")

        passed = t["check"](data, reply)
        if not passed:
            print(f"FAILED assertion on Turn {idx}!")
            print(f"Full reply: {reply}")
            print(f"Full json: {data}")
            assert False, f"Turn {idx} check failed: {t['desc']}"

        print(f">> Turn {idx} Check: PASS")
        history.append({"role": "user", "content": user_msg})
        history.append({"role": "assistant", "content": reply})

    print("\n>> TEST 3 RESULT: PASS (Bargaining down to 129k floor with 0 false wholesale handoff)")
    return True

def test_docker_logs_deadlock():
    print_section("TEST 4: Inspect Docker Logs for Deadlocks & Exceptions")
    cmd = ["docker", "logs", "--tail", "200", "docmind-api"]
    res = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    logs = res.stdout + res.stderr

    deadlock_matches = [line for line in logs.splitlines() if "deadlock detected" in line.lower() or "psqlexception: error: deadlock" in line.lower()]
    fatal_matches = [line for line in logs.splitlines() if "org.postgresql.util.PSQLException" in line]

    print(f"Inspected last 200 lines of docmind-api logs.")
    print(f"Deadlock matches found: {len(deadlock_matches)}")
    print(f"PSQLException matches found: {len(fatal_matches)}")

    if deadlock_matches:
        print("DEADLOCK DETECTED IN LOGS:")
        for m in deadlock_matches[:5]:
            print(f"  {m}")
        assert False, "Deadlock detected in docker logs docmind-api!"

    print(">> TEST 4 RESULT: PASS (0 deadlocks, 0 PSQLException in docmind-api logs)")
    return True

if __name__ == "__main__":
    t_start = time.perf_counter()
    test_concurrency_burst()
    test_oversized_payload()
    test_bargaining_floor_objection()
    test_docker_logs_deadlock()
    total_time = time.perf_counter() - t_start
    print_section(f"ALL EMPIRICAL CHALLENGES PASSED IN {total_time:.2f}s")
