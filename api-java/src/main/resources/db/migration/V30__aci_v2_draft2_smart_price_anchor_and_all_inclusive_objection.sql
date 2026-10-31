-- ============================================================================
-- V30: ACI V2 Customer Understanding Model (Draft 2), Adaptive Response Planner
--      (NO_ADDITIONAL_ACTION), Smart Price Anchoring (239k/399k, no 1.990k scare),
--      All-Inclusive 0-Tip Objection Handling & Respectful Pause Warmth
-- ============================================================================

-- 1. Neo giá thông minh khi khách hỏi "Giá?" (CẤM dọa giá trần 1.990k)
UPDATE learned_rules
SET title = 'Neo giá thông minh 2 gói tiêu chuẩn 60 phút (239k & 399k) — cấm dọa giá trần 1.990k',
    generalized_rule = 'Khi khách hỏi Giá chung ngắn gọn, không tung khoảng giá rộng tới 1.990k làm khách e ngại; báo trực diện 2 gói trải nghiệm 60 phút tiêu chuẩn dễ tiếp cận nhất là Gói dưỡng sinh cơ bản Đầu – Cổ – Vai – Gáy (239k/60 phút) và Massage cơ bản Toàn thân (399k/60 phút).',
    do_actions = 'Báo trực diện 2 gói 60 phút tiêu chuẩn dễ tiếp cận nhất: Gói dưỡng sinh cơ bản Đầu – Cổ – Vai – Gáy (239k/60 phút) và Massage cơ bản Toàn thân (399k/60 phút) gọn gàng, thân thiện.',
    dont_actions = 'Cấm dọa khoảng giá trần 1.990k khi khách mới hỏi Giá chung; không dùng dấu "+"; không mở bài dài dòng.'
WHERE rule_code = 'PB-SPA-PRICE-ANCHOR-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 2. Xử lý khi khách chê "Đắt" ở gói 239k / 399k: Giải thích "Trọn gói & Thực chất" 0 Tip, CẤM hỏi xoáy ngân sách gây tự ái
UPDATE learned_rules
SET title = 'Giải thích giá trị Trọn gói & Thực chất 100% (0 Tip / 0 phụ phí) — cấm hỏi xoáy ngân sách',
    generalized_rule = 'Khi khách chê Đắt ở gói 239k / 399k, tuyệt đối cấm hỏi câu gây tự ái về ngân sách hay lựa chọn rẻ hơn; hãy đồng cảm tự nhiên và giải thích góc độ Trọn gói & Thực chất: 239k/60 phút đã trọn gói 100% gồm gội thảo dược, massage cổ vai gáy, trà & ăn nhẹ thực dưỡng, không gian rèm riêng tĩnh lặng và cam kết 0 Tip / 0 phụ phí (không bị đội thêm 50k–100k tiền tip như nhiều nơi khác).',
    do_actions = 'Đồng cảm tự nhiên (không mở đầu rập khuôn Dạ em hiểu ạ) rồi giải thích Trọn gói & Thực chất: 239k/60 phút trọn gói 100% (gội thảo dược, massage cổ vai gáy, trà & ăn nhẹ thực dưỡng, không gian rèm riêng và cam kết 0 Tip/0 phụ phí, không đội thêm 50k–100k tiền tip).',
    dont_actions = 'Tuyệt đối cấm hỏi câu đuổi khách: "Mình đang thấy hơi cao so với ngân sách dự kiến, hay muốn tìm hiểu lựa chọn khác?"; không bịa mã giảm giá ngoài tài liệu.',
    retrieval_keywords = ARRAY['đắt', 'đắt nhỉ', 'cao quá', 'giảm giá', 'khuyến mãi', 'km', 'ưu đãi', 'tip', 'phụ phí']
WHERE rule_code = 'PB-SPA-PRICE-OBJECTION-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 3. Thu hẹp PB-SPA-PRICE-OBJECTION-002 chỉ áp dụng khi đang tư vấn gói cao (>= 580k) và khách chủ động xin gói nhẹ hơn
UPDATE learned_rules
SET title = 'Chuyển gói tiết kiệm khi đang ở gói chuyên sâu (>= 580k) và khách chủ động xin phương án nhẹ hơn',
    generalized_rule = 'Chỉ khi đang tư vấn các gói chuyên sâu/VIP (>= 580k) mà khách chủ động hỏi gói nhẹ hơn thì mới giới thiệu gói tiêu chuẩn 239k/60 phút hoặc 399k/60 phút cùng vùng mỏi; khi đã ở gói 239k/399k thì giải thích giá trị trọn gói 0 Tip, không hạ sang dịch vụ lệch nhu cầu.',
    do_actions = 'Khi đang tư vấn gói >= 580k và khách xin gói tiết kiệm hơn: giới thiệu gói tiêu chuẩn 239k (đầu-cổ-vai-gáy) hoặc 399k (toàn thân); ở gói 239k/399k thì giữ nguyên gói và làm rõ giá trị trọn gói 0 Tip.',
    dont_actions = 'Cấm hỏi xoáy ngân sách dự kiến của khách; cấm gạ dịch vụ lệch vùng mỏi chỉ vì rẻ hơn.'
