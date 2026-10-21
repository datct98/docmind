"""Adversarial Challenger Stress Tests for Multi-Tenant Isolation & Doc-Service Security.

Empirical verification harness targeting:
1. Multi-tenant isolation boundary attacks on GET /api/documents and GET /api/conversations.
2. Doc-service-py token enforcement on /v1/embed, /v1/parse, and job endpoints.
"""

import io
import os
import uuid
import pytest
import requests

API_URL = os.getenv("API_JAVA_URL", "http://localhost:8090")
DOC_SERVICE_URL = os.getenv("DOC_SERVICE_URL", "http://localhost:8091")
INTERNAL_TOKEN = os.getenv("DOCMIND_INTERNAL_TOKEN", "docmind-internal-secret-token-2026")


class TestAdversarialMultiTenantProbing:
    """Stress-tests multi-tenant isolation against varied malicious, malformed, and non-UUID inputs."""

    ADVERSARIAL_PAGE_IDS = [
        "invalid",
        "null",
        "undefined",
        "0",
        "-1",
        "none",
        "all",
        "true",
        "false",
        "' OR 1=1 --",
        "\" OR \"\"=\"",
        "../../../etc/passwd",
        "<script>alert(1)</script>",
        "00000000-0000-0000-0000-000000000000",
    ]

    def test_conversations_strict_zero_leakage_on_adversarial_page_id(self):
        """GET /api/conversations must NEVER return cross-tenant sessions on adversarial page_id."""
        for bad_id in self.ADVERSARIAL_PAGE_IDS:
            res = requests.get(f"{API_URL}/api/conversations?page_id={bad_id}", timeout=5)
            assert res.status_code == 200, f"Expected 200 OK, got {res.status_code}"
            data = res.json()
            assert isinstance(data, list)
            assert len(data) == 0, (
                f"LEAK DETECTED: /api/conversations?page_id={bad_id} returned {len(data)} sessions!"
            )

    def test_conversations_empty_without_param(self):
        """GET /api/conversations with no param or blank param returns empty list."""
        for url in [
            f"{API_URL}/api/conversations",
            f"{API_URL}/api/conversations?page_id=",
            f"{API_URL}/api/conversations?page_id=%20%20",
        ]:
            res = requests.get(url, timeout=5)
            assert res.status_code == 200
            data = res.json()
            assert len(data) == 0, f"Leak detected on {url}: {len(data)} sessions returned"

    def test_documents_strict_zero_leakage_on_adversarial_page_id(self):
        """GET /api/documents must NEVER dump all tenant documents on non-UUID or malicious page_id.
        
        CRITICAL VULNERABILITY CHECK:
        If page_id cannot be parsed as UUID (e.g. 'invalid', 'null', '' OR 1=1--'),
        DocumentService.listAll must NOT fall back to findAllByOrderByCreatedAtDesc().
        """
        leaks = []
        for bad_id in self.ADVERSARIAL_PAGE_IDS:
            if bad_id == "00000000-0000-0000-0000-000000000000":
                # Valid UUID format but non-existent tenant
                continue
            res = requests.get(f"{API_URL}/api/documents?page_id={bad_id}", timeout=5)
            assert res.status_code == 200, f"Expected 200 OK, got {res.status_code}"
            data = res.json()
            assert isinstance(data, list)
            if len(data) > 0:
                leaks.append((bad_id, len(data)))

        assert not leaks, (
            f"CRITICAL VULNERABILITY: GET /api/documents leaked all database documents on invalid page_id values: {leaks}"
        )

    def test_documents_empty_without_param(self):
        """GET /api/documents with no param or blank param returns empty list."""
        for url in [
            f"{API_URL}/api/documents",
            f"{API_URL}/api/documents?page_id=",
            f"{API_URL}/api/documents?page_id=%20%20",
        ]:
            res = requests.get(url, timeout=5)
            assert res.status_code == 200
            data = res.json()
            assert len(data) == 0, f"Leak detected on {url}: {len(data)} documents returned"


class TestAdversarialDocServiceTokenProbing:
    """Stress-tests token authentication on doc-service-py endpoints."""

    INVALID_HEADER_COMBOS = [
        ("no_token", {}),
        ("empty_x_internal_token", {"X-Internal-Token": ""}),
        ("bogus_x_internal_token", {"X-Internal-Token": "invalid-token-12345"}),
        ("truncated_token", {"X-Internal-Token": INTERNAL_TOKEN[:-4]}),
        ("extended_token", {"X-Internal-Token": INTERNAL_TOKEN + "_extra"}),
        ("empty_bearer", {"Authorization": "Bearer"}),
        ("bogus_bearer", {"Authorization": "Bearer fake-token-xyz"}),
        ("basic_auth", {"Authorization": "Basic dXNlcjpwYXNz"}),
    ]

    def test_embed_token_rejection(self):
        """POST /v1/embed must return 401 on missing, empty, or invalid tokens."""
        for label, headers in self.INVALID_HEADER_COMBOS:
            res = requests.post(
                f"{DOC_SERVICE_URL}/v1/embed",
                json={"texts": ["security check"]},
                headers=headers,
                timeout=5,
            )
            assert res.status_code == 401, (
                f"/v1/embed accepted invalid token ({label}): got status {res.status_code}"
            )
            data = res.json()
            assert "Unauthorized" in data.get("error", "")

    def test_embed_valid_token_acceptance(self):
        """POST /v1/embed accepts valid token via X-Internal-Token and Authorization: Bearer."""
        # X-Internal-Token
        res1 = requests.post(
            f"{DOC_SERVICE_URL}/v1/embed",
            json={"texts": ["hello world"]},
            headers={"X-Internal-Token": INTERNAL_TOKEN},
            timeout=5,
        )
        assert res1.status_code == 200, f"Expected 200, got {res1.status_code}"
        assert res1.json().get("dim") == 384

        # Bearer
        res2 = requests.post(
            f"{DOC_SERVICE_URL}/v1/embed",
            json={"texts": ["hello world"]},
            headers={"Authorization": f"Bearer {INTERNAL_TOKEN}"},
            timeout=5,
        )
        assert res2.status_code == 200, f"Expected 200, got {res2.status_code}"
        assert res2.json().get("dim") == 384

    def test_parse_token_rejection(self):
        """POST /v1/parse must return 401 on missing, empty, or invalid tokens."""
        for label, headers in self.INVALID_HEADER_COMBOS:
            fake_file = io.BytesIO(b"# Security Probe Markdown")
            res = requests.post(
                f"{DOC_SERVICE_URL}/v1/parse",
                files={"file": ("test.md", fake_file, "text/markdown")},
                headers=headers,
                timeout=5,
            )
            assert res.status_code == 401, (
                f"/v1/parse accepted invalid token ({label}): got status {res.status_code}"
            )

    def test_jobs_endpoints_token_rejection(self):
        """GET /v1/jobs/{job_id} and /result must return 401 without valid token."""
        for path in ["/v1/jobs/probe-job-id", "/v1/jobs/probe-job-id/result"]:
            for label, headers in self.INVALID_HEADER_COMBOS:
                res = requests.get(f"{DOC_SERVICE_URL}{path}", headers=headers, timeout=5)
                assert res.status_code == 401, (
                    f"{path} accepted invalid token ({label}): got status {res.status_code}"
                )
