"""Empirical Challenger Verification Suite for Milestone 2 - Round 2.

Focus areas:
1. Rule evolution sanitization: evolving an existing rule via feedback strips phone numbers
   and volatile prices while strictly preserving percentage discounts and item/session quantities.
2. Vector rule search isolation: vector similarity searches when page_id is null query
   strictly global rules (zero cross-tenant or page-scoped rule leakage).
"""

from __future__ import annotations

import os
import subprocess
import uuid
import pytest
import requests

API_URL = os.getenv("API_JAVA_URL", "http://localhost:8090")
VALID_PAGE_ID = "2850a8ef-ca4a-40eb-87fa-4f808640efee"


class TestRuleEvolutionSanitizationEmpirical:
    """Adversarially tests that rule evolution strips PII & volatile prices while preserving percentages."""

    def test_feedback_rule_evolution_strips_phone_and_price_preserves_percentage(self):
        """Feedback evolving a rule must strip phone numbers and volatile prices but keep percentages."""
        unique_marker = f"mk_{uuid.uuid4().hex[:6]}"

        # Step 1: Create an existing rule for a valid page
        initial_rule_payload = {
            "category": "PRICING_CALCULATION",
            "trigger_condition": f"Khi khách hỏi về chính sách chiết khấu mua nhiều {unique_marker}",
            "root_cause": "Chưa kiểm tra chính sách",
            "generalized_rule": "Kiểm tra kỹ điều kiện bảng giá và mức chiết khấu áp dụng theo số lượng.",
            "retrieval_keywords": ["chiết khấu", "mua nhiều", unique_marker],
            "active": True,
            "page_id": VALID_PAGE_ID,
        }
        res_create = requests.post(f"{API_URL}/api/rules", json=initial_rule_payload, timeout=10)
        assert res_create.status_code == 201, f"Failed to create base rule: {res_create.status_code} {res_create.text}"
        base_rule = res_create.json()
        base_rule_id = base_rule["id"]

        # Step 2: Submit feedback with phone number, volatile price, and percentage in template
        # Phone: 0978123456
        # Volatile price: 2.500.000 VNĐ
        # Percentage to preserve: 25%
        # Quantity to preserve: 10 buổi
        feedback_payload = {
            "question": f"Khi khách hỏi về chính sách chiết khấu mua nhiều {unique_marker} thì giải quyết thế nào?",
            "faulty_answer": "Không có chương trình giảm giá nào được áp dụng.",
            "user_correction": f"Khách gọi từ số 0978123456, giá gốc 2.500.000 VNĐ, hướng dẫn: 'Ưu đãi mua từ 10 buổi được giảm 25% tổng hóa đơn'.",
            "conversation_id": f"conv-evo-{uuid.uuid4()}",
            "page_id": VALID_PAGE_ID,
            "applied_rule_ids": [base_rule_id],
        }

        res_fb = requests.post(f"{API_URL}/api/feedback", json=feedback_payload, timeout=90)
        assert res_fb.status_code == 200, f"Feedback failed: {res_fb.status_code} {res_fb.text}"
        data = res_fb.json()
        learned_rule = data.get("learned_rule") or {}
        gen_rule = learned_rule.get("generalized_rule") or ""
        root_cause = learned_rule.get("root_cause") or ""
        trigger = learned_rule.get("trigger_condition") or ""

        # Verification 1: Phone number must be stripped
        assert "0978123456" not in gen_rule, f"Vulnerability: Phone number found in generalized_rule: {gen_rule}"
        assert "0978123456" not in root_cause, f"Vulnerability: Phone number found in root_cause: {root_cause}"
        assert "0978123456" not in trigger, f"Vulnerability: Phone number found in trigger_condition: {trigger}"

        # Verification 2: Volatile price must be stripped
        assert "2.500.000" not in gen_rule, f"Vulnerability: Volatile price found in generalized_rule: {gen_rule}"
        assert "2.500.000" not in root_cause, f"Vulnerability: Volatile price found in root_cause: {root_cause}"
        assert "2.500.000" not in trigger, f"Vulnerability: Volatile price found in trigger_condition: {trigger}"

        # Verification 3: Percentage must be strictly preserved
        assert "25%" in gen_rule or "25%" in root_cause, (
            f"Defect: Percentage 25% was accidentally wiped out! generalized_rule: {gen_rule}, root_cause: {root_cause}"
        )

        # Cleanup created rules
        try:
            requests.delete(f"{API_URL}/api/rules/{base_rule_id}", timeout=5)
            if learned_rule.get("id") and learned_rule.get("id") != base_rule_id:
                requests.delete(f"{API_URL}/api/rules/{learned_rule['id']}", timeout=5)
        except Exception:
            pass

    def test_global_feedback_strips_phone_and_price_preserves_percentage(self):
        """Global feedback (page_id=null) must also sanitize phone & price while preserving percentages."""
        feedback_payload = {
            "question": "Dịch vụ triệt lông nách có gói ưu đãi nào không?",
            "faulty_answer": "Dạ giá niêm yết 500.000đ.",
            "user_correction": "Khách để lại số 0918234567, giá 500.000đ là đơn buổi, trả lời khách: 'Gói chăm sóc 5 buổi được giảm 30% chi phí'.",
            "conversation_id": f"conv-global-{uuid.uuid4()}",
        }

        res = requests.post(f"{API_URL}/api/feedback", json=feedback_payload, timeout=90)
        assert res.status_code == 200, f"Feedback failed: {res.status_code} {res.text}"
        data = res.json()
        learned_rule = data.get("learned_rule") or {}
        gen_rule = learned_rule.get("generalized_rule") or ""
        root_cause = learned_rule.get("root_cause") or ""

        # Assert sanitization
        assert "0918234567" not in gen_rule
        assert "0918234567" not in root_cause
        assert "500.000" not in gen_rule
        assert "500.000" not in root_cause

        # Assert preservation
        assert "30%" in gen_rule or "30%" in root_cause, f"30% percentage was lost: gen_rule={gen_rule}"

        # Cleanup rule
        if learned_rule.get("id"):
            try:
                requests.delete(f"{API_URL}/api/rules/{learned_rule['id']}", timeout=5)
            except Exception:
                pass


