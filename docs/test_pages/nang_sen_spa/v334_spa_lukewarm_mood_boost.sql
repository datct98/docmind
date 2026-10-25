-- DocMind v3.3.4 — micro-skill used by PrimaryActionRouter rule R12L-MID-FUNNEL-LUKEWARM-REENGAGE
-- Page: Nàng Sen Spa (b23cd382-4f7a-4829-8c54-1d410da14bf8). Idempotent.
INSERT INTO agent_skills (id, code, name, category, description, trigger_signals, tactical_instructions, good_example, anti_pattern, priority, active, page_id, confidence_score)
SELECT gen_random_uuid(), 'spa_lukewarm_mood_boost',
 'Khơi lại cảm xúc khi khách trả lời hời hợt giữa phễu (Lukewarm Re-engagement)',
 'PSYCHOLOGY',
 'Khi khách đã nghe tư vấn/báo giá nhưng trả lời cụt lủn (ừm, để xem đã, vậy à, ok) thì nhiệt độ mua đang nguội: khơi lại cảm xúc bằng 1 chi tiết đã xác minh của gói đang trao đổi, không ép chốt.',
 'ừm, ừ, ok, oke, vậy à, thế à, để xem đã, để chị xem, để tính sau, xem đã, cũng được (khi không có câu hỏi đang chờ)',
 '1) Giữ nguyên gói đang trao đổi, KHÔNG báo lại giá và KHÔNG gợi ý gói khác. 2) 1 câu ngắn tôn trọng việc khách cân nhắc. 3) Khơi gợi đúng 1 chi tiết giàu cảm xúc có trong quyền lợi đã xác minh của gói (cảm giác cơ thể được thả lỏng, không gian, quyền lợi đi kèm). 4) Kết bằng 1 câu hỏi mở nhẹ về điều khách còn băn khoăn. Tối đa 1 bong bóng, 1 dấu hỏi.',
 'Tham khảo giọng văn (KHÔNG chép nguyên văn): tôn trọng việc khách cân nhắc -> gợi 1 cảm giác dễ chịu cụ thể của gói đang trao đổi -> hỏi mở khách còn băn khoăn điều gì.',
 'CẤM trả lời cụt "Dạ vâng ạ"; CẤM lặp lại con số giá; CẤM ép chọn khung giờ; CẤM hỏi "mình có thể nói lại cho em hiểu"; CẤM bịa quyền lợi ngoài tài liệu.',
 97, true, 'b23cd382-4f7a-4829-8c54-1d410da14bf8', 0.95
WHERE NOT EXISTS (SELECT 1 FROM agent_skills WHERE code = 'spa_lukewarm_mood_boost' AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8');
