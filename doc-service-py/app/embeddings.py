"""Embedding providers for POST /v1/embed — real sentence-transformers + stub.

Same lazy-import + graceful-fallback discipline as the parser engines
(:mod:`app.engines.base` / :mod:`app.engines.stub_engines`): importing this
module MUST NOT pull in ``sentence_transformers`` or load any model. The real
model is loaded lazily as a module-level singleton on the FIRST ``embed()``
call. When the ML library (or the model) is unavailable, a deterministic
384-dim stub provider answers instead, logging a WARNING on degrade — the
service always answers ``/v1/embed``.

Design choices (documented inline where they matter):
  * Model: ``sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2``
    (384-dim, CPU, strong Vietnamese support). The dimension is **384**
    everywhere and MUST NOT change (pgvector column + Java EmbeddingProperties).
  * Vectors are L2-normalized so cosine == dot product downstream.
  * The stub replicates the Java ``StubEmbeddingClient`` algorithm so both sides
    produce deterministic, self-consistent 384-dim vectors when degraded.
"""

from __future__ import annotations

import functools
import time
from typing import Protocol

from app.logging_config import get_logger

log = get_logger()

# --- Hard contract constants (do NOT change) --------------------------------
#: Embedding dimension — matches the pgvector column and Java EmbeddingProperties.
EMBEDDING_DIM = 384
#: Max texts processed per request; the route caps-and-logs beyond this.
MAX_BATCH = 256
#: Real provider model id (multilingual MiniLM, 384-dim, CPU, VI-friendly).
REAL_MODEL_ID = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"


class _ModelUnavailable(Exception):
    """Internal signal: the real model could not be imported/loaded.

    Raised by the real provider's lazy loader and caught by
    :func:`get_embedding_provider` so it never propagates to a request —
    the factory degrades to the stub instead.
    """


class EmbeddingProvider(Protocol):
    """Minimal provider contract consumed by the ``/v1/embed`` route."""

    #: Reported in the response ``model`` field (real model id or ``"stub"``).
    name: str
    #: Always :data:`EMBEDDING_DIM` (384).
    dim: int

    def embed(self, texts: list[str]) -> list[list[float]]:
        """Return one normalized 384-length vector per input, order preserved."""
        ...


# --- Stub provider (deterministic, no model) --------------------------------


# --- Vietnamese semantic concept clusters for deterministic 384-dim projection ---
_VI_SEMANTIC_CONCEPTS: list[tuple[str, tuple[str, ...]]] = [
    (
        "__concept_neck_shoulder_pain__",
        (
            "mỏi vai", "bả vai", "cổ vai gáy", "vai gáy", "căng cơ", "bó cơ",
            "nhức mỏi", "đau nhức", "đau vai", "đau cổ", "ngồi máy tính",
            "văn phòng", "ngồi làm việc", "ấn huyệt", "bấm huyệt", "giải cơ",
        ),
    ),
    (
        "__concept_headache_sleep__",
        (
            "đau đầu", "nặng đầu", "mất ngủ", "khó ngủ", "ngủ không sâu",
            "tiền đình", "căng thẳng", "stress", "áp lực công việc", "dưỡng sinh đầu",
        ),
    ),
    (
        "__concept_herbal_therapy_process__",
        (
            "ngâm bồn", "thảo dược", "xông hơi", "ngải cứu", "chườm nóng",
            "muối gừng", "ngâm chân", "quy trình", "các bước", "điểm tâm", "thực dưỡng",
        ),
    ),
    (
        "__concept_pricing_packages__",
        (
            "bảng giá", "mức giá", "chi phí", "bao nhiêu tiền", "niêm yết",
            "399.000", "530.000", "580.000", "129.000", "239.000", "trọn gói", "không nhận tip",
        ),
    ),
    (
        "__concept_privacy_couple_vip__",
        (
            "phòng riêng", "phòng vip", "phòng đôi", "cặp đôi", "vợ chồng",
            "riêng tư", "kín đáo", "2 người", "đi đôi",
        ),
    ),
]


