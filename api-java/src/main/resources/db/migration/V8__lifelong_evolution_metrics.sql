-- ============================================================
-- DocMind api-java — Flyway V8: Lifelong Safe Self-Evolving AI Metrics
--
-- Bổ sung các chỉ số tiến hóa dài hạn, điểm tín nhiệm thực chiến (confidence_score),
-- lịch sử phiên bản 1-click Rollback (version, previous_rule_text), cầu chì tự ngắt
-- (quarantined_reason) và thống kê chuyển đổi SĐT/đặt lịch (success_count / conversion_count).
-- ============================================================

ALTER TABLE learned_rules
    ADD COLUMN IF NOT EXISTS confidence_score DOUBLE PRECISION NOT NULL DEFAULT 0.85,
    ADD COLUMN IF NOT EXISTS applied_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS success_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS fail_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS previous_rule_text TEXT NULL,
    ADD COLUMN IF NOT EXISTS quarantined_reason TEXT NULL,
    ADD COLUMN IF NOT EXISTS last_applied_at TIMESTAMPTZ NULL;

CREATE INDEX IF NOT EXISTS idx_learned_rules_active_confidence
    ON learned_rules (active, confidence_score DESC);

ALTER TABLE agent_skills
    ADD COLUMN IF NOT EXISTS applied_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS conversion_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS confidence_score DOUBLE PRECISION NOT NULL DEFAULT 0.90;

CREATE INDEX IF NOT EXISTS idx_agent_skills_active_confidence_conversion
    ON agent_skills (active, conversion_count DESC, confidence_score DESC);
