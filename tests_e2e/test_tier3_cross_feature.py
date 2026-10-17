"""Tier 3: Cross-Feature Combinations (Pairwise & Multi-Service Interactions).

Verifies integrated end-to-end data flows across ingestion, chunking, feedback,
conversational correction, query expansion, playbook rules management, and chat grounding.
"""

from __future__ import annotations

import json
import uuid
import pytest

from .harness.client import DocMindE2EClient
from .harness.generators import DocumentGenerators
from .harness.models import BlockType, RuleCategory


@pytest.fixture
def client() -> DocMindE2EClient:
    return DocMindE2EClient()


class TestTier3CrossFeatureCombinations:
    """Pairwise and multi-feature interaction test suite."""

    def test_tier3_excel_ingestion_and_chat_retrieval(self, client: DocMindE2EClient):
        """Cross-Feature: Excel 350+ row ingestion -> Bounded window chunking -> Semantic chat retrieval & citation."""
        # 1. Ingest multi-sheet Excel with 350 rows
        excel_bytes = DocumentGenerators.generate_xlsx_bytes(rows_per_sheet=350)
        doc = client.parse_document("sales_inventory_350.xlsx", excel_bytes)
        assert len(doc.blocks) >= 4

        # 2. Derive chunks with bounded splitting
        chunks = client.chunk_blocks(doc.document_id, doc.blocks, max_chunk_chars=1200)
        assert len(chunks) >= 4

        # 3. Query SKU from the second sheet ("Phụ Kiện")
        chat_resp = client.chat(
            question="Tra cứu mã phụ kiện PK-050",
            document_ids=[doc.document_id],
        )
        assert chat_resp.answer is not None
        assert len(chat_resp.citations) >= 1
        # Citation should reference "Phụ Kiện" sheet in heading path
        assert any("Phụ Kiện" in c["heading_path"] for c in chat_resp.citations)

    def test_tier3_markdown_table_and_explicit_feedback_loop(self, client: DocMindE2EClient):
        """Cross-Feature: Markdown table parsing -> Grounded chat -> Explicit feedback submission -> Rule application."""
        # 1. Ingest markdown document with pricing table
        md = (
            "# Chính Sách Bán Hàng 2026\n"
            "## Bảng Chiết Khấu Đại Lý\n"
            "| Cấp Đại Lý | Mức Doanh Số | Tỷ Lệ Chiết Khấu |\n"
            "|---|---|---|\n"
            "| Đại lý Cấp 1 | Trên 500 triệu | 15% |\n"
            "| Đại lý Cấp 2 | Trên 200 triệu | 10% |\n"
        )
        doc = client.parse_document("pricing_table.md", md.encode("utf-8"), "text/markdown")
        client.chunk_blocks(doc.document_id, doc.blocks)

        # 2. Initial question receives faulty answer
        question = "Chiết khấu cho đại lý cấp 1 là bao nhiêu?"
        faulty_answer = "Theo chính sách, chiết khấu đại lý cấp 1 là 10%."

        # 3. User submits feedback ("Báo sai & Dạy AI")
        correction = "Không đúng, đại lý cấp 1 đạt doanh số trên 500 triệu được chiết khấu 15%."
        fb_resp = client.submit_feedback(
            question=question,
            faulty_answer=faulty_answer,
            user_correction=correction,
            document_ids=[doc.document_id],
        )

        assert fb_resp.learned_rule["category"] == RuleCategory.DISCOUNT.value
        assert fb_resp.learned_rule["active"] is True
        assert "15%" in fb_resp.regenerated_answer

        # 4. Subsequent chat question now automatically applies the learned rule
        follow_up = client.chat(
            question="Cho tôi biết tỷ lệ chiết khấu cho đối tác cấp 1",
            document_ids=[doc.document_id],
        )
        assert len(follow_up.applied_rules) >= 1
        assert any(r["id"] == fb_resp.learned_rule["id"] for r in follow_up.applied_rules)

    def test_tier3_conversational_correction_and_query_expansion(self, client: DocMindE2EClient):
        """Cross-Feature: Multi-turn chat -> Automatic correction detection -> Query expansion on next turn."""
        # Turn 1: Normal Q&A
        q1 = "Chính sách đổi trả hàng hóa bị lỗi kỹ thuật thế nào?"
        a1 = "Hàng hóa lỗi được đổi trả trong vòng 7 ngày làm việc."

        # Turn 2: User corrects the AI directly in chat
        q2 = "Không đúng rồi, quy định mới năm 2026 cho phép đổi trả trong 30 ngày đối với lỗi do nhà sản xuất."
        turn2_resp = client.chat(
            question=q2,
            previous_question=q1,
            previous_answer=a1,
        )

        # Verifies newly_learned_rule is attached to response
        assert turn2_resp.newly_learned_rule is not None
        assert turn2_resp.newly_learned_rule["category"] == RuleCategory.POLICY.value
        learned_id = turn2_resp.newly_learned_rule["id"]

        # Turn 3: Subsequent question is expanded with keywords from the newly learned rule
        q3 = "Thời hạn khách hàng được quyền yêu cầu đổi mới thiết bị?"
        turn3_resp = client.chat(question=q3)

        assert len(turn3_resp.applied_rules) >= 1
        assert any(r["id"] == learned_id for r in turn3_resp.applied_rules)

    def test_tier3_feedback_loop_playbook_rule_toggle_and_chat(self, client: DocMindE2EClient):
        """Cross-Feature: Feedback creates rule -> Rule active in chat -> Toggled inactive in Playbook -> Excluded in chat."""
        # 1. Create rule via feedback
        fb_resp = client.submit_feedback(
            question="Giá sỉ Model Pro?",
            faulty_answer="15 triệu",
            user_correction="Giá sỉ là 12 triệu khi đặt từ 10 bộ",
        )
        rule_id = fb_resp.learned_rule["id"]

        # 2. Verify rule is applied in chat
        chat1 = client.chat("Hỏi bảng giá sỉ Model Pro")
        assert any(r["id"] == rule_id for r in chat1.applied_rules)

        # 3. User deactivates rule in AI Playbook manager
        client.update_rule(rule_id, {"active": False})

        # 4. Verify inactive rule is NOT applied in subsequent chat
        chat2 = client.chat("Hỏi bảng giá sỉ Model Pro")
        assert not any(r["id"] == rule_id for r in chat2.applied_rules)

        # 5. Reactivate rule in Playbook
        client.update_rule(rule_id, {"active": True})
        chat3 = client.chat("Hỏi bảng giá sỉ Model Pro")
        assert any(r["id"] == rule_id for r in chat3.applied_rules)

    def test_tier3_document_chunk_splitting_and_citation_provenance(self, client: DocMindE2EClient):
        """Cross-Feature: Deep heading breadcrumbs -> Bounded chunking -> Citation retains block provenance."""
        md = (
            "# Quy Chế Bán Buôn\n"
            "## Điều 1. Phạm Vi Áp Dụng\n"
            "Chính sách áp dụng trên toàn bộ hệ thống đại lý 3 miền Bắc, Trung, Nam.\n"
            "## Điều 2. Mức Chiết Khấu & Thanh Toán\n"
            "### 2.1 Chiết Khấu Thanh Toán Sớm\n"
            "Đại lý thanh toán 100% trong vòng 3 ngày làm việc được chiết khấu thêm 2% trực tiếp trên hóa đơn VAT."
        )
        doc = client.parse_document("quyche.md", md.encode("utf-8"), "text/markdown")
        chunks = client.chunk_blocks(doc.document_id, doc.blocks)

        # Query specifically about early payment
        chat_resp = client.chat("thanh toán sớm được giảm thêm bao nhiêu?", document_ids=[doc.document_id])
        assert len(chat_resp.citations) >= 1
        citation = chat_resp.citations[0]
        assert "Quy Chế Bán Buôn" in citation["heading_path"]
        assert "2.1 Chiết Khấu Thanh Toán Sớm" in citation["heading_path"]

    def test_tier3_rules_crud_and_playbook_search_filtering(self, client: DocMindE2EClient):
        """Cross-Feature: Batch rule creation -> Playbook category filtering -> Search -> In-place update."""
        client.create_rule({
            "category": "PRICING",
            "trigger_condition": "Hỏi giá VIP",
            "root_cause": "Giá lẻ",
            "generalized_rule": "Kiểm tra biểu giá VIP",
            "retrieval_keywords": ["giá", "VIP"],
        })
        client.create_rule({
            "category": "POLICY",
            "trigger_condition": "Hỏi đổi trả",
            "root_cause": "Thiếu hạn",
            "generalized_rule": "Đổi trả trong 30 ngày",
            "retrieval_keywords": ["đổi trả"],
        })

        # Category filter
        pricing_rules = client.list_rules(category="PRICING")
        assert len(pricing_rules) == 1
        assert pricing_rules[0].category == "PRICING"

        # Search filter
        search_results = client.list_rules(search="30 ngày")
        assert len(search_results) == 1
        assert "30 ngày" in search_results[0].generalized_rule

        # Update
        updated = client.update_rule(search_results[0].id, {"generalized_rule": "Đổi trả trong 45 ngày"})
        assert updated.generalized_rule == "Đổi trả trong 45 ngày"

    def test_tier3_docx_ingestion_and_prompt_builder_injection(self, client: DocMindE2EClient):
        """Cross-Feature: Word document (.docx) ingestion -> Playbook rule injection -> Unified grounded answer."""
        docx_bytes = DocumentGenerators.generate_docx_bytes(
            title="Hợp Đồng Hợp Tác Phân Phối",
            sections=[
                ("Điều Khoản Chiết Khấu", "Đại lý phân phối được hưởng chiết khấu cơ bản 15%."),
                ("Thưởng Quý", "Đạt doanh số quý trên 1 tỷ được thưởng thêm 3%."),
            ],
        )
        doc = client.parse_document("contract.docx", docx_bytes)
        client.chunk_blocks(doc.document_id, doc.blocks)

        # Create active rule
        client.create_rule({
            "category": "DISCOUNT",
            "trigger_condition": "Hỏi thưởng doanh số",
            "root_cause": "N/A",
            "generalized_rule": "Đối chiếu bảng thưởng quý theo doanh số thực tế",
            "retrieval_keywords": ["thưởng", "doanh số", "quý"],
            "active": True,
        })

        chat_resp = client.chat("Chính sách thưởng doanh số quý của đại lý?", document_ids=[doc.document_id])
        assert len(chat_resp.citations) >= 1
        assert len(chat_resp.applied_rules) >= 1
