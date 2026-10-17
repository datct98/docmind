"""Adversarial stress tests for Excel un-truncation, window partitioning, and boundary handling.

Empirical Challenger verification suite for Milestone 1:
1. Workbooks with >350 data rows (550+ rows and 350+ rows across multiple worksheets).
2. Large empty boundaries (leading/trailing empty rows with whitespace/None, trailing empty columns).
3. Single-row worksheets (headers only) and completely empty sheets (0 rows or whitespace only).
4. Header row retention across all partitioned table blocks and heading_path = [sheet_title].
5. Exact window boundaries (100 rows, 200 rows) preventing phantom empty windows.
6. Full canonical normalization and schema monotonicity.
"""

from __future__ import annotations

import io
import pytest

from app.engines import office_parser
from app.engines.base import ParseContext
from app.engines.stub_engines import OfficeEngine
from app.normalizer import normalize
from app.schema import BlockType

_XLSX_MIME = (
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
)


def _ctx(filename: str = "stress_test.xlsx") -> ParseContext:
    return ParseContext(
        job_id="job-adversarial-excel",
        document_id="doc-adv-excel-1",
        correlation_id="corr-adv-1",
        filename=filename,
        mime=_XLSX_MIME,
        page=1,
        order_start=0,
    )


def _build_stress_workbook_500_plus_rows() -> bytes:
    """Build a multi-sheet workbook with:
    - Sheet 1: 520 data rows + 1 header row (4 columns)
    - Sheet 2: 350 data rows + 1 header row (3 columns)
    - Sheet 3: 120 data rows + 1 header row (2 columns)
    """
    import openpyxl  # noqa: PLC0415

    wb = openpyxl.Workbook()

    # Sheet 1: 520 data rows
    ws1 = wb.active
    ws1.title = "Báo cáo Doanh số Toàn quốc"
    ws1.append(["Mã SP", "Tên sản phẩm", "Đơn giá", "Doanh số"])
    for i in range(1, 521):
        ws1.append([f"SP{i:04d}", f"Sản phẩm {i}", 100000 + i * 500, i * 10])

    # Sheet 2: 350 data rows
    ws2 = wb.create_sheet("Khách hàng Thân thiết")
    ws2.append(["Mã KH", "Họ và tên", "Cấp bậc"])
    for i in range(1, 351):
        ws2.append([f"KH{i:04d}", f"Khách hàng {i}", f"VIP_{(i % 5) + 1}"])

    # Sheet 3: 120 data rows
    ws3 = wb.create_sheet("Kho Hàng")
    ws3.append(["Mã Kho", "Địa điểm"])
    for i in range(1, 121):
        ws3.append([f"WH_{i:03d}", f"Kho hàng chi nhánh {i}"])

    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


def _build_large_empty_boundaries_workbook() -> bytes:
    """Build workbook with extensive empty margins:
    - 25 leading empty rows (mixture of None, '', '   ', '\t')
    - 60 trailing empty rows
    - 15 trailing empty columns
    - Data block: 4 columns x 35 rows (1 header + 34 data rows)
    """
    import openpyxl  # noqa: PLC0415

    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "Bảng Có Biên Trống Rộng"

    # 25 leading empty rows
    for r in range(25):
        ws.append([None, "", "   ", "\t", None, "", ""])

    # Header row with 4 actual columns + 15 trailing empty columns
    header = ["Mã giao dịch", "Khách hàng", "Số tiền", "Trạng thái"] + ["" for _ in range(15)]
    ws.append(header)

    # 34 data rows with 15 trailing empty columns
    for i in range(1, 35):
        row = [f"TX_{i:04d}", f"Khách {i}", 500000 + i * 10000, "Thành công"] + [None for _ in range(15)]
        ws.append(row)

    # 60 trailing empty rows
    for r in range(60):
        ws.append([None, "", "  ", None] + ["" for _ in range(15)])

    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


