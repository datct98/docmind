#!/usr/bin/env python3
"""
Live Audit Runner for DocMind v3.6.7.1-MASTER (Gates 3, 4, 5)
Executes:
  - Scenario A (5 turns, Nàng Sen Spa: b23cd382-4f7a-4829-8c54-1d410da14bf8)
  - Scenario B (7 turns, Nàng Sen Spa: b23cd382-4f7a-4829-8c54-1d410da14bf8)
  - Gate 5 Multi-Tenant Isolation (3 turns, Nha Khoa Tỏa Sáng: d3000000-0000-0000-0000-000000000001)
Extracts [LIVE_AUDIT_JSON] entries from docmind-api logs and writes:
  - docs/benchmarks/live_audit.jsonl
  - docs/benchmarks/run_manifest.json (with SHA-256)
"""

import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.request
import uuid
from datetime import datetime, timezone

API_URL = "http://localhost:8090/api/bot/fanpage/message"
SPA_PAGE_ID = "b23cd382-4f7a-4829-8c54-1d410da14bf8"
DENTAL_PAGE_ID = "d3000000-0000-0000-0000-000000000001"

BENCH_DIR = os.path.dirname(os.path.abspath(__file__))
LIVE_AUDIT_PATH = os.path.join(BENCH_DIR, "live_audit.jsonl")
MANIFEST_PATH = os.path.join(BENCH_DIR, "run_manifest.json")


def get_git_commit() -> str:
    try:
        out = subprocess.check_output(
            ["git", "rev-parse", "HEAD"],
            cwd=os.path.dirname(BENCH_DIR),
            stderr=subprocess.DEVNULL,
        )
        return out.decode("utf-8").strip()
    except Exception:
        return "unknown"


def call_turn(page_id: str, sender_id: str, conversation_id: str, message: str, history: list) -> dict:
    payload = {
        "page_id": page_id,
        "sender_id": sender_id,
        "conversation_id": conversation_id,
        "customer_name": "Khách Test",
        "message": message,
        "history": history,
    }
    req = urllib.request.Request(
        API_URL,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=180) as resp:
        return json.loads(resp.read().decode("utf-8"))


def fetch_audit_logs_for_conversations(conv_ids: set[str], run_id: str, git_commit: str) -> list[dict]:
    env = os.environ.copy()
    env["PATH"] = f"/Applications/Docker.app/Contents/Resources/bin:{env.get('PATH', '')}"
    raw_logs = subprocess.check_output(
        ["docker", "logs", "docmind-api", "--since", "20m"],
        env=env,
        stderr=subprocess.STDOUT,
    ).decode("utf-8", errors="replace")

    records = []
    for line in raw_logs.splitlines():
        if "[LIVE_AUDIT_JSON]" not in line:
            continue
        try:
            outer = json.loads(line)
            msg = outer.get("formattedMessage", "")
        except Exception:
            msg = line
        idx = msg.find("[LIVE_AUDIT_JSON]")
        if idx < 0:
            continue
        json_str = msg[idx + len("[LIVE_AUDIT_JSON]"):].strip()
        try:
            rec = json.loads(json_str)
            if rec.get("conversation_id") in conv_ids:
                ordered = {
                    "run_id": run_id,
                    "git_commit": git_commit,
                }
                ordered.update(rec)
                records.append(ordered)
        except Exception as e:
            print(f"[WARN] Failed to parse LIVE_AUDIT_JSON line: {e}", file=sys.stderr)
    return records


def run_conversation(label: str, page_id: str, conv_id: str, messages: list[str]) -> list[dict]:
    print("=" * 88)
    print(f"RUNNING {label} | page_id={page_id} | conv_id={conv_id}")
    print("=" * 88)
    history = []
    turn_results = []
    for i, user_msg in enumerate(messages, start=1):
        t0 = time.time()
        resp = call_turn(page_id, conv_id, conv_id, user_msg, history)
        elapsed_ms = int((time.time() - t0) * 1000)
        bubbles = resp.get("reply_bubbles", [])
        reply_text = resp.get("reply_text", "")
        print(f"\n--- [{label} - Turn {i}] ({elapsed_ms} ms) ---")
        print(f"Customer: {user_msg}")
        for b_idx, b in enumerate(bubbles, start=1):
            print(f"  Bubble {b_idx} (delay={b.get('typing_delay_ms')}ms): {b.get('text')}")
        history.append({"role": "user", "content": user_msg})
        history.append({"role": "assistant", "content": reply_text})
        turn_results.append({
            "turn": i,
            "customer_message": user_msg,
            "response": resp,
            "client_elapsed_ms": elapsed_ms,
        })
    return turn_results


