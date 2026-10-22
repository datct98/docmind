#!/usr/bin/env python3
"""
Live Probe Challenge Suite for Challenger Final R3-1
1. Query arbitrary service codes: "gói NS01 và NS02 khác gì nhau vậy shop" -> Dynamic comparison, 0 mention of 780k/670k.
2. Query prices: "gói 399k và 530k khác gì nhau" -> Dynamic price table comparison.
3. Multi-round price bargaining: monotonic down-sell from 399k -> 239k -> 129k with trial & promo requests interspersed.
4. Extreme budget bounds: "100 triệu", "100tr", "1 tỷ", and verify "tỷ lệ" does NOT trigger ceiling refusal.
5. Large payload stress: 50,000 character payload completes within 5.0s SLA without timeout.
"""

import sys
import time
import json
import requests

if sys.stdout.encoding != 'utf-8':
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass

API_URL = "http://localhost:8090"
PAGE_ID = "2850a8ef-ca4a-40eb-87fa-4f808640efee" # Page "Nàng sen"

def post_message(message, history=None, timeout=20):
    payload = {
        "page_id": PAGE_ID,
        "message": message,
        "customer_id": "challenger_live_probe"
    }
    if history:
        payload["history"] = history
    t0 = time.time()
    resp = requests.post(f"{API_URL}/api/bot/fanpage/message", json=payload, timeout=timeout)
    elapsed = time.time() - t0
    return resp, elapsed

