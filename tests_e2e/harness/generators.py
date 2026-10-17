"""Synthetic document generators for .docx, .xlsx (350+ rows), and .md documents."""

from __future__ import annotations

import io
import zipfile
from typing import Any, Dict, List, Optional, Tuple


class DocumentGenerators:
    """Generates synthetic test documents with guaranteed schema compliance."""

    @staticmethod
    def generate_markdown(
        title: str = "Chính Sách Bán Hàng & Chiết Khấu Doanh Nghiệp",
        num_sections: int = 3,
        rows_per_table: int = 5,
        vietnamese: bool = True,
    ) -> str:
        """Generate structured Markdown with ATX headings, tables, bullet lists, and paragraphs."""
        lines = [
            f"# {title}",
            "",
            "Tài liệu quy định chi tiết về biểu giá, mức chiết khấu thương mại và chính sách hỗ trợ bán hàng.",
            "",
            "## 1. Phân Loại Khách Hàng & Đại Lý",
            "",
            "Hệ thống phân cấp đối tác kinh doanh được chia thành các nhóm sau:",
            "",
            "- **Đại lý Cấp 1 (Kim Cương)**: Doanh số tối thiểu 500 triệu VNĐ/tháng.",
            "- **Đại lý Cấp 2 (Vàng)**: Doanh số tối thiểu 200 triệu VNĐ/tháng.",
            "- **Đại lý Cấp 3 (Bạc)**: Doanh số tối thiểu 50 triệu VNĐ/tháng.",
            "- **Khách hàng Doanh Nghiệp (B2B)**: Hợp đồng mua sỉ số lượng lớn.",
            "",
            "## 2. Bảng Chiết Khấu Theo Doanh Số",
            "",
            "Dưới đây là tỷ lệ chiết khấu áp dụng theo doanh số thực tế:",
            "",
            "| Cấp Bậc | Doanh Số Tối Thiểu (VNĐ) | Chiết Khấu Tiêu Chuẩn | Thưởng Vượt Chỉ Tiêu |",
            "|---|---|---|---|",
        ]

        if rows_per_table > 0:
            tiers = [
                ("Cấp 1 - Kim Cương", "500,000,000", "15%", "3%"),
                ("Cấp 2 - Vàng", "200,000,000", "10%", "2%"),
                ("Cấp 3 - Bạc", "50,000,000", "5%", "1%"),
                ("Cửa Hàng Bán Lẻ", "10,000,000", "3%", "0%"),
            ]
            for i in range(rows_per_table):
                tier = tiers[i % len(tiers)]
                lines.append(f"| {tier[0]} #{i+1} | {tier[1]} | {tier[2]} | {tier[3]} |")
        lines.append("")

        lines.extend([
            "### 2.1 Quy Tắc Áp Dụng Chiết Khấu",
            "",
            "1. Chiết khấu được tính trên giá niêm yết chưa bao gồm thuế GTGT (VAT 10%).",
            "2. Đơn hàng thanh toán trong vòng 7 ngày được giảm thêm 1.5% chiết khấu thanh toán sớm.",
            "3. Không cộng dồn ưu đãi chương trình khuyến mãi tháng với chiết khấu đại lý đặc biệt.",
            "",
            "## 3. Điều Khoản Đổi Trả & Bảo Hành",
            "",
            "Sản phẩm lỗi kỹ thuật do nhà sản xuất được đổi mới trong 30 ngày kể từ ngày nhận hàng.",
            "Các trường hợp hư hỏng do vận chuyển của đối tác sẽ được bảo hiểm hỗ trợ bồi thường 100%.",
        ])

        return "\n".join(lines)

    @staticmethod
    def generate_docx_bytes(
        title: str = "Quy Chế Bán Hàng & Chính Sách Giá",
        sections: Optional[List[Tuple[str, str]]] = None,
        table_rows: Optional[List[List[str]]] = None,
    ) -> bytes:
        """Generate a valid .docx document as bytes using python-docx if available, or direct OOXML."""
        try:
            import docx

            doc = docx.Document()
            doc.add_heading(title, level=1)
            doc.add_paragraph(
                "Văn bản quy định mức giá phân phối và chiết khấu bán hàng cho đối tác toàn quốc."
            )

            if sections:
                for sec_title, sec_content in sections:
                    doc.add_heading(sec_title, level=2)
                    doc.add_paragraph(sec_content)
            else:
                doc.add_heading("1. Chính sách giá đại lý", level=2)
                doc.add_paragraph(
                    "Đại lý cấp 1 được áp dụng chiết khấu 15% trên giá niêm yết bán buôn."
                )
                doc.add_heading("2. Điều kiện thanh toán", level=2)
                doc.add_paragraph(
                    "Thanh toán ngay khi đặt hàng được giảm thêm 2% trên tổng giá trị đơn hàng."
                )

            if table_rows:
                table = doc.add_table(rows=len(table_rows), cols=len(table_rows[0]))
                for r_idx, row in enumerate(table_rows):
                    for c_idx, val in enumerate(row):
                        table.cell(r_idx, c_idx).text = str(val)
            else:
                table = doc.add_table(rows=3, cols=3)
                headers = ["Mã SP", "Tên Sản Phẩm", "Đơn Giá (VNĐ)"]
                for c_idx, h in enumerate(headers):
                    table.cell(0, c_idx).text = h
                sample_data = [
                    ["SP-001", "Gói Dịch Vụ Doanh Nghiệp Gold", "25,000,000"],
                    ["SP-002", "Gói Phần Mềm Quản Trị ERP Lite", "45,000,000"],
                ]
                for r_idx, row in enumerate(sample_data, start=1):
                    for c_idx, val in enumerate(row):
                        table.cell(r_idx, c_idx).text = val

            buf = io.BytesIO()
            doc.save(buf)
            return buf.getvalue()
        except ImportError:
            # Fallback: create a valid zip-based OpenXML docx
            buf = io.BytesIO()
            with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
                zf.writestr(
                    "[Content_Types].xml",
                    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                    '<Default Extension="xml" ContentType="application/xml"/>'
                    '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
                    "</Types>",
                )
                zf.writestr(
                    "_rels/.rels",
                    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
                    "</Relationships>",
                )
                zf.writestr(
                    "word/document.xml",
                    f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                    f'<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
                    f"<w:body>"
                    f"<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>{title}</w:t></w:r></w:p>"
                    f'<w:p><w:r><w:t>Quy chế bán hàng và chiết khấu đại lý năm 2026.</w:t></w:r></w:p>'
                    f"</w:body></w:document>",
                )
            return buf.getvalue()

    @staticmethod
    def generate_xlsx_bytes(
        sheet_configs: Optional[List[Dict[str, Any]]] = None,
        rows_per_sheet: int = 350,
        trailing_empty_rows: int = 0,
        trailing_empty_cols: int = 0,
    ) -> bytes:
        """Generate a valid .xlsx workbook with >350 rows across sheets, optional trailing empty rows/cols."""
        try:
            import openpyxl

            wb = openpyxl.Workbook()
            # Remove default active sheet if sheets config is given
            if sheet_configs:
                wb.remove(wb.active)
                for config in sheet_configs:
                    title = config.get("title", "Sheet")
                    ws = wb.create_sheet(title=title)
                    headers = config.get("headers", ["Mã Hàng", "Tên Sản Phẩm", "Đơn Giá", "Tồn Kho", "Nhóm Hàng"])
                    ws.append(headers)
                    count = config.get("rows", rows_per_sheet)
                    prefix = config.get("prefix", "SP")
                    category = config.get("category", "Tiêu Dùng")
                    for i in range(1, count + 1):
                        row = [
                            f"{prefix}-{i:04d}",
                            f"Sản phẩm {prefix} Model {i}",
                            100000 + (i * 5000),
                            50 + (i % 200),
                            category,
                        ]
                        # append trailing empty cells if configured
                        for _ in range(trailing_empty_cols):
                            row.append(None)
                        ws.append(row)
                    # append trailing empty rows
                    for _ in range(trailing_empty_rows):
                        ws.append([None] * (len(headers) + trailing_empty_cols))
            else:
                # Default 2 sheets: 1st with rows_per_sheet (e.g. 350), 2nd with 100 rows
                ws1 = wb.active
                ws1.title = "Kho Bán Buôn"
                headers = ["Mã Hàng", "Tên Sản Phẩm", "Đơn Giá (VNĐ)", "Tồn Kho", "Đơn Vị"]
                ws1.append(headers)
                for i in range(1, rows_per_sheet + 1):
                    ws1.append([
                        f"BB-{i:04d}",
                        f"Thiết bị điện tử Model X-{i}",
                        2500000 + (i * 10000),
                        100 + (i % 50),
                        "Chiếc",
                    ])
                for _ in range(trailing_empty_rows):
                    ws1.append([None] * len(headers))

                ws2 = wb.create_sheet(title="Phụ Kiện")
                ws2.append(["Mã PK", "Tên Phụ Kiện", "Đơn Giá (VNĐ)", "Bảo Hành (Tháng)"])
                for i in range(1, 101):
                    ws2.append([
                        f"PK-{i:03d}",
                        f"Cáp sạc & Củ sạc Type-C v{i}",
                        150000 + (i * 2000),
                        12,
                    ])

            buf = io.BytesIO()
            wb.save(buf)
            return buf.getvalue()
        except ImportError:
            # Fallback zip representation
            buf = io.BytesIO()
            with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
                zf.writestr(
                    "[Content_Types].xml",
                    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                    '<Default Extension="xml" ContentType="application/xml"/>'
                    '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
                    "</Types>",
                )
                zf.writestr(
                    "_rels/.rels",
                    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
                    "</Relationships>",
                )
                zf.writestr(
                    "xl/workbook.xml",
                    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                    '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
                    '<sheets><sheet name="SalesData" sheetId="1" r:id="rId1"/></sheets>'
                    "</workbook>",
                )
            return buf.getvalue()
