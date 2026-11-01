-- ============================================================================
-- V36: P3.5 HOTFIX — AI Identity Honesty (STEP_7), Verified Lower-Tier Trial
--      Suggestion on Repeat Price Hesitation (STEP_6), & V34 Provenance Tag Guard
-- ============================================================================

-- 1. PB-SPA-EMOTIONAL-REPAIR-001: Remove "như người thật đang trò chuyện trực tiếp"
--    and mandate strict AI identity honesty + receptive non-defensive NO_CTA reply.
UPDATE public.learned_rules
SET title = 'Trung thực về danh tính trợ lý AI & cầu thị khi khách nhận xét kịch bản / góp ý cách nói chuyện (Khóa NO_CTA)',
    generalized_rule = 'Khi khách hỏi xoáy về kịch bản trả lời ("Mỗi khi khách chê đắt, bạn được dạy phải reply vậy à", "Bot trả lời à") hoặc góp ý cách diễn đạt giống văn mẫu: bắt buộc trung thực về danh tính là trợ lý AI hỗ trợ tư vấn trên Fanpage (tuyệt đối cấm tự nhận sai sự thật là người thật hay nhân viên thật), phản hồi cầu thị, tự nhiên, lịch sự, không phòng thủ, không trả lời máy móc kiểu tổng đài cụt lủn; khóa NO_CTA tuyệt đối (không gài câu hỏi chào gói hay ép quay lại đặt lịch ở cuối câu).',
    do_actions = 'Thừa nhận trung thực, tự nhiên mình là trợ lý AI hỗ trợ tư vấn trên trang khi khách hỏi về kịch bản/bot/cách được dạy; cầu thị ghi nhận góp ý của khách để trao đổi tự nhiên hơn và dừng lại đúng mực (NO_CTA).',
    dont_actions = 'Tuyệt đối cấm tự nhận "em là người thật" hay giả danh nhân viên thật; không tự ái biện minh; không trả lời máy móc kiểu tổng đài; không thòng câu hỏi gài bán gói hay ép đặt lịch.',
    updated_at = NOW()
WHERE id = '250bf6a0-f16c-4bb8-bb43-c0054a6a1f3a'
  AND rule_code = 'PB-SPA-EMOTIONAL-REPAIR-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 2. PB-SPA-PRICE-OBJECTION-001: On repeat price hesitation ("Nhưng đắt nhỉ", "Hơi cao nhỉ"
--    without explicit closing refusal), suggest a verified lower-cost trial package in Catalog
--    (e.g. Lấy ráy tai cơ bản 30 phút 129k if not yet offered) with priority = BUDGET_FIT.
UPDATE public.learned_rules
SET title = 'Value Anchoring, Reframing & Gợi ý gói trải nghiệm thấp hơn có thực trong Catalog khi khách băn khoăn giá lặp lại',
    generalized_rule = 'Khi khách so sánh giá bên ngoài ("chỗ khác gội đầu có 50k") hoặc chê đắt lần đầu: dùng Value anchoring & Reframing để giải thích nhẹ nhàng sự khác biệt về thời lượng, quy trình chăm sóc và mục đích nghỉ dưỡng dựa trên đúng Catalog của gói đang trao đổi; không chê đối thủ, không tự hạ giá vô lý và cấm chào ngược lên gói đắt hơn. Khi khách vẫn tiếp tục băn khoăn/chê đắt ("Nhưng đắt nhỉ", "Hơi cao nhỉ") mà chưa nói lời từ chối kết thúc cuộc trò chuyện: không nhai lại bài giải thích cũ, đặt priority = BUDGET_FIT (Soft Recommendation), chủ động giảm áp lực và gợi ý nhẹ nhàng gói trải nghiệm ngắn/mức phí thấp hơn có thực trong Catalog của Page (như gói Lấy ráy tai cơ bản 30 phút giá 129k kèm ăn nhẹ nếu chưa giới thiệu) làm bước thử nhỏ vừa vặn, tuyệt đối không bịa gói ngoài Catalog và không ép đặt lịch.',
    do_actions = 'Lượt đầu giải thích nhẹ nhàng khác biệt thời lượng/quy trình/mục đích từ Catalog; lượt tiếp tục băn khoăn giá ("Nhưng đắt nhỉ") chủ động gợi ý nhẹ nhàng gói trải nghiệm thấp hơn có thực trong Catalog (như 129k/30p) làm bước thử nhỏ, không lặp văn cũ và không ép đặt lịch.',
    dont_actions = 'Cấm chào ngược lên gói cao tiền hơn khi khách đang chê đắt; cấm tự bịa gói không có trong Catalog; cấm đóng sớm thành lời chào tạm biệt khi khách mới băn khoăn giá ("Nhưng đắt nhỉ"); cấm nhai lại nguyên bài giải thích cũ; cấm ép đặt lịch.',
    updated_at = NOW()
