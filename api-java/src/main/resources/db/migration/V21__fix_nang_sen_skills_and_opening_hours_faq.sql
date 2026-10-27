-- V21: Fix Nàng Sen Spa micro-skill durations (spa_clarify_duration_once),
-- emotional repair learned rule (PB-SPA-EMOTIONAL-REPAIR-001),
-- and opening hours FAQ / policy chunks (09:00 - 21:00, nhận khách cuối 19:30).

-- 1. Fix P0-4: Update spa_clarify_duration_once to use real catalog durations (60, 80, 100 minutes) instead of 75/90 minutes
UPDATE agent_skills
SET name = 'Hỏi làm rõ thời lượng 60p/80p/100p tối đa 1 lần',
    description = 'Khi đã biết nhóm dịch vụ massage nhưng chưa rõ thời lượng để báo giá, hỏi 60 phút, 80 phút hay 100 phút tối đa 1 lần.',
    tactical_instructions = 'Khi đã biết nhóm dịch vụ massage nhưng chưa rõ thời lượng/nhu cầu để báo giá, hỏi đúng 1 lần khách muốn xem gói 60 phút, 80 phút hay 100 phút (hoặc đang mỏi vùng nào) theo đúng Catalog hiện hành; tuyệt đối không nói 75 phút hay 90 phút cho gói massage.',
    good_example = 'Dạ với dịch vụ massage bên em có các mức thời lượng 60 phút, 80 phút và 100 phút theo bảng giá hiện tại ạ. Mình đang nhức mỏi vùng nào hay muốn xem mức thời lượng nào để em báo giá chi tiết nha?',
    updated_at = NOW()
WHERE page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8'
  AND code = 'spa_clarify_duration_once';

-- 2. Fix P0-2: Update PB-SPA-EMOTIONAL-REPAIR-001 so wording feedback only acknowledges briefly without sales pitch, questions, or 💙
UPDATE learned_rules
SET generalized_rule = 'Khi khách góp ý về cách diễn đạt hoặc giọng điệu (ví dụ: "nghe thảo mai vãi", "nói chuyện máy móc thế", "văn mẫu quá"), chỉ tiếp nhận góp ý ngắn gọn, tự nhiên trong 1-2 câu ngắn ("Dạ cảm ơn mình đã góp ý thẳng thắn nha, để em tiết chế lại và nói chuyện ngắn gọn, tự nhiên hơn ạ."), TUYỆT ĐỐI KHÔNG kèm quảng cáo dịch vụ, KHÔNG đặt câu hỏi ("?"), KHÔNG dùng từ "xin lỗi" và KHÔNG dùng icon 💙.',
    do_actions = '["Tiếp nhận góp ý về cách nói chuyện một cách ngắn gọn, cầu thị, tự nhiên trong 1 bong bóng ngắn (0 câu hỏi)"]',
    dont_actions = '["CẤM kèm quảng cáo dịch vụ, kể lể kỹ thuật viên hay không gian spa trong lượt tiếp nhận góp ý giọng văn", "CẤM đặt câu hỏi (?)", "CẤM dùng từ xin lỗi", "CẤM dùng icon 💙", "CẤM lặp câu hỏi xác nhận hiển nhiên ... phải không? / ... đúng không?"]',
    updated_at = NOW()
WHERE page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8'
  AND rule_code = 'PB-SPA-EMOTIONAL-REPAIR-001';

-- 3. Fix P0-5: Ensure opening hours (09:00 - 21:00, nhận khách cuối 19:30) are explicitly grounded in Nàng Sen Spa policy & FAQ chunks
UPDATE chunks
SET text = E'Chính sách vận hành & chăm sóc khách hàng – Nàng Sen Spa\nChính sách vận hành & chăm sóc khách hàng\nÁp dụng thống nhất tại hệ thống Nàng Sen Spa để đảm bảo trải nghiệm yên tĩnh, riêng tư và chu đáo cho mọi khách hàng.\n1. Chính sách đặt lịch & hủy lịch\n| Hạng mục | Quy định chi tiết |\n| --- | --- |\n| Giờ mở cửa hoạt động & Nhận khách cuối | Mở cửa từ 09:00 - 21:00 hàng ngày (nhận khách cuối lúc 19:30 để đảm bảo trọn vẹn thời lượng liệu trình) |\n| Thời gian giữ lịch (Hold booking) | Giữ lịch tối đa 15 phút so với giờ hẹn. Quá 15 phút chưa thông báo, lịch hẹn sẽ tự động chuyển sang danh sách chờ để ưu tiên khách tiếp theo. |\n| Đổi / Hủy lịch hẹn | Quý khách vui lòng báo trước tối thiểu 2 tiếng trước giờ hẹn qua Hotline hoặc Fanpage để được hỗ trợ đổi khung giờ miễn phí. |\n| Phụ thu ngoài giờ (After-hours) | Không thu phụ phí đối với các ca trị liệu kết thúc muộn nếu khách đã check-in trước giờ nhận khách cuối (19:30). |\n| Đặt cọc (Deposit) | Không yêu cầu đặt cọc đối với khách lẻ (1-2 khách). Đoàn từ 3 khách trở lên hoặc đặt phòng Couple khung giờ cao điểm cuối tuần cần xác nhận trước qua điện thoại. |'
WHERE id = '117aab0c-5ac7-45b2-975d-212d8e8b88f4';

UPDATE chunks
SET text = text || E'\n| 76 | Bên mình mở cửa tới mấy giờ? / Giờ mở cửa hoạt động của Nàng Sen Spa? | Dạ Nàng Sen Spa mở cửa từ 09:00 đến 21:00 hàng ngày, và nhận khách cuối lúc 19:30 để đảm bảo trọn vẹn thời lượng liệu trình cho mình ạ. |'
WHERE id = 'f5a47e21-0a7c-471a-843e-dd7ba7d22fb1'
  AND text NOT LIKE '%nhận khách cuối lúc 19:30%';

UPDATE chunks
SET text = REPLACE(text, 'Giờ hoạt động: 9:00 - 22:00', 'Giờ hoạt động: 09:00 - 21:00 (nhận khách cuối 19:30)')
WHERE document_id = 'c9481efb-f3a9-4613-a2e0-fe50e9b99b39'
  AND text LIKE '%Giờ hoạt động: 9:00 - 22:00%';

-- 4. Fix Dental tenant (Nha Khoa Tỏa Sáng) symptom_keywords so "cạo vôi răng" (service) is not misclassified as a symptom "vôi răng"
UPDATE knowledge_pages
SET persona_config = jsonb_set(
    persona_config,
    '{symptom_keywords}',
    '["ố vàng", "xỉn màu", "ê buốt", "sâu răng", "mẻ răng", "chảy máu chân răng", "viêm lợi", "đau răng"]'::jsonb
)
WHERE id = 'd3000000-0000-0000-0000-000000000001';

