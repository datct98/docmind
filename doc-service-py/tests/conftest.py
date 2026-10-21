"""Pytest fixtures + tiny byte samples for the doc-service-py tests.

Run from the service dir:
    python -m pytest -q
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.jobs import get_job_store
from app.main import create_app


@pytest.fixture()
def client() -> TestClient:
    """A fresh FastAPI TestClient with a clean in-memory store per test."""
    # Reset the process-wide store so cache/job state does not leak between tests.
    store = get_job_store()
    store._jobs.clear()  # noqa: SLF001 - test-only reset
    store._cache.clear()  # noqa: SLF001 - test-only reset
    app = create_app()
    token = get_settings().docmind_internal_token
    with TestClient(app, headers={"X-Internal-Token": token}) as c:
        yield c


# -- tiny magic-byte samples (content decides mime, NOT the name) -----------

# A minimal-ish PDF: starts with the %PDF magic and has one /Type /Page object.
PDF_BYTES = (
    b"%PDF-1.4\n"
    b"1 0 obj<< /Type /Catalog /Pages 2 0 R >>endobj\n"
    b"2 0 obj<< /Type /Pages /Kids [3 0 R] /Count 1 >>endobj\n"
    b"3 0 obj<< /Type /Page /Parent 2 0 R >>endobj\n"
    b"BT (hello world) Tj ET\n"
    b"trailer<< /Root 1 0 R >>\n%%EOF\n"
)

# A 1x1 PNG (valid 8-byte PNG signature is enough for magic detection).
PNG_BYTES = (
    b"\x89PNG\r\n\x1a\n"
    b"\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00"
    b"\x1f\x15\xc4\x89\x00\x00\x00\nIDATx\x9cc\x00\x01\x00\x00\x05\x00\x01"
    b"\x0d\n-\xb4\x00\x00\x00\x00IEND\xaeB`\x82"
)

MD_BYTES = b"# Title\n\nSome **markdown** body with a list:\n- one\n- two\n"
