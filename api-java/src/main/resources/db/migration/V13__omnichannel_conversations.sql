-- ============================================================
-- DocMind api-java — Flyway V13: Omnichannel Live Conversation Inbox
--
-- Lưu trữ tập trung toàn bộ các phiên hội thoại giữa:
--   1. CUSTOMER   (Khách hàng nhắn từ Messenger / Simulator)
--   2. AI_BOT     (AI tự động trả lời ở Hot Path)
--   3. HUMAN_SALE (Nhân viên Sale tiếp quản tư vấn & làm nguồn mẫu cho Cold Path)
-- ============================================================

CREATE TABLE IF NOT EXISTS conversation_sessions (
    id                      UUID PRIMARY KEY,
    page_id                 UUID REFERENCES knowledge_pages (id) ON DELETE CASCADE,
    customer_psid           VARCHAR(128) NOT NULL,
    customer_name           VARCHAR(255) NOT NULL DEFAULT 'Khách hàng Messenger',
    honorific               VARCHAR(32) NOT NULL DEFAULT 'Chị',
    active_topic            TEXT NOT NULL DEFAULT 'Tư vấn dịch vụ chuẩn Y khoa',
    topic_family            VARCHAR(64) NOT NULL DEFAULT 'GENERAL',
    control_mode            VARCHAR(32) NOT NULL DEFAULT 'BOT_ACTIVE',
    handoff_category        VARCHAR(64) NULL,
    handoff_reason          TEXT NULL,
    captured_phone          VARCHAR(32) NULL,
    preferred_time          VARCHAR(128) NULL,
    buying_temperature      INT NOT NULL DEFAULT 42,
    previous_temperature    INT NULL,
    temperature_delta       INT NOT NULL DEFAULT 0,
    temperature_label       VARCHAR(64) NOT NULL DEFAULT 'WARM',
    momentum                VARCHAR(64) NOT NULL DEFAULT 'STABLE',
    buyer_archetype         VARCHAR(64) NOT NULL DEFAULT 'EXPLORING_CONSULTATION',
    archetype_label         VARCHAR(128) NOT NULL DEFAULT 'Đang tìm hiểu giải pháp',
    extracted_pains         TEXT NULL,
    hidden_concerns         TEXT NULL,
    recommended_strategy    TEXT NULL,
    sales_stage             VARCHAR(64) NOT NULL DEFAULT 'DISCOVERY',
    has_human_sale_reply    BOOLEAN NOT NULL DEFAULT FALSE,
    learned_in_cold_path    BOOLEAN NOT NULL DEFAULT FALSE,
    last_message_preview    TEXT NOT NULL DEFAULT '',
    last_sender_role        VARCHAR(32) NOT NULL DEFAULT 'CUSTOMER',
    last_message_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_conv_sessions_page_last_msg
    ON conversation_sessions (page_id, last_message_at DESC);

CREATE INDEX IF NOT EXISTS idx_conv_sessions_psid
    ON conversation_sessions (customer_psid);

CREATE INDEX IF NOT EXISTS idx_conv_sessions_control_mode
    ON conversation_sessions (control_mode);

CREATE TABLE IF NOT EXISTS conversation_messages (
    id              UUID PRIMARY KEY,
    session_id      UUID NOT NULL REFERENCES conversation_sessions (id) ON DELETE CASCADE,
    sender_role     VARCHAR(32) NOT NULL,
    content         TEXT NOT NULL,
    external_mid    VARCHAR(128) NULL,
    metadata_json   TEXT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_conv_messages_session_created
    ON conversation_messages (session_id, created_at ASC);

-- Seed 3 cuộc hội thoại thực tế mẫu để Quản lý & Sale kiểm thử ngay khi mở Inbox
INSERT INTO conversation_sessions (
    id, page_id, customer_psid, customer_name, honorific, active_topic, topic_family,
    control_mode, handoff_category, handoff_reason, captured_phone, preferred_time,
    buying_temperature, previous_temperature, temperature_delta, temperature_label, momentum,
    buyer_archetype, archetype_label, extracted_pains, hidden_concerns, recommended_strategy,
    sales_stage, has_human_sale_reply, learned_in_cold_path, last_message_preview, last_sender_role,
    last_message_at, created_at, updated_at
) VALUES
(
    'c1000000-0000-4000-a000-000000000001',
    (SELECT id FROM knowledge_pages ORDER BY created_at ASC LIMIT 1),
    'psid-fb-884120',
    'Anh Hoàng Minh',
    'Anh',
    'Trị mụn & Mờ thâm chuyên sâu (mờ thâm, trị mụn lưng)',
    'ACNE_THAM',
    'NEEDS_HANDOFF',
    'BOOKING_LEAD_CAPTURED',
    'Khách Nam để lại SĐT 0968221644 đặt lịch trị mụn lưng & mờ thâm — Cần Sale gọi xác nhận phòng riêng 1-1.',
    '0968221644',
    'Chiều mai 15h',
    86, 68, 18, 'HOT', 'RISING',
    'DISCREET_DECISIVE', 'Khách Nam kín đáo & quyết đoán',
    'Mụn viêm vùng lưng kèm thâm lâu năm',
    'Ngại đông người, cần không gian trị liệu riêng tư kín đáo 1-1 cho Nam giới',
    'Xác nhận ngay phòng VIP riêng 1-1 cho Nam giới và chốt giờ hẹn chiều mai 15h qua Zalo/SĐT.',
    'SOFT_BOOKING_CLOSE',
    FALSE, FALSE,
    '0968221644 chiều mai 15h anh ghé nhé, giữ phòng riêng giúp anh',
    'CUSTOMER',
    now() - interval '3 minutes',
    now() - interval '15 minutes',
    now() - interval '3 minutes'
),
(
    'c1000000-0000-4000-a000-000000000002',
    (SELECT id FROM knowledge_pages ORDER BY created_at ASC LIMIT 1),
    'psid-fb-992315',
    'Chị Thanh Thảo',
    'Chị',
    'Trị nám, tàn nhang & sắc tố Laser Pico',
    'MELASMA_PICO',
    'CONVERTED',
    'BOOKING_LEAD_CAPTURED',
    'Sale đã tiếp quản xử lý chê đắt và chốt thành công lịch hẹn soi da AI 3D SĐT 0912345678.',
    '0912345678',
    'Sáng thứ 7 (09h30)',
    92, 54, 38, 'HOT', 'RECOVERING',
    'PRICE_CONSCIOUS', 'Khách kỹ tính so sánh giá & bảo hành',
    'Nám chân sâu hai bên gò má sau sinh 3 năm',
    'Sợ bắn laser bị mỏng da, tăng sắc tố hoặc phát sinh chi phí ẩn',
    'Nhấn mạnh cam kết bảo hành văn bản 12 tháng + Bác sĩ CKI trực tiếp thực hiện + 0 phụ phí.',
    'SOFT_BOOKING_CLOSE',
    TRUE, FALSE,
    'Dạ em đã giữ lịch 09h30 sáng thứ 7 cùng Bác sĩ CKI cho Chị Thảo qua SĐT 0912345678 rồi ạ!',
    'HUMAN_SALE',
    now() - interval '18 minutes',
    now() - interval '45 minutes',
    now() - interval '18 minutes'
),
(
    'c1000000-0000-4000-a000-000000000003',
    (SELECT id FROM knowledge_pages ORDER BY created_at ASC LIMIT 1),
    'psid-fb-773109',
    'Chị Ngọc Mai',
    'Chị',
    'Trị mụn & Mờ thâm chuyên sâu (mụn ẩn, mờ thâm)',
    'ACNE_THAM',
    'BOT_ACTIVE',
    NULL, NULL, NULL, NULL,
    58, 42, 16, 'WARM', 'RISING',
    'SKEPTICAL_CHRONIC', 'Khách bị mụn lâu năm cần sự thấu cảm',
    'Mụn ẩn dưới cằm tái đi tái lại và vết thâm đỏ',
    'Lo lắng lấy nhân mụn bị sưng đỏ không đi làm được hôm sau',
    'Giải thích kỹ thuật lấy nhân chuẩn Y khoa êm dịu không sưng nề + mời qua soi da AI 3D miễn phí.',
    'VALUE_CONSULTATION',
    FALSE, FALSE,
    'Làm xong hôm sau có bị sưng đỏ không em, chị còn phải đi làm gặp khách hàng',
    'CUSTOMER',
    now() - interval '1 minute',
    now() - interval '10 minutes',
    now() - interval '1 minute'
) ON CONFLICT (id) DO NOTHING;

INSERT INTO conversation_messages (id, session_id, sender_role, content, created_at) VALUES
(
    'b1000000-0000-4000-a000-000000000101',
    'c1000000-0000-4000-a000-000000000001',
    'CUSTOMER',
    'Giá',
    now() - interval '14 minutes'
),
(
    'b1000000-0000-4000-a000-000000000102',
    'c1000000-0000-4000-a000-000000000001',
    'AI_BOT',
    'Dạ các phác đồ chăm sóc và điều trị da chuẩn Y khoa bên em hiện có mức chi phí rất linh hoạt chỉ từ 650.000đ/buổi tùy theo tình trạng nền da (đã bao gồm Bác sĩ Chuyên khoa I trực tiếp soi da AI 3D miễn phí 100%, cam kết trọn gói không phát sinh phụ phí ạ).' || E'\n\n' || 'Hiện tại Chị đang muốn ưu tiên xử lý dứt điểm mụn ẩn/mụn viêm, làm mờ sạm nám tàn nhang hay dưỡng căng bóng trẻ hóa để em báo đúng gói hợp nhất cho mình ạ?',
    now() - interval '13 minutes'
),
(
    'b1000000-0000-4000-a000-000000000103',
    'c1000000-0000-4000-a000-000000000001',
    'CUSTOMER',
    'Mờ thâm và trị mụn lưng',
    now() - interval '9 minutes'
),
(
    'b1000000-0000-4000-a000-000000000104',
    'c1000000-0000-4000-a000-000000000001',
    'AI_BOT',
    'Dạ với tình trạng mụn vùng lưng kèm vết thâm, Liệu trình Trị Mụn Chuẩn Y Khoa bên em (1.500.000đ/buổi, tặng kèm 01 tuýp chấm mụn kháng viêm, cam kết 0 phụ phí) sẽ giúp tiêu viêm sâu và làm sáng vết thâm vùng lưng rất hiệu quả ạ.' || E'\n\n' || 'Vì nền da vùng lưng có lớp sừng dày hơn da mặt, Chị tiện ghé cơ sở vào khung Sáng hay Chiều để Bác sĩ soi da AI 3D miễn phí 100% và lên phác đồ xử lý dứt điểm ạ?',
    now() - interval '8 minutes'
),
(
    'b1000000-0000-4000-a000-000000000105',
    'c1000000-0000-4000-a000-000000000001',
    'CUSTOMER',
    'nhưng tôi là nam',
    now() - interval '6 minutes'
),
(
    'b1000000-0000-4000-a000-000000000106',
    'c1000000-0000-4000-a000-000000000001',
    'AI_BOT',
    'Dạ các phác đồ trị mụn lưng và mờ thâm chuẩn Y khoa bên em hoàn toàn phù hợp và hiệu quả cao với Anh Nam giới ạ! Bên em có bố trí khu vực phòng trị liệu riêng tư kín đáo 1-1 nên Anh hoàn toàn thoải mái khi qua điều trị ạ.' || E'\n\n' || 'Anh tiện ghé cơ sở vào khung giờ Sáng hay Chiều để Bác sĩ Chuyên khoa I trực tiếp soi da AI 3D miễn phí 100% và giữ phòng riêng trước cho mình ạ?',
    now() - interval '5 minutes'
),
(
    'b1000000-0000-4000-a000-000000000107',
    'c1000000-0000-4000-a000-000000000001',
    'CUSTOMER',
    '0968221644 chiều mai 15h anh ghé nhé, giữ phòng riêng giúp anh',
    now() - interval '3 minutes'
),
(
    'b1000000-0000-4000-a000-000000000201',
    'c1000000-0000-4000-a000-000000000002',
    'CUSTOMER',
    'Gói trị nám Laser Pico bên shop giá 4tr5 1 buổi hơi cao nhỉ, chỗ khác báo chị có 2 triệu thôi',
    now() - interval '40 minutes'
),
(
    'b1000000-0000-4000-a000-000000000202',
    'c1000000-0000-4000-a000-000000000002',
    'HUMAN_SALE',
    'Dạ em rất hiểu băn khoăn của Chị Thảo ạ! Với nám chân sâu, bước sóng Laser Pico chuẩn Y khoa do trực tiếp Bác sĩ Chuyên khoa I thực hiện sẽ phá vỡ hắc sắc tố melanin ở tầng hạ bì mà hoàn toàn không làm bào mòn hay bong tróc da như laser thế hệ cũ, đặc biệt bên em ký cam kết bảo hành tái phát 12 tháng bằng văn bản và 0 phát sinh phụ phí ạ. Trước mắt Chị Thảo chưa cần quyết định mua gói ngay đâu ạ, Sáng hay Chiều thứ 7 này Chị rảnh ghé cơ sở để Bác sĩ soi da AI 3D miễn phí 100% phân tích độ sâu chân nám trước xem sao nhé ạ?',
    now() - interval '32 minutes'
),
(
    'b1000000-0000-4000-a000-000000000203',
    'c1000000-0000-4000-a000-000000000002',
    'CUSTOMER',
    'Nghe hợp lý đó em, vậy đặt lịch cho chị 9h30 sáng thứ 7 nhé, sđt chị 0912345678',
    now() - interval '20 minutes'
),
(
    'b1000000-0000-4000-a000-000000000204',
    'c1000000-0000-4000-a000-000000000002',
    'HUMAN_SALE',
    'Dạ em đã giữ lịch 09h30 sáng thứ 7 cùng Bác sĩ CKI cho Chị Thảo qua SĐT 0912345678 rồi ạ!',
    now() - interval '18 minutes'
),
(
    'b1000000-0000-4000-a000-000000000301',
    'c1000000-0000-4000-a000-000000000003',
    'CUSTOMER',
    'Da chị bị mụn ẩn dưới cằm kèm thâm thì làm liệu trình nào em?',
    now() - interval '6 minutes'
),
(
    'b1000000-0000-4000-a000-000000000302',
    'c1000000-0000-4000-a000-000000000003',
    'AI_BOT',
    'Dạ với tình trạng mụn ẩn vùng cằm kèm thâm của Chị Mai, Liệu trình Trị Mụn Chuẩn Y Khoa (1.500.000đ/buổi, tặng kèm 01 tuýp chấm mụn kháng viêm, trọn gói 0 phụ phí) sẽ giúp lấy sạch nhân mụn ẩn êm dịu và làm sáng vết thâm rất nhanh ạ.',
    now() - interval '5 minutes'
),
(
    'b1000000-0000-4000-a000-000000000303',
    'c1000000-0000-4000-a000-000000000003',
    'CUSTOMER',
    'Làm xong hôm sau có bị sưng đỏ không em, chị còn phải đi làm gặp khách hàng',
    now() - interval '1 minute'
) ON CONFLICT (id) DO NOTHING;
