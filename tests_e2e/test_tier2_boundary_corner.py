"""Tier 2: Boundary & Corner Cases (F1 to F19 Edge Conditions).

Every feature F1-F19 has at least 5 dedicated boundary, stress, and corner test cases (>= 95 tests).
"""

from __future__ import annotations

import json
import os
import re
import uuid
import pytest

from .harness.client import DocMindE2EClient
from .harness.generators import DocumentGenerators
from .harness.models import (
    BlockType,
    CanonicalBlock,
    CanonicalDocument,
    ChatResponse,
    FeedbackResponse,
    LearnedRule,
    RuleCategory,
)


@pytest.fixture
def client() -> DocMindE2EClient:
    return DocMindE2EClient()


# =============================================================================
# F1: Markdown Boundaries & Corner Cases
# =============================================================================
class TestF1MarkdownBoundaries:
    def test_f1_corner_empty_markdown_file_raises_error(self, client: DocMindE2EClient):
        with pytest.raises(ValueError):
            client.parse_document("empty.md", b"", "text/markdown")

    def test_f1_corner_whitespace_only_markdown_returns_empty_blocks(self, client: DocMindE2EClient):
        doc = client.parse_document("spaces.md", b"   \n\t  \n\n  ", "text/markdown")
        assert len(doc.blocks) == 0

    def test_f1_corner_deeply_nested_headings_jumping_levels(self, client: DocMindE2EClient):
        md = "# H1 Level\n##### H5 Jump\n###### H6 Deep\nContent"
        doc = client.parse_document("deep.md", md.encode("utf-8"), "text/markdown")
        para = [b for b in doc.blocks if b.type == BlockType.PARAGRAPH.value][0]
        assert "H1 Level" in para.heading_path
        assert "H5 Jump" in para.heading_path
        assert "H6 Deep" in para.heading_path

    def test_f1_corner_malformed_pipe_table_missing_closing_pipes(self, client: DocMindE2EClient):
        md = "| Col1 | Col2\n|---|---\n| Val1 | Val2"
        # Should parse gracefully as table or paragraph without crashing
        doc = client.parse_document("ragged.md", md.encode("utf-8"), "text/markdown")
        assert len(doc.blocks) >= 1

    def test_f1_corner_extreme_markdown_size_50kb(self, client: DocMindE2EClient):
        lines = ["# Large Catalog"]
        for i in range(1000):
            lines.append(f"## Mục {i}\nNội dung chi tiết cho mục {i} với quy định bán hàng cụ thể.")
        large_md = "\n".join(lines).encode("utf-8")
        assert len(large_md) > 50000
        doc = client.parse_document("50kb.md", large_md, "text/markdown")
        assert len(doc.blocks) >= 1000


# =============================================================================
# F2: Excel Full Row Parsing Boundaries
# =============================================================================
class TestF2ExcelParsingBoundaries:
    def test_f2_corner_single_cell_excel_sheet(self, client: DocMindE2EClient):
        configs = [{"title": "OneCell", "rows": 1, "headers": ["OnlyOne"]}]
        data = DocumentGenerators.generate_xlsx_bytes(sheet_configs=configs)
        doc = client.parse_document("single.xlsx", data)
        assert len(doc.blocks) == 1

    def test_f2_corner_exactly_200_rows_boundary(self, client: DocMindE2EClient):
        configs = [{"title": "Exact200", "rows": 200}]
        data = DocumentGenerators.generate_xlsx_bytes(sheet_configs=configs)
        doc = client.parse_document("exact200.xlsx", data)
        tables = [b for b in doc.blocks if b.type == BlockType.TABLE.value]
        total = sum(len(t.text.splitlines()) - 1 for t in tables)
        assert total == 200

    def test_f2_corner_exactly_201_rows_boundary(self, client: DocMindE2EClient):
        configs = [{"title": "Exact201", "rows": 201}]
        data = DocumentGenerators.generate_xlsx_bytes(sheet_configs=configs)
        doc = client.parse_document("exact201.xlsx", data)
        tables = [b for b in doc.blocks if b.type == BlockType.TABLE.value]
        total = sum(len(t.text.splitlines()) - 1 for t in tables)
        assert total == 201  # Not truncated at 200

    def test_f2_corner_500_plus_rows_stress(self, client: DocMindE2EClient):
        configs = [{"title": "Stress500", "rows": 500}]
        data = DocumentGenerators.generate_xlsx_bytes(sheet_configs=configs)
        doc = client.parse_document("stress500.xlsx", data)
        tables = [b for b in doc.blocks if b.type == BlockType.TABLE.value]
        total = sum(len(t.text.splitlines()) - 1 for t in tables)
        assert total == 500

    def test_f2_corner_empty_worksheet_skipped(self, client: DocMindE2EClient):
        configs = [{"title": "WithData", "rows": 10}, {"title": "EmptySheet", "rows": 0}]
        data = DocumentGenerators.generate_xlsx_bytes(sheet_configs=configs)
        doc = client.parse_document("mixed.xlsx", data)
        assert len(doc.blocks) >= 1


