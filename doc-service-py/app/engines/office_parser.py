"""Real Office (.docx / .xlsx) parsing helpers for :class:`OfficeEngine`.

This module isolates the two OPTIONAL native-office libraries behind a single
place so the sibling engines (PyMuPdf/Docling/PaddleOcr) and ``stub_engines``
stay untouched:

    * Word  (.docx)  -> python-docx (imports as ``docx``)
    * Excel (.xlsx)  -> openpyxl

Lazy-import discipline (same rule as the sibling engines): the two libraries are
imported INSIDE the loader helpers, never at module top level. ``import
app.engines.office_parser`` therefore succeeds even when neither library is
installed, and the caller (:class:`OfficeEngine`) falls back to its deterministic
stub blocks when a loader returns ``None``.

Field-value conventions for office formats (documented here once; the caller's
``block_factory`` stamps them onto every :class:`Block`):

    * ``bbox = (0.0, 0.0, 0.0, 0.0)`` — Office formats expose NO page coordinates,
      so there is no meaningful bounding box; a zero box is the agreed sentinel.
    * ``confidence = 1.0`` — native text extraction (NOT OCR); the characters are
      read verbatim from the file, so confidence is maximal.
    * ``page = 1`` — a ``.docx`` has no fixed page model (pagination is a render
      concern) and ``.xlsx`` worksheets are not pages; everything maps to page 1.
    * ``latex = None`` — Office parsing never produces formula blocks.
    * ``parse_engine = "office"``; ``parse_version`` is the backing lib version,
      e.g. ``"python-docx-1.1.2"`` / ``"openpyxl-3.1.5"``.

Vietnamese / UTF-8: python-docx and openpyxl already return Python ``str``, so no
manual decode is performed and accented text round-trips unchanged.

Scope: legacy ``.doc`` / ``.ppt`` and OOXML ``.pptx`` are intentionally NOT parsed
here — the caller keeps emitting stub blocks for those.
"""

from __future__ import annotations

import html as _html
import io
import re
from typing import Callable

from app.schema import Block, BlockType

# Bounded row-window size for Excel table blocks (bounds token/memory cost).
WINDOW_SIZE = 100
_MARKDOWN_VERSION = "builtin-markdown-1.0"

# A ``block_factory`` closure owned by the caller. It assigns block_id / order /
# parse_engine / version / the office field-value conventions above, so this
# module stays schema-light and the caller owns the running reading-order index.
BlockFactory = Callable[..., Block]


# --------------------------------------------------------------------------- #
# Lazy loaders (NEVER import at module top level)
# --------------------------------------------------------------------------- #
def load_docx() -> object | None:
    """Lazy-import python-docx (module ``docx``); return module or ``None``."""
    try:
        import docx  # noqa: PLC0415 - intentional lazy import
    except Exception:  # ImportError or any load-time failure
        return None
    return docx


def load_openpyxl() -> object | None:
    """Lazy-import openpyxl; return the module or ``None``."""
    try:
        import openpyxl  # noqa: PLC0415 - intentional lazy import
    except Exception:  # ImportError or any load-time failure
        return None
    return openpyxl


def _lib_version(module: object, prefix: str) -> str:
    """Return ``"<prefix>-<version>"``, degrading to ``"<prefix>-?"``."""
    version = getattr(module, "__version__", None)
    return f"{prefix}-{version}" if version else f"{prefix}-?"


# --------------------------------------------------------------------------- #
# Table rendering helpers (markdown / html / plain text)
# --------------------------------------------------------------------------- #
def _cell_md(value: str) -> str:
    """Escape a cell for a GitHub-style markdown table (pipes + newlines)."""
    return value.replace("\\", "\\\\").replace("|", "\\|").replace("\n", "<br>")


def _md_table(rows: list[list[str]]) -> str:
    """Render ``rows`` as a GitHub-style markdown table (first row = header)."""
    if not rows:
        return ""
    header = rows[0]
    width = len(header)
    sep = ["---"] * width
    lines = [
        "| " + " | ".join(_cell_md(c) for c in header) + " |",
        "| " + " | ".join(sep) + " |",
    ]
    for row in rows[1:]:
        # Pad/truncate so every body row matches the header column count.
        cells = (list(row) + [""] * width)[:width]
        lines.append("| " + " | ".join(_cell_md(c) for c in cells) + " |")
    return "\n".join(lines)


