-- ============================================================================
-- V40: P3.7-B.2 — Skill/Rule Arbitration, Respectful De-escalation on
--      Consecutive Price Objections, Zero Competitor Speculation, and
--      Customer Pause/Acknowledgement Distinction
-- ============================================================================

-- 1. PB-SPA-DISCOVER-001: Natural symptom empathy & service direction orientation
--    without premature dual-package price/perk dumping or medical claims.
UPDATE public.learned_rules
SET title = 'Đồng cảm tự nhiên đầy đủ triệu chứng & định hướng chăm sóc (không báo dồn nhiều gói/quyền lợi phụ khi khách chưa hỏi)',
    generalized_rule = 'Khi khách mới chia sẻ triệu chứng đau mỏi/khó ngủ (ví dụ "Cổ vai gáy và nhức đầu khó ngủ", "đau mỏi chân tay và buốt vai") mà chưa hỏi gói hay giá cụ thể: (1) Đồng cảm tự nhiên, nhắc đầy đủ các biểu hiện khách vừa nêu mà không dùng văn mẫu ("Dạ em hiểu ạ", "thấy thương", "xót quá") và tuyệt đối không suy diễn bệnh lý thần kinh hay dùng cụm "massage điều trị" hoặc cam kết chữa khỏi nhức đầu/mất ngủ; (2) Giới thiệu gọn định hướng chăm sóc phù hợp (như liệu trình dưỡng sinh kết hợp gội đầu thảo dược và chăm sóc thư giãn vùng cổ vai gáy, hoặc chăm sóc toàn thân kết hợp vùng mỏi) rồi hỏi nhẹ 1 câu xem khách muốn trải nghiệm buổi vừa đủ hay dành thêm thời gian chăm sóc kỹ hơn (hoặc hỏi vùng ưu tiên). Không tự động xổ liền cả 2 gói giá kèm rèm che/không gian/ăn nhẹ khi khách chưa hỏi.',
    do_actions = 'Đồng cảm tự nhiên đầy đủ các triệu chứng khách nêu, giới thiệu định hướng liệu trình thư giãn phù hợp và hỏi 1 câu mở nhẹ nhàng về mong muốn trải nghiệm (buổi vừa đủ hay chăm sóc kỹ hơn / phạm vi ưu tiên).',
    dont_actions = 'Cấm dùng cụm "massage điều trị" hoặc cam kết cải thiện mất ngủ/nhức đầu; cấm suy diễn bệnh lý ("chèn ép dây thần kinh", "tuần hoàn kém"); cấm xổ liền 2 gói giá kèm rèm che/ăn nhẹ khi khách mới nêu triệu chứng; cấm hỏi ngược lại giờ giấc xuất hiện triệu chứng khi khách đã chọn phạm vi.',
    updated_at = NOW()
WHERE id = '9859a860-56d0-4035-ac64-ce74776beae7'
  AND rule_code = 'PB-SPA-DISCOVER-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 2. PB-SPA-HERBAL-001: Separate symptom-only orientation vs package/price recommendation,
--    and forbid re-asking symptom questions after customer selects "Cả 2" / scope.
UPDATE public.learned_rules
SET title = 'Tư vấn Gội Đầu Dưỡng Sinh & Chăm Sóc Cổ Vai Gáy theo đúng nhịp hội thoại',
    generalized_rule = 'Khi tư vấn nhóm Gội đầu dưỡng sinh & chăm sóc cổ vai gáy: (1) Nếu khách mới nêu triệu chứng ("Cổ vai gáy và nhức đầu khó ngủ") chưa hỏi giá, chỉ giới thiệu định hướng liệu trình dưỡng sinh kết hợp gội đầu thảo dược & chăm sóc thư giãn cổ vai gáy và hỏi khách muốn trải nghiệm buổi vừa đủ hay dành thêm thời gian chăm sóc kỹ hơn; (2) Khi khách đã xác nhận nhu cầu kết hợp ("Cả 2", muốn chăm sóc kỹ cả gội đầu và cổ vai gáy) hoặc hỏi gói cụ thể, đề xuất rõ gói phù hợp (như Combo dưỡng sinh 80 phút giá 339k kết hợp gội thảo dược và chăm sóc chuyên sâu cổ vai gáy, hoặc gói cơ bản 60 phút giá 239k) và câu hỏi cuối BẮT BUỘC hướng về thời lượng/trải nghiệm — tuyệt đối không quay lại hỏi giờ giấc triệu chứng ("nhức đầu buổi nào trong ngày") và không dùng cụm kết luận thay khách ("mình thấy hợp với tình trạng hiện tại") hay cụm "massage điều trị".',
    do_actions = 'Giới thiệu đúng gói dưỡng sinh phù hợp khi khách đã xác nhận phạm vi hoặc hỏi gói; đặt câu hỏi cuối hướng về thời lượng/trải nghiệm dịch vụ.',
    dont_actions = 'Cấm dùng cụm "massage điều trị"; cấm vừa đề xuất gói 339k ở bong bóng trước vừa quay lại hỏi triệu chứng ở bong bóng sau; cấm dùng câu áp đặt "mình thấy hợp với tình trạng hiện tại".',
    updated_at = NOW()