def main():
    run_id = f"v3671-master-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}-{uuid.uuid4().hex[:6]}"
    git_commit = get_git_commit()

    conv_a = f"scen-a-{uuid.uuid4().hex[:8]}"
    conv_b = f"scen-b-{uuid.uuid4().hex[:8]}"
    conv_g5 = f"gate5-dental-{uuid.uuid4().hex[:8]}"

    scenario_a_msgs = [
        "Giá",
        "Tôi bị đau đầu khó ngủ mỏi vai",
        "Bị đuên à, đau đầu chứ đầu gối đâu",
        "Dở vừa, ns linh ta linh tinh",
        "Rồi định chăm sóc cái j cho t, t phát bực r",
    ]

    scenario_b_msgs = [
        "Giá sao",
        "Tôi đau nhức chân và mất ngủ",
        "Lâu rồi, giờ bị lại",
        "Dở à, đau chân mất ngủ mà đi massage vai làm j",
        "Tôi thấy tụt cảm xúc quá chán chả buồn nói",
        "Nghe ổn hơn đó, giá sao",
        "Đắt có giảm giá hya Km k",
    ]

    gate_5_msgs = [
        "Tôi muốn tẩy trắng răng",
        "Tẩy trắng răng chứ nhổ răng đâu",
        "Giá tẩy trắng răng bao nhiêu?",
    ]

    res_a = run_conversation("SCENARIO_A (5 TURNS - SPA)", SPA_PAGE_ID, conv_a, scenario_a_msgs)
    res_b = run_conversation("SCENARIO_B (7 TURNS - SPA)", SPA_PAGE_ID, conv_b, scenario_b_msgs)
    res_g5 = run_conversation("GATE_5 (3 TURNS - DENTAL)", DENTAL_PAGE_ID, conv_g5, gate_5_msgs)

    time.sleep(1.0)
    audit_records = fetch_audit_logs_for_conversations({conv_a, conv_b, conv_g5}, run_id, git_commit)

    # Initialize clean file if requested, then append each record in append-only mode
    if os.environ.get("CLEAN_AUDIT_LOG", "1") == "1" and os.path.exists(LIVE_AUDIT_PATH):
        os.remove(LIVE_AUDIT_PATH)
    with open(LIVE_AUDIT_PATH, "a", encoding="utf-8") as f:
        for rec in audit_records:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")

    with open(LIVE_AUDIT_PATH, "rb") as f:
        audit_bytes = f.read()
    sha256_hex = hashlib.sha256(audit_bytes).hexdigest()

    # Check cross-domain term leak on Gate 5 Dental turns
    forbidden_spa_terms = [
        "nàng sen", "an nhiên", "vai gáy", "mỏi gối", "ngâm thảo dược", "ráy tai",
        "kỹ thuật viên", "buffet", "thực dưỡng", "280k", "350k", "399k",
    ]
    dental_leaks = []
    for r in res_g5:
        txt = (r["response"].get("reply_text") or "").lower()
        for term in forbidden_spa_terms:
            if term in txt:
                dental_leaks.append({"turn": r["turn"], "term": term, "text": txt})

    manifest = {
        "run_id": run_id,
        "git_commit": git_commit,
        "timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "live_audit_file": "docs/benchmarks/live_audit.jsonl",
        "live_audit_sha256": sha256_hex,
        "records_appended_in_run": len(audit_records),
        "conversations": {
            "scenario_a": {"conversation_id": conv_a, "tenant_id": SPA_PAGE_ID, "turns": len(res_a)},
            "scenario_b": {"conversation_id": conv_b, "tenant_id": SPA_PAGE_ID, "turns": len(res_b)},
            "gate_5_dental": {"conversation_id": conv_g5, "tenant_id": DENTAL_PAGE_ID, "turns": len(res_g5)},
        },
        "gate_5_cross_domain_term_leak_rate": "0%" if len(dental_leaks) == 0 else f"{len(dental_leaks)} leaks",
        "gate_5_leaks": dental_leaks,
    }
    with open(MANIFEST_PATH, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)

    print("\n" + "=" * 88)
    print(f"COMPLETED LIVE AUDIT: {len(audit_records)} records appended to {LIVE_AUDIT_PATH}")
    print(f"SHA-256(live_audit.jsonl) = {sha256_hex}")
    print(f"Gate 5 Cross-Domain Term Leak Rate = {manifest['gate_5_cross_domain_term_leak_rate']}")
    print("=" * 88)


if __name__ == "__main__":
    main()
