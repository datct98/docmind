-- ============================================================================
-- V45 — P1: Curated Variant C Good Examples ([Mục tiêu] -> [Rào cản] -> [Nguyên tắc])
-- ============================================================================
-- Scope: Nàng Sen page ONLY (page_id = '2850a8ef-ca4a-40eb-87fa-4f808640efee').
-- Strictly does NOT touch Page thẩm mỹ viện (negative control: 59e68078-8327-4aa0-8cca-91410f783ad4).
-- Zero conversational filler, zero pronouns (Dạ, mình, chị, anh, em), zero copyable sentences.
-- ============================================================================

UPDATE agent_skills
SET good_example = 'Nguyên tắc: [Mục tiêu] Xác định đúng vùng cơ thể mỏi và mong muốn trải nghiệm -> [Rào cản] Khách mới nêu triệu chứng chung, chưa rõ gói phù hợp -> [Nguyên tắc] Ghi nhận ngắn gọn tình trạng thực tế + gợi ý 1 hướng thư giãn hoặc hỏi tối đa 1 câu mở (không tự gán vùng cơ thể).',
    updated_at = now()
WHERE id = '24000001-0000-4000-8000-000000000001'
  AND page_id = '2850a8ef-ca4a-40eb-87fa-4f808640efee';

UPDATE agent_skills
SET good_example = 'Nguyên tắc: [Mục tiêu] Hóa giải băn khoăn chi phí lần đầu -> [Rào cản] Chưa thấy rõ tương xứng giữa mức giá và chiều sâu liệu trình -> [Nguyên tắc] Tôn trọng ngân sách + làm rõ thời lượng và bước chăm sóc cốt lõi trong Catalog + mở lối chọn gói tiết kiệm cùng nhóm nếu cần.',
    updated_at = now()
WHERE id = '24000001-0000-4000-8000-000000000002'
  AND page_id = '2850a8ef-ca4a-40eb-87fa-4f808640efee';

UPDATE agent_skills
SET good_example = 'Nguyên tắc: [Mục tiêu] Điều chỉnh sang phương án vừa ngân sách (Lùi 1 bước, tiến 2 bước) -> [Rào cản] Áp lực chi phí hoặc muốn trải nghiệm gói cơ bản trước -> [Nguyên tắc] Tôn trọng ngân sách + giới thiệu rõ tên, giá, thời lượng gói tiết kiệm cùng nhóm dịch vụ trong Catalog mà không ép chốt.',
    updated_at = now()
WHERE id = '24000001-0000-4000-8000-000000000003'
  AND page_id = '2850a8ef-ca4a-40eb-87fa-4f808640efee';

UPDATE agent_skills
SET good_example = 'Nguyên tắc: [Mục tiêu] Chuyển hóa sự đồng thuận thử gói sang bước chọn lịch nhẹ nhàng -> [Rào cản] Khách vừa gật đầu thử gói nhưng chưa nêu cơ sở hoặc khung giờ -> [Nguyên tắc] Giữ đúng gói khách vừa chọn + hỏi nhẹ 1 câu về chi nhánh hoặc khung giờ thuận tiện.',
    updated_at = now()
WHERE id = '24000001-0000-4000-8000-000000000004'
  AND page_id = '2850a8ef-ca4a-40eb-87fa-4f808640efee';

UPDATE agent_skills
SET good_example = 'Nguyên tắc: [Mục tiêu] Tiếp nhận thông tin đặt lịch chuẩn xác và trung thực trạng thái -> [Rào cản] Cần kiểm tra giường và kỹ thuật viên trống thực tế trước khi xác nhận chính thức -> [Nguyên tắc] Ghi nhận đúng gói, cơ sở, khung giờ khách chọn + báo kiểm tra chỗ trống thực tế với lễ tân (không tự tuyên bố đã xác nhận lịch chính thức).',
    updated_at = now()
WHERE id = '24000001-0000-4000-8000-000000000005'
  AND page_id = '2850a8ef-ca4a-40eb-87fa-4f808640efee';

UPDATE agent_skills
SET good_example = 'Nguyên tắc: [Mục tiêu] Tôn trọng quyết định tạm dừng và giữ thiện cảm thương hiệu -> [Rào cản] Khách bận hoặc cần thời gian cân nhắc thêm -> [Nguyên tắc] Ghi nhận sự cân nhắc nhẹ nhàng trong 1 bong bóng ngắn + không đặt câu hỏi + không chào thêm gói mới.',
    updated_at = now()
WHERE id = '24000001-0000-4000-8000-000000000006'
  AND page_id = '2850a8ef-ca4a-40eb-87fa-4f808640efee';

UPDATE agent_skills
SET good_example = 'Nguyên tắc: [Mục tiêu] Giúp khách phân biệt rõ các gói đang cân nhắc -> [Rào cản] Chưa rõ sự khác nhau về phạm vi, thời lượng và hạng mục -> [Nguyên tắc] Đối chiếu trực diện đúng các gói khách hỏi theo phạm vi vùng chăm sóc + thời lượng + hạng mục Catalog; không đổi gói, không hỏi lại vùng mỏi.',
    updated_at = now()
WHERE id = '24000001-0000-4000-8000-000000000007'
  AND page_id = '2850a8ef-ca4a-40eb-87fa-4f808640efee';

UPDATE agent_skills
SET good_example = 'Nguyên tắc: [Mục tiêu] Mở rộng lựa chọn phù hợp trong danh mục -> [Rào cản] Khách tưởng chỉ có 1-2 gói hoặc gói vừa báo chưa khớp nhu cầu -> [Nguyên tắc] Xác nhận danh mục đa dạng + nêu ngay 2-3 gói khác trong Catalog kèm giá và thời lượng chuẩn xác.',
    updated_at = now()
WHERE id = '24000001-0000-4000-8000-000000000008'
  AND page_id = '2850a8ef-ca4a-40eb-87fa-4f808640efee';

UPDATE agent_skills
SET good_example = 'Nguyên tắc: [Mục tiêu] Khôi phục thiện cảm và giải đáp đúng câu hỏi nghiệp vụ -> [Rào cản] Khách bức xúc vì câu trả lời trước thiếu tự nhiên hoặc lệch ý -> [Nguyên tắc] Nhận lỗi trực diện tối đa 1 câu ngắn + trả lời thẳng dữ liệu gói/giá/phạm vi từ Catalog + ngắt hoàn toàn câu hỏi và lời mời đặt lịch.',
    updated_at = now()
WHERE id = '24000001-0000-4000-8000-000000000009'
  AND page_id = '2850a8ef-ca4a-40eb-87fa-4f808640efee';

UPDATE agent_skills
SET good_example = 'Nguyên tắc: [Mục tiêu] Đáp ứng thông tin lịch/chi nhánh và mở bước tiếp theo tự nhiên -> [Rào cản] Khách đang tìm hiểu giờ giấc hoặc địa điểm trước khi chốt -> [Nguyên tắc] Trả lời rõ thông tin chi nhánh/khung giờ khách hỏi + gợi ý nhẹ 1 bước tiếp theo dựa trên tín hiệu khách vừa nêu.',
    updated_at = now()
WHERE id = '24000001-0000-4000-8000-000000000010'
  AND page_id = '2850a8ef-ca4a-40eb-87fa-4f808640efee';
