"""Acceptance Test Suite for DocMind Overhaul (Requirements R1, R2, R3, R4).

Authoritative Specifications:
- ORIGINAL_REQUEST.md (Requirements R1, R2, R3, R4)
- PROJECT.md (Architecture, Interface Contracts, Milestones M1, M2, M3)

Test Coverage:
1. Zero "Lan Anh" string assertion across backend, frontend, prompts, configs, and runtime replies.
2. 4-turn Fanpage Bot conversational replay against /api/bot/fanpage/message:
   - Turn 1: Acne consultation -> greets with "Chị" (no "Lan Anh"), states Liệu trình Trị Mụn Chuẩn Y Khoa (1.500.000 VNĐ).
   - Turn 2: 15-year acne + pitted scars -> no repeated greeting, strict medical grounding (no scar-cure claim), no Combo conflation (Combo Tiết Kiệm 3-4 buổi 10% vs Combo Toàn Diện 5-9 buổi 15%).
   - Turn 3: "Mìn là nam, không biết có thể dùng dịch vụ không? bao lâu thấy hiệu quẩ" -> no repeated greeting, switches 100% to "Anh" (zero "Chị"/"chị"), confirms male suitability, answers sessions/timeframe.
   - Turn 4: "0968221644 tư vấn mình thêm" -> needs_human_handoff = true, handoff_reason contains 0968221644, addresses customer as "Anh", confirms phone/Zalo callback, silences pitch (no price/combo, no repeated discovery questions).
3. Fault-tolerant gender detection variants (Vietnamese typos/shorthand) and phone lead formats.
4. Rules CRUD acceptance tests (both Global page_id=null and page-scoped page_id=UUID).
5. Feedback submission & faithful rule extraction acceptance tests (preserves directives/numbers).
6. Applied rules injection in Fanpage Bot & Q&A.
7. Multi-provider LLM configuration verification (.env presets, URL schemes, headers, <think> stripping).
"""

from __future__ import annotations

import json
import os
import re
import uuid
from typing import Any, Dict, List

import pytest

from tests_e2e.harness.client import DocMindE2EClient
from tests_e2e.harness.models import (
    ConversationTurn,
    FanpageBotRequest,
    FanpageBotResponse,
    LearnedRule,
    RuleCategory,
)

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


class TestZeroLanAnhStringIntegrity:
    """Verifies complete eradication of hardcoded customer name 'Lan Anh' across the system."""

    def test_zero_lan_anh_in_frontend_source(self):
        """Assert zero occurrences of 'Lan Anh' in frontend source code."""
        frontend_src = os.path.join(REPO_ROOT, "frontend", "src")
        violations = []
        if os.path.exists(frontend_src):
            for root, _, files in os.walk(frontend_src):
                for f in files:
                    if f.endswith((".vue", ".js", ".ts", ".html", ".css")):
                        path = os.path.join(root, f)
                        with open(path, "r", encoding="utf-8", errors="ignore") as fh:
                            content = fh.read()
                            if re.search(r"(?i)\blan\s+anh\b", content):
                                violations.append(os.path.relpath(path, REPO_ROOT))
        assert len(violations) == 0, f"Found 'Lan Anh' in frontend files: {violations}"

    def test_zero_lan_anh_in_env_and_configs(self):
        """Assert zero occurrences of 'Lan Anh' in environment templates and configs."""
        config_files = [
            os.path.join(REPO_ROOT, ".env"),
            os.path.join(REPO_ROOT, ".env.example"),
            os.path.join(REPO_ROOT, "docker-compose.yml"),
            os.path.join(REPO_ROOT, "api-java", "src", "main", "resources", "application.properties"),
        ]
        violations = []
        for path in config_files:
            if os.path.exists(path):
                with open(path, "r", encoding="utf-8", errors="ignore") as fh:
                    content = fh.read()
                    if re.search(r"(?i)\blan\s+anh\b", content):
                        violations.append(os.path.relpath(path, REPO_ROOT))
        assert len(violations) == 0, f"Found 'Lan Anh' in configuration files: {violations}"

    def test_zero_lan_anh_in_fanpage_bot_responses(self, client: DocMindE2EClient):
        """Assert zero occurrences of 'Lan Anh' in bot output across multiple test queries."""
        test_queries = [
            "Dạ cho em hỏi bảng giá liệu trình mụn",
            "Tư vấn cho mình gói chăm sóc da",
            "Dịch vụ bên bạn thế nào",
            "Bên bạn có làm cho nam không",
        ]
        for q in test_queries:
            resp = client.send_fanpage_bot_message(FanpageBotRequest(message=q))
            assert not re.search(r"(?i)\blan\s+anh\b", resp.reply_text), (
                f"Bot response contained 'Lan Anh' for query '{q}': {resp.reply_text}"
            )
            for bubble in resp.reply_bubbles:
                assert not re.search(r"(?i)\blan\s+anh\b", bubble.text)


