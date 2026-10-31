-- ============================================================================
-- V27: Đồng bộ PB-SPA-PRICE-OBJECTION-001 khớp 100% với Chunk 6.2 của KB Nàng Sen
--      (giá niêm yết trọn gói, chăm sóc bài bản 60 phút, cam kết 0 phát sinh phụ phí)
-- ============================================================================

UPDATE learned_rules
SET do_actions = 'Đồng cảm gọn trong 15–30 từ (1 bong bóng), bảo vệ 1 giá trị cốt lõi của gói (liệu trình 60 phút chăm sóc bài bản từng vùng cơ mỏi, giá trọn gói cam kết không phát sinh phụ phí) rồi hỏi nhẹ cảm nhận của khách.',
    dont_actions = 'Không đọc lại danh sách dài như brochure; không tự giảm giá hay bịa khuyến mãi ngoài tài liệu.'
WHERE rule_code = 'PB-SPA-PRICE-OBJECTION-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';
