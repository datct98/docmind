"""LlamaIndex Structured Node & Table-Row Decomposition Parser.

Implements LlamaIndex's retrieval-first philosophy for DocMind:
1. Uses ``llama_index.core.node_parser.MarkdownNodeParser`` (when installed)
   to parse hierarchical Markdown headings into parent/child ``TextNode``s.
2. Decomposes multi-row Markdown / Office tables (FAQ Q&A tables, Service
   Pricing tables, Process tables) into **Atomic Semantic Row Nodes** so each
   FAQ question-answer pair and each service package is indexed as its own
   self-contained node with zero cross-row contamination.
"""

from __future__ import annotations

import re
from typing import Any

try:
    from llama_index.core.node_parser import MarkdownNodeParser
    from llama_index.core.schema import Document as LlamaDocument
    from llama_index.core.schema import TextNode

    _HAS_LLAMA_INDEX = True
except Exception:  # pragma: no cover - graceful fallback when llama_index not installed
    _HAS_LLAMA_INDEX = False
    MarkdownNodeParser = None  # type: ignore[assignment]
    LlamaDocument = None  # type: ignore[assignment]
    TextNode = None  # type: ignore[assignment]


_FAQ_HEADER_RE = re.compile(
    r"(?i)(?:câu\s+hỏi|khách\s+hàng\s+hỏi|tình\s+huống|thắc\s+mắc|vấn\s+đề|faq|"
    r"câu\s+trả\s+lời|giải\s+đáp|phản\s+hồi|tư\s+vấn\s+chuẩn)"
)


def is_faq_table(headers: list[str], heading_path: list[str] | None = None) -> bool:
    """Detect whether a table is a 2-to-3 column FAQ / Q&A table."""
    if not headers or len(headers) > 4:
        return False
    joined_headers = " | ".join(headers)
    if _FAQ_HEADER_RE.search(joined_headers):
        return True
    if heading_path:
        joined_path = " > ".join(heading_path)
        if len(headers) == 2 and re.search(r"(?i)\b(?:faq|câu\s+hỏi|giải\s+đáp)\b", joined_path):
            return True
    return False


def expand_table_rows_to_atomic_nodes(
    rows: list[list[str]],
    heading_path: list[str] | None = None,
) -> list[dict[str, Any]]:
    """Decompose a parsed table (`rows[0]` = header, `rows[1:]` = data rows) into
    self-contained LlamaIndex-style atomic node dicts (`text`, `markdown`, `heading_path`, `node_type`).
    """
    if not rows or len(rows) < 2:
        return []

    headers = [c.strip() for c in rows[0]]
    data_rows = [r for r in rows[1:] if any(c.strip() for c in r)]
    if not data_rows:
        return []

    breadcrumb = " > ".join(heading_path) if heading_path else ""
    prefix = f"[{breadcrumb}] " if breadcrumb else ""
    faq_mode = is_faq_table(headers, heading_path)

    nodes: list[dict[str, Any]] = []

    if faq_mode and len(headers) >= 2:
        for row in data_rows:
            cells = [c.strip() for c in row]
            if len(cells) < 2 or not cells[0] or not cells[1]:
                continue
            question = cells[0]
            answer = cells[1]
            extra = (
                f" ({headers[2]}: {cells[2]})"
                if len(cells) >= 3 and len(headers) >= 3 and cells[2]
                else ""
            )
            node_text = f"{prefix}Khách hỏi: {question}\nGiải đáp: {answer}{extra}"
            nodes.append(
                {
                    "text": node_text,
                    "markdown": f"**Khách hỏi:** {question}\n**Giải đáp:** {answer}{extra}",
                    "heading_path": list(heading_path or []),
                    "node_type": "faq_qa_pair",
                }
            )
        return nodes

    # Multi-column Pricing / Service / Process catalog table:
    # Emit 1 atomic node per row with column names explicitly attached to each value
    for row in data_rows:
        cells = [c.strip() for c in row]
        labeled_pairs: list[str] = []
        for idx, val in enumerate(cells):
            if not val:
                continue
            col_name = headers[idx] if idx < len(headers) and headers[idx] else f"Cột {idx + 1}"
            labeled_pairs.append(f"{col_name}: {val}")
        if not labeled_pairs:
            continue
        row_summary = " | ".join(labeled_pairs)
        node_text = f"{prefix}{row_summary}"
        nodes.append(
            {
                "text": node_text,
                "markdown": f"- {node_text}",
                "heading_path": list(heading_path or []),
                "node_type": "catalog_row",
            }
        )

    return nodes


def parse_markdown_with_llamaindex(markdown_text: str) -> list[dict[str, Any]]:
    """Parse Markdown text into hierarchical LlamaIndex nodes when llama-index-core is installed."""
    if not markdown_text or not markdown_text.strip():
        return []
    if _HAS_LLAMA_INDEX and MarkdownNodeParser is not None and LlamaDocument is not None:
        parser = MarkdownNodeParser()
        doc = LlamaDocument(text=markdown_text)
        llama_nodes = parser.get_nodes_from_documents([doc])
        results: list[dict[str, Any]] = []
        for n in llama_nodes:
            meta = getattr(n, "metadata", {}) or {}
            hp = [
                str(v).strip()
                for k, v in sorted(meta.items())
                if k.lower().startswith("header") and str(v).strip()
            ]
            results.append(
                {
                    "text": n.get_content().strip(),
                    "heading_path": hp,
                    "node_type": "llamaindex_markdown_node",
                }
            )
        return results
    return [{"text": markdown_text.strip(), "heading_path": [], "node_type": "fallback_node"}]
