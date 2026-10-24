"""Unit tests for non-destructive table preservation and forward-fill in office_parser.py.

Verifies Feature F1 requirements:
1. Broadened group cell carry across category, hierarchy, and facility cells.
2. Sub-group forward-fill updates when parent group is blank.
3. Preservation of identifiers (STT, Mã SP) from false forward-fill.
4. Structural inference of grouped category cells.
5. Broadened table header row detection (_is_table_header_row).
6. Non-destructive 1-row table preservation in _emit_spreadsheet_grid_blocks.
"""

from __future__ import annotations

import pytest

from app.engines import office_parser
from app.engines.office_parser import (
    _forward_fill_grouped_table,
    _is_table_header_row,
    _emit_spreadsheet_grid_blocks,
)
from app.schema import Block, BlockType


def _make_block_factory():
    def mk(idx: int, **kwargs) -> Block:
        return Block(
            block_id=f"blk-{idx}",
            order=idx,
            page=1,
            confidence=1.0,
            bbox=(0.0, 0.0, 0.0, 0.0),
            parse_engine="office",
            **kwargs,
        )
    return mk


def test_forward_fill_grouped_table_service_categories():
    """Verify Col 0 category forward-fill for 'Danh mục' in service catalogs."""
    table = [
        ["Danh mục", "Tên dịch vụ", "Đơn giá", "Thời lượng"],
        ["Massage", "Massage cơ bản", "500.000 VNĐ", "60 phút"],
        ["", "Massage nâng cao", "650.000 VNĐ", "90 phút"],
        ["", "Massage đá nóng", "750.000 VNĐ", "90 phút"],
        ["Gội đầu dưỡng sinh", "Gội cơ bản", "150.000 VNĐ", "30 phút"],
        ["", "Gội chuyên sâu", "250.000 VNĐ", "60 phút"],
    ]

    result = _forward_fill_grouped_table(table)

    assert len(result) == 6
    assert result[0] == ["Danh mục", "Tên dịch vụ", "Đơn giá", "Thời lượng"]
    assert result[1][0] == "Massage"
    assert result[2][0] == "Massage"
    assert result[3][0] == "Massage"
    assert result[4][0] == "Gội đầu dưỡng sinh"
    assert result[5][0] == "Gội đầu dưỡng sinh"


def test_forward_fill_grouped_table_multi_level_subgroups():
    """Verify 2-level hierarchy: Col 0 (Cơ sở) and Col 1 (Tầng) both carry down.

    Crucially tests that when Col 1 changes ('Tầng 2') while Col 0 is blank,
    last_col1 updates properly and carries down to subsequent rows.
    """
    table = [
        ["Cơ sở", "Tầng", "Phòng", "Loại phòng"],
        ["Cơ sở Nàng Sen Bích Câu", "Tầng 1", "Phòng 101", "VIP"],
        ["", "", "Phòng 102", "Tiêu chuẩn"],
        ["", "Tầng 2", "Phòng 201", "VIP"],
        ["", "", "Phòng 202", "Tiêu chuẩn"],
        ["Cơ sở Nàng Sen Quận 1", "Tầng 1", "Phòng 101", "VIP"],
        ["", "", "Phòng 102", "Tiêu chuẩn"],
    ]

    result = _forward_fill_grouped_table(table)

    assert result[1] == ["Cơ sở Nàng Sen Bích Câu", "Tầng 1", "Phòng 101", "VIP"]
    assert result[2] == ["Cơ sở Nàng Sen Bích Câu", "Tầng 1", "Phòng 102", "Tiêu chuẩn"]
    assert result[3] == ["Cơ sở Nàng Sen Bích Câu", "Tầng 2", "Phòng 201", "VIP"]
    assert result[4] == ["Cơ sở Nàng Sen Bích Câu", "Tầng 2", "Phòng 202", "Tiêu chuẩn"]
    assert result[5] == ["Cơ sở Nàng Sen Quận 1", "Tầng 1", "Phòng 101", "VIP"]
    assert result[6] == ["Cơ sở Nàng Sen Quận 1", "Tầng 1", "Phòng 102", "Tiêu chuẩn"]


def test_forward_fill_grouped_table_does_not_fill_identifiers():
    """Verify sequence numbers (STT) and codes (Mã SP) are NEVER forward filled."""
    table = [
        ["STT", "Mã SP", "Tên sản phẩm", "Đơn giá"],
        ["1", "SP01", "Trà sen Tây Hồ", "120.000 VNĐ"],
        ["", "", "Bánh đậu xanh", "45.000 VNĐ"],
        ["3", "SP03", "Trà ô long", "150.000 VNĐ"],
    ]

    result = _forward_fill_grouped_table(table)

    assert result[1][0] == "1"
    assert result[1][1] == "SP01"
    # Row 2 must remain blank for STT and Mã SP
    assert result[2][0] == ""
    assert result[2][1] == ""
    assert result[2][2] == "Bánh đậu xanh"
    assert result[3][0] == "3"
    assert result[3][1] == "SP03"