WHERE id = '59522533-6c67-4d25-a213-6366424a2729'
  AND rule_code = 'PB-SPA-HERBAL-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 3. PB-SPA-PRICE-OBJECTION-001: First price objection & external price comparison (50k-100k)
--    without speculating/disparaging outside shops or repeating secondary perks.
UPDATE public.learned_rules
SET title = 'Xử lý phản đối giá lần đầu & so sánh giá bên ngoài tôn trọng, không suy đoán tiệm khác, không lặp quyền lợi phụ',
    generalized_rule = 'Khi khách phản đối giá lần đầu ("Đắt thế", "Hơi cao") hoặc so sánh giá bên ngoài ("Bên ngoài gội đầu massage có 100k thôi"): (1) Với phản đối giá lần 1 trên gói cao (như 339k 80 phút): thừa nhận tự nhiên mức giá đó có thể hơi cao khi mới tìm hiểu và mở bước lùi hợp lý sang lựa chọn 60 phút (239k) trong cùng nhóm vẫn có gội thảo dược kết hợp chăm sóc thư giãn cổ vai gáy (nếu khách đã khóa Massage Body toàn thân thì giữ mức khởi điểm 399k của nhóm toàn thân, không tự ý hạ chéo nhóm); KHÔNG liệt kê lặp lại rèm che, không gian, ăn nhẹ trái cây nếu đã nói ở lượt trước; (2) Khi khách so sánh giá bên ngoài (50k-100k): TUYỆT ĐỐI CẤM hạ thấp hoặc tự suy đoán dịch vụ bên ngoài (cấm nói "bên ngoài 100k chủ yếu là gội và thư giãn nhanh" hay "gội nhanh xoa bóp ngắn"); hãy thừa nhận sự khác biệt mức giá, tôn trọng nếu khách ưu tiên chi phí và chỉ nêu ngắn gọn phạm vi đã xác thực của liệu trình 60 phút bên mình để khách tự cân nhắc.',
    do_actions = 'Thừa nhận cảm nhận về giá một cách tự nhiên; mở bước lùi hợp lý về gói 60 phút cùng nhóm dịch vụ mà không lặp lại rèm che/trái cây; khi khách so sánh giá 100k bên ngoài thì tôn trọng sự khác biệt mức giá và chỉ nêu ngắn gọn phạm vi xác thực của gói bên mình.',
    dont_actions = 'Cấm tuyệt đối suy đoán hoặc hạ thấp dịch vụ bên ngoài ("bên ngoài chủ yếu là gội nhanh/thư giãn nhanh"); cấm dùng văn mẫu "Mình thấy... cũng dễ hiểu" hay "dễ chịu/nhẹ nhàng hơn với mình lúc này ạ?"; cấm lặp lại rèm che, trái cây qua nhiều lượt; cấm tự ý hạ chéo từ Massage Body toàn thân sang Gội đầu.',
    updated_at = NOW()
WHERE id = '9b81f560-4b23-43e8-bf73-88dc53a5ecb5'
  AND rule_code = 'PB-SPA-PRICE-OBJECTION-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 4. PB-SPA-PRICE-OBJECTION-002: Second consecutive price objection -> RESPECTFUL_DEESCALATION
