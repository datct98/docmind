"""Adversarial Challenger M1-R2 stress test suite for office_parser.py table header carry-over."""

import io
import pytest
import openpyxl

from app.engines import office_parser
from app.engines.office_parser import (
    _emit_spreadsheet_grid_blocks,
    _is_compatible_table_row,
    _is_table_header_row,
    parse_xlsx,
    parse_html,
    WINDOW_SIZE,
)
from app.schema import Block, BlockType


def _mk():
    def factory(idx: int, **kwargs) -> Block:
        return Block(
            block_id=f"stress-blk-{idx}",
            order=idx,
            page=1,
            confidence=1.0,
            bbox=(0.0, 0.0, 0.0, 0.0),
            parse_engine="office",
            **kwargs,
        )
    return factory


def test_multi_section_three_categories_global_header():
    """Verify that 3 sections all inherit the global header and preserve every first data row."""
    grid = [
        ["STT", "Mã Dịch Vụ", "Tên Gói Trị Liệu", "Thời Lượng", "Đơn Giá"],
        ["1. MASSAGE TRỊ LIỆU ĐÔNG Y"],
        ["1", "NS01", "Massage Cổ Vai Gáy Chuyên Sâu", "60 phút", "399.000 VNĐ"],
        ["2", "NS02", "Massage Toàn Thân Đá Nóng", "90 phút", "599.000 VNĐ"],
        ["2. GỘI ĐẦU DƯỠNG SINH CỔ TRUYỀN"],
        ["1", "DS01", "Gội Đầu Dưỡng Sinh Thảo Dược", "45 phút", "199.000 VNĐ"],
        ["2", "DS02", "Gội Đầu Chuyên Sâu Đả Thông Kinh Lạc", "75 phút", "349.000 VNĐ"],
        ["3. CHĂM SÓC DA & TRẺ HÓA"],
        ["1", "SK01", "Điện Di Tinh Chất Trắng Sáng", "60 phút", "499.000 VNĐ"],
        ["2", "SK02", "Cấy Tảo Xoắn Tươi Tái Tạo", "75 phút", "699.000 VNĐ"],
    ]

    blocks: list[Block] = []
    _emit_spreadsheet_grid_blocks(
        filtered_rows=grid,
        base_heading_path=["Nàng Sen", "Bảng Giá 2026"],
        blocks=blocks,
        start_local_index=0,
        mk=_mk(),
        parse_version="stress-r2",
    )

    # Expected: Heading 1, Table 1, Heading 2, Table 2, Heading 3, Table 3 = 6 blocks total
    assert len(blocks) == 6, f"Expected 6 blocks, got {len(blocks)}: {[b.type for b in blocks]}"

    expected_headers = [
        "1. MASSAGE TRỊ LIỆU ĐÔNG Y",
        "2. GỘI ĐẦU DƯỠNG SINH CỔ TRUYỀN",
        "3. CHĂM SÓC DA & TRẺ HÓA",
    ]
    expected_first_rows = [
        "Massage Cổ Vai Gáy Chuyên Sâu",
        "Gội Đầu Dưỡng Sinh Thảo Dược",
        "Điện Di Tinh Chất Trắng Sáng",
    ]

    for sec_idx in range(3):
        h_block = blocks[sec_idx * 2]
        t_block = blocks[sec_idx * 2 + 1]

        assert h_block.type == BlockType.HEADING
        assert h_block.text == expected_headers[sec_idx]

        assert t_block.type == BlockType.TABLE
        lines = t_block.markdown.strip().split("\n")
        assert lines[0] == "| STT | Mã Dịch Vụ | Tên Gói Trị Liệu | Thời Lượng | Đơn Giá |"
        assert lines[1] == "| --- | --- | --- | --- | --- |"
        # Crucial check: First data row must be data, NOT table header!
        assert expected_first_rows[sec_idx] in lines[2], (
            f"Section {sec_idx} swallowed first data row into header: {lines[2]}"
        )


