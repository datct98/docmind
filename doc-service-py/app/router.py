"""ParserRouter: classify input into per-page PageKind + route to an engine.

Flow:
  * ``classify(mime, data)`` returns an ordered list of :class:`PageDecision`
    (one per page) with the chosen :class:`PageKind` and a human-readable reason.
  * For PDFs, ``classify_pdf(data)`` decides per-page TEXT_SIMPLE / TEXT_COMPLEX
    / SCANNED / FORMULA. It lazy-uses a pdf-inspector style classifier when
    available, otherwise a dependency-free heuristic over the raw bytes.
  * ``engine_for(page_kind)`` returns the first registered EngineAdapter whose
    ``supports(page_kind)`` is True.

Office/markdown/html/xlsx/pptx/docx map to OFFICE; HTML/web maps to WEB.
"""

from __future__ import annotations

from dataclasses import dataclass

from app.engines.base import EngineAdapter, PageKind
from app.engines.stub_engines import (
    DoclingEngine,
    OfficeEngine,
    PaddleOcrEngine,
    PyMuPdfEngine,
)
from app.logging_config import get_logger, stage_context

log = get_logger()

_OFFICE_MIMES = {
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/x-ole-storage",
    "application/msword",
    "application/vnd.ms-excel",
    "application/vnd.ms-powerpoint",
    "text/markdown",
    "text/x-web-markdown",
    "text/x-markdown",
    "text/plain",
    "text/csv",
}
_WEB_MIMES = {"text/html"}
_IMAGE_PREFIX = "image/"


@dataclass
class PageDecision:
    """Classification result for a single page."""

    page: int           # 1-based
    kind: PageKind
    reason: str


class ParserRouter:
    """Classifies input and routes each page to the right engine."""

    def __init__(self) -> None:
        # Registration order matters for engine_for() tie-breaks.
        self._engines: list[EngineAdapter] = [
            PyMuPdfEngine(),
            DoclingEngine(),
            PaddleOcrEngine(),
            OfficeEngine(),
        ]

    @property
    def engines(self) -> list[EngineAdapter]:
        return list(self._engines)

    def engine_for(self, page_kind: PageKind) -> EngineAdapter:
        """Return the first engine that supports ``page_kind``."""
        for engine in self._engines:
            if engine.supports(page_kind):
                return engine
        # Office engine is the catch-all fallback.
        return self._engines[-1]

    # -- classification -----------------------------------------------------

    def classify(self, mime: str, data: bytes) -> list[PageDecision]:
        """Return a per-page list of :class:`PageDecision` for ``data``."""
        if mime == "application/pdf":
            decisions = self.classify_pdf(data)
        elif mime.startswith(_IMAGE_PREFIX):
            decisions = [PageDecision(1, PageKind.SCANNED, f"image mime {mime}")]
        elif mime in _WEB_MIMES:
            decisions = [PageDecision(1, PageKind.WEB, "html/web content")]
        elif mime in _OFFICE_MIMES:
            decisions = [PageDecision(1, PageKind.OFFICE, f"office/text mime {mime}")]
        else:
            decisions = [PageDecision(1, PageKind.OFFICE, f"fallback office for {mime}")]

        for d in decisions:
            with stage_context(page=d.page):
                log.info(
                    "page classified",
                    stage="classify",
                    page=d.page,
                    kind=d.kind.value,
                    reason=d.reason,
                )
        return decisions

    def classify_pdf(self, data: bytes) -> list[PageDecision]:
        """Classify each PDF page. Lazy pdf-inspector, else heuristic stub."""
        inspector = self._load_pdf_inspector()
        if inspector is not None:  # pragma: no cover - only when lib present
            # TODO: delegate to the real pdf-inspector per-page classification.
            pass

        pages = self._estimate_pdf_pages(data)
        decisions: list[PageDecision] = []
        for page in range(1, pages + 1):
            kind, reason = self._heuristic_pdf_page_kind(data, page, pages)
            decisions.append(PageDecision(page, kind, reason))
        return decisions

    # -- internals ----------------------------------------------------------

    @staticmethod
    def _load_pdf_inspector() -> object | None:
        """Lazy-import a pdf-inspector style classifier; None if absent."""
        for mod in ("pdf_inspector", "firecrawl_pdf_inspector"):
            try:
                return __import__(mod)
            except Exception:
                continue
        return None

    @staticmethod
    def _estimate_pdf_pages(data: bytes) -> int:
        """Rough page count without a PDF lib: count /Type /Page objects."""
        # Count "/Type /Page" (not "/Pages"). Cheap and good enough for the stub.
        count = data.count(b"/Type /Page") + data.count(b"/Type/Page")
        # Avoid counting the /Pages tree node; clamp to at least 1.
        count -= data.count(b"/Type /Pages") + data.count(b"/Type/Pages")
        return max(1, count)

    @staticmethod
    def _heuristic_pdf_page_kind(
        data: bytes, page: int, total: int
    ) -> tuple[PageKind, str]:
        """Heuristic per-page kind over raw bytes (stand-in for pdf-inspector).

        * presence of a text operator stream (BT/ET, Tj/TJ) -> text-based,
        * image XObjects without text -> scanned,
        * math-ish markers -> formula,
        * tables / many columns -> text_complex.
        """
        has_text = (b"BT" in data and b"ET" in data) or b"Tj" in data or b"TJ" in data
        has_image = b"/Image" in data or b"/XObject" in data
        has_math = b"/Formula" in data or b"\\lim" in data or b"\\frac" in data
        has_table = b"/Table" in data or data.count(b"/Td") > 20

        if has_math:
            return PageKind.FORMULA, "math markers detected (formula path)"
        if has_text and not has_image:
            if has_table:
                return PageKind.TEXT_COMPLEX, "text + table layout -> docling"
            return PageKind.TEXT_SIMPLE, "clean text layer -> pymupdf"
        if has_image and not has_text:
            return PageKind.SCANNED, "image-only page, no text layer -> ocr"
        if has_text and has_image:
            return PageKind.TEXT_COMPLEX, "mixed text+image -> docling"
        # No signals at all (tiny/synthetic PDFs): treat as simple text.
        return PageKind.TEXT_SIMPLE, "no strong signal, default text_simple"
