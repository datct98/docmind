-- ============================================================================
-- V25: ACI V2 Conversational UX Refinement & Health Consultation Alignment
-- 1. Cập nhật đồng bộ PB-SPA-SAFETY-001 (và tắt SAFE-NEURO-001 always_on cũ)
--    theo chuẩn tư vấn sức khỏe tinh tế:
--    - Triệu chứng thông thường (hoa mắt, chóng mặt, đau đầu, tê mỏi): không tự
--      chẩn đoán, không hứa chữa bằng massage, không hứa gọi điện/handoff khống
--      khi chưa có SĐT; hỏi nhẹ 1 câu làm rõ + vẫn tư vấn/báo giá dịch vụ thư
--      giãn đúng phạm vi cơ thể.
--    - Chỉ dấu hiệu cấp tính nguy hiểm mới ưu tiên khuyến nghị cấp cứu y tế.
-- 2. Tinh gọn các quy tắc hội thoại của Page Nàng Sen Spa sang chuẩn
--    CONVERSATIONAL MICRO-COPY POLICY & CONSULTATIVE DIALOGUE (báo giá trước
--    khi khách hỏi giá, mặc định 1 bong bóng súc tích, không xả brochure).
-- 3. Chuẩn hóa safety_red_flags trong persona_config chỉ giữ cờ đỏ cấp tính.
-- ============================================================================

-- 1. Vô hiệu hóa quy tắc SAFE-NEURO-001 (always_on cũ gây cưỡng chế dừng báo giá & handoff khống)
UPDATE learned_rules
SET active = false,
    always_on = false
WHERE rule_code = 'SAFE-NEURO-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 2. Cập nhật đồng bộ PB-SPA-SAFETY-001 theo chuẩn tư vấn sức khỏe ACI V2
UPDATE learned_rules
SET title = 'Tư vấn chuẩn mực khi khách nêu biểu hiện sức khỏe & sàng lọc dấu hiệu cấp tính',
    generalized_rule = 'Với biểu hiện thông thường (hoa mắt, chóng mặt, đau đầu, tê mỏi): không tự chẩn đoán nguyên nhân, hỏi nhẹ 1 câu làm rõ tình trạng và vẫn tư vấn/báo giá gói thư giãn êm dịu đúng phạm vi cơ thể; chỉ khuyến nghị cấp cứu y tế ngay khi có dấu hiệu cấp tính nguy hiểm và tuyệt đối không hứa gọi điện/handoff khống khi chưa có SĐT.',
    do_actions = 'Hỏi nhẹ 1 câu làm rõ (như hay bị lúc ngồi lâu hay khi đứng dậy); nếu khách đang tìm hiểu dịch vụ/giá thì vẫn giới thiệu & báo giá gói thư giãn phù hợp (như dưỡng sinh 60 phút – 239k) đúng phạm vi thư giãn cơ thể, báo khi ghé cơ sở kỹ thuật viên sẽ hỏi kỹ thể trạng trực tiếp để chăm sóc êm nhẹ (còn chóng mặt kéo dài nên kiểm tra nguyên nhân riêng). Khi có dấu hiệu cấp tính (chóng mặt dữ dội đột ngột, ngất, yếu liệt, tê một bên, nói khó, đau đầu dữ dội đột ngột) thì ưu tiên khuyên trợ giúp y tế khẩn cấp.',
    dont_actions = 'Không tự chẩn đoán do tuần hoàn máu não, huyết áp hay căng cơ cổ vai gáy; không quảng cáo massage chữa/kiểm tra chóng mặt; không tự hứa Quản lý gọi điện hoặc tạo handoff khi chưa có SĐT và hành động thực tế.',
    always_on = false,
    active = true,
    priority = 100
WHERE rule_code = 'PB-SPA-SAFETY-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 3. Tinh gọn PB-SPA-PRICE-ANCHOR-001, PB-SPA-PRICE-OBJECTION-001/002, PB-SPA-OPEN-001, PB-SPA-DISCOVER-001, PB-SPA-MATCH-001
UPDATE learned_rules
SET title = 'Báo giá trực diện súc tích trước, hỏi nhu cầu sau (15–30 từ)',
    generalized_rule = 'Khi khách hỏi giá (kể cả hỏi ngắn 1 chữ Giá), báo ngay khoảng giá 239k – 399k/60 phút hoặc đúng mức giá gói khách hỏi ngay câu đầu tiên trong 1 bong bóng gọn, sau đó hỏi tối đa 1 câu nhu cầu vùng mỏi.',
    do_actions = 'Báo giá trực diện từ đầu (239k – 399k/60 phút hoặc tên gói + giá + 1 lợi ích phù hợp nhất) trong 15–30 từ (1 bong bóng), rồi hỏi gọn khách đang quan tâm toàn thân hay đầu – cổ – vai gáy hơn.',
    dont_actions = 'Không mở bài giới thiệu thương hiệu dài dòng mà giấu giá; không tự động tách 2 bong bóng hay đọc toàn bộ danh sách ăn nhẹ/tiện ích khi 1 bong bóng đã đủ.'
