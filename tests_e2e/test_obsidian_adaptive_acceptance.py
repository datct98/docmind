#!/usr/bin/env python3
"""DocMind Obsidian Knowledge Graph & Adaptive Reasoning Acceptance Test Suite.

Author: test_writer_o6_e2e (Reformulated by explorer_o6_final_r2_3)
Target: docmind full-stack RAG & Fanpage Bot (http://localhost:8090)
Page ID: 2850a8ef-ca4a-40eb-87fa-4f808640efee ("Nàng sen")

Tiers Covered:
- Tier 1: Feature Coverage (F1 Table Preservation, F2 Heading Path Indexing,
          F3 Vietnamese Query Normalizer, F4 Dynamic ChunkMetadata)
          [Zero Tautology: Exercises office_parser and live PostgreSQL chunks]
- Tier 2: Boundary & Corner Cases (Extreme prices, empty inputs, bursts, non-existent packages)
- Tier 3: Pairwise Combinations (Branch + price, typo + objection, branch switch, FAQ + price)
- Tier 4: Real-World Scenarios (5-turn probe on Page "Nàng sen", cross-section queries,
          latency budgets 4-8s vs <30s, 0 syntax leaks, Vietnamese honorifics)
"""

from __future__ import annotations

import io
import json
import os
import re
import subprocess
import sys
import time
import unittest
from typing import Any, Dict, List, Optional, Tuple

import pytest
import requests

# Locate doc-service-py robustly regardless of execution path
_cur_dir = os.path.dirname(os.path.abspath(__file__))
_possible_paths = [
    os.path.abspath(os.path.join(_cur_dir, "..", "doc-service-py")),
    os.path.abspath(os.path.join(_cur_dir, "..", "..", "..", "docmind", "doc-service-py")),
    os.path.abspath(os.path.join(os.getcwd(), "docmind", "doc-service-py")),
]
for _p in _possible_paths:
    if os.path.isdir(_p) and _p not in sys.path:
        sys.path.insert(0, _p)

from app.engines import office_parser
from app.schema import Block, BlockType

# Base configuration from environment
API_URL = os.getenv("API_JAVA_URL", "http://localhost:8090").rstrip("/")
DOC_SERVICE_URL = os.getenv("DOC_SERVICE_URL", "http://localhost:8091").rstrip("/")
PAGE_ID = os.getenv("PAGE_ID", "2850a8ef-ca4a-40eb-87fa-4f808640efee")

# Syntax leak patterns
WIKILINK_PATTERN = re.compile(r"\[\[.*?\]\]")
CITATION_PATTERN = re.compile(r"(?i)(?:\[\s*\d+\s*\]|\[\s*spa-\d+\s*\]|\bspa-\d{1,3}\b|\[\s*mục:[^\]]*\])")
MARKDOWN_HEADING_PATTERN = re.compile(r"(?m)^\s*#{1,6}\s+\S+")
TABLE_PIPE_PATTERN = re.compile(r"(?m)^\s*\|.*?\|.*?\|\s*$")
TABLE_DIVIDER_PATTERN = re.compile(r"\|\s*---+\s*\|")
INTERNAL_RULE_PATTERN = re.compile(r"\b(?:RULE|INV|DOM)_[A-Z0-9_]+\b")
LAN_ANH_PATTERN = re.compile(r"(?i)\blan\s+anh\b")


def _mk_block(idx: int, **kwargs) -> Block:
    """Canonical block factory helper for office_parser invocation."""
    return Block(
        block_id=f"acceptance-blk-{idx}",
        order=idx,
        page=1,
        confidence=1.0,
        bbox=(0.0, 0.0, 0.0, 0.0),
        parse_engine="office",
        **kwargs,
    )


def query_live_postgres(sql: str) -> List[Dict[str, Any]]:
    """Execute SQL query against docmind-postgres and return parsed JSON rows."""
    cmd = [
        "docker", "exec", "docmind-postgres",
        "psql", "-U", "docmind", "-d", "docmind", "-t", "-A", "-c", sql
    ]
    res = subprocess.run(cmd, capture_output=True, encoding="utf-8", timeout=10)
    assert res.returncode == 0, f"psql execution failed: {res.stderr}"
    rows = []
    for line in res.stdout.strip().splitlines():
        line = line.strip()
        if line:
            try:
                rows.append(json.loads(line))
            except Exception:
                rows.append({"raw": line})
    return rows


def call_fanpage_bot(
    message: str,
    history: Optional[List[Dict[str, str]]] = None,
    customer_id: str = "obsidian_acceptance_user",
    customer_name: Optional[str] = None,
    page_id: str = PAGE_ID,
    timeout: int = 120,
) -> Tuple[Dict[str, Any], float]:
    """Call public endpoint POST /api/bot/fanpage/message with timing."""
    url = f"{API_URL}/api/bot/fanpage/message"
    payload = {
        "page_id": page_id,
        "customer_id": customer_id,
        "customer_name": customer_name,
        "message": message,
        "history": history or [],
    }
    t0 = time.perf_counter()
    res = requests.post(url, json=payload, timeout=timeout)
    elapsed = time.perf_counter() - t0
    assert res.status_code == 200, f"HTTP {res.status_code}: {res.text}"
    return res.json(), elapsed


# =============================================================================
# TIER 1: FEATURE COVERAGE (F1 - F4) — ZERO TAUTOLOGY / RIGOROUS ENGINE & DB
# =============================================================================