def test_interleaved_empty_rows_before_and_after_section_breaks():
    """Verify empty rows around headings do not create phantom tables or lose headers."""
    grid = [
        ["STT", "Tên Dịch Vụ", "Thời Gian", "Đơn Giá"],
        ["", "", "", ""],
        ["", "", "", ""],
        ["1. MASSAGE TRỊ LIỆU"],
        ["", "", "", ""],
        ["1", "Massage Cổ Vai Gáy", "60 phút", "399.000"],
        ["2", "Massage Body Đá Nóng", "90 phút", "599.000"],
        ["", "", "", ""],
        ["2. GỘI ĐẦU DƯỠNG SINH"],
        ["1", "Gội Đầu Dưỡng Sinh", "45 phút", "199.000"],
        ["", "", "", ""],
    ]

    blocks: list[Block] = []
    _emit_spreadsheet_grid_blocks(
        filtered_rows=grid,
        base_heading_path=["Nàng Sen"],
        blocks=blocks,
        start_local_index=0,
        mk=_mk(),
        parse_version="stress-r2",
    )

    assert len(blocks) == 4, f"Expected 4 blocks, got {len(blocks)}: {[b.type for b in blocks]}"
    assert blocks[0].type == BlockType.HEADING and blocks[0].text == "1. MASSAGE TRỊ LIỆU"
    assert blocks[1].type == BlockType.TABLE
    assert "Massage Cổ Vai Gáy" in blocks[1].markdown.split("\n")[2]
    assert blocks[1].markdown.split("\n")[0] == "| STT | Tên Dịch Vụ | Thời Gian | Đơn Giá |"

    assert blocks[2].type == BlockType.HEADING and blocks[2].text == "2. GỘI ĐẦU DƯỠNG SINH"
    assert blocks[3].type == BlockType.TABLE
    assert "Gội Đầu Dưỡng Sinh" in blocks[3].markdown.split("\n")[2]
    assert blocks[3].markdown.split("\n")[0] == "| STT | Tên Dịch Vụ | Thời Gian | Đơn Giá |"


def test_massive_subtable_split_across_window_size():
    """Verify that a 120-row section sub-table splits with inherited headers replicated in both chunks."""
    header = ["STT", "Tên Gói Trị Liệu", "Thời Lượng", "Đơn Giá"]
    heading = ["1. MASSAGE CHUYÊN SÂU HƠN 100 GÓI"]
    rows = [header, heading]
    for i in range(1, 121):
        rows.append([str(i), f"Gói Đặc Biệt Số {i}", "60 phút", f"{300000 + i * 5000} VNĐ"])

    blocks: list[Block] = []
    _emit_spreadsheet_grid_blocks(
        filtered_rows=rows,
        base_heading_path=["Test"],
        blocks=blocks,
        start_local_index=0,
        mk=_mk(),
        parse_version="stress-r2",
    )

    # Heading (1) + Table Part 1 (100 rows) + Table Part 2 (20 rows) = 3 blocks
    assert len(blocks) == 3
    assert blocks[0].type == BlockType.HEADING
    assert blocks[1].type == BlockType.TABLE
    assert blocks[2].type == BlockType.TABLE

    # Part 1 check
    lines1 = blocks[1].markdown.strip().split("\n")
    assert lines1[0] == "| STT | Tên Gói Trị Liệu | Thời Lượng | Đơn Giá |"
    assert "Gói Đặc Biệt Số 1" in lines1[2]
    assert len(lines1) == 102  # header + separator + 100 data rows

    # Part 2 check
    lines2 = blocks[2].markdown.strip().split("\n")
    assert lines2[0] == "| STT | Tên Gói Trị Liệu | Thời Lượng | Đơn Giá |"
    assert "Gói Đặc Biệt Số 101" in lines2[2]
    assert len(lines2) == 22   # header + separator + 20 data rows


