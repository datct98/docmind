-- ============================================================================
-- V41 — ACI V3 P1: Taxonomy, Skill Example Neutralization & Rule Scoping
-- ============================================================================
-- Directive: ACI V3 — CHỈ THỊ TRIỂN KHAI CUỐI CÙNG, Stage P1 (§3.2, §3.4).
--
-- §3.2  Nàng Sen (2850a8ef-ca4a-40eb-87fa-4f808640efee): taxonomy rỗng khiến
--       resolvedScopeTaxonomy() từng fallback sang domain_keywords, đưa tên
--       dịch vụ "cổ vai gáy" thành "vùng cơ thể đã xác nhận". Migration này:
--         - điền scope_taxonomy / body_area_keywords / symptom_keywords;
--         - gỡ "cổ vai gáy" khỏi domain_keywords (nó là TÊN DỊCH VỤ, không
--           phải từ điển vùng cơ thể);
--         - GIỮ NGUYÊN safety_red_flags = [] (cấm tự động thêm red flags —
--           forbidden item #4 của chỉ thị).
--
-- §3.4  Skill 24000001-0000-4000-8000-000000000001 (discovery empathy) có
--       good_example + tactical_instructions hardcode giả định "cổ vai gáy,
--       lưng" -> bot tự ý gán vùng đau cho khách ("sao lại cổ vai gáy?").
--       Trung hòa thành câu hỏi mở, không giả định vùng cơ thể.
--
-- §3.4  4 learned rules misfire trên discovery/correction turn của Nàng Sen:
--         - 7084a3d7, 71bcd097: GLOBAL, gốc off-vertical (nha khoa/thẩm mỹ),
--           thúc ép khóa lịch / xin SĐT — vi phạm FEEL FIRST SELL SECOND.
--         - e301a8e1, 879f3d6c: page Nàng Sen, early-closing "chốt đơn" và
--           template cứng xin SĐT.
--       KHÔNG xóa (chỉ thị cấm xóa rules) — deactivate + quarantined_reason
--       để audit; tầng Java (CleanAgentCatalogAndPromptBuilder) đã có gate
--       theo correction-turn cho các rule tương tự còn active.
--
-- Lưu ý tương thích: V40 từng UPDATE page_id 'b23cd382-4f7a-4829-8c54-...'
-- (no-op trên dev vì skills thực tế gắn với 2850a8ef). V41 target đúng
-- 2850a8ef. Không đụng V1..V40.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- §3.2 — Nàng Sen persona taxonomy
-- ----------------------------------------------------------------------------
UPDATE knowledge_pages
SET persona_config = jsonb_set(
        jsonb_set(
            jsonb_set(
                jsonb_set(
                    persona_config,
                    '{scope_taxonomy}',
                    '["toàn thân","đầu","cổ","vai","gáy","lưng","tay","chân"]'::jsonb,
                    true),
                '{body_area_keywords}',
                '["cổ vai gáy","vai gáy","bắp chân","đùi","bàn chân","đầu gối","cổ tay","bả vai"]'::jsonb,
                true),
            '{symptom_keywords}',
            '["đau","mỏi","nhức","căng","căng cứng","tê","đau đầu","khó ngủ","mất ngủ","mệt mỏi","stress","căng thẳng"]'::jsonb,
            true),
        '{domain_keywords}',
        '["gội đầu","dưỡng sinh","massage","trị mụn","chăm sóc da","liệu trình","thư giãn"]'::jsonb,
        true)
WHERE id = '2850a8ef-ca4a-40eb-87fa-4f808640efee';
-- safety_red_flags: KHÔNG chạm — giữ [].

-- ----------------------------------------------------------------------------
-- §3.4 — Skill discovery: gỡ giả định cứng "cổ vai gáy, lưng"
-- ----------------------------------------------------------------------------
UPDATE agent_skills
SET good_example = 'Dạ đi tập nặng về mà cơ khớp còn đang căng mỏi chắc người mình oải lắm ạ. Để em chọn đúng liệu trình thả lỏng vừa vặn nhất, hiện tại mình đang nhức mỏi nhiều nhất ở vùng nào, hay muốn chăm sóc giãn cơ toàn thân luôn ạ?',
    tactical_instructions = '[Tình huống tâm lý khách] Khách vừa chia sẻ cơ thể căng mỏi sau buổi tập nặng, ngồi làm việc lâu, uể oải hoặc trằn trọc khó ngủ nhưng chưa nêu rõ vùng ưu tiên (hoặc vừa tiếp nhận thông tin chung) -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Vỗ về cảm giác mỏi mệt bằng ngôn từ dịu dàng, đánh thức cảm giác thư thái; khéo léo hỏi nhẹ khách đang mỏi nhất ở vùng nào để chọn đúng liệu trình đo ni đóng giày, TUYỆT ĐỐI KHÔNG tự giả định hay gán sẵn vùng cơ thể cho khách khi khách chưa nêu (nếu khách đã nêu rõ toàn thân hoặc hỏi giá thì giới thiệu ngay cuốn hút); tuyệt đối không chèn lời khuyên đi khám bệnh viện lạnh lùng khi không có dấu hiệu cấp tính -> [Các hướng diễn đạt gợi cảm xúc theo ngữ cảnh] "Tập nặng về mà các thớ cơ còn đang căng cứng chắc người mình oải lắm ạ", "Hơi ấm thảo dược dịu nhẹ kết hợp thả lỏng từng điểm cơ căng sẽ giúp người nhẹ nhõm hơn hẳn; hiện mình đang mỏi nhất ở vùng nào, hay muốn thả lỏng toàn thân ạ?"',
    updated_at = now()
WHERE id = '24000001-0000-4000-8000-000000000001';

-- ----------------------------------------------------------------------------
-- §3.4 — Rule scoping: deactivate 4 rules (không xóa), kèm justification
-- ----------------------------------------------------------------------------
UPDATE learned_rules
SET active = false,
    quarantined_reason = 'ACI V3 P1 §3.4: GLOBAL off-vertical rule (gốc phiên nha khoa/thẩm mỹ) thúc ép khóa cứng lịch trên mọi page — misfire trên discovery/correction turn của Nàng Sen, vi phạm FEEL FIRST SELL SECOND. Deactivate có kiểm soát; giữ nguyên nội dung để audit.',
    updated_at = now()
WHERE id = '7084a3d7-e66a-4b19-a81d-ddffb1365d7b';

UPDATE learned_rules
SET active = false,
    quarantined_reason = 'ACI V3 P1 §3.4: GLOBAL off-vertical rule chỉ xin SĐT để khóa lịch — misfire trên discovery/correction turn của Nàng Sen, vi phạm FEEL FIRST SELL SECOND. Deactivate có kiểm soát; giữ nguyên nội dung để audit.',
    updated_at = now()
WHERE id = '71bcd097-d758-4bfb-9c21-171af2a058d6';

UPDATE learned_rules
SET active = false,
    quarantined_reason = 'ACI V3 P1 §3.4: early-closing rule "điều hướng khách đặt lịch, chốt đơn" — misfire khi khách đang correction/pushback phạm vi (vd "sao lại cổ vai gáy?"). Deactivate có kiểm soát; giữ nguyên nội dung để audit.',
    updated_at = now()
WHERE id = 'e301a8e1-f87a-429d-960a-0b5a783aedae';

UPDATE learned_rules
SET active = false,
    quarantined_reason = 'ACI V3 P1 §3.4: rigid-template rule ép mẫu câu cứng khi nhận SĐT — misfire trên correction turn, mẫu câu cứng vi phạm nguyên tắc diễn đạt theo ngữ cảnh. Deactivate có kiểm soát; giữ nguyên nội dung để audit.',
    updated_at = now()
WHERE id = '879f3d6c-ef96-46d2-b14e-509e0bb284f1';
