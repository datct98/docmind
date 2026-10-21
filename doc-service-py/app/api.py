"""HTTP API: /v1 parse-job endpoints + health probes.

Routes:
  POST /v1/parse            -> 202 {job_id, document_id, cached}
  GET  /v1/jobs/{job_id}         -> status + per-page progress + engine_breakdown
  GET  /v1/jobs/{job_id}/result  -> canonical Document JSON (404 until succeeded)
  GET  /healthz             -> liveness (always 200 while the process is up)
  GET  /readyz              -> readiness (200; reports optional ML lib importability)

Every handler reads the X-Correlation-Id header (set by the Java orchestrator)
and binds it + ids onto the logging context so all lines share the chain.
"""

from __future__ import annotations

import importlib.util

from typing import Any

from fastapi import APIRouter, BackgroundTasks, File, Header, Request, UploadFile
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from app.agent.nightly_graph import run_nightly_training_graph
from app.agent.sales_graph import run_sales_consultation_graph
from app.agent.security import verify_internal_token
from app.config import get_settings
from app.embeddings import MAX_BATCH, get_embedding_provider
from app.jobs import JobState, get_job_store
from app.logging_config import bind_context, clear_context, get_logger
from app.service import process_parse_job, submit_parse

log = get_logger()

router = APIRouter()

# Optional ML libraries whose importability /readyz reports.
_OPTIONAL_ML_LIBS = [
    "fitz",            # PyMuPDF
    "docling",
    "paddleocr",
    "paddle",          # paddlepaddle
    "sentence_transformers",
    "pdf_inspector",   # firecrawl pdf-inspector style classifier
]


def _correlation_from(header_value: str | None) -> str | None:
    return header_value or None


def _authenticate_request(
    x_internal_token: str | None = None,
    authorization: str | None = None,
) -> bool:
    token = x_internal_token or authorization
    return verify_internal_token(token)


# Per-text char cap: truncate very long inputs before embedding (logged).
_MAX_CHARS = 20_000


class EmbedRequest(BaseModel):
    """Request body for POST /v1/embed: a list of texts to embed."""

    texts: list[str]


@router.post("/v1/parse", status_code=202)
async def parse(
    request: Request,
    background_tasks: BackgroundTasks,
    file: UploadFile = File(...),
    x_internal_token: str | None = Header(default=None),
    authorization: str | None = Header(default=None),
    x_correlation_id: str | None = Header(default=None),
) -> JSONResponse:
    """Accept a multipart file; return 202 with job ids immediately."""
    if not _authenticate_request(x_internal_token, authorization):
        return JSONResponse(
            status_code=401,
            content={"error": "Unauthorized: invalid or missing internal token"},
        )
    correlation_id = _correlation_from(x_correlation_id)
    bind_context(correlation_id=correlation_id)
    try:
        data = await file.read()
        filename = file.filename or "upload.bin"

        store = get_job_store()
        result = submit_parse(
            data=data,
            filename=filename,
            correlation_id=correlation_id,
            store=store,
        )

        # Only schedule background parsing when it was NOT a cache hit.
        if not result.cached:
            background_tasks.add_task(
                process_parse_job,
                job_id=result.job_id,
                data=data,
                filename=filename,
                correlation_id=correlation_id,
                store=store,
            )

        return JSONResponse(
            status_code=202,
            content={
                "job_id": result.job_id,
                "document_id": result.document_id,
                "cached": result.cached,
            },
        )
    finally:
        clear_context()


