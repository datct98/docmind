#!/usr/bin/env python3
"""Aggressive Empirical Concurrency Burst and Stress Harness for Challenger R3-2.

Targets:
1. 300 concurrent GET requests to http://localhost:8090/api/health (direct backend)
2. 300 concurrent GET requests to http://localhost:3000/api/health (frontend reverse proxy)
3. 50 concurrent POST requests to http://localhost:8090/api/bot/fanpage/message with varying scenarios
"""

import concurrent.futures
import time
import requests
import json
from typing import Dict, List, Tuple

def burst_endpoint(url: str, num_requests: int = 300, max_workers: int = 60, timeout: float = 15.0) -> Dict[str, any]:
    results: List[Tuple[int, float, str]] = []

    def _worker(i: int):
        t0 = time.perf_counter()
        try:
            r = requests.get(url, timeout=timeout)
            elapsed = time.perf_counter() - t0
            return r.status_code, elapsed, ""
        except Exception as e:
            elapsed = time.perf_counter() - t0
            return 0, elapsed, str(e)

    t_start = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as pool:
        futures = [pool.submit(_worker, i) for i in range(num_requests)]
        for f in concurrent.futures.as_completed(futures):
            results.append(f.result())
    total_time = time.perf_counter() - t_start

    status_counts: Dict[int, int] = {}
    latencies: List[float] = []
    errors: List[Tuple[int, str]] = []

    for code, elapsed, err in results:
        status_counts[code] = status_counts.get(code, 0) + 1
        latencies.append(elapsed)
        if err or code != 200:
            errors.append((code, err))

    latencies.sort()
    p50 = latencies[len(latencies) // 2] if latencies else 0
    p90 = latencies[int(len(latencies) * 0.90)] if latencies else 0
    p95 = latencies[int(len(latencies) * 0.95)] if latencies else 0
    p99 = latencies[int(len(latencies) * 0.99)] if latencies else 0
    max_lat = max(latencies) if latencies else 0

    return {
        "url": url,
        "total_requests": num_requests,
        "total_time_s": total_time,
        "rps": num_requests / total_time if total_time > 0 else 0,
        "status_counts": status_counts,
        "p50_ms": p50 * 1000,
        "p90_ms": p90 * 1000,
        "p95_ms": p95 * 1000,
        "p99_ms": p99 * 1000,
        "max_ms": max_lat * 1000,
        "errors": errors,
    }

def burst_bot_messages(url: str, num_requests: int = 50, max_workers: int = 15, timeout: float = 30.0) -> Dict[str, any]:
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

    def _post_worker(idx: int):
        msg = queries[idx % len(queries)]
        payload = {
            "page_id": "2850a8ef-ca4a-40eb-87fa-4f808640efee",
            "customer_id": f"burst_r3_2_{idx}",
            "message": msg,
            "history": []
        }
        t0 = time.perf_counter()
        try:
            r = requests.post(url, json=payload, timeout=timeout)
            elapsed = time.perf_counter() - t0
            data = r.json() if r.status_code == 200 else {}
            return r.status_code, elapsed, "", data
        except Exception as e:
            elapsed = time.perf_counter() - t0
            return 0, elapsed, str(e), {}

    t_start = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as pool:
        futures = [pool.submit(_post_worker, i) for i in range(num_requests)]
        for f in concurrent.futures.as_completed(futures):
            results.append(f.result())
    total_time = time.perf_counter() - t_start

    status_counts: Dict[int, int] = {}
    latencies: List[float] = []
    errors: List[str] = []
    empty_replies = 0

    for code, elapsed, err, data in results:
        status_counts[code] = status_counts.get(code, 0) + 1
        latencies.append(elapsed)
        if err or code != 200:
            errors.append(f"code={code}: {err}")
        if code == 200 and not data.get("reply_text"):
            empty_replies += 1

    latencies.sort()
    p50 = latencies[len(latencies) // 2] if latencies else 0
    p95 = latencies[int(len(latencies) * 0.95)] if latencies else 0
    max_lat = max(latencies) if latencies else 0

    return {
        "url": url,
        "total_requests": num_requests,
        "total_time_s": total_time,
        "rps": num_requests / total_time if total_time > 0 else 0,
        "status_counts": status_counts,
        "p50_ms": p50 * 1000,
        "p95_ms": p95 * 1000,
        "max_ms": max_lat * 1000,
        "empty_replies": empty_replies,
        "errors": errors,
    }

def main():
    print("=== STARTING AGGRESSIVE EMPIRICAL BURST BENCHMARKS ===")
    
    # 1. 300 requests to 8090
    print("\n--- Benchmark 1: 300 Requests to http://localhost:8090/api/health (60 concurrency) ---")
    res_8090 = burst_endpoint("http://localhost:8090/api/health", num_requests=300, max_workers=60)
    print(f"Requests: {res_8090['total_requests']}, Duration: {res_8090['total_time_s']:.2f}s, RPS: {res_8090['rps']:.1f}")
    print(f"Latency: P50={res_8090['p50_ms']:.1f}ms, P90={res_8090['p90_ms']:.1f}ms, P95={res_8090['p95_ms']:.1f}ms, P99={res_8090['p99_ms']:.1f}ms, Max={res_8090['max_ms']:.1f}ms")
    print(f"Statuses: {res_8090['status_counts']}, Errors: {len(res_8090['errors'])}")
    assert res_8090['status_counts'].get(200, 0) == 300, f"Expected 300 HTTP 200, got: {res_8090['status_counts']}"
    assert 502 not in res_8090['status_counts'], "502 Bad Gateway observed on 8090!"
    assert 504 not in res_8090['status_counts'], "504 Gateway Timeout observed on 8090!"
    print(">> Benchmark 1: PASS (100% 200 OK, 0 errors, 0 dropped)")

    # 2. 300 requests to 3000
    print("\n--- Benchmark 2: 300 Requests to http://localhost:3000/api/health (60 concurrency) ---")
    res_3000 = burst_endpoint("http://localhost:3000/api/health", num_requests=300, max_workers=60)
    print(f"Requests: {res_3000['total_requests']}, Duration: {res_3000['total_time_s']:.2f}s, RPS: {res_3000['rps']:.1f}")
    print(f"Latency: P50={res_3000['p50_ms']:.1f}ms, P90={res_3000['p90_ms']:.1f}ms, P95={res_3000['p95_ms']:.1f}ms, P99={res_3000['p99_ms']:.1f}ms, Max={res_3000['max_ms']:.1f}ms")
    print(f"Statuses: {res_3000['status_counts']}, Errors: {len(res_3000['errors'])}")
    assert res_3000['status_counts'].get(200, 0) == 300, f"Expected 300 HTTP 200, got: {res_3000['status_counts']}"
    assert 502 not in res_3000['status_counts'], "502 Bad Gateway observed on 3000!"
    assert 504 not in res_3000['status_counts'], "504 Gateway Timeout observed on 3000!"
    print(">> Benchmark 2: PASS (100% 200 OK, 0 errors, 0 dropped)")

    # 3. 50 concurrent requests to Bot POST endpoint
    print("\n--- Benchmark 3: 50 Concurrent Requests to http://localhost:8090/api/bot/fanpage/message ---")
    res_bot = burst_bot_messages("http://localhost:8090/api/bot/fanpage/message", num_requests=50, max_workers=15)
    print(f"Requests: {res_bot['total_requests']}, Duration: {res_bot['total_time_s']:.2f}s, RPS: {res_bot['rps']:.1f}")
    print(f"Latency: P50={res_bot['p50_ms']:.1f}ms, P95={res_bot['p95_ms']:.1f}ms, Max={res_bot['max_ms']:.1f}ms")
    print(f"Statuses: {res_bot['status_counts']}, Empty replies: {res_bot['empty_replies']}, Errors: {len(res_bot['errors'])}")
    assert res_bot['status_counts'].get(200, 0) == 50, f"Expected 50 HTTP 200, got: {res_bot['status_counts']}"
    assert res_bot['empty_replies'] == 0, f"Found {res_bot['empty_replies']} empty replies!"
    assert 502 not in res_bot['status_counts'], "502 Bad Gateway observed on bot endpoint!"
    assert 504 not in res_bot['status_counts'], "504 Gateway Timeout observed on bot endpoint!"
    print(">> Benchmark 3: PASS (100% 200 OK, 0 empty replies, SLA respected)")

    print("\n=== ALL AGGRESSIVE BURST BENCHMARKS COMPLETED SUCCESSFULLY ===")

if __name__ == "__main__":
    main()