def _html_table(rows: list[list[str]]) -> str:
    """Render ``rows`` as an HTML table (first row = ``<th>`` header)."""
    if not rows:
        return "<table></table>"
    parts = ["<table>"]
    header = rows[0]
    width = len(header)
    parts.append(
        "<tr>" + "".join(f"<th>{_html.escape(c)}</th>" for c in header) + "</tr>"
    )
    for row in rows[1:]:
        cells = (list(row) + [""] * width)[:width]
        parts.append(
            "<tr>" + "".join(f"<td>{_html.escape(c)}</td>" for c in cells) + "</tr>"
        )
    parts.append("</table>")
    return "".join(parts)


def _text_table(rows: list[list[str]]) -> str:
    """Flatten ``rows`` to plain text: cells joined by ' | ', rows by newline."""
    return "\n".join(" | ".join(row) for row in rows)


def _stringify(value: object) -> str:
    """Stringify a cell value; ``None`` -> empty string, everything else str()."""
    return "" if value is None else str(value)


# --------------------------------------------------------------------------- #
# Word (.docx)
# --------------------------------------------------------------------------- #
def _heading_level(style_name: str) -> int | None:
    """Return the heading level for a style name, or ``None`` if not a heading.

    ``Title`` -> level 0 (resets the breadcrumb). ``Heading 1``..``Heading 9``
    -> their numeric level. Anything else -> ``None`` (treated as body text).
    """
    if not style_name:
        return None
    name = style_name.strip()
    if name.lower() == "title":
        return 0
    if name.lower().startswith("heading"):
        tail = name[len("heading"):].strip()
        if tail.isdigit():
            return int(tail)
        return 1  # bare "Heading" with no number -> level 1
    return None


def _apply_heading(path: list[str], level: int, text: str) -> list[str]:
    """Return a NEW breadcrumb after placing ``text`` at ``level``.

    * level 0 (Title) resets the path to ``[text]``.
    * level N truncates the path to N-1 entries then appends ``text`` so a
      Heading 2 under a Heading 1 yields ``["H1", "H2"]``.
    """
    if level <= 0:
        return [text]
    truncated = path[: level - 1]
    return [*truncated, text]


def parse_docx(payload: bytes, mk: BlockFactory) -> list[Block]:
    """Parse Word ``.docx`` bytes into canonical blocks in reading order.

    Heading styles become ``heading`` blocks maintaining a running
    ``heading_path`` breadcrumb; normal paragraphs become ``paragraph`` blocks;
    empty paragraphs are skipped; tables become ``table`` blocks (markdown +
    html + plain-text flatten). ``mk`` stamps id/order/engine/version/bbox/etc.
    """
    docx = load_docx()
    assert docx is not None, "parse_docx called without python-docx available"
    from docx.table import Table  # noqa: PLC0415 - lazy, only on the real path
    from docx.text.paragraph import Paragraph  # noqa: PLC0415

    document = docx.Document(io.BytesIO(payload))
    parse_version = _lib_version(docx, "python-docx")

    blocks: list[Block] = []
    heading_path: list[str] = []
    local_index = 0

    # Iterate the body in TRUE reading order. python-docx exposes paragraphs and
    # tables separately, so we walk the XML body children and wrap each <w:p> and
    # <w:tbl> element with the matching python-docx wrapper. Newer python-docx
    # offers Document.iter_inner_content(); if absent we fall back to the XML walk.
    inner = getattr(document, "iter_inner_content", None)
    if callable(inner):
        items = list(inner())
    else:  # pragma: no cover - exercised only on older python-docx
        items = []
        body = document.element.body
        for child in body.iterchildren():
            tag = child.tag.rsplit("}", 1)[-1]
            if tag == "p":
                items.append(Paragraph(child, document))
            elif tag == "tbl":
                items.append(Table(child, document))

    for item in items:
        if isinstance(item, Paragraph):
            text = item.text.strip()
            if not text:
                continue  # skip empty / whitespace-only paragraphs
            style_name = item.style.name if item.style is not None else ""
            level = _heading_level(style_name)
            if level is not None:
                heading_path = _apply_heading(heading_path, level, text)
                md_level = max(1, level) if level > 0 else 1
                html_level = min(max(md_level, 1), 6)
                blocks.append(
                    mk(
                        local_index,
                        type=BlockType.HEADING,
                        text=text,
                        markdown=f"{'#' * md_level} {text}",
                        html=f"<h{html_level}>{_html.escape(text)}</h{html_level}>",
                        heading_path=list(heading_path),
                        parse_version=parse_version,
                    )
                )
            else:
                blocks.append(
                    mk(
                        local_index,
                        type=BlockType.PARAGRAPH,
                        text=text,
                        markdown=text,
                        html=f"<p>{_html.escape(text)}</p>",
                        heading_path=list(heading_path),
                        parse_version=parse_version,
                    )
                )
            local_index += 1
        elif isinstance(item, Table):
            rows = [[cell.text.strip() for cell in row.cells] for row in item.rows]
            if not rows:
                continue
            blocks.append(
                mk(
                    local_index,
                    type=BlockType.TABLE,
                    text=_text_table(rows),
                    markdown=_md_table(rows),
                    html=_html_table(rows),
                    heading_path=list(heading_path),
                    parse_version=parse_version,
                )
            )
            local_index += 1

    return blocks


