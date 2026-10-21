-- ============================================================================
-- V12: Staging Queue (PENDING_REVIEW) & AI Security Auditor metadata
-- Supports Dual-Loop Nightly Self-Training (Cold Path) with 1-Click Approval.
-- ============================================================================

ALTER TABLE learned_rules
    ADD COLUMN IF NOT EXISTS review_status VARCHAR(32) NOT NULL DEFAULT 'APPROVED',
    ADD COLUMN IF NOT EXISTS security_audit_notes TEXT NULL;

CREATE INDEX IF NOT EXISTS idx_learned_rules_review_status
    ON learned_rules (review_status);
