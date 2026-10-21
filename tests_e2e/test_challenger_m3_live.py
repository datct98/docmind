"""Empirical Challenger Verification Harness for M3 Live Multi-Turn Dialogue & Guardrails.

Adversarial Tests against:
Endpoint: http://localhost:8090/api/bot/fanpage/message
Page ID:  2850a8ef-ca4a-40eb-87fa-4f808640efee

Tasks Verified:
1. Turn 1: 650.000đ starting price, free 3D AI scan, anti-info dumping hook, 0 Lan Anh, 0 citations.
2. Turn 2: 1.500.000đ, acne treatment boundary regarding pitted scars, no repeated greeting.
3. Turn 3: Male gender variations ("mìn là nam", "minh la nam", "mình là con trai", "mk là nam") -> 100% switch to "Anh" with ZERO "Chị" in reply!
4. Turn 4: Phone lead ("0968221644 tư vấn mình thêm") -> fast-path (<0.5s), needs_human_handoff = true, phone captured, sales pitch silenced.
5. Buying Temperature decay and takeaway response on brush-off ("ừ để chị xem thêm") and idle time (300s).
"""

import os
import re
import time
import pytest
import requests

API_URL = os.getenv("API_JAVA_URL", "http://localhost:8090")
PAGE_ID = "2850a8ef-ca4a-40eb-87fa-4f808640efee"

CITATION_PATTERN = re.compile(
    r"(?i)(?:\[\s*\d+\s*\]|\[\s*spa-\d+\s*\]|\bspa-\d{1,3}\b|\[\s*mục:[^\]]*\]|\(\s*spa-\d+\s*\))"
)
LAN_ANH_PATTERN = re.compile(r"(?i)\blan\s+anh\b")
CHI_PATTERN = re.compile(r"(?i)\bchị\b")


def call_fanpage_bot(message, history=None, customer_name=None, customer_id="challenger_user_01", timeout=90):
    url = f"{API_URL}/api/bot/fanpage/message"
    payload = {
        "page_id": PAGE_ID,
        "customer_id": customer_id,
        "customer_name": customer_name,
        "message": message,
        "history": history or []
    }
    t0 = time.perf_counter()
    res = requests.post(url, json=payload, timeout=timeout)
    elapsed = time.perf_counter() - t0
    assert res.status_code == 200, f"HTTP {res.status_code}: {res.text}"
    data = res.json()
    return data, elapsed


class TestTurn1DiscoveryAndPricingAnchor:
    """Probe Turn 1: Broad pricing inquiry in DISCOVERY stage."""

    def test_turn1_pricing_scan_antidump_integrity(self):
        msg = "Cho mình hỏi về dịch vụ trị mụn bên bạn, chi phí như thế nào?"
        data, elapsed = call_fanpage_bot(msg, history=[], customer_id="challenger_t1_01")

        reply = data.get("reply_text", "")
        bubbles = data.get("reply_bubbles", [])
        hooks = data.get("triggered_hooks", [])
        stage = data.get("sales_stage", "")

        # 1. Price anchor
        assert "650.000" in reply, f"Starting price 650.000đ missing in Turn 1 reply: {reply}"

        # 2. Free 3D AI scan
        assert any(k in reply.lower() for k in ["soi da", "ai 3d", "3d"]), (
            f"Free 3D AI skin analysis missing in Turn 1 reply: {reply}"
        )

        # 3. Anti-info dumping hook
        assert "HOOK_ANTI_INFO_DUMPING_DISCOVERY" in hooks, (
            f"HOOK_ANTI_INFO_DUMPING_DISCOVERY not triggered in hooks: {hooks}"
        )

        # 4. Zero Lan Anh
        assert not LAN_ANH_PATTERN.search(reply), f"Phantom name 'Lan Anh' found in Turn 1 reply: {reply}"

        # 5. Zero citations
        citations = CITATION_PATTERN.findall(reply)
        assert not citations, f"Internal citations found in Turn 1 reply: {citations}"

        # 6. Concise bubbles
        assert len(bubbles) <= 2, f"Expected <= 2 bubbles, got {len(bubbles)}: {bubbles}"

        # 7. Sales stage
        assert stage == "DISCOVERY", f"Expected stage DISCOVERY, got {stage}"