def _is_table_delimiter(line: str) -> bool:
    """Check if line is a GFM table delimiter row like `|---|:---|---:|`."""
    stripped = line.strip()
    if not stripped or "|" not in stripped:
        return False
    if stripped.startswith("|"):
        stripped = stripped[1:]
    if stripped.endswith("|"):
        stripped = stripped[:-1]
    cells = [c.strip() for c in stripped.split("|")]
    if not cells:
        return False
    return all(bool(re.match(r"^:?-+:?$", c)) for c in cells)


def _split_table_row(line: str) -> list[str]:
    """Split a markdown table row into cells, handling escaped pipes."""
    stripped = line.strip()
    if stripped.startswith("|"):
        stripped = stripped[1:]
    if stripped.endswith("|"):
        stripped = stripped[:-1]
    raw_cells = re.split(r"(?<!\\)\|", stripped)
    return [c.strip().replace(r"\|", "|") for c in raw_cells]


def _is_list_marker(line: str) -> bool:
    """Check if line starts with an unordered or ordered list marker."""
    stripped = line.strip()
    return bool(re.match(r"^(?:[*+-]|\d+\.)\s+", stripped))


def _render_list_html(lines: list[str]) -> str:
    """Render markdown list lines to HTML (<ul> or <ol>)."""
    if not lines:
        return "<ul></ul>"
    first = ""
    for l in lines:
        if l.strip():
            first = l.strip()
            break
    is_ol = bool(re.match(r"^\d+\.", first))
    tag = "ol" if is_ol else "ul"
    items: list[str] = []
    current: list[str] = []
    for l in lines:
        m = re.match(r"^\s*(?:[*+-]|\d+\.)\s+(.*)$", l)
        if m:
            if current:
                items.append(" ".join(current))
                current = []
            current.append(m.group(1))
        elif l.strip():
            if current:
                current.append(l.strip())
            else:
                current.append(l.strip())
    if current:
        items.append(" ".join(current))
    lis = "".join(f"<li>{_html.escape(it)}</li>" for it in items)
    return f"<{tag}>{lis}</{tag}>"