def test_real_openpyxl_workbook_end_to_end_parse_excel():
    """Verify end-to-end openpyxl XLSX generation and parse_excel execution."""
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "Bảng Giá Tổng Hợp"

    ws.append(["STT", "Tên Dịch Vụ", "Thời Gian", "Đơn Giá"])
    ws.append(["1. MASSAGE TRỊ LIỆU"])
    ws.append(["1", "Massage Cổ Vai Gáy", "60 phút", "399.000"])
    ws.append(["2", "Massage Body Toàn Thân", "90 phút", "599.000"])
    ws.append(["2. GỘI ĐẦU DƯỠNG SINH"])
    ws.append(["1", "Gội Đầu Thảo Dược", "45 phút", "199.000"])

    bio = io.BytesIO()
    wb.save(bio)
    xlsx_bytes = bio.getvalue()

    blocks = parse_xlsx(xlsx_bytes, _mk())
    assert len(blocks) >= 4

    # Find the table blocks
    table_blocks = [b for b in blocks if b.type == BlockType.TABLE]
    assert len(table_blocks) == 2

    # Check that neither table swallowed the first data row
    assert "Massage Cổ Vai Gáy" in table_blocks[0].markdown.split("\n")[2]
    assert table_blocks[0].markdown.split("\n")[0] == "| STT | Tên Dịch Vụ | Thời Gian | Đơn Giá |"

    assert "Gội Đầu Thảo Dược" in table_blocks[1].markdown.split("\n")[2]
    assert table_blocks[1].markdown.split("\n")[0] == "| STT | Tên Dịch Vụ | Thời Gian | Đơn Giá |"


def test_section_heading_followed_by_list_then_another_section_table():
    """Verify that section 1 (table), section 2 (notes), section 3 (table) maintains header inheritance."""
    grid = [
        ["STT", "Tên Dịch Vụ", "Thời Gian", "Đơn Giá"],
        ["1. MASSAGE TRỊ LIỆU"],
        ["1", "Massage Cổ Vai Gáy", "60 phút", "399.000"],
        ["2. LƯU Ý VÀ CHÍNH SÁCH"],
        ["Khách hàng đặt lịch trước 30 phút để được phục vụ chu đáo nhất"],
        ["Không áp dụng đồng thời với các voucher quà tặng ngoài hệ thống"],
        ["3. GỘI ĐẦU DƯỠNG SINH"],
        ["1", "Gội Đầu Thảo Dược", "45 phút", "199.000"],
        ["2", "Gội Đầu Chuyên Sâu", "60 phút", "299.000"],
    ]

    blocks: list[Block] = []
    _emit_spreadsheet_grid_blocks(
        filtered_rows=grid,
        base_heading_path=["Nàng Sen"],
        blocks=blocks,
        start_local_index=0,
        mk=_mk(),
        parse_version="stress-r2",
    )

    # 1. Heading 1
    # 2. Table 1 (Massage)
    # 3. Heading 2 (Lưu ý)
    # 4. List (Notes)
    # 5. Heading 3 (Gội đầu)
    # 6. Table 2 (Gội đầu)
    assert len(blocks) == 6
    assert blocks[0].type == BlockType.HEADING and blocks[0].text == "1. MASSAGE TRỊ LIỆU"
    assert blocks[1].type == BlockType.TABLE
    assert "Massage Cổ Vai Gáy" in blocks[1].markdown.split("\n")[2]

    assert blocks[2].type == BlockType.HEADING and blocks[2].text == "2. LƯU Ý VÀ CHÍNH SÁCH"
    assert blocks[3].type == BlockType.LIST
    assert "Khách hàng đặt lịch trước" in blocks[3].text

    assert blocks[4].type == BlockType.HEADING and blocks[4].text == "3. GỘI ĐẦU DƯỠNG SINH"
    assert blocks[5].type == BlockType.TABLE
    assert "Gội Đầu Thảo Dược" in blocks[5].markdown.split("\n")[2]
    assert blocks[5].markdown.split("\n")[0] == "| STT | Tên Dịch Vụ | Thời Gian | Đơn Giá |"


def test_single_data_row_under_section_heading():
    """Verify that a section with only a single data row preserves the row and inherits header."""
    grid = [
        ["STT", "Tên Dịch Vụ", "Thời Gian", "Đơn Giá"],
        ["1. GÓI ĐẶC BIỆT DUY NHẤT"],
        ["1", "Massage Hoàng Gia 4 Tay", "120 phút", "1.990.000 VNĐ"],
    ]
    blocks: list[Block] = []
    _emit_spreadsheet_grid_blocks(
        filtered_rows=grid,
        base_heading_path=["Nàng Sen"],
        blocks=blocks,
        start_local_index=0,
        mk=_mk(),
        parse_version="stress-r2",
    )

    assert len(blocks) == 2
    assert blocks[0].type == BlockType.HEADING and blocks[0].text == "1. GÓI ĐẶC BIỆT DUY NHẤT"
    assert blocks[1].type == BlockType.TABLE
    lines = blocks[1].markdown.strip().split("\n")
    assert lines[0] == "| STT | Tên Dịch Vụ | Thời Gian | Đơn Giá |"
    assert len(lines) == 3  # header + separator + 1 data row
    assert "Massage Hoàng Gia 4 Tay" in lines[2]