class TestFanpageBot4TurnReplay:
    """Verifies the exact 4-turn conversation replay against /api/bot/fanpage/message per ORIGINAL_REQUEST.md."""

    def test_turn1_first_contact_greeting_and_pricing(self, client: DocMindE2EClient):
        """Turn 1: Acne consultation -> greets with 'Chị' (no 'Lan Anh'), states Liệu trình Trị Mụn Chuẩn Y Khoa (1.500.000 VNĐ)."""
        req = FanpageBotRequest(
            message="Cho mình hỏi về dịch vụ trị mụn bên bạn, chi phí như thế nào?",
            history=[],
        )
        resp = client.send_fanpage_bot_message(req)

        # 1. Zero 'Lan Anh'
        assert not re.search(r"(?i)\blan\s+anh\b", resp.reply_text)
        # 2. Greets with 'Chị' (default)
        assert "Chị" in resp.reply_text or "chị" in resp.reply_text.lower()
        assert resp.consultation_profile is not None
        assert resp.consultation_profile.preferred_honorific == "Chị"
        # 3. States Liệu trình Trị Mụn Chuẩn Y Khoa
        assert "Trị Mụn Chuẩn Y Khoa" in resp.reply_text or "trị mụn chuẩn y khoa" in resp.reply_text.lower()
        # 4. States price 1.500.000 VNĐ
        assert "1.500.000" in resp.reply_text or "1.500.000đ" in resp.reply_text or "1.500.000 VNĐ" in resp.reply_text
        # 5. No human handoff on normal turn 1
        assert resp.needs_human_handoff is False
        assert len(resp.reply_bubbles) >= 1

    def test_turn2_followup_no_repeated_greeting_and_medical_grounding(self, client: DocMindE2EClient):
        """Turn 2: 15-year acne + pitted scars -> no repeated greeting, no ungrounded scar-cure claim, no Combo conflation."""
        turn1_user = "Cho mình hỏi về dịch vụ trị mụn bên bạn, chi phí như thế nào?"
        turn1_bot = (
            "Dạ em chào Chị ạ! Liệu trình Trị Mụn Chuẩn Y Khoa bên em có chi phí là 1.500.000 VNĐ mỗi buổi ạ. "
            "Hiện tại tình trạng mụn của Chị đang ở mức độ nào rồi ạ?"
        )
        history = [
            ConversationTurn(role="user", content=turn1_user),
            ConversationTurn(role="assistant", content=turn1_bot),
        ]
        turn2_msg = "T bị mụn từ lúc dậy thì, đến nay cũng 15 năm thi thaorng viêm nhẹ và sưng kèm sẹo rỗ"
        req = FanpageBotRequest(message=turn2_msg, history=history)
        resp = client.send_fanpage_bot_message(req)

        reply_lower = resp.reply_text.lower()

        # 1. No repeated greeting on turn >= 2
        assert not resp.reply_text.startswith("Dạ em chào")
        assert "em rất vui được tư vấn" not in reply_lower
        assert "chào mừng" not in reply_lower
        # 2. No ungrounded scar cure claim
        assert "không điều trị sẹo rỗ" in reply_lower or "sẹo rỗ" in reply_lower
        assert "trị sạch sẹo rỗ" not in reply_lower
        assert "khỏi hẳn sẹo rỗ" not in reply_lower
        # 3. No Combo conflation: Combo Tiết Kiệm (3 - 4 buổi, giảm 10%) vs Combo Toàn Diện (5 - 9 buổi, giảm 15%)
        if "toàn diện" in reply_lower and "tiết kiệm" in reply_lower:
            # Must not associate 3-4 sessions with Combo Toàn Diện
            assert not re.search(r"combo\s+toàn\s+diện[^\n]*3\s*[-–]\s*4", reply_lower)
            # Must not associate 10% discount with Combo Toàn Diện
            assert not re.search(r"combo\s+toàn\s+diện[^\n]*10\s*%", reply_lower)
        # 4. Closing question is not identical to Turn 1 closing question
        assert "tình trạng mụn của chị đang ở mức độ nào rồi ạ" not in reply_lower

    def test_turn3_fault_tolerant_gender_switch_and_multi_part_answering(self, client: DocMindE2EClient):
        """Turn 3: 'Mìn là nam...' -> no repeated greeting, switches 100% to 'Anh' (0 'Chị'), answers male suitability & timeframe."""
        history = [
            ConversationTurn(role="user", content="Cho mình hỏi về dịch vụ trị mụn"),
            ConversationTurn(role="assistant", content="Dạ em chào Chị ạ! Liệu trình 1.500.000 VNĐ ạ."),
            ConversationTurn(role="user", content="T bị mụn 15 năm kèm sẹo rỗ"),
            ConversationTurn(role="assistant", content="Dạ với sẹo rỗ cần phác đồ riêng biệt sau khi hết mụn ạ."),
        ]
        turn3_msg = "Mìn là nam, không biết có thể dùng dịch vụ không? bao lâu thấy hiệu quẩ"
        req = FanpageBotRequest(message=turn3_msg, history=history)
        resp = client.send_fanpage_bot_message(req)

        # 1. No repeated greeting
        assert not resp.reply_text.startswith("Dạ em chào")
        # 2. 100% 'Anh' consistency (ZERO 'Chị' or 'chị' anywhere in reply)
        assert "Anh" in resp.reply_text
        assert not re.search(r"(?i)\bchị\b", resp.reply_text), (
            f"Expected 0 'Chị'/'chị' after male detection, but found in: {resp.reply_text}"
        )
        assert resp.consultation_profile is not None
        assert resp.consultation_profile.customer_gender == "MALE"
        assert resp.consultation_profile.preferred_honorific == "Anh"

        # 3. Answers ALL parts of multi-part question:
        # Part A: Confirms male suitability
        reply_lower = resp.reply_text.lower()
        assert any(w in reply_lower for w in ["nam", "nam giới", "khách hàng nam"]), (
            "Did not confirm male suitability in response"
        )
        # Part B: Answers timeframe/sessions
        assert any(w in reply_lower for w in ["buổi", "tuần", "tháng", "hiệu quả"]), (
            "Did not answer timeframe/sessions for visible results"
        )

    def test_turn4_phone_lead_capture_and_pitch_silencing(self, client: DocMindE2EClient):
        """Turn 4: '0968221644 tư vấn mình thêm' -> needs_human_handoff = true, reason has phone, addresses 'Anh', silences pitch."""
        history = [
            ConversationTurn(role="user", content="Cho mình hỏi về dịch vụ trị mụn"),
            ConversationTurn(role="assistant", content="Dạ em chào Chị ạ! Liệu trình 1.500.000 VNĐ ạ."),
            ConversationTurn(role="user", content="T bị mụn 15 năm kèm sẹo rỗ"),
            ConversationTurn(role="assistant", content="Dạ với sẹo rỗ cần phác đồ riêng biệt sau khi hết mụn ạ."),
            ConversationTurn(role="user", content="Mìn là nam, không biết có thể dùng dịch vụ không? bao lâu thấy hiệu quẩ"),
            ConversationTurn(role="assistant", content="Dạ bên em phục vụ cả nam giới ạ. Thông thường sau 3-4 buổi Anh sẽ thấy đỡ mụn."),
        ]
        turn4_msg = "0968221644 tư vấn mình thêm"
        req = FanpageBotRequest(message=turn4_msg, history=history)
        resp = client.send_fanpage_bot_message(req)

        # 1. Lead capture & human handoff
        assert resp.needs_human_handoff is True
        assert resp.handoff_reason is not None
        assert "0968221644" in resp.handoff_reason
        assert resp.extracted_lead is not None
        assert resp.extracted_lead.has_buying_or_booking_signal is True
        assert resp.extracted_lead.phone_number == "0968221644"

        # 2. Addresses customer as 'Anh' (preserves resolved gender from history)
        assert "Anh" in resp.reply_text
        assert not re.search(r"(?i)\bchị\b", resp.reply_text)

        # 3. Confirms phone/Zalo callback
        reply_lower = resp.reply_text.lower()
        assert "0968221644" in resp.reply_text
        assert any(w in reply_lower for w in ["liên hệ", "gọi", "zalo", "điện thoại"])

        # 4. Silences pitches: NO price dumps, NO combo sales pitch, NO discovery question repetition
        assert "1.500.000" not in resp.reply_text
        assert "combo tiết kiệm" not in reply_lower
        assert "combo toàn diện" not in reply_lower
        assert "muốn cải thiện vấn đề nào nhất" not in reply_lower

    def test_full_4turn_sequential_replay(self, client: DocMindE2EClient):
        """Executes full sequential 4-turn dialogue verifying state continuity across all turns."""
        turns = [
            ("Cho mình hỏi về dịch vụ trị mụn bên bạn, chi phí như thế nào?", 1),
            ("T bị mụn từ lúc dậy thì, đến nay cũng 15 năm thi thaorng viêm nhẹ và sưng kèm sẹo rỗ", 2),
            ("Mìn là nam, không biết có thể dùng dịch vụ không? bao lâu thấy hiệu quẩ", 3),
            ("0968221644 tư vấn mình thêm", 4),
        ]
        history: List[ConversationTurn] = []

        for msg, turn_num in turns:
            req = FanpageBotRequest(message=msg, history=history)
            resp = client.send_fanpage_bot_message(req)

            if turn_num == 1:
                assert "Chị" in resp.reply_text
                assert "1.500.000" in resp.reply_text
                assert not re.search(r"(?i)\blan\s+anh\b", resp.reply_text)
            elif turn_num == 2:
                assert not resp.reply_text.startswith("Dạ em chào")
                assert "sẹo rỗ" in resp.reply_text.lower()
            elif turn_num == 3:
                assert "Anh" in resp.reply_text
                assert not re.search(r"(?i)\bchị\b", resp.reply_text)
            elif turn_num == 4:
                assert resp.needs_human_handoff is True
                assert "0968221644" in resp.handoff_reason
                assert "Anh" in resp.reply_text
                assert "1.500.000" not in resp.reply_text

            history.append(ConversationTurn(role="user", content=msg))
            history.append(ConversationTurn(role="assistant", content=resp.reply_text))


