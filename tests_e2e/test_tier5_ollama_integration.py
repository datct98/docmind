"""Tier 5: Real Local Ollama (qwen2.5) E2E Integration Tests for DocMind.

Verifies live LLM inference against local Ollama (`http://localhost:11434`, `qwen2.5:7b` / `qwen2.5:latest`)
across DocMind's RAG Chat, Explicit Feedback Loop (F9), Conversational Correction (F10),
Root-Cause Rule Extraction (F11), and Playbook Query Expansion & Prompt Injection (F12).
"""

from __future__ import annotations

from tests_e2e.harness.client import DocMindE2EClient
from tests_e2e.harness.generators import DocumentGenerators


class TestTier5OllamaQwen25Integration:
    """Live Ollama qwen2.5 integration test suite."""

    def test_f18_ollama_health_and_qwen25_model_availability(self, client: DocMindE2EClient):
        """Verify local Ollama server is reachable and qwen2.5 model is loaded."""
        health = client.check_ollama_health()
        assert health["ok"] is True
        models = health["available_models"]
        assert any("qwen2.5" in m for m in models), f"Expected qwen2.5 in Ollama models, got: {models}"

    def test_f11_ollama_qwen25_root_cause_rule_extraction_no_volatile_numbers(self, client: DocMindE2EClient):
        """Verify real qwen2.5 extracts generalized Meta-Learning rule JSON and strips volatile numbers."""
        rule_dict = client.extract_root_cause_rule_ollama(
            question="Đại lý cấp 1 đạt doanh số 500 triệu trong Quý 4 được hưởng chiết khấu bao nhiêu?",
            faulty_answer="Đại lý cấp 1 được chiết khấu 15%.",
            user_correction="Sai rồi, theo chính sách Quý 4 mới nhất, đại lý cấp 1 đạt từ 500 triệu trở lên được chiết khấu 18% chứ không phải 15%.",
            retrieved_context="Bảng chính sách chiết khấu Q4: Đại lý cấp 1 doanh số >= 500.000.000 VNĐ hưởng chiết khấu 18%.",
        )

        assert rule_dict["id"]
        assert rule_dict["category"] in {"DISCOUNT", "PRICING", "POLICY", "GENERAL"}
        assert len(rule_dict["generalized_rule"]) > 15
        # Must not memorize volatile numbers from the correction
        assert "500" not in rule_dict["generalized_rule"]
        assert "18" not in rule_dict["generalized_rule"]
        assert "15" not in rule_dict["generalized_rule"]
        assert len(rule_dict["retrieval_keywords"]) >= 1

    def test_f9_ollama_qwen25_explicit_feedback_and_regenerated_answer(self, client: DocMindE2EClient):
        """Verify F9 explicit feedback loop using real Ollama qwen2.5 for rule extraction & answer regeneration."""
        fb_resp = client.submit_feedback_ollama(
            question="Đơn hàng sỉ từ 100 sản phẩm trở lên được giảm giá bao nhiêu phần trăm?",
            faulty_answer="Đơn hàng từ 100 sản phẩm được giảm 5%.",
            user_correction="Không đúng, đơn hàng sỉ từ 100 sản phẩm trở lên theo bảng giá đối tác được giảm 12%.",
        )

        assert fb_resp.learned_rule["id"] in client._rules_db
        assert "100" not in fb_resp.learned_rule["generalized_rule"]
        assert "12" not in fb_resp.learned_rule["generalized_rule"]
        assert len(fb_resp.regenerated_answer) > 10
        assert "12%" in fb_resp.regenerated_answer
        assert len(fb_resp.applied_rules) == 1

    def test_f10_f12_ollama_qwen25_multi_turn_conversational_correction_and_rag(self, client: DocMindE2EClient):
        """Verify end-to-end Markdown ingestion -> Chunking -> Conversational Correction -> Grounded RAG with qwen2.5."""
        md_bytes = DocumentGenerators.generate_markdown().encode("utf-8")
        doc = client.parse_document("bang_gia_va_chinh_sach_q4.md", md_bytes)
        chunks = client.chunk_blocks(doc.document_id, doc.blocks)
        assert len(chunks) > 0

        # Turn 1: User corrects previous faulty answer in natural conversation
        corr_resp = client.chat_ollama(
            question="Sai rồi, khi tra cứu giá sản phẩm trong bảng giá phải kiểm tra kỹ điều khoản đổi trả 30 ngày và chiết khấu thanh toán sớm.",
            document_ids=[doc.document_id],
            previous_question="Chính sách đổi trả sản phẩm trong bảng giá như thế nào?",
            previous_answer="Tất cả sản phẩm đều chỉ đổi trả trong 7 ngày.",
        )

        assert corr_resp.newly_learned_rule is not None
        assert corr_resp.newly_learned_rule["id"] in client._rules_db
        assert len(corr_resp.applied_rules) >= 1
        assert len(corr_resp.citations) >= 1
        assert len(corr_resp.answer) > 15