def test_roman_numeral_and_emoji_section_headings():
    """Verify Roman numerals ('I.', 'II.') and emoji/uppercase section headings trigger header carry-over."""
    grid = [
        ["Mã", "Tên Gói", "Thời Lượng", "Chi Phí"],
        ["I. MASSAGE ĐÁ NÓNG"],
        ["NS01", "Đá Nóng Himalaya", "75 phút", "550.000"],
        ["II. GỘI ĐẦU DƯỠNG SINH"],
        ["DS01", "Canh Thuốc Thảo Dược", "60 phút", "250.000"],
        ["⭐️ III. KHUYẾN MÃI ĐẶC BIỆT"],
        ["KM01", "Combo Toàn Diện", "120 phút", "700.000"],
    ]
    blocks: list[Block] = []
    _emit_spreadsheet_grid_blocks(
        filtered_rows=grid,
        base_heading_path=["Menu"],
        blocks=blocks,
        start_local_index=0,
        mk=_mk(),
        parse_version="stress-r2",
    )

    assert len(blocks) == 6
    assert blocks[0].type == BlockType.HEADING and "I. MASSAGE" in blocks[0].text
    assert blocks[1].type == BlockType.TABLE
    assert "Đá Nóng Himalaya" in blocks[1].markdown.split("\n")[2]
    assert blocks[1].markdown.split("\n")[0] == "| Mã | Tên Gói | Thời Lượng | Chi Phí |"

    assert blocks[2].type == BlockType.HEADING and "II. GỘI ĐẦU" in blocks[2].text
    assert blocks[3].type == BlockType.TABLE
    assert "Canh Thuốc Thảo Dược" in blocks[3].markdown.split("\n")[2]
    assert blocks[3].markdown.split("\n")[0] == "| Mã | Tên Gói | Thời Lượng | Chi Phí |"

    assert blocks[4].type == BlockType.HEADING and "III. KHUYẾN MÃI" in blocks[4].text
    assert blocks[5].type == BlockType.TABLE
    assert "Combo Toàn Diện" in blocks[5].markdown.split("\n")[2]
    assert blocks[5].markdown.split("\n")[0] == "| Mã | Tên Gói | Thời Lượng | Chi Phí |"


def test_html_table_parsing_with_section_headings_and_data_rows():
    """Verify HTML table parsing with single-cell section headings preserves data rows and headers."""
    html_content = """
    <html>
      <body>
        <table>
          <tr><th>STT</th><th>Dịch Vụ</th><th>Thời Lượng</th><th>Giá Tiền</th></tr>
          <tr><td colspan="4">1. MASSAGE TRỊ LIỆU</td></tr>
          <tr><td>1</td><td>Massage Cổ Vai Gáy</td><td>60 phút</td><td>399.000</td></tr>
          <tr><td>2</td><td>Massage Body Đá Nóng</td><td>90 phút</td><td>599.000</td></tr>
          <tr><td colspan="4">2. GỘI ĐẦU DƯỠNG SINH</td></tr>
          <tr><td>1</td><td>Gội Đầu Thảo Dược</td><td>45 phút</td><td>199.000</td></tr>
        </table>
      </body>
    </html>
    """.strip()

    blocks = parse_html(html_content.encode("utf-8"), _mk())
    # HTML parser produces canonical blocks
    table_blocks = [b for b in blocks if b.type == BlockType.TABLE]
    assert len(table_blocks) >= 1
    # Check that Massage Cổ Vai Gáy is not lost
    combined_md = "\n".join(b.markdown for b in blocks)
    assert "Massage Cổ Vai Gáy" in combined_md
    assert "Gội Đầu Thảo Dược" in combined_md