# --------------------------------------------------------------------------- #
# Markdown (.md / .markdown / .txt)
# --------------------------------------------------------------------------- #
def parse_markdown(payload: bytes, mk: BlockFactory) -> list[Block]:
    """Parse UTF-8 Markdown text into canonical blocks.

    Zero-dependency pure-Python parser emitting:
      * HEADING: ATX `#` .. `######` (maintains running `heading_path` breadcrumb)
      * TABLE: GFM pipe tables (`| ... |` with delimiter row)
      * LIST: bullet (`- `, `* `, `+ `) or numbered (`1. `) lists
      * PARAGRAPH: standard text paragraphs
    """
    raw_text = payload.decode("utf-8", errors="replace").lstrip("\ufeff")
    raw_text = raw_text.replace("\r\n", "\n").replace("\r", "\n")
    lines = raw_text.split("\n")

    blocks: list[Block] = []
    heading_path: list[str] = []
    local_index = 0
    parse_version = _MARKDOWN_VERSION

    i = 0
    n = len(lines)
    while i < n:
        line = lines[i]
        stripped = line.strip()

        # 1. Blank line -> advance
        if not stripped:
            i += 1
            continue

        # 2. ATX Heading: # .. ######
        m_head = re.match(r"^(#{1,6})\s+(.*?)(?:\s+#+)?$", stripped)
        if m_head:
            level = len(m_head.group(1))
            h_text = m_head.group(2).strip()
            heading_path = _apply_heading(heading_path, level, h_text)
            md_level = max(1, min(6, level))
            blocks.append(
                mk(
                    local_index,
                    type=BlockType.HEADING,
                    text=h_text,
                    markdown=f"{'#' * md_level} {h_text}",
                    html=f"<h{md_level}>{_html.escape(h_text)}</h{md_level}>",
                    heading_path=list(heading_path),
                    parse_version=parse_version,
                )
            )
            local_index += 1
            i += 1
            continue

        # 3. GFM Table: current line has pipe, next line is table delimiter
        if "|" in line and i + 1 < n and _is_table_delimiter(lines[i + 1]):
            header_cells = _split_table_row(lines[i])
            width = len(header_cells)
            body_rows: list[list[str]] = []
            j = i + 2
            while j < n:
                row_line = lines[j]
                r_strip = row_line.strip()
                if not r_strip or "|" not in row_line or re.match(r"^#{1,6}\s+", r_strip):
                    break
                row_cells = _split_table_row(row_line)
                padded = (row_cells + [""] * width)[:width]
                body_rows.append(padded)
                j += 1

            all_rows = [header_cells, *body_rows]
            blocks.append(
                mk(
                    local_index,
                    type=BlockType.TABLE,
                    text=_text_table(all_rows),
                    markdown=_md_table(all_rows),
                    html=_html_table(all_rows),
                    heading_path=list(heading_path),
                    parse_version=parse_version,
                )
            )
            local_index += 1
            i = j
            continue

        # 4. List: starts with bullet or number
        if _is_list_marker(line):
            list_lines: list[str] = [line]
            j = i + 1
            while j < n:
                next_line = lines[j]
                n_strip = next_line.strip()
                if not n_strip:
                    # Look ahead: if next non-empty line is a list item, continue loose list
                    k = j + 1
                    while k < n and not lines[k].strip():
                        k += 1
                    if k < n and _is_list_marker(lines[k]):
                        list_lines.append("")
                        j += 1
                        continue
                    else:
                        break
                if re.match(r"^#{1,6}\s+", n_strip):
                    break
                if "|" in next_line and j + 1 < n and _is_table_delimiter(lines[j + 1]):
                    break
                if _is_list_marker(next_line) or next_line.startswith((" ", "\t")):
                    list_lines.append(next_line)
                    j += 1
                else:
                    break

            # Strip trailing blank lines
            while list_lines and not list_lines[-1].strip():
                list_lines.pop()

            blocks.append(
                mk(
                    local_index,
                    type=BlockType.LIST,
                    text="\n".join(list_lines),
                    markdown="\n".join(list_lines),
                    html=_render_list_html(list_lines),
                    heading_path=list(heading_path),
                    parse_version=parse_version,
                )
            )
            local_index += 1
            i = j
            continue

        # 5. Paragraph: consecutive text lines
        para_lines: list[str] = [line]
        j = i + 1
        while j < n:
            next_line = lines[j]
            n_strip = next_line.strip()
            if not n_strip:
                break
            if re.match(r"^#{1,6}\s+", n_strip):
                break
            if "|" in next_line and j + 1 < n and _is_table_delimiter(lines[j + 1]):
                break
            if _is_list_marker(next_line):
                break
            para_lines.append(next_line)
            j += 1

        p_text = "\n".join(para_lines).strip()
        if p_text:
            blocks.append(
                mk(
                    local_index,
                    type=BlockType.PARAGRAPH,
                    text=p_text,
                    markdown="\n".join(para_lines),
                    html=f"<p>{_html.escape(p_text)}</p>",
                    heading_path=list(heading_path),
                    parse_version=parse_version,
                )
            )
            local_index += 1
        i = j

    return blocks