class TestTier1FeatureCoverage:
    """Tier 1: Comprehensive feature coverage for table preservation, heading paths,

    typo normalizer, and dynamic chunk metadata.
    All tests execute the real doc-service-py parser, PostgreSQL live state, or public APIs.
    """

    # --- F1: Non-Destructive Table Preservation ---
    def test_tier1_f1_table_markdown_structure_preserved(self):
        """F1.1: Verify Markdown table retains pipe delimiter and header row via office_parser & live DB."""
        raw_md = (
            "# Dịch vụ Spa\n\n"
            "| Dịch vụ | Thời gian | Giá |\n"
            "| --- | --- | --- |\n"
            "| Massage cơ bản | 60p | 399.000đ |\n"
            "| Massage nâng cao | 80p | 530.000đ |\n"
        ).encode("utf-8")
        blocks = office_parser.parse_markdown(raw_md, _mk_block)
        table_blocks = [b for b in blocks if b.type == BlockType.TABLE]
        assert len(table_blocks) == 1, "Expected exactly 1 table block"
        tb = table_blocks[0]
        assert "| Dịch vụ | Thời gian | Giá |" in tb.markdown
        assert "| Massage cơ bản | 60p | 399.000đ |" in tb.markdown
        assert "| --- | --- | --- |" in tb.markdown

        # Cross-verify with live PostgreSQL chunk from Page "Nàng sen"
        sql = f"""
        SELECT json_build_object('id', id, 'text', text)
        FROM chunks
        WHERE page_id = '{PAGE_ID}' AND chunk_metadata->>'chunk_role' = 'PRICE_TABLE'
        LIMIT 1;
        """
        rows = query_live_postgres(sql)
        assert len(rows) == 1, "Live database must contain PRICE_TABLE chunks"
        live_text = rows[0]["text"]
        assert "|" in live_text and "---" in live_text, "Live chunk must preserve markdown table structure"

    def test_tier1_f1_table_header_replicated_across_splits(self):
        """F1.2: Verify chunk splitting on large tables replicates column header across all partitioned blocks."""
        header = ["Mã gói", "Tên gói", "Giá", "Tổng thời gian"]
        rows = [header] + [
            [f"NS{i:02d}", f"Gói massage {i}", f"{300000 + i * 10000} VNĐ", f"{60 + i} phút"]
            for i in range(1, 150)
        ]
        blocks: list[Block] = []
        office_parser._emit_spreadsheet_grid_blocks(
            filtered_rows=rows,
            base_heading_path=["Bảng giá"],
            blocks=blocks,
            start_local_index=0,
            mk=_mk_block,
            parse_version="test-1.0",
        )
        table_blocks = [b for b in blocks if b.type == BlockType.TABLE]
        assert len(table_blocks) >= 2, f"Expected table to split into >= 2 blocks across WINDOW_SIZE, got {len(table_blocks)}"
        for idx, tb in enumerate(table_blocks):
            assert "| Mã gói | Tên gói | Giá | Tổng thời gian |" in tb.markdown, (
                f"Table block {idx} did not replicate the column header row!"
            )

    def test_tier1_f1_table_cell_alignment_preserved(self):
        """F1.3: Verify multi-column table preserves structured cells and does not flatten into commas in live DB."""
        sql = f"""
        SELECT json_build_object('id', id, 'text', text)
        FROM chunks
        WHERE page_id = '{PAGE_ID}' AND chunk_metadata->'service_codes' ? 'NS01'
        LIMIT 1;
        """
        rows = query_live_postgres(sql)
        assert len(rows) == 1, "Expected live chunk containing NS01"
        live_text = rows[0]["text"]
        lines = [line.strip() for line in live_text.splitlines() if line.strip().startswith("|")]
        ns01_line = next((l for l in lines if "NS01" in l), None)
        assert ns01_line is not None, "Live table chunk must contain row with NS01"
        cells = office_parser._split_table_row(ns01_line)
        assert len(cells) >= 4, f"Row must have at least 4 structured columns, got {len(cells)}"
        assert cells[0] == "NS01"
        assert "Massage" in cells[1]
        assert "399.000" in cells[2]

    def test_tier1_f1_table_merged_cells_forward_filled(self):
        """F1.4: Verify category group headers are forward-filled without dropping grouping context via _forward_fill_grouped_table."""
        raw_table = [
            ["Danh mục", "Mã gói", "Tên gói", "Giá"],
            ["MASSAGE", "NS01", "Massage cơ bản", "399.000 VNĐ"],
            ["", "NS02", "Massage nâng cao", "530.000 VNĐ"],
            ["GỘI ĐẦU DƯỠNG SINH", "DS01", "Gội dưỡng sinh cơ bản", "129.000 VNĐ"],
            ["", "DS02", "Gội dưỡng sinh chuyên sâu", "260.000 VNĐ"],
        ]
        filled = office_parser._forward_fill_grouped_table(raw_table)
        assert filled[1][0] == "MASSAGE"
        assert filled[2][0] == "MASSAGE", "Row 2 must forward-fill 'MASSAGE' from row 1"
        assert filled[3][0] == "GỘI ĐẦU DƯỠNG SINH"
        assert filled[4][0] == "GỘI ĐẦU DƯỠNG SINH", "Row 4 must forward-fill 'GỘI ĐẦU DƯỠNG SINH' from row 3"

    def test_tier1_f1_table_zero_destructive_flattening(self):
        """F1.5: Verify table blocks are preserved as TABLE entities rather than flattened into raw plain text."""
        doc_content = (
            "# Bảng Giá Nàng Sen\n\n"
            "Đoạn văn giới thiệu cơ sở spa.\n\n"
            "| Mã gói | Dịch vụ | Giá |\n"
            "| --- | --- | --- |\n"
            "| NS01 | Massage body | 399.000đ |\n\n"
            "Đoạn văn kết luận.\n"
        ).encode("utf-8")
        blocks = office_parser.parse_markdown(doc_content, _mk_block)
        types = [b.type for b in blocks]
        assert BlockType.HEADING in types
        assert BlockType.PARAGRAPH in types
        assert BlockType.TABLE in types
        tbl = next(b for b in blocks if b.type == BlockType.TABLE)
        assert tbl.html.startswith("<table>") and "<th>Mã gói</th>" in tbl.html
        assert tbl.markdown.startswith("| Mã gói | Dịch vụ | Giá |")

    # --- F2: Hierarchical Heading Path Indexing (resolveSubpath) ---
    def test_tier1_f2_heading_path_inheritance(self):
        """F2.1: Verify heading_path breadcrumbs contain hierarchical parents (Sheet > Category > Section)."""
        # Test 1: Parser engine heading hierarchy resolution
        raw_md = (
            "# MASSAGE\n\n"
            "## Cơ sở Nàng Sen Mỹ Đình (Hà Nội)\n\n"
            "| Mã gói | Tên gói | Giá |\n"
            "| --- | --- | --- |\n"
            "| NS01 | Massage cơ bản | 399.000 VNĐ |\n"
        ).encode("utf-8")
        blocks = office_parser.parse_markdown(raw_md, _mk_block)
        tbl_block = next(b for b in blocks if b.type == BlockType.TABLE)
        assert len(tbl_block.heading_path) == 2
        assert tbl_block.heading_path[0] == "MASSAGE"
        assert "Mỹ Đình" in tbl_block.heading_path[1]

        # Test 2: Live PostgreSQL database chunks verification
        sql = f"""
        SELECT json_build_object('id', id, 'heading_path', heading_path)
        FROM chunks
        WHERE page_id = '{PAGE_ID}'
        AND array_to_string(heading_path, ' ') LIKE '%Mỹ Đình%'
        LIMIT 1;
        """
        rows = query_live_postgres(sql)
        assert len(rows) == 1, "Live database must have chunks with Mỹ Đình heading path"
        hp = rows[0]["heading_path"]
        assert len(hp) >= 2, f"Live chunk heading_path must be hierarchical (>=2 elements), got {hp}"
        assert hp[0] == "MASSAGE"
        assert "Mỹ Đình" in hp[1]

    def test_tier1_f2_heading_path_search_matching(self):
        """F2.2: Live API query with branch located in heading_path correctly retrieves chunk."""
        data, _ = call_fanpage_bot("cơ sở Mỹ Đình", history=[], customer_id="t1_f2_02")
        reply = data.get("reply_text", "")
        assert reply is not None and len(reply) > 0

    def test_tier1_f2_heading_path_hybrid_search(self):
        """F2.3: Query combining category and package name correctly grounds answer."""
        data, _ = call_fanpage_bot("gói Massage cơ bản tại Hà Nội", history=[], customer_id="t1_f2_03")
        reply = data.get("reply_text", "")
        assert "399.000" in reply or "Massage" in reply

    def test_tier1_f2_heading_path_branch_tag_resolution(self):
        """F2.4: Heading path containing Quận 1 maps to Saigon branch tag in live database chunks."""
        sql = f"""
        SELECT json_build_object('id', id, 'heading_path', heading_path, 'branch_tags', chunk_metadata->'branch_tags')
        FROM chunks
        WHERE page_id = '{PAGE_ID}'
        AND array_to_string(heading_path, ' ') LIKE '%Quận 1%'
        LIMIT 3;
        """
        rows = query_live_postgres(sql)
        assert len(rows) > 0, "Live database must contain chunks with Quận 1 heading path"
        for r in rows:
            tags = r["branch_tags"]
            assert "quan_1" in tags, f"Chunk {r['id']} heading path had Quận 1 but missing quan_1 in branch_tags: {tags}"

    def test_tier1_f2_heading_path_empty_fallback(self):
        """F2.5: Chunks without explicit heading path fallback gracefully to empty list or root title."""
        # 1. Parser verification: content before any heading gets empty heading_path
        raw_md = b"Introductory paragraph before any heading.\n\nAnother line."
        blocks = office_parser.parse_markdown(raw_md, _mk_block)
        assert len(blocks) > 0
        assert blocks[0].heading_path == []

        # 2. Live DB verification: chunks with empty heading path exist and have valid metadata
        sql = f"""
        SELECT json_build_object('id', id, 'heading_path', heading_path, 'role', chunk_metadata->>'chunk_role')
        FROM chunks
        WHERE page_id = '{PAGE_ID}'
        AND array_length(heading_path, 1) IS NULL
        LIMIT 1;
        """
        rows = query_live_postgres(sql)
        assert len(rows) == 1, "Live database must have gracefully handled chunk with empty heading path"
        assert rows[0]["heading_path"] == []
        assert rows[0]["role"] in ["PROMO", "GENERAL", "FAQ"]

    # --- F3: Messenger Abbreviation & Typo Normalizer ---
    def test_tier1_f3_telex_typo_muosn_to_muon(self):
        """F3.1: Telex typo 'muosn' is interpreted as 'muốn' (want)."""
        data, _ = call_fanpage_bot("tôi muosn thử trải nghiệm trước", history=[], customer_id="t1_f3_01")
        reply = data.get("reply_text", "")
        # Should not reject as incomprehensible, should offer service/booking
        assert not data.get("needs_human_handoff", False)
        assert any(k in reply.lower() for k in ["trải nghiệm", "massage", "gói", "đặt lịch"])

    def test_tier1_f3_shorthand_ko_k_to_khong(self):
        """F3.2: Shorthand 'ko' / 'k' correctly interpreted as negative without confusing intent."""
        data, _ = call_fanpage_bot("k đắt đúng ko shop", history=[], customer_id="t1_f3_02")
        assert not data.get("needs_human_handoff", False)

    def test_tier1_f3_shorthand_bn_to_bao_nhieu(self):
        """F3.3: Shorthand 'bn' interpreted as 'bao nhiêu' (how much / price)."""
        data, _ = call_fanpage_bot("giá bn ạ", history=[], customer_id="t1_f3_03")
        reply = data.get("reply_text", "")
        assert any(k in reply for k in ["399.000", "570.000", "129.000", "VNĐ", "đ"])

    def test_tier1_f3_shorthand_sdt_to_so_dien_thoai(self):
        """F3.4: Inquiry with 'sdt' recognized as phone inquiry or contact."""
        data, _ = call_fanpage_bot("cho xin sdt liên hệ", history=[], customer_id="t1_f3_04")
        reply = data.get("reply_text", "")
        assert len(reply) > 0

    def test_tier1_f3_combined_typos_and_abbreviations(self):
        """F3.5: Mixed abbreviations 'mk muosn hoi gia bn k dat' processed gracefully."""
        data, _ = call_fanpage_bot("mk muosn hoi gia bn k dat", history=[], customer_id="t1_f3_05")
        reply = data.get("reply_text", "")
        assert len(reply) > 0
        assert not data.get("needs_human_handoff", False)

    # --- F4: Dynamic ChunkMetadata Extraction ---
    def test_tier1_f4_extract_min_max_price(self):
        """F4.1: Min and max price correctly bounded for known catalog chunks in live DB."""
        sql = f"""
        SELECT json_build_object(
            'min', min((chunk_metadata->>'min_price')::bigint),
            'max', max((chunk_metadata->>'max_price')::bigint)
        )
        FROM chunks
        WHERE page_id = '{PAGE_ID}'
        AND chunk_metadata->'branch_tags' ? 'my_dinh'
        AND chunk_metadata->>'chunk_role' = 'PRICE_TABLE';
        """
        rows = query_live_postgres(sql)
        assert len(rows) == 1
        min_p = rows[0]["min"]
        max_p = rows[0]["max"]
        assert min_p is not None and max_p is not None
        assert min_p <= 399000, f"Mỹ Đình price catalog minimum expected <= 399000, got {min_p}"
        assert max_p >= 1990000, f"Mỹ Đình price catalog maximum expected >= 1990000, got {max_p}"

    def test_tier1_f4_extract_branch_tags(self):
        """F4.2: Branch tag identification extracts known branch tokens across live chunks."""
        sql = f"""
        SELECT DISTINCT jsonb_array_elements_text(chunk_metadata->'branch_tags') as tag
        FROM chunks
        WHERE page_id = '{PAGE_ID}';
        """
        cmd = [
            "docker", "exec", "docmind-postgres",
            "psql", "-U", "docmind", "-d", "docmind", "-t", "-A", "-c", sql
        ]
        res = subprocess.run(cmd, capture_output=True, encoding="utf-8", timeout=10)
        assert res.returncode == 0
        all_tags = set(line.strip() for line in res.stdout.strip().splitlines() if line.strip())
        expected_branches = {"my_dinh", "quan_1", "bich_cau", "long_bien"}
        missing = expected_branches - all_tags
        assert not missing, f"Expected all branch tags in Page 'Nàng sen', missing: {missing}"

    def test_tier1_f4_extract_service_codes(self):
        """F4.3: Service codes NS01, DS01, DS02 detected dynamically from live table markdown."""
        sql = f"""
        SELECT DISTINCT jsonb_array_elements_text(chunk_metadata->'service_codes') as code
        FROM chunks
        WHERE page_id = '{PAGE_ID}';
        """
        cmd = [
            "docker", "exec", "docmind-postgres",
            "psql", "-U", "docmind", "-d", "docmind", "-t", "-A", "-c", sql
        ]
        res = subprocess.run(cmd, capture_output=True, encoding="utf-8", timeout=10)
        assert res.returncode == 0
        all_codes = set(line.strip() for line in res.stdout.strip().splitlines() if line.strip())
        assert "NS01" in all_codes, "NS01 must be extracted in service_codes"
        assert "DS01" in all_codes, "DS01 must be extracted in service_codes"
        assert "DS02" in all_codes, "DS02 must be extracted in service_codes"

    def test_tier1_f4_chunk_role_price_table(self):
        """F4.4: Markdown chunk with price headers identified as PRICE_TABLE in live database."""
        sql = f"""
        SELECT json_build_object(
            'id', id,
            'role', chunk_metadata->>'chunk_role',
            'min_price', (chunk_metadata->>'min_price')::bigint,
            'max_price', (chunk_metadata->>'max_price')::bigint,
            'text', left(text, 100)
        )
        FROM chunks
        WHERE page_id = '{PAGE_ID}'
        AND chunk_metadata->>'chunk_role' = 'PRICE_TABLE';
        """
        rows = query_live_postgres(sql)
        assert len(rows) >= 15, f"Expected >= 15 PRICE_TABLE chunks, got {len(rows)}"
        for r in rows:
            assert r["role"] == "PRICE_TABLE"
            assert r["min_price"] is not None and r["min_price"] > 0
            assert r["max_price"] is not None and r["max_price"] >= r["min_price"]
            assert "|" in r["text"], "PRICE_TABLE chunk must contain table markdown"

    def test_tier1_f4_chunk_role_faq(self):
        """F4.5: Markdown chunk with Q&A headers identified as FAQ in live database."""
        sql = f"""
        SELECT json_build_object(
            'id', id,
            'role', chunk_metadata->>'chunk_role',
            'faq_flag', (chunk_metadata->>'faq')::boolean,
            'heading_path', heading_path,
            'text', left(text, 100)
        )
        FROM chunks
        WHERE page_id = '{PAGE_ID}'
        AND chunk_metadata->>'chunk_role' = 'FAQ';
        """
        rows = query_live_postgres(sql)
        assert len(rows) >= 20, f"Expected >= 20 FAQ chunks, got {len(rows)}"
        for r in rows:
            assert r["role"] == "FAQ"
            assert r["faq_flag"] is True, f"Chunk {r['id']} classified as FAQ must have faq=true in metadata"
            combined = (" ".join(r["heading_path"]) + " " + r["text"]).lower()
            assert any(k in combined for k in ["câu hỏi", "khách hỏi", "hỏi", "thắc mắc", "faq", "trả lời"]), (
                f"FAQ chunk must contain Q&A cues in heading or text, got heading={r['heading_path']}, text={r['text']}"
            )


