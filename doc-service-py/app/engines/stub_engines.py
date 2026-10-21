"""Concrete engine adapters with CPU-safe stub fallbacks.

Each adapter tries to lazy-import its real backing library at the top of
``parse()``. If the import fails (library/model not installed) it logs a notice
and ALWAYS returns deterministic sample canonical :class:`Block` objects with
realistic bbox / confidence / heading_path and the correct ``parse_engine`` +
``parse_version``. This keeps the whole pipeline runnable with only the base
requirements and no model downloads.

Mapping of engine -> canonical parse_engine value:
    PyMuPdfEngine  -> pymupdf
    DoclingEngine  -> docling   (also the formula-capable path)
    PaddleOcrEngine-> paddleocr
    OfficeEngine   -> office

Determinism: block ids are derived from (document_id, page, local index) via a
UUID5 so re-parsing identical input yields identical ids (helps the result
cache and tests).
"""

from __future__ import annotations

import time
import uuid

from app.engines import office_parser
from app.engines.base import EngineAdapter, PageKind, ParseContext
from app.logging_config import get_logger, stage_context
from app.schema import Block, BlockType, ParseEngine

log = get_logger()

# Stable namespace for deterministic UUID5 block ids.
_BLOCK_NS = uuid.UUID("6d9f0a1c-7b2e-4f33-9a44-a1b2c3d4e5f6")


def _block_id(document_id: str, page: int, local_index: int) -> str:
    return str(uuid.uuid5(_BLOCK_NS, f"{document_id}:{page}:{local_index}"))


def _mean_confidence(blocks: list[Block]) -> float:
    if not blocks:
        return 0.0
    return round(sum(b.confidence for b in blocks) / len(blocks), 4)


class _BaseEngine(EngineAdapter):
    """Shared stub plumbing: logging + deterministic block assembly."""

    name: str = "office"
    version: str = "stub-0.1"
    _real_module: str = ""  # dotted module name of the real backing lib

    def _try_import(self) -> object | None:
        """Lazy-import the real backing library; return module or None."""
        if not self._real_module:
            return None
        try:
            module = __import__(self._real_module)
        except Exception:  # ImportError and any load-time failure
            return None
        return module

    def _emit(self, blocks: list[Block], ctx: ParseContext, started: float) -> list[Block]:
        duration_ms = round((time.perf_counter() - started) * 1000.0, 3)
        log.info(
            "parsed page",
            stage="parse",
            engine=self.name,
            page=ctx.page,
            duration_ms=duration_ms,
            confidence=_mean_confidence(blocks),
            block_count=len(blocks),
            parse_version=self.version,
        )
        return blocks

    def _sample_blocks(self, ctx: ParseContext) -> list[Block]:  # pragma: no cover - overridden
        raise NotImplementedError


class PyMuPdfEngine(_BaseEngine):
    """Fast text extraction for text-based simple PDFs (real: PyMuPDF/fitz)."""

    name = ParseEngine.PYMUPDF.value
    version = "stub-pymupdf-0.1"
    _real_module = "fitz"  # PyMuPDF imports as `fitz`

    def supports(self, page_kind: PageKind) -> bool:
        return page_kind == PageKind.TEXT_SIMPLE

    def parse(self, payload: bytes, ctx: ParseContext) -> list[Block]:
        started = time.perf_counter()
        with stage_context(engine=self.name, page=ctx.page):
            module = self._try_import()
            if module is None:
                log.info(
                    "engine falling back to stub (lib unavailable)",
                    stage="parse",
                    engine=self.name,
                    page=ctx.page,
                    detail="pymupdf/fitz not installed",
                )
            # TODO: when `module` is available, open the page and extract real
            # text spans into canonical blocks. For now we always emit samples.
            return self._emit(self._sample_blocks(ctx), ctx, started)

    def _sample_blocks(self, ctx: ParseContext) -> list[Block]:
        heading = f"Section {ctx.page}"
        path = [*ctx.heading_path, heading]
        return [
            Block(
                block_id=_block_id(ctx.document_id, ctx.page, 0),
                page=ctx.page,
                order=ctx.order_start,
                type=BlockType.HEADING,
                text=heading,
                markdown=f"# {heading}",
                html=f"<h1>{heading}</h1>",
                latex=None,
                bbox=(72.0, 90.0, 480.0, 118.0),
                confidence=0.99,
                heading_path=path,
                parse_engine=self.name,
                parse_version=self.version,
            ),
            Block(
                block_id=_block_id(ctx.document_id, ctx.page, 1),
                page=ctx.page,
                order=ctx.order_start + 1,
                type=BlockType.PARAGRAPH,
                text=f"Extracted text from page {ctx.page} of {ctx.filename}.",
                markdown=f"Extracted text from page {ctx.page} of {ctx.filename}.",
                html=f"<p>Extracted text from page {ctx.page} of {ctx.filename}.</p>",
                latex=None,
                bbox=(72.0, 130.0, 523.0, 172.0),
                confidence=0.97,
                heading_path=path,
                parse_engine=self.name,
                parse_version=self.version,
            ),
        ]