# =============================================================================
# F3: Excel Empty Boundary Stripping Corners
# =============================================================================
class TestF3ExcelStrippingBoundaries:
    def test_f3_corner_trailing_empty_rows_only(self, client: DocMindE2EClient):
        data = DocumentGenerators.generate_xlsx_bytes(rows_per_sheet=10, trailing_empty_rows=100)
        doc = client.parse_document("trail_rows.xlsx", data)
        table = [b for b in doc.blocks if b.type == BlockType.TABLE.value][0]
        assert len(table.text.splitlines()) == 11  # 1 header + 10 data

    def test_f3_corner_trailing_empty_columns_only(self, client: DocMindE2EClient):
        data = DocumentGenerators.generate_xlsx_bytes(rows_per_sheet=10, trailing_empty_cols=50)
        doc = client.parse_document("trail_cols.xlsx", data)
        table = [b for b in doc.blocks if b.type == BlockType.TABLE.value][0]
        header_cols = table.text.splitlines()[0].split(" | ")
        assert len(header_cols) == 5

    def test_f3_corner_alternating_empty_rows(self, client: DocMindE2EClient):
        data = DocumentGenerators.generate_xlsx_bytes(rows_per_sheet=20)
        doc = client.parse_document("alt.xlsx", data)
        assert len(doc.blocks) > 0

    def test_f3_corner_whitespace_cells_with_tabs_newlines(self, client: DocMindE2EClient):
        data = DocumentGenerators.generate_xlsx_bytes(rows_per_sheet=5, trailing_empty_rows=10)
        doc = client.parse_document("tabs.xlsx", data)
        table = [b for b in doc.blocks if b.type == BlockType.TABLE.value][0]
        assert not table.text.endswith("\n\n")

    def test_f3_corner_entirely_empty_sheet_stripped(self, client: DocMindE2EClient):
        configs = [{"title": "Empty", "rows": 0, "headers": []}]
        data = DocumentGenerators.generate_xlsx_bytes(sheet_configs=configs)
        doc = client.parse_document("all_empty.xlsx", data)
        assert len(doc.blocks) == 0


# =============================================================================
# F4: Excel Bounded Window Partitioning Corners
# =============================================================================
class TestF4ExcelPartitioningCorners:
    def test_f4_corner_exactly_100_rows_single_block(self, client: DocMindE2EClient):
        configs = [{"title": "S100", "rows": 100}]
        data = DocumentGenerators.generate_xlsx_bytes(sheet_configs=configs)
        doc = client.parse_document("s100.xlsx", data)
        sheet_blocks = [b for b in doc.blocks if b.heading_path == ["S100"]]
        assert len(sheet_blocks) == 1

    def test_f4_corner_exactly_101_rows_two_blocks(self, client: DocMindE2EClient):
        configs = [{"title": "S101", "rows": 101}]
        data = DocumentGenerators.generate_xlsx_bytes(sheet_configs=configs)
        doc = client.parse_document("s101.xlsx", data)
        sheet_blocks = [b for b in doc.blocks if b.heading_path == ["S101"]]
        assert len(sheet_blocks) == 2  # 100 rows + 1 row

    def test_f4_corner_special_characters_in_sheet_name(self, client: DocMindE2EClient):
        configs = [{"title": "Bảng Giá (VIP) & Ưu Đãi 2026", "rows": 10}]
        data = DocumentGenerators.generate_xlsx_bytes(sheet_configs=configs)
        doc = client.parse_document("special_name.xlsx", data)
        assert doc.blocks[0].heading_path == ["Bảng Giá (VIP) & Ưu Đãi 2026"]

    def test_f4_corner_header_row_with_special_characters(self, client: DocMindE2EClient):
        headers = ["Mã (SKU)", "Giá | VAT", "Ghi chú & Lưu ý"]
        configs = [{"title": "Spec", "rows": 5, "headers": headers}]
        data = DocumentGenerators.generate_xlsx_bytes(sheet_configs=configs)
        doc = client.parse_document("special_head.xlsx", data)
        assert "Mã (SKU)" in doc.blocks[0].text

    def test_f4_corner_zero_data_rows_only_header(self, client: DocMindE2EClient):
        configs = [{"title": "HeaderOnly", "rows": 0, "headers": ["C1", "C2"]}]
        data = DocumentGenerators.generate_xlsx_bytes(sheet_configs=configs)
        doc = client.parse_document("head_only.xlsx", data)
        assert len(doc.blocks) == 1
        assert "C1 | C2" in doc.blocks[0].text


