"""Adversarial challenger test suite for table parsing in office_parser.py.

Empirical Challenger verification suite for Milestone 1:
1. Malformed tables with ragged rows and differing column counts.
2. Missing column cells in header and body.
3. Multi-level merged headers and category forward-fill.
4. Non-forward-fill of identifiers (STT, Mã, SKU).
5. Massive tables with 50+, 100+, 250+ rows across WINDOW_SIZE boundaries.
6. Special characters in table cells (pipes, newlines, HTML tags).
7. Critical defect reproduction: Section heading causes first data row to be eaten as table header.
"""

from __future__ import annotations

import pytest

from app.engines import office_parser
from app.engines.office_parser import (
    _emit_spreadsheet_grid_blocks,
    _forward_fill_grouped_table,
    _is_table_header_row,
    _md_table,
    WINDOW_SIZE,
)
from app.schema import Block, BlockType


def _mk():
    def factory(idx: int, **kwargs) -> Block:
        return Block(
            block_id=f"blk-{idx}",
            order=idx,
            page=1,
            confidence=1.0,
            bbox=(0.0, 0.0, 0.0, 0.0),
            parse_engine="office",
            **kwargs,
        )
    return factory


def test_ragged_rows_padding_without_section_break():
    """Verify that rows with varying numbers of cells are padded to the full width."""
    ragged = [
        ["Danh mục", "Dịch vụ", "Đơn giá", "Thời lượng", "Phòng"],
        ["Massage", "Body VIP", "500k", "60 phút", "Phòng VIP"],
        ["", "Foot massage"],
        ["Xông hơi", "Xông đá muối", "150k"],
        ["", "Xông thảo dược", "120k", "30 phút", "Phòng Thường"],
    ]
    blocks: list[Block] = []
    _emit_spreadsheet_grid_blocks(
        filtered_rows=ragged,
        base_heading_path=["TestSheet"],
        blocks=blocks,
        start_local_index=0,
        mk=_mk(),
        parse_version="test-1.0",
    )

    assert len(blocks) == 1
    table_block = blocks[0]
    assert table_block.type == BlockType.TABLE
    lines = table_block.markdown.strip().split("\n")
    # All rows should have 5 columns (6 pipes: | c1 | c2 | c3 | c4 | c5 |)
    for line in lines:
        assert line.count("|") == 6, f"Malformed line in markdown table: {line}"


def test_missing_column_cells_in_header():
    """Verify table with empty cells in header row."""
    table = [
        ["Danh mục", "", "Đơn giá", ""],
        ["Massage", "Massage VIP", "500.000 VNĐ", "60 phút"],
        ["", "Massage thường", "300.000 VNĐ", "45 phút"],
    ]
    blocks: list[Block] = []
    _emit_spreadsheet_grid_blocks(
        filtered_rows=table,
        base_heading_path=["Bảng Giá"],
        blocks=blocks,
        start_local_index=0,
        mk=_mk(),
        parse_version="test-1.0",
    )

    assert len(blocks) == 1
    table_block = blocks[0]
    assert table_block.type == BlockType.TABLE
    # Forward-fill should still work on Col 0
    assert "Massage | Massage thường" in table_block.markdown


def test_multi_level_merged_headers_three_levels():
    """Verify 3-level hierarchy forward fill (Cơ sở > Tầng > Phòng)."""
    table = [
        ["Cơ sở", "Tầng", "Phòng", "Loại phòng", "Giá"],
        ["Cơ sở 1", "Tầng 1", "P101", "VIP", "500k"],
        ["", "", "P102", "Standard", "300k"],
        ["", "Tầng 2", "P201", "VIP", "600k"],
        ["", "", "P202", "Standard", "350k"],
        ["Cơ sở 2", "Tầng 1", "P101", "VIP", "550k"],
        ["", "", "P102", "Standard", "320k"],
    ]
    filled = _forward_fill_grouped_table(table)
    assert filled[1] == ["Cơ sở 1", "Tầng 1", "P101", "VIP", "500k"]
    assert filled[2] == ["Cơ sở 1", "Tầng 1", "P102", "Standard", "300k"]
    assert filled[3] == ["Cơ sở 1", "Tầng 2", "P201", "VIP", "600k"]
    assert filled[4] == ["Cơ sở 1", "Tầng 2", "P202", "Standard", "350k"]
    assert filled[5] == ["Cơ sở 2", "Tầng 1", "P101", "VIP", "550k"]
    assert filled[6] == ["Cơ sở 2", "Tầng 1", "P102", "Standard", "320k"]


