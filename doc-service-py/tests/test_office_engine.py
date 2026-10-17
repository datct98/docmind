"""Real OfficeEngine tests: parse tiny .docx / .xlsx into canonical blocks.

Builds minimal Word and Excel files in memory (python-docx / openpyxl), runs
them through :class:`OfficeEngine` (and the :class:`ParserRouter` for the
integration path), and asserts the canonical-block invariants: block types,
heading breadcrumb, Vietnamese round-trip, the office field-value contract
(bbox / confidence / latex / page / parse_engine / parse_version), and the
graceful stub fallback when a backing library is unavailable.
"""

from __future__ import annotations

import io

import pytest

from app.engines import office_parser
from app.engines.base import PageKind, ParseContext
from app.engines.stub_engines import OfficeEngine
from app.normalizer import normalize
from app.router import ParserRouter

_DOCX_MIME = (
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
)
_XLSX_MIME = (
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
)
_MD_MIME = "text/markdown"
_TXT_MIME = "text/plain"


def _docx_bytes() -> bytes:
    """Build a tiny .docx with headings, a paragraph, an empty para, a table."""
    import docx  # noqa: PLC0415

    document = docx.Document()
    document.add_heading("Tài liệu thử nghiệm", level=1)
    document.add_heading("Mục con", level=2)
    document.add_paragraph("Nội dung tiếng Việt có dấu.")
    document.add_paragraph("")  # empty paragraph -> must be skipped
    table = document.add_table(rows=2, cols=2)
    table.cell(0, 0).text = "Khoá"
    table.cell(0, 1).text = "Giá trị"
    table.cell(1, 0).text = "Alpha"
    table.cell(1, 1).text = "1"
    buf = io.BytesIO()
    document.save(buf)
    return buf.getvalue()


def _xlsx_bytes() -> bytes:
    """Build a tiny .xlsx with two sheets, Vietnamese header + data."""
    import openpyxl  # noqa: PLC0415

    wb = openpyxl.Workbook()
    ws1 = wb.active
    ws1.title = "Bảng 1"
    ws1.append(["Tên", "Điểm"])
    ws1.append(["Học viên A", 9])
    ws1.append(["Học viên B", 8])
    ws2 = wb.create_sheet("Bảng 2")
    ws2.append(["Ghi chú"])
    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


def _ctx(mime: str, filename: str) -> ParseContext:
    return ParseContext(
        job_id="job-office",
        document_id="doc-office-1",
        correlation_id="corr-1",
        filename=filename,
        mime=mime,
        page=1,
        order_start=0,
    )


# --------------------------------------------------------------------------- #
# Word
# --------------------------------------------------------------------------- #
def test_office_parses_docx_into_canonical_blocks():
    pytest.importorskip("docx")
    data = _docx_bytes()
    blocks = OfficeEngine().parse(data, _ctx(_DOCX_MIME, "test.docx"))

    assert len(blocks) >= 4  # 2 headings + 1 paragraph + 1 table (empty skipped)

    # First block: Heading 1 with the breadcrumb seeded.
    first = blocks[0]
    assert first.type == "heading"
    assert first.heading_path == ["Tài liệu thử nghiệm"]

    # Heading 2 appends under Heading 1.
    second = blocks[1]
    assert second.type == "heading"
    assert second.heading_path == ["Tài liệu thử nghiệm", "Mục con"]

    # A paragraph exists with the Vietnamese text round-tripping exactly.
    paragraphs = [b for b in blocks if b.type == "paragraph"]
    assert any(b.text == "Nội dung tiếng Việt có dấu." for b in paragraphs)
    assert paragraphs[0].heading_path == ["Tài liệu thử nghiệm", "Mục con"]

    # A table block exists with markdown + html rendering.
    tables = [b for b in blocks if b.type == "table"]
    assert tables, "expected a table block"
    assert "|" in tables[0].markdown
    assert "<table" in tables[0].html
    assert "Khoá" in tables[0].text and "Giá trị" in tables[0].text

    # Empty paragraph produced no block (count stays at the 4 real items).
    assert len(blocks) == 4

    # Office field-value contract on every block.
    for b in blocks:
        assert b.parse_engine == "office"
        assert b.bbox == (0.0, 0.0, 0.0, 0.0)
        assert b.confidence == 1.0
        assert b.latex is None
        assert b.page == 1
        assert b.parse_version.startswith("python-docx")