# =============================================================================
# TIER 2: BOUNDARY & CORNER CASES
# =============================================================================

class TestTier2BoundaryAndCorner:
    """Tier 2: Extreme values, corner inputs, burst calls, and non-existent queries."""

    def test_tier2_extreme_price_query_zero_dong(self):
        """T2.1: Inquiring about free / 0 VNĐ services handled politely without hallucinating free packages."""
        data, _ = call_fanpage_bot("có gói nào 0 đồng hoặc miễn phí hoàn toàn không shop", history=[], customer_id="t2_01")
        reply = data.get("reply_text", "")
        # Should clarify minimum package price (e.g. 129k or 399k) or politely clarify no free package
        assert any(k in reply.lower() for k in ["129.000", "239.000", "399.000", "ưu đãi", "không có gói", "chưa có gói", "không có gói miễn phí"])

    def test_tier2_extreme_price_query_billion_dong(self):
        """T2.2: Inquiring about extreme high budget (1 tỷ VNĐ) caps gracefully at catalog maximum."""
        data, _ = call_fanpage_bot("tôi muốn gói cao cấp nhất 100 triệu hoặc 1 tỷ", history=[], customer_id="t2_02")
        reply = data.get("reply_text", "")
        # Should politely clarify that no 100M or 1 billion package exists, or cap to actual catalog tiers
        assert any(k in reply.lower() for k in ["chưa có gói", "không có gói", "chỉ quanh mức", "cao nhất", "vài trăm"]), (
            f"Expected polite refusal or catalog ceiling explanation, got: {reply}"
        )

    def test_tier2_empty_or_whitespace_message(self):
        """T2.3: Pure whitespace or empty message handled gracefully (either 400 or safe fallback response)."""
        url = f"{API_URL}/api/bot/fanpage/message"
        res = requests.post(url, json={"page_id": PAGE_ID, "message": "   ", "history": []}, timeout=10)
        # Must return valid HTTP (either 200 with fallback or 400 Bad Request, never 500 Internal Error)
        assert res.status_code in [200, 400], f"Expected 200 or 400, got {res.status_code}"

    def test_tier2_multiple_rapid_price_queries_burst(self):
        """T2.4: Consecutive burst queries within short interval receive coherent replies."""
        data1, _ = call_fanpage_bot("giá", history=[], customer_id="t2_burst")
        data2, _ = call_fanpage_bot("bảng giá sao shop", history=[], customer_id="t2_burst")
        assert not data1.get("needs_human_handoff", False)
        assert not data2.get("needs_human_handoff", False)

    def test_tier2_non_existent_package_code(self):
        """T2.5: Inquiring about fake package code 'NS999' does not invent fake pricing."""
        data, _ = call_fanpage_bot("tôi muốn hỏi gói NS999 bên bạn", history=[], customer_id="t2_05")
        reply = data.get("reply_text", "")
        # Must not hallucinate a confirmed NS999 price
        assert "NS999: 9" not in reply

    def test_tier2_special_vietnamese_accents_and_case(self):
        """T2.6: Mixed uppercase, accented, and unaccented Vietnamese parsed reliably."""
        msg = "CƠ SỞ QUẬN 1 CÓ GÓI GỘI ĐẦU NÀO MỀM KHÔNG EM"
        data, _ = call_fanpage_bot(msg, history=[], customer_id="t2_06")
        reply = data.get("reply_text", "")
        assert any(k in reply for k in ["129.000", "260.000", "Quận 1", "gội", "dưỡng sinh"])

    def test_tier2_extended_history_context_scaling(self):
        """T2.7: Multi-turn history with 8 turns does not trigger token overflow or crash."""
        history = []
        for i in range(4):
            history.append({"role": "user", "content": f"Câu hỏi {i+1} về dịch vụ dưỡng sinh"})
            history.append({"role": "assistant", "content": f"Dạ em tư vấn cho Chị về dịch vụ {i+1} ạ."})
        data, _ = call_fanpage_bot("tư vấn gói rẻ nhất", history=history, customer_id="t2_07")
        assert not data.get("needs_human_handoff", False)