def _build_single_row_and_empty_workbook() -> bytes:
    """Build workbook with:
    - Sheet 1: Exactly 1 row (header only, 0 data rows)
    - Sheet 2: Completely empty (0 rows)
    - Sheet 3: Only whitespace/None cells (10 rows of empty cells)
    """
    import openpyxl  # noqa: PLC0415

    wb = openpyxl.Workbook()

    # Sheet 1: Header only
    ws1 = wb.active
    ws1.title = "Chỉ Có Tiêu Đề Cột"
    ws1.append(["Cột A", "Cột B", "Cột C", "Cột D"])

    # Sheet 2: Completely empty
    ws2 = wb.create_sheet("Trang Hoàn Toàn Trống")

    # Sheet 3: Whitespace only
    ws3 = wb.create_sheet("Chỉ Chứa Khoảng Trắng")
    for _ in range(10):
        ws3.append(["", None, "    ", "\t"])

    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


def _build_exact_multiples_workbook() -> bytes:
    """Build workbook testing exact multiples of WINDOW_SIZE (100):
    - Sheet 1: exactly 100 data rows (header + 100 rows) -> must be exactly 1 table window
    - Sheet 2: exactly 200 data rows (header + 200 rows) -> must be exactly 2 table windows
    """
    import openpyxl  # noqa: PLC0415

    wb = openpyxl.Workbook()

    ws1 = wb.active
    ws1.title = "Đúng 100 Dòng"
    ws1.append(["STT", "Dữ liệu"])
    for i in range(1, 101):
        ws1.append([i, f"Val_{i}"])

    ws2 = wb.create_sheet("Đúng 200 Dòng")
    ws2.append(["Mã", "Mô tả"])
    for i in range(1, 201):
        ws2.append([i, f"Item_{i}"])

    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


# --------------------------------------------------------------------------- #
# Test 1: >350 rows (550+ rows & 350+ rows across multiple worksheets)
# --------------------------------------------------------------------------- #
def test_adversarial_excel_over_500_rows_multisheet():
    pytest.importorskip("openpyxl")
    data = _build_stress_workbook_500_plus_rows()
    blocks = OfficeEngine().parse(data, _ctx("500_rows_multisheet.xlsx"))

    # Verify no truncation indicator exists anywhere
    for b in blocks:
        assert "[truncated" not in b.text
        assert "[truncated" not in b.markdown

    # Sheet 1: Heading + 6 Table windows (520 rows / 100 = 6: 100, 100, 100, 100, 100, 20)
    # Sheet 2: Heading + 4 Table windows (350 rows / 100 = 4: 100, 100, 100, 50)
    # Sheet 3: Heading + 2 Table windows (120 rows / 100 = 2: 100, 20)
    # Total blocks: (1 + 6) + (1 + 4) + (1 + 2) = 7 + 5 + 3 = 15 blocks
    assert len(blocks) == 15

    # Verify Sheet 1
    s1_title = "Báo cáo Doanh số Toàn quốc"
    assert blocks[0].type == BlockType.HEADING
    assert blocks[0].text == s1_title
    assert blocks[0].heading_path == [s1_title]

    s1_tables = blocks[1:7]
    assert len(s1_tables) == 6
    s1_row_counts = [101, 101, 101, 101, 101, 21]
    for idx, (tbl, expected_lines) in enumerate(zip(s1_tables, s1_row_counts)):
        assert tbl.type == BlockType.TABLE
        assert tbl.heading_path == [s1_title]
        # Column header retained at line 0 in text and in markdown
        text_lines = tbl.text.split("\n")
        assert len(text_lines) == expected_lines, f"Window {idx} had {len(text_lines)} lines, expected {expected_lines}"
        assert text_lines[0] == "Mã SP | Tên sản phẩm | Đơn giá | Doanh số"
        assert "| Mã SP | Tên sản phẩm | Đơn giá | Doanh số |" in tbl.markdown

    # Verify boundary row continuity across Sheet 1 windows
    assert "SP0001" in s1_tables[0].text
    assert "SP0100" in s1_tables[0].text
    assert "SP0101" not in s1_tables[0].text

    assert "SP0101" in s1_tables[1].text
    assert "SP0200" in s1_tables[1].text

    assert "SP0201" in s1_tables[2].text
    assert "SP0300" in s1_tables[2].text

    assert "SP0301" in s1_tables[3].text
    assert "SP0400" in s1_tables[3].text

    assert "SP0401" in s1_tables[4].text
    assert "SP0500" in s1_tables[4].text

    assert "SP0501" in s1_tables[5].text
    assert "SP0520" in s1_tables[5].text

    # Verify Sheet 2
    s2_title = "Khách hàng Thân thiết"
    assert blocks[7].type == BlockType.HEADING
    assert blocks[7].text == s2_title
    assert blocks[7].heading_path == [s2_title]

    s2_tables = blocks[8:12]
    assert len(s2_tables) == 4
    s2_row_counts = [101, 101, 101, 51]
    for tbl, expected_lines in zip(s2_tables, s2_row_counts):
        assert tbl.type == BlockType.TABLE
        assert tbl.heading_path == [s2_title]
        text_lines = tbl.text.split("\n")
        assert len(text_lines) == expected_lines
        assert text_lines[0] == "Mã KH | Họ và tên | Cấp bậc"
        assert "| Mã KH | Họ và tên | Cấp bậc |" in tbl.markdown

    assert "KH0001" in s2_tables[0].text
    assert "KH0350" in s2_tables[3].text

    # Verify Sheet 3
    s3_title = "Kho Hàng"
    assert blocks[12].type == BlockType.HEADING
    assert blocks[12].text == s3_title
    assert blocks[12].heading_path == [s3_title]

    s3_tables = blocks[13:15]
    assert len(s3_tables) == 2
    assert len(s3_tables[0].text.split("\n")) == 101
    assert len(s3_tables[1].text.split("\n")) == 21
    for tbl in s3_tables:
        assert tbl.type == BlockType.TABLE
        assert tbl.heading_path == [s3_title]
        assert "Mã Kho | Địa điểm" == tbl.text.split("\n")[0]


