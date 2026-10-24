#!/usr/bin/env python3
"""
Verification Suite for Domain-Agnostic Runtime Control & Stateful Action Router (v3.1)
Tests all 6 Failure Cases from Section 8 of SPA_RUNTIME_CONTROL_FIX_v3.1.md:
  - Case 1: Turn 1 "Giá" -> 3 bubbles (2 opening bubbles + 1 clarify bubble)
  - Case 2: Turn 1 "T bị mỏi vai nhức gáy và tê tay" -> 2 opening bubbles + 1 preference question (NO 2 prices!)
            Turn 2 "Lực vừa" -> recommends 1 package
            Turn 3 "Giá sao" -> answers price once + Bubble 2 has delay >= 10000ms
  - Case 3: "Sao gói 45p lại đắt hơn 60p thế" -> EXPLAIN_UNIT_VALUE (~7.76k/min vs ~6.65k/min), NO "gợi ý gói ngắn"
  - Case 4: After price turn, user says "Uk" -> ADVANCE_AFTER_ACK, NO repeated price
  - Case 5: Turn "Đắt" (1st time) -> HANDLE_PRICE_OBJECTION_FIRST_TIME (downsell 280.000đ)
            Turn "Vẫn đắt" (2nd time) -> HANDLE_PRICE_OBJECTION_REPEAT (NO repeated first-time template, no lower price)
  - Case 6: After repeat objection, user says "Ừ" -> ADVANCE_AFTER_ACK, NO repeated price
"""

import json
import re
import sys
import uuid
import urllib.request

API_URL = "http://localhost:8090/api/bot/fanpage/message"
PAGE_ID = "b23cd382-4f7a-4829-8c54-1d410da14bf8"
PRICE_REGEX = re.compile(r"\b\d{1,3}(?:[.,]\d{3})+\s*(?:đ|vnđ|vnd)|(?:\b\d{2,3}\s*k\b)", re.IGNORECASE)


