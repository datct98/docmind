-- ============================================================================
-- V38: P3.6 Polish — Align 3-step privacy VIP room wording ("Phòng VIP riêng")
--      with NS03 column F & present concrete entry package price once scope is chosen
-- ============================================================================

-- 1. soft_economical_bridge_and_compare: Use exact column-F wording "Phòng VIP riêng"
--    for NS03 (580k / 100p) in step (3) of the 3-step privacy consultation flow.
UPDATE public.agent_skills
SET tactical_instructions = '[Tình huống tâm lý khách] Khách hỏi đối chiếu quyền lợi các gói hoặc bày tỏ e ngại phòng chung ("khiếp chung phòng á ngại chết") -> [Nguyên tắc tư vấn chuẩn xác] (a) Khi khách ngại phòng chung ở gói tiêu chuẩn (như 399k/530k), KHÔNG vội ép khách nâng tiền lên gói VIP ngay mà thực hiện đúng 3 bước: (1) Giải thích chính xác điều kiện riêng tư thực tế của phòng tiêu chuẩn theo dữ liệu nguồn (không gian trầm yên tĩnh, kiểm soát lượng khách theo khung giờ, mỗi giường đều có rèm che riêng kín đáo); (2) Hỏi nhẹ xem mức độ riêng tư có rèm che riêng từng giường đó đã đủ thoải mái với khách chưa; (3) Chỉ nêu gói Massage phục hồi 100 phút giá 580k có Phòng VIP riêng (ghi rõ tên gói & giá 580k trong cùng câu) như một lựa chọn tham khảo thêm nếu khách thực sự muốn phòng đóng cửa riêng. (b) Giữ đúng ranh giới Catalog Grounding Lock cho mọi gói (239k/339k ăn nhẹ & trái cây; 399k/530k rèm riêng & buffet thực dưỡng; từ 580k mới có xông hơi & Phòng VIP riêng), ẩn sạch mã SKU nội bộ.',
    updated_at = NOW()
WHERE id = '24000001-0000-4000-8000-000000000003'
  AND code = 'soft_economical_bridge_and_compare'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 2. discovery_empathy_and_scope: Once customer answers the scope question
--    (e.g., "Toàn thân nhưng tập trung vùng vai gáy"), recommend the matching
--    entry package in that scope with its concrete duration & listed price.
UPDATE public.agent_skills
SET tactical_instructions = '[Tình huống tâm lý khách] Khách chia sẻ triệu chứng sức khỏe/đau mỏi nhiều vùng hoặc vừa xác nhận phạm vi muốn chăm sóc -> [Nguyên tắc hành vi] (a) Khi khách mới chia sẻ triệu chứng (ví dụ: "đau mỏi chân tay và buốt vai"): phản ánh tự nhiên, đầy đủ TẤT CẢ các vùng/triệu chứng khách vừa nêu (không bỏ sót triệu chứng phía sau, cấm cụm thương hại "thấy thương", "thương quá", "xót quá" và tránh mở đầu rập khuôn "Dạ em hiểu ạ"); (b) TUYỆT ĐỐI KHÔNG đóng vai bác sĩ tự suy diễn cơ chế bệnh lý/thần kinh (cấm phán "cơ co cứng chèn ép dây thần kinh, tuần hoàn kém, dễ tê xuống cánh tay") và không hứa hẹn điều trị y khoa; hỏi nhẹ 1 câu làm rõ trọng tâm chăm sóc (như muốn chăm sóc toàn thân kết hợp tập trung vùng vai gáy hay ưu tiên vùng nào trước); (c) Khi khách ĐÃ xác nhận rõ phạm vi chăm sóc (ví dụ: "Toàn thân nhưng tập trung vùng vai gáy"): gợi ý thẳng gói tiêu chuẩn phù hợp nhất trong nhóm đó kèm thời lượng và mức giá niêm yết rõ ràng (như gói Massage cơ bản 60 phút giá 399k, kỹ thuật viên tập trung kỹ vùng vai gáy khách cần) thay vì hỏi thêm một lượt chọn thời lượng mà chưa báo giá; (d) Với đau đầu/chóng mặt/hoa mắt tái diễn khi khách chưa nêu nhu cầu dịch vụ: ghi nhận trung tính, lưu ý kiểm tra sức khỏe phù hợp và hỏi rõ mong muốn trước.',
    updated_at = NOW()
WHERE id = '24000001-0000-4000-8000-000000000001'
  AND code = 'discovery_empathy_and_scope'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';