# --------------------------------------------------------------------------- #
# Test 2: Large empty boundaries stripping
# --------------------------------------------------------------------------- #
def test_adversarial_excel_large_empty_boundaries():
    pytest.importorskip("openpyxl")
    data = _build_large_empty_boundaries_workbook()
    blocks = OfficeEngine().parse(data, _ctx("empty_boundaries.xlsx"))

    # Expect: 1 Heading block + 1 Table block (34 data rows + 1 header = 35 rows)
    assert len(blocks) == 2
    assert blocks[0].type == BlockType.HEADING
    assert blocks[0].text == "Bảng Có Biên Trống Rộng"
    assert blocks[0].heading_path == ["Bảng Có Biên Trống Rộng"]

    table_block = blocks[1]
    assert table_block.type == BlockType.TABLE
    assert table_block.heading_path == ["Bảng Có Biên Trống Rộng"]

    # Exactly 35 rows (1 header + 34 data rows)
    lines = table_block.text.split("\n")
    assert len(lines) == 35

    # Check header
    assert lines[0] == "Mã giao dịch | Khách hàng | Số tiền | Trạng thái"
    assert lines[1].startswith("TX_0001 | Khách 1")
    assert lines[-1].startswith("TX_0034 | Khách 34")

    # Verify trailing empty columns were stripped: exactly 4 columns in markdown
    md_lines = table_block.markdown.split("\n")
    assert md_lines[0] == "| Mã giao dịch | Khách hàng | Số tiền | Trạng thái |"
    assert md_lines[1] == "| --- | --- | --- | --- |"
    # Ensure no extra trailing pipes from empty columns
    assert not md_lines[0].endswith("|  |")
    assert not any(line.endswith("|  |") for line in md_lines)


# --------------------------------------------------------------------------- #
# Test 3: Single-row (header only) and completely empty sheets
# --------------------------------------------------------------------------- #
def test_adversarial_excel_single_row_and_completely_empty():
    pytest.importorskip("openpyxl")
    data = _build_single_row_and_empty_workbook()
    blocks = OfficeEngine().parse(data, _ctx("single_row_empty.xlsx"))

    # Expect: 3 sheets -> 3 headings + 3 tables = 6 blocks
    assert len(blocks) == 6

    # Sheet 1: Header only
    s1_title = "Chỉ Có Tiêu Đề Cột"
    assert blocks[0].type == BlockType.HEADING
    assert blocks[0].text == s1_title
    assert blocks[0].heading_path == [s1_title]

    s1_table = blocks[1]
    assert s1_table.type == BlockType.TABLE
    assert s1_table.heading_path == [s1_title]
    assert s1_table.text == "Cột A | Cột B | Cột C | Cột D"
    assert s1_table.markdown == "| Cột A | Cột B | Cột C | Cột D |\n| --- | --- | --- | --- |"
    assert "<th>Cột A</th>" in s1_table.html
    assert "<td>" not in s1_table.html  # no data rows

    # Sheet 2: Completely empty
    s2_title = "Trang Hoàn Toàn Trống"
    assert blocks[2].type == BlockType.HEADING
    assert blocks[2].text == s2_title
    assert blocks[2].heading_path == [s2_title]

    s2_table = blocks[3]
    assert s2_table.type == BlockType.TABLE
    assert s2_table.heading_path == [s2_title]
    assert s2_table.text == ""
    assert s2_table.markdown == ""
    assert s2_table.html == "<table></table>"

    # Sheet 3: Whitespace only
    s3_title = "Chỉ Chứa Khoảng Trắng"
    assert blocks[4].type == BlockType.HEADING
    assert blocks[4].text == s3_title
    assert blocks[4].heading_path == [s3_title]

    s3_table = blocks[5]
    assert s3_table.type == BlockType.TABLE
    assert s3_table.heading_path == [s3_title]
    assert s3_table.text == ""
    assert s3_table.markdown == ""
    assert s3_table.html == "<table></table>"


