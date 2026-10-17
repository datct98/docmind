# DocMind

A document-grounded RAG system (NotebookLLM-style): upload documents, then ask
questions answered **only** from what was uploaded, with citations back to the
exact page / block. DocMind lives inside the `digital-spirituality` monorepo
alongside the sibling `horoscope` service.

---

## Architecture

Two services plus Postgres:

- **`api-java`** — Spring Boot 4 **orchestrator** and the **chat / RAG brain**.
  Owns the database (via Flyway + JPA), the ingest job lifecycle, chunking,
  embedding + BM25 indexing, hybrid retrieval, reranking, LLM answer generation,
  and citation validation. Listens on **port 8090**.
- **`doc-service-py`** — FastAPI **document parser**. Takes a raw file and returns
  canonical blocks (see [`docs/canonical-schema.md`](docs/canonical-schema.md)).
  It routes a file to the right engine (PyMuPDF / Docling / PaddleOCR+PP-Structure
  for scans / native office parsers), then normalizes everything into the shared
  canonical schema. Listens on **port 8091**.
- **`frontend`** — Vue 3 + Vite **web client**. Giao diện tải lên file (kéo thả), theo dõi trạng thái real-time (`READY`, `PARSING`, `FAILED`), phân bổ engine, và chat hỏi đáp RAG với trích dẫn citations. Chạy tại **port 3000**.
- **`postgres`** — PostgreSQL 16 + **pgvector** (host **port 5433** -> 5432).

**The chat path is Java-only.** During chat, `api-java` reads already-parsed,
already-indexed data from Postgres and talks to the LLM/embedding providers
itself. It **never calls** `doc-service-py` on the chat path — the Python service
is only involved during **ingest**.

```
                 upload / chat (HTTP)
                         |
                         v
+--------------------------------------------------+
|  frontend (3000)  — Vue 3 / Vite UI              |
+--------------------------------------------------+
                         |
                         v
+--------------------------------------------------+
|  api-java (8090)  — orchestrator + chat/RAG      |
|  ingest jobs | chunking | embedding | BM25 |     |
|  hybrid retrieval | rerank | LLM | citations     |
+------------------+------------------+------------+
                   |                  |
   parse (ingest   |                  | SQL + pgvector
    only)          v                  v
     +---------------------+   +---------------------+
     | doc-service-py 8091 |   |  postgres 5433->5432|
     |  parser + OCR +     |   |  pgvector extension |
     |  canonical blocks   |   |  Flyway-owned tables|
     +---------------------+   +---------------------+
```

---

## REST API contract

### `doc-service-py` (parser) — async job pattern

| Method | Path                     | Purpose |
|--------|--------------------------|---------|
| GET    | `/healthz`               | Liveness. |
| GET    | `/readyz`                | Readiness (dependencies / lazy models). |
| POST   | `/v1/parse`              | Submit a file for parsing. Returns **202** with `{ "job_id": "..." }`. |
| GET    | `/v1/jobs/{id}`          | Job status: `queued` \| `running` \| `succeeded` \| `failed`. |
| GET    | `/v1/jobs/{id}/result`   | Canonical `Document` JSON (see canonical schema) once `succeeded`. |

### `api-java` (orchestrator) — ingest async + chat

| Method | Path                     | Purpose |
|--------|--------------------------|---------|
| POST   | `/api/documents`         | Upload a file (`multipart/form-data`, field `file`) -> starts ingest. Returns **201** with `{ "document_id": "...", "job_id": "...", "status": "..." }`. |
| GET    | `/api/documents`         | List all uploaded documents with status, pages count, and engine breakdown. |
| GET    | `/api/documents/{id}`    | Ingest job status (mirrors parser states + indexing). |
| DELETE | `/api/documents/{id}`    | Delete a document and cascade delete all its blocks and embedded chunks (pgvector + tsvector). Returns **204 No Content**. |
| POST   | `/api/chat`              | Ask a question. Request `{ "question": "...", "document_ids": [...], "top_k": N }`; response includes the answer and `citations[]` referencing `document_id` + `page` + `bbox` + `block_ids`. |

The exact block/chunk fields these endpoints exchange are defined in
[`docs/canonical-schema.md`](docs/canonical-schema.md) — read that first.

---

## Ingest flow

```
upload (POST /api/documents to api-java)
   |
   v
validate / hash (sha256) / detect mime     [antivirus hook: later]
   |
   v
api-java calls doc-service-py POST /v1/parse  -> 202 job_id
   |
   v
doc-service-py routes by file type:
   .md/.html/.docx/.pptx/.xlsx -> office parser
   .pdf -> classify:
       text simple   -> pymupdf
       text complex  -> docling
       scanned       -> paddleocr (render page -> OCR + PP-Structure)
       mixed         -> per page: text parser or paddleocr
   |
   v
Normalizer -> canonical Document { blocks[] }  (see canonical-schema.md)
   |
   v
api-java: chunk (inherit document_id/heading_path/block_ids/page/bbox)
   |
   v
embedding + BM25 index  (pgvector + text index in Postgres)
```

## Chat flow (Java-only; never calls Python)

```
question (POST /api/chat to api-java)
   |
   v
embed query + BM25 query  (hybrid)
   |
   v
hybrid retrieval over Postgres (pgvector + BM25)
   |
   v
reranker
   |
   v
LLM answer with citations  (LLM via Gemini key, or stub LLM if no key)
   |
   v
citation validation (answer spans must map to retrieved chunks)
```

---

## Observability / correlation-id model

Every ingest and chat request carries a **correlation id** (generated by
`api-java` if absent, propagated to `doc-service-py` as a header, and attached
to the parse job). Both services emit **structured JSON logs** that include the
correlation id, the `job_id`, the service name, and the stage, so a single
request can be traced end-to-end across Java -> Python -> Postgres. This is
deliberate: the pipeline has many stages, so logs must make it obvious **where**
a failure happened. Log level is controlled by `DOCMIND_LOG_LEVEL`.

---

## Deliberate scope notes

- **MinerU is intentionally DEFERRED** until its license is verified. It is not
  wired into the parser router and is **not** a valid `parse_engine` value in the
  canonical schema. PyMuPDF / Docling / PaddleOCR / office parsers cover the
  current paths.
- **Formula handling targets LaTeX output.** A `formula` block carries its LaTeX
  in the `latex` field (non-null only for `type == "formula"`); see the canonical
  schema for the exact rule.
- **Chat uses a stub LLM** until a Gemini API key is set via `GEMINI_API_KEYS`,
  so the system boots and answers (with placeholder generation) with no key.
- Heavy ML dependencies in the Python service are **lazy-imported behind stubs**
  so the service boots and tests pass CPU-only without model downloads.

See [`db/README.md`](db/README.md) for the init-SQL vs. Flyway ownership split
and [`docs/canonical-schema.md`](docs/canonical-schema.md) for the shared data
contract.