class TestTurn2ValueConsultationAndMedicalBoundary:
    """Probe Turn 2: 15-year acne + pitted scars."""

    def test_turn2_boundary_and_no_repeated_greeting(self):
        t1_user = "Cho mình hỏi về dịch vụ trị mụn bên bạn, chi phí như thế nào?"
        t1_asst = (
            "Dạ em chào Chị ạ! Chi phí các liệu trình chuẩn Y khoa bên em rất linh hoạt chỉ từ 650.000đ/buổi "
            "tùy tình trạng nền da, đã bao gồm Bác sĩ chuyên khoa soi da AI 3D miễn phí 100% và cam kết trọn gói không phát sinh phụ phí ạ.\n\n"
            "Hiện tại Chị đang muốn ưu tiên xử lý mụn, mờ nám tàn nhang hay dưỡng căng bóng trẻ hóa để em báo đúng gói hợp nhất cho mình ạ?"
        )
        history = [
            {"role": "user", "content": t1_user},
            {"role": "assistant", "content": t1_asst}
        ]

        t2_user = "T bị mụn từ lúc dậy thì, đến nay cũng 15 năm thi thaorng viêm nhẹ và sưng kèm sẹo rỗ"
        data, elapsed = call_fanpage_bot(t2_user, history=history, customer_id="challenger_t2_01")

        reply = data.get("reply_text", "")
        stage = data.get("sales_stage", "")

        # 1. Price 1.500.000đ present
        assert any(p in reply.lower() for p in ["1.500.000", "1.500k", "1tr5"]), (
            f"Expected 1.500.000đ for medical acne treatment in reply: {reply}"
        )

        # 2. Medical boundary: acknowledges scar boundary, no false cure claim
        assert "sẹo rỗ" in reply.lower(), f"Reply did not address pitted scars (sẹo rỗ): {reply}"
        assert "trị sạch sẹo rỗ" not in reply.lower() and "khỏi hẳn sẹo rỗ" not in reply.lower(), (
            f"Bot falsely claimed to completely cure pitted scars: {reply}"
        )

        # 3. No repeated greeting
        assert not reply.strip().startswith("Dạ em chào") and not reply.strip().startswith("Em chào"), (
            f"Bot repeated greeting on Turn 2: {reply[:40]}"
        )

        # 4. Zero citations
        citations = CITATION_PATTERN.findall(reply)
        assert not citations, f"Internal citations found in Turn 2 reply: {citations}"

        # 5. Zero Lan Anh
        assert not LAN_ANH_PATTERN.search(reply), f"Phantom name 'Lan Anh' found in Turn 2 reply: {reply}"

        # 6. Stage
        assert stage == "VALUE_CONSULTATION", f"Expected stage VALUE_CONSULTATION, got {stage}"