# --------------------------------------------------------------------------- #
# Test 4: Exact multiples of WINDOW_SIZE (preventing phantom empty windows)
# --------------------------------------------------------------------------- #
def test_adversarial_excel_exact_multiples_no_phantom_windows():
    pytest.importorskip("openpyxl")
    data = _build_exact_multiples_workbook()
    blocks = OfficeEngine().parse(data, _ctx("exact_multiples.xlsx"))

    # Sheet 1: Exactly 100 data rows -> 1 Heading + exactly 1 Table block (101 lines)
    # Sheet 2: Exactly 200 data rows -> 1 Heading + exactly 2 Table blocks (101 lines each)
    # Total = 1 + 1 + 1 + 2 = 5 blocks
    assert len(blocks) == 5

    # Sheet 1
    assert blocks[0].text == "Đúng 100 Dòng"
    assert blocks[1].type == BlockType.TABLE
    assert len(blocks[1].text.split("\n")) == 101

    # Sheet 2
    assert blocks[2].text == "Đúng 200 Dòng"
    assert blocks[3].type == BlockType.TABLE
    assert len(blocks[3].text.split("\n")) == 101
    assert blocks[4].type == BlockType.TABLE
    assert len(blocks[4].text.split("\n")) == 101


# --------------------------------------------------------------------------- #
# Test 5: Meta dictionary observability & Normalization
# --------------------------------------------------------------------------- #
def test_adversarial_excel_meta_and_normalization():
    pytest.importorskip("openpyxl")
    data = _build_stress_workbook_500_plus_rows()

    # Direct parse_xlsx call with meta dict
    import openpyxl  # noqa: PLC0415
    from app.engines.stub_engines import _block_id

    ctx = _ctx("meta_test.xlsx")

    def mk(local_index, *, type, text, markdown, html, heading_path, parse_version):
        from app.schema import Block
        return Block(
            block_id=_block_id(ctx.document_id, ctx.page, local_index),
            page=ctx.page,
            order=ctx.order_start + local_index,
            type=type,
            text=text,
            markdown=markdown,
            html=html,
            latex=None,
            bbox=(0.0, 0.0, 0.0, 0.0),
            confidence=1.0,
            heading_path=heading_path,
            parse_engine="office",
            parse_version=parse_version,
        )

    meta = {}
    blocks = office_parser.parse_xlsx(data, mk, meta=meta)

    # Verify meta facts
    assert meta["library"].startswith("openpyxl")
    assert meta["sheet_names"] == [
        "Báo cáo Doanh số Toàn quốc",
        "Khách hàng Thân thiết",
        "Kho Hàng",
    ]
    assert meta["sheet_rows"] == {
        "Báo cáo Doanh số Toàn quốc": 521,  # 1 header + 520 data rows
        "Khách hàng Thân thiết": 351,        # 1 header + 350 data rows
        "Kho Hàng": 121,                     # 1 header + 120 data rows
    }
    assert meta["truncated_sheets"] == []

    # Verify normalization
    doc = normalize(
        document_id="doc-adv-norm-1",
        filename="500_rows.xlsx",
        sha256="2" * 64,
        mime=_XLSX_MIME,
        pages=1,
        page_blocks=[blocks],
    )

    orders = [b.order for b in doc.blocks]
    assert orders == list(range(len(doc.blocks)))
    assert doc.stats.engine_breakdown.get("office") == len(doc.blocks)
