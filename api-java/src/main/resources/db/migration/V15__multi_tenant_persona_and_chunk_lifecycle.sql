-- ============================================================
-- DocMind api-java — Flyway V15: Multi-Tenant Persona Skin & Document/Chunk Lifecycle
--
-- Phase 1 & Phase 2:
--   1. Add persona_config JSONB to knowledge_pages ("Configurable Skin" per Tenant/Page).
--   2. Update conversation_sessions.active_topic default to 'Tư vấn chung' (industry-neutral).
--   3. Add effective_from, effective_to, is_active to documents for time-bound promo/price filtering.
--   4. Add semantic_tags TEXT[] to chunks + GIN index for intent-aware tag boosting.
-- ============================================================

-- 1. Configurable Persona Skin on knowledge_pages
ALTER TABLE knowledge_pages
    ADD COLUMN IF NOT EXISTS persona_config JSONB NOT NULL DEFAULT '{}'::jsonb;

-- Backfill persona_config for existing pages based on their industry/name
UPDATE knowledge_pages
SET persona_config = CASE
    WHEN (industry ILIKE '%spa%' OR industry ILIKE '%thẩm mỹ%' OR industry ILIKE '%dưỡng sinh%' OR name ILIKE '%sen%') THEN
        jsonb_build_object(
            'industry_code', 'BEAUTY_SPA',
            'bot_name', 'Chuyên viên Tư vấn ' || name,
            'self_pronoun', 'em',
            'default_customer_pronoun', 'Chị',
            'tone_style', 'Dịu dàng, tận tâm, thấu cảm sâu sắc và tạo cảm giác an tâm tuyệt đối',
            'emoji_level', 'LOW',
            'forbidden_words', jsonb_build_array('cam kết khỏi 100%', 'chữa bách bệnh', 'rẻ nhất thị trường'),
            'domain_keywords', jsonb_build_array('gội đầu', 'dưỡng sinh', 'massage', 'cổ vai gáy', 'trị mụn', 'chăm sóc da', 'liệu trình', 'thư giãn')
        )
    ELSE
        jsonb_build_object(
            'industry_code', 'GENERAL_SERVICE',
            'bot_name', 'Trợ lý Tư vấn ' || name,
            'self_pronoun', 'em',
            'default_customer_pronoun', 'Anh/Chị',
            'tone_style', 'Thân thiện, chuyên nghiệp, rõ ràng và nhiệt tình hỗ trợ',
            'emoji_level', 'LOW',
            'forbidden_words', jsonb_build_array(),
            'domain_keywords', jsonb_build_array()
        )
END
WHERE persona_config IS NULL OR persona_config = '{}'::jsonb;

-- 2. Industry-neutral default for conversation_sessions.active_topic
ALTER TABLE conversation_sessions
    ALTER COLUMN active_topic SET DEFAULT 'Tư vấn chung';

-- 3. Time-bound lifecycle columns on documents
ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS effective_from TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS effective_to TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX IF NOT EXISTS idx_documents_lifecycle
    ON documents (is_active, effective_from, effective_to);

-- 4. Semantic tags on chunks for intent-aware RAG boosting
ALTER TABLE chunks
    ADD COLUMN IF NOT EXISTS semantic_tags TEXT[] NOT NULL DEFAULT '{}';

CREATE INDEX IF NOT EXISTS idx_chunks_semantic_tags_gin
    ON chunks USING GIN (semantic_tags);

-- Backfill semantic_tags for existing chunks
UPDATE chunks
SET semantic_tags = ARRAY(
    SELECT tag FROM (
        SELECT 'warranty_return' AS tag
        WHERE text ILIKE '%bảo hành%' OR text ILIKE '%cam kết%' OR text ILIKE '%hoàn tiền%' OR text ILIKE '%đổi trả%' OR text ILIKE '%0 phụ phí%' OR text ILIKE '%không phát sinh%'
        UNION ALL
        SELECT 'social_proof' AS tag
        WHERE text ILIKE '%khách hàng%' AND (text ILIKE '%đánh giá%' OR text ILIKE '%hài lòng%' OR text ILIKE '%tin chọn%' OR text ILIKE '%feedback%' OR text ILIKE '%chứng nhận%')
        UNION ALL
        SELECT 'spec_ingredients' AS tag
        WHERE text ILIKE '%thành phần%' OR text ILIKE '%thảo dược%' OR text ILIKE '%chất liệu%' OR text ILIKE '%kích thước%' OR text ILIKE '%thông số%' OR text ILIKE '%bao gồm%'
        UNION ALL
        SELECT 'scarcity_promo' AS tag
        WHERE text ILIKE '%ưu đãi%' OR text ILIKE '%khuyến mãi%' OR text ILIKE '%giảm %' OR text ILIKE '%giờ vàng%' OR text ILIKE '%voucher%'
    ) t
)
WHERE semantic_tags IS NULL OR cardinality(semantic_tags) = 0;