--    (never pitch 5-session 10% bundle, no trailing question required).
UPDATE public.learned_rules
SET title = 'Phản đối giá liên tiếp lần 2 (RESPECTFUL_DEESCALATION): Lùi lại tôn trọng, cấm gài mua combo 5 buổi, không ép câu hỏi cuối',
    generalized_rule = 'Khi khách phản đối giá lần 2 liên tiếp ("Thấy đắt quá", "Vẫn đắt") sau khi đã được giới thiệu lựa chọn tiết kiệm hơn (như gói 239k) hoặc giải thích giá trị gói khởi điểm: (1) Nhận biết mức giá hiện tại vẫn chưa thuyết phục được khách và chuyển sang RESPECTFUL_DEESCALATION — lùi lại tôn trọng để khách có khoảng trống cân nhắc thoải mái; (2) CẤM TUYỆT ĐỐI việc chào mua gói từ 5 buổi giảm 10% hay nâng lên gói đắt hơn khi khách đang chê đắt 1 buổi lẻ; (3) Không lặp lại danh sách quyền lợi phụ (rèm che, trái cây) và KHÔNG đặt câu hỏi ép chọn ở cuối lượt (0 câu hỏi, NO_CTA). (Lưu ý: nếu khách mới hỏi rõ "Có gói nào mềm hơn không?" ở nhóm Massage Body toàn thân thì giữ đúng ZERO_SCOPE_DRIFT giải thích gói 399k là mức khởi điểm toàn thân).',
    do_actions = 'Nhận diện khách chưa thấy phù hợp về chi phí sau khi đã hạ bậc gói; phản hồi 1–2 bong bóng ngắn gọn, chân thành, tôn trọng ngân sách của khách và để khách thong thả cân nhắc (0 câu hỏi ở cuối lượt khi khách chê đắt liên tiếp).',
    dont_actions = 'Cấm tuyệt đối gài ưu đãi "mua từ 5 buổi giảm 10%" khi khách đang chê đắt; cấm đặt câu hỏi ép chọn ("mình thấy hướng nào nhẹ nhàng hơn ạ?") ở lượt phản đối giá liên tiếp; cấm lặp lại rèm che, ăn nhẹ trái cây.',
    updated_at = NOW()
WHERE id = '5bf4d0d9-b2bd-4279-afad-c0fb571ba164'
  AND rule_code = 'PB-SPA-PRICE-OBJECTION-002'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 5. PB-SPA-MID-FUNNEL-LUKEWARM-001: Short acknowledgement ("K", "Ok", "Ừ") -> CUSTOMER_PAUSE_OR_ACKNOWLEDGEMENT
UPDATE public.learned_rules
SET title = 'Xác nhận ngắn ("K", "Ok", "Ừ", "Để xem đã") -> CUSTOMER_PAUSE_OR_ACKNOWLEDGEMENT ấm áp, 0 câu hỏi',
    generalized_rule = 'Khi khách nhắn tin xác nhận ngắn hoặc tạm dừng ("K", "Ok", "Ừ", "Ừm", "Để xem đã"): hiểu đúng đây là CUSTOMER_PAUSE_OR_ACKNOWLEDGEMENT (khách ghi nhận thông tin và tạm dừng cân nhắc, KHÔNG mặc định là CUSTOMER_EXIT đóng cửa vĩnh viễn và KHÔNG tự hiểu là chốt lịch). Phản hồi 1 bong bóng ngắn gọn, ấm áp, tự nhiên (ví dụ: "Dạ vâng ạ, mình cứ tham khảo thoải mái nhé. Khi nào cần em vẫn ở đây hỗ trợ mình ạ."), giữ 0 câu hỏi và 0 cố bán thêm.',
    do_actions = 'Phản hồi 1 bong bóng ngắn gọn, ấm áp, để mở lời hẹn hỗ trợ khi khách cần ("Dạ vâng ạ, mình cứ tham khảo thoải mái nhé. Khi nào cần em vẫn ở đây hỗ trợ mình ạ."), giữ 0 câu hỏi và bookingStatus = NONE.',
    dont_actions = 'Không viết lời chào tạm biệt đóng phiên lạnh lùng kiểu kết thúc hội thoại; không hỏi thêm câu hỏi; không cố chào thêm gói dịch vụ hay hỏi giờ đặt lịch.',
    updated_at = NOW()
WHERE id = '4665ae9e-a7ff-4e83-b679-6bfe4456df30'
  AND rule_code = 'PB-SPA-MID-FUNNEL-LUKEWARM-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 6. agent_skills: Update discovery_empathy_and_scope (Nàng Sen)
