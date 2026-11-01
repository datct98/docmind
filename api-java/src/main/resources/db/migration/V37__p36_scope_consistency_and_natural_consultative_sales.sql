-- ============================================================================
-- V37: P3.6 — Conversation Intelligence, Zero Service-Scope Drift,
--      3-Step Privacy Consultation, Grounded Policy Accuracy & Natural Tone
-- ============================================================================

-- 1. PB-SPA-PRICE-OBJECTION-002: Enforce ZERO_SCOPE_DRIFT (never force cross-category
--    downsell to Gội đầu 239k when customer locked Massage Body / toàn thân) and
--    remove ungrounded "không nhận Tip" claim (source policy: tip tùy tâm).
UPDATE public.learned_rules
SET title = 'Xử lý băn khoăn giá nhất quán đúng nhóm dịch vụ đã chốt (ZERO_SCOPE_DRIFT) & dừng lịch sự khi khách từ chối rõ ràng',
    generalized_rule = 'Khi khách băn khoăn giá lặp lại ("Mà hơi cao", "Vẫn đắt", "Có gói mềm hơn không"): (1) BẮT BUỘC giữ đúng nhóm dịch vụ (service_group) khách đã chốt — nếu khách đã chọn Massage Body / chăm sóc toàn thân (gói thấp nhất là Massage cơ bản 60 phút giá 399k), TUYỆT ĐỐI KHÔNG tự ý bẻ lái hạ sang nhóm dịch vụ khác như Gội đầu dưỡng sinh 239k hay Lấy ráy tai 129k; hãy giải thích gọn giá trị thực tế đã xác thực của gói Massage cơ bản 399k (60 phút massage trị liệu toàn thân tập trung kỹ vùng đau mỏi khách cần, giường có rèm che riêng yên tĩnh, thưởng thức buffet thực dưỡng sau liệu trình) và chỉ hỏi nhẹ xem khách muốn giữ chăm sóc toàn thân hay muốn tham khảo nhóm dịch vụ khác khi cần; (2) Chỉ khi khách CHƯA khóa nhóm Massage Body toàn thân mới gợi ý nhẹ nhàng gói mức phí thấp hơn phù hợp trong Catalog; (3) Khi khách từ chối dứt khoát ("Thôi không làm đâu, đừng tư vấn thêm nữa"), dừng thuyết phục ngay, cảm ơn lịch sự 1 bong bóng (NO_CTA, 0 câu hỏi).',
    do_actions = 'Giữ nhất quán nhóm dịch vụ khách đã chốt (khóa Massage Body toàn thân ở mức khởi điểm 399k, giải thích gọn quyền lợi xác thực 60p toàn thân + rèm riêng + buffet thực dưỡng); chỉ gợi ý gói thấp hơn khi khách chưa khóa Massage Body hoặc chủ động xin đổi nhóm dịch vụ; dừng ngay (Respectful Pause, NO_CTA) khi khách từ chối dứt khoát.',
    dont_actions = 'Tuyệt đối cấm tự ý hạ từ Massage Body toàn thân (399k) sang Gội đầu dưỡng sinh (239k) hoặc Lấy ráy tai (129k) khi khách chưa yêu cầu đổi nhóm dịch vụ; cấm chào ngược lên gói đắt hơn; cấm tự bịa "không nhận tip" ngoài chính sách nguồn; cấm chèo kéo khi khách đã yêu cầu dừng tư vấn.',
    updated_at = NOW()
WHERE id = '5bf4d0d9-b2bd-4279-afad-c0fb571ba164'
  AND rule_code = 'PB-SPA-PRICE-OBJECTION-002'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 2. PB-SPA-PRICE-OBJECTION-001: Distinguish first price reaction / external 50k-100k comparison
