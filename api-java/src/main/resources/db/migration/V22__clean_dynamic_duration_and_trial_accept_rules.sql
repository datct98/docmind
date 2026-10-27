-- V22: P0/P1 Runtime & Playbook Alignment
-- 1. Remove hardcoded duration numbers from spa_clarify_duration_once (rely on valid_catalog_durations in <GROUNDED_TURN_FACTS>)
-- 2. Fix spa_trial_acceptance_booking and PB-SPA-TRIAL-ACCEPT-001: forbid "Em vui mừng khi...", forbid repeating price,
--    forbid claiming "đã giữ lịch" before visitTime is known, and forbid cramming time slot + phone number into one sentence.
-- 3. Update PB-SPA-EMOTIONAL-REPAIR-001 to cover both pure STYLE_REPAIR and funnel-preserving STYLE_REPAIR when bookingStatus = COLLECTING_INFO.
-- 4. Update spa_objection_value_trial and PB-SPA-PRICE-OBJECTION-001 for strict catalog grounding, max 2 bubbles, no premature downsell, and no cross-turn repetition.

-- 1. P1.6: Dynamic catalog durations in spa_clarify_duration_once
UPDATE agent_skills
SET name = 'Hỏi làm rõ thời lượng theo Catalog hiện hành tối đa 1 lần',
    description = 'Khi đã biết nhóm dịch vụ nhưng chưa rõ thời lượng để báo giá, hỏi khách chọn các mốc thời lượng có trong valid_catalog_durations tối đa 1 lần.',
    tactical_instructions = 'Khi đã biết nhóm dịch vụ nhưng chưa rõ thời lượng/nhu cầu để báo giá, hỏi đúng 1 lần khách muốn xem mức thời lượng nào theo đúng danh sách valid_catalog_durations trong <GROUNDED_TURN_FACTS> (hoặc đang mỏi vùng nào); tuyệt đối không tự bịa mốc thời lượng ngoài valid_catalog_durations.',
    good_example = 'Dạ với dịch vụ này bên em có các mức thời lượng theo bảng giá hiện tại trong Catalog ạ. Mình đang nhức mỏi vùng nào hay muốn xem mức thời lượng nào để em báo giá chi tiết nha?',
    updated_at = NOW()
WHERE code = 'spa_clarify_duration_once';

-- 2. P0.4: Natural trial acceptance in spa_trial_acceptance_booking & PB-SPA-TRIAL-ACCEPT-001
UPDATE agent_skills
SET name = 'Hỗ trợ bước chọn khung giờ ghé khi khách đồng ý thử gói (Trial Acceptance)',
    description = 'Khi khách đồng ý thử gói (packageInterestAccepted = true), phản hồi tự nhiên trong 1 bong bóng ngắn và hỏi khung giờ dự kiến ghé.',
    tactical_instructions = 'Khi khách đồng ý thử gói vừa đề xuất (ví dụ "Uk thử xem", "Ok làm gói đó"): 1) Phản hồi ngắn gọn, tự nhiên trong 1 bong bóng để chuyển sang bước hỏi khung giờ dự kiến ghé (bookingStatus = COLLECTING_INFO). 2) TUYỆT ĐỐI CẤM dùng mẫu câu dịch máy "Em vui mừng khi...", CẤM lặp lại con số giá đã báo, CẤM tự tuyên bố "đã giữ lịch" khi chưa có thông tin giờ ghé thực tế, và CẤM nhồi cả khung giờ lẫn số điện thoại vào một câu dài.',
    good_example = 'Dạ vâng ạ. Mình dự kiến ghé bên em vào khung giờ nào để em hỗ trợ tiếp nhé?',
    updated_at = NOW()
WHERE code = 'spa_trial_acceptance_booking';

UPDATE learned_rules
SET generalized_rule = '[PB-SPA-TRIAL-ACCEPT-001 - Khách đồng ý thử gói (packageInterestAccepted = true) -> hỏi ngắn gọn khung giờ ghé] NÊN LÀM: Phản hồi ngắn gọn, tự nhiên trong 1 bong bóng và hỏi khung giờ khách dự kiến ghé để hỗ trợ bước tiếp theo (ví dụ: "Dạ vâng ạ. Mình dự kiến ghé bên em vào khung giờ nào để em hỗ trợ tiếp nhé?"). TRÁNH: Tuyệt đối cấm mẫu câu dịch máy "Em vui mừng khi...", cấm lặp lại con số giá đã báo, cấm tự tuyên bố "đã giữ lịch" khi chưa có giờ ghé thực tế, và cấm nhồi cả giờ ghé lẫn số điện thoại vào một câu dài.',
    do_actions = 'Phản hồi tự nhiên, ngắn gọn trong 1 bong bóng và hỏi khung giờ khách dự kiến ghé.',
    dont_actions = 'Cấm dùng "Em vui mừng khi..."; cấm lặp lại con số giá đã báo; cấm tự tuyên bố "đã giữ lịch" khi chưa có giờ ghé; cấm nhồi cả khung giờ và số điện thoại vào 1 câu.',
    updated_at = NOW()