@router.get("/v1/jobs/{job_id}")
async def get_job(
    job_id: str,
    x_internal_token: str | None = Header(default=None),
    authorization: str | None = Header(default=None),
    x_correlation_id: str | None = Header(default=None),
) -> JSONResponse:
    """Return job status + per-page progress + engine_breakdown-so-far."""
    if not _authenticate_request(x_internal_token, authorization):
        return JSONResponse(
            status_code=401,
            content={"error": "Unauthorized: invalid or missing internal token"},
        )
    bind_context(correlation_id=_correlation_from(x_correlation_id), job_id=job_id)
    try:
        job = get_job_store().get(job_id)
        if job is None:
            return JSONResponse(status_code=404, content={"error": "job not found"})

        bind_context(document_id=job.document_id)
        payload = {
            "job_id": job.job_id,
            "document_id": job.document_id,
            "status": job.state.value if isinstance(job.state, JobState) else job.state,
            "filename": job.filename,
            "sha256": job.sha256,
            "cached": job.cached,
            "pages_total": job.pages_total,
            "pages_done": job.pages_done,
            "page_progress": [
                {
                    "page": pp.page,
                    "kind": pp.kind,
                    "status": pp.status,
                    "block_count": pp.block_count,
                }
                for pp in job.page_progress
            ],
            "engine_breakdown": job.engine_breakdown,
            "error": job.error,
        }
        log.info("job status served", stage="jobs", status=payload["status"])
        return JSONResponse(status_code=200, content=payload)
    finally:
        clear_context()


@router.get("/v1/jobs/{job_id}/result")
async def get_job_result(
    job_id: str,
    x_internal_token: str | None = Header(default=None),
    authorization: str | None = Header(default=None),
    x_correlation_id: str | None = Header(default=None),
) -> JSONResponse:
    """Return the canonical Document JSON; 404 until the job has succeeded."""
    if not _authenticate_request(x_internal_token, authorization):
        return JSONResponse(
            status_code=401,
            content={"error": "Unauthorized: invalid or missing internal token"},
        )
    bind_context(correlation_id=_correlation_from(x_correlation_id), job_id=job_id)
    try:
        job = get_job_store().get(job_id)
        if job is None:
            return JSONResponse(status_code=404, content={"error": "job not found"})
        if job.result is None or job.state != JobState.SUCCEEDED:
            return JSONResponse(
                status_code=404,
                content={
                    "error": "result not ready",
                    "status": job.state.value
                    if isinstance(job.state, JobState)
                    else job.state,
                },
            )
        bind_context(document_id=job.document_id)
        log.info("job result served", stage="result", cached=job.cached)
        # mode="json" renders enums/tuples to their JSON-safe forms.
        return JSONResponse(status_code=200, content=job.result.model_dump(mode="json"))
    finally:
        clear_context()


@router.post("/v1/embed")
async def embed(
    body: EmbedRequest,
    x_internal_token: str | None = Header(default=None),
    authorization: str | None = Header(default=None),
    x_correlation_id: str | None = Header(default=None),
) -> JSONResponse:
    """Embed a batch of texts; return one 384-dim vector per input (order kept).

    Contract: request ``{"texts": [...]}`` -> ``{"model": <id|"stub">, "dim": 384,
    "embeddings": [[...384 floats...], ...]}``. Empty list -> 200 with empty
    embeddings (no model load). Batch is capped at ``MAX_BATCH`` (cap-and-log),
    and overly long individual texts are truncated at ``_MAX_CHARS`` (logged).
    The active provider is real sentence-transformers when installed, else a
    deterministic 384-dim stub — the service always answers.
    """
    if not _authenticate_request(x_internal_token, authorization):
        return JSONResponse(
            status_code=401,
            content={"error": "Unauthorized: invalid or missing internal token"},
        )
    bind_context(correlation_id=_correlation_from(x_correlation_id))
    try:
        provider = get_embedding_provider()

        texts = body.texts
        if not texts:
            # Empty input: no model load, empty embeddings, dim still reported.
            log.info(
                "embed served (empty input)",
                stage="embed-serve",
                provider=provider.name,
                batch_size=0,
            )
            return JSONResponse(
                status_code=200,
                content={"model": provider.name, "dim": provider.dim, "embeddings": []},
            )

        # Cap batch size (process only the first MAX_BATCH; log the overflow).
        original_count = len(texts)
        if original_count > MAX_BATCH:
            log.warning(
                "embed batch capped",
                stage="embed-serve",
                provider=provider.name,
                requested=original_count,
                cap=MAX_BATCH,
            )
            texts = texts[:MAX_BATCH]

        # Truncate overly long individual texts before embedding.
        truncated = 0
        prepared: list[str] = []
        for t in texts:
            s = t or ""
            if len(s) > _MAX_CHARS:
                s = s[:_MAX_CHARS]
                truncated += 1
            prepared.append(s)
        if truncated:
            log.warning(
                "embed truncated long texts",
                stage="embed-serve",
                provider=provider.name,
                truncated=truncated,
                max_chars=_MAX_CHARS,
            )

        embeddings = provider.embed(prepared)
        log.info(
            "embed served",
            stage="embed-serve",
            provider=provider.name,
            batch_size=len(prepared),
        )
        return JSONResponse(
            status_code=200,
            content={
                "model": provider.name,
                "dim": provider.dim,
                "embeddings": embeddings,
            },
        )
    finally:
        clear_context()


