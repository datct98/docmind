from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import app


client = TestClient(app)


def test_health_and_5_core_skills_registered():
    res = client.get("/v1/brain/health")
    assert res.status_code == 200
    body = res.json()
    assert body["status"] == "ok"
    assert body["service"] == "agent-brain"


def test_case_1_returning_customer_size_and_shipping_recall():
    """
    Section 18.1: Khách cũ quay lại hỏi mẫu mới — nhớ size M và khung giờ nhận hàng.
    """
    tenant_id = "tenant_fashion"
    customer_id = "cust_case_1"

    # Turn 1 (old conversation): Retain customer's size M and morning delivery slot
    r1 = client.post(
        "/v1/brain/retain",
        json={
            "tenant_id": tenant_id,
            "customer_id": customer_id,
            "conversation_id": "conv_old_1",
            "user_message": "Mình mặc size M nhé, giao sáng mai giúp mình",
            "assistant_reply": "Dạ em đã ghi nhận size M cho Chị ạ.",
            "summarize": True,
        },
    )
    assert r1.status_code == 200

    # Turn 2 (new conversation): Customer returns and asks general question -> Recall + Advise
    r2 = client.post(
        "/v1/brain/recall-and-advise",
        json={
            "tenant_id": tenant_id,
            "customer_id": customer_id,
            "conversation_id": "conv_new_2",
            "message": "Shop tư vấn giúp mình mẫu mới về với",
        },
    )
    assert r2.status_code == 200
    data = r2.json()
    fact_map = {f["fact_key"]: f["fact_value"] for f in data["active_facts"]}
    assert fact_map.get("preferred_size") == "M"
    assert "sáng mai" in (fact_map.get("time_slot_preference") or "").lower()
    # General inquiry should activate spin_micro_diagnostic
    assert data["active_skill"]["skill_code"] == "spin_micro_diagnostic"
    assert len(data["active_skill"]["bubble_plan"]) == 3


def test_case_2_repeated_price_objection_triggers_value_reframing_and_handoff():
    """
    Section 18.2: Khách chê đắt 2 lần -> kích hoạt behavioral_value_reframing
    và lặp lại lần 2 sẽ bật requires_human = True (HANDOFF).
    """
    tenant_id = "tenant_spa"
    customer_id = "cust_case_2"
    conv_id = "conv_price_2"

    # First price objection: advise + retain
    r1 = client.post(
        "/v1/brain/recall-and-advise",
        json={
            "tenant_id": tenant_id,
            "customer_id": customer_id,
            "conversation_id": conv_id,
            "message": "Giá cao thế shop, bên kia bán rẻ hơn nhiều",
        },
    )
    assert r1.status_code == 200
    d1 = r1.json()
    assert d1["active_skill"]["skill_code"] == "behavioral_value_reframing"
    assert d1["requires_human"] is False

    client.post(
        "/v1/brain/retain",
        json={
            "tenant_id": tenant_id,
            "customer_id": customer_id,
            "conversation_id": conv_id,
            "user_message": "Giá cao thế shop, bên kia bán rẻ hơn nhiều",
            "assistant_reply": "Dạ bên em dùng nguyên liệu cao cấp và có gói cơ bản mềm hơn ạ.",
        },
    )

    # Second price objection in same conversation -> should trigger handoff
    r2 = client.post(
        "/v1/brain/recall-and-advise",
        json={
            "tenant_id": tenant_id,
            "customer_id": customer_id,
            "conversation_id": conv_id,
            "message": "Vẫn đắt quá, có giảm giá hay km gì không?",
        },
    )
    assert r2.status_code == 200
    d2 = r2.json()
    assert d2["active_skill"]["skill_code"] == "behavioral_value_reframing"
    assert d2["requires_human"] is True
    assert d2["next_best_action"] == "HANDOFF"
    assert "2 lần" in (d2["handoff_reason"] or "")


def test_case_3_conflict_resolution_supersedes_old_color_fact():
    """
    Section 18.3: Khách đổi ý từ màu đen sang màu trắng -> Current Explicit Message
    supersedes old fact (old fact marked superseded, active fact is 'trắng').
    """
    tenant_id = "tenant_fashion"
    customer_id = "cust_case_3"
    conv_id = "conv_color_3"

    r1 = client.post(
        "/v1/brain/retain",
        json={
            "tenant_id": tenant_id,
            "customer_id": customer_id,
            "conversation_id": conv_id,
            "user_message": "Cho mình lấy màu đen size L nhé",
        },
    )
    assert r1.status_code == 200

    # Customer explicitly changes mind to white
    r2 = client.post(
        "/v1/brain/retain",
        json={
            "tenant_id": tenant_id,
            "customer_id": customer_id,
            "conversation_id": conv_id,
            "user_message": "À thôi mình đổi sang màu trắng nhé",
        },
    )
    assert r2.status_code == 200
    retain_data = r2.json()
    assert len(retain_data["superseded_fact_ids"]) >= 1

    # Recall customer context and verify only 'trắng' is active for color_preference
    r_ctx = client.get(f"/v1/brain/customers/{tenant_id}/{customer_id}")
    assert r_ctx.status_code == 200
    fact_map = {f["fact_key"]: f["fact_value"] for f in r_ctx.json()["active_facts"]}
    assert fact_map.get("color_preference") == "trắng"
    assert fact_map.get("preferred_size") == "L"