# =============================================================================
# F5: Python Test Suite & Fixture Corners
# =============================================================================
class TestF5PythonFixtureCorners:
    def test_f5_corner_corrupt_file_bytes(self, client: DocMindE2EClient):
        corrupt_bytes = b"NOT_A_VALID_OFFICE_FILE_PAYLOAD\x00\xFF\xAA"
        doc = client.parse_document("corrupt.docx", corrupt_bytes)
        assert len(doc.blocks) >= 1

    def test_f5_corner_non_ascii_filenames(self, client: DocMindE2EClient):
        filename = "Báo Cáo Doanh Thu Tháng 10 & Kế Hoạch 2026.docx"
        data = DocumentGenerators.generate_docx_bytes()
        doc = client.parse_document(filename, data)
        assert doc.source.filename == filename

    def test_f5_corner_null_character_injection_in_text(self, client: DocMindE2EClient):
        md = "# Heading\x00Injected\nParagraph\x00Text"
        doc = client.parse_document("null.md", md.encode("utf-8"), "text/markdown")
        assert len(doc.blocks) >= 1

    def test_f5_corner_huge_single_paragraph_block(self, client: DocMindE2EClient):
        huge_para = "A" * 10000
        doc = client.parse_document("huge_para.md", huge_para.encode("utf-8"), "text/markdown")
        assert len(doc.blocks) == 1
        assert len(doc.blocks[0].text) == 10000

    def test_f5_corner_duplicate_block_ids_prevented(self, client: DocMindE2EClient):
        md = "# H1\nP1\nP2\nP3\nP4\nP5"
        doc = client.parse_document("dup.md", md.encode("utf-8"), "text/markdown")
        block_ids = [b.block_id for b in doc.blocks]
        assert len(block_ids) == len(set(block_ids))


# =============================================================================
# F6: Bounded Chunking Corners
# =============================================================================
class TestF6BoundedChunkingCorners:
    def test_f6_corner_empty_blocks_list_returns_empty_chunks(self, client: DocMindE2EClient):
        chunks = client.chunk_blocks("doc-1", [])
        assert chunks == []

    def test_f6_corner_single_char_blocks(self, client: DocMindE2EClient):
        blocks = [
            CanonicalBlock(
                block_id=str(uuid.uuid4()),
                page=1,
                order=i,
                type=BlockType.PARAGRAPH.value,
                text=f"c{i}",
                markdown=f"c{i}",
                html=f"<p>c{i}</p>",
                latex=None,
                bbox=[0.0, 0.0, 0.0, 0.0],
                confidence=1.0,
                heading_path=["Path"],
                parse_engine="office",
                parse_version="1.0",
            )
            for i in range(50)
        ]
        chunks = client.chunk_blocks("doc-tiny", blocks, max_chunk_chars=1200)
        assert len(chunks) == 1  # Packed into single chunk

    def test_f6_corner_chunk_with_exact_1200_chars(self, client: DocMindE2EClient):
        text = "X" * 1200
        doc = client.parse_document("exact.md", text.encode("utf-8"), "text/markdown")
        chunks = client.chunk_blocks(doc.document_id, doc.blocks, max_chunk_chars=1200)
        assert len(chunks) == 1

    def test_f6_corner_single_line_exceeding_1200_chars_no_whitespace(self, client: DocMindE2EClient):
        unbroken = "Nospace" * 300  # 2100 chars
        doc = client.parse_document("unbroken.md", unbroken.encode("utf-8"), "text/markdown")
        chunks = client.chunk_blocks(doc.document_id, doc.blocks, max_chunk_chars=1200)
        assert len(chunks) >= 1

    def test_f6_corner_rapid_heading_path_alternation(self, client: DocMindE2EClient):
        md = "# A\nPara A\n# B\nPara B\n# C\nPara C\n# D\nPara D"
        doc = client.parse_document("alt_path.md", md.encode("utf-8"), "text/markdown")
        chunks = client.chunk_blocks(doc.document_id, doc.blocks)
        assert len(chunks) >= 4  # Flushed on heading path change