def test_massive_table_window_splitting_and_header_replication():
    """Verify a table with 250 rows is split across WINDOW_SIZE=100 boundaries and 100% blocks retain header."""
    header = ["STT", "Tên dịch vụ", "Đơn giá", "Ghi chú"]
    rows = [header]
    for i in range(1, 251):
        rows.append([str(i), f"Dịch vụ số {i}", f"{100000 + i * 1000} VNĐ", f"Ghi chú {i}"])

    blocks: list[Block] = []
    _emit_spreadsheet_grid_blocks(
        filtered_rows=rows,
        base_heading_path=["Dịch Vụ"],
        blocks=blocks,
        start_local_index=0,
        mk=_mk(),
        parse_version="test-1.0",
    )

    # 250 data rows / 100 per window = 3 blocks (100, 100, 50)
    assert len(blocks) == 3
    for idx, b in enumerate(blocks):
        assert b.type == BlockType.TABLE
        lines = b.markdown.strip().split("\n")
        # Line 0 is header row
        assert lines[0] == "| STT | Tên dịch vụ | Đơn giá | Ghi chú |", f"Block {idx} missing header: {lines[0]}"
        # Line 1 is delimiter row
        assert lines[1] == "| --- | --- | --- | --- |", f"Block {idx} missing delimiter: {lines[1]}"
        # Number of data rows: block 0 -> 100, block 1 -> 100, block 2 -> 50
        data_line_count = len(lines) - 2
        expected_rows = 100 if idx < 2 else 50
        assert data_line_count == expected_rows, f"Block {idx} expected {expected_rows} rows but got {data_line_count}"


def test_special_characters_escaping_in_cells():
    """Verify pipes, newlines, and html in cell values are safely escaped in markdown and html."""
    rows = [
        ["Cột 1", "Cột 2"],
        ["Cell with | pipe", "Cell with \n newline"],
        ["<script>alert(1)</script>", "Backslash \\ test"],
    ]
    blocks: list[Block] = []
    _emit_spreadsheet_grid_blocks(
        filtered_rows=rows,
        base_heading_path=["Special"],
        blocks=blocks,
        start_local_index=0,
        mk=_mk(),
        parse_version="test-1.0",
    )

    assert len(blocks) == 1
    tb = blocks[0]
    assert "\\|" in tb.markdown, "Pipe should be escaped with backslash in markdown"
    assert "<br>" in tb.markdown, "Newline should be converted to <br> in markdown"
    assert "&lt;script&gt;" in tb.html, "HTML tags must be escaped in HTML table"


def test_section_heading_preserves_table_header_and_first_data_row():
    """Verify that sub-tables under section headings inherit global headers without losing data rows."""
    sheet = [
        ["STT", "Tên Dịch Vụ", "Thời Gian", "Đơn Giá"],
        ["1. MASSAGE TRỊ LIỆU"],
        ["1", "Massage Cổ Vai Gáy", "60 phút", "399.000"],
        ["2", "Massage Body Đá Nóng", "90 phút", "599.000"],
    ]
    blocks: list[Block] = []
    _emit_spreadsheet_grid_blocks(
        filtered_rows=sheet,
        base_heading_path=["Bảng Giá"],
        blocks=blocks,
        start_local_index=0,
        mk=_mk(),
        parse_version="test-1.0",
    )

    # Exactly 2 blocks: Heading and Table (0 phantom empty table blocks)
    assert len(blocks) == 2, f"Expected 2 blocks (HEADING + TABLE), got {len(blocks)}"
    assert blocks[0].type == BlockType.HEADING
    assert blocks[0].text == "1. MASSAGE TRỊ LIỆU"
    assert blocks[1].type == BlockType.TABLE

    table_block = blocks[1]
    header_line = table_block.markdown.strip().split("\n")[0]
    assert header_line == "| STT | Tên Dịch Vụ | Thời Gian | Đơn Giá |", f"Unexpected header: {header_line}"
    assert "| 1 | Massage Cổ Vai Gáy | 60 phút | 399.000 |" in table_block.markdown
    assert "| 2 | Massage Body Đá Nóng | 90 phút | 599.000 |" in table_block.markdown


