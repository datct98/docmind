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
    skipped_sheets: list[str] = []
    blocks: list[Block] = []
    local_index = 0
    total_sheets = len(workbook.worksheets)

    try:
        for worksheet in workbook.worksheets:
            title = worksheet.title
            sheet_names.append(title)
            norm_title = title.strip().lower()
            if total_sheets >= 4 and norm_title in {"dashboard", "note", "notes", "todo", "nháp"}:
                skipped_sheets.append(title)
                sheet_rows[title] = 0
                continue

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

            sheet_rows[title] = sum(1 for r in rows if any(c.strip() for c in r))

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
                local_index = _emit_spreadsheet_grid_blocks(
                    rows,
                    [title],
                    blocks,
                    local_index,
                    mk,
                    parse_version,
                )
    finally:
        workbook.close()

    if meta is not None:
        meta["library"] = parse_version
        meta["sheet_names"] = sheet_names
        meta["sheet_rows"] = sheet_rows
        meta["skipped_sheets"] = skipped_sheets
        meta["truncated_sheets"] = []

    return blocks


# --------------------------------------------------------------------------- #
# HTML & Google Sheets Export (.html / .htm)
# --------------------------------------------------------------------------- #
_HTML_PARSER_VERSION = "builtin-html-1.0"


class _GoogleSheetsAndHtmlDomParser(_html.parser.HTMLParser if hasattr(_html, "parser") else object):
    """Lightweight stdlib HTML parser extracting tables, headings, paragraphs, and lists."""


from html.parser import HTMLParser as _StdHTMLParser  # noqa: E402


class _StructuredHtmlParser(_StdHTMLParser):
    """Extract structured tables and non-table blocks while stripping <style>/<script>/<head>."""

    _IGNORE_TAGS = {"style", "script", "head", "title", "noscript", "svg", "meta", "link"}

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._ignore_depth = 0
        self._in_table = 0
        self._in_thead = False
        self._in_row = False
        self._in_cell = False
        self._skip_current_cell = False
        self._current_cell_colspan = 1
        self._current_cell_parts: list[str] = []
        self._current_row: list[str] = []
        self._current_row_is_thead = False
        self._current_table: list[tuple[bool, list[str]]] = []

        # Ordered sequence of ("table", rows) or ("heading", level, text) or ("para", text)
        self.items: list[tuple] = []
        self._block_tag: str | None = None
        self._block_level: int = 1
        self._block_parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        t = tag.lower()
        if t in self._IGNORE_TAGS:
            if t not in ("meta", "link"):
                self._ignore_depth += 1
            return
        if self._ignore_depth > 0:
            return

        attr_map = {k.lower(): (v or "") for k, v in attrs}

        if t == "table":
            self._flush_inline_block()
            self._in_table += 1
            if self._in_table == 1:
                self._current_table = []
            return

        if self._in_table > 0:
            if t == "thead":
                self._in_thead = True
            elif t == "tr":
                self._in_row = True
                self._current_row = []
                self._current_row_is_thead = self._in_thead
            elif t in ("td", "th") and self._in_row:
                self._in_cell = True
                self._current_cell_parts = []
                cls = attr_map.get("class", "")
                cell_id = attr_map.get("id", "")
                # Skip Google Sheets row-number header cells (<th id="...R0" class="row-header">)
                if t == "th" and (
                    "row-header" in cls
                    or "freezebar" in cls
                    or bool(re.search(r"R\d+$", cell_id))
                ):
                    self._skip_current_cell = True
                else:
                    self._skip_current_cell = False
                try:
                    cs = int(attr_map.get("colspan", "1") or "1")
                    self._current_cell_colspan = max(1, min(cs, 20))
                except ValueError:
                    self._current_cell_colspan = 1
            elif t == "br" and self._in_cell:
                self._current_cell_parts.append(" ; ")
            return

        # Outside tables: handle h1..h6, p, li
        if t in ("h1", "h2", "h3", "h4", "h5", "h6"):
            self._flush_inline_block()
            self._block_tag = "heading"
            self._block_level = int(t[1])
            self._block_parts = []
        elif t in ("p", "li", "div"):
            if self._block_tag is None:
                self._block_tag = "para"
                self._block_parts = []
        elif t == "br" and self._block_tag is not None:
            self._block_parts.append("\n")

    def handle_endtag(self, tag: str) -> None:
        t = tag.lower()
        if t in self._IGNORE_TAGS:
            if t not in ("meta", "link") and self._ignore_depth > 0:
                self._ignore_depth -= 1
            return
        if self._ignore_depth > 0:
            return

        if self._in_table > 0:
            if t in ("td", "th") and self._in_cell:
                if not self._skip_current_cell:
                    raw = "".join(self._current_cell_parts)
                    clean = re.sub(r"\s+", " ", raw).strip(" ;")
                    self._current_row.append(clean)
                    for _ in range(self._current_cell_colspan - 1):
                        self._current_row.append("")
                self._in_cell = False
                self._skip_current_cell = False
                self._current_cell_parts = []
            elif t == "tr" and self._in_row:
                self._in_row = False
                if self._current_row:
                    self._current_table.append((self._current_row_is_thead, self._current_row))
                self._current_row = []
            elif t == "thead":
                self._in_thead = False
            elif t == "table":
                self._in_table -= 1
                if self._in_table == 0 and self._current_table:
                    self.items.append(("table", list(self._current_table)))
                    self._current_table = []
            return

        if t in ("h1", "h2", "h3", "h4", "h5", "h6", "p", "li"):
            self._flush_inline_block()

    def handle_data(self, data: str) -> None:
        if self._ignore_depth > 0:
            return
        if self._in_table > 0:
            if self._in_cell and not self._skip_current_cell:
                self._current_cell_parts.append(data)
            return
        if self._block_tag is None:
            if data.strip():
                self._block_tag = "para"
                self._block_parts = [data]
        else:
            self._block_parts.append(data)

    def _flush_inline_block(self) -> None:
        if self._block_tag is None:
            return
        text = re.sub(r"[ \t]+", " ", "".join(self._block_parts)).strip()
        if text:
            if self._block_tag == "heading":
                self.items.append(("heading", self._block_level, text))
            else:
                self.items.append(("para", text))
        self._block_tag = None
        self._block_parts = []