class _StubProvider:
    """Deterministic 384-dim stub mirroring the Java ``StubEmbeddingClient``
    with Vietnamese semantic concept cluster projection.
    """

    name = "stub"
    dim = EMBEDDING_DIM

    def embed(self, texts: list[str]) -> list[list[float]]:
        return [self._embed_one(t) for t in texts]

    def _embed_one(self, text: str) -> list[float]:
        src = (text or "").lower()
        v = [0.0] * self.dim
        for token in src.split():  # tokens split on whitespace then \W filtered
            for piece in _split_non_word(token):
                if not piece:
                    continue
                h = _java_string_hashcode(piece)
                idx = h % self.dim  # Python % already floor-mods for +dim
                sign = 1.0 if ((h >> 31) & 1) == 0 else -1.0
                v[idx] += sign
        for concept_id, triggers in _VI_SEMANTIC_CONCEPTS:
            matches = sum(1 for kw in triggers if kw in src)
            if matches > 0:
                weight = min(4.5, 2.0 + matches * 0.8)
                for salt in range(3):
                    ch = _java_string_hashcode(f"{concept_id}:{salt}")
                    cidx = ch % self.dim
                    csign = 1.0 if ((ch >> 31) & 1) == 0 else -1.0
                    v[cidx] += csign * weight
        if all(x == 0.0 for x in v):
            seed = _java_string_hashcode(src) % self.dim
            v[seed] = 1.0
        _l2_normalize(v)
        return v


def _split_non_word(token: str) -> list[str]:
    """Split a whitespace token further on non-word chars (``\\W+`` parity)."""
    import re

    return re.split(r"\W+", token)


def _java_string_hashcode(s: str) -> int:
    """Compute Java's ``String.hashCode()`` as a 32-bit signed int.

    Mirrors ``StubEmbeddingClient`` so Python stub vectors match the Java stub
    for identical input (deterministic cross-service behavior under degrade).
    """
    h = 0
    for ch in s:
        h = (31 * h + ord(ch)) & 0xFFFFFFFF
    # Interpret as signed 32-bit (Java int semantics).
    if h >= 0x80000000:
        h -= 0x100000000
    return h


def _l2_normalize(v: list[float]) -> None:
    norm = sum(x * x for x in v) ** 0.5
    if norm > 0:
        for i in range(len(v)):
            v[i] = v[i] / norm


# --- Real sentence-transformers provider (lazy singleton) -------------------

# Module-global singleton holder for the loaded model (never loaded at import).
_MODEL = None
_MODEL_LOGGED = False


def _load_model():
    """Lazy-load the SentenceTransformer model once; raise ``_ModelUnavailable``.

    Loaded on the FIRST ``embed()`` call, NOT at import time. On any import or
    load failure the caller degrades to the stub (the factory catches this).
    """
    global _MODEL, _MODEL_LOGGED
    if _MODEL is not None:
        return _MODEL
    try:
        from sentence_transformers import SentenceTransformer  # heavy, lazy
    except Exception as exc:  # ImportError or transitive load failure
        raise _ModelUnavailable(f"sentence_transformers import failed: {exc}") from exc
    started = time.perf_counter()
    try:
        model = SentenceTransformer(REAL_MODEL_ID, device="cpu")
    except Exception as exc:  # download/load failure (offline, disk, etc.)
        raise _ModelUnavailable(f"model load failed: {exc}") from exc
    _MODEL = model
    if not _MODEL_LOGGED:
        model_load_ms = round((time.perf_counter() - started) * 1000.0, 1)
        log.info(
            "embedding model loaded",
            stage="embed-serve",
            provider="real",
            model=REAL_MODEL_ID,
            model_load_ms=model_load_ms,
        )
        _MODEL_LOGGED = True
    return _MODEL


class _RealSentenceTransformerProvider:
    """CPU sentence-transformers provider; vectors normalized (cosine==dot)."""

    name = REAL_MODEL_ID
    dim = EMBEDDING_DIM

    def embed(self, texts: list[str]) -> list[list[float]]:
        model = _load_model()  # first call loads + logs timing
        # normalize_embeddings=True => L2-normalized so cosine == dot product.
        vectors = model.encode(
            texts,
            normalize_embeddings=True,
            convert_to_numpy=True,
        )
        return [[float(x) for x in row] for row in vectors]


# --- Factory ----------------------------------------------------------------


@functools.lru_cache(maxsize=1)
def get_embedding_provider() -> EmbeddingProvider:
    """Return the active provider: real if available, else the stub.

    Process-wide cached. Decides WITHOUT loading the model: it only probes
    whether ``sentence_transformers`` is importable (and defers the actual
    heavy load to the first ``embed()`` call). Logs ONCE which provider is
    active, and a WARNING when degrading to the stub.
    """
    import importlib.util

    if importlib.util.find_spec("sentence_transformers") is not None:
        log.info(
            "embedding provider active",
            stage="embed-serve",
            provider="real",
            model=REAL_MODEL_ID,
        )
        return _RealSentenceTransformerProvider()

    log.warning(
        "embedding provider degraded to stub (sentence_transformers unavailable)",
        stage="embed-serve",
        provider="stub",
        model="stub",
    )
    return _StubProvider()