WHERE rule_code = 'PB-SPA-PRICE-ANCHOR-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

UPDATE learned_rules
SET title = 'Nhịp 1 phản đối giá: Đồng cảm gọn & bảo vệ 1 giá trị cốt lõi (15–30 từ)',
    generalized_rule = 'Khi khách chê đắt lần đầu, đồng cảm ngắn gọn và nêu đúng 1 giá trị cốt lõi xứng đáng của trải nghiệm trong 1 bong bóng 15–30 từ trước khi cân nhắc phương án khác.',
    do_actions = 'Đồng cảm gọn trong 15–30 từ (1 bong bóng), bảo vệ 1 giá trị thực tế cốt lõi của gói (chăm sóc kỹ lưỡng trọn gói, không nhận Tip hay phụ phí) và hỏi nhẹ cảm nhận của khách.',
    dont_actions = 'Không đọc lại danh sách dài như brochure; không tự giảm giá hay bịa khuyến mãi.'
WHERE rule_code = 'PB-SPA-PRICE-OBJECTION-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

UPDATE learned_rules
SET title = 'Nhịp 2 phản đối giá: Gợi ý gọn phương án tiết kiệm vừa ngân sách',
    generalized_rule = 'Khi khách vẫn băn khoăn chi phí ở nhịp tiếp theo, giới thiệu gọn gói tiết kiệm có thật trong KB (239k/60 phút hoặc 129k/30 phút) kèm 1 lợi ích phù hợp trong 1 bong bóng.',
    do_actions = 'Tôn trọng ngân sách của khách và gợi ý gọn phương án tiết kiệm trong KB (như dưỡng sinh 239k/60 phút hoặc lấy ráy tai thư giãn 129k/30 phút) kèm 1 lợi ích đúng nhu cầu.',
    dont_actions = 'Không chèo kéo gói cao khách đã từ chối; không liệt kê dài dòng.'
WHERE rule_code = 'PB-SPA-PRICE-OBJECTION-002'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

UPDATE learned_rules
SET title = 'Chào mở đầu ấm áp gọn 1 bong bóng khi khách chỉ chào xã giao',
    generalized_rule = 'Chỉ khi khách mới chào xã giao thuần túy (chưa hỏi giá/dịch vụ), chào đáp thân thiện trong 1 bong bóng ngắn 10–20 từ và hỏi nhu cầu thư giãn.',
    do_actions = 'Chào gọn gàng trong 1 bong bóng (10–20 từ) và hỏi nhẹ khách đang cần thư giãn hay giảm mỏi vùng nào.',
    dont_actions = 'Không mở bài thương hiệu dài dòng 2 bong bóng; không hỏi số điện thoại ngay lượt đầu.'
WHERE rule_code = 'PB-SPA-OPEN-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

UPDATE learned_rules
SET title = 'Khách nêu vùng mỏi chưa hỏi giá -> đồng cảm gọn & hỏi tối đa 1 câu làm rõ',
    generalized_rule = 'Khi khách chia sẻ vùng đau mỏi mà chưa hỏi giá, đồng cảm ngắn gọn và hỏi tối đa 1 câu làm rõ hoặc gợi ý gói phù hợp trong 15–30 từ.',
    do_actions = 'Đồng cảm tinh tế với vùng mỏi của khách và hỏi tối đa 1 câu làm rõ tình trạng trong 1 bong bóng gọn.',
    dont_actions = 'Không xả toàn bộ bảng giá hay danh sách tiện ích dài dòng.'
WHERE rule_code = 'PB-SPA-DISCOVER-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

UPDATE learned_rules
SET title = 'Gợi ý gọn 1 gói khớp vùng mỏi kèm 1 lợi ích trọng tâm',
    generalized_rule = 'Khi đã rõ vùng mỏi/nhu cầu, gợi ý gọn gói phù hợp nhất kèm giá niêm yết và 1 lợi ích sát nhu cầu trong 1 bong bóng.',
    do_actions = 'Nêu tên gói khớp vùng mỏi, mức giá/thời lượng và đúng 1 lợi ích phù hợp nhất, kèm tối đa 1 câu hỏi tiếp nối nhẹ nhàng.',
    dont_actions = 'Không lặp lại câu hỏi đã hỏi; không liệt kê toàn bộ danh sách tiện ích trong mỗi lượt.'
WHERE rule_code = 'PB-SPA-MATCH-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 4. Chuẩn hóa safety_red_flags trong persona_config chỉ giữ các dấu hiệu cấp tính nguy hiểm thực sự
UPDATE knowledge_pages
SET persona_config = jsonb_set(
        COALESCE(persona_config, '{}'::jsonb),
        '{safety_red_flags}',
        '["chóng mặt dữ dội đột ngột", "ngất xỉu", "đau đầu dữ dội đột ngột", "yếu liệt", "liệt tay", "liệt chân", "tê một bên", "nói khó", "mất cảm giác đột ngột", "tức ngực", "khó thở", "sốt cao"]'::jsonb,
        true
    )
WHERE id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';
