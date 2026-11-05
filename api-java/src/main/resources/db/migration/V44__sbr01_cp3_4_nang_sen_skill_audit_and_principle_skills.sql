-- ============================================================================
-- V44 — SBR-01 Wave 1 (CP3.4): Nàng Sen Skill Audit & Principle-Based Skills
-- ============================================================================
-- Scope: Nàng Sen page ONLY (page_id = '2850a8ef-ca4a-40eb-87fa-4f808640efee').
-- Strictly does NOT touch Page thẩm mỹ viện (negative control) or any other page.
-- Does NOT restore V16 skills.
--
-- BẢNG KIỂM TOÁN 6 SKILL HIỆN CÓ CỦA NÀNG SEN:
-- +--------------------------------------+-------------------------------------+-----------------------------------------------+---------------------------------------------------------------+
-- | ID / Code                            | Trigger Signals                     | Lỗi liên quan (Failure Modes)                 | Quyết định (Giữ / Sửa / Xóa)                                  |
-- +--------------------------------------+-------------------------------------+-----------------------------------------------+---------------------------------------------------------------+
-- | 24000001-0000-4000-8000-000000000001 | SHARING_CONDITION_OR_CONTEXT,       | Từng chứa ví dụ "đi tập nặng về" (đã trung    | SỬA (GIỮ ACTIVE): Rút gọn tactical_instructions về nguyên tắc |
-- | discovery_empathy_and_scope          | ACKNOWLEDGED_GENERAL_INFO           | hòa ở V43) và câu thoại mẫu dài dễ bị chép.   | ngắn, bỏ phần câu thoại mẫu nhúng trong tactical_instructions.|
-- +--------------------------------------+-------------------------------------+-----------------------------------------------+---------------------------------------------------------------+
-- | 24000001-0000-4000-8000-000000000002 | PRICE_OBJECTION_FIRST_TIME,         | Nhúng câu thoại mẫu hoàn chỉnh trong          | SỬA (GIỮ ACTIVE): Chuyển thành nguyên tắc ngắn (đồng cảm ngân |
-- | price_objection_sensory_value        | PRICE_OBJECTION                     | tactical_instructions gây lặp khung câu.      | sách, nêu giá trị quy trình/thời lượng thật, không cam kết y).|
-- +--------------------------------------+-------------------------------------+-----------------------------------------------+---------------------------------------------------------------+
-- | 24000001-0000-4000-8000-000000000003 | PRICE_HESITATION_AFTER_VALUE_DEFENSE| Ôm đồm cả cầu nối gói tiết kiệm lẫn so sánh   | SỬA (GIỮ ACTIVE): Chuyên biệt hóa cho cầu nối gói tiết kiệm   |
-- | soft_economical_bridge_and_compare   | PRICE_OBJECTION_REPEAT_OR_DOWNSELL  | gói, kèm câu mẫu dài.                         | cùng nhóm dịch vụ; tách so sánh gói sang skill riêng.         |
-- +--------------------------------------+-------------------------------------+-----------------------------------------------+---------------------------------------------------------------+
-- | 24000001-0000-4000-8000-000000000004 | POSITIVE_CONFIRMATION_SIGNAL,       | Chứa câu thoại mẫu cố định về góc giường/rèm   | SỬA (GIỮ ACTIVE - Booking Cấp 2): Viết dạng nguyên tắc ngắn   |
-- | warm_nod_to_gentle_scheduling        | POSITIVE_INTEREST_READY_FOR_TRIAL   | riêng dễ lặp lại nguyên văn.                  | khi khách đồng ý thử gói, hỏi nhẹ khung giờ dự kiến.          |
-- +--------------------------------------+-------------------------------------+-----------------------------------------------+---------------------------------------------------------------+
-- | 24000001-0000-4000-8000-000000000005 | ACCEPTED_PROPOSED_TIME_SLOT,        | Chứa câu thoại mẫu hoàn chỉnh trong           | SỬA (GIỮ ACTIVE - Booking Cấp 3): Chỉ mở khi khách yêu cầu    |
-- | booking_careful_preparation          | PACKAGE_ACCEPTANCE_OR_BOOKING       | tactical_instructions.                        | đặt lịch rõ; ghi nhận gói + giờ và báo chờ kiểm tra chỗ trống.|
-- +--------------------------------------+-------------------------------------+-----------------------------------------------+---------------------------------------------------------------+
-- | 24000001-0000-4000-8000-000000000006 | POLITE_CLOSING_ACKNOWLEDGMENT,      | Chứa câu chúc mẫu dài dễ lặp lại máy móc.     | SỬA (GIỮ ACTIVE): Nguyên tắc tôn trọng khoảng nghỉ, 0 câu hỏi,|
-- | graceful_pause_keep_warmth           | HESITATION_OR_DEFERRAL              |                                               | 0 chào thêm gói mới ngoài mạch hội thoại.                     |
-- +--------------------------------------+-------------------------------------+-----------------------------------------------+---------------------------------------------------------------+
--
-- BỔ SUNG 4 SKILL NGUYÊN TẮC NGẮN (KHÔNG NHÚNG CÂU TRẢ LỜI MẪU HOÀN CHỈNH):
-- 1. package_comparison_and_scope_clarity (24000001-0000-4000-8000-000000000007): Giải thích và so sánh gói.
-- 2. catalog_breadth_clarification        (24000001-0000-4000-8000-000000000008): Làm rõ danh mục gói.
-- 3. service_recovery_to_consultation_pivot (24000001-0000-4000-8000-000000000009): Thoát recovery và quay lại tư vấn.
-- 4. gentle_next_step_by_signal           (24000001-0000-4000-8000-000000000010): Gợi ý bước tiếp theo nhẹ nhàng theo tín hiệu khách.
-- ============================================================================