def test_office_docx_routes_through_parser_router():
    pytest.importorskip("docx")
    data = _docx_bytes()
    router = ParserRouter()

    decisions = router.classify(_DOCX_MIME, data)
    assert len(decisions) == 1
    assert decisions[0].kind == PageKind.OFFICE

    engine = router.engine_for(PageKind.OFFICE)
    assert isinstance(engine, OfficeEngine)
    assert engine.supports(PageKind.OFFICE) is True

    blocks = engine.parse(data, _ctx(_DOCX_MIME, "routed.docx"))
    assert blocks and blocks[0].type == "heading"
    assert all(b.parse_engine == "office" for b in blocks)


# --------------------------------------------------------------------------- #
# Excel
# --------------------------------------------------------------------------- #
def test_office_parses_xlsx_into_canonical_blocks():
    pytest.importorskip("openpyxl")
    data = _xlsx_bytes()
    blocks = OfficeEngine().parse(data, _ctx(_XLSX_MIME, "test.xlsx"))

    # First block per sheet is a heading with the sheet name.
    assert blocks[0].type == "heading"
    assert blocks[0].text == "Bảng 1"
    assert blocks[0].heading_path == ["Bảng 1"]

    # The block right after the sheet heading is the sheet's table.
    assert blocks[1].type == "table"
    assert blocks[1].heading_path == ["Bảng 1"]

    # Second sheet "Bảng 2" appears as its own heading block.
    headings = [b for b in blocks if b.type == "heading"]
    assert any(b.text == "Bảng 2" for b in headings)

    # Vietnamese cell text round-trips inside the table block.
    tables = [b for b in blocks if b.type == "table"]
    assert any("Học viên A" in b.text for b in tables)
    assert any("Điểm" in b.text for b in tables)

    for b in blocks:
        assert b.parse_engine == "office"
        assert b.parse_version.startswith("openpyxl")
        assert b.bbox == (0.0, 0.0, 0.0, 0.0)
        assert b.confidence == 1.0
        assert b.latex is None
        assert b.page == 1


# --------------------------------------------------------------------------- #
# Fallback + normalization
# --------------------------------------------------------------------------- #
def test_office_falls_back_to_stub_when_docx_lib_unavailable(monkeypatch):
    # Force the lazy loader to report python-docx as absent.
    monkeypatch.setattr(office_parser, "load_docx", lambda: None)

    # Any bytes are fine; the fallback happens before parsing.
    blocks = OfficeEngine().parse(b"not-a-real-docx", _ctx(_DOCX_MIME, "x.docx"))

    # Stub fallback shape: exactly 2 blocks, first a heading. Must not raise.
    assert len(blocks) == 2
    assert blocks[0].type == "heading"
    assert all(b.parse_engine == "office" for b in blocks)


def test_office_blocks_normalize_into_valid_document():
    pytest.importorskip("docx")
    data = _docx_bytes()
    blocks = OfficeEngine().parse(data, _ctx(_DOCX_MIME, "norm.docx"))

    document = normalize(
        document_id="doc-office-1",
        filename="norm.docx",
        sha256="0" * 64,
        mime=_DOCX_MIME,
        pages=1,
        page_blocks=[blocks],
    )

    # Global reading order is re-assigned monotonically 0..n-1.
    orders = [b.order for b in document.blocks]
    assert orders == list(range(len(document.blocks)))
    assert document.stats.engine_breakdown.get("office") == len(document.blocks)


