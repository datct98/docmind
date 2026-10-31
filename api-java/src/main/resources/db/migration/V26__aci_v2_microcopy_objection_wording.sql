-- ============================================================================
-- V26: Tinh chỉnh diễn đạt PB-SPA-PRICE-OBJECTION-001 & PB-SPA-SAFETY-001
--      tránh LLM diễn đạt nhầm "không nhận Tip" thành "giá đã gồm tiền tip",
--      đồng thời cấm cả câu giả định "nếu do căng cứng cổ vai gáy" khi khách
--      nêu biểu hiện hoa mắt chóng mặt.
-- ============================================================================

UPDATE learned_rules
SET do_actions = 'Đồng cảm gọn trong 15–30 từ (1 bong bóng), bảo vệ 1 giá trị thực tế cốt lõi của gói (liệu trình 60 phút chăm sóc bài bản từng vùng mỏi, giá trọn gói không phát sinh phụ phí và văn hóa không nhận Tip) rồi hỏi nhẹ cảm nhận của khách.',
    dont_actions = 'Không đọc lại danh sách dài như brochure; không diễn đạt nhầm thành giá đã bao gồm tiền tip; không tự giảm giá hay bịa khuyến mãi.'
WHERE rule_code = 'PB-SPA-PRICE-OBJECTION-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

UPDATE learned_rules
SET do_actions = 'Phản hồi gọn đủ 3 ý: (1) hỏi nhẹ 1 câu làm rõ (như hay bị lúc ngồi lâu hay khi đứng dậy), (2) giới thiệu & báo giá gói thư giãn cơ thể êm dịu (như Dưỡng sinh cơ bản 60 phút – 239k) đúng phạm vi thư giãn, (3) báo rõ khi ghé cơ sở kỹ thuật viên sẽ hỏi kỹ thể trạng trực tiếp để chăm sóc êm nhẹ phù hợp nhất (còn chóng mặt kéo dài nên kiểm tra nguyên nhân y tế riêng). Khi có dấu hiệu cấp tính nguy hiểm thì ưu tiên khuyên trợ giúp y tế khẩn cấp.',
    dont_actions = 'Không tự chẩn đoán hay giả định nguyên nhân (kể cả câu giả định nếu do căng cứng cổ vai gáy, tuần hoàn máu não hay huyết áp); không quảng cáo massage chữa/kiểm tra chóng mặt; không tự hứa Quản lý gọi điện hoặc tạo handoff khi chưa có SĐT và hành động thực tế.'
WHERE rule_code = 'PB-SPA-SAFETY-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';