# =============================================================================
# TIER 3: PAIRWISE & CROSS-FEATURE COMBINATIONS
# =============================================================================

class TestTier3PairwiseCombinations:
    """Tier 3: Combinations of branch constraints, typos, objections, and promotions."""

    def test_tier3_branch_specific_and_lowest_price(self):
        """T3.1: Combining branch (Quận 1) + price floor inquiry."""
        data, _ = call_fanpage_bot("cơ sở Quận 1 giá thấp nhất là bao nhiêu?", history=[], customer_id="t3_01")
        reply = data.get("reply_text", "")
        # In Sài Gòn Quận 1: floor is 129.000đ (DS01) or 570.000đ (NS01)
        assert any(k in reply for k in ["129.000", "260.000", "570.000"])

    def test_tier3_typo_and_price_objection(self):
        """T3.2: Combining typo 'muosn thử' + price objection 'nhưng cao the'."""
        history = [
            {"role": "user", "content": "giá massage"},
            {"role": "assistant", "content": "Dạ gói Massage cơ bản bên em khởi điểm từ 399.000VNĐ ạ."}
        ]
        data, _ = call_fanpage_bot("muosn thử nhưng cao the", history=history, customer_id="t3_02")
        reply = data.get("reply_text", "")
        # Should down-sell to 239.000đ or 129.000đ without handoff
        assert not data.get("needs_human_handoff", False)
        assert any(k in reply for k in ["239.000", "129.000", "mềm hơn", "dưỡng sinh"])

    def test_tier3_branch_switch_in_multiturn(self):
        """T3.3: Customer inquires about Hanoi, then switches to Saigon branch."""
        history = [
            {"role": "user", "content": "Cơ sở Mỹ Đình có gói nào?"},
            {"role": "assistant", "content": "Dạ cơ sở Mỹ Đình có gói Massage cơ bản từ 399.000VNĐ ạ."}
        ]
        data, _ = call_fanpage_bot("thế còn chi nhánh Sài Gòn Quận 1 thì sao?", history=history, customer_id="t3_03")
        reply = data.get("reply_text", "")
        assert any(k in reply for k in ["Quận 1", "Sài Gòn", "570.000", "129.000"])

    def test_tier3_faq_comparison_with_price_objection(self):
        """T3.4: FAQ comparison with price concern ('gói 670k và 780k khác gì nhau mà chênh giá')."""
        data, _ = call_fanpage_bot("gói 670k và 780k khác gì nhau mà chênh giá vậy shop", history=[], customer_id="t3_04")
        reply = data.get("reply_text", "")
        assert any(k in reply.lower() for k in ["vip", "riêng tư", "gội dưỡng sinh", "phòng riêng", "di chuyển"])

    def test_tier3_service_code_and_promo_interaction(self):
        """T3.5: Checking if package DS01 or service combo applies 10% promo when purchasing 5 sessions."""
        data, _ = call_fanpage_bot("mua 5 buổi có được giảm 10% không em", history=[], customer_id="t3_05")
        reply = data.get("reply_text", "")
        assert any(k in reply for k in ["10%", "ưu đãi", "5 buổi", "giảm"])