# --------------------------------------------------------------------------- #
# Large multi-sheet Excel window partitioning tests
# --------------------------------------------------------------------------- #
def _large_multisheet_xlsx_bytes() -> bytes:
    """Build a multi-sheet .xlsx workbook with 350+ data rows, trailing/leading empty cells."""
    import openpyxl  # noqa: PLC0415

    wb = openpyxl.Workbook()

    # Sheet 1: 350 data rows + 1 header row, with 3 trailing empty cols and 5 trailing empty rows
    ws1 = wb.active
    ws1.title = "Danh mục sản phẩm"
    ws1.append(["Mã SP", "Tên sản phẩm", "Đơn giá", "Tồn kho", "", "", ""])
    for i in range(1, 351):
        ws1.append([f"SP_{i:04d}", f"Sản phẩm {i}", 100000 + i * 1000, i % 50, None, "", "   "])
    for _ in range(5):
        ws1.append(["", None, "   ", "", None, "", ""])

    # Sheet 2: 150 data rows with 2 leading empty rows
    ws2 = wb.create_sheet("Khách hàng VIP")
    ws2.append(["", "", ""])
    ws2.append([None, None, None])
    ws2.append(["Mã KH", "Họ và tên", "Cấp bậc", "Doanh số"])
    for i in range(1, 151):
        ws2.append([f"KH_{i:04d}", f"Khách hàng {i}", "VIP 1", 50000000])

    # Sheet 3: Empty sheet
    ws3 = wb.create_sheet("Ghi chú trống")
    ws3.append(["", None])

    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


def test_office_parses_large_xlsx_without_truncation_and_partitions_windows():
    pytest.importorskip("openpyxl")
    data = _large_multisheet_xlsx_bytes()
    blocks = OfficeEngine().parse(data, _ctx(_XLSX_MIME, "catalog_large.xlsx"))

    # Assert no truncation occurred
    for b in blocks:
        assert "[truncated" not in b.text
        assert "[truncated" not in b.markdown

    # Sheet 1: Heading + 4 Table windows (350 data rows / 100 per window = 4 windows)
    # Sheet 2: Heading + 2 Table windows (150 data rows / 100 per window = 2 windows)
    # Sheet 3: Heading + 1 empty Table
    # Total blocks: 1 + 4 + 1 + 2 + 1 + 1 = 10 blocks
    assert len(blocks) == 10

    # Sheet 1 Heading
    assert blocks[0].type == "heading"
    assert blocks[0].text == "Danh mục sản phẩm"
    assert blocks[0].heading_path == ["Danh mục sản phẩm"]

    # Sheet 1 Table windows
    sheet1_tables = blocks[1:5]
    for idx, tbl in enumerate(sheet1_tables):
        assert tbl.type == "table"
        assert tbl.heading_path == ["Danh mục sản phẩm"]
        # Column header row retained in every window
        assert "Mã SP" in tbl.text and "Tên sản phẩm" in tbl.text
        assert "Mã SP" in tbl.markdown
        # Empty trailing columns were stripped (no extra empty pipes)
        assert not tbl.markdown.endswith("|  |")
        assert tbl.parse_engine == "office"
        assert tbl.bbox == (0.0, 0.0, 0.0, 0.0)
        assert tbl.confidence == 1.0

    # Check row counts in windows:
    # First 3 windows have header + 100 data rows = 101 lines in text
    assert len(sheet1_tables[0].text.split("\n")) == 101
    assert "SP_0001" in sheet1_tables[0].text
    assert "SP_0100" in sheet1_tables[0].text
    assert "SP_0101" not in sheet1_tables[0].text

    assert len(sheet1_tables[1].text.split("\n")) == 101
    assert "SP_0101" in sheet1_tables[1].text
    assert "SP_0200" in sheet1_tables[1].text

    assert len(sheet1_tables[2].text.split("\n")) == 101
    assert "SP_0201" in sheet1_tables[2].text
    assert "SP_0300" in sheet1_tables[2].text

    # 4th window has header + remaining 50 data rows = 51 lines in text
    assert len(sheet1_tables[3].text.split("\n")) == 51
    assert "SP_0301" in sheet1_tables[3].text
    assert "SP_0350" in sheet1_tables[3].text

    # Sheet 2 Heading
    assert blocks[5].type == "heading"
    assert blocks[5].text == "Khách hàng VIP"
    assert blocks[5].heading_path == ["Khách hàng VIP"]

    # Sheet 2 Table windows (leading empty rows stripped so header is detected)
    sheet2_tables = blocks[6:8]
    assert len(sheet2_tables) == 2
    assert sheet2_tables[0].heading_path == ["Khách hàng VIP"]
    assert "Mã KH" in sheet2_tables[0].text and "Họ và tên" in sheet2_tables[0].text
    assert len(sheet2_tables[0].text.split("\n")) == 101  # header + 100
    assert len(sheet2_tables[1].text.split("\n")) == 51   # header + 50

    # Sheet 3 Heading & Empty Table
    assert blocks[8].type == "heading"
    assert blocks[8].text == "Ghi chú trống"
    assert blocks[9].type == "table"
    assert blocks[9].heading_path == ["Ghi chú trống"]

    # Monotonic reading order across all blocks
    orders = [b.order for b in blocks]
    assert orders == list(range(len(blocks)))