WHERE id = '9b81f560-4b23-43e8-bf73-88dc53a5ecb5'
  AND rule_code = 'PB-SPA-PRICE-OBJECTION-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 3. price_objection_sensory_value skill: Align (b) repeat price hesitation with verified lower-tier Catalog trial suggestion.
UPDATE public.agent_skills
SET tactical_instructions = '[Tình huống tâm lý khách] Khách so sánh giá dịch vụ bên ngoài ("Nhưng sao đắt thế chỗ khác gội đầu có 50k") hoặc băn khoăn/chê đắt ("239k đắt quá", "Nhưng đắt nhỉ", "Hơi cao nhỉ") -> [Chiến thuật điều hướng] (a) Value anchoring & Reframing (lượt đầu / khi khách so sánh giá): Không lập tức tự khen dịch vụ mình tốt hơn sáo rỗng, không chê đối thủ, không vội giảm giá vô lý và tuyệt đối KHÔNG chào ngược lên gói giá cao hơn; hiểu rằng khách đang so sánh hai dịch vụ khác nhau về thời lượng, quy trình và mục đích để giải thích nhẹ nhàng, có căn cứ từ Catalog của đúng gói đang trao đổi (như thời lượng trọn 60 phút chăm sóc nhịp chậm, quy trình thảo dược & massage theo hạng mục gói, không gian yên tĩnh mỗi giường có rèm che riêng kín đáo, điểm tâm/ăn nhẹ theo gói). (b) Take a step back & Soft recommendation (khi khách tiếp tục băn khoăn giá như "Nhưng đắt nhỉ", "Hơi cao nhỉ" mà chưa nói lời từ chối kết thúc phiên): Đặt priority = BUDGET_FIT, không nhai lại nguyên bài giải thích cũ; chủ động giảm áp lực và gợi ý nhẹ nhàng gói trải nghiệm ngắn/mức phí thấp hơn có thực trong <VERIFIED_BUSINESS_CATALOG> của Page (ví dụ gói Lấy ráy tai cơ bản 30 phút 129k kèm ăn nhẹ nếu chưa giới thiệu) để khách dễ thử cảm nhận không gian trước, tuyệt đối không bịa gói ngoài Catalog và không ép đặt lịch.',
    updated_at = NOW()
WHERE id = '24000001-0000-4000-8000-000000000002'
  AND code = 'price_objection_sensory_value'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 4. Restore V34 provenance metadata tags if previously overwritten by startup backfill
UPDATE public.chunks
SET chunk_metadata = COALESCE(chunk_metadata, '{}'::jsonb) || jsonb_build_object(
        'provenance_normalized', 'V34_P22',
        'special_room_merge_status', 'PENDING_CONFIRMATION'
    )
WHERE id IN (
    '5c6c56f3-f99d-42d3-89de-9cddf336cb54'::uuid,
    'a83e5421-cb0a-49e1-b9f8-f10d3fc745c8'::uuid,
    'db248346-59e7-48ff-a0c9-93c98486a973'::uuid
)
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8'::uuid;
