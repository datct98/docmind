#!/usr/bin/env python3
"""Challenger Final R6-2 Concurrency Burst & DB Invariant Verification Suite.

Validates:
1. Concurrency Burst:
   - 300 concurrent requests against http://localhost:8090/api/health (0 502/504 errors, 100% 200 OK, 0 dropped)
   - 300 concurrent requests against http://localhost:3000/api/health (0 502/504 errors, 100% 200 OK, 0 dropped)
2. Database Invariants:
   - Exactly 65 chunks preserved for Page "Nàng sen" (id: 2850a8ef-ca4a-40eb-87fa-4f808640efee)
   - 65 with text, 65 with heading_path, 65 with tsv, 65 with chunk_metadata, 65 with embedding.
"""

import concurrent.futures
import time
import requests
import json
import subprocess
from typing import Dict, List, Tuple

def run_concurrency_burst(url: str, num_requests: int = 300, max_workers: int = 60, timeout: float = 15.0) -> Dict[str, any]:
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

def verify_db_invariants() -> Dict[str, any]:
    cmd = [
        "docker", "exec", "docmind-postgres", "psql", "-U", "docmind", "-d", "docmind",
        "-t", "-A", "-F", ",", "-c",
        "SELECT count(*) as total, count(text) as with_text, count(heading_path) as with_heading_path, "
        "count(tsv) as with_tsv, count(chunk_metadata) as with_metadata, count(embedding) as with_embedding "
        "FROM chunks c JOIN documents d ON c.document_id = d.id "
        "WHERE d.page_id = '2850a8ef-ca4a-40eb-87fa-4f808640efee';"
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True, check=True)
    out = proc.stdout.strip()
    parts = out.split(",")
    total, with_text, with_heading, with_tsv, with_meta, with_embed = [int(p) for p in parts]
    return {
        "total": total,
        "with_text": with_text,
        "with_heading": with_heading,
        "with_tsv": with_tsv,
        "with_meta": with_meta,
        "with_embed": with_embed
    }

def test_concurrency_burst_8090():
    url = "http://localhost:8090/api/health"
    print(f"\n[STRESS] Launching 300 concurrent requests against {url}...")
    res = run_concurrency_burst(url, num_requests=300, max_workers=60)
    print(f"Results for {url}:")
    print(f"  Total Time: {res['total_time_s']:.2f}s | RPS: {res['rps']:.1f}")
    print(f"  Status Counts: {res['status_counts']}")
    print(f"  Latency: p50={res['p50_ms']:.1f}ms, p95={res['p95_ms']:.1f}ms, p99={res['p99_ms']:.1f}ms, max={res['max_ms']:.1f}ms")
    print(f"  Errors: {len(res['errors'])}")
    
    assert res['status_counts'].get(502, 0) == 0, f"502 errors detected: {res['status_counts'].get(502)}"
    assert res['status_counts'].get(504, 0) == 0, f"504 errors detected: {res['status_counts'].get(504)}"
    assert res['status_counts'].get(200, 0) == 300, f"Expected 300 HTTP 200, got: {res['status_counts']}"
    assert len(res['errors']) == 0, f"Errors occurred during burst: {res['errors'][:5]}"

def test_concurrency_burst_3000():
    url = "http://localhost:3000/api/health"
    print(f"\n[STRESS] Launching 300 concurrent requests against {url}...")
    res = run_concurrency_burst(url, num_requests=300, max_workers=60)
    print(f"Results for {url}:")
    print(f"  Total Time: {res['total_time_s']:.2f}s | RPS: {res['rps']:.1f}")
    print(f"  Status Counts: {res['status_counts']}")
    print(f"  Latency: p50={res['p50_ms']:.1f}ms, p95={res['p95_ms']:.1f}ms, p99={res['p99_ms']:.1f}ms, max={res['max_ms']:.1f}ms")
    print(f"  Errors: {len(res['errors'])}")
    
    assert res['status_counts'].get(502, 0) == 0, f"502 errors detected: {res['status_counts'].get(502)}"
    assert res['status_counts'].get(504, 0) == 0, f"504 errors detected: {res['status_counts'].get(504)}"
    assert res['status_counts'].get(200, 0) == 300, f"Expected 300 HTTP 200, got: {res['status_counts']}"
    assert len(res['errors']) == 0, f"Errors occurred during burst: {res['errors'][:5]}"

def test_database_invariants():
    print("\n[DB] Checking PostgreSQL database invariants on port 5433...")
    db_res = verify_db_invariants()
    print(f"  DB Invariants for Page 'Nàng sen': {db_res}")
    assert db_res["total"] == 65, f"Expected 65 chunks, found {db_res['total']}"
    assert db_res["with_text"] == 65, f"Expected 65 with text, found {db_res['with_text']}"
    assert db_res["with_heading"] == 65, f"Expected 65 with heading_path, found {db_res['with_heading']}"
    assert db_res["with_tsv"] == 65, f"Expected 65 with tsv, found {db_res['with_tsv']}"
    assert db_res["with_meta"] == 65, f"Expected 65 with chunk_metadata, found {db_res['with_meta']}"
    assert db_res["with_embed"] == 65, f"Expected 65 with embedding, found {db_res['with_embed']}"
    print("  ALL DATABASE INVARIANTS SATISFIED (65/65 intact, zero data loss)!")

if __name__ == "__main__":
    print("=== STARTING CHALLENGER FINAL R6-2 BURST & INVARIANTS ===")
    test_concurrency_burst_8090()
    test_concurrency_burst_3000()
    test_database_invariants()
    print("\n=== ALL TESTS PASSED SUCCESSFULLY! ===")
