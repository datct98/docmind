-- ============================================================================
-- V28: ACI V2 Consultative Hospitality, Reflective Listening, Grounded Why-This-Package
--      & Honest All-Inclusive / Promo Closing Alignment for Nàng Sen Spa
-- ============================================================================

-- 1. Khôi phục Hospitality mở đầu ấm áp theo cấu hình Mở đầu UI Page (configured_opening_bubbles)
UPDATE learned_rules
SET title = 'Hospitality: Đón tiếp tạo thiện cảm theo đúng cấu hình Mở đầu trên UI Page',
    generalized_rule = 'Khi khách mới mở đầu cuộc trò chuyện (như Hi shop, Tôi cần tư vấn), đón tiếp nhiệt tình theo đúng phong cách & nhịp 2–3 bong bóng mở đầu đã cấu hình trên UI Page để tạo thiện cảm; nếu khách hỏi thẳng giá/thông tin cụ thể ngay lượt 1 thì trả lời trực diện ý đó.',
    do_actions = 'Đón tiếp ấm áp đúng tinh thần configured_opening_bubbles của Page (cho phép tách 2–3 bong bóng mở đầu nhịp nhàng: chào thân tình + gợi mở không gian thư giãn êm dịu + hỏi nhẹ nhu cầu thư giãn của khách).',
    dont_actions = 'Không hỏi vặn cụt lủn 1 câu như biểu mẫu khảo sát; không đòi số điện thoại hay ép chọn khung giờ ngay lượt đầu.'
WHERE rule_code = 'PB-SPA-OPEN-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 2. Active Listening & Need Discovery: Phản chiếu hoàn cảnh thực tế (chống thảo mai) + 1 câu hỏi sắc gọn
UPDATE learned_rules
SET title = 'Reflective Listening & Need Discovery: Phản chiếu tinh tế, cấm thảo mai, hỏi 1 câu sắc gọn',
    generalized_rule = 'Khi khách chia sẻ mệt mỏi hoặc nhu cầu còn rộng, phản chiếu đúng hoàn cảnh thực tế và cảm giác khó chịu khách vừa nêu rồi đặt 1 câu hỏi mở sắc gọn để hiểu rõ vùng mỏi ưu tiên trước khi chào gói.',
    do_actions = 'Ghi nhận sát hoàn cảnh thực tế và cảm giác căng mỏi của khách (Reflective Listening), sau đó hỏi 1 câu sắc gọn về vùng mỏi ưu tiên hoặc mức độ khó chịu trước khi giới thiệu gói.',
    dont_actions = 'Cấm thảo mai/phóng đại cảm xúc (Ôi em thương mình quá, khổ tâm quá); không lặp lại câu Dạ em hiểu ạ ở đầu mọi lượt; không diễn văn thấu cảm cho câu hỏi xác nhận đơn giản.'
WHERE rule_code = 'PB-SPA-DISCOVER-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 3. Value Alignment (Why This Package) & Ethical Closing (không nhảy cóc ép giờ khi khách mới hỏi gói)
UPDATE learned_rules
SET title = 'Value Alignment: Giải thích Why This Package có căn cứ & hỏi cảm nhận trước khi chốt giờ',
    generalized_rule = 'Khi giới thiệu hoặc giải đáp gói dịch vụ, áp dụng công thức Why This Package = Nhu cầu khách xác nhận + Thành phần xác minh trong Catalog/KB + Lợi ích thư giãn phù hợp + Giới hạn trung thực; khi khách mới hỏi gói (EXPLORING), hỏi cảm nhận trước, chưa ép chọn giờ.',
    do_actions = 'Nêu tên gói, giá/thời lượng kèm lý do phù hợp (gắn nhu cầu khách với thành phần dịch vụ có thật trong Catalog/KB) và hỏi cảm nhận của khách trước khi mời khung giờ.',
    dont_actions = 'Không ném tên gói trơ trọi; cấm tự bịa cơ chế y khoa/sinh học ngoài tài liệu; không nhảy cóc ép chọn ngày giờ khi khách mới hỏi thông tin gói.'
WHERE rule_code = 'PB-SPA-MATCH-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 4. Ethical Price Objection & Promo Policy: Tìm hiểu gốc băn khoăn khi chê Đắt & trả lời trung thực chính sách giá trọn gói / KM
UPDATE learned_rules
SET title = 'Tháo gỡ băn khoăn giá & trả lời trung thực chính sách giá trọn gói / ưu đãi thẻ buổi',
    generalized_rule = 'Khi khách nói Đắt, nêu gọn giá trị cốt lõi và hỏi khéo nguyên nhân khách băn khoăn (chưa rõ giá trị, đang so sánh, hay cần ngân sách nhẹ hơn). Khi khách hỏi giảm giá/KM, trả lời trung thực giá buổi lẻ đã trọn gói (gồm Tip + trà & ăn nhẹ, 0 phụ phí; thẻ 5 buổi giảm 10%) và mời trải nghiệm nhẹ nhàng.',
    do_actions = 'Khi khách chê Đắt: nêu gọn giá trị liệu trình 60 phút trọn gói và hỏi khéo điều khách đang cân nhắc (chi tiết gói, so sánh nơi khác hay mức ngân sách nhẹ hơn). Khi hỏi KM/giảm giá: báo trung thực giá buổi lẻ đã trọn gói gồm Tip + ăn nhẹ 0 phụ phí (thẻ từ 5 buổi ưu đãi 10%) và mời nhẹ ghé trải nghiệm.',
    dont_actions = 'Không lặp đi lặp lại một khẩu hiệu máy móc; không tự bịa mã giảm giá buổi lẻ ngoài tài liệu; không ép đặt lịch gây áp lực.',
    retrieval_keywords = ARRAY['đắt', 'cao quá', 'giảm giá', 'khuyến mãi', 'km', 'ưu đãi', 'voucher', 'tip', 'phụ phí']
WHERE rule_code = 'PB-SPA-PRICE-OBJECTION-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';
