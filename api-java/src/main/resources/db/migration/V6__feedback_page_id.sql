-- ============================================================
-- DocMind api-java — Flyway V6: Feedback Logs Page Id
--
-- Bổ sung page_id vào feedback_logs để định danh trang/kênh
-- khi người dùng gửi phản hồi sửa sai (Báo sai & Dạy AI).
-- ============================================================

ALTER TABLE feedback_logs
    ADD COLUMN IF NOT EXISTS page_id UUID REFERENCES knowledge_pages (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_feedback_logs_page_id ON feedback_logs (page_id);