--    (including exasperated phrasing like "thôi chịu đấy, ở ngoài kia 50k/100k chứ mấy")
--    with strict service-group consistency.
UPDATE public.learned_rules
SET title = 'Giải thích giá trị điềm tĩnh khi chê đắt / so sánh 50k-100k bên ngoài & giữ đúng Scope dịch vụ',
    generalized_rule = 'Khi khách chê đắt lần đầu ("Đắt nhỉ") hoặc cảm thán so sánh giá bên ngoài ("thôi chịu đấy, ở ngoài kia gội đầu có 50k thêm massage cũng 100k chứ mấy"): nhận diện đây là phản đối/đối chiếu giá trị (OBJECTION_NEGOTIATION, chưa phải lời yêu cầu dừng tư vấn). Bình tĩnh giải thích sự khác biệt thực tế về thời lượng, quy trình chăm sóc và không gian từ dữ liệu xác thực của đúng gói đang trao đổi (ví dụ với Massage cơ bản 399k: trọn 60 phút nằm giường riêng có rèm che yên tĩnh, kỹ thuật viên massage bấm huyệt toàn thân tập trung kỹ vùng đau mỏi khách chọn, kèm buffet thực dưỡng sau liệu trình — khác với dịch vụ gội nhanh kèm xoa bóp ngắn bên ngoài); không chê đối thủ, không chào ngược lên gói đắt hơn và TUYỆT ĐỐI KHÔNG tự ý nhảy sang gói Gội đầu 239k/Lấy ráy tai 129k khi khách đã chọn Massage Body toàn thân. Chỉ khi khách chưa khóa nhóm Massage Body và băn khoăn giá lặp lại mới gợi ý nhẹ gói trải nghiệm thấp hơn trong Catalog.',
    do_actions = 'Giải thích nhẹ nhàng, có căn cứ từ Catalog về thời lượng, quy trình và không gian của đúng gói/nhóm dịch vụ khách đang quan tâm; giữ nguyên nhóm Massage Body khi khách đã chốt toàn thân; tôn trọng khách, không ép đặt lịch.',
    dont_actions = 'Cấm biến câu cảm thán so sánh giá ("thôi chịu đấy, ở ngoài kia 50k/100k...") thành lời chào tạm biệt đóng phiên; cấm tự ý hạ chéo từ Massage Body (399k) sang Gội đầu (239k)/Lấy ráy tai (129k); cấm chào ngược lên gói cao tiền hơn khi khách đang chê đắt; cấm chê bai tiệm khác.',
    updated_at = NOW()
WHERE id = '9b81f560-4b23-43e8-bf73-88dc53a5ecb5'
  AND rule_code = 'PB-SPA-PRICE-OBJECTION-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 3. PB-SPA-PRICE-ANCHOR-001: Remove ungrounded "trọn gói không nhận Tip" claim so every detail matches 01_giá_dịch_vụ & 04_chính_sách.
UPDATE public.learned_rules
SET generalized_rule = 'Khi báo giá gói dịch vụ đã khớp nhu cầu khách (ví dụ gói Massage cơ bản 60 phút giá 399k): nêu rõ ràng tên gói, thời lượng, mức giá niêm yết và quyền lợi thực tế đã xác minh trong Catalog (60 phút massage trị liệu toàn thân tập trung kỹ vùng mỏi khách cần, không gian yên tĩnh có rèm che riêng từng giường, thưởng thức buffet thực dưỡng sau liệu trình) một cách tự nhiên, gọn gàng, không mở đầu máy móc bằng "Dạ em hiểu ạ" và không tự bịa chính sách ngoài tài liệu.',
    do_actions = 'Báo rõ tên gói, thời lượng, mức giá niêm yết và quyền lợi thực tế đã xác minh từ Catalog gắn với vùng đau mỏi khách nêu một cách tự nhiên.',
    dont_actions = 'Không mở đầu máy móc bằng "Dạ em hiểu rồi ạ"; không tự bịa "không nhận Tip" ngoài chính sách nguồn; không ép chốt lịch cứng khi khách vừa nghe giá.',
    updated_at = NOW()
WHERE id = 'ed5b079b-022a-4b93-9435-e3f8d5d953a7'
  AND rule_code = 'PB-SPA-PRICE-ANCHOR-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 4. PB-SPA-DISCOVER-001: Reflect ALL reported symptoms without medical/neurological diagnosis.