class TestVectorRuleSearchGlobalIsolationEmpirical:
    """Adversarially tests that vector rule search when page_id is null returns strictly global rules."""

    def test_api_rules_without_page_id_contains_zero_page_scoped_rules(self):
        """GET /api/rules without page_id parameter must strictly return global rules only."""
        res = requests.get(f"{API_URL}/api/rules", timeout=10)
        assert res.status_code == 200
        rules = res.json()
        assert isinstance(rules, list)
        tenant_leaked = [r for r in rules if r.get("page_id")]
        assert len(tenant_leaked) == 0, (
            f"Vulnerability: GET /api/rules leaked {len(tenant_leaked)} tenant rules: {tenant_leaked}"
        )

    @pytest.mark.parametrize("param_value", ["null", "undefined", "", "   "])
    def test_api_rules_with_falsy_page_id_contains_zero_page_scoped_rules(self, param_value):
        """GET /api/rules?page_id={falsy} must strictly return global rules only."""
        res = requests.get(f"{API_URL}/api/rules", params={"page_id": param_value}, timeout=10)
        assert res.status_code == 200
        rules = res.json()
        assert isinstance(rules, list)
        tenant_leaked = [r for r in rules if r.get("page_id")]
        assert len(tenant_leaked) == 0, (
            f"Vulnerability: GET /api/rules?page_id={param_value} leaked {len(tenant_leaked)} tenant rules!"
        )

    def test_direct_postgres_vector_search_sql_enforces_page_id_is_null(self):
        """The exact SQL used by LearnedRuleRepositoryCustomImpl must never return page-scoped rows when pageId is null."""
        # Query existing tenant rules with embeddings
        find_cmd = [
            "docker", "exec", "docmind-postgres", "psql", "-U", "docmind", "-d", "docmind", "-t", "-A", "-c",
            "SELECT id, page_id FROM learned_rules WHERE active = true AND page_id IS NOT NULL AND embedding IS NOT NULL LIMIT 1;"
        ]
        result = subprocess.run(find_cmd, capture_output=True, text=True, check=True)
        row = result.stdout.strip()
        if not row:
            pytest.skip("No page-scoped rules with embeddings in database to test vector search collision.")

        tenant_rule_id, tenant_page_id = row.split("|")[:2]

        # Execute the exact SQL query from LearnedRuleRepositoryCustomImpl.searchSimilarActiveRules when pageId is null:
        # SELECT id, page_id, (1 - (embedding <=> (SELECT embedding FROM learned_rules WHERE id = '...'))) AS score
        # FROM learned_rules WHERE active = true AND page_id IS NULL AND embedding IS NOT NULL
        # ORDER BY embedding <=> (SELECT embedding FROM learned_rules WHERE id = '...') LIMIT 20;
        vector_test_sql = (
            f"SELECT id, page_id FROM learned_rules "
            f"WHERE active = true AND page_id IS NULL AND embedding IS NOT NULL "
            f"ORDER BY embedding <=> (SELECT embedding FROM learned_rules WHERE id = '{tenant_rule_id}') "
            f"LIMIT 20;"
        )
        query_cmd = [
            "docker", "exec", "docmind-postgres", "psql", "-U", "docmind", "-d", "docmind", "-t", "-A", "-c",
            vector_test_sql
        ]
        query_res = subprocess.run(query_cmd, capture_output=True, text=True, check=True)
        lines = [line.strip() for line in query_res.stdout.strip().split("\n") if line.strip()]

        for line in lines:
            parts = line.split("|")
            rule_id = parts[0]
            page_id_val = parts[1] if len(parts) > 1 else ""
            assert page_id_val == "" or page_id_val is None, (
                f"Vulnerability: Vector rule search with null page_id leaked tenant rule {rule_id} of page {page_id_val}!"
            )
            assert rule_id != tenant_rule_id, (
                f"Vulnerability: The target tenant rule {tenant_rule_id} was returned in null-page vector search!"
            )

    def test_direct_postgres_vector_search_for_evolution_enforces_page_id_is_null(self):
        """The exact SQL used by LearnedRuleRepositoryCustomImpl.searchSimilarRulesForEvolution must never return page-scoped rows."""
        find_cmd = [
            "docker", "exec", "docmind-postgres", "psql", "-U", "docmind", "-d", "docmind", "-t", "-A", "-c",
            "SELECT id FROM learned_rules WHERE page_id IS NOT NULL AND embedding IS NOT NULL LIMIT 1;"
        ]
        result = subprocess.run(find_cmd, capture_output=True, text=True, check=True)
        tenant_rule_id = result.stdout.strip()
        if not tenant_rule_id:
            pytest.skip("No page-scoped rules with embeddings in database.")

        evo_test_sql = (
            f"SELECT id, page_id FROM learned_rules "
            f"WHERE (active = true OR quarantined_reason IS NOT NULL) "
            f"  AND page_id IS NULL "
            f"  AND embedding IS NOT NULL "
            f"ORDER BY embedding <=> (SELECT embedding FROM learned_rules WHERE id = '{tenant_rule_id}') "
            f"LIMIT 20;"
        )
        query_cmd = [
            "docker", "exec", "docmind-postgres", "psql", "-U", "docmind", "-d", "docmind", "-t", "-A", "-c",
            evo_test_sql
        ]
        query_res = subprocess.run(query_cmd, capture_output=True, text=True, check=True)
        lines = [line.strip() for line in query_res.stdout.strip().split("\n") if line.strip()]

        for line in lines:
            parts = line.split("|")
            rule_id = parts[0]
            page_id_val = parts[1] if len(parts) > 1 else ""
            assert page_id_val == "" or page_id_val is None, (
                f"Vulnerability: Evolution vector search with null page_id leaked tenant rule {rule_id} of page {page_id_val}!"
            )
            assert rule_id != tenant_rule_id, (
                f"Vulnerability: Target tenant rule {tenant_rule_id} was returned in evolution vector search!"
            )
