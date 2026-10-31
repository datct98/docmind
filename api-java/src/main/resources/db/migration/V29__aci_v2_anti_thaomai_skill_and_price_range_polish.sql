-- ============================================================================
-- V29: ACI V2 Anti-Thảo-Mai Reflective Skill Wording & Accurate Full Price Range
-- ============================================================================

-- 1. Loại bỏ từ ngữ "vỗ về" dễ kích hoạt câu cảm thán thảo mai ("em thấy thương lắm") trong skill discovery_empathy_and_scope
UPDATE agent_skills
SET tactical_instructions = '[Tình huống tâm lý khách] Khách vừa chia sẻ cơ thể căng mỏi do ngồi máy tính lâu, làm việc căng thẳng hoặc trằn trọc khó ngủ -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Dùng Reflective Listening: phản chiếu sát hoàn cảnh thực tế và cảm giác khó chịu âm ỉ khách vừa nêu (tuyệt đối không dùng câu cảm thán thảo mai như "em thấy thương lắm", "khổ tâm quá", "Dạ em hiểu ạ"), sau đó hỏi đúng 1 câu mở sắc gọn về vùng mỏi ưu tiên hoặc mức độ mỏi trước khi chào gói -> [Các hướng diễn đạt gợi cảm xúc theo ngữ cảnh] "Ngồi máy tính liên tục mà vùng cổ vai gáy cứ căng mỏi âm ỉ thì lúc cần tập trung làm việc khá bứt rứt đó ạ. Hiện mình đang mỏi tập trung ở cổ vai gáy hay lan xuống cả vùng lưng ạ?"',
    anti_pattern = 'Dùng câu cảm thán thảo mai bề mặt ("em thấy thương lắm", "khổ tâm quá", "Dạ em hiểu ạ") hoặc xổ ngay bảng giá khi chưa hỏi rõ vùng mỏi ưu tiên.'
WHERE code = 'discovery_empathy_and_scope'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 2. Tinh chỉnh skill price_objection_sensory_value tránh mở đầu rập khuôn "Dạ em hiểu ạ"
UPDATE agent_skills
SET tactical_instructions = '[Tình huống tâm lý khách] Khách vừa nghe mức giá liệu trình và thoáng băn khoăn ("Đắt", "Cao quá") -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Ghi nhận tự nhiên sự cân nhắc của khách cho mức giá/thời lượng đó (không mở đầu rập khuôn bằng "Dạ em hiểu ạ"), điểm nhẹ 1 giá trị cốt lõi (kỹ thuật viên chăm chút ấn mở điểm cơ căng mỏi, trọn gói không phụ phí) và hỏi khéo nguyên nhân khách đắn đo (chưa rõ gói gồm gì, đang so sánh, hay muốn tìm mức ngân sách nhẹ hơn)',
    anti_pattern = 'Mở đầu rập khuôn "Dạ em hiểu ạ", đọc lại nguyên văn danh sách tiện ích vừa nói ở lượt trước, hoặc hạ gói ngay khi chưa rõ điều khách băn khoăn.'
WHERE code = 'price_objection_sensory_value'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 3. Chuẩn hóa PB-SPA-PRICE-ANCHOR-001 báo đúng khoảng giá niêm yết trọn gói không dấu "+"
UPDATE learned_rules
SET generalized_rule = 'Khi khách hỏi Giá chung ngắn gọn, báo ngay khoảng giá trọn gói chuẩn xác trong Catalog (từ 239k đến 1.990k cho các gói dưỡng sinh & massage 60–180 phút, hoặc 129k lấy ráy tai thư giãn 30 phút; tuyệt đối không dùng dấu "+") trong 1 bong bóng gọn rồi hỏi 1 câu về vùng mỏi.',
    do_actions = 'Báo khoảng giá trọn gói trực diện ngay câu đầu (239k – 1.990k tùy thời lượng & liệu trình, không viết dấu "+") trong 15–30 từ (1 bong bóng), kèm 1 câu hỏi gọn vùng mỏi ưu tiên.',
    dont_actions = 'Không viết ký hiệu giá thiếu chuẩn như 399k+; không mở bài giới thiệu thương hiệu dài dòng trước khi báo giá.'
WHERE rule_code = 'PB-SPA-PRICE-ANCHOR-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';
