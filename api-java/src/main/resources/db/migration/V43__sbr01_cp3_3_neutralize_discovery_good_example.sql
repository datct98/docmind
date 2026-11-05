-- ============================================================================
-- V43 — SBR-01 Wave 1 (CP3.3): Neutralize "đi tập nặng về" example in discovery_empathy_and_scope
-- ============================================================================
-- Scope: Nàng Sen page ONLY (2850a8ef-ca4a-40eb-87fa-4f808640efee).
-- Replaces the context-specific "đi tập nặng về" example in skill
-- discovery_empathy_and_scope (24000001-0000-4000-8000-000000000001) with a
-- neutral relaxation example so the bot never projects unstated workout causes.
-- ============================================================================

UPDATE agent_skills
SET good_example = 'Dạ cơ thể đang căng mỏi, uể oải kéo dài chắc mình khó chịu lắm ạ. Để em gợi ý đúng liệu trình thư giãn vừa vặn nhất, hiện mình đang muốn tập trung chăm sóc vùng nào nhất hay muốn thả lỏng toàn thân ạ?',
    tactical_instructions = '[Tình huống tâm lý khách] Khách vừa chia sẻ cơ thể căng mỏi, làm việc nhiều, uể oải hoặc trằn trọc khó ngủ nhưng chưa nêu rõ hướng trải nghiệm ưu tiên (hoặc vừa tiếp nhận thông tin chung) -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Ghi nhận cảm giác mỏi mệt bằng ngôn từ dịu dàng, tự nhiên; khéo léo hỏi nhẹ mong muốn trải nghiệm của khách để chọn đúng liệu trình vừa vặn, TUYỆT ĐỐI KHÔNG tự giả định nguyên nhân (như tập nặng) hay gán sẵn vùng cơ thể khi khách chưa nêu (nếu khách đã nêu rõ toàn thân hoặc hỏi giá thì giới thiệu ngay); tuyệt đối không chèn lời khuyên đi khám bệnh viện lạnh lùng khi không có dấu hiệu cấp tính -> [Các hướng diễn đạt gợi cảm xúc theo ngữ cảnh] "Cơ thể đang căng mỏi, uể oải kéo dài chắc mình khó chịu lắm ạ", "Hơi ấm thảo dược dịu nhẹ kết hợp thả lỏng từng điểm cơ căng sẽ giúp người nhẹ nhõm hơn; hiện mình đang muốn tập trung chăm sóc vùng nào hay thả lỏng toàn thân ạ?"',
    updated_at = now()
WHERE id = '24000001-0000-4000-8000-000000000001'
  AND page_id = '2850a8ef-ca4a-40eb-87fa-4f808640efee';
