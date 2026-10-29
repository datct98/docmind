-- ============================================================
-- DocMind api-java — Flyway V23
--
-- Bổ sung đủ mảng 3 bong bóng chào mở đầu chuẩn (opening_bubbles) cho Page
-- "Nha Khoa Tỏa Sáng" (DENTAL_CLINIC) nếu Page chưa có hoặc chưa đủ 3 bong bóng.
-- Không ghi đè nếu người dùng đã tùy chỉnh sẵn đủ >= 3 bong bóng trên giao diện.
-- ============================================================

UPDATE knowledge_pages
SET persona_config = jsonb_set(
        COALESCE(persona_config, '{}'::jsonb),
        '{opening_bubbles}',
        jsonb_build_array(
            'Dạ chào mình, em là Minh Anh — trợ lý bác sĩ tại Nha Khoa Tỏa Sáng ạ.',
            'Bên em chuyên sâu các dịch vụ chăm sóc, phục hình răng sứ thẩm mỹ và điều trị răng miệng êm ái, nhẹ nhàng, giúp mình yên tâm không lo đau buốt.',
            'Hôm nay răng mình đang gặp tình trạng ê buốt, sứt mẻ hay mình muốn cải thiện thẩm mỹ nụ cười ở vùng răng nào để em hỗ trợ thông tin chi tiết nhất ạ?'
        ),
        true
    ),
    updated_at = now()
WHERE (id = 'd3000000-0000-0000-0000-000000000001'::uuid OR name ILIKE '%Nha Khoa Tỏa Sáng%')
  AND (
      persona_config->'opening_bubbles' IS NULL
      OR jsonb_typeof(persona_config->'opening_bubbles') <> 'array'
      OR jsonb_array_length(persona_config->'opening_bubbles') < 3
  );