-- 1. Rút gọn 6 skill hiện có của Nàng Sen về nguyên tắc ngắn, bỏ câu thoại mẫu nhúng
UPDATE agent_skills
SET tactical_instructions = '[Tình huống tâm lý khách] Khách chia sẻ cơ thể căng mỏi, làm việc nhiều, uể oải hoặc khó ngủ nhưng chưa nêu rõ hướng trải nghiệm ưu tiên -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Ghi nhận ngắn gọn, tự nhiên cảm giác mỏi mệt của khách; gợi ý nhẹ 1 hướng thư giãn phù hợp trong Catalog hoặc hỏi 1 câu mở về mong muốn trải nghiệm (nhẹ nhàng hay chuyên sâu, thời lượng); không tự giả định nguyên nhân hay vùng cơ thể khi khách chưa nêu và không khuyên khám bệnh viện khi không có dấu hiệu cấp cứu.',
    good_example = 'Nguyên tắc: Ghi nhận ngắn gọn tình trạng mỏi mệt khách nêu + gợi ý nhẹ hướng thư giãn hoặc hỏi 1 câu mở về mong muốn trải nghiệm (không chép văn mẫu, không tự gán vùng cơ thể).',
    updated_at = now()
WHERE id = '24000001-0000-4000-8000-000000000001'
  AND page_id = '2850a8ef-ca4a-40eb-87fa-4f808640efee';

UPDATE agent_skills
SET tactical_instructions = '[Tình huống tâm lý khách] Khách băn khoăn chi phí khi vừa nghe mức giá liệu trình -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Công nhận băn khoăn ngân sách của khách một cách tôn trọng; làm rõ giá trị cốt lõi theo đúng thời lượng và quy trình thực tế trong Catalog gắn với nhu cầu thư giãn của khách, không lặp lại danh sách tiện ích phụ vừa nêu ở lượt trước và không cam kết điều trị triệu chứng bệnh lý.',
    good_example = 'Nguyên tắc: Đồng cảm ngắn gọn về chi phí + làm rõ giá trị thời lượng và quy trình chăm sóc thực tế trong Catalog + gợi ý phương án tiết kiệm cùng nhóm dịch vụ nếu phù hợp.',
    updated_at = now()
WHERE id = '24000001-0000-4000-8000-000000000002'
  AND page_id = '2850a8ef-ca4a-40eb-87fa-4f808640efee';

UPDATE agent_skills
SET tactical_instructions = '[Tình huống tâm lý khách] Khách ngập ngừng về ngân sách hoặc tìm phương án vừa túi tiền hơn -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Giải tỏa áp lực quyết định, giới thiệu gói cơ bản/tiết kiệm hơn cùng nhóm dịch vụ trong Catalog kèm đúng tên gói, giá và thời lượng; tôn trọng lựa chọn thử gói nhỏ của khách, không hạ thấp gói cơ bản và không ép upsell.',
    good_example = 'Nguyên tắc: Tôn trọng ngân sách khách + giới thiệu rõ tên, giá, thời lượng của gói cơ bản/tiết kiệm cùng nhóm dịch vụ trong Catalog mà không ép chốt.',
    updated_at = now()
WHERE id = '24000001-0000-4000-8000-000000000003'
  AND page_id = '2850a8ef-ca4a-40eb-87fa-4f808640efee';

UPDATE agent_skills
SET tactical_instructions = '[Tình huống tâm lý khách] Khách đồng ý thử một liệu trình cụ thể sau khi đã nghe tư vấn (Booking Cấp 2) -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Đón nhận sự đồng thuận của khách ngắn gọn, ấm áp; giữ đúng gói khách vừa chọn (không tự ý đổi sang gói đắt hơn) và hỏi nhẹ chi nhánh hoặc khung giờ dự kiến để chuẩn bị đón tiếp.',
    good_example = 'Nguyên tắc: Xác nhận đúng gói khách đồng ý thử + hỏi nhẹ chi nhánh hoặc khung giờ khách tiện ghé.',
    updated_at = now()
