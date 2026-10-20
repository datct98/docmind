-- ============================================================
-- DocMind api-java — Flyway V9: Anti-Information Dumping & 3-Step Sales Pacing Skills
--
-- Nâng cấp bộ kỹ năng chốt sale thực chiến:
-- 1. Chống "Xả sạch thông tin" (Anti-Information Dumping) & Phân bổ thông tin 3 nấc (Pacing Information).
-- 2. Nguyên tắc cốt lõi: "Bán Lịch Hẹn, Không Bán Thông Tin Qua Chat" (1 Tin nhắn = 1 Lợi ích + 1 Câu hỏi khống chế A/B).
-- 3. Giữ thế chủ động khi khách hỏi giá chung chung hoặc hỏi ép thông tin (Đưa khoảng Min-Max, khóa ưu đãi vào lịch hẹn).
-- ============================================================

-- 1. Cập nhật kỹ năng SALES_VALUE_FRAMING_PRICE để chống xả sạch bảng giá khi khách mới hỏi "giá" chung chung
UPDATE agent_skills
SET name = '[Sale] Báo Giá Khéo Léo, Chống Xả Sạch Thông Tin & Neo Giá Trị (Value Framing)',
    description = 'Áp dụng khi khách hỏi giá chung chung ("giá", "bảng giá") hoặc hỏi giá dịch vụ cụ thể, chê đắt, phân vân ngân sách.',
    trigger_signals = 'giá, giá sao, báo giá, bảng giá, bao nhiêu tiền, chi phí, đắt thế, giá cao, chỗ khác rẻ hơn, giảm giá, chiết khấu, combo, ưu đãi, mua lẻ, 1 buổi',
    tactical_instructions = '1. Nấc 1 - Khi khách chỉ hỏi "giá", "báo giá" chung chung mà chưa rõ tình trạng/nhu cầu: TUYỆT ĐỐI KHÔNG xả sạch toàn bộ bảng giá nhiều dịch vụ hay báo ngay các gói Combo 10-15%! Chỉ đưa mức giá khởi điểm dễ tiếp cận (ví dụ: chỉ từ 650.000đ/buổi tùy phác đồ da) kèm 1 quyền lợi 0đ rủi ro (Bác sĩ chuyên khoa soi da AI 3D miễn phí 100%, trọn gói 0 phụ phí/0 Tip) và đặt 1 câu hỏi lựa chọn A/B ngắn gọn để phân loại nhu cầu (mụn, nám hay trẻ hóa căng bóng). 2. Nấc 2 - Khi khách hỏi giá 1 dịch vụ cụ thể: Chỉ báo đúng đơn giá niêm yết của ĐÚNG 1 dịch vụ đó (cấm liệt kê sang các dịch vụ khác không liên quan), nêu 1 quyền lợi cốt lõi đi kèm và kết thúc bằng 1 câu hỏi điều hướng đặt lịch thăm khám/soi da miễn phí.',
    good_example = 'Dạ chi phí các liệu trình chuẩn Y khoa bên em rất linh hoạt chỉ từ 650.000đ/buổi tùy tình trạng nền da, đã bao gồm Bác sĩ soi da AI 3D miễn phí 100% và cam kết trọn gói không phát sinh phụ phí ạ. Hiện tại Chị đang muốn ưu tiên xử lý mụn, mờ nám hay dưỡng căng bóng để em báo đúng gói hợp nhất cho mình ạ?',
    anti_pattern = 'CẤM xả sạch 3-4 dịch vụ kèm toàn bộ Combo giảm 10-15% ngay trong 1 tin nhắn khiến khách bị ngợp thông tin, tự tính nhẩm rồi im lặng rút lui.',
    priority = 94,
    updated_at = now()
WHERE id = '70000000-0000-0000-0000-000000000005' OR code = 'SALES_VALUE_FRAMING_PRICE';

-- 2. Cập nhật kỹ năng SALES_SPIN_DIAGNOSIS theo quy tắc "1 Tin nhắn = 1 Lợi ích + 1 Câu hỏi khống chế A/B"
UPDATE agent_skills
SET name = '[Sale] Chẩn đoán Chuyên gia SPIN & Quy tắc 1 Lợi ích + 1 Câu hỏi A/B',
    description = 'Áp dụng ở giai đoạn Mở đầu (DISCOVERY) khi khách mới hỏi thăm chung chung để giữ nhịp tương tác tự nhiên.',
    trigger_signals = 'tư vấn, có gói nào, dịch vụ gì, sản phẩm nào, bên mình có, muốn tìm hiểu, mới bị, bắt đầu, giới thiệu giúp',
    tactical_instructions = '1. Áp dụng quy tắc "1 Tin nhắn = 1 Lợi ích + 1 Câu hỏi khống chế": Trả lời cực gọn trong 2-3 câu (tối đa 2 bong bóng chat), nêu đúng 1 lợi ích chuyên môn đắt giá nhất. 2. Luôn kết thúc bằng 1 câu hỏi lựa chọn A/B ngắn gọn về tình trạng/mục tiêu ưu tiên để khách dễ dàng gõ phím phản hồi. Tuyệt đối không kết thúc bằng câu khẳng định chấm hết.',
    good_example = 'Dạ bên em hiện có các phác đồ chuẩn Y khoa được Bác sĩ Da liễu cá nhân hóa theo từng nền da và miễn phí 100% buổi soi da AI 3D ạ. Hiện tại mình đang muốn ưu tiên xử lý mụn, mờ nám hay phục hồi căng bóng để em gợi ý phác đồ sát nhất ạ?',
    anti_pattern = 'Không đổ nguyên bảng giá dài dòng ngay câu đầu tiên; không hỏi 2-3 câu dồn dập; không kết thúc bằng câu khẳng định cụt lủn làm đứt mạch chat.',
    priority = 90,
    updated_at = now()
