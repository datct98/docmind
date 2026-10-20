-- ============================================================
-- DocMind api-java — Flyway V10: Psychological Persuasion Skills for Cooling/Silent Leads
--
-- Bổ sung 3 kỹ năng thuyết phục tâm lý & xoay trục chiến thuật:
-- 1. PSYCH_ZERO_PRESSURE_TAKEAWAY: Lùi một bước - Buông bỏ áp lực bán (Takeaway Reverse Psychology)
-- 2. PSYCH_PATTERN_INTERRUPT_CURIOSITY: Phá vỡ khuôn mẫu & Gợi mở tò mò (Pattern Interrupt & Curiosity Hook)
-- 3. SALES_LOSS_AVERSION_RESERVATION: Giữ suất ưu đãi 0đ ràng buộc (Loss Aversion Privilege Reservation)
-- ============================================================

INSERT INTO agent_skills (
    id, code, name, category, description, trigger_signals,
    tactical_instructions, good_example, anti_pattern, priority, active, page_id, confidence_score
) VALUES
(
    '70000000-0000-0000-0000-000000000013',
    'PSYCH_ZERO_PRESSURE_TAKEAWAY',
    '[Tâm lý] Lùi Một Bước - Buông Bỏ Áp Lực Bán (Zero-Pressure Takeaway)',
    'PSYCHOLOGY',
    'Xóa bỏ áp lực bán hàng ngay lập tức khi khách hàng có dấu hiệu do dự, từ chối nhẹ ("ừ", "để xem", "để chị nghĩ thêm", "thôi"), giúp hạ rào cản phòng vệ tâm lý trước khi gợi mở lý do thật sự.',
    'ừ, uk, ok, vâng, để xem, để chị xem, để xem thêm, để nghĩ đã, suy nghĩ thêm, để chị nghĩ, thôi, hơi đắt, đắt quá, chưa cần',
    '1. Hạ hoàn toàn áp lực: Đồng ý ngay với quyền tự do suy nghĩ của khách, tuyệt đối không giục giã hay nhắc lại bảng giá/chốt lịch. 2. Buông bỏ giả định mua hàng (Takeaway): Khẳng định việc tìm hiểu kỹ là hoàn toàn đúng đắn. 3. Đặt 1 câu hỏi tháo gỡ nhẹ nhàng (Uncover hidden blocker) để khách tự nhiên chia sẻ mà không cảm thấy bị làm phiền.',
    'Dạ không sao đâu Chị ạ, việc chăm sóc da mình cứ cân nhắc thật kỹ để hoàn toàn yên tâm trước nhé ạ. Cho em hỏi nhỏ để hỗ trợ Chị tốt hơn, hiện tại điều làm Chị băn khoăn nhất là về chi phí hay chưa chắc chắn liệu trình có hợp với nền da của mình ạ?',
    'CẤM tiếp tục thúc ép đặt lịch; CẤM lặp lại bảng giá hay ép khách quyết định ngay khi khách vừa nói để xem.',
    95,
    TRUE,
    NULL,
    0.95
),
(
    '70000000-0000-0000-0000-000000000014',
    'PSYCH_PATTERN_INTERRUPT_CURIOSITY',
    '[Tâm lý] Phá Vỡ Khuôn Mẫu & Gợi Mở Tò Mò (Pattern Interrupt & Curiosity Hook)',
    'PSYCHOLOGY',
    'Cắt đứt phản xạ trả lời đối phó hoặc thờ ơ của khách bằng một góc nhìn chuyên môn bất ngờ hoặc một câu hỏi kích thích tò mò về tình trạng da/sản phẩm.',
    'ừ, ok, thế à, vậy à, để xem, im lặng, không biết, chưa rõ',
    '1. Phá vỡ kịch bản tư vấn thông thường: Không lặp lại câu hỏi bán hàng cũ. 2. Đưa ra một dữ kiện hoặc câu hỏi vi mô (micro-question) bất ngờ liên quan trực tiếp đến tình trạng/thói quen của khách. 3. Kích thích sự tò mò để khách muốn tìm hiểu thêm về nguyên nhân gốc rễ thay vì chỉ nhìn vào giá.',
    'Dạ Chị ơi, thông thường khi điều trị mụn ẩn lâu năm, 80% khách bên em đều bất ngờ khi biết nguyên nhân gốc không nằm ở sữa rửa mặt mà do bước làm dịu nền da bị thiếu ẩm. Hiện tại ở nhà Chị có đang dùng thêm sản phẩm bôi thoa nào không để em xem có đang bị xung đột hoạt chất không ạ?',
    'Không spam câu hỏi triết lý xa vời; không hỏi những câu đòi hỏi khách phải gõ văn bản dài phức tạp.',
    93,
    TRUE,
    NULL,
    0.93
),
(
    '70000000-0000-0000-0000-000000000015',
    'SALES_LOSS_AVERSION_RESERVATION',
    '[Sale] Giữ Suất Ưu Đãi 0đ Ràng Buộc (Loss Aversion Privilege Reservation)',
    'SALES_STRATEGY',
    'Kích hoạt tâm lý sợ mất quyền lợi (Loss Aversion) một cách tinh tế khi khách hàng nguội lạnh hoặc im lặng: Đề xuất giữ lại suất ưu đãi hoặc suất thăm khám miễn phí mà không ép mua bất kỳ gói nào.',
    'để xem, giữ suất, ưu đãi, hết hạn, khuyến mãi, giữ chỗ, im lặng, suy nghĩ thêm, giá cao',
    '1. Tận dụng tâm lý sợ bỏ lỡ nhưng không gây phản cảm: Báo cho khách biết suất ưu đãi/suất soi da AI 3D miễn phí cùng Bác sĩ sắp hết hoặc chỉ giữ được trong 24-48h. 2. Cam kết 0 đồng ràng buộc: Khẳng định giữ mã ưu đãi hoàn toàn miễn phí, nếu bận không qua được mã sẽ tự hủy mà không mất phí. 3. Đặt câu hỏi xác nhận giữ suất nhẹ nhàng.',
    'Dạ Chị ơi, chương trình tặng 100% suất soi da AI 3D cùng Bác sĩ tuần này bên em chỉ còn đúng 2 suất ưu tiên thôi ạ. Hay là em cứ tạm giữ mã ưu đãi này cho Chị trước đến hết ngày mai nhé, hoàn toàn 0đ và không bắt buộc làm liệu trình, nếu mai Chị bận thì mã tự hủy không sao hết ạ!',
    'CẤM đe dọa thô thiển kiểu "mua ngay kẻo tăng giá gấp đôi"; CẤM tạo cảm giác ép buộc cọc tiền trước.',
    94,
    TRUE,
    NULL,
    0.94
)
ON CONFLICT (id) DO UPDATE SET
    code = EXCLUDED.code,
    name = EXCLUDED.name,
    category = EXCLUDED.category,
    description = EXCLUDED.description,
    trigger_signals = EXCLUDED.trigger_signals,
    tactical_instructions = EXCLUDED.tactical_instructions,
    good_example = EXCLUDED.good_example,
    anti_pattern = EXCLUDED.anti_pattern,
    priority = EXCLUDED.priority,
    active = EXCLUDED.active,
    confidence_score = EXCLUDED.confidence_score,
    updated_at = now();