WHERE rule_code = 'PB-SPA-TRIAL-ACCEPT-001';

-- 3. P0.3 & P0.4: Two-layer STYLE_REPAIR and funnel preservation in PB-SPA-EMOTIONAL-REPAIR-001
UPDATE learned_rules
SET generalized_rule = 'Khi khách góp ý/phàn nàn về cách giao tiếp của bot (nhại lời, lặp đi lặp lại, nói câu đã bảo rồi, hoặc chê cách dùng từ như "em vui mừng khi..."): 1) Nếu chưa vào bước đặt lịch (bookingStatus != COLLECTING_INFO): phản hồi ngắn gọn trong 1 bong bóng thừa nhận đúng điểm vừa nói chưa khéo, TUYỆT ĐỐI CẤM kèm mô tả công dụng gói dịch vụ và CẤM hỏi lại câu mời trải nghiệm (0 câu hỏi). 2) Nếu khách đã đồng ý thử gói trước đó (packageInterestAccepted = true, bookingStatus = COLLECTING_INFO): tiếp nhận lời góp ý ngắn gọn rồi hỗ trợ tiếp bước chọn khung giờ ghé, TUYỆT ĐỐI CẤM lùi phễu hỏi lại khách có muốn thử gói hay không.',
    do_actions = '["Thừa nhận ngắn gọn đúng điểm giao tiếp chưa khéo, không dùng văn mẫu xin lỗi dài dòng", "Nếu bookingStatus = COLLECTING_INFO thì giữ nguyên vị trí phễu và hỏi nhẹ khung giờ khách dự kiến ghé"]',
    dont_actions = '["CẤM đính kèm câu mô tả công dụng gói dịch vụ trong lượt STYLE_REPAIR", "CẤM hỏi lại câu mời trải nghiệm (Mình muốn thử trải nghiệm một buổi không ạ?)", "CẤM lùi phễu khi khách đã đồng ý thử gói", "CẤM dùng icon 💙"]',
    updated_at = NOW()
WHERE rule_code = 'PB-SPA-EMOTIONAL-REPAIR-001';

-- 4. P1.5: First-time price objection grounded strictly in verified Catalog & Policy chunks
UPDATE agent_skills
SET name = 'Xử lý phản đối giá lần 1 bám sát Catalog đã xác minh (Tối đa 2 bong bóng)',
    description = 'Khi khách chê đắt lần đầu (HANDLE_PRICE_OBJECTION_FIRST_TIME), trả lời tối đa 2 bong bóng, giữ nguyên gói đang giữ và chỉ nêu quyền lợi trong verified_package_benefits.',
    tactical_instructions = 'Khi khách phản đối giá lần 1 (ví dụ "Mà đắt quá", "Hơi đắt"): 1) Tối đa 2 bong bóng, giữ nguyên gói đang giữ và TUYỆT ĐỐI KHÔNG tự đề xuất đổi sang gói khác ở lần phản đối giá đầu tiên. 2) Chỉ sử dụng các quyền lợi có trong verified_package_benefits của <GROUNDED_TURN_FACTS> (như thời lượng, phạm vi chăm sóc, ăn nhẹ, loại phòng); chỉ nêu chính sách Tip khi có trong verified_package_benefits. 3) TUYỆT ĐỐI CẤM lặp lại câu mô tả quảng cáo hoặc câu mời trải nghiệm đã dùng ở các lượt trước.',
    updated_at = NOW()
WHERE code = 'spa_objection_value_trial';

UPDATE learned_rules
SET generalized_rule = '[PB-SPA-PRICE-OBJECTION-001 - Phản đối giá lần 1: Tối đa 2 bong bóng, giữ nguyên gói đang giữ, bám sát nguồn Catalog đã xác minh] NÊN LÀM: Trả lời tối đa 2 bong bóng; giữ nguyên gói đang tư vấn, không lặp lại con số giá đã báo; giải thích giá trị gói dựa hoàn toàn trên quyền lợi đã xác minh trong verified_package_benefits của Catalog/Policy đang ACTIVE; hỏi nhẹ nhàng ở cuối xem khách còn phân vân điểm nào hoặc muốn thử trải nghiệm 1 buổi lẻ trước không. TRÁNH: Không tự đề xuất đổi sang gói khác ở lần phản đối giá đầu tiên; không lặp lại câu quảng cáo chung chung của lượt trước; không gán nhầm quyền lợi ngoài Catalog.',
    do_actions = 'Trả lời tối đa 2 bong bóng; giữ nguyên gói đang giữ; chỉ nêu quyền lợi thực tế trong verified_package_benefits.',
    dont_actions = 'Không tự đề xuất đổi sang gói khác ở lần phản đối giá đầu tiên; không lặp lại câu mô tả quảng cáo của lượt trước; không lặp lại con số giá cũ.',
    updated_at = NOW()
WHERE rule_code = 'PB-SPA-PRICE-OBJECTION-001';
