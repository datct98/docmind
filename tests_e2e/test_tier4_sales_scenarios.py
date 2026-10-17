"""Tier 4: Real-World Sales Scenarios.

Verifies complete end-to-end sales workflows using realistic documents:
1. Pricing policy .docx (tiered pricing, payment terms, regional rules)
2. Large sales inventory .xlsx with 350+ rows across multiple worksheets
3. Product catalog .md with nested headings, tables, bullet lists
4. Multi-turn Q&A and error correction with meta-learning rule induction
"""

from __future__ import annotations

import json
import pytest

from .harness.client import DocMindE2EClient
from .harness.generators import DocumentGenerators
from .harness.models import BlockType, RuleCategory


@pytest.fixture
def client() -> DocMindE2EClient:
    return DocMindE2EClient()


class TestTier4RealWorldSalesScenarios:
    """Enterprise Sales Real-World Scenarios."""

    def test_tier4_scenario1_pricing_policy_docx(self, client: DocMindE2EClient):
        """Scenario 1: Ingestion & Q&A of a Realistic Sales Pricing Policy (.docx)."""
        docx_bytes = DocumentGenerators.generate_docx_bytes(
            title="Quy Chế Bán Hàng & Chính Sách Giá Toàn Quốc Năm 2026",
            sections=[
                (
                    "1. Nguyên Tắc Định Giá & Chiết Khấu Bán Buôn",
                    "Đại lý Kim Cương (Cấp 1) được chiết khấu 15% trên giá niêm yết bán buôn. "
                    "Đại lý Vàng (Cấp 2) được chiết khấu 10%. Đại lý Bạc (Cấp 3) được chiết khấu 5%.",
                ),
                (
                    "2. Ưu Đãi Thanh Toán & Công Nợ Thương Mại",
                    "Đơn hàng thanh toán ngay 100% khi đặt hàng được giảm thêm 2% trên tổng giá trị hóa đơn. "
                    "Thời hạn công nợ tối đa là 30 ngày kể từ ngày xuất kho đối với đại lý Cấp 1.",
                ),
                (
                    "3. Phí Vận Chuyển & Giao Nhận Toàn Quốc",
                    "Miễn phí vận chuyển cho đơn hàng có giá trị từ 50.000.000 VNĐ trở lên tại khu vực nội thành.",
                ),
            ],
            table_rows=[
                ["Hạng Đối Tác", "Doanh Số Tối Thiểu (VNĐ)", "Chiết Khấu", "Thời Hạn Nợ"],
                ["Cấp 1 - Kim Cương", "500,000,000", "15%", "30 ngày"],
                ["Cấp 2 - Vàng", "200,000,000", "10%", "15 ngày"],
                ["Cấp 3 - Bạc", "50,000,000", "5%", "Thanh toán ngay"],
            ],
        )

        # Ingestion
        doc = client.parse_document("pricing_policy_2026.docx", docx_bytes)
        assert len(doc.blocks) >= 3

        # Chunking
        chunks = client.chunk_blocks(doc.document_id, doc.blocks)
        assert len(chunks) >= 1

        # Grounded Chat Inquiry
        chat_resp = client.chat(
            question="Đại lý Cấp 1 được hưởng chiết khấu bao nhiêu và công nợ tối đa mấy ngày?",
            document_ids=[doc.document_id],
        )
        assert len(chat_resp.citations) >= 1
        citation_text = chat_resp.citations[0]["text"]
        assert "Kim Cương" in citation_text or "Cấp 1" in citation_text

    def test_tier4_scenario2_large_sales_inventory_xlsx_350_rows(self, client: DocMindE2EClient):
        """Scenario 2: Large Sales Inventory (.xlsx with 400 rows across 3 sheets)."""
        configs = [
            {"title": "Kho_DienThoai", "rows": 200, "prefix": "DT", "category": "Điện thoại"},
            {"title": "Kho_Laptop", "rows": 150, "prefix": "LT", "category": "Máy tính xách tay"},
            {"title": "Kho_PhuKien", "rows": 50, "prefix": "PK", "category": "Phụ kiện"},
        ]
        # Total data rows: 200 + 150 + 50 = 400 rows!
        xlsx_bytes = DocumentGenerators.generate_xlsx_bytes(sheet_configs=configs)

        doc = client.parse_document("sales_inventory_large.xlsx", xlsx_bytes)

        # Verification 1: Un-truncated row parsing
        tables = [b for b in doc.blocks if b.type == BlockType.TABLE.value]
        # 200 rows -> 2 blocks (100, 100); 150 rows -> 2 blocks (100, 50); 50 rows -> 1 block (50) = 5 blocks
        assert len(tables) == 5

        # Verification 2: Sheet title in heading_path
        dt_blocks = [b for b in tables if b.heading_path == ["Kho_DienThoai"]]
        lt_blocks = [b for b in tables if b.heading_path == ["Kho_Laptop"]]
        pk_blocks = [b for b in tables if b.heading_path == ["Kho_PhuKien"]]
        assert len(dt_blocks) == 2
        assert len(lt_blocks) == 2
        assert len(pk_blocks) == 1

        # Verification 3: Row 0 is always detected column header row
        for block in tables:
            first_line = block.text.splitlines()[0]
            assert "Mã Hàng | Tên Sản Phẩm | Đơn Giá | Tồn Kho | Nhóm Hàng" in first_line

        # Verification 4: Bounded chunking preserves table pipes
        chunks = client.chunk_blocks(doc.document_id, doc.blocks, max_chunk_chars=1200)
        assert len(chunks) >= 5
        assert all("|" in c.text for c in chunks)

        # Verification 5: Grounded Retrieval of specific SKU across sheets
        res_dt = client.chat("Tra cứu tồn kho mã DT-0199", document_ids=[doc.document_id])
        assert len(res_dt.citations) >= 1
        assert res_dt.citations[0]["heading_path"] == ["Kho_DienThoai"]

        res_lt = client.chat("Kiểm tra sản phẩm laptop LT-0075", document_ids=[doc.document_id])
        assert len(res_lt.citations) >= 1
        assert res_lt.citations[0]["heading_path"] == ["Kho_Laptop"]

    def test_tier4_scenario3_product_catalog_md(self, client: DocMindE2EClient):
        """Scenario 3: Product Catalog (.md) with nested headings, tables, bullet lists."""
        catalog_md = DocumentGenerators.generate_markdown(
            title="Bảng Giá & Danh Mục Giải Pháp Doanh Nghiệp DocMind 2026",
            num_sections=3,
            rows_per_table=5,
        )

        doc = client.parse_document("docmind_catalog_2026.md", catalog_md.encode("utf-8"), "text/markdown")

        # Verify block kinds
        block_types = {b.type for b in doc.blocks}
        assert BlockType.HEADING.value in block_types
        assert BlockType.PARAGRAPH.value in block_types
        assert BlockType.LIST.value in block_types
        assert BlockType.TABLE.value in block_types

        # Verify breadcrumbs
        table_blocks = [b for b in doc.blocks if b.type == BlockType.TABLE.value]
        assert len(table_blocks) >= 1
        assert table_blocks[0].heading_path == [
            "Bảng Giá & Danh Mục Giải Pháp Doanh Nghiệp DocMind 2026",
            "2. Bảng Chiết Khấu Theo Doanh Số",
        ]

        # Verify chunks preserve markdown table syntax
        chunks = client.chunk_blocks(doc.document_id, doc.blocks)
        table_chunks = [c for c in chunks if "| Cấp Bậc | Doanh Số Tối Thiểu" in c.text]
        assert len(table_chunks) >= 1
        assert "|---|---|---|---|" in table_chunks[0].text

        # Multi-turn Q&A
        res = client.chat("Chiết khấu thanh toán sớm trong 7 ngày được giảm bao nhiêu %?", document_ids=[doc.document_id])
        assert len(res.citations) >= 1

    def test_tier4_scenario4_end_to_end_sales_error_correction_loop(self, client: DocMindE2EClient):
        """Scenario 4: Complete Sales Error Correction & Meta-Learning Loop.

        1. User asks complex sales question
        2. AI makes mistake
        3. User submits correction ("Báo sai & Dạy AI")
        4. Meta-learning extracts root cause & generalized rule (stripping volatile prices/numbers)
        5. Rule persisted to database
        6. Subsequent question triggers query expansion & prompt injection
        7. AI answers correctly with applied rule badges
        """
        # 1. Ingest sales policy document
        md = (
            "# Quy Chế Bán Hàng Q4/2026\n"
            "## Chính Sách Đại Lý & Thưởng Quý\n"
            "| Cấp Đại Lý | Mức Doanh Số Cơ Bản | Chiết Khấu Tiêu Chuẩn | Thưởng Vượt Chỉ Tiêu Quý |\n"
            "|---|---|---|---|\n"
            "| Đại lý Cấp 1 | 500 triệu | 15% | +3% khi doanh số vượt 500 triệu |\n"
            "| Đại lý Cấp 2 | 200 triệu | 10% | +2% khi doanh số vượt 200 triệu |\n"
        )
        doc = client.parse_document("sales_q4_policy.md", md.encode("utf-8"), "text/markdown")
        client.chunk_blocks(doc.document_id, doc.blocks)

        # 2. User asks question, assistant answers with an omission (forgot bonus)
        q1 = "Đại lý Cấp 1 có doanh số quý đạt 600 triệu thì tỷ lệ chiết khấu nhận được là bao nhiêu?"
        a1_faulty = "Tỷ lệ chiết khấu cho đại lý Cấp 1 là 15% theo bảng giá tiêu chuẩn."

        # 3. User corrects via Feedback endpoint
        correction = (
            "Không đúng. Đại lý cấp 1 đạt doanh số 600 triệu đã vượt mức 500 triệu, "
            "do đó được cộng thêm 3% thưởng quý, tổng chiết khấu phải là 18%."
        )
        fb_resp = client.submit_feedback(
            question=q1,
            faulty_answer=a1_faulty,
            user_correction=correction,
            document_ids=[doc.document_id],
        )

        # 4. Verify Root-Cause Rule Extraction & Invariants
        rule = fb_resp.learned_rule
        assert rule["category"] == RuleCategory.DISCOUNT.value
        assert rule["active"] is True
        assert len(rule["root_cause"]) > 0
        assert len(rule["generalized_rule"]) > 0
        assert len(rule["retrieval_keywords"]) >= 2

        # Invariant: volatile numbers (600 triệu, 18%) stripped from generalized rule
        assert "600 triệu" not in rule["generalized_rule"]
        assert "18%" not in rule["generalized_rule"]

        # 5. Subsequent question for a different customer in Q4
        q2 = "Đối tác Cấp 1 hoàn thành doanh số 750 triệu trong Quý 4 thì tính chiết khấu thế nào?"
        chat2_resp = client.chat(question=q2, document_ids=[doc.document_id])

        # 6. Verify Rule Application
        assert len(chat2_resp.applied_rules) >= 1
        assert any(r["id"] == rule["id"] for r in chat2_resp.applied_rules)
        # Citations grounded in source document
        assert len(chat2_resp.citations) >= 1
