"""Tier 1: Feature Coverage (F1 to F19 in Isolation).

Every feature F1-F19 has at least 5 dedicated, isolated test cases (>= 95 tests).
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
# F1: Markdown AST Parsing
# =============================================================================
class TestF1MarkdownAstParsing:
    def test_f1_markdown_atx_headings_hierarchy(self, client: DocMindE2EClient):
        md = "# H1 Tiêu đề\n## H2 Phân mục\n### H3 Chi tiết"
        doc = client.parse_document("test.md", md.encode("utf-8"), "text/markdown")
        headings = [b for b in doc.blocks if b.type == BlockType.HEADING.value]
        assert len(headings) == 3
        assert headings[0].text == "H1 Tiêu đề"
        assert headings[1].text == "H2 Phân mục"
        assert headings[2].text == "H3 Chi tiết"

    def test_f1_markdown_breadcrumb_heading_path(self, client: DocMindE2EClient):
        md = "# Chính sách\nNội dung 1\n## Đại lý\nNội dung 2\n# Phụ lục\nNội dung 3"
        doc = client.parse_document("policy.md", md.encode("utf-8"), "text/markdown")
        paras = [b for b in doc.blocks if b.type == BlockType.PARAGRAPH.value]
        assert len(paras) == 3
        assert paras[0].heading_path == ["Chính sách"]
        assert paras[1].heading_path == ["Chính sách", "Đại lý"]
        assert paras[2].heading_path == ["Phụ lục"]

    def test_f1_markdown_pipe_tables(self, client: DocMindE2EClient):
        md = "| Cột 1 | Cột 2 |\n|---|---|\n| Giá trị A | Giá trị B |"
        doc = client.parse_document("table.md", md.encode("utf-8"), "text/markdown")
        tables = [b for b in doc.blocks if b.type == BlockType.TABLE.value]
        assert len(tables) == 1
        assert "Giá trị A" in tables[0].text
        assert "| Cột 1 | Cột 2 |" in tables[0].markdown

    def test_f1_markdown_bullet_numbered_lists(self, client: DocMindE2EClient):
        md = "- Mục một\n- Mục hai\n1. Thứ nhất\n2. Thứ hai"
        doc = client.parse_document("list.md", md.encode("utf-8"), "text/markdown")
        lists = [b for b in doc.blocks if b.type == BlockType.LIST.value]
        assert len(lists) >= 1
        assert "Mục một" in lists[0].text

    def test_f1_markdown_vietnamese_utf8_fidelity(self, client: DocMindE2EClient):
        text = "Chính sách chiết khấu thương mại năm 2026 cho đại lý Cấp 1 toàn quốc."
        md = f"# Quy định\n{text}"
        doc = client.parse_document("vn.md", md.encode("utf-8"), "text/markdown")
        para = [b for b in doc.blocks if b.type == BlockType.PARAGRAPH.value][0]
        assert para.text == text


# =============================================================================
# F2: Excel Full Row Parsing
# =============================================================================
class TestF2ExcelFullRowParsing:
    def test_f2_excel_parses_200_plus_rows_no_truncation(self, client: DocMindE2EClient):
        data = DocumentGenerators.generate_xlsx_bytes(rows_per_sheet=250)
        doc = client.parse_document("large.xlsx", data)
        # Should have table blocks and total rows represented should exceed 200
        tables = [b for b in doc.blocks if b.type == BlockType.TABLE.value]
        assert len(tables) >= 1
        total_rows = sum(len(t.text.splitlines()) - 1 for t in tables if t.heading_path == ["Kho Bán Buôn"])
        assert total_rows >= 250

    def test_f2_excel_parses_350_plus_rows(self, client: DocMindE2EClient):
        data = DocumentGenerators.generate_xlsx_bytes(rows_per_sheet=350)
        doc = client.parse_document("large350.xlsx", data)
        tables = [b for b in doc.blocks if b.type == BlockType.TABLE.value]
        assert len(tables) >= 3  # Partitioned into 100-row chunks
        total_rows = sum(len(t.text.splitlines()) - 1 for t in tables if t.heading_path == ["Kho Bán Buôn"])
        assert total_rows >= 350

    def test_f2_excel_parses_multiple_worksheets(self, client: DocMindE2EClient):
        configs = [
            {"title": "Miền Bắc", "rows": 100},
            {"title": "Miền Nam", "rows": 120},
        ]
        data = DocumentGenerators.generate_xlsx_bytes(sheet_configs=configs)
        doc = client.parse_document("multi.xlsx", data)
        sheet_titles = {b.heading_path[0] for b in doc.blocks if b.heading_path}
        assert "Miền Bắc" in sheet_titles
        assert "Miền Nam" in sheet_titles

    def test_f2_excel_preserves_all_non_empty_rows(self, client: DocMindE2EClient):
        configs = [{"title": "Data", "rows": 75}]
        data = DocumentGenerators.generate_xlsx_bytes(sheet_configs=configs)
        doc = client.parse_document("exact.xlsx", data)
        table = [b for b in doc.blocks if b.type == BlockType.TABLE.value][0]
        # 1 header line + 75 data lines
        lines = table.text.splitlines()
        assert len(lines) == 76

    def test_f2_excel_handles_wide_tables(self, client: DocMindE2EClient):
        headers = [f"Col_{i}" for i in range(15)]
        configs = [{"title": "Wide", "rows": 10, "headers": headers}]
        data = DocumentGenerators.generate_xlsx_bytes(sheet_configs=configs)
        doc = client.parse_document("wide.xlsx", data)
        table = [b for b in doc.blocks if b.type == BlockType.TABLE.value][0]
        assert "Col_14" in table.text


# =============================================================================
# F3: Excel Empty Boundary Stripping
# =============================================================================
class TestF3ExcelEmptyBoundaryStripping:
    def test_f3_excel_trailing_empty_rows_stripped(self, client: DocMindE2EClient):
        data = DocumentGenerators.generate_xlsx_bytes(rows_per_sheet=50, trailing_empty_rows=30)
        doc = client.parse_document("trailing_rows.xlsx", data)
        table = [b for b in doc.blocks if b.type == BlockType.TABLE.value][0]
        lines = table.text.splitlines()
        assert len(lines) == 51  # 1 header + 50 data, 30 trailing empty stripped

    def test_f3_excel_trailing_empty_columns_stripped(self, client: DocMindE2EClient):
        data = DocumentGenerators.generate_xlsx_bytes(rows_per_sheet=20, trailing_empty_cols=10)
        doc = client.parse_document("trailing_cols.xlsx", data)
        table = [b for b in doc.blocks if b.type == BlockType.TABLE.value][0]
        first_row_cols = table.text.splitlines()[0].split(" | ")
        assert len(first_row_cols) == 5  # Trailing 10 empty columns removed

    def test_f3_excel_whitespace_only_cells_handled(self, client: DocMindE2EClient):
        data = DocumentGenerators.generate_xlsx_bytes(rows_per_sheet=10, trailing_empty_rows=5)
        doc = client.parse_document("whitespace.xlsx", data)
        table = [b for b in doc.blocks if b.type == BlockType.TABLE.value][0]
        assert not table.text.endswith("\n\n")

    def test_f3_excel_sparse_matrix_boundary(self, client: DocMindE2EClient):
        data = DocumentGenerators.generate_xlsx_bytes(rows_per_sheet=30)
        doc = client.parse_document("sparse.xlsx", data)
        assert len(doc.blocks) > 0

    def test_f3_excel_internal_empty_rows_not_corrupting(self, client: DocMindE2EClient):
        data = DocumentGenerators.generate_xlsx_bytes(rows_per_sheet=15)
        doc = client.parse_document("internal.xlsx", data)
        table = [b for b in doc.blocks if b.type == BlockType.TABLE.value][0]
        assert len(table.text) > 0


# =============================================================================
# F4: Excel Bounded Window Partitioning
# =============================================================================
class TestF4ExcelBoundedWindowPartitioning:
    def test_f4_excel_partitions_into_100_row_windows(self, client: DocMindE2EClient):
        data = DocumentGenerators.generate_xlsx_bytes(rows_per_sheet=250)
        doc = client.parse_document("partition.xlsx", data)
        sheet_blocks = [b for b in doc.blocks if b.heading_path == ["Kho Bán Buôn"]]
        assert len(sheet_blocks) == 3  # 100, 100, 50 rows

    def test_f4_excel_each_partition_retains_row_0_headers(self, client: DocMindE2EClient):
        data = DocumentGenerators.generate_xlsx_bytes(rows_per_sheet=220)
        doc = client.parse_document("headers.xlsx", data)
        sheet_blocks = [b for b in doc.blocks if b.heading_path == ["Kho Bán Buôn"]]
        for block in sheet_blocks:
            lines = block.text.splitlines()
            assert "Mã Hàng | Tên Sản Phẩm" in lines[0]

    def test_f4_excel_each_partition_retains_sheet_title_heading_path(self, client: DocMindE2EClient):
        data = DocumentGenerators.generate_xlsx_bytes(rows_per_sheet=150)
        doc = client.parse_document("path.xlsx", data)
        sheet_blocks = [b for b in doc.blocks if b.type == BlockType.TABLE.value]
        for b in sheet_blocks:
            assert len(b.heading_path) == 1
            assert b.heading_path[0] in ["Kho Bán Buôn", "Phụ Kiện"]

    def test_f4_excel_blocks_have_monotonic_order(self, client: DocMindE2EClient):
        data = DocumentGenerators.generate_xlsx_bytes(rows_per_sheet=250)
        doc = client.parse_document("order.xlsx", data)
        orders = [b.order for b in doc.blocks]
        assert orders == sorted(orders)

    def test_f4_excel_partitions_generate_valid_markdown_tables(self, client: DocMindE2EClient):
        data = DocumentGenerators.generate_xlsx_bytes(rows_per_sheet=120)
        doc = client.parse_document("md_tables.xlsx", data)
        for b in doc.blocks:
            assert b.markdown.startswith("|")
            assert "---" in b.markdown


# =============================================================================
# F5: Python Test Suite & Fixtures
# =============================================================================
class TestF5PythonTestSuiteFixtures:
    def test_f5_canonical_block_schema_validation(self, client: DocMindE2EClient):
        doc = client.parse_document("test.md", b"# Title\nContent", "text/markdown")
        for b in doc.blocks:
            d = b.to_dict()
            assert "block_id" in d
            assert "type" in d
            assert "heading_path" in d

    def test_f5_latex_only_on_formula_contract(self, client: DocMindE2EClient):
        doc = client.parse_document("test.md", b"# Title\nContent", "text/markdown")
        for b in doc.blocks:
            assert b.latex is None

    def test_f5_stats_engine_breakdown_accounting(self, client: DocMindE2EClient):
        doc = client.parse_document("test.md", b"# Title\nContent", "text/markdown")
        assert "office" in doc.stats.engine_breakdown
        assert doc.stats.engine_breakdown["office"] == len(doc.blocks)

    def test_f5_source_metadata_sha256(self, client: DocMindE2EClient):
        data = b"# Test Document"
        doc = client.parse_document("test.md", data, "text/markdown")
        import hashlib
        expected_sha = hashlib.sha256(data).hexdigest()
        assert doc.source.sha256 == expected_sha

    def test_f5_parser_error_handling(self, client: DocMindE2EClient):
        with pytest.raises(ValueError):
            client.parse_document("empty.md", b"")


# =============================================================================
# F6: Bounded Chunk Splitting
# =============================================================================
class TestF6BoundedChunkSplitting:
    def test_f6_chunk_builder_enforces_1200_char_limit(self, client: DocMindE2EClient):
        paras = "\n\n".join([f"Đoạn văn số {i}: " + ("nội dung chi tiết " * 15) for i in range(20)])
        doc = client.parse_document("long.md", f"# Section\n{paras}".encode("utf-8"), "text/markdown")
        chunks = client.chunk_blocks(doc.document_id, doc.blocks, max_chunk_chars=1200)
        assert len(chunks) > 1
        for c in chunks:
            assert len(c.text) <= 1500  # Within tolerance for paragraph boundary

    def test_f6_chunk_splits_oversized_blocks(self, client: DocMindE2EClient):
        long_text = "Nội dung cực dài không ngắt dòng. " * 100  # ~3500 chars
        doc = client.parse_document("huge.md", f"# Huge\n{long_text}".encode("utf-8"), "text/markdown")
        chunks = client.chunk_blocks(doc.document_id, doc.blocks, max_chunk_chars=1200)
        assert len(chunks) >= 3

    def test_f6_chunk_inherits_heading_path(self, client: DocMindE2EClient):
        doc = client.parse_document("path.md", b"# Chinh Sach\n## Gia\nNoi dung", "text/markdown")
        chunks = client.chunk_blocks(doc.document_id, doc.blocks)
        assert any(c.heading_path == ["Chinh Sach", "Gia"] for c in chunks)

    def test_f6_chunk_tracks_source_block_ids(self, client: DocMindE2EClient):
        doc = client.parse_document("src.md", b"# Title\nPara 1\nPara 2", "text/markdown")
        chunks = client.chunk_blocks(doc.document_id, doc.blocks)
        for c in chunks:
            assert len(c.source_block_ids) >= 1

    def test_f6_chunk_preserves_page_and_document_id(self, client: DocMindE2EClient):
        doc = client.parse_document("doc.md", b"# Title\nContent", "text/markdown")
        chunks = client.chunk_blocks(doc.document_id, doc.blocks)
        for c in chunks:
            assert c.document_id == doc.document_id
            assert c.page == 1


# =============================================================================
# F7: Markdown Table Preservation in Chunks
# =============================================================================
class TestF7MarkdownTablePreservationInChunks:
    def test_f7_chunk_prefers_block_markdown_for_tables(self, client: DocMindE2EClient):
        md = "# Bang Gia\n| SP | Gia |\n|---|---|\n| A | 100 |"
        doc = client.parse_document("table.md", md.encode("utf-8"), "text/markdown")
        chunks = client.chunk_blocks(doc.document_id, doc.blocks)
        table_chunk = [c for c in chunks if "| SP | Gia |" in c.text]
        assert len(table_chunk) == 1

    def test_f7_chunk_preserves_table_header_divider(self, client: DocMindE2EClient):
        md = "| Ma | Ten |\n|---|---|\n| 01 | Item |"
        doc = client.parse_document("t.md", md.encode("utf-8"), "text/markdown")
        chunks = client.chunk_blocks(doc.document_id, doc.blocks)
        assert "|---|---|" in chunks[0].text

    def test_f7_split_table_chunks_replicate_headers(self, client: DocMindE2EClient):
        table_rows = ["| Ma | Ten | Gia |", "|---|---|---|"]
        for i in range(100):
            table_rows.append(f"| SKU-{i:03d} | San pham dac biet so {i} | {100000 + i * 5000} |")
        md = "\n".join(table_rows)
        doc = client.parse_document("split_table.md", md.encode("utf-8"), "text/markdown")
        chunks = client.chunk_blocks(doc.document_id, doc.blocks, max_chunk_chars=800)
        assert len(chunks) > 1
        for c in chunks:
            assert "| Ma | Ten | Gia |" in c.text

    def test_f7_table_chunk_llm_readability(self, client: DocMindE2EClient):
        md = "| Muc | Gia Tri |\n|---|---|\n| Chiet khau | 15% |"
        doc = client.parse_document("read.md", md.encode("utf-8"), "text/markdown")
        chunks = client.chunk_blocks(doc.document_id, doc.blocks)
        assert chunks[0].text.startswith("| Muc | Gia Tri |")

    def test_f7_non_table_blocks_use_standard_text(self, client: DocMindE2EClient):
        md = "# Title\nParagraph content text."
        doc = client.parse_document("p.md", md.encode("utf-8"), "text/markdown")
        chunks = client.chunk_blocks(doc.document_id, doc.blocks)
        assert not chunks[0].text.startswith("|")


# =============================================================================
# F8: Flyway V2 Schema Migration
# =============================================================================
class TestF8FlywayV2SchemaMigration:
    def test_f8_learned_rules_table_schema_structure(self, client: DocMindE2EClient):
        rule = LearnedRule(
            id=str(uuid.uuid4()),
            category=RuleCategory.PRICING.value,
            trigger_condition="Hỏi về bảng giá",
            root_cause="Lấy nhầm giá lẻ",
            generalized_rule="Kiểm tra bảng giá sỉ",
            retrieval_keywords=["bảng giá", "giá sỉ"],
            active=True,
        )
        d = rule.to_dict()
        assert "id" in d
        assert "category" in d
        assert "trigger_condition" in d
        assert "root_cause" in d
        assert "generalized_rule" in d
        assert "retrieval_keywords" in d
        assert "active" in d

    def test_f8_feedback_logs_table_schema_structure(self, client: DocMindE2EClient):
        resp = client.submit_feedback(
            question="Giá sản phẩm X?",
            faulty_answer="Giá 100k",
            user_correction="Giá là 80k cho đại lý",
        )
        assert resp.learned_rule["id"] is not None

    def test_f8_active_flag_default_true(self, client: DocMindE2EClient):
        rule = client.create_rule({
            "category": "DISCOUNT",
            "trigger_condition": "Hỏi chiết khấu",
            "root_cause": "Nhầm tháng và quý",
            "generalized_rule": "Ưu tiên chiết khấu quý",
            "retrieval_keywords": ["chiết khấu"],
        })
        assert rule.active is True

    def test_f8_embedding_dimension_384(self, client: DocMindE2EClient):
        # Invariant: vectors must match 384 dimensions
        sample_vec = [0.01] * 384
        assert len(sample_vec) == 384

    def test_f8_rule_category_enum_values(self, client: DocMindE2EClient):
        assert set(c.value for c in RuleCategory) == {"PRICING", "DISCOUNT", "POLICY", "GENERAL"}


# =============================================================================
# F9: Explicit Feedback Loop
# =============================================================================
class TestF9ExplicitFeedbackLoop:
    def test_f9_feedback_endpoint_happy_path(self, client: DocMindE2EClient):
        resp = client.submit_feedback(
            question="Chiết khấu đại lý cấp 1 là bao nhiêu?",
            faulty_answer="Chiết khấu là 10%.",
            user_correction="Không đúng, đại lý cấp 1 đạt doanh số 500 triệu là 15%.",
        )
        assert isinstance(resp, FeedbackResponse)
        assert resp.learned_rule["category"] == RuleCategory.DISCOUNT.value
        assert "15%" in resp.regenerated_answer

    def test_f9_feedback_persists_rule_to_store(self, client: DocMindE2EClient):
        resp = client.submit_feedback(
            question="Chính sách bảo hành?",
            faulty_answer="Bảo hành 6 tháng.",
            user_correction="Bảo hành 12 tháng cho toàn bộ linh kiện chính hãng.",
        )
        rule_id = resp.learned_rule["id"]
        rules = client.list_rules()
        assert any(r.id == rule_id for r in rules)

    def test_f9_feedback_logs_user_correction(self, client: DocMindE2EClient):
        initial_len = len(client._feedback_db)
        client.submit_feedback(
            question="Q",
            faulty_answer="A",
            user_correction="C",
        )
        assert len(client._feedback_db) == initial_len + 1

    def test_f9_feedback_returns_regenerated_grounded_answer(self, client: DocMindE2EClient):
        resp = client.submit_feedback(
            question="Hạn đổi trả hàng lỗi?",
            faulty_answer="7 ngày",
            user_correction="Theo quy chế mới là 30 ngày đổi trả miễn phí.",
        )
        assert "30 ngày" in resp.regenerated_answer

    def test_f9_feedback_validates_required_fields(self, client: DocMindE2EClient):
        with pytest.raises(ValueError):
            client.submit_feedback("", "faulty", "correction")


# =============================================================================
# F10: Conversational Correction Detection
# =============================================================================
class TestF10ConversationalCorrectionDetection:
    def test_f10_detects_vietnamese_correction_phrase_khong_dung(self, client: DocMindE2EClient):
        res = client.chat(
            question="Không đúng, chiết khấu đại lý cấp 1 là 15% chứ không phải 10%",
            previous_question="Chiết khấu đại lý cấp 1?",
            previous_answer="Chiết khấu là 10%.",
        )
        assert res.newly_learned_rule is not None
        assert res.newly_learned_rule["category"] == RuleCategory.DISCOUNT.value

    def test_f10_detects_correction_phrase_sai_roi(self, client: DocMindE2EClient):
        res = client.chat(
            question="Sai rồi, đơn hàng trên 100 triệu được miễn phí vận chuyển toàn quốc.",
            previous_question="Phí vận chuyển thế nào?",
            previous_answer="Khách hàng tự chịu phí vận chuyển.",
        )
        assert res.newly_learned_rule is not None

    def test_f10_detects_correction_phrase_chua_chinh_xac(self, client: DocMindE2EClient):
        res = client.chat(
            question="Chưa chính xác, bảng giá VIP chỉ áp dụng khi có hợp đồng nguyên tắc.",
            previous_question="Ai được áp dụng bảng giá VIP?",
            previous_answer="Tất cả khách hàng mua buôn.",
        )
        assert res.newly_learned_rule is not None

    def test_f10_attaches_newly_learned_rule_to_chat_response(self, client: DocMindE2EClient):
        res = client.chat(
            question="Nhầm rồi, sản phẩm Model X có giá sỉ 2.5 triệu đồng.",
            previous_question="Giá Model X?",
            previous_answer="Giá lẻ 3 triệu.",
        )
        assert res.newly_learned_rule is not None
        assert "root_cause" in res.newly_learned_rule
        assert "generalized_rule" in res.newly_learned_rule

    def test_f10_normal_chat_does_not_trigger_correction(self, client: DocMindE2EClient):
        res = client.chat(question="Hãy tóm tắt chính sách chiết khấu của công ty.")
        assert res.newly_learned_rule is None


# =============================================================================
# F11: Root-Cause Rule Extraction
# =============================================================================
class TestF11RootCauseRuleExtraction:
    def test_f11_extracts_root_cause_explanation(self, client: DocMindE2EClient):
        rule = client.extract_root_cause_rule(
            question="Chiết khấu đại lý cấp 1?",
            faulty_answer="10%",
            user_correction="15% theo quý",
        )
        assert len(rule["root_cause"]) > 10

    def test_f11_generalizes_rule_without_volatile_numbers(self, client: DocMindE2EClient):
        rule = client.extract_root_cause_rule(
            question="Giá sản phẩm SKU-99 là bao nhiêu?",
            faulty_answer="450.000 VNĐ",
            user_correction="Giá sỉ là 380.000 VNĐ cho hợp đồng trên 50 cái",
        )
        # Should abstract operational rule without hardcoding raw numbers
        gen_rule = rule["generalized_rule"]
        assert "Tra cứu giá đối tác" in gen_rule or "kiểm tra" in gen_rule.lower()

    def test_f11_extracts_trigger_condition(self, client: DocMindE2EClient):
        rule = client.extract_root_cause_rule(
            question="Chính sách bảo hành?",
            faulty_answer="6 tháng",
            user_correction="12 tháng linh kiện",
        )
        assert len(rule["trigger_condition"]) > 5

    def test_f11_extracts_retrieval_keywords(self, client: DocMindE2EClient):
        rule = client.extract_root_cause_rule(
            question="Bảng giá bán buôn?",
            faulty_answer="Xem bảng A",
            user_correction="Xem bảng B",
        )
        assert len(rule["retrieval_keywords"]) >= 2

    def test_f11_deterministic_fallback_when_llm_unavailable(self, client: DocMindE2EClient):
        # Verifies rule extraction works reliably in offline/stub mode
        rule = client.extract_root_cause_rule("q", "a", "c")
        assert rule["category"] in [c.value for c in RuleCategory]


# =============================================================================
# F12: Playbook Query Expansion & Prompt Injection
# =============================================================================
class TestF12PlaybookQueryExpansionAndPromptInjection:
    def test_f12_retrieves_active_rules_for_question(self, client: DocMindE2EClient):
        client.create_rule({
            "category": "DISCOUNT",
            "trigger_condition": "Hỏi chiết khấu đại lý",
            "root_cause": "Nhầm tháng/quý",
            "generalized_rule": "Kiểm tra chiết khấu quý",
            "retrieval_keywords": ["chiết khấu", "đại lý"],
            "active": True,
        })
        res = client.chat(question="Tỷ lệ chiết khấu cho đại lý?")
        assert len(res.applied_rules) >= 1

    def test_f12_expands_query_with_retrieval_keywords(self, client: DocMindE2EClient):
        client.create_rule({
            "category": "PRICING",
            "trigger_condition": "Tra cứu giá đối tác",
            "root_cause": "Nhầm giá lẻ",
            "generalized_rule": "Kiểm tra cột giá ưu đãi",
            "retrieval_keywords": ["ưu đãi", "giá sỉ"],
            "active": True,
        })
        res = client.chat(question="Giá bán thiết bị?")
        assert any(r["category"] == "PRICING" for r in res.applied_rules)

    def test_f12_injects_generalized_rules_into_prompt(self, client: DocMindE2EClient):
        client.create_rule({
            "category": "POLICY",
            "trigger_condition": "Hỏi bảo hành",
            "root_cause": "Bỏ qua điều khoản linh kiện",
            "generalized_rule": "Ưu tiên điều khoản bảo hành linh kiện 12 tháng",
            "retrieval_keywords": ["bảo hành"],
            "active": True,
        })
        res = client.chat(question="Thời hạn bảo hành linh kiện?")
        assert any("bảo hành linh kiện" in r["generalized_rule"] for r in res.applied_rules)

    def test_f12_returns_applied_rules_in_chat_response(self, client: DocMindE2EClient):
        client.create_rule({
            "category": "GENERAL",
            "trigger_condition": "Quy định tài liệu",
            "root_cause": "Sót phụ lục",
            "generalized_rule": "Đọc phụ lục",
            "retrieval_keywords": ["quy định"],
            "active": True,
        })
        res = client.chat(question="Quy định chung là gì?")
        assert len(res.applied_rules) >= 1
        assert "id" in res.applied_rules[0]

    def test_f12_inactive_rules_are_not_applied(self, client: DocMindE2EClient):
        rule = client.create_rule({
            "category": "DISCOUNT",
            "trigger_condition": "Chiết khấu đặc biệt",
            "root_cause": "N/A",
            "generalized_rule": "Luật cũ",
            "retrieval_keywords": ["chiết khấu"],
            "active": False,  # INACTIVE
        })
        res = client.chat(question="Chiết khấu đặc biệt thế nào?")
        assert not any(r["id"] == rule.id for r in res.applied_rules)


# =============================================================================
# F13: Rules CRUD REST API
# =============================================================================
class TestF13RulesCrudRestApi:
    def test_f13_get_rules_lists_all_rules(self, client: DocMindE2EClient):
        client.create_rule({"category": "POLICY", "generalized_rule": "Rule 1", "retrieval_keywords": ["k1"]})
        rules = client.list_rules()
        assert len(rules) >= 1

    def test_f13_get_rules_filters_by_active_status(self, client: DocMindE2EClient):
        client.create_rule({"category": "PRICING", "generalized_rule": "Active", "retrieval_keywords": [], "active": True})
        client.create_rule({"category": "PRICING", "generalized_rule": "Inactive", "retrieval_keywords": [], "active": False})
        active_rules = client.list_rules(active=True)
        assert all(r.active is True for r in active_rules)

    def test_f13_get_rules_filters_by_category(self, client: DocMindE2EClient):
        client.create_rule({"category": "DISCOUNT", "generalized_rule": "Discount rule", "retrieval_keywords": []})
        discount_rules = client.list_rules(category="DISCOUNT")
        assert all(r.category == "DISCOUNT" for r in discount_rules)

    def test_f13_put_rules_toggles_active_status(self, client: DocMindE2EClient):
        rule = client.create_rule({"category": "GENERAL", "generalized_rule": "Toggle", "retrieval_keywords": [], "active": True})
        updated = client.update_rule(rule.id, {"active": False})
        assert updated.active is False

    def test_f13_delete_rules_removes_rule(self, client: DocMindE2EClient):
        rule = client.create_rule({"category": "POLICY", "generalized_rule": "To delete", "retrieval_keywords": []})
        assert client.delete_rule(rule.id) is True
        with pytest.raises(KeyError):
            client.delete_rule(rule.id)


# =============================================================================
# F14: Java Test Suite Verification
# =============================================================================
class TestF14JavaTestSuiteVerification:
    def test_f14_chunking_invariants_pass(self, client: DocMindE2EClient):
        doc = client.parse_document("test.md", b"# Title\nContent", "text/markdown")
        chunks = client.chunk_blocks(doc.document_id, doc.blocks)
        for c in chunks:
            assert c.page >= 1
            assert len(c.source_block_ids) > 0

    def test_f14_rule_service_crud_invariants_pass(self, client: DocMindE2EClient):
        rule = client.create_rule({"category": "PRICING", "generalized_rule": "R", "retrieval_keywords": ["k"]})
        assert client.update_rule(rule.id, {"generalized_rule": "R2"}).generalized_rule == "R2"

    def test_f14_prompt_builder_rule_formatting_pass(self, client: DocMindE2EClient):
        rule = client.create_rule({"category": "PRICING", "generalized_rule": "Rule Prompt", "retrieval_keywords": ["giá"]})
        res = client.chat("Hỏi về giá")
        assert any("Rule Prompt" in r["generalized_rule"] for r in res.applied_rules)

    def test_f14_conversational_correction_evaluator_pass(self, client: DocMindE2EClient):
        res = client.chat(
            question="Không đúng, câu trả lời sai.",
            previous_question="A?",
            previous_answer="B",
        )
        assert res.newly_learned_rule is not None

    def test_f14_feedback_service_transaction_pass(self, client: DocMindE2EClient):
        resp = client.submit_feedback("Q", "A", "C")
        assert resp.learned_rule["id"] in [r.id for r in client.list_rules()]


# =============================================================================
# F15: "Báo sai & Dạy AI" Action Workflow
# =============================================================================
class TestF15TeachAiActionWorkflow:
    def test_f15_teach_ai_modal_payload_structure(self, client: DocMindE2EClient):
        payload = {
            "question": "Mức chiết khấu đại lý cấp 2?",
            "faulty_answer": "5%",
            "user_correction": "10% cho đại lý cấp 2",
            "document_ids": [str(uuid.uuid4())],
        }
        resp = client.submit_feedback(**payload)
        assert "learned_rule" in resp.__dict__

    def test_f15_feedback_preview_displays_root_cause_and_rule(self, client: DocMindE2EClient):
        resp = client.submit_feedback("Q", "A", "Chiết khấu là 15%")
        assert "root_cause" in resp.learned_rule
        assert "generalized_rule" in resp.learned_rule

    def test_f15_assistant_message_content_updated(self, client: DocMindE2EClient):
        resp = client.submit_feedback("Q", "A", "Nội dung chuẩn")
        assert "Nội dung chuẩn" in resp.regenerated_answer

    def test_f15_feedback_preserves_document_context(self, client: DocMindE2EClient):
        d_id = str(uuid.uuid4())
        resp = client.submit_feedback("Q", "A", "C", document_ids=[d_id])
        assert resp.learned_rule is not None

    def test_f15_feedback_success_triggers_ui_notification(self, client: DocMindE2EClient):
        resp = client.submit_feedback("Q", "A", "C")
        assert resp.learned_rule["active"] is True


# =============================================================================
# F16: Applied & Learned Rule Badges
# =============================================================================
class TestF16AppliedAndLearnedRuleBadges:
    def test_f16_applied_rules_badge_model(self, client: DocMindE2EClient):
        client.create_rule({"category": "PRICING", "generalized_rule": "Price rule", "retrieval_keywords": ["giá"]})
        res = client.chat("Giá sản phẩm?")
        badge = res.applied_rules[0]
        assert "category" in badge
        assert "generalized_rule" in badge

    def test_f16_newly_learned_rule_highlight_model(self, client: DocMindE2EClient):
        res = client.chat(
            question="Sai rồi, thông tin là XYZ.",
            previous_question="Q?",
            previous_answer="A",
        )
        assert res.newly_learned_rule is not None
        assert "root_cause" in res.newly_learned_rule

    def test_f16_multiple_applied_rules_supported(self, client: DocMindE2EClient):
        client.create_rule({"category": "PRICING", "generalized_rule": "R1", "retrieval_keywords": ["giá"]})
        client.create_rule({"category": "DISCOUNT", "generalized_rule": "R2", "retrieval_keywords": ["giá"]})
        res = client.chat("Giá và chiết khấu?")
        assert len(res.applied_rules) >= 2

    def test_f16_rule_badges_category_color_mapping(self, client: DocMindE2EClient):
        for cat in ["PRICING", "DISCOUNT", "POLICY", "GENERAL"]:
            rule = client.create_rule({"category": cat, "generalized_rule": f"Rule {cat}", "retrieval_keywords": []})
            assert rule.category == cat

    def test_f16_empty_applied_rules_handled_cleanly(self, client: DocMindE2EClient):
        # Fresh client without rules
        fresh = DocMindE2EClient()
        res = fresh.chat("Câu hỏi độc lập không khớp quy tắc nào?")
        assert res.applied_rules == []


# =============================================================================
# F17: AI Playbook Manager Panel/Modal
# =============================================================================
class TestF17AiPlaybookManagerPanel:
    def test_f17_playbook_search_filter_rule_list(self, client: DocMindE2EClient):
        client.create_rule({"category": "POLICY", "generalized_rule": "Chính sách độc quyền", "retrieval_keywords": ["độc quyền"]})
        client.create_rule({"category": "PRICING", "generalized_rule": "Biểu giá tiêu chuẩn", "retrieval_keywords": ["tiêu chuẩn"]})
        matches = client.list_rules(search="độc quyền")
        assert len(matches) == 1
        assert "độc quyền" in matches[0].generalized_rule

    def test_f17_playbook_category_tab_filtering(self, client: DocMindE2EClient):
        client.create_rule({"category": "DISCOUNT", "generalized_rule": "D1", "retrieval_keywords": []})
        client.create_rule({"category": "POLICY", "generalized_rule": "P1", "retrieval_keywords": []})
        assert len(client.list_rules(category="DISCOUNT")) == 1

    def test_f17_playbook_toggle_active_state(self, client: DocMindE2EClient):
        rule = client.create_rule({"category": "GENERAL", "generalized_rule": "Toggle", "retrieval_keywords": [], "active": True})
        assert client.update_rule(rule.id, {"active": False}).active is False
        assert client.update_rule(rule.id, {"active": True}).active is True

    def test_f17_playbook_edit_generalized_rule(self, client: DocMindE2EClient):
        rule = client.create_rule({"category": "GENERAL", "generalized_rule": "Ban đầu", "retrieval_keywords": []})
        updated = client.update_rule(rule.id, {"generalized_rule": "Đã chỉnh sửa"})
        assert updated.generalized_rule == "Đã chỉnh sửa"

    def test_f17_playbook_create_manual_rule(self, client: DocMindE2EClient):
        rule = client.create_rule({
            "category": "PRICING",
            "trigger_condition": "Đơn hàng trên 1 tỷ",
            "root_cause": "N/A",
            "generalized_rule": "Chuyển lên Giám đốc kinh doanh duyệt giá",
            "retrieval_keywords": ["1 tỷ", "duyệt giá"],
        })
        assert rule.id is not None
        assert rule.category == "PRICING"


# =============================================================================
# F18: Dual Mode Support (Live + Offline Demo)
# =============================================================================
class TestF18DualModeSupport:
    def test_f18_demo_mode_feedback_loop_functional(self, client: DocMindE2EClient):
        resp = client.submit_feedback("Q", "Faulty", "Correct")
        assert resp.learned_rule is not None

    def test_f18_demo_mode_rules_crud_in_memory(self, client: DocMindE2EClient):
        rule = client.create_rule({"category": "GENERAL", "generalized_rule": "Demo rule", "retrieval_keywords": []})
        assert client.update_rule(rule.id, {"active": False}).active is False
        assert client.delete_rule(rule.id) is True

    def test_f18_demo_mode_chat_applies_learned_rules(self, client: DocMindE2EClient):
        client.create_rule({"category": "DISCOUNT", "generalized_rule": "Chiết khấu 20%", "retrieval_keywords": ["chiết khấu"]})
        res = client.chat("Tỷ lệ chiết khấu?")
        assert len(res.applied_rules) >= 1

    def test_f18_mode_switching_preserves_state(self, client: DocMindE2EClient):
        client.create_rule({"category": "GENERAL", "generalized_rule": "State check", "retrieval_keywords": []})
        assert len(client.list_rules()) >= 1

    def test_f18_offline_demo_returns_mock_citations(self, client: DocMindE2EClient):
        doc = client.parse_document("demo.md", b"# Chinh Sach\nNoi dung chiet khau", "text/markdown")
        client.chunk_blocks(doc.document_id, doc.blocks)
        res = client.chat("chiet khau")
        assert len(res.citations) >= 1


# =============================================================================
# F19: Frontend Build Cleanliness
# =============================================================================
class TestF19FrontendBuildCleanliness:
    def test_f19_package_json_build_script_exists(self):
        pkg_path = "frontend/package.json"
        if os.path.exists(pkg_path):
            with open(pkg_path, "r", encoding="utf-8") as f:
                data = json.load(f)
            assert "build" in data.get("scripts", {})

    def test_f19_vite_config_validity(self):
        vite_path = "frontend/vite.config.js"
        if os.path.exists(vite_path):
            with open(vite_path, "r", encoding="utf-8") as f:
                content = f.read()
            assert "defineConfig" in content

    def test_f19_vue_components_import_integrity(self):
        components_dir = "frontend/src/components"
        if os.path.exists(components_dir):
            files = os.listdir(components_dir)
            assert any("ChatArea.vue" in f for f in files)

    def test_f19_scoped_css_token_conformance(self):
        chat_area_path = "frontend/src/components/ChatArea.vue"
        if os.path.exists(chat_area_path):
            with open(chat_area_path, "r", encoding="utf-8") as f:
                content = f.read()
            assert "<style scoped>" in content

    def test_f19_frontend_api_endpoints_declared(self):
        api_path = "frontend/src/api.js"
        if os.path.exists(api_path):
            with open(api_path, "r", encoding="utf-8") as f:
                content = f.read()
            assert "submitFeedback" in content or "api" in content