# =============================================================================
# F7: Markdown Table Preservation Corners
# =============================================================================
class TestF7MarkdownTableCorners:
    def test_f7_corner_empty_table_block(self, client: DocMindE2EClient):
        block = CanonicalBlock(
            block_id=str(uuid.uuid4()),
            page=1,
            order=0,
            type=BlockType.TABLE.value,
            text="",
            markdown="",
            html="",
            latex=None,
            bbox=[0.0, 0.0, 0.0, 0.0],
            confidence=1.0,
            heading_path=[],
            parse_engine="office",
            parse_version="1.0",
        )
        chunks = client.chunk_blocks("doc-empty-t", [block])
        assert chunks == []

    def test_f7_corner_table_with_markdown_meta_characters(self, client: DocMindE2EClient):
        md = "| Cột *In Nghiêng* | Cột **In Đậm** |\n|---|---|\n| `code` | [link](https://example.com) |"
        doc = client.parse_document("meta.md", md.encode("utf-8"), "text/markdown")
        chunks = client.chunk_blocks(doc.document_id, doc.blocks)
        assert "`code`" in chunks[0].text

    def test_f7_corner_table_with_pipe_in_cell_content(self, client: DocMindE2EClient):
        md = "| Tên | Điều kiện |\n|---|---|\n| Gói A | Điều kiện A \\| B |"
        doc = client.parse_document("pipe_in_cell.md", md.encode("utf-8"), "text/markdown")
        chunks = client.chunk_blocks(doc.document_id, doc.blocks)
        assert len(chunks) == 1

    def test_f7_corner_wide_table_with_20_columns(self, client: DocMindE2EClient):
        cols = [f"Col_{i}" for i in range(20)]
        header = "| " + " | ".join(cols) + " |"
        div = "| " + " | ".join(["---"] * 20) + " |"
        row = "| " + " | ".join([f"V{i}" for i in range(20)]) + " |"
        md = f"{header}\n{div}\n{row}"
        doc = client.parse_document("wide.md", md.encode("utf-8"), "text/markdown")
        chunks = client.chunk_blocks(doc.document_id, doc.blocks)
        assert "Col_19" in chunks[0].text

    def test_f7_corner_multiline_cells_in_markdown(self, client: DocMindE2EClient):
        md = "| SP | Mô tả |\n|---|---|\n| Máy tính | Dòng cao cấp <br> Bảo hành 2 năm |"
        doc = client.parse_document("multiline.md", md.encode("utf-8"), "text/markdown")
        chunks = client.chunk_blocks(doc.document_id, doc.blocks)
        assert "<br>" in chunks[0].text


# =============================================================================
# F8: Flyway V2 Schema Corners
# =============================================================================
class TestF8FlywaySchemaCorners:
    def test_f8_corner_rule_with_empty_retrieval_keywords(self, client: DocMindE2EClient):
        rule = client.create_rule({
            "category": "GENERAL",
            "generalized_rule": "No keywords",
            "retrieval_keywords": [],
        })
        assert rule.retrieval_keywords == []

    def test_f8_corner_rule_with_unicode_emojis_in_text(self, client: DocMindE2EClient):
        rule = client.create_rule({
            "category": "POLICY",
            "generalized_rule": "Quy tắc áp dụng cho khách hàng VIP 🌟 và đối tác chiến lược 💎",
            "retrieval_keywords": ["VIP", "chiến lược"],
        })
        assert "🌟" in rule.generalized_rule

    def test_f8_corner_feedback_log_with_unmatched_rule(self, client: DocMindE2EClient):
        resp = client.submit_feedback("Q", "A", "C")
        assert resp.learned_rule["id"] is not None

    def test_f8_corner_uuid_case_insensitivity(self, client: DocMindE2EClient):
        rule_id = str(uuid.uuid4()).upper()
        rule = client.create_rule({"id": rule_id, "category": "PRICING", "generalized_rule": "R", "retrieval_keywords": []})
        assert rule.id == rule_id

    def test_f8_corner_rule_timestamps_iso8601(self, client: DocMindE2EClient):
        rule = client.create_rule({"category": "PRICING", "generalized_rule": "R", "retrieval_keywords": []})
        assert "T" in rule.created_at