# --------------------------------------------------------------------------- #
# Excel (.xlsx)
# --------------------------------------------------------------------------- #
def parse_xlsx(payload: bytes, mk: BlockFactory, meta: dict | None = None) -> list[Block]:
    """Parse Excel ``.xlsx`` bytes into canonical blocks.

    Per worksheet:
      * one ``heading`` block with the sheet name (heading_path = [sheet_title]).
      * partitioned ``table`` blocks in windows of :data:`WINDOW_SIZE` (100) rows.
      * every table block retains the detected column header row at index 0 and
        has heading_path = [sheet_title].
      * trailing and leading empty rows and empty trailing columns are stripped.

    Observability facts (sheet names, per-sheet row counts, truncated_sheets=[])
    are written into the optional ``meta`` dict.
    """
    openpyxl = load_openpyxl()
    assert openpyxl is not None, "parse_xlsx called without openpyxl available"

    workbook = openpyxl.load_workbook(io.BytesIO(payload), read_only=True, data_only=True)
    parse_version = _lib_version(openpyxl, "openpyxl")

    sheet_names: list[str] = []
    sheet_rows: dict[str, int] = {}
    blocks: list[Block] = []
    local_index = 0

    try:
        for worksheet in workbook.worksheets:
            title = worksheet.title
            sheet_names.append(title)

            # Heading block for the sheet.
            blocks.append(
                mk(
                    local_index,
                    type=BlockType.HEADING,
                    text=title,
                    markdown=f"# {title}",
                    html=f"<h1>{_html.escape(title)}</h1>",
                    heading_path=[title],
                    parse_version=parse_version,
                )
            )
            local_index += 1

            # Read raw rows stringifying values
            raw_rows: list[list[str]] = []
            for raw in worksheet.iter_rows(values_only=True):
                raw_rows.append([_stringify(v) for v in raw])

            # Detect max non-empty column index across all rows
            max_col = -1
            for r in raw_rows:
                for c_idx in range(len(r) - 1, -1, -1):
                    if r[c_idx].strip():
                        if c_idx > max_col:
                            max_col = c_idx
                        break

            if max_col >= 0:
                rows = [r[: max_col + 1] for r in raw_rows]
                # Strip trailing empty rows
                while rows and all(not c.strip() for c in rows[-1]):
                    rows.pop()
                # Strip leading empty rows
                while rows and all(not c.strip() for c in rows[0]):
                    rows.pop(0)
            else:
                rows = []

            sheet_rows[title] = len(rows)

            if not rows:
                # Empty sheet: emit single empty table block
                blocks.append(
                    mk(
                        local_index,
                        type=BlockType.TABLE,
                        text="",
                        markdown="",
                        html="<table></table>",
                        heading_path=[title],
                        parse_version=parse_version,
                    )
                )
                local_index += 1
            elif len(rows) == 1:
                # Only header row
                blocks.append(
                    mk(
                        local_index,
                        type=BlockType.TABLE,
                        text=_text_table(rows),
                        markdown=_md_table(rows),
                        html=_html_table(rows),
                        heading_path=[title],
                        parse_version=parse_version,
                    )
                )
                local_index += 1
            else:
                # Multiple rows: header row + data rows partitioned into windows
                header_row = rows[0]
                data_rows = rows[1:]
                for start_idx in range(0, len(data_rows), WINDOW_SIZE):
                    chunk = data_rows[start_idx : start_idx + WINDOW_SIZE]
                    window_rows = [header_row] + chunk
                    blocks.append(
                        mk(
                            local_index,
                            type=BlockType.TABLE,
                            text=_text_table(window_rows),
                            markdown=_md_table(window_rows),
                            html=_html_table(window_rows),
                            heading_path=[title],
                            parse_version=parse_version,
                        )
                    )
                    local_index += 1
    finally:
        workbook.close()

    if meta is not None:
        meta["library"] = parse_version
        meta["sheet_names"] = sheet_names
        meta["sheet_rows"] = sheet_rows
        meta["truncated_sheets"] = []

    return blocks