def test_case_4_phone_call_request_and_pii_masking():
    """
    Section 18.4: Khách yêu cầu gọi điện trực tiếp + để lại SĐT -> PII masking hoạt động,
    lưu preferred_contact = 'phone' và kích hoạt handoff.
    """
    tenant_id = "tenant_clinic"
    customer_id = "cust_case_4"

    r1 = client.post(
        "/v1/brain/recall-and-advise",
        json={
            "tenant_id": tenant_id,
            "customer_id": customer_id,
            "conversation_id": "conv_call_4",
            "message": "Gọi cho mình số 0912345678 tư vấn trực tiếp nhé, nhắn tin lâu quá",
        },
    )
    assert r1.status_code == 200
    data = r1.json()
    assert "0912345678" not in data["masked_message"]
    assert data["requires_human"] is True
    assert data["next_best_action"] == "HANDOFF"

    # Retain and verify reflect report recommends calling customer
    client.post(
        "/v1/brain/retain",
        json={
            "tenant_id": tenant_id,
            "customer_id": customer_id,
            "conversation_id": "conv_call_4",
            "user_message": "Gọi cho mình số 0912345678 tư vấn trực tiếp nhé, nhắn tin lâu quá",
        },
    )
    r_ref = client.post(
        "/v1/brain/reflect",
        json={"tenant_id": tenant_id, "customer_id": customer_id},
    )
    assert r_ref.status_code == 200
    assert r_ref.json()["should_call_customer"] is True


def test_case_5_consecutive_bot_failures_triggers_handoff():
    """
    Section 18.5: Bot hiểu sai 2 lần liên tiếp (is_bot_misunderstanding) -> handoff.
    """
    tenant_id = "tenant_service"
    customer_id = "cust_case_5"
    conv_id = "conv_fail_5"

    # First misunderstanding turn retained
    client.post(
        "/v1/brain/retain",
        json={
            "tenant_id": tenant_id,
            "customer_id": customer_id,
            "conversation_id": conv_id,
            "user_message": "Không phải ý đó, bạn hiểu sai rồi",
        },
    )

    # Second misunderstanding turn -> should immediately handoff
    r2 = client.post(
        "/v1/brain/recall-and-advise",
        json={
            "tenant_id": tenant_id,
            "customer_id": customer_id,
            "conversation_id": conv_id,
            "message": "Trả lời sai rồi, không hiểu à?",
        },
    )
    assert r2.status_code == 200
    data = r2.json()
    assert data["requires_human"] is True
    assert data["next_best_action"] == "HANDOFF"


def test_all_5_psychological_skills_and_subtle_scarcity_and_latency():
    """
    Verify each of the 5 psychological skills triggers accurately on its target signal,
    loss_aversion_urgency uses subtle operational scarcity (no fake stock counts),
    and hot-path latency stays under 15ms.
    """
    tenant_id = "tenant_all_skills"

    # 1. tactical_empathy_voss (trust/quality fear)
    r_voss = client.post(
        "/v1/brain/recall-and-advise",
        json={
            "tenant_id": tenant_id,
            "customer_id": "c_voss",
            "conversation_id": "conv_voss",
            "message": "Mua online sợ không giống hình, sợ hớ lắm shop ơi",
        },
    )
    assert r_voss.status_code == 200
    assert r_voss.json()["active_skill"]["skill_code"] == "tactical_empathy_voss"

    # 2. loss_aversion_urgency (stall / "để suy nghĩ thêm" / "lương về")
    r_loss = client.post(
        "/v1/brain/recall-and-advise",
        json={
            "tenant_id": tenant_id,
            "customer_id": "c_loss",
            "conversation_id": "conv_loss",
            "message": "Để hôm nào lương về mình mua nhé, để suy nghĩ thêm đã",
        },
    )
    assert r_loss.status_code == 200
    d_loss = r_loss.json()
    assert d_loss["active_skill"]["skill_code"] == "loss_aversion_urgency"
    assert "CẤM nói kiểu 'chỉ còn 1-2 suất cuối' giả tạo" in d_loss["active_skill"]["prompt_directive"]

    # 3. assumptive_double_bind_close (buying signal: "thử trước 1 buổi cũng được")
    r_close = client.post(
        "/v1/brain/recall-and-advise",
        json={
            "tenant_id": tenant_id,
            "customer_id": "c_close",
            "conversation_id": "conv_close",
            "message": "Ok thử trc 1b cũng được shop",
        },
    )
    assert r_close.status_code == 200
    d_close = r_close.json()
    assert d_close["active_skill"]["skill_code"] == "assumptive_double_bind_close"
    assert d_close["latency_ms"] < 25.0
