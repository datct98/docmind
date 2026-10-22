#!/usr/bin/env python3
"""Adversarial High Concurrency Burst Test Suite.

Author: Challenger Final 2
Targets:
- http://localhost:8090/api/health
- http://localhost:3000/api/health
- http://localhost:8090/api/bot/fanpage/message
"""

import concurrent.futures
import time
import requests
import pytest
from typing import Dict, List, Tuple


def _burst_get(url: str, num_requests: int = 100, max_workers: int = 50) -> Dict[str, any]:
    """Execute concurrent GET requests in a burst and collect statistics."""
    results: List[Tuple[int, float, str]] = []
    
    def _send(req_id: int):
        t0 = time.perf_counter()
        try:
            resp = requests.get(url, timeout=10)
            elapsed = time.perf_counter() - t0
            return resp.status_code, elapsed, ""
        except Exception as e:
            elapsed = time.perf_counter() - t0
            return 0, elapsed, str(e)

    t_start = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = [executor.submit(_send, i) for i in range(num_requests)]
        for f in concurrent.futures.as_completed(futures):
            results.append(f.result())
    total_duration = time.perf_counter() - t_start

    status_counts = {}
    latencies = []
    errors = []
    for code, elapsed, err in results:
        status_counts[code] = status_counts.get(code, 0) + 1
        latencies.append(elapsed)
        if err or code != 200:
            errors.append((code, err))

    latencies.sort()
    p50 = latencies[len(latencies) // 2]
    p95 = latencies[int(len(latencies) * 0.95)]
    p99 = latencies[int(len(latencies) * 0.99)]
    max_lat = max(latencies) if latencies else 0.0

    return {
        "url": url,
        "total_requests": num_requests,
        "total_duration": total_duration,
        "rps": num_requests / total_duration if total_duration > 0 else 0,
        "status_counts": status_counts,
        "p50_ms": p50 * 1000,
        "p95_ms": p95 * 1000,
        "p99_ms": p99 * 1000,
        "max_ms": max_lat * 1000,
        "errors": errors,
    }


def test_burst_health_8090():
    """Burst test 100 requests to docmind-api (8090) with 50 concurrent threads."""
    stats = _burst_get("http://localhost:8090/api/health", num_requests=100, max_workers=50)
    print(f"\n[8090 Health Burst] {stats['total_requests']} reqs in {stats['total_duration']:.2f}s "
          f"({stats['rps']:.1f} rps) | P50={stats['p50_ms']:.1f}ms, P95={stats['p95_ms']:.1f}ms, "
          f"P99={stats['p99_ms']:.1f}ms, Max={stats['max_ms']:.1f}ms | Statuses: {stats['status_counts']}")
    assert stats["status_counts"].get(200, 0) == 100, f"Expected 100x 200 OK, got {stats['status_counts']}"
    assert 502 not in stats["status_counts"], "Found 502 Bad Gateway"
    assert 504 not in stats["status_counts"], "Found 504 Gateway Timeout"
    assert len(stats["errors"]) == 0, f"Encountered connection errors: {stats['errors']}"


def test_burst_health_3000():
    """Burst test 100 requests to frontend proxy (3000) with 50 concurrent threads."""
    stats = _burst_get("http://localhost:3000/api/health", num_requests=100, max_workers=50)
    print(f"\n[3000 Health Burst] {stats['total_requests']} reqs in {stats['total_duration']:.2f}s "
          f"({stats['rps']:.1f} rps) | P50={stats['p50_ms']:.1f}ms, P95={stats['p95_ms']:.1f}ms, "
          f"P99={stats['p99_ms']:.1f}ms, Max={stats['max_ms']:.1f}ms | Statuses: {stats['status_counts']}")
    assert stats["status_counts"].get(200, 0) == 100, f"Expected 100x 200 OK, got {stats['status_counts']}"
    assert 502 not in stats["status_counts"], "Found 502 Bad Gateway"
    assert 504 not in stats["status_counts"], "Found 504 Gateway Timeout"
    assert len(stats["errors"]) == 0, f"Encountered connection errors: {stats['errors']}"


def test_interleaved_dual_burst_concurrency():
    """Simultaneously burst both 8090 and 3000 under concurrent load."""
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        f_8090 = executor.submit(_burst_get, "http://localhost:8090/api/health", 100, 50)
        f_3000 = executor.submit(_burst_get, "http://localhost:3000/api/health", 100, 50)
        s_8090 = f_8090.result()
        s_3000 = f_3000.result()

    assert s_8090["status_counts"].get(200, 0) == 100
    assert s_3000["status_counts"].get(200, 0) == 100
    assert 502 not in s_8090["status_counts"] and 502 not in s_3000["status_counts"]
    assert 504 not in s_8090["status_counts"] and 504 not in s_3000["status_counts"]


def test_concurrent_fanpage_bot_queries():
    """Adversarial concurrency test: 20 simultaneous POST requests to Fanpage Bot."""
    url = "http://localhost:8090/api/bot/fanpage/message"
    queries = [
        "giá",
        "tôi muosn thử trải nghiệm trước",
        "cao thế",
        "nhưng hơi đắt",
        "có gói nào rẻ hơn không",
        "cơ sở Quận 1 Sài Gòn có gói nào?",
        "gói 670k và 780k khác gì nhau?",
        "có giảm giá combo không",
        "mình là nam",
        "gói massage 399k gồm những gì"
    ] * 2  # 20 queries

    results = []
    def _send_bot(idx: int, msg: str):
        t0 = time.perf_counter()
        try:
            resp = requests.post(url, json={
                "page_id": "2850a8ef-ca4a-40eb-87fa-4f808640efee",
                "customer_id": f"burst_user_{idx}",
                "message": msg,
                "history": []
            }, timeout=30)
            elapsed = time.perf_counter() - t0
            return resp.status_code, elapsed, resp.json() if resp.status_code == 200 else {}
        except Exception as e:
            elapsed = time.perf_counter() - t0
            return 0, elapsed, {"error": str(e)}

    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
        futures = [executor.submit(_send_bot, i, queries[i]) for i in range(len(queries))]
        for f in concurrent.futures.as_completed(futures):
            results.append(f.result())

    status_codes = [r[0] for r in results]
    assert all(code == 200 for code in status_codes), f"Not all queries succeeded: {status_codes}"
    for code, elapsed, data in results:
        assert data.get("reply_text"), "Empty reply_text under concurrency"
        assert elapsed < 30.0, f"Query took too long: {elapsed:.2f}s"


if __name__ == "__main__":
    test_burst_health_8090()
    test_burst_health_3000()
    test_interleaved_dual_burst_concurrency()
    test_concurrent_fanpage_bot_queries()
    print("\nALL CONCURRENCY BURST TESTS PASSED EMPIRICALLY!")