UPDATE public.agent_skills
SET tactical_instructions = '[Tình huống tâm lý khách] Khách mới chia sẻ triệu chứng đau mỏi/khó ngủ -> [Nguyên tắc hành vi] (a) Khi khách chỉ mới nêu triệu chứng (như "Cổ vai gáy và nhức đầu khó ngủ", "đau mỏi chân tay và buốt vai") mà chưa hỏi giá: đồng cảm tự nhiên, nhắc đủ các triệu chứng khách nêu, giới thiệu định hướng liệu trình thư giãn phù hợp và hỏi nhẹ 1 câu xem khách muốn trải nghiệm buổi vừa đủ hay dành thêm thời gian chăm sóc kỹ hơn (hoặc hỏi vùng ưu tiên) — KHÔNG tự báo luôn cả 2 gói giá kèm rèm che/ăn nhẹ khi khách chưa hỏi; (b) TUYỆT ĐỐI KHÔNG dùng cụm "massage điều trị", không cam kết cải thiện mất ngủ/nhức đầu, không suy diễn bệnh lý thần kinh ("chèn ép dây thần kinh", "tuần hoàn kém") và không dùng từ "thấy thương", "xót quá"; (c) Khi khách ĐÃ chọn phạm vi ("Cả 2", "Toàn thân tập trung vai gáy"): đề xuất gói phù hợp và đặt câu hỏi cuối về thời lượng/trải nghiệm, TUYỆT ĐỐI KHÔNG quay lại hỏi triệu chứng xuất hiện buổi nào.',
    anti_pattern = 'Dùng cụm "massage điều trị", cam kết cải thiện mất ngủ/nhức đầu, xổ liền 2 gói giá kèm rèm che khi khách mới kể triệu chứng, hoặc quay lại hỏi triệu chứng sau khi đã đề xuất gói.',
    updated_at = NOW()
WHERE id = '24000001-0000-4000-8000-000000000001'
  AND code = 'discovery_empathy_and_scope'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 7. agent_skills: Update price_objection_sensory_value (Nàng Sen) — remove outside 50k/100k speculation
UPDATE public.agent_skills
SET tactical_instructions = '[Tình huống tâm lý khách] Khách phản đối giá lần đầu ("Đắt thế", "Hơi cao") hoặc so sánh giá bên ngoài ("Bên ngoài gội đầu massage có 100k thôi") -> [Chiến thuật điều hướng] (a) Phản đối giá lần 1 trên gói cao (như 339k): thừa nhận mức 339k có thể hơi cao nếu mới tìm hiểu và mở bước lùi hợp lý sang gói 60 phút (239k) trong cùng nhóm vẫn có gội thảo dược và chăm sóc thư giãn cổ vai gáy (nếu khách đã chốt Massage Body toàn thân thì giữ mức 399k toàn thân, không hạ chéo sang gội đầu); KHÔNG liệt kê lặp lại rèm che, không gian, trái cây đã nói ở lượt trước; (b) Khi khách so sánh giá 50k-100k bên ngoài: CẤM TUYỆT ĐỐI suy đoán hoặc hạ thấp dịch vụ bên ngoài (không nói "bên ngoài 50k/100k chủ yếu là gội nhanh / thư giãn nhanh"); thừa nhận sự khác biệt mức giá, tôn trọng nếu khách ưu tiên chi phí và chỉ nêu ngắn gọn phạm vi đã xác thực của gói 60 phút bên mình để khách tự cân nhắc.',
    good_example = 'Dạ mỗi nơi sẽ có thiết kế dịch vụ và mức chi phí khác nhau, em hoàn toàn tôn trọng nếu mình đang ưu tiên mức giá tiết kiệm ạ. Ở bên em thì gói dưỡng sinh 60 phút (239k) sẽ bao gồm trọn quy trình gội đầu thảo dược kết hợp chăm sóc thư giãn vùng đầu và cổ vai gáy, mình cứ thoải mái cân nhắc theo nhu cầu của mình nhé ạ.',
    anti_pattern = 'Suy đoán hoặc hạ thấp tiệm bên ngoài ("bên ngoài 100k chủ yếu là gội và thư giãn nhanh"), lặp lại rèm che/ăn nhẹ trái cây qua nhiều lượt, dùng câu mẫu "Mình thấy... cũng dễ hiểu" hay "dễ chịu/nhẹ nhàng hơn với mình lúc này ạ?", hoặc gài mua combo 5 buổi khi khách chê đắt.',
    updated_at = NOW()