def _is_google_sheets_col_letter_row(cells: list[str]) -> bool:
    """Detect Google Sheets column-letter bar: ['A', 'B', 'C', ...]"""
    non_empty = [c.strip() for c in cells if c.strip()]
    if not non_empty:
        return True
    return all(bool(re.match(r"^[A-Z]{1,2}$", c)) for c in non_empty)


def _is_table_header_row(non_empty_cells: list[str]) -> bool:
    """Detect if a row is the column header of a structured sub-table."""
    if len(non_empty_cells) < 2:
        return False
    first = non_empty_cells[0].lower().strip()
    second = non_empty_cells[1].lower().strip()
    header_Firsts = {
        "mã gói",
        "mã dịch vụ",
        "mã sp",
        "bước",
        "tên ưu đãi",
        "nội dung",
        "hạng mục",
        "stt",
        "câu hỏi",
        "khách hỏi",
        "giai đoạn",
        "chi nhánh",
        "cơ sở",
        "tên ảnh",
        "nhóm",
        "nhóm nội dung",
        "vùng trị liệu",
    }
    if first in header_Firsts:
        return True
    if first == "tên gói" and ("giá" in second or "thời gian" in second):
        return True
    return False


def _forward_fill_grouped_table(compact: list[list[str]]) -> list[list[str]]:
    """Forward-fill empty leading group key cells in hierarchical tables.

    For example, in '8.12 Cơ sở vật chất' (Col 0 = Cơ sở, Col 1 = Tầng) or
    '07_Giáo_trình_massage' (Col 0 = Bước, Col 1 = Vùng trị liệu, Col 2 = Huyệt đạo),
    subsequent rows under the same facility or step leave Col 0 (and Col 1) blank.
    Carrying down the group label ensures every row and chunk remains self-contained.
    """
    if len(compact) <= 2 or not compact[0]:
        return compact
    num_cols = len(compact[0])
    if num_cols < 2:
        return compact

    h0 = compact[0][0].lower().strip()
    h1 = compact[0][1].lower().strip()
    # Fix missing "Bước" header when Col 0 has "Bước 1", "Bước 2" and Col 1 is "Vùng trị liệu"
    if not h0 and h1 == "vùng trị liệu" and any(r[0].lower().startswith("bước") for r in compact[1:] if r):
        compact[0][0] = "Bước"
        h0 = "bước"

    carry_col0 = h0 in {"cơ sở", "chi nhánh", "bước", "giai đoạn", "nhóm"}
    carry_col1 = h0 == "bước" and h1 == "vùng trị liệu"
    if not carry_col0:
        return compact

    last_col0 = ""
    last_col1 = ""
    for r in compact[1:]:
        if r[0].strip():
            last_col0 = r[0].strip()
            if carry_col1:
                last_col1 = r[1].strip()
        elif last_col0 and any(c.strip() for c in r[1:]):
            r[0] = last_col0
            if carry_col1 and not r[1].strip() and last_col1:
                r[1] = last_col1
    return compact