WHERE id = '24000001-0000-4000-8000-000000000004'
  AND page_id = '2850a8ef-ca4a-40eb-87fa-4f808640efee';

UPDATE agent_skills
SET tactical_instructions = '[Tình huống tâm lý khách] Khách chủ động yêu cầu đặt lịch rõ ràng hoặc chốt ngày giờ cụ thể (Booking Cấp 3) -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Ghi nhận chính xác tên gói, chi nhánh, ngày giờ và lưu ý của khách; thông báo rõ đang giữ lịch tạm chờ lễ tân kiểm tra giường và kỹ thuật viên trống thực tế (AWAITING_AVAILABILITY_CHECK), tuyệt đối không tự tuyên bố đã xác nhận lịch chính thức.',
    good_example = 'Nguyên tắc: Ghi nhận đúng gói, cơ sở, khung giờ khách chọn + báo kiểm tra chỗ trống thực tế với lễ tân trước khi xác nhận chính thức.',
    updated_at = now()
WHERE id = '24000001-0000-4000-8000-000000000005'
  AND page_id = '2850a8ef-ca4a-40eb-87fa-4f808640efee';

UPDATE agent_skills
SET tactical_instructions = '[Tình huống tâm lý khách] Khách báo bận, xin suy nghĩ thêm hoặc tạm dừng cuộc trò chuyện -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Tôn trọng quyết định tạm dừng của khách ngay lập tức bằng 1 bong bóng ngắn gọn, ấm áp; không đặt thêm câu hỏi, không giới thiệu thêm gói mới ngoài các gói đã trao đổi.',
    good_example = 'Nguyên tắc: Ghi nhận sự cân nhắc của khách nhẹ nhàng trong 1 bong bóng + không đặt câu hỏi + không chào thêm gói mới.',
    updated_at = now()
WHERE id = '24000001-0000-4000-8000-000000000006'
  AND page_id = '2850a8ef-ca4a-40eb-87fa-4f808640efee';

-- 2. Thêm 4 skill nguyên tắc ngắn chỉ dành riêng cho page Nàng Sen (2850a8ef-ca4a-40eb-87fa-4f808640efee)
INSERT INTO agent_skills (
    id, code, name, category, description, trigger_signals,
    tactical_instructions, good_example, anti_pattern,
    priority, active, page_id, confidence_score, created_at, updated_at
)
SELECT
    v.id::uuid,
    v.code,
    v.name,
    v.category,
    v.description,
    v.trigger_signals,
    v.tactical_instructions,
    v.good_example,
    v.anti_pattern,
    v.priority,
    true,
    '2850a8ef-ca4a-40eb-87fa-4f808640efee'::uuid,
    0.96,
    now(),
    now()