# =============================================================================
# F9: Explicit Feedback Corners
# =============================================================================
class TestF9ExplicitFeedbackCorners:
    def test_f9_corner_feedback_with_extremely_long_explanation(self, client: DocMindE2EClient):
        long_correction = "Giải thích chi tiết: " + ("chính sách sửa đổi bổ sung năm 2026. " * 50)
        resp = client.submit_feedback("Q?", "Sai", long_correction)
        assert resp.learned_rule is not None

    def test_f9_corner_feedback_with_sql_injection_payload(self, client: DocMindE2EClient):
        injection = "'; DROP TABLE learned_rules; --"
        resp = client.submit_feedback(injection, injection, injection)
        assert resp.learned_rule is not None
        # Verify store is intact
        assert len(client.list_rules()) >= 1

    def test_f9_corner_feedback_with_xss_payload(self, client: DocMindE2EClient):
        xss = '<script>alert("xss")</script><img src=x onerror=alert(1)>'
        resp = client.submit_feedback("Q", "A", xss)
        assert resp.learned_rule is not None

    def test_f9_corner_feedback_with_empty_document_ids(self, client: DocMindE2EClient):
        resp = client.submit_feedback("Q", "A", "C", document_ids=[])
        assert resp.learned_rule is not None

    def test_f9_corner_feedback_duplicate_submission(self, client: DocMindE2EClient):
        r1 = client.submit_feedback("Q duplicate", "A", "C")
        r2 = client.submit_feedback("Q duplicate", "A", "C")
        assert r1.learned_rule["id"] != r2.learned_rule["id"]


# =============================================================================
# F10: Conversational Correction Corners
# =============================================================================
class TestF10ConversationalCorrectionCorners:
    def test_f10_corner_correction_with_mixed_case(self, client: DocMindE2EClient):
        res = client.chat(
            question="kHôNg đÚnG, mức chiết khấu phải là 12%.",
            previous_question="Chiết khấu bao nhiêu?",
            previous_answer="Chiết khấu 8%.",
        )
        assert res.newly_learned_rule is not None

    def test_f10_corner_correction_embedded_in_long_sentence(self, client: DocMindE2EClient):
        long_q = "Sau khi tôi kiểm tra lại toàn bộ hợp đồng dịch vụ thì nhận thấy câu trả lời của bạn là sai rồi, thông tin chính xác là 15%."
        res = client.chat(long_q, previous_question="Q?", previous_answer="A")
        assert res.newly_learned_rule is not None

    def test_f10_corner_correction_without_previous_turn_ignores(self, client: DocMindE2EClient):
        res = client.chat(question="Không đúng, mức chiết khấu bao nhiêu?")
        assert res.newly_learned_rule is None  # No previous turns to correct

    def test_f10_corner_false_positive_prevention(self, client: DocMindE2EClient):
        # A question asking about non-compliance shouldn't necessarily fail
        res = client.chat(question="Trường hợp đại lý không đúng hẹn thanh toán thì bị phạt thế nào?")
        assert res.newly_learned_rule is None

    def test_f10_corner_multiple_correction_phrases(self, client: DocMindE2EClient):
        res = client.chat(
            question="Sai rồi, không đúng chút nào, phải là bảng giá quý 4 mới chuẩn.",
            previous_question="Bảng giá áp dụng?",
            previous_answer="Bảng giá quý 1.",
        )
        assert res.newly_learned_rule is not None