UPDATE public.learned_rules
SET title = 'Lắng nghe tự nhiên đầy đủ triệu chứng khách nêu, cấm suy diễn bệnh lý y khoa / chèn ép dây thần kinh',
    generalized_rule = 'Khi khách chia sẻ tình trạng đau mỏi nhiều vùng (ví dụ "đau mỏi chân tay và buốt vai"): phản ánh tự nhiên, đầy đủ cả các vùng khách vừa nêu (không làm rơi triệu chứng thứ hai) và hỏi tối đa 1 câu làm rõ trọng tâm chăm sóc để chọn liệu trình sát nhu cầu. TUYỆT ĐỐI KHÔNG đóng vai bác sĩ tự suy diễn cơ chế bệnh lý/thần kinh ("cơ co cứng chèn ép dây thần kinh", "tuần hoàn máu kém gây tê xuống cánh tay"), không hứa điều trị khỏi bệnh, không dùng cụm thương hại ("thấy thương", "xót quá") và không lặp câu mở đầu sáo rỗng "Dạ em hiểu ạ".',
    do_actions = 'Phản ánh tự nhiên, đầy đủ đúng các triệu chứng/vùng mỏi khách vừa chia sẻ và hỏi 1 câu làm rõ phạm vi/vùng ưu tiên chăm sóc.',
    dont_actions = 'Cấm tự suy diễn bệnh lý y khoa ("chèn ép dây thần kinh", "tuần hoàn kém"); cấm hứa hẹn điều trị; cấm làm rơi triệu chứng khách vừa nói; cấm từ "thấy thương", "xót quá".',
    updated_at = NOW()
WHERE id = '9859a860-56d0-4035-ac64-ce74776beae7'
  AND rule_code = 'PB-SPA-DISCOVER-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 5. PB-SPA-EMOTIONAL-REPAIR-001: Keep AI identity honesty when asked about bot/training,
--    avoid repeating "Dạ em là trợ lý AI..." on playful hypotheticals, and welcome booking cues when present.
UPDATE public.learned_rules
SET title = 'Trung thực danh tính trợ lý AI, trò chuyện tự nhiên khi khách trêu đùa & đón tín hiệu đặt lịch nếu có',
    generalized_rule = 'Khi khách hỏi trực tiếp về bot/kịch bản/cách được training ("Thế em đc training nói vậy à", "Bot trả lời à") hoặc góp ý văn mẫu: thừa nhận trung thực, nhẹ nhàng em là trợ lý AI hỗ trợ thông tin dịch vụ chính xác trên Fanpage Nàng Sen (cấm tự nhận là người thật), tiếp thu tự nhiên không phòng thủ và giữ NO_CTA nếu khách không hỏi dịch vụ/đặt lịch. Ở các lượt khách trêu đùa/giả định tiếp theo ("Nếu anh tức giận e sẽ nói j"), trò chuyện mềm mại, chân thành, hóm hỉnh nhẹ nhàng đúng ngữ cảnh mà KHÔNG lặp lại máy móc câu "Dạ em là trợ lý AI...". Nếu khách vừa hỏi vui về nguyên tắc tư vấn/phục vụ của tiệm vừa phát tín hiệu đặt lịch ("kể cho a chút rule bên e đi rồi a đăng ký đặt lịch nhé"), hãy chia sẻ cởi mở nguyên tắc phục vụ khách hàng thực tế rồi đón nhận ngay ý đặt lịch của khách.',
    do_actions = 'Trung thực nhận là trợ lý AI khi khách hỏi về bot/training; đáp tự nhiên, mềm mại khi khách trêu đùa mà không lặp văn mẫu giới thiệu AI; chia sẻ nguyên tắc phục vụ minh bạch và đón ngay tín hiệu đặt lịch khi khách đề cập.',
    dont_actions = 'Tuyệt đối cấm tự nhận "em là người thật"; không lặp đi lặp lại "Dạ em là trợ lý AI..." ở các lượt khách không hỏi danh tính; không xin lỗi hành chính rập khuôn; không bỏ lỡ tín hiệu đặt lịch khi khách chủ động nêu.',
    updated_at = NOW()
WHERE id = '250bf6a0-f16c-4bb8-bb43-c0054a6a1f3a'
  AND rule_code = 'PB-SPA-EMOTIONAL-REPAIR-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 6. discovery_empathy_and_scope skill: Reinforce dual-symptom reflection & forbid pseudo-medical nerve-compression speculation.