FROM (
    VALUES
    (
        '24000001-0000-4000-8000-000000000007',
        'package_comparison_and_scope_clarity',
        'Giải thích & So sánh gói theo phạm vi thực tế (Nàng Sen)',
        'SALES_STRATEGY',
        'Đối chiếu rõ sự khác biệt giữa các gói vừa nêu theo phạm vi chăm sóc, thời lượng và hạng mục thực tế trong Catalog.',
        'PACKAGE_COMPARISON_REQUEST,SERVICE_OR_UNIT_DETAIL_QUESTION',
        '[Tình huống tâm lý khách] Khách hỏi điểm khác nhau giữa 2 hay nhiều gói vừa được nhắc tới hoặc hỏi chi tiết phạm vi gói -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Đối chiếu trực diện đúng các gói vừa nêu theo 3 trục: phạm vi vùng chăm sóc, thời lượng thực hiện và hạng mục cốt lõi trong Catalog; giữ đúng tên gói và mức giá đã báo, không tự ý đổi sang gói khác và không lặp lại câu hỏi khám phá vùng mỏi.',
        'Nguyên tắc: So sánh đúng các gói vừa nêu theo phạm vi chăm sóc + thời lượng + hạng mục trong Catalog; không đổi gói, không hỏi lại vùng mỏi.',
        'Đổi sang báo giá gói khác không nằm trong câu hỏi so sánh của khách hoặc trả lời chung chung không nêu rõ điểm khác biệt.',
        96
    ),
    (
        '24000001-0000-4000-8000-000000000008',
        'catalog_breadth_clarification',
        'Làm rõ độ rộng danh mục gói dịch vụ (Nàng Sen)',
        'SALES_STRATEGY',
        'Làm rõ bên spa có nhiều gói đa dạng và giới thiệu ngay các gói khác trong Catalog khi khách hỏi thêm lựa chọn.',
        'ALTERNATIVE_PACKAGE_REQUEST,CATALOG_BREADTH_INQUIRY',
        '[Tình huống tâm lý khách] Khách hỏi còn gói nào khác không hoặc thắc mắc tưởng bên spa chỉ có 1-2 gói -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Xác nhận ngắn gọn bên spa có nhiều gói đa dạng theo từng nhu cầu rồi giới thiệu ngay 2-3 gói khác trong Catalog (đúng tên gói, giá, thời lượng, đúng chi nhánh, không lặp lại gói vừa báo ở lượt trước).',
        'Nguyên tắc: Xác nhận danh mục có nhiều gói + nêu ngay 2-3 gói khác trong Catalog kèm giá và thời lượng chuẩn xác.',
        'Chỉ xin lỗi suông mà không nêu tên và giá các gói khác trong Catalog, hoặc lặp lại đúng gói khách vừa chê.',
        95
    ),
    (
        '24000001-0000-4000-8000-000000000009',
        'service_recovery_to_consultation_pivot',
        'Thoát phục hồi lỗi & Quay lại tư vấn trực diện (Nàng Sen)',
        'COMMUNICATION',
        'Nhận lỗi ngắn gọn 1 câu khi khách phàn nàn kèm câu hỏi nghiệp vụ và trả lời thẳng vào câu hỏi của khách.',
        'POST_RECOVERY_BUSINESS_INQUIRY,MIXED_COMPLAINT_AND_INQUIRY',
        '[Tình huống tâm lý khách] Khách vừa phàn nàn cách tư vấn kèm theo một câu hỏi nghiệp vụ (hỏi giá, so sánh gói, hỏi gói khác, hỏi phạm vi) hoặc đã chuyển sang hỏi thông tin sau lượt xin lỗi -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Chỉ nhận lỗi trực diện tối đa 1 câu ngắn (không lặp lại lời xin lỗi nếu lượt trước đã xin lỗi) và trả lời đầy đủ câu hỏi nghiệp vụ của khách bằng dữ liệu Catalog; ngắt hoàn toàn lời mời đặt lịch.',
        'Nguyên tắc: Tối đa 1 câu nhận lỗi trực diện + trả lời thẳng câu hỏi gói/giá/phạm vi từ Catalog + 0 CTA đặt lịch.',
        'Chỉ xin lỗi suông bỏ quên câu hỏi nghiệp vụ của khách, hoặc lặp lại lời xin lỗi ở các lượt kế tiếp khi khách đã hỏi sang chuyện khác.',
        97
    ),
    (
        '24000001-0000-4000-8000-000000000010',
        'gentle_next_step_by_signal',
        'Gợi ý bước tiếp theo nhẹ nhàng theo tín hiệu khách (Nàng Sen)',
        'COMMUNICATION',
        'Đáp ứng thông tin lịch/chi nhánh và gợi ý nhẹ bước tiếp theo khi khách chủ động phát tín hiệu tìm hiểu lịch hoặc chọn gói thử.',
        'SCHEDULE_OR_BRANCH_INQUIRY,TRIAL_PACKAGE_SELECTION',
        '[Tình huống tâm lý khách] Khách chủ động hỏi lịch trống, hỏi chi nhánh, giờ mở cửa, cách đặt lịch hoặc đồng ý thử một gói cụ thể (Booking Cấp 1-2) -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Trả lời rõ thông tin khách hỏi và gợi ý nhẹ 1 bước tiếp theo phù hợp với tín hiệu của khách (như chọn cơ sở thuận tiện hoặc khung giờ dự kiến), không ép chốt lịch khi khách chưa có tín hiệu.',
        'Nguyên tắc: Trả lời rõ thông tin chi nhánh/khung giờ khách hỏi + gợi ý nhẹ 1 bước tiếp theo dựa trên tín hiệu khách vừa nêu.',
        'Đẩy câu hỏi chốt lịch hoặc hỏi khung giờ khi khách mới đang hỏi triệu chứng hoặc chưa có bất kỳ tín hiệu quan tâm lịch/chọn gói nào.',
        94
    )
) AS v(id, code, name, category, description, trigger_signals, tactical_instructions, good_example, anti_pattern, priority)
WHERE EXISTS (
    SELECT 1 FROM knowledge_pages kp WHERE kp.id = '2850a8ef-ca4a-40eb-87fa-4f808640efee'
)
ON CONFLICT (id) DO UPDATE
SET code = EXCLUDED.code,
    name = EXCLUDED.name,
    category = EXCLUDED.category,
    description = EXCLUDED.description,
    trigger_signals = EXCLUDED.trigger_signals,
    tactical_instructions = EXCLUDED.tactical_instructions,
    good_example = EXCLUDED.good_example,
    anti_pattern = EXCLUDED.anti_pattern,
    priority = EXCLUDED.priority,
    active = EXCLUDED.active,
    updated_at = now();
