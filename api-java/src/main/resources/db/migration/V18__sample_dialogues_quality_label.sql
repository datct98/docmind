-- =========================================================================
-- V18: Add quality_label column to sample_dialogues for Playbook v3.0.1
-- =========================================================================

ALTER TABLE sample_dialogues
    ADD COLUMN IF NOT EXISTS quality_label VARCHAR(64) DEFAULT 'APPROVED_GOOD';
