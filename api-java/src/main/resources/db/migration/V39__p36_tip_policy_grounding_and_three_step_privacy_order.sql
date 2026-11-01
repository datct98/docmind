-- ============================================================================
-- V39: P3.6 Truth-Grounding & Privacy Sequence Polish
--      1. Eliminate inverted "giá đã gồm tip" hallucination on price objections
--         (Nàng Sen policy 04_chính_sách.html: tip is NOT included in price & optional).
--      2. Enforce strict sequential (1) -> (2) -> (3) two-bubble structure on
--         privacy concern turns ("khiếp chung phòng á ngại chết").
-- ============================================================================

-- 1. PB-SPA-PRICE-OBJECTION-002: Explicitly forbid unsolicited/false tip inclusion claims
UPDATE public.learned_rules
SET dont_actions = 'Tuyệt đối cấm tự ý hạ từ Massage Body toàn thân (399k) sang Gội đầu dưỡng sinh (239k) hoặc Lấy ráy tai (129k) khi khách chưa yêu cầu đổi nhóm dịch vụ; cấm chào ngược lên gói đắt hơn; không tự ý nhắc chuyện tiền tip khi khách không hỏi (theo chính sách nguồn: tiền tip chưa bao gồm trong giá dịch vụ và hoàn toàn tùy tâm — tuyệt đối cấm nói "giá đã gồm tip" hay "không nhận tip"); cấm chèo kéo khi khách đã yêu cầu dừng tư vấn.',
    updated_at = NOW()
WHERE id = '5bf4d0d9-b2bd-4279-afad-c0fb571ba164'
  AND rule_code = 'PB-SPA-PRICE-OBJECTION-002'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 2. PB-SPA-PRICE-OBJECTION-001: Also forbid unsolicited/false tip inclusion claims on first price objection
UPDATE public.learned_rules
SET dont_actions = 'Cấm biến câu cảm thán so sánh giá ("thôi chịu đấy, ở ngoài kia 50k/100k...") thành lời chào tạm biệt đóng phiên; cấm tự ý hạ chéo từ Massage Body (399k) sang Gội đầu (239k)/Lấy ráy tai (129k); cấm chào ngược lên gói cao tiền hơn khi khách đang chê đắt; không tự ý nhắc chuyện tiền tip khi khách không hỏi (chính sách nguồn ghi tiền tip chưa bao gồm trong giá dịch vụ — tuyệt đối cấm bịa "giá đã gồm tip" hay "không nhận tip"); cấm chê bai tiệm khác.',
    updated_at = NOW()
WHERE id = '9b81f560-4b23-43e8-bf73-88dc53a5ecb5'
  AND rule_code = 'PB-SPA-PRICE-OBJECTION-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 3. PB-SPA-PRICE-ANCHOR-001: Align tip policy constraint with 04_chính_sách.html
UPDATE public.learned_rules
SET dont_actions = 'Không mở đầu máy móc bằng "Dạ em hiểu rồi ạ"; không tự ý nhắc chuyện tiền tip khi khách không hỏi (tuyệt đối cấm nói "giá đã gồm tip" hay "không nhận Tip" trái chính sách nguồn); không ép chốt lịch cứng khi khách vừa nghe giá.',
    updated_at = NOW()
WHERE id = 'ed5b079b-022a-4b93-9435-e3f8d5d953a7'
  AND rule_code = 'PB-SPA-PRICE-ANCHOR-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 4. soft_economical_bridge_and_compare: Enforce strict 2-bubble (1) -> (2) -> (3) reading order
UPDATE public.agent_skills
SET tactical_instructions = '[Tình huống tâm lý khách] Khách hỏi đối chiếu quyền lợi các gói hoặc bày tỏ e ngại phòng chung ("khiếp chung phòng á ngại chết") -> [Nguyên tắc tư vấn chuẩn xác] (a) Khi khách ngại phòng chung ở gói tiêu chuẩn (như 399k/530k), KHÔNG vội ép khách nâng tiền lên gói VIP ngay mà BẮT BUỘC phản hồi theo đúng thứ tự tuần tự (1) -> (2) -> (3) tách thành 2 bong bóng (CẤM đưa mục (3) lên trước câu hỏi (2)): Bong bóng 1 nêu (1) điều kiện riêng tư thực tế của phòng tiêu chuẩn theo dữ liệu nguồn (không gian trầm yên tĩnh, kiểm soát lượng khách theo khung giờ, mỗi giường đều có rèm che riêng kín đáo) + (2) hỏi nhẹ xem mức độ riêng tư có rèm che riêng từng giường đó đã đủ thoải mái với khách chưa; Bong bóng 2 nêu (3) gói Massage phục hồi 100 phút giá 580k có Phòng VIP riêng (ghi rõ tên gói & giá 580k trong cùng câu) như một lựa chọn tham khảo thêm nếu khách thực sự muốn phòng đóng cửa riêng. (b) Giữ đúng ranh giới Catalog Grounding Lock cho mọi gói (239k/339k ăn nhẹ & trái cây; 399k/530k rèm riêng & buffet thực dưỡng; từ 580k mới có xông hơi & Phòng VIP riêng), ẩn sạch mã SKU nội bộ.',
    updated_at = NOW()
WHERE id = '24000001-0000-4000-8000-000000000003'
  AND code = 'soft_economical_bridge_and_compare'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';