class TestTurn3MaleGenderVariations:
    """Probe Turn 3: Male gender variations must trigger 100% switch to 'Anh' with ZERO 'Chị'."""

    MALE_VARIATIONS = [
        ("mìn là nam", "Mìn là nam, không biết có thể dùng dịch vụ không? bao lâu thấy hiệu quẩ"),
        ("minh la nam", "minh la nam, bên mình có nhận khách nam không? liệu trình mấy buổi"),
        ("mình là con trai", "mình là con trai, có làm được không shop? bao lâu có kết quả"),
        ("mk là nam", "mk là nam, da dầu mụn có làm được không em? mấy tuần đỡ"),
    ]

    @pytest.mark.parametrize("label, user_msg", MALE_VARIATIONS)
    def test_male_variation_switch_to_anh_zero_chi(self, label, user_msg):
        history = [
            {"role": "user", "content": "Cho mình hỏi về dịch vụ trị mụn bên bạn, chi phí như thế nào?"},
            {"role": "assistant", "content": "Dạ em chào Chị ạ! Liệu trình chuẩn Y khoa bên em chỉ từ 650.000đ/buổi ạ."},
            {"role": "user", "content": "T bị mụn từ lúc dậy thì, đến nay cũng 15 năm thi thaorng viêm nhẹ và sưng kèm sẹo rỗ"},
            {"role": "assistant", "content": "Dạ phác đồ Trị Mụn Chuẩn Y Khoa bên em là 1.500.000đ/buổi, còn sẹo rỗ cần Bác sĩ soi da AI 3D trực tiếp ạ."}
        ]

        data, elapsed = call_fanpage_bot(user_msg, history=history, customer_id=f"male_eval_{label}")
        reply = data.get("reply_text", "")
        hooks = data.get("triggered_hooks", [])

        # 1. 100% Switch to "Anh"
        assert "Anh" in reply, f"Failed to address customer as 'Anh' for variation '{user_msg}': {reply}"

        # 2. ZERO "Chị" in reply
        chi_matches = CHI_PATTERN.findall(reply)
        assert not chi_matches, (
            f"CRITICAL VIOLATION: Found 'Chị' in reply to male customer ('{label}'): {chi_matches}\nFull reply: {reply}"
        )

        # 3. Confirms male suitability
        assert any(w in reply.lower() for w in ["nam", "nam giới", "con trai"]), (
            f"Did not confirm male suitability for '{label}': {reply}"
        )

        # 4. No repeated greeting
        assert not reply.strip().startswith("Dạ em chào") and not reply.strip().startswith("Em chào"), (
            f"Bot repeated greeting on Turn 3: {reply[:40]}"
        )

        # 5. Zero citations & Zero Lan Anh
        assert not CITATION_PATTERN.findall(reply), f"Citations found: {reply}"
        assert not LAN_ANH_PATTERN.search(reply), f"Lan Anh found: {reply}"


class TestTurn4PhoneLeadFastPath:
    """Probe Turn 4: Phone lead fast-path response, lead extraction, and pitch silencing."""

    PHONE_VARIATIONS = [
        ("0968221644 tư vấn mình thêm", "0968221644"),
        ("alo số của anh là 0912.345.678 nhé, gọi lại tư vấn", "0912345678"),
    ]

    @pytest.mark.parametrize("lead_msg, expected_phone", PHONE_VARIATIONS)
    def test_phone_lead_fast_path(self, lead_msg, expected_phone):
        history = [
            {"role": "user", "content": "Cho mình hỏi về dịch vụ trị mụn bên bạn, chi phí như thế nào?"},
            {"role": "assistant", "content": "Dạ em chào Chị ạ! Liệu trình chuẩn Y khoa bên em chỉ từ 650.000đ/buổi ạ."},
            {"role": "user", "content": "T bị mụn 15 năm kèm sẹo rỗ"},
            {"role": "assistant", "content": "Dạ gói Trị Mụn Chuẩn Y Khoa là 1.500.000đ/buổi ạ."},
            {"role": "user", "content": "Mìn là nam, không biết có thể dùng dịch vụ không?"},
            {"role": "assistant", "content": "Dạ phác đồ Trị Mụn hoàn toàn phù hợp với nền da Anh Nam giới ạ."}
        ]

        data, elapsed = call_fanpage_bot(lead_msg, history=history, customer_id=f"phone_eval_{expected_phone}")
        reply = data.get("reply_text", "")
        handoff = data.get("needs_human_handoff", False)
        category = data.get("handoff_category", "")
        reason = data.get("handoff_reason", "")
        lead = data.get("extracted_lead", {})

        # 1. Fast path response time (< 0.5s)
        # Note: network to localhost:8090 should complete in < 500ms when bypassing LLM
        assert elapsed < 0.5, f"Fast-path exceeded 0.5s: {elapsed:.3f}s"

        # 2. needs_human_handoff = True
        assert handoff is True, f"Expected needs_human_handoff = True, got {handoff}"

        # 3. Category & Reason
        assert category == "BOOKING_LEAD_CAPTURED", f"Expected BOOKING_LEAD_CAPTURED, got {category}"
        assert expected_phone in reason, f"Expected phone {expected_phone} in reason: {reason}"

        # 4. Lead extraction
        captured_phone = lead.get("phone_number")
        assert captured_phone == expected_phone, f"Expected lead phone {expected_phone}, got {captured_phone}"

        # 5. Addressed as "Anh", ZERO "Chị"
        assert "Anh" in reply, f"Reply did not address customer as 'Anh': {reply}"
        assert not CHI_PATTERN.search(reply), f"Found 'Chị' in reply: {reply}"

        # 6. Sales pitch silenced (no prices, no combo dumping)
        assert "1.500.000" not in reply and "650.000" not in reply, (
            f"Sales price repeated after phone lead: {reply}"
        )
        assert "combo" not in reply.lower(), f"Combo pitch repeated after phone lead: {reply}"