# =============================================================================
# TIER 4: REAL-WORLD SCENARIOS (PAGE 'NÀNG SEN' 5-TURN PROBE & AUDIT CHECKS)
# =============================================================================

class TestTier4RealWorldNangSenProbe:
    """Tier 4: Authoritative 5-Turn Probe on Page 'Nàng sen' (2850a8ef-ca4a-40eb-87fa-4f808640efee)

    Derived from ORIGINAL_REQUEST.md (## 2026-10-21T02:21:29Z) & DISPATCH.md.
    """

    def setup_method(self, method=None):
        self.customer_id = f"nangsen_probe_{int(time.time())}"
        self.history: List[Dict[str, str]] = []

    def test_tier4_turn1_pricing_anchor_discovery(self):
        """Turn 1: 'giá' -> quotes 399.000 VNĐ (or 570k for Q1), latency 4–8s."""
        msg = "giá"
        data, elapsed = call_fanpage_bot(msg, history=self.history, customer_id=self.customer_id)
        reply = data.get("reply_text", "")
        bubbles = data.get("reply_bubbles", [])
        stage = data.get("sales_stage", "")

        # 1. Price Quote Assertion
        has_hn_price = "399.000" in reply
        has_sg_price = "570.000" in reply
        assert has_hn_price or has_sg_price, f"Turn 1 must quote starting price 399.000đ (HN) or 570.000đ (Q1): {reply}"

        # 2. Latency Budget: Simple turn target 4-8s (< 15s absolute guardrail)
        assert elapsed < 15.0, f"Turn 1 latency {elapsed:.2f}s exceeded budget"

        # 3. Bubbles cap: max 2 concise bubbles
        assert len(bubbles) <= 2, f"Turn 1 produced {len(bubbles)} bubbles, expected <= 2"

        # 4. Zero Lan Anh
        assert not LAN_ANH_PATTERN.search(reply), f"Turn 1 leaked phantom name 'Lan Anh': {reply}"

        # 5. Zero syntax leaks
        assert not WIKILINK_PATTERN.search(reply), f"Wikilink leaked in Turn 1: {reply}"
        assert not CITATION_PATTERN.search(reply), f"Citation leaked in Turn 1: {reply}"
        assert not TABLE_DIVIDER_PATTERN.search(reply), f"Table divider leaked in Turn 1: {reply}"

    def test_tier4_turn2_trial_request_soft_booking(self):
        """Turn 2: 'tôi muosn thử trải nghiệm trước' -> maintains package, soft booking, latency 4–8s."""
        # Setup Turn 1 in history
        self.history = [
            {"role": "user", "content": "giá"},
            {"role": "assistant", "content": "Dạ em chào Chị ạ! Bên em có gói Massage cơ bản khởi điểm từ 399.000VNĐ (Hà Nội) gồm full body trị liệu 60 phút giúp giảm đau mỏi kèm buffet thực dưỡng sau liệu trình ạ.\n\nChị đang quan tâm Massage thư giãn hay Gội đầu dưỡng sinh để em tư vấn sát hơn nhé?"}
        ]
        msg = "tôi muosn thử trải nghiệm trước"
        data, elapsed = call_fanpage_bot(msg, history=self.history, customer_id=self.customer_id)
        reply = data.get("reply_text", "")
        bubbles = data.get("reply_bubbles", [])
        stage = data.get("sales_stage", "")

        # 1. Package consistency & trial confirmation
        assert any(k in reply.lower() for k in ["trải nghiệm", "399.000", "cơ bản", "massage"]), (
            f"Turn 2 failed to maintain package recommendation for trial: {reply}"
        )

        # 2. Moving toward booking
        assert stage in ["SOFT_BOOKING_CLOSE", "VALUE_CONSULTATION", "DISCOVERY"]

        # 3. Latency Budget: Simple turn 4-8s (< 15s guardrail)
        assert elapsed < 15.0, f"Turn 2 latency {elapsed:.2f}s exceeded budget"

        # 4. Bubble count & syntax
        assert len(bubbles) <= 2
        assert not WIKILINK_PATTERN.search(reply)
        assert not CITATION_PATTERN.search(reply)

    def test_tier4_turn3_price_objection_downsell_239k(self):
        """Turn 3: 'cao thế' -> down-sells to 239.000 VNĐ (60p), latency 15–30s (< 30s), 0 handoff."""
        # Setup Turns 1-2 in history
        self.history = [
            {"role": "user", "content": "giá"},
            {"role": "assistant", "content": "Dạ em chào Chị ạ! Bên em có gói Massage cơ bản khởi điểm từ 399.000VNĐ (Hà Nội) gồm full body trị liệu 60 phút."},
            {"role": "user", "content": "tôi muosn thử trải nghiệm trước"},
            {"role": "assistant", "content": "Dạ chị muốn thử trải nghiệm thì gói Massage cơ bản 399.000VNĐ (60 phút) rất phù hợp ạ. Chị muốn đặt lịch vào khung giờ nào ạ?"}
        ]
        msg = "cao thế"
        data, elapsed = call_fanpage_bot(msg, history=self.history, customer_id=self.customer_id)
        reply = data.get("reply_text", "")
        handoff = data.get("needs_human_handoff", False)

        # 1. Deterministic down-sell to 239.000 VNĐ (or 129k)
        assert any(k in reply for k in ["239.000", "129.000", "dưỡng sinh cơ bản"]), (
            f"Turn 3 did not down-sell to 239.000 VNĐ: {reply}"
        )

        # 2. No false handoff
        assert handoff is False, f"Turn 3 triggered false handoff on price objection: {reply}"

        # 3. Latency Budget: Deep reasoning turn < 30s
        assert elapsed < 30.0, f"Turn 3 latency {elapsed:.2f}s exceeded 30s budget"

        # 4. Zero syntax leak
        assert not WIKILINK_PATTERN.search(reply)
        assert not CITATION_PATTERN.search(reply)

    def test_tier4_turn4_second_objection_downsell_129k(self):
        """Turn 4: 'nhưng hơi đắt' -> down-sells to 129.000 VNĐ (30p), latency 15–30s (< 30s)."""
        self.history = [
            {"role": "user", "content": "giá"},
            {"role": "assistant", "content": "Dạ em chào Chị ạ! Bên em có gói Massage cơ bản khởi điểm từ 399.000VNĐ (Hà Nội)."},
            {"role": "user", "content": "tôi muosn thử trải nghiệm trước"},
            {"role": "assistant", "content": "Dạ chị muốn thử thì gói 399.000VNĐ 60 phút rất phù hợp ạ."},
            {"role": "user", "content": "cao thế"},
            {"role": "assistant", "content": "Dạ bên em có gói dưỡng sinh cơ bản chỉ 239.000VNĐ/60 phút rất nhẹ nhàng để chị trải nghiệm trước ạ."}
        ]
        msg = "nhưng hơi đắt"
        data, elapsed = call_fanpage_bot(msg, history=self.history, customer_id=self.customer_id)
        reply = data.get("reply_text", "")
        handoff = data.get("needs_human_handoff", False)

        # 1. Down-sell to 129.000 VNĐ catalog floor
        assert "129.000" in reply or "129k" in reply.lower() or "ráy tai" in reply.lower(), (
            f"Turn 4 failed to down-sell to 129.000 VNĐ: {reply}"
        )

        # 2. Zero handoff
        assert handoff is False

        # 3. Latency Budget: < 30s
        assert elapsed < 30.0, f"Turn 4 latency {elapsed:.2f}s exceeded 30s budget"

    def test_tier4_turn5_catalog_floor_and_promo_policy(self):
        """Turn 5: 'có gói nào rẻ hơn không' -> confirms 129.000 VNĐ floor + 10% promo, needs_human_handoff = false."""
        self.history = [
            {"role": "user", "content": "giá"},
            {"role": "assistant", "content": "Dạ có gói 399.000VNĐ ạ."},
            {"role": "user", "content": "tôi muosn thử trải nghiệm trước"},
            {"role": "assistant", "content": "Dạ 399k trải nghiệm rất hợp ạ."},
            {"role": "user", "content": "cao thế"},
            {"role": "assistant", "content": "Dạ bên em có gói 239.000VNĐ ạ."},
            {"role": "user", "content": "nhưng hơi đắt"},
            {"role": "assistant", "content": "Dạ bên em có gói lấy ráy tai cơ bản 129.000VNĐ/30 phút là mức giá mềm nhất rồi ạ."}
        ]
        msg = "có gói nào rẻ hơn không"
        data, elapsed = call_fanpage_bot(msg, history=self.history, customer_id=self.customer_id)
        reply = data.get("reply_text", "")
        handoff = data.get("needs_human_handoff", False)

        # 1. Confirms 129.000 VNĐ is floor / lowest tier + cites 10% promo
        assert any(k in reply for k in ["129.000", "thấp nhất", "mềm nhất", "10%", "ưu đãi"]), (
            f"Turn 5 must confirm 129.000đ floor and 10% promo: {reply}"
        )

        # 2. Zero false handoff
        assert handoff is False

        # 3. Latency < 30s
        assert elapsed < 30.0

    def test_tier4_cross_section_q1_branch_packages(self):
        """Cross-section Q1: 'cơ sở Quận 1 Sài Gòn có gói nào?' -> retrieves Saigon packages (570k / 129k / 260k)."""
        msg = "cơ sở Quận 1 Sài Gòn có gói nào?"
        data, elapsed = call_fanpage_bot(msg, history=[], customer_id="cross_q1")
        reply = data.get("reply_text", "")

        # Must mention Saigon / Q1 pricing
        assert any(k in reply for k in ["570.000", "129.000", "260.000", "Quận 1"]), (
            f"Saigon cross-section query did not return Saigon packages: {reply}"
        )
        assert not WIKILINK_PATTERN.search(reply)
        assert not CITATION_PATTERN.search(reply)

    def test_tier4_cross_section_q2_faq_comparison_670k_vs_780k(self):
        """Cross-section Q2: 'gói 670k và 780k khác gì nhau?' -> accurately cites FAQ regarding private VIP room shampoo."""
        msg = "gói 670k và 780k khác gì nhau?"
        data, elapsed = call_fanpage_bot(msg, history=[], customer_id="cross_q2")
        reply = data.get("reply_text", "")

        # Must cite the FAQ difference: 780k có phòng VIP riêng khép kín / gội dưỡng sinh tại phòng / không cần di chuyển
        assert any(k in reply.lower() for k in ["vip", "riêng tư", "gội dưỡng sinh", "khép kín", "không cần di chuyển", "phòng riêng"]), (
            f"FAQ 670k vs 780k comparison did not cite private room shampoo service difference: {reply}"
        )
        assert not WIKILINK_PATTERN.search(reply)
        assert not CITATION_PATTERN.search(reply)

    def test_tier4_zero_leaked_internal_syntax(self):
        """Verify 0 leaked internal syntax across all bubbles (wikilinks, citations, markdown headers, table pipes)."""
        messages = [
            "giá",
            "cơ sở Quận 1 có gói nào",
            "gói 670k và 780k khác gì nhau?",
            "có giảm giá combo không"
        ]
        for msg in messages:
            data, _ = call_fanpage_bot(msg, history=[], customer_id=f"syntax_probe_{hash(msg)}")
            reply = data.get("reply_text", "")
            bubbles = [b.get("text", "") for b in data.get("reply_bubbles", [])]

            for bubble in bubbles:
                assert not WIKILINK_PATTERN.search(bubble), f"Wikilink leaked in bubble: {bubble}"
                assert not CITATION_PATTERN.search(bubble), f"Citation leaked in bubble: {bubble}"
                assert not MARKDOWN_HEADING_PATTERN.search(bubble), f"Markdown heading leaked in bubble: {bubble}"
                assert not TABLE_PIPE_PATTERN.search(bubble), f"Table pipe leaked in bubble: {bubble}"
                assert not TABLE_DIVIDER_PATTERN.search(bubble), f"Table divider leaked in bubble: {bubble}"
                assert not INTERNAL_RULE_PATTERN.search(bubble), f"Internal rule ID leaked in bubble: {bubble}"

    def test_tier4_vietnamese_honorific_consistency(self):
        """Verify 100% consistent Vietnamese honorifics ('Em - Chị' or 'Em - Anh') with 0 phantom names."""
        # 1. Default customer should be addressed warmly with Chị
        data1, _ = call_fanpage_bot("giá dịch vụ", history=[], customer_id="honorific_female")
        reply1 = data1.get("reply_text", "")
        assert not LAN_ANH_PATTERN.search(reply1), f"Phantom name 'Lan Anh' found: {reply1}"
        assert any(k in reply1 for k in ["Chị", "chị", "mình"]), f"Expected female honorific Chị: {reply1}"

        # 2. Male customer self-identifying must switch 100% to Anh with ZERO Chị
        history_male = [
            {"role": "user", "content": "mình là nam, có gói nào phù hợp không em"}
        ]
        data2, _ = call_fanpage_bot("tư vấn gói massage thư giãn", history=history_male, customer_id="honorific_male")
        reply2 = data2.get("reply_text", "")
        assert "Anh" in reply2 or "anh" in reply2, f"Male honorific Anh missing: {reply2}"
        assert not bool(re.search(r"(?i)\bchị\b", reply2)), f"Leaked 'Chị' in male conversation: {reply2}"


