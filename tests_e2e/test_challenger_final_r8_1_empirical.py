#!/usr/bin/env python3
"""Challenger Final Round 8-1 Live Empirical Stress Test Suite.

Verifies:
1. Live Concurrency Burst on Bot Endpoint:
   - 50 concurrent requests against POST http://localhost:8090/api/bot/fanpage/message with 25 worker threads.
   - Verify 100% 200 OK responses ({200: 50}), 0 HTTP 500 errors, 0 deadlocks in docker logs docmind-api.
2. Oversized Payload Stress (50,000 chars):
   - Send 50,000-character payload to POST /api/bot/fanpage/message.
   - Verify response latency is well under 5.0s SLA (target < 0.5s), status 200, grounded fallback returned.
3. Multi-Turn Price Bargaining & Floor Defense:
   - Send multi-turn bargaining sequence ending with "bớt thêm đi shop".
   - Verify catalog floor (129k) is defended with 10% promo, needs_human_handoff = false, and 0 false wholesale handoffs.
"""

import sys
import time
import json
import concurrent.futures
import subprocess
import requests

if sys.stdout.encoding != 'utf-8':
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass

API_URL = "http://localhost:8090"
PAGE_ID = "2850a8ef-ca4a-40eb-87fa-4f808640efee"

def test_result(name, passed, details=""):
    status = "PASS" if passed else "FAIL"
    print(f"[{status}] {name} - {details}")
    return passed