class DoclingEngine(_BaseEngine):
    """Structural parsing for complex PDFs + formulas (real: docling)."""

    name = ParseEngine.DOCLING.value
    version = "stub-docling-0.1"
    _real_module = "docling"

    def supports(self, page_kind: PageKind) -> bool:
        return page_kind in (PageKind.TEXT_COMPLEX, PageKind.FORMULA)

    def parse(self, payload: bytes, ctx: ParseContext) -> list[Block]:
        started = time.perf_counter()
        with stage_context(engine=self.name, page=ctx.page):
            module = self._try_import()
            if module is None:
                log.info(
                    "engine falling back to stub (lib unavailable)",
                    stage="parse",
                    engine=self.name,
                    page=ctx.page,
                    detail="docling not installed",
                )
            return self._emit(self._sample_blocks(ctx), ctx, started)

    def _sample_blocks(self, ctx: ParseContext) -> list[Block]:
        path = [*ctx.heading_path, f"Section {ctx.page}"]
        table_html = (
            "<table><tr><th>Key</th><th>Value</th></tr>"
            "<tr><td>Alpha</td><td>1</td></tr></table>"
        )
        return [
            Block(
                block_id=_block_id(ctx.document_id, ctx.page, 0),
                page=ctx.page,
                order=ctx.order_start,
                type=BlockType.TABLE,
                text="Key | Value\nAlpha | 1",
                markdown="| Key | Value |\n| --- | --- |\n| Alpha | 1 |",
                html=table_html,
                latex=None,
                bbox=(70.0, 100.0, 500.0, 200.0),
                confidence=0.94,
                heading_path=path,
                parse_engine=self.name,
                parse_version=self.version,
            ),
            Block(
                block_id=_block_id(ctx.document_id, ctx.page, 1),
                page=ctx.page,
                order=ctx.order_start + 1,
                type=BlockType.FORMULA,
                text="f'(x) = lim (h->0) (f(x+h) - f(x)) / h",
                markdown=r"$$f'(x) = \lim_{h \to 0} \frac{f(x+h) - f(x)}{h}$$",
                html="<p>f&#39;(x) = lim (f(x+h) &minus; f(x)) / h</p>",
                latex=r"f'(x) = \lim_{h \to 0} \frac{f(x+h) - f(x)}{h}",
                bbox=(100.0, 210.0, 410.0, 260.0),
                confidence=0.92,
                heading_path=path,
                parse_engine=self.name,
                parse_version=self.version,
            ),
        ]


class PaddleOcrEngine(_BaseEngine):
    """OCR / PP-Structure for scanned pages & images (real: paddleocr)."""

    name = ParseEngine.PADDLEOCR.value
    version = "stub-paddleocr-0.1"
    _real_module = "paddleocr"

    def supports(self, page_kind: PageKind) -> bool:
        return page_kind == PageKind.SCANNED

    def parse(self, payload: bytes, ctx: ParseContext) -> list[Block]:
        started = time.perf_counter()
        with stage_context(engine=self.name, page=ctx.page):
            module = self._try_import()
            if module is None:
                log.info(
                    "engine falling back to stub (lib unavailable)",
                    stage="parse",
                    engine=self.name,
                    page=ctx.page,
                    detail="paddleocr/paddlepaddle not installed",
                )
            return self._emit(self._sample_blocks(ctx), ctx, started)

    def _sample_blocks(self, ctx: ParseContext) -> list[Block]:
        path = [*ctx.heading_path, f"Scanned page {ctx.page}"]
        return [
            Block(
                block_id=_block_id(ctx.document_id, ctx.page, 0),
                page=ctx.page,
                order=ctx.order_start,
                type=BlockType.PARAGRAPH,
                text=f"OCR text recovered from scanned page {ctx.page}.",
                markdown=f"OCR text recovered from scanned page {ctx.page}.",
                html=f"<p>OCR text recovered from scanned page {ctx.page}.</p>",
                latex=None,
                bbox=(55.0, 80.0, 540.0, 140.0),
                confidence=0.86,
                heading_path=path,
                parse_engine=self.name,
                parse_version=self.version,
            ),
            Block(
                block_id=_block_id(ctx.document_id, ctx.page, 1),
                page=ctx.page,
                order=ctx.order_start + 1,
                type=BlockType.IMAGE,
                text="",
                markdown="![scanned region](about:blank)",
                html='<img alt="scanned region" src="about:blank"/>',
                latex=None,
                bbox=(55.0, 150.0, 300.0, 360.0),
                confidence=0.80,
                heading_path=path,
                parse_engine=self.name,
                parse_version=self.version,
            ),
        ]