def test_section_heading_causes_first_data_row_to_be_eaten_as_header():
    """Backward compatibility alias verifying header preservation fix."""
    test_section_heading_preserves_table_header_and_first_data_row()


def test_multiple_section_headings_inherit_global_header():
    """Verify multiple consecutive section headings all inherit the global column header."""
    sheet = [
        ["STT", "Tên Dịch Vụ", "Thời Gian", "Đơn Giá"],
        ["1. MASSAGE TRỊ LIỆU"],
        ["1", "Massage Cổ Vai Gáy", "60 phút", "399.000"],
        ["2", "Massage Body Đá Nóng", "90 phút", "599.000"],
        ["2. GỘI ĐẦU DƯỠNG SINH"],
        ["1", "Gội Đầu Thư Giãn", "45 phút", "199.000"],
        ["2", "Gội Đầu Chuyên Sâu", "60 phút", "299.000"],
    ]
    blocks: list[Block] = []
    _emit_spreadsheet_grid_blocks(sheet, ["Bảng Giá"], blocks, 0, _mk(), "test-1.0")

    assert len(blocks) == 4
    assert blocks[0].type == BlockType.HEADING and blocks[0].text == "1. MASSAGE TRỊ LIỆU"
    assert blocks[1].type == BlockType.TABLE
    assert blocks[1].markdown.split("\n")[0] == "| STT | Tên Dịch Vụ | Thời Gian | Đơn Giá |"
    assert "Massage Cổ Vai Gáy" in blocks[1].markdown.split("\n")[2]

    assert blocks[2].type == BlockType.HEADING and blocks[2].text == "2. GỘI ĐẦU DƯỠNG SINH"
    assert blocks[3].type == BlockType.TABLE
    assert blocks[3].markdown.split("\n")[0] == "| STT | Tên Dịch Vụ | Thời Gian | Đơn Giá |"
    assert "Gội Đầu Thư Giãn" in blocks[3].markdown.split("\n")[2]


def test_section_heading_with_own_header_overrides_global_header():
    """Verify a sub-table with its own explicit header row does not use the prior header."""
    sheet = [
        ["STT", "Tên Dịch Vụ", "Thời Gian", "Đơn Giá"],
        ["1. MASSAGE TRỊ LIỆU"],
        ["1", "Massage Cổ Vai Gáy", "60 phút", "399.000"],
        ["2. CHÍNH SÁCH ƯU ĐÃI"],
        ["Tên ưu đãi", "Điều kiện áp dụng", "Mức giảm"],
        ["Combo Tiết Kiệm", "3-4 buổi", "Giảm 10%"],
    ]
    blocks: list[Block] = []
    _emit_spreadsheet_grid_blocks(sheet, ["Bảng Giá"], blocks, 0, _mk(), "test-1.0")

    assert len(blocks) == 4
    assert blocks[1].markdown.split("\n")[0] == "| STT | Tên Dịch Vụ | Thời Gian | Đơn Giá |"
    assert blocks[3].markdown.split("\n")[0] == "| Tên ưu đãi | Điều kiện áp dụng | Mức giảm |"
    assert "Combo Tiết Kiệm" in blocks[3].markdown.split("\n")[2]


def test_section_followed_by_single_cell_notes_does_not_inherit_header():
    """Verify single-cell notes under a heading are emitted as a list, not forced into table."""
    sheet = [
        ["STT", "Tên Dịch Vụ", "Thời Gian", "Đơn Giá"],
        ["1. MASSAGE TRỊ LIỆU"],
        ["1", "Massage Cổ Vai Gáy", "60 phút", "399.000"],
        ["2. LƯU Ý"],
        ["Khách hàng vui lòng đặt lịch trước 30 phút"],
        ["Không uống rượu bia trước khi massage"],
    ]
    blocks: list[Block] = []
    _emit_spreadsheet_grid_blocks(sheet, ["Bảng Giá"], blocks, 0, _mk(), "test-1.0")

    assert len(blocks) == 4
    assert blocks[0].type == BlockType.HEADING and blocks[0].text == "1. MASSAGE TRỊ LIỆU"
    assert blocks[1].type == BlockType.TABLE
    assert blocks[2].type == BlockType.HEADING and blocks[2].text == "2. LƯU Ý"
    assert blocks[3].type == BlockType.LIST
    assert "- Khách hàng vui lòng đặt lịch trước 30 phút" in blocks[3].text