def run_live_probes():
    results = {}
    passed_all = True
    print("=" * 80)
    print("RUNNING CHALLENGER FINAL R3-1 LIVE PROBES")
    print("=" * 80)

    # -------------------------------------------------------------
    # Probe 1: Arbitrary service codes: NS01 vs NS02
    # -------------------------------------------------------------
    print("\n--- Probe 1: Arbitrary service codes (NS01 vs NS02) ---")
    r1, el1 = post_message("gói NS01 và NS02 khác gì nhau vậy shop")
    if r1.status_code == 200:
        reply1 = r1.json().get("reply_text") or ""
        print(f"Status: 200 (in {el1:.2f}s)\nReply:\n{reply1}")
        has_ns01 = "NS01" in reply1 or "ns01" in reply1.lower()
        has_ns02 = "NS02" in reply1 or "ns02" in reply1.lower()
        no_780 = "780" not in reply1
        no_670_canned = "gói tiêu chuẩn 670k" not in reply1.lower() and "phòng riêng vip 780k" not in reply1.lower()
        p1 = has_ns01 and has_ns02 and no_780 and no_670_canned
        print(f"Assertions: has_ns01={has_ns01}, has_ns02={has_ns02}, no_780={no_780}, no_canned_780_670={no_670_canned}")
        results["probe_1_ns01_ns02"] = p1
        if not p1:
            passed_all = False
    else:
        print(f"FAILED with HTTP {r1.status_code}")
        results["probe_1_ns01_ns02"] = False
        passed_all = False

    # -------------------------------------------------------------
    # Probe 2: Query prices: 399k và 530k khác gì nhau
    # -------------------------------------------------------------
    print("\n--- Probe 2: Query prices (399k và 530k khác gì nhau) ---")
    r2, el2 = post_message("gói 399k và 530k khác gì nhau")
    if r2.status_code == 200:
        reply2 = r2.json().get("reply_text") or ""
        print(f"Status: 200 (in {el2:.2f}s)\nReply:\n{reply2}")
        mentions_399 = "399" in reply2
        mentions_530 = "530" in reply2
        p2 = mentions_399 and mentions_530
        print(f"Assertions: mentions_399={mentions_399}, mentions_530={mentions_530}")
        results["probe_2_price_comparison"] = p2
        if not p2:
            passed_all = False
    else:
        print(f"FAILED with HTTP {r2.status_code}")
        results["probe_2_price_comparison"] = False
        passed_all = False

    # -------------------------------------------------------------
    # Probe 3: Multi-round price bargaining with trial & promo interspersed
    # -------------------------------------------------------------
    print("\n--- Probe 3: Multi-round price bargaining with trial & promo ---")
    # Turns:
    # 3.1: "giá bao nhiêu shop" -> anchor 399k
    # 3.2: "tôi muốn thử trải nghiệm trước xem thế nào" -> trial preserves 399k
    # 3.3: "mua 5 buổi có được giảm 10% không em" -> promo inquiry -> confirms 10% discount without downselling to 239k prematurely
    # 3.4: "cao thế, có gói nào mềm hơn không" -> downsell to 239k
    # 3.5: "vẫn hơi đắt, còn gói nào rẻ hơn nữa không" -> downsell to 129k (floor)
    # 3.6: "có gói nào rẻ hơn 129k nữa không" -> floor 129k preserved, promo policy mentioned
    # 3.7: "bớt thêm đi shop" -> stays at floor 129k, no oscillation
    turns = [
        {"msg": "giá bao nhiêu shop", "check": lambda r: "399.000" in r, "label": "3.1 Anchor 399k"},
        {"msg": "tôi muốn thử trải nghiệm trước xem thế nào", "check": lambda r: "399.000" in r, "label": "3.2 Trial preserves 399k"},
        {"msg": "mua 5 buổi có được giảm 10% không em", "check": lambda r: "10%" in r and ("ưu đãi" in r.lower() or "giảm" in r.lower()), "label": "3.3 Promo inquiry answers 10%"},
        {"msg": "cao thế, có gói nào mềm hơn không", "check": lambda r: "239.000" in r, "label": "3.4 Downsell to 239k"},
        {"msg": "vẫn hơi đắt, còn gói nào rẻ hơn nữa không", "check": lambda r: "129.000" in r, "label": "3.5 Downsell to 129k (floor)"},
        {"msg": "có gói nào rẻ hơn 129k nữa không", "check": lambda r: "129.000" in r, "label": "3.6 Floor preserved at 129k"},
        {"msg": "bớt thêm đi shop", "check": lambda r: "129.000" in r, "label": "3.7 Stays at 129k floor"}
    ]
    history = []
    ladder_passed = True
    for t_idx, t in enumerate(turns, 1):
        r_t, el_t = post_message(t["msg"], history=history)
        if r_t.status_code == 200:
            reply_t = r_t.json().get("reply_text") or ""
            ok = t["check"](reply_t)
            print(f"[{'PASS' if ok else 'FAIL'}] {t['label']} ({el_t:.2f}s): {reply_t[:70]}...")
            if not ok:
                ladder_passed = False
                passed_all = False
                print(f"  FULL REPLY:\n{reply_t}")
            history.append({"role": "user", "content": t["msg"]})
            history.append({"role": "assistant", "content": reply_t})
        else:
            print(f"[FAIL] {t['label']} HTTP {r_t.status_code}")
            ladder_passed = False
            passed_all = False
            break
    results["probe_3_ladder"] = ladder_passed

    # -------------------------------------------------------------
    # Probe 4: Extreme budget bounds & "tỷ lệ" check
    # -------------------------------------------------------------
    print("\n--- Probe 4: Extreme budget bounds & 'tỷ lệ' verification ---")
    bounds = [
        ("100 triệu", "Ngân sách chị 100 triệu muốn dùng dịch vụ cao cấp nhất",
         lambda r: ("chưa có gói" in r.lower() or "1.990.000" in r or "100 triệu" in r or "cao cấp nhất" in r.lower())),
        ("100tr", "Mình có 100tr cần tư vấn",
         lambda r: ("chưa có gói" in r.lower() or "1.990.000" in r or "triệu" in r.lower())),
        ("1 tỷ", "Chị có khoảng 1 tỷ muốn làm dịch vụ toàn diện",
         lambda r: ("chưa có gói" in r.lower() or "1 tỷ" in r.lower() or "1.990.000" in r or "triệu" in r.lower())),
        ("tỷ lệ (must NOT trigger ceiling)", "Tỷ lệ khách hàng hài lòng bên mình thế nào vậy em?",
         lambda r: "chưa có gói dịch vụ mức 100 triệu hay 1 tỷ" not in r.lower())
    ]
    bounds_passed = True
    for b_label, b_query, b_validator in bounds:
        r_b, el_b = post_message(b_query)
        if r_b.status_code == 200:
            reply_b = r_b.json().get("reply_text") or ""
            ok_b = b_validator(reply_b)
            print(f"[{'PASS' if ok_b else 'FAIL'}] Bound {b_label} ({el_b:.2f}s): {reply_b[:70]}...")
            if not ok_b:
                bounds_passed = False
                passed_all = False
                print(f"  FULL REPLY:\n{reply_b}")
        else:
            print(f"[FAIL] Bound {b_label} HTTP {r_b.status_code}")
            bounds_passed = False
            passed_all = False
    results["probe_4_bounds"] = bounds_passed

    # -------------------------------------------------------------
    # Probe 5: Large payload stress (50,000 characters within 5.0s SLA)
    # -------------------------------------------------------------
    print("\n--- Probe 5: Large payload stress (50,000 chars, < 5.0s SLA) ---")
    large_payload = "Cho tôi hỏi giá dịch vụ massage và gội đầu dưỡng sinh của shop nhé. " * 750 # ~51,000 chars
    print(f"Payload length: {len(large_payload)} characters")
    r5, el5 = post_message(large_payload, timeout=10)
    if r5.status_code == 200:
        reply5 = r5.json().get("reply_text") or ""
        p5 = el5 < 5.0 and len(reply5) > 20
        print(f"[{'PASS' if p5 else 'FAIL'}] Large payload: elapsed={el5:.2f}s (< 5.0s), reply_len={len(reply5)}")
        print(f"Reply sample: {reply5[:80]}...")
        results["probe_5_large_payload"] = p5
        if not p5:
            passed_all = False
    else:
        print(f"[FAIL] Large payload HTTP {r5.status_code}")
        results["probe_5_large_payload"] = False
        passed_all = False

    print("\n" + "=" * 80)
    print("LIVE PROBE CHALLENGE RESULTS SUMMARY:")
    for k, v in results.items():
        print(f"  - {k}: {'PASS' if v else 'FAIL'}")
    print(f"OVERALL PROBE VERDICT: {'ALL PASSED' if passed_all else 'FAILURES DETECTED'}")
    print("=" * 80)
    return passed_all

if __name__ == "__main__":
    success = run_live_probes()
    sys.exit(0 if success else 1)