def test_forward_fill_grouped_table_structural_inference():
    """Verify structural inference for custom headers not in the predefined dictionary."""
    table = [
        ["Mặt hàng kinh doanh", "Quy cách đóng gói", "Tồn kho"],
        ["Tinh dầu sả chanh", "Chai 10ml", "50"],
        ["", "Chai 50ml", "20"],
        ["", "Chai 100ml", "10"],
    ]

    result = _forward_fill_grouped_table(table)

    assert result[1][0] == "Tinh dầu sả chanh"
    assert result[2][0] == "Tinh dầu sả chanh"
    assert result[3][0] == "Tinh dầu sả chanh"


def test_is_table_header_row_broadened():
    """Verify broadened header detection recognizes diverse business table headers."""
    # Positive detections
    assert _is_table_header_row(["Danh mục", "Đơn giá", "Ghi chú"])
    assert _is_table_header_row(["Phân loại", "Chi phí"])
    assert _is_table_header_row(["Loại dịch vụ", "Tên gói"])
    assert _is_table_header_row(["Sản phẩm", "Giá niêm yết"])
    assert _is_table_header_row(["Mã SKU", "Tên hàng"])
    assert _is_table_header_row(["Khu vực", "Thời gian"])
    assert _is_table_header_row(["Gói dịch vụ", "Thời lượng"])

    # Negative detections (not table headers)
    assert not _is_table_header_row(["Khách hàng thân thiết"])
    assert not _is_table_header_row(["Dạ em chào anh", "Rất vui được tư vấn"])
    assert not _is_table_header_row([""])


def test_single_row_header_table_preserved_in_grid_blocks():
    """Verify a 1-row table containing headers is preserved as BlockType.TABLE."""
    rows = [
        ["Mã dịch vụ", "Tên dịch vụ", "Đơn giá", "Thời lượng"],
    ]
    blocks: list[Block] = []
    mk = _make_block_factory()

    _emit_spreadsheet_grid_blocks(
        filtered_rows=rows,
        base_heading_path=["Dịch Vụ"],
        blocks=blocks,
        start_local_index=0,
        mk=mk,
        parse_version="test-1.0",
    )

    assert len(blocks) == 1
    assert blocks[0].type == BlockType.TABLE
    assert "| Mã dịch vụ | Tên dịch vụ | Đơn giá | Thời lượng |" in blocks[0].markdown
    assert "<th>Mã dịch vụ</th>" in blocks[0].html


def test_side_by_side_detail_strip_joined_and_post_faq_menu_heading():
    """Verify side-by-side 'Quy trình chi tiết' strip is joined into main package rows and post-FAQ menu gets its own heading."""
    rows = [
        ["MASSAGE", "", "", "", "", "", "", ""],
        ["Cơ sở Nàng Sen Bích Câu", "Địa chỉ: 6 Bích Câu - Hà Nội", "", "", "", "", "", ""],
        ["Mã gói", "Tên gói dịch vụ", "Thời gian", "Giá (VNĐ)", "", "Mã gói", "Tên gói dịch vụ", "Giá (VNĐ)", "Quy trình chi tiết"],
        ["NS01", "Massage trị liệu cơ bản", "60 phút", "399.000VNĐ", "", "NS01", "Massage trị liệu cơ bản", "399.000 VNĐ", "• Massage full body • Tập trung cổ vai gáy, lưng, chân, tay"],
        ["", "", "", "", "", "", "", "", ""],
        ["FAQ", "", "", "", "", "", "", "", ""],
        ["Massage có đau không?", "Kỹ thuật viên điều chỉnh lực phù hợp.", "", "", "", "", "", "", ""],
        ["", "", "", "", "", "", "", "", ""],
        ["banh my bo sot vang", "", "", "", "", "", "", "", ""],
        ["trung ngại cuu", "", "", "", "", "", "", "", ""],
    ]
    blocks: list[Block] = []
    mk = _make_block_factory()

    _emit_spreadsheet_grid_blocks(
        filtered_rows=rows,
        base_heading_path=[],
        blocks=blocks,
        start_local_index=0,
        mk=mk,
        parse_version="test-2.0",
    )

    table_blocks = [b for b in blocks if b.type == BlockType.TABLE]
    list_blocks = [b for b in blocks if b.type == BlockType.LIST]

    # Main massage table should include joined "Quy trình chi tiết" column
    massage_tables = [b for b in table_blocks if b.heading_path and b.heading_path[0] == "MASSAGE" and "Bích Câu" in " ".join(b.heading_path)]
    assert len(massage_tables) == 1
    assert "Quy trình chi tiết" in massage_tables[0].markdown
    assert "cổ vai gáy, lưng, chân, tay" in massage_tables[0].markdown

    # Side strip table must have heading_path under MASSAGE, NOT FAQ
    side_tables = [b for b in table_blocks if b.heading_path == ["MASSAGE", "Quy trình chi tiết"]]
    assert len(side_tables) == 1

    # Post-FAQ menu table must preserve parent FAQ heading and subheading with restored Vietnamese food items
    menu_tables = [
        b for b in table_blocks
        if b.heading_path == ["FAQ", "Thực đơn Buffet thực dưỡng & Điểm tâm ăn nhẹ sau liệu trình"]
    ]
    assert len(menu_tables) == 1
    assert "Bánh mì bò sốt vang" in menu_tables[0].text
    assert "Trứng ngải cứu" in menu_tables[0].text