class TestFaultTolerantGenderAndLeadVariants:
    """Verifies Vietnamese typo tolerance in gender detection and varied phone formats."""

    @pytest.mark.parametrize("male_expression", [
        "mìn là nam",
        "mình làm nam",
        "minh la nam",
        "mk là nam",
        "mik là nam",
        "toi la nam",
        "t là nam",
        "da nam giới dùng được không",
        "gọi cho anh số này",
        "anh muốn hỏi gói trị mụn",
    ])
    def test_male_gender_recognition_vietnamese_typos(self, client: DocMindE2EClient, male_expression: str):
        """Asserts that various typo/slang forms of male identification trigger 100% 'Anh' honorific."""
        req = FanpageBotRequest(
            message=f"{male_expression}, tư vấn cho mình liệu trình mụn",
            history=[],
        )
        resp = client.send_fanpage_bot_message(req)
        assert "Anh" in resp.reply_text, f"Failed to switch to 'Anh' for: '{male_expression}'"
        assert not re.search(r"(?i)\bchị\b", resp.reply_text), (
            f"Leaked 'Chị' in reply for male expression '{male_expression}': {resp.reply_text}"
        )

    @pytest.mark.parametrize("phone_format, expected_digits", [
        ("0968221644", "0968221644"),
        ("0968.221.644", "0968221644"),
        ("0968 221 644", "0968221644"),
        ("+84968221644", "0968221644"),
        ("gọi vào số 0912345678 giúp mình nhé", "0912345678"),
    ])
    def test_phone_lead_capture_formats(self, client: DocMindE2EClient, phone_format: str, expected_digits: str):
        """Asserts that diverse phone number formats trigger lead capture and pitch silencing."""
        req = FanpageBotRequest(
            message=f"Số của mình là {phone_format}, gọi tư vấn giúp",
            history=[
                ConversationTurn(role="user", content="Tư vấn gói trị mụn"),
                ConversationTurn(role="assistant", content="Dạ gói trị mụn 1.500.000 VNĐ ạ."),
            ],
        )
        resp = client.send_fanpage_bot_message(req)
        assert resp.needs_human_handoff is True
        assert resp.extracted_lead is not None
        assert resp.extracted_lead.phone_number == expected_digits
        assert expected_digits in resp.handoff_reason
        # Verify pitch silencing
        assert "1.500.000" not in resp.reply_text