def call_chat(sender_id: str, message: str, history: list, customer_name: str = None):
    payload = {
        "page_id": PAGE_ID,
        "sender_id": sender_id,
        "customer_name": customer_name,
        "message": message,
        "history": history,
    }
    req = urllib.request.Request(
        API_URL,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def count_prices(text: str) -> int:
    return len(PRICE_REGEX.findall(text or ""))


def run_suite():
    failures = []
    results = []

    print("=" * 80)
    print("VERIFYING DOMAIN-AGNOSTIC RUNTIME CONTROL & STATE MACHINE v3.1 (6 CASES)")
    print("=" * 80)

    # -------------------------------------------------------------------------
    # CASE 1: Turn 1 "Giá" -> 2 opening bubbles + 1 clarify bubble = 3 bubbles
    # -------------------------------------------------------------------------
    s1 = f"v31-case1-{uuid.uuid4().hex[:8]}"
    r1 = call_chat(s1, "Giá", [])
    b1 = r1.get("reply_bubbles", [])
    reply1 = r1.get("reply_text", "")
    c1_pass = (
        len(b1) == 3
        and "chào" in b1[0]["text"].lower()
        and ("nàng sen" in b1[1]["text"].lower() or "thư giãn" in b1[1]["text"].lower())
        and "?" in b1[2]["text"]
        and count_prices(reply1) == 0
    )
    results.append(("Case 1: Turn 1 'Giá' -> 3 bubbles (2 opening + 1 clarify, 0 price)", c1_pass, b1))
    if not c1_pass:
        failures.append(f"Case 1 failed: bubbles={b1}")

    # -------------------------------------------------------------------------
    # CASE 2: Multi-turn Symptom -> Preference -> Price (with 10s delay)
    # -------------------------------------------------------------------------
    s2 = f"v31-case2-{uuid.uuid4().hex[:8]}"
    history2 = []
    # Turn 1: "T bị mỏi vai nhức gáy và tê tay"
    r2_t1 = call_chat(s2, "T bị mỏi vai nhức gáy và tê tay", history2)
    b2_t1 = r2_t1.get("reply_bubbles", [])
    reply2_t1 = r2_t1.get("reply_text", "")
    c2_t1_pass = (
        len(b2_t1) == 3
        and "chào" in b2_t1[0]["text"].lower()
        and (
            "bị lâu chưa" in b2_t1[2]["text"].lower()
            or "ngồi lâu" in b2_t1[2]["text"].lower()
            or "lực nhẹ" in b2_t1[2]["text"].lower()
            or "lực vừa" in b2_t1[2]["text"].lower()
        )
        and count_prices(reply2_t1) == 0
    )
    history2.append({"role": "user", "content": "T bị mỏi vai nhức gáy và tê tay"})
    history2.append({"role": "assistant", "content": reply2_t1})

    # Turn 2: "Lực vừa"
    r2_t2 = call_chat(s2, "Lực vừa", history2)
    b2_t2 = r2_t2.get("reply_bubbles", [])
    reply2_t2 = r2_t2.get("reply_text", "")
    c2_t2_pass = (
        1 <= len(b2_t2) <= 2
        and "chào" not in reply2_t2.lower()
        and ("60 phút" in reply2_t2.lower() or "cổ vai gáy" in reply2_t2.lower() or "body" in reply2_t2.lower())
        and count_prices(reply2_t2) <= 1
    )
    history2.append({"role": "user", "content": "Lực vừa"})
    history2.append({"role": "assistant", "content": reply2_t2})

    # Turn 3: "Giá sao"
    r2_t3 = call_chat(s2, "Giá sao", history2)
    b2_t3 = r2_t3.get("reply_bubbles", [])
    reply2_t3 = r2_t3.get("reply_text", "")
    c2_t3_pass = (
        len(b2_t3) == 2
        and ("280.000" in b2_t3[0]["text"] or "280k" in b2_t3[0]["text"] or "350.000" in b2_t3[0]["text"])
        and count_prices(b2_t3[1]["text"]) == 0
        and b2_t3[1].get("typing_delay_ms", 0) >= 10000
    )
    c2_pass = c2_t1_pass and c2_t2_pass and c2_t3_pass
    results.append((
        "Case 2: Symptom -> Preference ('Lực vừa') -> 'Giá sao' (10s delay on Bubble 2)",
        c2_pass,
        {"turn1": b2_t1, "turn2": b2_t2, "turn3": b2_t3},
    ))
    if not c2_pass:
        failures.append(f"Case 2 failed: t1={c2_t1_pass}, t2={c2_t2_pass}, t3={c2_t3_pass}, b2_t3={b2_t3}")

    # -------------------------------------------------------------------------
    # CASE 3: "Sao gói 45p lại đắt hơn 60p thế" -> EXPLAIN_UNIT_VALUE
    # -------------------------------------------------------------------------
    s3 = f"v31-case3-{uuid.uuid4().hex[:8]}"
    history3 = [
        {"role": "user", "content": "Mình bị mỏi vai gáy"},
        {"role": "assistant", "content": "Dạ em chào mình ạ! Mình muốn làm gói 60 phút (399.000đ) hay 45 phút (349.000đ) ạ?"},
    ]
    r3 = call_chat(s3, "Sao gói 45p lại đắt hơn 60p thế", history3)
    b3 = r3.get("reply_bubbles", [])
    reply3 = r3.get("reply_text", "")
    c3_pass = (
        1 <= len(b3) <= 2
        and ("phút" in reply3.lower() or "tính theo phút" in reply3.lower() or "6.6" in reply3.lower() or "7.7" in reply3.lower())
        and "ngân sách" not in reply3.lower()
        and "gợi ý gói ngắn" not in reply3.lower()
    )
    results.append(("Case 3: 'Sao gói 45p lại đắt hơn 60p thế' -> EXPLAIN_UNIT_VALUE", c3_pass, b3))
    if not c3_pass:
        failures.append(f"Case 3 failed: reply={reply3}")

    # -------------------------------------------------------------------------
    # CASE 4: "Uk" after price -> ADVANCE_AFTER_ACK (0 prices repeated)
    # -------------------------------------------------------------------------
    history4 = history3 + [
        {"role": "user", "content": "Sao gói 45p lại đắt hơn 60p thế"},
        {"role": "assistant", "content": reply3},
    ]
    r4 = call_chat(s3, "Uk", history4)
    b4 = r4.get("reply_bubbles", [])
    reply4 = r4.get("reply_text", "")
    c4_pass = (
        len(b4) == 1
        and count_prices(reply4) == 0
        and "?" in reply4
    )
    results.append(("Case 4: 'Uk' after price -> ADVANCE_AFTER_ACK (1 bubble, 0 prices)", c4_pass, b4))
    if not c4_pass:
        failures.append(f"Case 4 failed: bubbles={b4}")

    # -------------------------------------------------------------------------
    # CASE 5: "Đắt" (1st time) -> "Vẫn đắt" (2nd time, NO template repeat)
    # -------------------------------------------------------------------------
    s5 = f"v31-case5-{uuid.uuid4().hex[:8]}"
    history5 = [
        {"role": "user", "content": "Giá massage cổ vai gáy 90p bao nhiêu"},
        {"role": "assistant", "content": "Dạ em chào mình ạ! Gói Massage cổ vai gáy 90 phút bên em có giá 480.000đ ạ."},
    ]
    r5_t1 = call_chat(s5, "Đắt", history5)
    b5_t1 = r5_t1.get("reply_bubbles", [])
    reply5_t1 = r5_t1.get("reply_text", "")
    c5_t1_pass = (
        len(b5_t1) == 2
        and "hiểu" in b5_t1[0]["text"].lower()
        and "cân nhắc chi phí" in b5_t1[0]["text"].lower()
        and "trải nghiệm thử 1 buổi" in b5_t1[1]["text"].lower()
        and "🥰" in b5_t1[1]["text"]
        and b5_t1[1].get("typing_delay_ms", 0) == 2000
    )
    history5.append({"role": "user", "content": "Đắt"})
    history5.append({"role": "assistant", "content": reply5_t1})

    r5_t2 = call_chat(s5, "Vẫn đắt", history5)
    b5_t2 = r5_t2.get("reply_bubbles", [])
    reply5_t2 = r5_t2.get("reply_text", "")
    c5_t2_pass = (
        len(b5_t2) == 2
        and "hiểu mình đang cân nhắc chi phí" not in reply5_t2.lower()
        and "60 phút" in b5_t2[0]["text"].lower()
        and ("350k" in b5_t2[0]["text"].lower() or "350.000" in b5_t2[0]["text"].lower())
        and "90 phút" not in reply5_t2.lower()
        and "480" not in reply5_t2.lower()
        and b5_t2[1].get("typing_delay_ms", 0) >= 10000
    )
    c5_pass = c5_t1_pass and c5_t2_pass
    results.append((
        "Case 5: 'Đắt' (1st time) -> 'Vẫn đắt' (2nd time -> HANDLE_PRICE_OBJECTION_REPEAT)",
        c5_pass,
        {"first_objection": b5_t1, "repeat_objection": b5_t2},
    ))
    if not c5_pass:
        failures.append(f"Case 5 failed: t1={c5_t1_pass} ({reply5_t1}), t2={c5_t2_pass} ({reply5_t2})")

    # -------------------------------------------------------------------------
    # CASE 6: "Ừ" after repeat objection -> ADVANCE_AFTER_ACK (0 prices)
    # -------------------------------------------------------------------------
    history6 = history5 + [
        {"role": "user", "content": "Vẫn đắt"},
        {"role": "assistant", "content": reply5_t2},
    ]
    r6 = call_chat(s5, "Ừ", history6)
    b6 = r6.get("reply_bubbles", [])
    reply6 = r6.get("reply_text", "")
    c6_pass = (
        len(b6) == 1
        and count_prices(reply6) == 0
    )
    results.append(("Case 6: 'Ừ' after repeat objection -> ADVANCE_AFTER_ACK (0 prices)", c6_pass, b6))
    if not c6_pass:
        failures.append(f"Case 6 failed: bubbles={b6}")

    for title, passed, detail in results:
        status = "PASS ✅" if passed else "FAIL ❌"
        print(f"\n[{status}] {title}")
        print(json.dumps(detail, ensure_ascii=False, indent=2))

    print("\n" + "=" * 80)
    if failures:
        print(f"SUMMARY: {len(results) - len(failures)}/{len(results)} PASSED, {len(failures)} FAILED")
        for f in failures:
            print(" -", f)
        sys.exit(1)
    else:
        print(f"SUMMARY: ALL {len(results)}/{len(results)} V3.1 FAILURE CASES PASSED 100%! 🎉")
        sys.exit(0)


if __name__ == "__main__":
    run_suite()