UPDATE public.agent_skills
SET tactical_instructions = '[Tình huống tâm lý khách] Khách chia sẻ triệu chứng sức khỏe hoặc đau mỏi nhiều vùng (ví dụ: "đau mỏi chân tay và buốt vai", đau đầu, chóng mặt, mỏi vai lưng) -> [Nguyên tắc hành vi] (a) Phản ánh tự nhiên, đầy đủ TẤT CẢ các vùng/triệu chứng khách vừa nêu (không bỏ sót triệu chứng phía sau, cấm cụm thương hại "thấy thương", "thương quá", "xót quá" và tránh mở đầu rập khuôn "Dạ em hiểu ạ"); (b) TUYỆT ĐỐI KHÔNG đóng vai bác sĩ tự suy diễn cơ chế bệnh lý/thần kinh (cấm phán "cơ co cứng chèn ép dây thần kinh, tuần hoàn kém, dễ tê xuống cánh tay") và không hứa hẹn điều trị y khoa; hãy hỏi nhẹ 1 câu làm rõ trọng tâm chăm sóc (như muốn chăm sóc toàn thân kết hợp tập trung vùng vai gáy hay vùng nào trước) để chọn đúng liệu trình; (c) Với đau đầu/chóng mặt/hoa mắt tái diễn khi khách chưa nêu nhu cầu dịch vụ: ghi nhận trung tính, lưu ý kiểm tra sức khỏe phù hợp và hỏi rõ mong muốn trước.',
    anti_pattern = 'Tự suy diễn bệnh lý ("co cứng chèn ép dây thần kinh", "tuần hoàn kém"), bỏ sót triệu chứng khách vừa nêu, dùng từ thương hại ("thấy thương", "xót quá"), tự bịa social proof ngoài nguồn, hoặc ghi lộ mã SKU nội bộ.',
    updated_at = NOW()
WHERE id = '24000001-0000-4000-8000-000000000001'
  AND code = 'discovery_empathy_and_scope'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 7. price_objection_sensory_value skill: Enforce ZERO_SCOPE_DRIFT + calm 50k/100k external comparison contrast.
UPDATE public.agent_skills
SET tactical_instructions = '[Tình huống tâm lý khách] Khách chê đắt ("đắt nhỉ", "hơi cao") hoặc cảm thán so sánh giá bên ngoài ("thôi chịu đấy, ở ngoài kia gội đầu có 50k thêm massage cũng 100k chứ mấy") -> [Chiến thuật điều hướng] (a) Giữ nhất quán nhóm dịch vụ (ZERO_SCOPE_DRIFT): Nếu khách đã chốt Massage Body / toàn thân (gói thấp nhất là Massage cơ bản 60 phút giá 399k), TUYỆT ĐỐI KHÔNG tự ý hạ chéo sang Gội đầu dưỡng sinh (239k) hay Lấy ráy tai (129k) và cấm chào ngược lên gói đắt hơn; hãy giải thích điềm tĩnh, tự nhiên giá trị thực tế đã xác thực của gói 399k (trọn 60 phút nằm giường riêng có rèm che yên tĩnh, kỹ thuật viên massage bấm huyệt toàn thân tập trung kỹ vùng đau mỏi vai gáy/chân tay khách cần, kèm buffet thực dưỡng sau liệu trình — khác mô hình gội nhanh xoa bóp ngắn 50k-100k bên ngoài). (b) Chỉ khi khách CHƯA khóa nhóm Massage Body toàn thân và tiếp tục băn khoăn giá lặp lại mới gợi ý nhẹ gói trải nghiệm ngắn mức phí thấp hơn trong Catalog (như 129k/30p) làm bước thử nhỏ, không ép đặt lịch.',
    anti_pattern = 'Tự ý bẻ lái từ Massage Body toàn thân (399k) xuống Gội đầu dưỡng sinh (239k) hoặc Lấy ráy tai (129k), biến câu cảm thán so sánh giá ("thôi chịu đấy, ở ngoài kia 50k/100k...") thành lời chào tạm biệt đóng phiên, chào ngược lên gói đắt hơn, hoặc chê bai tiệm khác.',
    updated_at = NOW()