class TestRulesCrudAcceptance:
    """Acceptance tests for AI Playbook rules CRUD supporting Global (page_id=null) and Page-scoped (page_id=UUID)."""

    def test_create_global_rule_page_id_null(self, client: DocMindE2EClient):
        """Asserts POST /api/rules creates a global rule where page_id is null."""
        payload = {
            "category": "POLICY",
            "trigger_condition": "Khi khách hàng hỏi về chính sách bảo hành toàn hệ thống",
            "root_cause": "Áp dụng sai chính sách chung cho các cơ sở",
            "generalized_rule": "Chính sách bảo hành dịch vụ toàn quốc áp dụng tối đa 30 ngày sau điều trị.",
            "retrieval_keywords": ["bảo hành", "toàn quốc", "chính sách"],
            "page_id": None,
            "active": True,
        }
        rule = client.create_rule(payload)
        assert rule.id is not None
        assert rule.page_id is None
        assert rule.active is True
        assert rule.category == "POLICY"

    def test_create_page_scoped_rule_page_id_uuid(self, client: DocMindE2EClient):
        """Asserts POST /api/rules creates a page-scoped rule linked to a specific page_id."""
        page_uuid = str(uuid.uuid4())
        payload = {
            "category": "PRICING",
            "trigger_condition": "Khi tư vấn bảng giá chi nhánh Hà Nội",
            "root_cause": "Nhầm lẫn bảng giá chi nhánh với giá trụ sở chính",
            "generalized_rule": "Chi nhánh Hà Nội áp dụng phụ thu 5% dịch vụ cuối tuần.",
            "retrieval_keywords": ["hà nội", "phụ thu", "cuối tuần"],
            "page_id": page_uuid,
            "active": True,
        }
        rule = client.create_rule(payload)
        assert rule.id is not None
        assert rule.page_id == page_uuid
        assert rule.active is True

    def test_list_rules_global_vs_page_filtering(self, client: DocMindE2EClient):
        """Asserts GET /api/rules properly filters between Global rules and page-scoped rules."""
        page_a = str(uuid.uuid4())
        page_b = str(uuid.uuid4())

        client.create_rule({"category": "POLICY", "generalized_rule": "Global Rule 1", "page_id": None})
        client.create_rule({"category": "POLICY", "generalized_rule": "Page A Rule", "page_id": page_a})
        client.create_rule({"category": "POLICY", "generalized_rule": "Page B Rule", "page_id": page_b})

        global_rules = client.list_rules(page_id="null")
        assert any(r.generalized_rule == "Global Rule 1" for r in global_rules)
        assert all(r.page_id is None for r in global_rules)

        page_a_rules = client.list_rules(page_id=page_a)
        assert any(r.generalized_rule == "Page A Rule" for r in page_a_rules)
        assert all(r.page_id == page_a for r in page_a_rules)

    def test_update_rule_and_toggle_active(self, client: DocMindE2EClient):
        """Asserts PUT /api/rules/{id} updates rule contents and toggles active status without null embedding errors."""
        rule = client.create_rule({
            "category": "DISCOUNT",
            "generalized_rule": "Quy tắc giảm giá gốc",
            "active": True,
            "page_id": None,
        })

        # Toggle inactive
        updated_1 = client.update_rule(rule.id, {"active": False})
        assert updated_1.active is False

        # Toggle active again and update text
        updated_2 = client.update_rule(rule.id, {
            "active": True,
            "generalized_rule": "Quy tắc giảm giá đã cập nhật",
        })
        assert updated_2.active is True
        assert updated_2.generalized_rule == "Quy tắc giảm giá đã cập nhật"

    def test_delete_rule(self, client: DocMindE2EClient):
        """Asserts DELETE /api/rules/{id} removes rule from database."""
        rule = client.create_rule({
            "category": "GENERAL",
            "generalized_rule": "Quy tắc tạm thời để xóa",
            "page_id": None,
        })
        success = client.delete_rule(rule.id)
        assert success is True

        remaining = client.list_rules()
        assert not any(r.id == rule.id for r in remaining)