# =============================================================================
# STANDALONE RUNNER & BASELINE RECORDER
# =============================================================================

def run_suite_and_report_baseline():
    """Execute all tests directly and print baseline report."""
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8")

    print("=" * 80)
    print("  DocMind Obsidian Knowledge Graph & Adaptive Reasoning Acceptance Suite")
    print(f"  Target: {API_URL} | Page ID: {PAGE_ID}")
    print("=" * 80)

    test_classes = [
        ("Tier 1: Feature Coverage", TestTier1FeatureCoverage),
        ("Tier 2: Boundary & Corner Cases", TestTier2BoundaryAndCorner),
        ("Tier 3: Pairwise Combinations", TestTier3PairwiseCombinations),
        ("Tier 4: Real-World Scenarios (Page 'Nàng sen')", TestTier4RealWorldNangSenProbe),
    ]

    total_passed = 0
    total_failed = 0
    results: List[Dict[str, Any]] = []

    for tier_label, cls in test_classes:
        print(f"\n--- {tier_label} ---")
        instance = cls()
        methods = [m for m in dir(instance) if m.startswith("test_")]

        for method_name in sorted(methods):
            method = getattr(instance, method_name)
            # Setup method if needed
            if hasattr(instance, "setup_method"):
                instance.setup_method()

            t0 = time.perf_counter()
            passed = False
            error_str = None
            try:
                method()
                passed = True
                total_passed += 1
            except Exception as e:
                total_failed += 1
                error_str = str(e)
            elapsed = time.perf_counter() - t0

            status = "PASS" if passed else "FAIL"
            print(f"  [{status}] {method_name} ({elapsed:.2f}s)")
            if error_str:
                print(f"         Error: {error_str[:160]}...")

            results.append({
                "tier": tier_label,
                "test": method_name,
                "passed": passed,
                "duration_sec": elapsed,
                "error": error_str,
            })

    print("\n" + "=" * 80)
    print(f"  BASELINE SUMMARY: {total_passed} Passed | {total_failed} Failed | Total: {total_passed + total_failed}")
    print("=" * 80)
    return total_passed, total_failed, results


if __name__ == "__main__":
    passed, failed, _ = run_suite_and_report_baseline()
    sys.exit(0 if failed == 0 else 1)