class TestBuyingTemperatureDecayAndTakeaway:
    """Probe Buying Temperature decay and takeaway response on brush-off."""

    def test_temperature_decay_on_brush_off(self):
        brush_off_msg = "ừ để chị xem thêm"
        history = [
            {"role": "user", "content": "Cho mình hỏi về dịch vụ trị mụn"},
            {"role": "assistant", "content": "Dạ em chào Chị ạ! Liệu trình Trị Mụn Chuẩn Y Khoa 1.500.000 VNĐ ạ."},
            {"role": "user", "content": "Giá này có được giảm không em?"},
            {"role": "assistant", "content": "Dạ nếu Chị đăng ký Combo Tiết Kiệm (3-4 buổi) bên em giảm 10% và tặng 1 buổi Detox Oxy ạ."}
        ]

        data, elapsed = call_fanpage_bot(brush_off_msg, history=history, customer_id="challenger_brush_01")
        insight = data.get("customer_insight", {})
        temp = insight.get("buying_temperature")
        delta = insight.get("temperature_delta")
        momentum = insight.get("momentum")
        strategy = insight.get("recommended_strategy", "")
        reply = data.get("reply_text", "")

        # 1. Delta is negative (temperature dropped)
        assert delta is not None and delta < 0, f"Expected negative temperature delta on brush-off, got {delta}"

        # 2. Momentum reflects cooling
        assert momentum == "COOLING_HALF_HEARTED", f"Expected COOLING_HALF_HEARTED momentum, got {momentum}"

        # 3. Takeaway / Zero-pressure response
        # Bot should not push or repeat hard sell; should allow space or hold slot without obligation
        assert any(w in reply.lower() for w in ["thoải mái", "không giục", "xem thêm", "giữ suất", "không ràng buộc"]), (
            f"Bot failed to use zero-pressure takeaway phrasing: {reply}"
        )

    def test_idle_time_decay_and_reengage(self):
        url = f"{API_URL}/api/bot/fanpage/reengage"
        payload = {
            "page_id": PAGE_ID,
            "customer_id": "challenger_idle_01",
            "idle_seconds": 300,
            "history": [
                {"role": "user", "content": "Tư vấn cho mình gói trị mụn"},
                {"role": "assistant", "content": "Dạ liệu trình trị mụn 1.500.000 VNĐ ạ. Chị đang gặp mụn viêm hay mụn ẩn ạ?"}
            ]
        }
        res = requests.post(url, json=payload, timeout=90)
        assert res.status_code == 200, f"HTTP {res.status_code}: {res.text}"
        data = res.json()

        insight = data.get("customer_insight", {})
        delta = insight.get("temperature_delta")
        momentum = insight.get("momentum")
        reply = data.get("reply_text", "")

        # 1. Delta is negative
        assert delta is not None and delta < 0, f"Expected negative delta for 300s idle, got {delta}"

        # 2. Momentum COOLING_IDLE
        assert momentum == "COOLING_IDLE", f"Expected COOLING_IDLE momentum, got {momentum}"

        # 3. Proactive message generated
        assert len(reply) > 20, f"Expected substantial proactive re-engagement message, got: '{reply}'"