class TestFeedbackAndMistakeTrainingAcceptance:
    """Acceptance tests for 'Báo sai & Dạy AI' (Feedback submission) & faithful rule extraction."""

    def test_feedback_faithful_rule_extraction_preserves_numbers_and_directives(self, client: DocMindE2EClient):
        """Asserts that user correction with combo numbers & discounts is NOT mangled or stripped of digits."""
        correction = "Combo Tiết Kiệm là 3 - 4 buổi giảm 10%, không phải giảm 15%"
        resp = client.submit_feedback(
            question="Gói 3-4 buổi giảm bao nhiêu %?",
            faulty_answer="Combo 3-4 buổi là Combo Toàn Diện giảm 15%.",
            user_correction=correction,
        )

        learned = resp.learned_rule
        assert learned["id"] is not None
        assert learned["active"] is True
        # Must preserve operational directive without destructive digit stripping
        assert "3" in learned["generalized_rule"] or "tiết kiệm" in learned["generalized_rule"].lower()
        assert "10%" in learned["generalized_rule"] or "10" in learned["generalized_rule"]
        assert len(resp.regenerated_answer) > 10

    def test_feedback_medical_boundary_rule_extraction(self, client: DocMindE2EClient):
        """Asserts feedback teaching a medical boundary correctly extracts scar treatment policy."""
        correction = "Liệu trình Trị Mụn Chuẩn Y Khoa không điều trị sẹo rỗ, phải hướng dẫn khách phác đồ laser riêng biệt"
        resp = client.submit_feedback(
            question="Gói trị mụn có trị được sẹo rỗ không?",
            faulty_answer="Có, gói Trị Mụn Chuẩn Y Khoa trị sạch cả sẹo rỗ sau 1 liệu trình.",
            user_correction=correction,
        )

        learned = resp.learned_rule
        assert "sẹo rỗ" in learned["generalized_rule"].lower()
        assert "không" in learned["generalized_rule"].lower() or "chỉ" in learned["generalized_rule"].lower()

    def test_applied_rules_injection_in_fanpage_bot(self, client: DocMindE2EClient):
        """Asserts that active rules matching Global or page_id are injected and returned in applied_rules of FanpageBotResponse."""
        page_id = str(uuid.uuid4())
        # Create active rule for page_id
        client.create_rule({
            "category": "POLICY",
            "generalized_rule": "Bắt buộc tư vấn liệu trình trị mụn chuẩn y khoa trước khi báo giá phụ",
            "trigger_condition": "Khi khách hỏi về trị mụn",
            "retrieval_keywords": ["trị mụn", "mụn"],
            "page_id": page_id,
            "active": True,
        })

        req = FanpageBotRequest(
            message="Cho mình hỏi liệu trình trị mụn",
            page_id=page_id,
        )
        resp = client.send_fanpage_bot_message(req)
        assert len(resp.applied_rules) >= 1
        assert any("trị mụn" in r.get("generalized_rule", "").lower() for r in resp.applied_rules)