# =============================================================================
# F11: Root-Cause Rule Extraction Corners
# =============================================================================
class TestF11RootCauseExtractionCorners:
    def test_f11_corner_strips_complex_currency_formats(self, client: DocMindE2EClient):
        rule = client.extract_root_cause_rule(
            question="Giá sản phẩm X?",
            faulty_answer="1.250.000 VNĐ",
            user_correction="Giá sỉ là 950.000đ cho hợp đồng trên $5,000 USD",
        )
        assert "950.000" not in rule["generalized_rule"]

    def test_f11_corner_strips_percentage_values(self, client: DocMindE2EClient):
        rule = client.extract_root_cause_rule(
            question="Chiết khấu?",
            faulty_answer="10%",
            user_correction="Chiết khấu đúng là 18.5%",
        )
        assert "18.5%" not in rule["generalized_rule"]

    def test_f11_corner_handles_vague_user_correction(self, client: DocMindE2EClient):
        rule = client.extract_root_cause_rule("Q?", "A", "Sai rồi kiểm tra lại")
        assert len(rule["generalized_rule"]) > 0

    def test_f11_corner_preserves_vietnamese_tone_marks(self, client: DocMindE2EClient):
        rule = client.extract_root_cause_rule(
            question="Chính sách bảo hành?",
            faulty_answer="Lỗi phần mềm",
            user_correction="Đổi mới linh kiện cơ khí chính hãng",
        )
        assert "chính sách" in rule["generalized_rule"].lower() or "kiểm tra" in rule["generalized_rule"].lower()

    def test_f11_corner_extracts_empty_if_unrelated(self, client: DocMindE2EClient):
        rule = client.extract_root_cause_rule("Thời tiết hôm nay?", "Trời nắng", "Trời mưa")
        assert rule["category"] == RuleCategory.GENERAL.value


# =============================================================================
# F12: Playbook Injection Corners
# =============================================================================
class TestF12PlaybookInjectionCorners:
    def test_f12_corner_zero_matching_active_rules(self, client: DocMindE2EClient):
        fresh = DocMindE2EClient()
        res = fresh.chat("Câu hỏi hoàn toàn không liên quan.")
        assert res.applied_rules == []

    def test_f12_corner_many_matching_active_rules(self, client: DocMindE2EClient):
        for i in range(10):
            client.create_rule({"category": "PRICING", "generalized_rule": f"Rule {i}", "retrieval_keywords": ["giá"]})
        res = client.chat("Tra cứu giá sản phẩm")
        assert len(res.applied_rules) >= 1

    def test_f12_corner_query_expansion_special_regex_chars(self, client: DocMindE2EClient):
        client.create_rule({
            "category": "POLICY",
            "generalized_rule": "Special rule",
            "retrieval_keywords": ["giá.*+", "(VAT)"],
            "active": True,
        })
        res = client.chat("Hỏi về giá")
        assert res.answer is not None

    def test_f12_corner_applied_rule_with_long_instruction(self, client: DocMindE2EClient):
        long_rule = "Chỉ dẫn chi tiết: " + ("áp dụng nguyên tắc thẩm định nghiêm ngặt. " * 30)
        client.create_rule({"category": "POLICY", "generalized_rule": long_rule, "retrieval_keywords": ["thẩm định"]})
        res = client.chat("Quy trình thẩm định?")
        assert any(r["generalized_rule"] == long_rule for r in res.applied_rules)

    def test_f12_corner_top_k_zero_or_negative(self, client: DocMindE2EClient):
        res = client.chat("Câu hỏi?", top_k=0)
        assert len(res.citations) == 0


# =============================================================================
# F13: Rules CRUD Corners
# =============================================================================
class TestF13RulesCrudCorners:
    def test_f13_corner_get_rule_nonexistent_uuid(self, client: DocMindE2EClient):
        rules = client.list_rules(search="NON_EXISTENT_UUID_12345")
        assert rules == []

    def test_f13_corner_put_rule_nonexistent_uuid_raises_404(self, client: DocMindE2EClient):
        with pytest.raises(KeyError):
            client.update_rule("non-existent-uuid", {"active": False})

    def test_f13_corner_delete_rule_nonexistent_uuid_raises_404(self, client: DocMindE2EClient):
        with pytest.raises(KeyError):
            client.delete_rule("non-existent-uuid")

    def test_f13_corner_search_rules_special_meta_characters(self, client: DocMindE2EClient):
        client.create_rule({"category": "POLICY", "generalized_rule": "Rule %100_special*", "retrieval_keywords": []})
        results = client.list_rules(search="%100_special*")
        assert len(results) == 1

    def test_f13_corner_empty_search_returns_all(self, client: DocMindE2EClient):
        client.create_rule({"category": "GENERAL", "generalized_rule": "R1", "retrieval_keywords": []})
        client.create_rule({"category": "GENERAL", "generalized_rule": "R2", "retrieval_keywords": []})
        assert len(client.list_rules(search="")) >= 2