# --------------------------------------------------------------------------- #
# Markdown parsing tests
# --------------------------------------------------------------------------- #
def test_office_parses_markdown_into_canonical_blocks():
    md_content = """# Báo cáo Doanh số Sales 2026
Tổng quan tình hình kinh doanh trong hai quý đầu năm 2026.
Bao gồm kênh trực tiếp và đại lý phân phối.

## Kênh Trực tiếp
Chi tiết kết quả bán hàng tại các chi nhánh:

| Chi nhánh | Số đơn | Doanh thu | Tỷ lệ tăng trưởng |
| --- | --- | --- | --- |
| Hà Nội | 1,250 | 2,500,000,000 | +15% |
| TP.HCM | 3,400 | 5,100,000,000 | +28% |
| Đà Nẵng | 850 | 1,200,000,000 | +10% |

### Chính sách Ưu đãi
Các chương trình khuyến mại đang áp dụng:
- Giảm 10% cho đơn hàng đầu tiên
- Miễn phí giao hàng với đơn từ 500k
- Tặng voucher 200k cho thành viên thân thiết

Quy trình bảo hành:
1. Tiếp nhận yêu cầu bảo hành
2. Kiểm tra tình trạng sản phẩm
3. Đổi mới linh kiện hoặc sản phẩm

## Kênh Đại lý
Chính sách chiết khấu dành cho đại lý cấp 1 và cấp 2.
"""
    payload = md_content.encode("utf-8")
    blocks = OfficeEngine().parse(payload, _ctx(_MD_MIME, "report.md"))

    assert len(blocks) >= 12

    # First block: H1 heading
    assert blocks[0].type == "heading"
    assert blocks[0].text == "Báo cáo Doanh số Sales 2026"
    assert blocks[0].heading_path == ["Báo cáo Doanh số Sales 2026"]
    assert blocks[0].markdown == "# Báo cáo Doanh số Sales 2026"

    # Paragraph under H1
    assert blocks[1].type == "paragraph"
    assert "Tổng quan tình hình" in blocks[1].text
    assert blocks[1].heading_path == ["Báo cáo Doanh số Sales 2026"]

    # H2 heading
    assert blocks[2].type == "heading"
    assert blocks[2].text == "Kênh Trực tiếp"
    assert blocks[2].heading_path == ["Báo cáo Doanh số Sales 2026", "Kênh Trực tiếp"]

    # Paragraph under H2
    assert blocks[3].type == "paragraph"
    assert blocks[3].heading_path == ["Báo cáo Doanh số Sales 2026", "Kênh Trực tiếp"]

    # Table under H2
    table_block = blocks[4]
    assert table_block.type == "table"
    assert table_block.heading_path == ["Báo cáo Doanh số Sales 2026", "Kênh Trực tiếp"]
    assert "| Chi nhánh |" in table_block.markdown
    assert "Hà Nội" in table_block.text
    assert "<td>TP.HCM</td>" in table_block.html

    # H3 heading under H2
    assert blocks[5].type == "heading"
    assert blocks[5].text == "Chính sách Ưu đãi"
    assert blocks[5].heading_path == [
        "Báo cáo Doanh số Sales 2026",
        "Kênh Trực tiếp",
        "Chính sách Ưu đãi",
    ]

    # Paragraph under H3
    assert blocks[6].type == "paragraph"
    assert blocks[6].heading_path == [
        "Báo cáo Doanh số Sales 2026",
        "Kênh Trực tiếp",
        "Chính sách Ưu đãi",
    ]

    # Bullet list under H3
    bullet_list = blocks[7]
    assert bullet_list.type == "list"
    assert bullet_list.heading_path == [
        "Báo cáo Doanh số Sales 2026",
        "Kênh Trực tiếp",
        "Chính sách Ưu đãi",
    ]
    assert "Giảm 10%" in bullet_list.text
    assert "<ul>" in bullet_list.html
    assert "<li>Giảm 10% cho đơn hàng đầu tiên</li>" in bullet_list.html

    # Paragraph under H3
    assert blocks[8].type == "paragraph"
    assert "Quy trình bảo hành" in blocks[8].text

    # Numbered list under H3
    numbered_list = blocks[9]
    assert numbered_list.type == "list"
    assert numbered_list.heading_path == [
        "Báo cáo Doanh số Sales 2026",
        "Kênh Trực tiếp",
        "Chính sách Ưu đãi",
    ]
    assert "1. Tiếp nhận" in numbered_list.text
    assert "<ol>" in numbered_list.html
    assert "<li>Tiếp nhận yêu cầu bảo hành</li>" in numbered_list.html

    # H2 heading: level 2 pops previous level 2 & 3 breadcrumbs!
    assert blocks[10].type == "heading"
    assert blocks[10].text == "Kênh Đại lý"
    assert blocks[10].heading_path == ["Báo cáo Doanh số Sales 2026", "Kênh Đại lý"]

    # Paragraph under new H2
    assert blocks[11].type == "paragraph"
    assert blocks[11].heading_path == ["Báo cáo Doanh số Sales 2026", "Kênh Đại lý"]

    # Canonical office conventions
    for b in blocks:
        assert b.parse_engine == "office"
        assert b.parse_version == "builtin-markdown-1.0"
        assert b.bbox == (0.0, 0.0, 0.0, 0.0)
        assert b.confidence == 1.0
        assert b.latex is None
        assert b.page == 1


