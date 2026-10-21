"""Empirical Challenger Verification Harness for M2-R2 Remediation.

Targeting:
1. Task 1: GET /api/documents with invalid, null, undefined, and SQL injection page_id inputs.
   Asserts ZERO documents returned.
2. Task 2: GET /api/skills and GET /api/harness/runs without page_id.
   Asserts ZERO tenant-specific items leak.
3. Extended adversarial stress on skills, harness runs, and conversations.
"""

import os
import uuid
import pytest
import requests

API_URL = os.getenv("API_JAVA_URL", "http://localhost:8090")


class TestAdversarialDocumentsZeroLeakage:
    """Task 1: Verify ZERO documents leak across invalid page_id inputs."""

    MANDATORY_ADVERSARIAL_INPUTS = [
        "invalid",
        "null",
        "undefined",
        "' OR 1=1 --",
    ]

    EXTENDED_ADVERSARIAL_INPUTS = [
        "",
        "   ",
        "\" OR \"\"=\"",
        "; DROP TABLE documents; --",
        "0",
        "-1",
        "none",
        "all",
        "true",
        "false",
        "../../../etc/passwd",
        "<script>alert(1)</script>",
        "00000000-0000-0000-0000-000000000000",
        "NaN",
        "{}",
        "[]",
    ]

    @pytest.mark.parametrize("page_id", MANDATORY_ADVERSARIAL_INPUTS)
    def test_mandatory_adversarial_page_id_zero_documents(self, page_id):
        """GET /api/documents?page_id={invalid,null,undefined,' OR 1=1 --'} must return 0 documents."""
        res = requests.get(f"{API_URL}/api/documents", params={"page_id": page_id}, timeout=5)
        assert res.status_code == 200, f"Expected HTTP 200, got {res.status_code}: {res.text}"
        data = res.json()
        assert isinstance(data, list), f"Expected list response, got {type(data)}"
        assert len(data) == 0, (
            f"SECURITY BREACH / DATA LEAK: GET /api/documents?page_id={page_id} "
            f"returned {len(data)} documents: {[d.get('id') for d in data]}"
        )

    @pytest.mark.parametrize("page_id", EXTENDED_ADVERSARIAL_INPUTS)
    def test_extended_adversarial_page_id_zero_documents(self, page_id):
        """GET /api/documents with boundary/injection inputs must return 0 documents."""
        res = requests.get(f"{API_URL}/api/documents", params={"page_id": page_id}, timeout=5)
        assert res.status_code == 200
        data = res.json()
        assert isinstance(data, list)
        assert len(data) == 0, (
            f"SECURITY BREACH: GET /api/documents?page_id={page_id} returned {len(data)} documents!"
        )

    def test_documents_without_page_id_returns_zero(self):
        """GET /api/documents with no parameter must return 0 documents (no multi-tenant dumping)."""
        res = requests.get(f"{API_URL}/api/documents", timeout=5)
        assert res.status_code == 200
        data = res.json()
        assert isinstance(data, list)
        assert len(data) == 0, f"Unscoped /api/documents returned {len(data)} documents!"


class TestAdversarialSkillsIsolation:
    """Task 2a: Verify zero tenant-specific skills leak when page_id is omitted or invalid."""

    def test_skills_without_page_id_returns_zero_tenant_skills(self):
        """GET /api/skills without page_id must return ONLY global skills (page_id is null)."""
        res = requests.get(f"{API_URL}/api/skills", timeout=5)
        assert res.status_code == 200, f"Expected HTTP 200, got {res.status_code}: {res.text}"
        skills = res.json()
        assert isinstance(skills, list)
        
        leaked_tenant_skills = [
            s for s in skills
            if s.get("page_id") is not None and str(s.get("page_id")).strip() != ""
        ]
        assert len(leaked_tenant_skills) == 0, (
            f"SECURITY BREACH: GET /api/skills without page_id leaked {len(leaked_tenant_skills)} "
            f"tenant-specific skills: {[s.get('id') for s in leaked_tenant_skills]}"
        )

    @pytest.mark.parametrize("bad_page_id", [
        "",
        "   ",
        "invalid",
        "null",
        "undefined",
        "' OR 1=1 --",
        "00000000-0000-0000-0000-000000000000",
    ])
    def test_skills_with_adversarial_page_id_zero_tenant_leak(self, bad_page_id):
        """GET /api/skills with adversarial page_id must never leak skills from actual tenants."""
        res = requests.get(f"{API_URL}/api/skills", params={"page_id": bad_page_id}, timeout=5)
        assert res.status_code == 200
        skills = res.json()
        assert isinstance(skills, list)
        # Any returned skill must either be global or have matching page_id (which here is bad_page_id)
        leaked = [
            s for s in skills
            if s.get("page_id") is not None 
            and str(s.get("page_id")).strip() != ""
            and str(s.get("page_id")).strip() != bad_page_id
        ]
        assert len(leaked) == 0, (
            f"Cross-tenant skill leak on page_id={bad_page_id}: leaked {leaked}"
        )


class TestAdversarialHarnessRunsIsolation:
    """Task 2b: Verify zero tenant-specific harness runs leak when page_id is omitted or invalid."""

    def test_harness_runs_without_page_id_returns_zero_tenant_runs(self):
        """GET /api/harness/runs without page_id must return ONLY global runs (page_id is null)."""
        res = requests.get(f"{API_URL}/api/harness/runs", timeout=5)
        assert res.status_code == 200, f"Expected HTTP 200, got {res.status_code}: {res.text}"
        runs = res.json()
        assert isinstance(runs, list)

        leaked_tenant_runs = [
            r for r in runs
            if r.get("page_id") is not None and str(r.get("page_id")).strip() != ""
        ]
        assert len(leaked_tenant_runs) == 0, (
            f"SECURITY BREACH: GET /api/harness/runs without page_id leaked {len(leaked_tenant_runs)} "
            f"tenant-specific runs: {[r.get('id') for r in leaked_tenant_runs]}"
        )

    @pytest.mark.parametrize("bad_page_id", [
        "",
        "   ",
        "invalid",
        "null",
        "undefined",
        "' OR 1=1 --",
        "00000000-0000-0000-0000-000000000000",
    ])
    def test_harness_runs_with_adversarial_page_id_zero_tenant_leak(self, bad_page_id):
        """GET /api/harness/runs with adversarial page_id must never leak runs from other tenants."""
        res = requests.get(f"{API_URL}/api/harness/runs", params={"page_id": bad_page_id}, timeout=5)
        assert res.status_code == 200
        runs = res.json()
        assert isinstance(runs, list)
        leaked = [
            r for r in runs
            if r.get("page_id") is not None
            and str(r.get("page_id")).strip() != ""
            and str(r.get("page_id")).strip() != bad_page_id
        ]
        assert len(leaked) == 0, (
            f"Cross-tenant harness run leak on page_id={bad_page_id}: leaked {leaked}"
        )
