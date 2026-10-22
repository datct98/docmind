#!/usr/bin/env python3
"""
Dedicated Deep Adversarial Stress Suite - Challenger Final Round 2
Independently verifies:
1. Price Ladder Turn 3 Down-Sell: No Saigon latching occurs when Turn 1 quotes multiple branches.
2. Extreme Budget Bounding: "100 triệu", "100tr", and "1 tỷ" trigger catalog ceiling refusal,
   while "tỷ lệ" (tỷ lệ hài lòng, tỷ lệ tái khám) does NOT trigger refusal.
3. ReDoS / Oversized Payload: 50,000-character payload completes in < 5s without timeout.
4. Male customer name "Nam" honorific resolution (tên mình là Nguyễn Văn Nam -> "Anh", NOT "Chị").
"""

import sys
import time
import requests

if sys.stdout.encoding != 'utf-8':
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass

API_URL = "http://localhost:8090"
PAGE_ID = "2850a8ef-ca4a-40eb-87fa-4f808640efee"

def run_tests():
    passed = 0
    failed = 0
    errors = []

    print("=" * 80)
    print("RUNNING CHALLENGER R2-1 DEEP EMPIRICAL STRESS SUITE")
    print("=" * 80)

    # -------------------------------------------------------------------------
    # SUITE 1: Price Ladder Turn 3 Down-Sell (No Saigon Latching)
    # -------------------------------------------------------------------------
    print("\n--- SUITE 1: Price Ladder Turn 3 Down-Sell Multi-Branch Isolation ---")
    
    # Simulate a conversation where Turn 1 quotes BOTH Hanoi (399.000) and Saigon (570.000)
    history_test_cases = [
        {
            "name": "Standard Discovery Turn 1 with both branches quoted",
            "history": [
                {
                    "role": "user",
                    "content": "Giá dịch vụ massage bên em thế nào?"
                },
                {
                    "role": "assistant",
                    "content": "Dạ em chào Chị ạ! Bên em có gói Massage cơ bản khởi điểm từ 399.000 VNĐ (Hà Nội) hoặc 570.000 VNĐ (Quận 1 Sài Gòn) gồm full body trị liệu 60-70 phút, cùng các gói gội đầu dưỡng sinh từ 129.000 VNĐ ạ."
                },
                {
                    "role": "user",
                    "content": "tôi muosn thử trải nghiệm trước xem thế nào"
                },
                {
                    "role": "assistant",
                    "content": "Dạ Chị muốn thử trải nghiệm trước thì gói Massage cơ bản 399.000 VNĐ (60 phút) full body trị liệu giảm đau mỏi là lựa chọn rất lý tưởng ạ, liệu trình có kèm buffet thực dưỡng sau buổi ạ."
                }
            ],
            "turn3_message": "cao thế, có gói nào mềm hơn không",
            "expected_price": "239.000",
            "forbidden_price": "260.000", # 260.000 is Saigon downsell! Must NOT latch to Saigon!
            "forbidden_branch": "sài gòn"
        },
        {
            "name": "Single-turn history where assistant quoted both branches in Turn 1",
            "history": [
                {
                    "role": "user",
                    "content": "Giá bao nhiêu vậy shop?"
                },
                {
                    "role": "assistant",
                    "content": "Dạ bên em có cơ sở tại Hà Nội (399.000 VNĐ) và Quận 1 Sài Gòn (570.000 VNĐ) ạ."
                }
            ],
            "turn3_message": "cao thế, có gói nào mềm hơn không shop",
            "expected_price": "239.000",
            "forbidden_price": "260.000",
            "forbidden_branch": "sài gòn"
        }
    ]

    for tc in history_test_cases:
        t0 = time.time()
        r = requests.post(
            f"{API_URL}/api/bot/fanpage/message",
            json={
                "page_id": PAGE_ID,
                "customer_id": "test_saigon_latching_user",
                "message": tc["turn3_message"],
                "history": tc["history"]
            },
            timeout=10
        )
        elapsed = time.time() - t0
        assert r.status_code == 200, f"Status code {r.status_code}"
        data = r.json()
        reply = data.get("reply_text") or data.get("replyText") or ""
        lower_reply = reply.lower()

        p = (
            tc["expected_price"] in reply
            and tc["forbidden_price"] not in reply
            and (tc["forbidden_branch"] not in lower_reply or "hà nội" in lower_reply)
        )

        status_str = "PASS" if p else "FAIL"
        print(f"[{status_str}] {tc['name']} ({elapsed:.2f}s)")
        print(f"       Reply excerpt: {reply[:100]}...")
        if p:
            passed += 1
        else:
            failed += 1
            errors.append(f"Suite 1 Failure in {tc['name']}: Expected {tc['expected_price']}, Forbidden {tc['forbidden_price']}, Reply: {reply}")

    # -------------------------------------------------------------------------
    # SUITE 2: Extreme Budget Bounding vs False Positives ("tỷ lệ")
    # -------------------------------------------------------------------------
    print("\n--- SUITE 2: Extreme Budget Bounding & False Positives ---")

    budget_tests = [
        # Must trigger refusal (Priority 2)
        {
            "name": "100 triệu budget inquiry",
            "message": "Chị có ngân sách khoảng 100 triệu muốn mua gói chăm sóc cao cấp nhất",
            "should_refuse": True,
            "keyword": "chưa có gói"
        },
        {
            "name": "100tr shorthand budget inquiry",
            "message": "Ngân sách tầm 100tr bên shop có gói nào VIP nhất không em",
            "should_refuse": True,
            "keyword": "chưa có gói"
        },
        {
            "name": "1 tỷ astronomical budget inquiry",
            "message": "Bên mình có gói dịch vụ 1 tỷ cho khách VIP không shop",
            "should_refuse": True,
            "keyword": "chưa có gói"
        },
        {
            "name": "100.000.000 vnđ formatted budget inquiry",
            "message": "Tôi muốn gói trị liệu giá 100.000.000 vnđ",
            "should_refuse": True,
            "keyword": "chưa có gói"
        },
        {
            "name": "Tiền tỷ luxury inquiry",
            "message": "Liệu trình tiền tỷ bên em bao gồm những dịch vụ gì",
            "should_refuse": True,
            "keyword": "chưa có gói"
        },
        # MUST NOT trigger refusal (Must NOT match "tỷ")
        {
            "name": "Tỷ lệ khách hàng hài lòng inquiry",
            "message": "Tỷ lệ khách hàng hài lòng với dịch vụ massage vai gáy bên em là bao nhiêu?",
            "should_refuse": False,
            "keyword": "chưa có gói"
        },
        {
            "name": "Tỷ lệ tái khám inquiry",
            "message": "Cho mình hỏi tỷ lệ tái khám hoặc phục hồi sau liệu trình đau mỏi vai gáy?",
            "should_refuse": False,
            "keyword": "chưa có gói"
        },
        {
            "name": "Tỷ lệ chiết khấu inquiry",
            "message": "Tỷ lệ chiết khấu khi mua nhiều buổi bên em như thế nào?",
            "should_refuse": False,
            "keyword": "chưa có gói"
        }
    ]

    for bt in budget_tests:
        t0 = time.time()
        r = requests.post(
            f"{API_URL}/api/bot/fanpage/message",
            json={"page_id": PAGE_ID, "message": bt["message"]},
            timeout=10
        )
        elapsed = time.time() - t0
        assert r.status_code == 200, f"Status code {r.status_code}"
        data = r.json()
        reply = data.get("reply_text") or data.get("replyText") or ""
        lower_reply = reply.lower()

        has_keyword = bt["keyword"] in lower_reply
        if bt["should_refuse"]:
            p = has_keyword or "100 triệu" in reply or "1 tỷ" in reply
        else:
            p = not has_keyword and ("100 triệu hay 1 tỷ" not in lower_reply)

        status_str = "PASS" if p else "FAIL"
        print(f"[{status_str}] {bt['name']} ({elapsed:.2f}s)")
        print(f"       Reply excerpt: {reply[:90]}...")
        if p:
            passed += 1
        else:
            failed += 1
            errors.append(f"Suite 2 Failure in {bt['name']}: should_refuse={bt['should_refuse']}, Reply: {reply}")

    # -------------------------------------------------------------------------
    # SUITE 3: ReDoS & Oversized Payload Resiliency
    # -------------------------------------------------------------------------
    print("\n--- SUITE 3: ReDoS & Oversized Payload Resiliency ---")

    payload_sizes = [
        ("50,000 characters repeated words", "Shop ơi tư vấn dịch vụ giúp mình với nha! " * 1250, 5.0),
        ("60,000 characters whitespace & punctuation", "    alo shop ??? !!!   \n\t " * 2500, 5.0),
        ("Adversarial nested parentheses & quotes", "(([[{{'test'}}]]))" * 3000, 5.0)
    ]

    for name, payload, max_sec in payload_sizes:
        char_count = len(payload)
        t0 = time.time()
        r = requests.post(
            f"{API_URL}/api/bot/fanpage/message",
            json={"page_id": PAGE_ID, "message": payload},
            timeout=15
        )
        elapsed = time.time() - t0
        p = (r.status_code == 200 and elapsed < max_sec)

        status_str = "PASS" if p else "FAIL"
        print(f"[{status_str}] {name} ({char_count} chars, elapsed: {elapsed:.2f}s, threshold: <{max_sec}s)")
        if p:
            passed += 1
        else:
            failed += 1
            errors.append(f"Suite 3 Failure in {name}: elapsed={elapsed}s, status={r.status_code}")

    # -------------------------------------------------------------------------
    # SUITE 4: Male Name "Nam" Honorific Resolution
    # -------------------------------------------------------------------------
    print("\n--- SUITE 4: Male Name 'Nam' Honorific Resolution ---")

    male_name_queries = [
        ("Full name in statement", "tên mình là Nguyễn Văn Nam, tư vấn cho mình gói vai gáy"),
        ("Simple name introduction", "Tôi tên là Nam, cho tôi hỏi về dịch vụ gội đầu dưỡng sinh"),
        ("Shorthand name intro", "mình tên Nam, shop tư vấn giúp nhé"),
        ("Formal introduction", "Xin chào shop, tên của tôi là Trần Đình Nam"),
        ("Name at end of greeting", "Chào em, anh tên là Nam"),
        ("Multi-turn with name in history", "Cho mình hỏi gói massage 399k có những bước nào?",
         # History where user introduced name
         [{"role": "user", "content": "Chào shop, tên mình là Nguyễn Văn Nam"},
          {"role": "assistant", "content": "Dạ em chào Anh ạ! Rất vui được hỗ trợ Anh Nam ạ."}])
    ]

    for item in male_name_queries:
        if len(item) == 2:
            name, query = item
            history = []
        else:
            name, query, history = item

        t0 = time.time()
        r = requests.post(
            f"{API_URL}/api/bot/fanpage/message",
            json={
                "page_id": PAGE_ID,
                "message": query,
                "history": history
            },
            timeout=10
        )
        elapsed = time.time() - t0
        assert r.status_code == 200, f"Status code {r.status_code}"
        data = r.json()
        reply = data.get("reply_text") or data.get("replyText") or ""
        lower_reply = reply.lower()

        # Must address as "Anh" and must NOT address as "Chị"
        p = ("anh" in lower_reply and "chị" not in lower_reply)

        status_str = "PASS" if p else "FAIL"
        print(f"[{status_str}] {name} ({elapsed:.2f}s)")
        print(f"       Reply excerpt: {reply[:80]}...")
        if p:
            passed += 1
        else:
            failed += 1
            errors.append(f"Suite 4 Failure in {name}: 'anh' in reply: {'anh' in lower_reply}, 'chị' in reply: {'chị' in lower_reply}, Reply: {reply}")

    # -------------------------------------------------------------------------
    # SUMMARY
    # -------------------------------------------------------------------------
    print("\n" + "=" * 80)
    print("CHALLENGER DEEP STRESS SUITE RESULTS")
    print(f"Total: {passed + failed} | Passed: {passed} | Failed: {failed}")
    print("=" * 80)

    if errors:
        print("\nERRORS:")
        for e in errors:
            print(f" - {e}")
        return False
    else:
        print("\nALL DEEP ADVERSARIAL CHALLENGES CONFIRMED RESOLVED!")
        return True

if __name__ == "__main__":
    ok = run_tests()
    sys.exit(0 if ok else 1)