WHERE rule_code = 'PB-SPA-PRICE-OBJECTION-002'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 4. Phân biệt Need Revision (khách chủ động đổi ý) với lỗi hiểu nhầm của Bot
UPDATE learned_rules
SET title = 'Need Revision & Đính chính: Đổi sang nhu cầu mới mượt mà, không hỏi vặn lại điều khách vừa nói rõ',
    generalized_rule = 'Khi khách chủ động đổi nhu cầu (như "Thôi, mình muốn massage toàn thân hơn"), cập nhật ngay nhu cầu mới làm CURRENT và gợi ý gọn gói phù hợp (Massage cơ bản Toàn thân 399k/60 phút) mà không xin lỗi thừa hay hỏi vặn xác nhận lại; chỉ xin lỗi ngắn khi Bot hiểu sai lời khách.',
    do_actions = 'Khi khách đổi ý rõ ràng (Need Revision): ghi nhận mượt mà và gợi ý gọn gói khớp nhu cầu mới ngay, tôn trọng phong cách CONCISE nếu đã bật và chưa tự ép đặt lịch.',
    dont_actions = 'Không hỏi vặn xác nhận lại điều khách vừa nói rõ; không tự ý bật chốt giờ đặt lịch khi khách mới đổi gói tham khảo.'
WHERE rule_code = 'PB-SPA-CORRECTION-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 5. Đồng bộ skill price_objection_sensory_value theo chuẩn Trọn gói & Thực chất 0 Tip (cấm hỏi xoáy ngân sách)
UPDATE agent_skills
SET tactical_instructions = '[Tình huống tâm lý khách] Khách phản ứng về giá ("Đắt", "Đắt nhỉ", "Cao quá") ở gói 239k hoặc 399k -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Tuyệt đối CẤM hỏi câu gây tự ái "Mình đang thấy hơi cao so với ngân sách dự kiến, hay muốn tìm hiểu lựa chọn khác?". Hãy đồng cảm tự nhiên (không mở đầu rập khuôn "Dạ em hiểu ạ") rồi giải thích góc độ Trọn gói & Thực chất: mức 239k/60 phút tại Nàng Sen là trọn gói 100% (đã gồm gội thảo dược, chăm sóc cổ vai gáy, trà & ăn nhẹ thực dưỡng, không gian rèm riêng tĩnh lặng và cam kết không nhận Tip/phụ phí — không bị đội thêm 50k–100k tiền tip như nhiều nơi khác)',
    anti_pattern = 'Hỏi xoáy ngân sách gây tự ái ("cao so với ngân sách dự kiến, hay muốn lựa chọn khác?"), mở đầu rập khuôn "Dạ em hiểu ạ", hoặc hạ gói lệch nhu cầu.'
WHERE code = 'price_objection_sensory_value'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 6. Đồng bộ skill graceful_pause_keep_warmth theo kỹ thuật "Lùi giao dịch – Tiến cảm xúc" (Respectful Pause + NO_ADDITIONAL_ACTION)
UPDATE agent_skills
SET tactical_instructions = '[Tình huống tâm lý khách] Khách nói "Để nghĩ đã", "Thôi để hôm nào rảnh mình qua thử" hoặc xin tạm dừng -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Áp dụng "Lùi giao dịch – Tiến cảm xúc" (Respectful Pause + NO_ADDITIONAL_ACTION): đón nhận nhẹ nhàng, tuyệt đối không níu kéo hay thòng thêm câu hỏi ép giờ/chọn cơ sở ở cuối câu, gửi kèm một lời chúc và dặn dò khách thư giãn nhẹ nhàng tại nhà (như vươn vai, chườm ấm nhẹ vùng cổ gáy) để giữ ấn tượng ấm áp',
    anti_pattern = 'Thòng câu hỏi khảo sát/ép chọn khung giờ ở cuối câu khi khách đã xin tạm dừng, hoặc trả lời cụt lủn lạnh nhạt.'
WHERE code = 'graceful_pause_keep_warmth'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';