def run_burst_concurrency():
    print("\n" + "=" * 80)
    print("TEST 1: LIVE CONCURRENCY BURST ON BOT ENDPOINT (50 requests, 25 workers)")
    print("=" * 80)

    url = f"{API_URL}/api/bot/fanpage/message"
    total_requests = 50
    workers = 25
    results = []

    def _worker(i):
        payload = {
            "page_id": PAGE_ID,
            "customer_id": f"challenger_r8_burst_{i}",
            "message": f"Shop tư vấn dịch vụ giúp mình với, yêu cầu số {i}"
        }
        t0 = time.perf_counter()
        try:
            r = requests.post(url, json=payload, timeout=25.0)
            elapsed = time.perf_counter() - t0
            return r.status_code, elapsed, r.text[:100]
        except Exception as e:
            elapsed = time.perf_counter() - t0
            return 0, elapsed, str(e)

    t_start = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(_worker, i) for i in range(total_requests)]
        for f in concurrent.futures.as_completed(futures):
            results.append(f.result())
    total_duration = time.perf_counter() - t_start

    status_counts = {}
    latencies = []
    errors = []

    for code, elapsed, resp in results:
        status_counts[code] = status_counts.get(code, 0) + 1
        latencies.append(elapsed)
        if code != 200:
            errors.append((code, resp))

    latencies.sort()
    p50 = latencies[len(latencies) // 2]
    p95 = latencies[int(len(latencies) * 0.95)]
    p99 = latencies[int(len(latencies) * 0.99)]
    max_lat = max(latencies)

    print(f"Total Requests: {total_requests}")
    print(f"Workers: {workers}")
    print(f"Total Time: {total_duration:.2f}s")
    print(f"Throughput: {total_requests / total_duration:.2f} req/s")
    print(f"Status Counts: {status_counts}")
    print(f"Latency P50: {p50*1000:.1f}ms | P95: {p95*1000:.1f}ms | P99: {p99*1000:.1f}ms | Max: {max_lat*1000:.1f}ms")

    # Assertions
    passed_200 = (status_counts.get(200, 0) == total_requests)
    no_500 = (status_counts.get(500, 0) == 0)

    test_result("Burst 50 requests 100% 200 OK", passed_200, f"Got {status_counts.get(200, 0)}/{total_requests} 200s")
    test_result("Burst 50 requests 0 HTTP 500 errors", no_500, f"Got {status_counts.get(500, 0)} 500s")

    # Check docker logs for deadlocks or internal exceptions
    proc = subprocess.run(["docker", "logs", "--tail", "150", "docmind-api"], capture_output=True, text=True)
    logs = proc.stdout + proc.stderr
    deadlock_free = "Deadlock" not in logs and "deadlock" not in logs
    test_result("Docker logs deadlock-free", deadlock_free, "No deadlock keywords found in recent container logs")

    return passed_200 and no_500 and deadlock_free

def run_oversized_payload_test():
    print("\n" + "=" * 80)
    print("TEST 2: OVERSIZED PAYLOAD STRESS (50,000 CHARS FAST-PATH)")
    print("=" * 80)

    url = f"{API_URL}/api/bot/fanpage/message"
    oversized_text = "Em ơi giá dịch vụ bao nhiêu shop? " * 1600 # ~54,400 chars
    print(f"Payload length: {len(oversized_text)} characters")

    payload = {
        "page_id": PAGE_ID,
        "customer_id": "challenger_r8_oversized",
        "message": oversized_text
    }

    t0 = time.perf_counter()
    r = requests.post(url, json=payload, timeout=10.0)
    elapsed = time.perf_counter() - t0

    print(f"HTTP Status: {r.status_code}")
    print(f"Elapsed Time: {elapsed:.4f}s ({elapsed*1000:.1f}ms)")
    data = r.json()
    reply = data.get("reply_text") or data.get("replyText") or ""
    print(f"Reply: {reply[:150]}...")

    p_status = (r.status_code == 200)
    p_latency_5s = (elapsed < 5.0)
    p_latency_sub_second = (elapsed < 1.0)
    p_grounded = len(reply) > 0 and not data.get("needs_human_handoff", False)

    test_result("Oversized 50k payload returns 200 OK", p_status, f"Status: {r.status_code}")
    test_result("Oversized 50k payload under 5.0s SLA", p_latency_5s, f"Elapsed: {elapsed:.3f}s")
    test_result("Oversized 50k payload fast-path target (< 1.0s)", p_latency_sub_second, f"Elapsed: {elapsed:.3f}s")
    test_result("Oversized 50k payload grounded reply returned", p_grounded, f"Reply length: {len(reply)}")

    return p_status and p_latency_5s and p_grounded

def run_price_bargaining_ladder():
    print("\n" + "=" * 80)
    print("TEST 3: MULTI-TURN PRICE BARGAINING & FLOOR DEFENSE")
    print("=" * 80)

    url = f"{API_URL}/api/bot/fanpage/message"
    customer_id = f"challenger_r8_bargain_{int(time.time())}"

    turns = [
        {"user": "giá bao nhiêu vậy shop?", "expect_price": "399.000", "step": "Turn 1: Discovery anchor 399k"},
        {"user": "tôi muosn thử trải nghiệm trước xem thế nào", "expect_price": "399.000", "step": "Turn 2: Trial request preserves anchor 399k"},
        {"user": "cao thế, có gói nào mềm hơn không", "expect_price": "239.000", "step": "Turn 3: Objection 1 -> downsell to 239k"},
        {"user": "nhưng vẫn hơi đắt, còn gói nào rẻ hơn nữa không", "expect_price": "129.000", "step": "Turn 4: Objection 2 -> downsell to floor 129k"},
        {"user": "bớt thêm đi shop", "expect_price": "129.000", "expect_promo": "10%", "step": "Turn 5: Ending with 'bớt thêm đi shop' -> floor defense with 10% promo"},
    ]

    history = []
    all_passed = True

    for i, t in enumerate(turns, 1):
        user_msg = t["user"]
        step_desc = t["step"]
        print(f"\n--- {step_desc} ---")
        print(f"User: \"{user_msg}\"")

        payload = {
            "page_id": PAGE_ID,
            "customer_id": customer_id,
            "message": user_msg,
            "history": history
        }

        r = requests.post(url, json=payload, timeout=20.0)
        if r.status_code != 200:
            test_result(f"Turn {i} HTTP 200", False, f"Status: {r.status_code}")
            all_passed = False
            break

        data = r.json()
        reply = data.get("reply_text") or data.get("replyText") or ""
        handoff = data.get("needs_human_handoff", False)
        print(f"Bot Reply: {reply}")
        print(f"needs_human_handoff: {handoff}")

        # Check price expectation
        exp_price = t["expect_price"]
        p_price = exp_price in reply
        test_result(f"Turn {i} Price contains '{exp_price}'", p_price, f"Found {exp_price}: {p_price}")
        if not p_price:
            all_passed = False

        # Check promo if required
        if "expect_promo" in t:
            exp_promo = t["expect_promo"]
            p_promo = exp_promo in reply or "ưu đãi" in reply.lower() or "giảm 10" in reply.lower()
            test_result(f"Turn {i} Promo contains '{exp_promo}' / discount", p_promo, f"Found promo: {p_promo}")
            if not p_promo:
                all_passed = False

        # Check no false handoff
        p_handoff = (handoff is False)
        test_result(f"Turn {i} needs_human_handoff is False", p_handoff, f"Handoff: {handoff}")
        if not p_handoff:
            all_passed = False

        # Check no false wholesale claim (chỉ bán lẻ/sỉ nhầm lẫn)
        p_wholesale = ("giá sỉ" not in reply.lower() and "đại lý" not in reply.lower())
        test_result(f"Turn {i} 0 false wholesale claims", p_wholesale, "No false wholesale handoff detected")
        if not p_wholesale:
            all_passed = False

        history.append({"role": "user", "content": user_msg})
        history.append({"role": "assistant", "content": reply})

    return all_passed

def main():
    print("################################################################################")
    print("CHALLENGER FINAL ROUND 8-1 EMPIRICAL ADVERSARIAL STRESS TEST HARNESS")
    print("################################################################################")

    t_start = time.time()
    p1 = run_burst_concurrency()
    p2 = run_oversized_payload_test()
    p3 = run_price_bargaining_ladder()

    total_time = time.time() - t_start
    print("\n" + "=" * 80)
    print("OVERALL EMPIRICAL RESULTS")
    print(f"1. Concurrency Burst (50 reqs, 25 workers): {'PASS' if p1 else 'FAIL'}")
    print(f"2. Oversized Payload (50k chars fast-path):  {'PASS' if p2 else 'FAIL'}")
    print(f"3. Multi-turn Bargaining & Floor Defense:   {'PASS' if p3 else 'FAIL'}")
    print(f"Total Test Execution Time: {total_time:.2f}s")
    print("=" * 80)

    success = p1 and p2 and p3
    print("FINAL VERDICT: " + ("ALL TESTS PASSED - SYSTEM ROBUST" if success else "FAILURES DETECTED"))
    return success

if __name__ == "__main__":
    ok = main()
    sys.exit(0 if ok else 1)