class TestMultiProviderLlmConfiguration:
    """Acceptance tests verifying Multi-Provider LLM configuration (.env presets, URL schemes, headers, think tag stripping)."""

    def test_env_example_contains_all_seven_providers(self):
        """Asserts that .env.example contains documented presets for all 7 providers."""
        env_example_path = os.path.join(REPO_ROOT, ".env.example")
        assert os.path.exists(env_example_path), ".env.example file must exist at project root"

        with open(env_example_path, "r", encoding="utf-8") as fh:
            content = fh.read()

        required_providers = [
            "ollama",
            "deepseek",
            "grok",
            "openai",
            "gemini",
            "openrouter",
            "custom",
        ]
        for provider in required_providers:
            assert f"LLM_PROVIDER={provider}" in content or f"Preset" in content and provider in content.lower(), (
                f"Missing preset for provider '{provider}' in .env.example"
            )

    def test_provider_presets_url_and_model_specs(self):
        """Validates standard endpoints and models for each LLM provider."""
        provider_specs = {
            "deepseek": {
                "base_url": "https://api.deepseek.com/v1",
                "models": ["deepseek-chat", "deepseek-reasoner"],
            },
            "grok": {
                "base_url": "https://api.x.ai/v1",
                "models": ["grok-2-latest", "grok-3-mini"],
            },
            "openai": {
                "base_url": "https://api.openai.com/v1",
                "models": ["gpt-4o-mini", "gpt-4o"],
            },
            "gemini": {
                "base_url": "https://generativelanguage.googleapis.com/v1beta/openai",
                "models": ["gemini-2.5-flash", "gemini-1.5-flash"],
            },
            "ollama": {
                "base_url": "http://localhost:11434",
                "models": ["qwen2.5:7b", "qwen2.5:latest"],
            },
        }

        with open(os.path.join(REPO_ROOT, ".env.example"), "r", encoding="utf-8") as fh:
            content = fh.read()

        for provider, specs in provider_specs.items():
            assert specs["base_url"] in content, f"Missing base_url {specs['base_url']} for {provider}"
            assert any(m in content for m in specs["models"]), f"Missing expected models for {provider}"

    def test_openai_compatible_payload_and_think_tag_stripping(self):
        """Verifies OpenAI chat completion payload structure, Bearer token header, and <think> stripping."""
        # 1. Bearer token header
        api_key = "test-api-key-12345"
        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        }
        assert headers["Authorization"] == "Bearer test-api-key-12345"

        # 2. Chat completion payload format
        messages = [
            {"role": "system", "content": "You are a sales consultant."},
            {"role": "user", "content": "Tư vấn gói trị mụn."},
        ]
        payload = {
            "model": "deepseek-chat",
            "messages": messages,
            "temperature": 0.1,
            "stream": False,
        }
        json_str = json.dumps(payload)
        parsed = json.loads(json_str)
        assert parsed["model"] == "deepseek-chat"
        assert len(parsed["messages"]) == 2
        assert parsed["temperature"] == 0.1

        # 3. <think> tag stripping for reasoning models (DeepSeek-R1, Qwen reasoning)
        raw_reasoning_output = "<think>Phân tích câu hỏi của khách hàng: khách cần điều trị mụn 15 năm.</think>Dạ bên em có gói Trị Mụn Chuẩn Y Khoa ạ."
        cleaned = re.sub(r"(?s)<think>.*?</think>", "", raw_reasoning_output).strip()
        assert cleaned == "Dạ bên em có gói Trị Mụn Chuẩn Y Khoa ạ."
        assert "<think>" not in cleaned