class InternalConsultRequest(BaseModel):
    """Request payload for Hot-Path LangGraph consultation (`POST /internal/v1/consult`)."""

    customer_psid: str = Field(default="anonymous_customer")
    page_id: str | None = Field(default=None)
    message: str = Field(default="")
    history: list[dict[str, str]] = Field(default_factory=list)
    candidate_chunks: list[dict[str, Any]] = Field(default_factory=list)
    candidate_rules: list[dict[str, Any]] = Field(default_factory=list)
    honorific: str | None = Field(default=None)


class InternalNightlyExtractRequest(BaseModel):
    """Request payload for Cold-Path Nightly Training & Security Audit (`POST /internal/v1/nightly-extract`)."""

    page_id: str | None = Field(default=None)
    conversations: list[dict[str, Any]] = Field(default_factory=list)


@router.post("/internal/v1/consult")
async def internal_consult(
    body: InternalConsultRequest,
    x_internal_token: str | None = Header(default=None),
    x_correlation_id: str | None = Header(default=None),
) -> JSONResponse:
    """Hot-Path LangGraph state-driven consultation & topic-anchor contextualization."""
    bind_context(correlation_id=_correlation_from(x_correlation_id))
    try:
        if not verify_internal_token(x_internal_token):
            log.warning("internal consult rejected: invalid X-Internal-Token", stage="langgraph-consult")
            return JSONResponse(
                status_code=403,
                content={"error": "Forbidden: invalid X-Internal-Token"},
            )

        result = run_sales_consultation_graph(body.model_dump())
        log.info(
            "langgraph consult served",
            stage="langgraph-consult",
            funnel_stage=result.get("funnel_stage"),
            topic_family=result.get("topic_family"),
            honorific=result.get("honorific"),
            is_profile_update=result.get("is_profile_update_turn"),
        )
        return JSONResponse(status_code=200, content=result)
    finally:
        clear_context()


@router.post("/internal/v1/nightly-extract")
async def internal_nightly_extract(
    body: InternalNightlyExtractRequest,
    x_internal_token: str | None = Header(default=None),
    x_correlation_id: str | None = Header(default=None),
) -> JSONResponse:
    """Cold-Path LangGraph nightly sample extraction & independent AI Security Auditor."""
    bind_context(correlation_id=_correlation_from(x_correlation_id))
    try:
        if not verify_internal_token(x_internal_token):
            log.warning("internal nightly-extract rejected: invalid X-Internal-Token", stage="langgraph-nightly")
            return JSONResponse(
                status_code=403,
                content={"error": "Forbidden: invalid X-Internal-Token"},
            )

        result = run_nightly_training_graph(body.model_dump())
        log.info(
            "langgraph nightly-extract served",
            stage="langgraph-nightly",
            passed_count=result.get("passed_count"),
            rejected_count=result.get("rejected_count"),
        )
        return JSONResponse(status_code=200, content=result)
    finally:
        clear_context()


@router.get("/healthz")
async def healthz() -> JSONResponse:
    """Liveness: always 200 while the process is up."""
    return JSONResponse(status_code=200, content={"status": "ok"})


@router.get("/readyz")
async def readyz() -> JSONResponse:
    """Readiness: 200 (stubs make us ready) + optional ML lib importability."""
    optional = {
        name: importlib.util.find_spec(name) is not None
        for name in (_OPTIONAL_ML_LIBS + ["langgraph"])
    }
    settings = get_settings()
    return JSONResponse(
        status_code=200,
        content={
            "status": "ready",
            "ready": True,  # stub engines always keep the service usable
            "optional_ml_libs": optional,
            "persist_artifacts": settings.docmind_persist_artifacts,
        },
    )