def _split_grid_by_empty_columns(rows: list[list[str]]) -> list[list[list[str]]]:
    """Split a 2D cell grid horizontally along columns that are 100% empty across all rows.

    Google Sheets often place multiple tables side-by-side separated by an empty
    divider column (e.g., Cols A..I = Pricing table, Col J = empty yellow divider,
    Cols K..N = Treatment process table). Splitting along empty columns produces
    clean, independent vertical table strips.
    """
    if not rows:
        return []
    max_cols = max((len(r) for r in rows), default=0)
    if max_cols == 0:
        return []

    padded = [r + [""] * (max_cols - len(r)) for r in rows]
    col_has_data = [
        any(bool(padded[r_idx][c_idx].strip()) for r_idx in range(len(padded)))
        for c_idx in range(max_cols)
    ]

    strips: list[tuple[int, int]] = []
    c = 0
    while c < max_cols:
        while c < max_cols and not col_has_data[c]:
            c += 1
        if c >= max_cols:
            break
        start = c
        while c < max_cols and col_has_data[c]:
            c += 1
        strips.append((start, c))

    result: list[list[list[str]]] = []
    for start, end in strips:
        strip_rows = [r[start:end] for r in padded]
        result.append(strip_rows)
    return result


def _emit_spreadsheet_grid_blocks(
    filtered_rows: list[list[str]],
    base_heading_path: list[str],
    blocks: list[Block],
    start_local_index: int,
    mk: BlockFactory,
    parse_version: str,
) -> int:
    """Shared multi-table, side-by-side strip, section-heading, and forward-fill emitter for Excel & HTML sheets."""
    local_index = start_local_index
    heading_path = list(base_heading_path)
    base_depth = len(base_heading_path)

    def emit_heading(level: int, text: str) -> None:
        nonlocal heading_path, local_index
        clean_t = text.strip()
        if not clean_t:
            return
        effective_level = base_depth + max(1, level)
        heading_path = _apply_heading(heading_path, effective_level, clean_t)
        md_lvl = max(1, min(6, effective_level))
        blocks.append(
            mk(
                local_index,
                type=BlockType.HEADING,
                text=clean_t,
                markdown=f"{'#' * md_lvl} {clean_t}",
                html=f"<h{md_lvl}>{_html.escape(clean_t)}</h{md_lvl}>",
                heading_path=list(heading_path),
                parse_version=parse_version,
            )
        )
        local_index += 1

    def emit_paragraph(text: str) -> None:
        nonlocal local_index
        clean_t = text.strip()
        if not clean_t:
            return
        blocks.append(
            mk(
                local_index,
                type=BlockType.PARAGRAPH,
                text=clean_t,
                markdown=clean_t,
                html=f"<p>{_html.escape(clean_t)}</p>",
                heading_path=list(heading_path),
                parse_version=parse_version,
            )
        )
        local_index += 1

    def emit_table(table_rows: list[list[str]]) -> None:
        nonlocal local_index
        if not table_rows:
            return
        max_c = -1
        for r in table_rows:
            for idx in range(len(r) - 1, -1, -1):
                if r[idx].strip():
                    if idx > max_c:
                        max_c = idx
                    break
        if max_c < 0:
            return
        trimmed = [[c.strip() for c in r[: max_c + 1]] for r in table_rows]
        active_cols = [
            c_idx
            for c_idx in range(max_c + 1)
            if any(
                c_idx < len(r) and bool(r[c_idx].strip())
                for r in trimmed
            )
        ]
        if not active_cols:
            return
        compact = [[r[c_idx] if c_idx < len(r) else "" for c_idx in active_cols] for r in trimmed]

        if len(active_cols) == 1:
            if len(compact) == 1 and base_depth > 0:
                blocks.append(
                    mk(
                        local_index,
                        type=BlockType.TABLE,
                        text=_text_table(compact),
                        markdown=_md_table(compact),
                        html=_html_table(compact),
                        heading_path=list(heading_path),
                        parse_version=parse_version,
                    )
                )
                local_index += 1
                return
            items_text = "\n".join(f"- {r[0]}" for r in compact if r[0])
            if items_text:
                blocks.append(
                    mk(
                        local_index,
                        type=BlockType.LIST,
                        text=items_text,
                        markdown=items_text,
                        html=_render_list_html([f"- {r[0]}" for r in compact if r[0]]),
                        heading_path=list(heading_path),
                        parse_version=parse_version,
                    )
                )
                local_index += 1
            return

        if (
            len(active_cols) == 2
            and not _is_table_header_row([c for c in compact[0] if c])
            and ("?" in compact[0][0] or any("?" in r[0] for r in compact[:3]))
        ):
            compact = [["Câu hỏi khách hàng", "Câu trả lời tư vấn chuẩn"], *compact]
        elif len(compact) == 1 and not any("?" in c for c in compact[0]) and base_depth == 0:
            emit_paragraph(" — ".join(c for c in compact[0] if c))
            return

        compact = _forward_fill_grouped_table(compact)
        header_row = compact[0]
        data_rows = compact[1:]
        if not data_rows:
            blocks.append(
                mk(
                    local_index,
                    type=BlockType.TABLE,
                    text=_text_table(compact),
                    markdown=_md_table(compact),
                    html=_html_table(compact),
                    heading_path=list(heading_path),
                    parse_version=parse_version,
                )
            )
            local_index += 1
            return

        for start_idx in range(0, len(data_rows), WINDOW_SIZE):
            chunk = data_rows[start_idx : start_idx + WINDOW_SIZE]
            window_rows = [header_row, *chunk]
            blocks.append(
                mk(
                    local_index,
                    type=BlockType.TABLE,
                    text=_text_table(window_rows),
                    markdown=_md_table(window_rows),
                    html=_html_table(window_rows),
                    heading_path=list(heading_path),
                    parse_version=parse_version,
                )
            )
            local_index += 1

    strips = _split_grid_by_empty_columns(filtered_rows)
    for strip in strips:
        active_table: list[list[str]] = []
        for row in strip:
            non_empty = [c.strip() for c in row if c.strip()]
            if not non_empty:
                if active_table:
                    emit_table(active_table)
                    active_table = []
                continue

            first_cell = non_empty[0]
            first_lower = first_cell.lower()

            # Case A: Single-cell section heading (e.g., "MASSAGE", "8.1. TỔNG_QUAN_THƯƠNG_HIỆU", "1. CHÍNH_SÁCH...", "FAQ")
            if len(non_empty) == 1 and len(first_cell) <= 95 and "?" not in first_cell:
                is_numbered_section = (
                    bool(re.match(r"^(?:\d+(?:\.\d+)*|[IVX]+)[.:_\s]+\S", first_cell))
                    and "động tác" not in first_lower
                    and (
                        "_" in first_cell
                        or "⭐️" in first_cell
                        or first_cell.isupper()
                        or bool(re.match(r"^(?:[IVX]+|\d+)\s*[.:_]\s*[A-ZÀÁẠẢÃÂẦẤẬẨẪĂẰẮẶẲẴÈÉẸẺẼÊỀẾỆỂỄÌÍỊỈĨÒÓỌỎÕÔỒỐỘỔỖƠỜỚỢỞỠÙÚỤỦŨƯỪỨỰỬỮỲÝỴỶỸĐ]{2,}", first_cell))
                    )
                )
                is_major = (
                    (first_cell.isupper() and len(first_cell) >= 3)
                    or is_numbered_section
                    or first_lower.startswith((
                        "massage", "gội đầu", "faq", "khách hỏi", "quy trình",
                        "lưu_ý", "lưu ý", "ưu đãi", "bài massage", "chính sách",
                    ))
                )
                is_sub = first_lower.startswith(("cơ sở", "khu vực", "chi nhánh")) and len(first_cell) <= 55
                if is_major or is_sub:
                    if active_table:
                        emit_table(active_table)
                        active_table = []
                    emit_heading(1 if is_major else 2, first_cell)
                    continue

            # Case B: Facility title + Address row (e.g. "Cơ sở Nàng Sen Bích Câu" | "Địa chỉ: 6 Bích Câu...")
            if (
                len(non_empty) <= 3
                and "?" not in first_cell
                and first_lower.startswith(("cơ sở", "chi nhánh"))
                and not _is_table_header_row(non_empty)
                and any("địa chỉ" in c.lower() for c in non_empty[1:])
            ):
                if active_table:
                    emit_table(active_table)
                    active_table = []
                combined_heading = " — ".join(non_empty)
                emit_heading(2, combined_heading)
                continue

            # Case C: Contact / Hotline / Opening hours row
            if first_lower.startswith(("hotline", "địa chỉ", "giờ hoạt động", "giờ mở cửa")):
                if active_table:
                    emit_table(active_table)
                    active_table = []
                info_line = " | ".join(non_empty)
                if heading_path and len(heading_path) > base_depth:
                    short_contact = " | ".join(
                        c for c in non_empty if not c.lower().startswith("http")
                    )
                    if short_contact and short_contact not in heading_path[-1]:
                        heading_path[-1] = f"{heading_path[-1]} ({short_contact})"
                emit_paragraph(info_line)
                continue

            # Case D: New explicit table header row starts while another table was accumulating
            if _is_table_header_row(non_empty) and active_table:
                emit_table(active_table)
                active_table = [row]
                continue

            active_table.append(row)

        if active_table:
            emit_table(active_table)
            active_table = []

    return local_index