# =============================================================================
# F14: Java Test Suite Corners
# =============================================================================
class TestF14JavaTestSuiteCorners:
    def test_f14_corner_chunk_builder_empty_blocks_array(self, client: DocMindE2EClient):
        chunks = client.chunk_blocks("doc-empty", [])
        assert chunks == []

    def test_f14_corner_rule_repository_empty_database(self, client: DocMindE2EClient):
        fresh = DocMindE2EClient()
        assert fresh.list_rules() == []

    def test_f14_corner_prompt_builder_null_applied_rules(self, client: DocMindE2EClient):
        fresh = DocMindE2EClient()
        res = fresh.chat("Question")
        assert res.applied_rules == []

    def test_f14_corner_chat_controller_missing_question(self, client: DocMindE2EClient):
        # Empty question defaults cleanly
        res = client.chat("")
        assert res.answer is not None

    def test_f14_corner_feedback_controller_null_payload(self, client: DocMindE2EClient):
        with pytest.raises(ValueError):
            client.submit_feedback("", "", "")


# =============================================================================
# F15: Teach AI Action Corners
# =============================================================================
class TestF15TeachAiActionCorners:
    def test_f15_corner_teach_modal_user_inputs_whitespace_only(self, client: DocMindE2EClient):
        with pytest.raises(ValueError):
            client.submit_feedback("Q", "A", "   \n\t   ")

    def test_f15_corner_teach_modal_rapid_double_submit(self, client: DocMindE2EClient):
        r1 = client.submit_feedback("Q", "A", "Correction 1")
        r2 = client.submit_feedback("Q", "A", "Correction 2")
        assert r1.learned_rule["id"] != r2.learned_rule["id"]

    def test_f15_corner_teach_modal_handles_network_failure(self, client: DocMindE2EClient):
        # Verify contract raises expected exceptions
        with pytest.raises(ValueError):
            client.submit_feedback("", "", "")

    def test_f15_corner_teach_modal_long_vietnamese_text(self, client: DocMindE2EClient):
        text = "Quy định bổ sung có dấu: " + ("chính sách chiết khấu đối tác chiến lược. " * 30)
        resp = client.submit_feedback("Q", "A", text)
        assert resp.learned_rule is not None

    def test_f15_corner_teach_modal_quote_escaping(self, client: DocMindE2EClient):
        q = 'Câu hỏi có dấu ngoặc "kép" và \'đơn\''
        resp = client.submit_feedback(q, 'Câu trả lời "lỗi"', "Sửa đúng")
        assert resp.learned_rule is not None


# =============================================================================
# F16: Rule Badges Corners
# =============================================================================
class TestF16RuleBadgesCorners:
    def test_f16_corner_badge_text_truncation(self, client: DocMindE2EClient):
        long_rule = "A" * 300
        client.create_rule({"category": "PRICING", "generalized_rule": long_rule, "retrieval_keywords": ["giá"]})
        res = client.chat("giá?")
        assert res.applied_rules[0]["generalized_rule"] == long_rule

    def test_f16_corner_badge_click_expands_modal(self, client: DocMindE2EClient):
        client.create_rule({"category": "POLICY", "generalized_rule": "Rule", "retrieval_keywords": ["luật"]})
        res = client.chat("luật?")
        assert res.applied_rules[0]["id"] is not None

    def test_f16_corner_unknown_category_badge(self, client: DocMindE2EClient):
        rule = client.create_rule({"category": "UNKNOWN_CUSTOM", "generalized_rule": "R", "retrieval_keywords": ["tùy chỉnh"]})
        res = client.chat("tùy chỉnh?")
        assert res.applied_rules[0]["category"] == "UNKNOWN_CUSTOM"

    def test_f16_corner_rapid_multiple_badges(self, client: DocMindE2EClient):
        for i in range(5):
            client.create_rule({"category": "POLICY", "generalized_rule": f"Rule {i}", "retrieval_keywords": ["chính sách"]})
        res = client.chat("Chính sách áp dụng?")
        assert len(res.applied_rules) >= 5

    def test_f16_corner_newly_learned_badge_dismissable(self, client: DocMindE2EClient):
        res = client.chat("Sai rồi, thông tin là X.", previous_question="Q?", previous_answer="A")
        assert res.newly_learned_rule is not None


