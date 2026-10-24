#!/usr/bin/env python3
"""
Verification Suite for DocMind v3.1.4 Final Patch Pack — Nàng Sen Spa (Cleaned & Synchronized)
Tests all 8 regression scenarios (RT-SPA-001 through RT-SPA-008) + False-Positive Guards & Opt-Out (RT-SPA-009).
"""

import json
import re
import sys
import uuid
import urllib.request

API_URL = "http://localhost:8090/api/bot/fanpage/message"
PAGE_ID = "b23cd382-4f7a-4829-8c54-1d410da14bf8"
DENTAL_PAGE_ID = "d3000000-0000-0000-0000-000000000001"
PRICE_REGEX = re.compile(r"\b\d{1,3}(?:[.,]\d{3})+\s*(?:đ|vnđ|vnd)|(?:\b\d{2,3}\s*k\b)", re.IGNORECASE)


def call_chat(sender_id: str, message: str, history: list, customer_name: str = None, page_id: str = PAGE_ID):
    payload = {
        "page_id": page_id,
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
    matches = PRICE_REGEX.findall(text or "")
    normalized = set()
    for m in matches:
        digits = re.sub(r"\D", "", m)
        if not digits:
            continue
        val = int(digits)
        if val < 1000:
            val *= 1000
        normalized.add(val)
    return len(normalized)


def contains_any(text: str, candidates: list[str]) -> bool:
    lower = (text or "").lower()
    return any(c.lower() in lower for c in candidates)


def contains_none(text: str, forbidden: list[str]) -> bool:
    lower = (text or "").lower()
    return all(f.lower() not in lower for f in forbidden)


def run_suite():
    failures = []
    results = []

    print("=" * 88)
    print("VERIFYING DOCMIND v3.1.4 FINAL PATCH PACK — NÀNG SEN SPA (RT-SPA-001 .. RT-SPA-009)")
    print("=" * 88)

    # -------------------------------------------------------------------------
    # RT-SPA-001: Symptom first -> ask condition, never ask pressure
    # -------------------------------------------------------------------------
    s1 = f"rt-spa-001-{uuid.uuid4().hex[:8]}"
    r1 = call_chat(s1, "Tôi bị đau cơ mỏi vai", [])
    b1 = r1.get("reply_bubbles", [])
    txt1 = r1.get("reply_text", "")
    rt1_pass = (
        len(b1) == 3
        and contains_none(txt1, ["lực nhẹ", "lực vừa", "massage, ngâm thảo dược hay vệ sinh tai"])
        and contains_any(txt1, ["bị lâu chưa", "mới gần đây", "ngồi lâu", "vận động", "mỏi"])
        and txt1.count("?") <= 1
    )
    results.append(("RT-SPA-001: Symptom first -> ask condition, never ask pressure", rt1_pass, b1))
    if not rt1_pass:
        failures.append(f"RT-SPA-001 failed: bubbles={b1}")

    # -------------------------------------------------------------------------
    # RT-SPA-002: Frustration 'Hâm à' -> rescue + recommend, not repeat question
    # -------------------------------------------------------------------------
    s2 = f"rt-spa-002-{uuid.uuid4().hex[:8]}"
    hist2 = []
    r2_t1 = call_chat(s2, "Tôi bị đau cơ mỏi vai", hist2)
    hist2.append({"role": "user", "content": "Tôi bị đau cơ mỏi vai"})
    hist2.append({"role": "assistant", "content": r2_t1.get("reply_text", "")})

    r2_t2 = call_chat(s2, "Hâm à", hist2)
    b2_t2 = r2_t2.get("reply_bubbles", [])
    txt2_t2 = r2_t2.get("reply_text", "")
    rt2_pass = (
        1 <= len(b2_t2) <= 2
        and contains_any(txt2_t2, ["xin lỗi"])
        and contains_any(txt2_t2, ["massage cổ vai gáy", "45 phút", "60 phút"])
        and contains_none(txt2_t2, ["lực nhẹ", "lực vừa", "massage, ngâm thảo dược hay vệ sinh tai", "mình muốn hỏi thêm"])
        and txt2_t2.count("?") <= 1
    )
    results.append(("RT-SPA-002: Frustration 'Hâm à' -> rescue + recommend, not repeat question", rt2_pass, b2_t2))
    if not rt2_pass:
        failures.append(f"RT-SPA-002 failed: bubbles={b2_t2}")

    # -------------------------------------------------------------------------
    # RT-SPA-003: Repeated frustration -> human handoff
    # -------------------------------------------------------------------------
    s3 = f"rt-spa-003-{uuid.uuid4().hex[:8]}"
    hist3 = []
    r3_t1 = call_chat(s3, "Tôi bị đau cơ mỏi vai", hist3)
    hist3.append({"role": "user", "content": "Tôi bị đau cơ mỏi vai"})
    hist3.append({"role": "assistant", "content": r3_t1.get("reply_text", "")})

    r3_t2 = call_chat(s3, "Hâm à", hist3)
    hist3.append({"role": "user", "content": "Hâm à"})
    hist3.append({"role": "assistant", "content": r3_t2.get("reply_text", "")})

    r3_t3 = call_chat(s3, "Trả lời vớ vẩn thế", hist3)
    b3_t3 = r3_t3.get("reply_bubbles", [])
    txt3_t3 = r3_t3.get("reply_text", "")
    rt3_pass = (
        len(b3_t3) == 1
        and r3_t3.get("needs_human_handoff") is True
        and txt3_t3.count("?") == 0
        and contains_any(txt3_t3, ["xin lỗi", "nhân viên hỗ trợ trực tiếp"])
    )
    results.append(("RT-SPA-003: Repeated frustration -> human handoff (1 bubble, 0 questions)", rt3_pass, b3_t3))
    if not rt3_pass:
        failures.append(f"RT-SPA-003 failed: handoff={r3_t3.get('needs_human_handoff')}, bubbles={b3_t3}")

    # -------------------------------------------------------------------------
    # RT-SPA-004: Service already known -> never ask broad menu again
    # -------------------------------------------------------------------------
    s4 = f"rt-spa-004-{uuid.uuid4().hex[:8]}"
    hist4 = []
    r4_t1 = call_chat(s4, "Tôi muốn massage", hist4)
    hist4.append({"role": "user", "content": "Tôi muốn massage"})
    hist4.append({"role": "assistant", "content": r4_t1.get("reply_text", "")})

    r4_t2 = call_chat(s4, "Tôi bị đau cơ mỏi vai", hist4)
    hist4.append({"role": "user", "content": "Tôi bị đau cơ mỏi vai"})
    hist4.append({"role": "assistant", "content": r4_t2.get("reply_text", "")})

    r4_t3 = call_chat(s4, "Mỏi 3 ngày do ngồi máy tính", hist4)
    b4_t3 = r4_t3.get("reply_bubbles", [])
    txt4_t3 = r4_t3.get("reply_text", "")
    rt4_pass = (
        1 <= len(b4_t3) <= 2
        and contains_any(txt4_t3, ["massage cổ vai gáy", "45 phút", "60 phút"])
        and contains_none(txt4_t3, ["massage, ngâm thảo dược hay vệ sinh tai", "lực nhẹ hay lực vừa"])
    )
    results.append(("RT-SPA-004: Service already known -> never ask broad menu again", rt4_pass, b4_t3))
    if not rt4_pass:
        failures.append(f"RT-SPA-004 failed: bubbles={b4_t3}")

    # -------------------------------------------------------------------------
    # RT-SPA-005: Broad price Turn 1 -> condition -> recommend + quote with post-price delay
    # -------------------------------------------------------------------------
    s5 = f"rt-spa-005-{uuid.uuid4().hex[:8]}"
    hist5 = []
    r5_t1 = call_chat(s5, "Giá sao shop", hist5)
    b5_t1 = r5_t1.get("reply_bubbles", [])
    txt5_t1 = r5_t1.get("reply_text", "")
    hist5.append({"role": "user", "content": "Giá sao shop"})
    hist5.append({"role": "assistant", "content": txt5_t1})

    r5_t2 = call_chat(s5, "Tôi bị mỏi vai gáy do ngồi máy tính nhiều", hist5)
    b5_t2 = r5_t2.get("reply_bubbles", [])
    txt5_t2 = r5_t2.get("reply_text", "")
    rt5_pass = (
        len(b5_t1) == 3
        and count_prices(txt5_t1) == 0
        and len(b5_t2) == 2
        and contains_any(b5_t2[0]["text"], ["massage cổ vai gáy", "280", "350"])
        and count_prices(b5_t2[0]["text"]) == 1
        and b5_t2[1].get("typing_delay_ms", 0) >= 10000
        and contains_none(txt5_t2, ["lực nhẹ hay lực vừa", "massage, ngâm thảo dược hay vệ sinh tai"])
    )
    results.append(("RT-SPA-005: Broad price Turn 1 -> condition -> recommend + quote + 10s delay", rt5_pass, b5_t2))
    if not rt5_pass:
        failures.append(f"RT-SPA-005 failed: t1={b5_t1}, t2={b5_t2}")

    # -------------------------------------------------------------------------
    # RT-SPA-006: Broad price -> customer says 'Massage đi' -> clarify duration, not service menu
    # -------------------------------------------------------------------------
    s6 = f"rt-spa-006-{uuid.uuid4().hex[:8]}"
    hist6 = []
    r6_t1 = call_chat(s6, "Giá sao", hist6)
    hist6.append({"role": "user", "content": "Giá sao"})
    hist6.append({"role": "assistant", "content": r6_t1.get("reply_text", "")})

    r6_t2 = call_chat(s6, "Massage đi", hist6)
    b6_t2 = r6_t2.get("reply_bubbles", [])
    txt6_t2 = r6_t2.get("reply_text", "")
    rt6_pass = (
        1 <= len(b6_t2) <= 2
        and contains_any(txt6_t2, ["45 phút", "60 phút"])
        and contains_none(txt6_t2, ["massage, ngâm thảo dược hay vệ sinh tai"])
    )
    results.append(("RT-SPA-006: Broad price -> 'Massage đi' -> clarify duration, not service menu", rt6_pass, b6_t2))
    if not rt6_pass:
        failures.append(f"RT-SPA-006 failed: bubbles={b6_t2}")

    # -------------------------------------------------------------------------
    # RT-SPA-007: Price already discussed -> customer says 'Massage đi' -> advance to booking, no repeat price
    # -------------------------------------------------------------------------
    s7 = f"rt-spa-007-{uuid.uuid4().hex[:8]}"
    hist7 = []
    r7_t1 = call_chat(s7, "Giá gói cổ vai gáy 60 phút", hist7)
    hist7.append({"role": "user", "content": "Giá gói cổ vai gáy 60 phút"})
    hist7.append({"role": "assistant", "content": r7_t1.get("reply_text", "")})

    r7_t2 = call_chat(s7, "Massage đi", hist7)
    b7_t2 = r7_t2.get("reply_bubbles", [])
    txt7_t2 = r7_t2.get("reply_text", "")
    rt7_pass = (
        1 <= len(b7_t2) <= 2
        and contains_any(txt7_t2, ["khung giờ", "hôm nay", "ngày mai", "giữ lịch"])
        and count_prices(txt7_t2) == 0
    )
    results.append(("RT-SPA-007: Price discussed -> 'Massage đi' -> advance to booking, 0 price repeat", rt7_pass, b7_t2))
    if not rt7_pass:
        failures.append(f"RT-SPA-007 failed: bubbles={b7_t2}")

    # -------------------------------------------------------------------------
    # RT-SPA-008: Safety red flag -> immediate handoff
    # -------------------------------------------------------------------------
    s8 = f"rt-spa-008-{uuid.uuid4().hex[:8]}"
    r8 = call_chat(s8, "Tôi đau vai kèm tê lan xuống tay và chóng mặt", [])
    b8 = r8.get("reply_bubbles", [])
    txt8 = r8.get("reply_text", "")
    rt8_pass = (
        len(b8) == 1
        and r8.get("needs_human_handoff") is True
        and txt8.count("?") == 0
    )
    results.append(("RT-SPA-008: Safety red flag -> immediate handoff (1 bubble, 0 questions)", rt8_pass, b8))
    if not rt8_pass:
        failures.append(f"RT-SPA-008 failed: handoff={r8.get('needs_human_handoff')}, bubbles={b8}")

    # -------------------------------------------------------------------------
    # RT-SPA-009: False-positive guard ("Tức ngực khó thở" -> SAFETY_RISK) & Explicit Opt-Out ("Đừng nhắn nữa")
    # -------------------------------------------------------------------------
    s9a = f"rt-spa-009a-{uuid.uuid4().hex[:8]}"
    r9a = call_chat(s9a, "Tức ngực khó thở quá shop ơi", [])
    b9a = r9a.get("reply_bubbles", [])
    s9b = f"rt-spa-009b-{uuid.uuid4().hex[:8]}"
    r9b = call_chat(s9b, "Đừng nhắn nữa", [])
    b9b = r9b.get("reply_bubbles", [])
    txt9b = r9b.get("reply_text", "")
    rt9_pass = (
        len(b9a) == 1
        and r9a.get("needs_human_handoff") is True
        and len(b9b) == 1
        and r9b.get("needs_human_handoff") is False
        and txt9b.count("?") == 0
        and contains_any(txt9b, ["xin phép dừng"])
    )
    results.append(("RT-SPA-009: False-positive guard ('Tức ngực' -> Safety) & Explicit Opt-Out ('Đừng nhắn nữa')", rt9_pass, {"9a": b9a, "9b": b9b}))
    if not rt9_pass:
        failures.append(f"RT-SPA-009 failed: 9a={r9a}, 9b={r9b}")

    # =========================================================================
    # v3.1.5.3 HOTFIX REGRESSION SUITE (RT-SPA-010-V3.1.5.3 .. RT-SPA-020-V3.1.5.3)
    # =========================================================================

    # -------------------------------------------------------------------------
    # RT-SPA-010-V3.1.5.3 & RT-SPA-015-V3.1.5.3 & RT-SPA-016 & RT-SPA-017:
    # Full 5-turn flow:
    #   T1: "Giá sao"
    #   T2: "Tôi bị tê tay, mỏi vai và đau đầu"
    #   T3: "Thi thoảng mới bị nên k rõ" -> quotes 45 phút 280k (280.000đ), delay >= 10000ms, no "phòng riêng"
    #   T4: "đắt thế" -> Veteran sales 1st objection: hold 45 phút, no numeric price, invite 1-session trial 🥰, delay == 2000ms
    #   T5: "Thử xem sao" -> Books held package 45 phút 🥰, asks branch & time slot, 0 price repeat, no "gói ngắn"
    # -------------------------------------------------------------------------
    s10 = f"rt-spa-010-{uuid.uuid4().hex[:8]}"
    hist10 = []
    r10_t1 = call_chat(s10, "Giá sao", hist10)
    hist10.append({"role": "user", "content": "Giá sao"})
    hist10.append({"role": "assistant", "content": r10_t1.get("reply_text", "")})

    r10_t2 = call_chat(s10, "Tôi bị tê tay, mỏi vai và đau đầu", hist10)
    hist10.append({"role": "user", "content": "Tôi bị tê tay, mỏi vai và đau đầu"})
    hist10.append({"role": "assistant", "content": r10_t2.get("reply_text", "")})

    r10_t3 = call_chat(s10, "Thi thoảng mới bị nên k rõ", hist10)
    b10_t3 = r10_t3.get("reply_bubbles", [])
    txt10_t3 = r10_t3.get("reply_text", "")
    hist10.append({"role": "user", "content": "Thi thoảng mới bị nên k rõ"})
    hist10.append({"role": "assistant", "content": txt10_t3})

    r10_t4 = call_chat(s10, "đắt thế", hist10)
    b10_t4 = r10_t4.get("reply_bubbles", [])
    txt10_t4 = r10_t4.get("reply_text", "")
    hist10.append({"role": "user", "content": "đắt thế"})
    hist10.append({"role": "assistant", "content": txt10_t4})

    r10_t5 = call_chat(s10, "Thử xem sao", hist10)
    b10_t5 = r10_t5.get("reply_bubbles", [])
    txt10_t5 = r10_t5.get("reply_text", "")

    rt10_pass = (
        len(b10_t4) == 2
        and count_prices(txt10_t4) == 0
        and contains_any(b10_t4[0]["text"], ["cân nhắc chi phí"])
        and contains_any(b10_t4[0]["text"], ["kỹ thuật viên làm kỹ đúng vùng mỏi"])
        and contains_any(b10_t4[1]["text"], ["trải nghiệm thử 1 buổi", "45 phút", "🥰"])
        and b10_t4[1].get("typing_delay_ms") == 2000
        and contains_none(txt10_t4, ["30 phút", "200k", "200.000", "280k", "280.000"])
    )
    results.append(("RT-SPA-010-V3.1.5.3: 1st objection ('đắt thế') -> hold 45p + value + invite 1-session trial 🥰 (delay 2000ms)", rt10_pass, b10_t4))
    if not rt10_pass:
        failures.append(f"RT-SPA-010 failed: bubbles={b10_t4}")

    rt15_pass = (
        len(b10_t5) == 2
        and count_prices(txt10_t5) == 0
        and contains_any(b10_t5[0]["text"], ["trải nghiệm thử 1 buổi", "massage cổ vai gáy 45 phút", "🥰"])
        and contains_any(b10_t5[1]["text"], ["cơ sở nào", "khung giờ nào", "giữ lịch"])
        and contains_none(txt10_t5, ["gói ngắn", "30 phút", "280k", "280.000", "350k", "350.000"])
    )
    results.append(("RT-SPA-015-V3.1.5.3: Trial acceptance ('Thử xem sao') after 1st objection -> books held 45p package, 0 price repeat", rt15_pass, b10_t5))
    if not rt15_pass:
        failures.append(f"RT-SPA-015 failed: bubbles={b10_t5}")

    rt16_pass = (
        contains_none(txt10_t3, ["phòng riêng"])
        and contains_none(txt10_t5, ["phòng riêng"])
        and contains_any(txt10_t3, ["giữ lịch"])
        and contains_any(txt10_t5, ["giữ lịch"])
    )
    results.append(("RT-SPA-016-V3.1.5.3: No ungrounded 'phòng riêng' promise when docs_has_private_room=false", rt16_pass, {"t3": b10_t3, "t5": b10_t5}))
    if not rt16_pass:
        failures.append(f"RT-SPA-016 failed: t3={txt10_t3}, t5={txt10_t5}")

    rt17_pass = (
        contains_any(txt10_t4, ["nhiều anh/chị sau buổi đầu trải nghiệm có phản hồi khá tốt ạ"])
        and contains_none(txt10_t4, ["99%", "100%", "95%", "10.000 khách", "tất cả khách đều", "ai cũng hài lòng", "đều phản hồi"])
    )
    results.append(("RT-SPA-017-V3.1.5.3: Controlled qualitative social proof only, no fake stats or absolutes", rt17_pass, b10_t4))
    if not rt17_pass:
        failures.append(f"RT-SPA-017 failed: txt={txt10_t4}")

    # -------------------------------------------------------------------------
    # RT-SPA-011-V3.1.5.3: Explicit cheaper/shorter request on 1st objection -> immediate 1-step downsell
    # -------------------------------------------------------------------------
    s11 = f"rt-spa-011-{uuid.uuid4().hex[:8]}"
    hist11 = [
        {"role": "user", "content": "Giá gói massage cổ vai gáy 45 phút"},
        {"role": "assistant", "content": "Dạ em chào mình, em là An Nhiên, nhân viên chăm sóc của Nàng Sen Spa ạ.\n\nDạ gói massage cổ vai gáy 45 phút bên em có giá 280k (280.000đ) ạ.\n\nMình muốn đặt lịch vào khung giờ nào để em giữ lịch cho mình nhé?"},
    ]
    r11 = call_chat(s11, "Có gói nào ngắn hơn rẻ hơn không em?", hist11)
    b11 = r11.get("reply_bubbles", [])
    txt11 = r11.get("reply_text", "")
    rt11_pass = (
        len(b11) == 2
        and contains_any(b11[0]["text"], ["30 phút"])
        and contains_any(b11[0]["text"], ["200k", "200.000"])
        and b11[1].get("typing_delay_ms", 0) >= 10000
        and contains_none(txt11, ["45 phút", "280k", "280.000"])
    )
    results.append(("RT-SPA-011-V3.1.5.3: Explicit cheaper/shorter request -> immediate 1-step downsell to 30p (200k)", rt11_pass, b11))
    if not rt11_pass:
        failures.append(f"RT-SPA-011 failed: bubbles={b11}")

    # -------------------------------------------------------------------------
    # RT-SPA-012-V3.1.5.3 & RT-SPA-013-V3.1.5.3:
    # 2nd price objection ("Nhưng vẫn thấy hơi đắt em ạ") -> downsell 1 step to 30 phút 200k, forbid 45 phút
    # Followed by "Thử xem sao" -> books downsell package ("gói ngắn 30 phút")
    # -------------------------------------------------------------------------
    s12 = f"rt-spa-012-{uuid.uuid4().hex[:8]}"
    hist12 = [
        {"role": "user", "content": "Giá gói massage cổ vai gáy 45 phút"},
        {"role": "assistant", "content": "Dạ gói massage cổ vai gáy 45 phút bên em có giá 280k (280.000đ) ạ."},
    ]
    r12_t1 = call_chat(s12, "Đắt thế", hist12)
    hist12.append({"role": "user", "content": "Đắt thế"})
    hist12.append({"role": "assistant", "content": r12_t1.get("reply_text", "")})

    r12_t2 = call_chat(s12, "Nhưng vẫn thấy hơi đắt em ạ", hist12)
    b12_t2 = r12_t2.get("reply_bubbles", [])
    txt12_t2 = r12_t2.get("reply_text", "")
    rt12_pass = (
        len(b12_t2) == 2
        and contains_any(b12_t2[0]["text"], ["gói ngắn 30 phút", "30 phút"])
        and contains_any(b12_t2[0]["text"], ["200k", "200.000"])
        and b12_t2[1].get("typing_delay_ms", 0) >= 10000
        and contains_none(txt12_t2, ["45 phút", "280k", "280.000", "cân nhắc chi phí"])
    )
    results.append(("RT-SPA-012-V3.1.5.3: 2nd price objection -> 1-step downsell to 30p (200k), VR-004A forbids 45p/280k", rt12_pass, b12_t2))
    if not rt12_pass:
        failures.append(f"RT-SPA-012 failed: bubbles={b12_t2}")

    hist12.append({"role": "user", "content": "Nhưng vẫn thấy hơi đắt em ạ"})
    hist12.append({"role": "assistant", "content": txt12_t2})
    r13 = call_chat(s12, "Thử xem sao", hist12)
    b13 = r13.get("reply_bubbles", [])
    txt13 = r13.get("reply_text", "")
    rt13_pass = (
        len(b13) == 2
        and count_prices(txt13) == 0
        and contains_any(b13[0]["text"], ["trải nghiệm thử 1 buổi", "gói ngắn 30 phút", "🥰"])
        and contains_any(b13[1]["text"], ["cơ sở nào", "khung giờ nào", "giữ lịch"])
        and contains_none(txt13, ["45 phút", "280k", "200k", "200.000"])
    )
    results.append(("RT-SPA-013-V3.1.5.3: Trial acceptance after downsell -> books 'gói ngắn 30 phút', 0 price repeat", rt13_pass, b13))
    if not rt13_pass:
        failures.append(f"RT-SPA-013 failed: bubbles={b13}")

    # -------------------------------------------------------------------------
    # RT-SPA-014-V3.1.5.3: Soft "thôi" ("Đắt thế nhưng thôi thử xem sao") vs Strong rejection ("Thôi khỏi em ạ")
    # -------------------------------------------------------------------------
    s14a = f"rt-spa-014a-{uuid.uuid4().hex[:8]}"
    hist14a = [
        {"role": "user", "content": "Giá gói massage cổ vai gáy 45 phút"},
        {"role": "assistant", "content": "Dạ gói massage cổ vai gáy 45 phút bên em có giá 280k (280.000đ) ạ."},
    ]
    r14a = call_chat(s14a, "Đắt thế nhưng thôi thử xem sao", hist14a)
    b14a = r14a.get("reply_bubbles", [])
    txt14a = r14a.get("reply_text", "")

    s14b = f"rt-spa-014b-{uuid.uuid4().hex[:8]}"
    r14b = call_chat(s14b, "Thôi khỏi em ạ", hist14a)
    b14b = r14b.get("reply_bubbles", [])
    txt14b = r14b.get("reply_text", "")

    rt14_pass = (
        len(b14a) == 2
        and contains_any(txt14a, ["trải nghiệm thử 1 buổi", "45 phút"])
        and contains_any(txt14a, ["cơ sở nào", "khung giờ nào"])
        and len(b14b) == 1
        and txt14b.count("?") == 0
        and contains_any(txt14b, ["không sao ạ", "cảm ơn", "khi nào mình cần thư giãn"])
        and contains_none(txt14b, ["trải nghiệm thử 1 buổi", "khung giờ nào"])
    )
    results.append(("RT-SPA-014-V3.1.5.3: Soft 'thôi' ('Đắt thế nhưng thôi thử xem sao') vs Strong rejection ('Thôi khỏi em ạ')", rt14_pass, {"14a": b14a, "14b": b14b}))
    if not rt14_pass:
        failures.append(f"RT-SPA-014 failed: 14a={b14a}, 14b={b14b}")

    # -------------------------------------------------------------------------
    # RT-SPA-018-V3.1.5.3: Explicit downsell when already at lowest package (30p) -> polite handoff
    # -------------------------------------------------------------------------
    s18 = f"rt-spa-018-{uuid.uuid4().hex[:8]}"
    hist18 = [
        {"role": "user", "content": "Giá gói massage cổ vai gáy 30 phút"},
        {"role": "assistant", "content": "Dạ gói massage cổ vai gáy 30 phút bên em có giá 200k (200.000đ) ạ."},
    ]
    r18 = call_chat(s18, "Có gói nào ngắn hơn rẻ hơn nữa không em?", hist18)
    b18 = r18.get("reply_bubbles", [])
    txt18 = r18.get("reply_text", "")
    rt18_pass = (
        len(b18) == 1
        and r18.get("needs_human_handoff") is True
        and txt18.count("?") == 0
        and contains_any(txt18, ["không có gói ngắn hơn mức này", "chuyển chuyên viên"])
    )
    results.append(("RT-SPA-018-V3.1.5.3: Explicit downsell when no lower package exists -> NO_LOWER_PACKAGE_HANDOFF", rt18_pass, b18))
    if not rt18_pass:
        failures.append(f"RT-SPA-018 failed: handoff={r18.get('needs_human_handoff')}, bubbles={b18}")

    # -------------------------------------------------------------------------
    # RT-SPA-019-V3.1.5.3: Neurological safety red flag ("Tôi bị tê tay và đau đầu dữ dội") -> immediate handoff
    # -------------------------------------------------------------------------
    s19 = f"rt-spa-019-{uuid.uuid4().hex[:8]}"
    r19 = call_chat(s19, "Tôi bị tê tay và đau đầu dữ dội", [])
    b19 = r19.get("reply_bubbles", [])
    txt19 = r19.get("reply_text", "")
    rt19_pass = (
        len(b19) == 1
        and r19.get("needs_human_handoff") is True
        and txt19.count("?") == 0
        and count_prices(txt19) == 0
        and contains_any(txt19, ["bác sĩ", "cơ sở y tế", "kiểm tra an toàn", "chuyển chuyên viên"])
    )
    results.append(("RT-SPA-019-V3.1.5.3: Neurological safety red flag ('tê tay và đau đầu dữ dội') -> immediate handoff", rt19_pass, b19))
    if not rt19_pass:
        failures.append(f"RT-SPA-019 failed: handoff={r19.get('needs_human_handoff')}, bubbles={b19}")

    # -------------------------------------------------------------------------
    # RT-SPA-020-V3.1.5.3: "Có gói 30 phút thử xem sao không em?" when held=45p -> EXPLICIT_DOWNSELL_REQUEST (not 45p booking)
    # -------------------------------------------------------------------------
    s20 = f"rt-spa-020-{uuid.uuid4().hex[:8]}"
    hist20 = [
        {"role": "user", "content": "Giá gói massage cổ vai gáy 45 phút"},
        {"role": "assistant", "content": "Dạ gói massage cổ vai gáy 45 phút bên em có giá 280k (280.000đ) ạ."},
    ]
    r20 = call_chat(s20, "Có gói 30 phút thử xem sao không em?", hist20)
    b20 = r20.get("reply_bubbles", [])
    txt20 = r20.get("reply_text", "")
    rt20_pass = (
        len(b20) == 2
        and contains_any(b20[0]["text"], ["30 phút"])
        and contains_any(b20[0]["text"], ["200k", "200.000"])
        and b20[1].get("typing_delay_ms", 0) >= 10000
        and contains_none(txt20, ["45 phút", "280k", "280.000"])
    )
    results.append(("RT-SPA-020-V3.1.5.3: 'Có gói 30 phút thử xem sao không em?' when held=45p -> downsell to 30p (200k)", rt20_pass, b20))
    if not rt20_pass:
        failures.append(f"RT-SPA-020 failed: bubbles={b20}")

    # -------------------------------------------------------------------------
    # RT-SPA-018 (Multi-Entity Extraction, Scope-Grounded Recommendation & ENTITY_MISMATCH_CORRECTION):
    #   T1: "Giá" -> 3 opening/discovery bubbles, 0 price
    #   T2: "Tôi bị chân đau, mỏi gối tê bì" -> 1 bubble, normalizes "đau chân, mỏi gối và tê bì", never narrows to "chân"
    #   T3: "thi thoảng" -> 2 bubbles, scope-grounded to ["chân", "gối"] ("ngâm chân thảo dược ấm" or "massage body thư giãn"), NEVER "cổ vai gáy" or "280k"
    #   T4: "Tao đau chân, mỏi gối tê tay mà mày đi tư vấn massage cổ là sao" -> 1 bubble, 0 questions, needs_human_handoff=True, apologizes & transfers to quản lý, NEVER hijacked by "massage cổ"
    # -------------------------------------------------------------------------
    s21 = f"rt-spa-018-multientity-{uuid.uuid4().hex[:8]}"
    hist21 = []
    r21_t1 = call_chat(s21, "Giá", hist21)
    b21_t1 = r21_t1.get("reply_bubbles", [])
    txt21_t1 = r21_t1.get("reply_text", "")
    hist21.append({"role": "user", "content": "Giá"})
    hist21.append({"role": "assistant", "content": txt21_t1})

    r21_t2 = call_chat(s21, "Tôi bị chân đau, mỏi gối tê bì", hist21)
    b21_t2 = r21_t2.get("reply_bubbles", [])
    txt21_t2 = r21_t2.get("reply_text", "")
    hist21.append({"role": "user", "content": "Tôi bị chân đau, mỏi gối tê bì"})
    hist21.append({"role": "assistant", "content": txt21_t2})

    r21_t3 = call_chat(s21, "thi thoảng", hist21)
    b21_t3 = r21_t3.get("reply_bubbles", [])
    txt21_t3 = r21_t3.get("reply_text", "")
    hist21.append({"role": "user", "content": "thi thoảng"})
    hist21.append({"role": "assistant", "content": txt21_t3})

    r21_t4 = call_chat(s21, "Tao đau chân, mỏi gối tê tay mà mày đi tư vấn massage cổ là sao", hist21)
    b21_t4 = r21_t4.get("reply_bubbles", [])
    txt21_t4 = r21_t4.get("reply_text", "")

    rt21_pass = (
        len(b21_t1) == 3
        and count_prices(txt21_t1) == 0
        and 1 <= len(b21_t2) <= 2
        and contains_any(txt21_t2, ["đau chân, mỏi gối và tê bì"])
        and contains_none(txt21_t2, ["vùng chân mình", "vùng vai mình", "lực nhẹ hay lực vừa"])
        and len(b21_t3) == 2
        and contains_any(txt21_t3, ["ngâm chân thảo dược ấm", "massage body thư giãn"])
        and contains_none(txt21_t3, ["cổ vai gáy", "280k", "280.000"])
        and len(b21_t4) == 1
        and r21_t4.get("needs_human_handoff") is True
        and txt21_t4.count("?") == 0
        and contains_any(txt21_t4, ["xin lỗi", "quản lý"])
        and contains_none(txt21_t4, ["280k", "280.000", "gói massage cổ"])
    )
    results.append(("RT-SPA-018 (Multi-Entity + Scope-Grounded + ENTITY_MISMATCH_CORRECTION 4-Turn Flow)", rt21_pass, {"t2": b21_t2, "t3": b21_t3, "t4": b21_t4}))
    if not rt21_pass:
        failures.append(f"RT-SPA-018 (4-turn) failed: t1={b21_t1}, t2={b21_t2}, t3={b21_t3}, t4={r21_t4}")

    # -------------------------------------------------------------------------
    # RT-MULTI-TENANT-001 (Cross-Industry Dental Clinic Tenant — Nha Khoa Tỏa Sáng):
    #   Proves zero Spa hardcoding in runtime; bot dynamically speaks as Minh Anh of Nha Khoa Tỏa Sáng
    #   and recommends dental services from Active Chunks with post-price delay >= 10000ms.
    # -------------------------------------------------------------------------
    s_dental = f"rt-dental-001-{uuid.uuid4().hex[:8]}"
    hist_dental = []
    rd_t1 = call_chat(s_dental, "Bảng giá bên mình sao shop", hist_dental, page_id=DENTAL_PAGE_ID)
    bd_t1 = rd_t1.get("reply_bubbles", [])
    txtd_t1 = rd_t1.get("reply_text", "")
    hist_dental.append({"role": "user", "content": "Bảng giá bên mình sao shop"})
    hist_dental.append({"role": "assistant", "content": txtd_t1})

    rd_t2 = call_chat(s_dental, "Mình bị chảy máu chân răng và răng ố vàng", hist_dental, page_id=DENTAL_PAGE_ID)
    bd_t2 = rd_t2.get("reply_bubbles", [])
    txtd_t2 = rd_t2.get("reply_text", "")
    hist_dental.append({"role": "user", "content": "Mình bị chảy máu chân răng và răng ố vàng"})
    hist_dental.append({"role": "assistant", "content": txtd_t2})

    rd_t3 = call_chat(s_dental, "Mới bị khoảng 2 tuần nay", hist_dental, page_id=DENTAL_PAGE_ID)
    bd_t3 = rd_t3.get("reply_bubbles", [])
    txtd_t3 = rd_t3.get("reply_text", "")

    rt_dental_pass = (
        len(bd_t1) == 3
        and contains_any(txtd_t1, ["minh anh", "nha khoa tỏa sáng"])
        and contains_none(txtd_t1, ["nàng sen", "an nhiên", "massage", "cổ vai gáy"])
        and 1 <= len(bd_t2) <= 2
        and contains_any(txtd_t2, ["chảy máu chân răng", "răng ố vàng"])
        and contains_none(txtd_t2, ["massage", "cổ vai gáy", "nàng sen", "ngồi lâu", "vận động nhiều", "vùng đang mỏi"])
        and len(bd_t3) == 2
        and contains_any(txtd_t3, ["cạo vôi răng", "tẩy trắng răng"])
        and contains_any(txtd_t3, ["250k", "900k"])
        and bd_t3[1].get("typing_delay_ms", 0) >= 10000
        and contains_none(txtd_t3, ["massage", "cổ vai gáy", "nàng sen", "280k", "vùng đang mỏi"])
    )
    results.append(("RT-MULTI-TENANT-001: Cross-Industry Dental Clinic ('Nha Khoa Tỏa Sáng') — Zero Spa Leakage", rt_dental_pass, {"t1": bd_t1, "t2": bd_t2, "t3": bd_t3}))
    if not rt_dental_pass:
        failures.append(f"RT-MULTI-TENANT-001 failed: t1={bd_t1}, t2={bd_t2}, t3={bd_t3}")

    # Print summary
    for label, passed, detail in results:
        status = "PASS ✅" if passed else "FAIL ❌"
        print(f"[{status}] {label}")
        print(f"    -> {json.dumps(detail, ensure_ascii=False)}")

    print("=" * 88)
    passed_count = sum(1 for _, p, _ in results if p)
    print(f"TOTAL: {passed_count}/{len(results)} PASSED")
    if failures:
        print("FAILURES:")
        for f in failures:
            print(f"  - {f}")
        sys.exit(1)
    print("ALL v3.1.4 REGRESSION TESTS PASSED!")


if __name__ == "__main__":
    run_suite()