def test_office_markdown_routes_through_parser_router():
    router = ParserRouter()
    md_bytes = b"# Tieu de\n\nNoi dung ngan."

    for mime, fname in [
        ("text/markdown", "sales.md"),
        ("text/plain", "notes.txt"),
        ("application/octet-stream", "doc.markdown"),
    ]:
        decisions = router.classify(mime, md_bytes)
        assert len(decisions) == 1
        assert decisions[0].kind == PageKind.OFFICE

        engine = router.engine_for(PageKind.OFFICE)
        blocks = engine.parse(md_bytes, _ctx(mime, fname))
        assert len(blocks) == 2
        assert blocks[0].type == "heading"
        assert blocks[0].text == "Tieu de"
        assert blocks[1].type == "paragraph"
        assert blocks[1].text == "Noi dung ngan."
        assert blocks[0].parse_version == "builtin-markdown-1.0"


def test_office_markdown_edge_cases():
    # 1. Empty payload
    empty_blocks = OfficeEngine().parse(b"", _ctx(_MD_MIME, "empty.md"))
    assert empty_blocks == []

    # 2. Heading with trailing hashes and pipe escaping
    content = """# Tiêu đề chính #
## Mục phụ ###

| Cột A | Cột B |
| :--- | ---: |
| Dữ liệu có \\| dấu gạch đứng | Giá trị 2 |
"""
    blocks = OfficeEngine().parse(content.encode("utf-8"), _ctx(_MD_MIME, "escape.md"))
    assert len(blocks) == 3
    assert blocks[0].text == "Tiêu đề chính"
    assert blocks[0].markdown == "# Tiêu đề chính"
    assert blocks[1].text == "Mục phụ"
    assert blocks[1].markdown == "## Mục phụ"
    assert blocks[2].type == "table"
    assert "Dữ liệu có | dấu gạch đứng" in blocks[2].text


def test_office_markdown_normalizes_into_valid_document():
    content = b"# Document Title\n\nSome introductory text.\n"
    blocks = OfficeEngine().parse(content, _ctx(_MD_MIME, "test.md"))

    document = normalize(
        document_id="doc-office-md-1",
        filename="test.md",
        sha256="1" * 64,
        mime=_MD_MIME,
        pages=1,
        page_blocks=[blocks],
    )

    orders = [b.order for b in document.blocks]
    assert orders == [0, 1]
    assert document.stats.engine_breakdown.get("office") == 2