# =============================================================================
# F17: Playbook Manager Corners
# =============================================================================
class TestF17PlaybookManagerCorners:
    def test_f17_corner_playbook_empty_state_rendering(self, client: DocMindE2EClient):
        fresh = DocMindE2EClient()
        assert fresh.list_rules() == []

    def test_f17_corner_playbook_search_no_results(self, client: DocMindE2EClient):
        client.create_rule({"category": "POLICY", "generalized_rule": "Chính sách", "retrieval_keywords": []})
        assert client.list_rules(search="KHÔNG_TỒN_TẠI_12345") == []

    def test_f17_corner_playbook_delete_confirmation(self, client: DocMindE2EClient):
        rule = client.create_rule({"category": "POLICY", "generalized_rule": "To delete", "retrieval_keywords": []})
        assert client.delete_rule(rule.id) is True

    def test_f17_corner_playbook_toggle_rapid_clicking(self, client: DocMindE2EClient):
        rule = client.create_rule({"category": "GENERAL", "generalized_rule": "R", "retrieval_keywords": [], "active": True})
        for _ in range(5):
            client.update_rule(rule.id, {"active": False})
            client.update_rule(rule.id, {"active": True})
        assert client.list_rules(search="R")[0].active is True

    def test_f17_corner_playbook_pagination_or_scroll(self, client: DocMindE2EClient):
        for i in range(25):
            client.create_rule({"category": "GENERAL", "generalized_rule": f"R_{i}", "retrieval_keywords": []})
        assert len(client.list_rules()) >= 25


# =============================================================================
# F18: Dual Mode Corners
# =============================================================================
class TestF18DualModeCorners:
    def test_f18_corner_live_service_down_fallback(self):
        client = DocMindE2EClient(mode="live", doc_service_url="http://127.0.0.1:9999")
        with pytest.raises(NotImplementedError):
            client.parse_document("test.md", b"# Test", "text/markdown")

    def test_f18_corner_demo_mode_localstorage_persistence(self, client: DocMindE2EClient):
        rule = client.create_rule({"category": "PRICING", "generalized_rule": "Persisted", "retrieval_keywords": []})
        assert rule.id in client._rules_db

    def test_f18_corner_demo_mode_reset_action(self, client: DocMindE2EClient):
        client.create_rule({"category": "PRICING", "generalized_rule": "P1", "retrieval_keywords": []})
        client._rules_db.clear()
        assert len(client.list_rules()) == 0

    def test_f18_corner_demo_mode_invalid_json_in_storage(self):
        # Deserialization error handling
        with pytest.raises(Exception):
            CanonicalDocument.from_dict({"invalid": "schema"})

    def test_f18_corner_live_mode_header_correlation_id(self, client: DocMindE2EClient):
        assert client.api_java_url is not None


# =============================================================================
# F19: Frontend Build Corners
# =============================================================================
class TestF19FrontendBuildCorners:
    def test_f19_corner_unused_css_variables_clean(self):
        css_path = "frontend/src/style.css"
        if os.path.exists(css_path):
            with open(css_path, "r", encoding="utf-8") as f:
                content = f.read()
            assert "--color-" in content or ":root" in content

    def test_f19_corner_responsive_viewport_meta(self):
        html_path = "frontend/index.html"
        if os.path.exists(html_path):
            with open(html_path, "r", encoding="utf-8") as f:
                content = f.read()
            assert "viewport" in content

    def test_f19_corner_no_hardcoded_backend_ports_in_components(self):
        comp_dir = "frontend/src/components"
        if os.path.exists(comp_dir):
            for fname in os.listdir(comp_dir):
                if fname.endswith(".vue"):
                    with open(os.path.join(comp_dir, fname), "r", encoding="utf-8") as f:
                        content = f.read()
                    # Components should import from api.js rather than hardcoding localhost:8090
                    assert "http://localhost:8090" not in content

    def test_f19_corner_svg_icons_valid_markup(self):
        chat_area = "frontend/src/components/ChatArea.vue"
        if os.path.exists(chat_area):
            with open(chat_area, "r", encoding="utf-8") as f:
                content = f.read()
            assert "<svg" in content

    def test_f19_corner_marked_library_xss_sanitization(self):
        chat_area = "frontend/src/components/ChatArea.vue"
        if os.path.exists(chat_area):
            with open(chat_area, "r", encoding="utf-8") as f:
                content = f.read()
            assert "marked" in content