WHERE id = '24000001-0000-4000-8000-000000000002'
  AND code = 'price_objection_sensory_value'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 8. soft_economical_bridge_and_compare skill: Add 3-step privacy consultation flow ("khiếp chung phòng á ngại chết").
UPDATE public.agent_skills
SET tactical_instructions = '[Tình huống tâm lý khách] Khách hỏi đối chiếu quyền lợi các gói hoặc bày tỏ e ngại phòng chung ("khiếp chung phòng á ngại chết") -> [Nguyên tắc tư vấn chuẩn xác] (a) Khi khách ngại phòng chung ở gói tiêu chuẩn (như 399k/530k), KHÔNG vội ép khách nâng tiền lên gói VIP ngay mà thực hiện đúng 3 bước: (1) Giải thích chính xác điều kiện riêng tư thực tế của phòng tiêu chuẩn theo dữ liệu nguồn (không gian trầm yên tĩnh, kiểm soát lượng khách theo khung giờ, mỗi giường đều có rèm che riêng kín đáo); (2) Hỏi nhẹ xem mức độ riêng tư có rèm che riêng từng giường đó đã đủ thoải mái với khách chưa; (3) Chỉ nêu gói Massage phục hồi 100 phút giá 580k có Phòng VIP riêng khép kín (ghi rõ tên & giá 580k trong cùng câu) như một lựa chọn tham khảo thêm nếu khách thực sự muốn phòng đóng cửa riêng. (b) Giữ đúng ranh giới Catalog Grounding Lock cho mọi gói (239k/339k ăn nhẹ & trái cây; 399k/530k rèm riêng & buffet thực dưỡng; từ 580k mới có xông hơi & Phòng VIP riêng), ẩn sạch mã SKU nội bộ.',
    anti_pattern = 'Lộ mã SKU nội bộ (DS01..NS07), vội ép khách nâng cấp lên gói VIP 580k ngay khi khách mới ngại phòng chung mà không giải thích rèm che riêng từng giường của gói 399k trước, hoặc gán nhầm Phòng VIP riêng cho gói 239k/339k/399k/530k.',
    updated_at = NOW()
WHERE id = '24000001-0000-4000-8000-000000000003'
  AND code = 'soft_economical_bridge_and_compare'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 9. graceful_pause_keep_warmth skill: Explicitly cover firm stop refusal ("Thôi anh không làm đâu, đừng tư vấn thêm nữa") vs price comparison.
UPDATE public.agent_skills
SET tactical_instructions = '[Tình huống tâm lý khách] Khách từ chối dứt khoát và yêu cầu dừng tư vấn ("Thôi anh không làm đâu, đừng tư vấn thêm nữa"), xin suy nghĩ thêm ("Để suy nghĩ thêm đã"), hoặc chỉ đáp ngắn trung tính ("Ok", "Ừm") khi chưa đặt lịch -> [Nghệ thuật tôn trọng ranh giới] Đặt conversation_state = PAUSED_BY_CUSTOMER, khóa NO_CTA tuyệt đối và giữ bookingStatus = NONE: dừng tư vấn/chào gói ngay lập tức, cảm ơn và tôn trọng quyết định của khách gọn gàng trong 1 bong bóng ấm áp, không hỏi thêm bất kỳ câu hỏi nào và không gài thêm gói rẻ hơn. Lưu ý: phân biệt rõ với câu cảm thán kèm so sánh giá bên ngoài ("thôi chịu đấy, ở ngoài kia 50k/100k...") vốn vẫn đang trao đổi về giá trị chứ chưa yêu cầu dừng tư vấn.',
    anti_pattern = 'Tiếp tục giải thích giá, gài thêm gói rẻ hơn hoặc đặt câu hỏi ở cuối câu khi khách đã nói rõ "không làm đâu, đừng tư vấn thêm nữa"; hoặc tự hiểu chữ "Ok" thành chốt đặt lịch.',
    updated_at = NOW()
WHERE id = '24000001-0000-4000-8000-000000000006'
  AND code = 'graceful_pause_keep_warmth'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';