WHERE id = '24000001-0000-4000-8000-000000000002'
  AND code = 'price_objection_sensory_value'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 8. agent_skills: Update soft_economical_bridge_and_compare (Nàng Sen) — respectful de-escalation on repeat price objection
UPDATE public.agent_skills
SET tactical_instructions = '[Tình huống tâm lý khách] Khách phản đối giá lần 2 liên tiếp ("Thấy đắt quá") hoặc hỏi đối chiếu quyền lợi / e ngại phòng chung -> [Nguyên tắc tư vấn chuẩn xác] (a) Khi khách phản đối giá lần 2 liên tiếp sau khi đã nghe gói 239k (hoặc gói khởi điểm): chuyển sang RESPECTFUL_DEESCALATION — nhận biết mức giá này vẫn chưa thuyết phục được khách, lùi lại tôn trọng để khách có khoảng trống cân nhắc, CẤM chào mua 5 buổi giảm 10%, CẤM lặp lại rèm che/trái cây và KHÔNG đặt câu hỏi ở cuối lượt (0 câu hỏi); (b) Khi khách ngại phòng chung ở gói tiêu chuẩn (như 399k/530k), thực hiện tuần tự: Bong bóng 1 giải thích không gian trầm tĩnh, mỗi giường đều có rèm che riêng kín đáo và hỏi nhẹ mức độ riêng tư đó đã đủ thoải mái chưa; Bong bóng 2 nêu thêm gói Massage phục hồi 100 phút (580k) có Phòng VIP riêng nếu khách muốn phòng khép kín.',
    anti_pattern = 'Đề xuất mua 5 buổi giảm 10% khi khách đang chê đắt, đặt câu hỏi ép chọn ở lượt phản đối giá liên tiếp lần 2, lộ mã SKU nội bộ, hoặc gán nhầm Phòng VIP riêng cho gói tiêu chuẩn.',
    updated_at = NOW()
WHERE id = '24000001-0000-4000-8000-000000000003'
  AND code = 'soft_economical_bridge_and_compare'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 9. agent_skills: Update graceful_pause_keep_warmth (Nàng Sen) — distinguish CUSTOMER_PAUSE_OR_ACKNOWLEDGEMENT ("K") vs CUSTOMER_EXIT
UPDATE public.agent_skills
SET tactical_instructions = '[Tình huống tâm lý khách] Khách nhắn xác nhận ngắn/tạm dừng ("K", "Ok", "Ừ", "Để xem đã") hoặc từ chối rõ ("Thôi không làm đâu") -> [Nghệ thuật tôn trọng ranh giới] (a) Với tin nhắn xác nhận ngắn ("K", "Ok", "Ừ", "Để xem đã" — CUSTOMER_PAUSE_OR_ACKNOWLEDGEMENT): phản hồi 1 bong bóng ngắn gọn, ấm áp ("Dạ vâng ạ, mình cứ tham khảo thoải mái nhé. Khi nào cần em vẫn ở đây hỗ trợ mình ạ."), giữ 0 câu hỏi, 0 cố bán thêm, không chào tạm biệt lạnh lùng kiểu đóng sập hội thoại; (b) Với từ chối dứt khoát ("Thôi anh không làm đâu, đừng tư vấn thêm nữa" — CUSTOMER_EXIT): dừng tư vấn ngay, cảm ơn lịch sự trong 1 bong bóng, 0 câu hỏi.',
    good_example = 'Dạ vâng ạ, mình cứ tham khảo thoải mái nhé. Khi nào cần em vẫn ở đây hỗ trợ mình ạ.',
    anti_pattern = 'Biến tin nhắn "K" thành lời chào tạm biệt xa cách ("cảm ơn mình đã dành thời gian trò chuyện"), đặt thêm câu hỏi hoặc cố gài thêm gói rẻ hơn.',
    updated_at = NOW()
WHERE id = '24000001-0000-4000-8000-000000000006'
  AND code = 'graceful_pause_keep_warmth'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';