class OfficeEngine(_BaseEngine):
    """Native parsing for docx/xlsx (real) + pptx/doc/md/html/web (stub).

    Real paths lazy-import python-docx / openpyxl via ``app.engines.office_parser``
    and build canonical blocks there. If the backing library is missing, or the
    real parse raises, we log and fall back to :meth:`_sample_blocks` so the
    service still boots and the job still completes (base-class contract: this
    method MUST NOT raise). Legacy ``.doc`` / OOXML ``.pptx`` are intentionally
    stub-only — real pptx parsing is out of scope.
    """

    name = ParseEngine.OFFICE.value
    version = "stub-office-0.1"
    _real_module = ""  # office parsing uses stdlib/light libs, no ML model

    # OOXML MIMEs that have a real parser (mirrors router._OFFICE_MIMES subset).
    _DOCX_MIME = (
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    )
    _XLSX_MIME = (
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    )
    _MD_MIMES = {
        "text/markdown",
        "text/x-web-markdown",
        "text/x-markdown",
        "text/plain",
    }
    _HTML_MIMES = {
        "text/html",
        "application/xhtml+xml",
    }

    def supports(self, page_kind: PageKind) -> bool:
        return page_kind in (PageKind.OFFICE, PageKind.WEB)

    def _format(self, ctx: ParseContext) -> str:
        """Return 'docx' | 'xlsx' | 'md' | 'html' | 'stub' from the detected mime (+ filename).

        Magic-byte mime is authoritative (see validation.detect_mime); the
        filename extension is a secondary tiebreak when the mime is a generic
        zip/ole value that the sniffer could not disambiguate.
        """
        mime = ctx.mime or ""
        name = (ctx.filename or "").lower()
        if mime == self._DOCX_MIME:
            return "docx"
        if mime == self._XLSX_MIME:
            return "xlsx"
        if mime in self._HTML_MIMES or name.endswith((".html", ".htm")):
            return "html"
        if mime in self._MD_MIMES:
            return "md"
        if mime in ("application/zip", "application/octet-stream"):
            if name.endswith(".docx"):
                return "docx"
            if name.endswith(".xlsx"):
                return "xlsx"
        if name.endswith((".md", ".markdown", ".txt")):
            return "md"
        return "stub"

    def _make_block_factory(self, ctx: ParseContext):
        """Return a ``mk`` closure that stamps the office field-value contract.

        Office formats have no page coordinates, so ``bbox`` is the zero sentinel
        ``(0.0, 0.0, 0.0, 0.0)``; ``confidence`` is 1.0 (native extraction, not
        OCR); ``latex`` is always None (no formula blocks); ``page`` is ctx.page
        (always 1 for office). The caller passes a monotonic ``local_index`` so
        ``order = ctx.order_start + local_index`` stays monotonic within the call.
        """

        def mk(
            local_index: int,
            *,
            type: BlockType,
            text: str,
            markdown: str,
            html: str,
            heading_path: list[str],
            parse_version: str,
        ) -> Block:
            return Block(
                block_id=_block_id(ctx.document_id, ctx.page, local_index),
                page=ctx.page,
                order=ctx.order_start + local_index,
                type=type,
                text=text,
                markdown=markdown,
                html=html,
                latex=None,  # office never produces formula blocks
                bbox=(0.0, 0.0, 0.0, 0.0),  # office formats expose no coordinates
                confidence=1.0,  # native text extraction, not OCR
                heading_path=heading_path,
                parse_engine=self.name,
                parse_version=parse_version,
            )

        return mk

    def parse(self, payload: bytes, ctx: ParseContext) -> list[Block]:
        started = time.perf_counter()
        with stage_context(engine=self.name, page=ctx.page):
            fmt = self._format(ctx)

            if fmt == "stub":
                # Legacy .doc / .ppt / .pptx / web -> stub blocks.
                log.info(
                    "office stub path (real parsing out of scope for this type)",
                    stage="parse",
                    engine=self.name,
                    page=ctx.page,
                    mime=ctx.mime,
                )
                return self._emit(self._sample_blocks(ctx), ctx, started)

            if fmt == "html":
                try:
                    mk = self._make_block_factory(ctx)
                    blocks = office_parser.parse_html(payload, mk)
                    if blocks:
                        parse_version = blocks[0].parse_version
                        log.info(
                            "office parsed html",
                            stage="parse",
                            engine=self.name,
                            page=ctx.page,
                            library=parse_version,
                            block_count=len(blocks),
                        )
                        return self._emit(blocks, ctx, started)
                except Exception as exc:
                    log.warning(
                        "office html parse failed; using stub",
                        stage="parse",
                        engine=self.name,
                        page=ctx.page,
                        error=str(exc),
                    )
                return self._emit(self._sample_blocks(ctx), ctx, started)

            if fmt == "md":
                try:
                    mk = self._make_block_factory(ctx)
                    blocks = office_parser.parse_markdown(payload, mk)
                    parse_version = (
                        blocks[0].parse_version if blocks else "builtin-markdown-1.0"
                    )
                    log.info(
                        "office parsed markdown",
                        stage="parse",
                        engine=self.name,
                        page=ctx.page,
                        library=parse_version,
                        block_count=len(blocks),
                    )
                    return self._emit(blocks, ctx, started)
                except Exception as exc:  # unexpected parse error
                    log.warning(
                        "office markdown parse failed; using stub",
                        stage="parse",
                        engine=self.name,
                        page=ctx.page,
                        error=str(exc),
                    )
                    return self._emit(self._sample_blocks(ctx), ctx, started)

            if fmt == "docx":
                if office_parser.load_docx() is None:
                    log.info(
                        "engine falling back to stub (lib unavailable)",
                        stage="parse",
                        engine=self.name,
                        page=ctx.page,
                        detail="python-docx not installed",
                    )
                    return self._emit(self._sample_blocks(ctx), ctx, started)
                try:
                    mk = self._make_block_factory(ctx)
                    blocks = office_parser.parse_docx(payload, mk)
                    parse_version = (
                        blocks[0].parse_version if blocks else "python-docx-?"
                    )
                    log.info(
                        "office parsed docx",
                        stage="parse",
                        engine=self.name,
                        page=ctx.page,
                        library=parse_version,
                        block_count=len(blocks),
                    )
                    return self._emit(blocks, ctx, started)
                except Exception as exc:  # corrupt file / unexpected lib error
                    log.warning(
                        "office real parse failed; using stub",
                        stage="parse",
                        engine=self.name,
                        page=ctx.page,
                        error=str(exc),
                    )
                    return self._emit(self._sample_blocks(ctx), ctx, started)

            # fmt == "xlsx"
            if office_parser.load_openpyxl() is None:
                log.info(
                    "engine falling back to stub (lib unavailable)",
                    stage="parse",
                    engine=self.name,
                    page=ctx.page,
                    detail="openpyxl not installed",
                )
                return self._emit(self._sample_blocks(ctx), ctx, started)
            try:
                mk = self._make_block_factory(ctx)
                meta: dict = {}
                blocks = office_parser.parse_xlsx(payload, mk, meta)
                log.info(
                    "office parsed xlsx",
                    stage="parse",
                    engine=self.name,
                    page=ctx.page,
                    library=meta.get("library", "openpyxl-?"),
                    sheet_names=meta.get("sheet_names", []),
                    sheet_rows=meta.get("sheet_rows", {}),
                    truncated_sheets=meta.get("truncated_sheets", []),
                    block_count=len(blocks),
                )
                return self._emit(blocks, ctx, started)
            except Exception as exc:  # corrupt file / unexpected lib error
                log.warning(
                    "office real parse failed; using stub",
                    stage="parse",
                    engine=self.name,
                    page=ctx.page,
                    error=str(exc),
                )
                return self._emit(self._sample_blocks(ctx), ctx, started)

    def _sample_blocks(self, ctx: ParseContext) -> list[Block]:
        title = f"{ctx.filename}"
        path = [title]
        return [
            Block(
                block_id=_block_id(ctx.document_id, ctx.page, 0),
                page=ctx.page,
                order=ctx.order_start,
                type=BlockType.HEADING,
                text=title,
                markdown=f"# {title}",
                html=f"<h1>{title}</h1>",
                latex=None,
                bbox=(0.0, 0.0, 600.0, 40.0),
                confidence=0.98,
                heading_path=path,
                parse_engine=self.name,
                parse_version=self.version,
            ),
            Block(
                block_id=_block_id(ctx.document_id, ctx.page, 1),
                page=ctx.page,
                order=ctx.order_start + 1,
                type=BlockType.LIST,
                text="- item one\n- item two",
                markdown="- item one\n- item two",
                html="<ul><li>item one</li><li>item two</li></ul>",
                latex=None,
                bbox=(0.0, 50.0, 600.0, 120.0),
                confidence=0.95,
                heading_path=path,
                parse_engine=self.name,
                parse_version=self.version,
            ),
        ]