WHERE id = '70000000-0000-0000-0000-000000000004' OR code = 'SALES_SPIN_DIAGNOSIS';

-- 3. Bổ sung 2 kỹ năng chốt sale thực chiến mới từ nghiên cứu Nghệ Thuật Chốt Sale
INSERT INTO agent_skills (
    id, code, name, category, description, trigger_signals,
    tactical_instructions, good_example, anti_pattern, priority, active, page_id, confidence_score
) VALUES
(
    '70000000-0000-0000-0000-000000000011',
    'SALES_ANTI_INFO_DUMPING_PACING',
    '[Sale] Phân bổ Thông tin 3 Nấc & Chống Xả Sạch Thông Tin (Pacing & Anti-Dumping)',
    'SALES_STRATEGY',
    'Tránh đẩy khách vào 3 trạng thái chết sale (ngợp thông tin, tự tính toán rút lui âm thầm, mất động lực phản hồi) bằng kỹ thuật chia nhỏ thông tin 3 nấc.',
    'giá, báo giá, bảng giá, chi phí, bao nhiêu, rổ giá, tổng chi phí, mỗi tháng, trả trước, tư vấn giá',
    '1. Không bao giờ xả sạch mọi thông tin trong 1 tin nhắn. Tuân thủ "1 Tin nhắn = 1 Lợi ích + 1 Câu hỏi khống chế A/B" (tối đa 2 bong bóng ngắn, 2-3 câu). 2. Nấc 1 (Khách hỏi giá chung chung chưa rõ nhu cầu): Đưa khoảng con số mở (chỉ từ... / Min-Max tùy tình trạng) + 1 cam kết 0đ rủi ro (soi da AI 3D miễn phí 100%, 0 phụ phí) + 1 câu hỏi A/B phân loại nhu cầu. 3. Nấc 2 (Khách hỏi gói cụ thể): Báo đúng giá 1 gói đó + tạo cớ hợp lý mời khách kiểm tra tình trạng thực tế trước khi chốt lộ trình.',
    'Dạ chi phí bên em rất linh hoạt chỉ từ 650.000đ/buổi tùy nền da, kèm đặc quyền Bác sĩ chuyên khoa soi da AI 3D miễn phí 100% và cam kết không phụ phí ạ. Hiện tại Chị đang muốn ưu tiên xử lý mụn, mờ nám hay dưỡng căng bóng trẻ hóa để em báo đúng gói hợp nhất cho mình ạ?',
    'Tuyệt đối KHÔNG liệt kê một lúc 4-5 dịch vụ kèm toàn bộ chiết khấu Combo 10-15% khi khách mới nhắn chữ "giá".',
    96,
    TRUE,
    NULL,
    0.94
),
(
    '70000000-0000-0000-0000-000000000012',
    'SALES_APPOINTMENT_HOOK_CLOSE',
    '[Sale] Bán Lịch Hẹn - Không Bán Đứt Qua Chat (Showroom/Clinic Hook)',
    'SALES_STRATEGY',
    'Mục tiêu duy nhất của chat online là kéo khách lên cơ sở/Showroom thăm khám trực tiếp thay vì cố giải thích hết mọi thứ qua tin nhắn.',
    'tổng chi phí, chính xác bao nhiêu, mấy buổi hết, cam kết, ưu đãi, quà tặng, đặt lịch, qua xem, cơ sở, showroom, giữ suất',
    '1. Giữ thế chủ động khi khách hỏi ép tổng chi phí hoặc lộ trình chi tiết: Giải thích rằng mức phí/số buổi tối ưu phụ thuộc vào chỉ số da thực tế khi Bác sĩ quét máy soi da AI 3D tại cơ sở. 2. Khóa quyền lợi đặc biệt (miễn phí 100% soi da AI 3D cùng Bác sĩ, giữ mã ưu đãi/quà tặng trực tiếp) vào lịch hẹn để tạo lý do hợp lý giúp khách không mất công đi lại vô ích. 3. Luôn là người đặt câu hỏi cuối cùng theo dạng lựa chọn khung giờ A/B (Sáng hay Chiều).',
    'Dạ để tránh mình mất công qua mà chưa chọn đúng phác đồ tiết kiệm nhất, Bác sĩ bên em sẽ soi da AI 3D miễn phí 100% trước 10 phút để lên bảng lộ trình chi tiết từng buổi cho mình ạ. Sáng hay Chiều mai mình tiện ghé cơ sở để em giữ sẵn suất ưu đãi cho mình ạ?',
    'Không báo số tổng tiền cứng nhắc khi chưa thăm khám; không kết thúc tin nhắn mà thiếu câu hỏi điều hướng lịch hẹn.',
    92,
    TRUE,
    NULL,
    0.92
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