def parse_html(payload: bytes, mk: BlockFactory) -> list[Block]:
    """Parse HTML / Google Sheets exported HTML into clean canonical blocks."""
    raw_html = payload.decode("utf-8", errors="replace").lstrip("\ufeff")
    parser = _StructuredHtmlParser()
    parser.feed(raw_html)
    parser._flush_inline_block()

    blocks: list[Block] = []
    heading_path: list[str] = []
    local_index = 0
    parse_version = _HTML_PARSER_VERSION

    for item in parser.items:
        kind = item[0]
        if kind == "heading":
            clean_t = item[2].strip()
            if clean_t:
                heading_path = _apply_heading(heading_path, item[1], clean_t)
                md_lvl = max(1, min(6, item[1]))
                blocks.append(
                    mk(
                        local_index,
                        type=BlockType.HEADING,
                        text=clean_t,
                        markdown=f"{'#' * md_lvl} {clean_t}",
                        html=f"<h{md_lvl}>{_html.escape(clean_t)}</h{md_lvl}>",
                        heading_path=list(heading_path),
                        parse_version=parse_version,
                    )
                )
                local_index += 1
        elif kind == "para":
            clean_t = item[1].strip()
            if clean_t:
                blocks.append(
                    mk(
                        local_index,
                        type=BlockType.PARAGRAPH,
                        text=clean_t,
                        markdown=clean_t,
                        html=f"<p>{_html.escape(clean_t)}</p>",
                        heading_path=list(heading_path),
                        parse_version=parse_version,
                    )
                )
                local_index += 1
        elif kind == "table":
            raw_tagged_rows: list[tuple[bool, list[str]]] = item[1]
            filtered_rows: list[list[str]] = []
            for is_thead, r in raw_tagged_rows:
                if is_thead and _is_google_sheets_col_letter_row(r):
                    continue
                filtered_rows.append(r)
            local_index = _emit_spreadsheet_grid_blocks(
                filtered_rows,
                heading_path,
                blocks,
                local_index,
                mk,
                parse_version,
            )

    return blocks

