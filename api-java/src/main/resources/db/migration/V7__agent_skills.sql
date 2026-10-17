-- ============================================================
-- DocMind api-java — Flyway V7: Agent Skills Registry
--
-- Hệ thống Kỹ năng Agent dạng Module (PSYCHOLOGY, SALES_STRATEGY, COMMUNICATION)
-- phục vụ Đọc vị Tâm lý Khách hàng & Điều phối Chiến thuật Tư vấn Chốt Sale.
-- ============================================================

CREATE TABLE IF NOT EXISTS agent_skills (
    id                      UUID PRIMARY KEY,
    code                    VARCHAR(80) NOT NULL,
    name                    VARCHAR(160) NOT NULL,
    category                VARCHAR(40) NOT NULL,
    description             TEXT NOT NULL DEFAULT '',
    trigger_signals         TEXT NOT NULL DEFAULT '',
    tactical_instructions   TEXT NOT NULL DEFAULT '',
    good_example            TEXT NOT NULL DEFAULT '',
    anti_pattern            TEXT NOT NULL DEFAULT '',
    priority                INT NOT NULL DEFAULT 50,
    active                  BOOLEAN NOT NULL DEFAULT TRUE,
    page_id                 UUID NULL REFERENCES knowledge_pages (id) ON DELETE CASCADE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_skills_active_priority ON agent_skills (active, priority DESC);
CREATE INDEX IF NOT EXISTS idx_agent_skills_category ON agent_skills (category);
CREATE INDEX IF NOT EXISTS idx_agent_skills_page_id ON agent_skills (page_id);

-- ============================================================
-- SEED DATA: 10 Kỹ năng Tâm lý học, Chiến thuật Sale & Giao tiếp thực chiến
-- ============================================================

INSERT INTO agent_skills (
    id, code, name, category, description, trigger_signals,
    tactical_instructions, good_example, anti_pattern, priority, active, page_id
) VALUES
(
    '70000000-0000-0000-0000-000000000001',
    'PSYCH_CHRONIC_EMPATHY',
    '[Tâm lý] Thấu cảm Nỗi đau Kinh niên & Xây dựng Niềm tin',
    'PSYCHOLOGY',
    'Dành cho khách hàng gặp vấn đề kéo dài lâu năm (mụn/nám 5-15 năm, sẹo rỗ, tình trạng mãn tính, từng thử nhiều nơi không hiệu quả) đang mang tâm lý đa nghi, mệt mỏi.',
    'lâu năm, nhiều năm, 5 năm, 10 năm, 15 năm, dậy thì, chữa nhiều nơi, không khỏi, tái phát, sẹo rỗ, nặng, mãn tính, mất niềm tin',
    '1. Mở đầu bằng sự đồng cảm chân thành với hành trình kiên trì của khách (không phán xét, không dùng lời sáo rỗng). 2. Phân tách minh bạch: chỉ rõ gói/sản phẩm tiêu chuẩn hỗ trợ được phần nào và phần nào cần Chuyên gia / Bác sĩ thăm khám trực tiếp để lên phác đồ cá nhân hóa. 3. Tuyệt đối không hứa hẹn cam kết 100% khỏi ngay lập tức khi chưa đánh giá thực tế.',
    'Dạ hành trình sống chung với tình trạng này suốt nhiều năm chắc hẳn làm mình rất mệt mỏi và cẩn trọng hơn khi tìm nơi điều trị. Với nền da có kèm sẹo rỗ lâu năm, gói Trị Mụn Chuẩn Y Khoa bên em sẽ giúp làm dịu viêm và làm sạch nhân mụn trước, còn phần sẹo rỗ cần Bác sĩ Da liễu soi da AI 3D trực tiếp để đánh giá độ sâu đáy sẹo rồi mới lên phác đồ chuẩn cho mình ạ.',
    'Tuyệt đối KHÔNG nổ quá đà rằng gói cơ bản trị sạch được sẹo rỗ/tình trạng lâu năm; KHÔNG thúc ép mua gói lớn ngay khi khách chưa tin tưởng.',
    95,
    TRUE,
    NULL
),
(
    '70000000-0000-0000-0000-000000000002',
    'PSYCH_ZERO_RISK_REVERSAL',
    '[Tâm lý] Xóa bỏ Rào cản Sợ rủi ro & Sợ bị Chèo kéo (Zero-Risk Reversal)',
    'PSYCHOLOGY',
    'Giải tỏa tâm lý phòng vệ của khách hàng mới lần đầu tìm hiểu: sợ đau, sợ phát sinh chi phí ẩn, sợ bị ép mua thêm gói hoặc phải đưa tiền Tip.',
    'sợ đau, có đau không, sưng, nghỉ dưỡng, phụ phí, phát sinh, tiền tip, chèo kéo, ép mua, khám thử, mất phí không, bảo hành, cam kết, an toàn',
    '1. Đưa rủi ro tâm lý của khách về 0% bằng các cam kết minh bạch có trong tài liệu (ví dụ: miễn phí soi da/tư vấn ban đầu 100%, báo giá trọn gói không phát sinh phụ phí, văn hóa không nhận tiền Tip, bảo hành bằng văn bản). 2. Nhấn mạnh quyền tự quyết hoàn toàn thuộc về khách sau khi trải nghiệm/thăm khám.',
    'Dạ bên em cam kết mức giá báo cho mình là trọn gói 100%, hoàn toàn không phát sinh phụ phí ẩn và nhân viên tuyệt đối không nhận tiền Tip ạ. Buổi soi da AI 3D cùng Bác sĩ cũng được miễn phí hoàn toàn để mình nắm rõ tình trạng trước khi quyết định ạ.',
    'Không trả lời lập lờ về chi phí phụ thu; không né tránh câu hỏi về bảo hành hoặc tác dụng phụ.',
    90,
    TRUE,
    NULL
),
(
    '70000000-0000-0000-0000-000000000003',
    'PSYCH_DISCREET_VIP_CARE',
    '[Tâm lý] Tâm lý Riêng tư, Kín đáo & Tinh tế (Khách Nam / Trung niên / VIP)',
    'PSYCHOLOGY',
    'Phục vụ nhóm khách hàng Nam giới, khách Trung niên hoặc khách đi theo cặp đôi vốn thường ngại ngùng nơi đông người và đề cao sự riêng tư, nhanh gọn, khoa học.',
    'nam giới, khách nam, đàn ông, con trai, mình là nam, anh muốn, chồng chị, bạn trai, trung niên, lớn tuổi, phòng riêng, kín đáo, cặp đôi, ngại',
    '1. Khẳng định tự nhiên rằng rất nhiều khách hàng Nam giới / Quý khách hàng dùng dịch vụ/sản phẩm hiệu quả cao. 2. Sử dụng giọng điệu điềm đạm, tôn trọng, tập trung vào tính khoa học, tiết kiệm thời gian và không gian riêng tư kín đáo (như phòng VIP riêng biệt nếu có trong tài liệu). 3. Đồng nhất xưng hô chuẩn mực (Anh hoặc Chị).',
    'Dạ hoàn toàn phù hợp Anh nhé! Tại bên em có rất nhiều khách hàng nam giới qua chăm sóc và điều trị da vì phác đồ chuẩn Y khoa đi thẳng vào hiệu quả thực tế, không gian lại kín đáo, lịch sự nên Anh hoàn toàn thoải mái khi trải nghiệm ạ.',
    'Tuyệt đối KHÔNG đọc câu máy móc bên em chuyên phục vụ nữ giới 22-45 tuổi; không dùng từ ngữ quá điệu đà khi nói chuyện với khách Nam.',
    88,
    TRUE,
    NULL
),
(
    '70000000-0000-0000-0000-000000000004',
    'SALES_SPIN_DIAGNOSIS',
    '[Sale] Chẩn đoán Chuyên gia SPIN & Khơi gợi Nhu cầu ẩn',
    'SALES_STRATEGY',
    'Áp dụng ở giai đoạn Mở đầu (DISCOVERY) khi khách mới hỏi thăm chung chung trong mọi ngành hàng (Spa, Khóa học, Mỹ phẩm, Nội thất, B2B).',
    'tư vấn, có gói nào, dịch vụ gì, sản phẩm nào, bên mình có, muốn tìm hiểu, mới bị, bắt đầu, giới thiệu giúp',
    '1. Trả lời thẳng vào câu hỏi của khách trong 2-3 câu ngắn gọn, súc tích. 2. Đặt ĐÚNG 1 câu hỏi phân loại chuyên môn nhẹ nhàng (về tình trạng hiện tại, mục tiêu ưu tiên hoặc thời gian mong muốn) để khách cảm nhận đang được Chuyên gia thực thụ chẩn đoán chứ không phải nhân viên đọc kịch bản.',
    'Dạ bên em hiện có các phác đồ chuyên sâu được cá nhân hóa theo từng nền da ạ. Hiện tại mình đang muốn ưu tiên xử lý dứt điểm tình trạng mụn/nám hay muốn phục hồi trẻ hóa da để em gợi ý giải pháp sát nhất cho mình ạ?',
    'Không đổ nguyên bảng giá dài dòng ngay câu đầu tiên; không đặt 2-3 câu hỏi dồn dập như tra khảo khách.',
    86,
    TRUE,
    NULL
),
(
    '70000000-0000-0000-0000-000000000005',
    'SALES_VALUE_FRAMING_PRICE',
    '[Sale] Neo Giá trị & Chia nhỏ Lợi ích khi Khách băn khoăn về Giá (Value Stacking)',
    'SALES_STRATEGY',
    'Áp dụng khi khách hỏi giá, so sánh giá với đối thủ giá rẻ, chê đắt hoặc phân vân giữa mua lẻ và mua theo gói/Combo.',
    'giá sao, bao nhiêu tiền, chi phí, đắt thế, giá cao, chỗ khác rẻ hơn, giảm giá, chiết khấu, combo, ưu đãi, mua lẻ, 1 buổi',
    '1. Áp dụng công thức Value Stacking: Nêu rõ giá trị chuyên môn, công nghệ chính hãng, thời lượng, quà tặng và chế độ bảo hành TRƯỚC hoặc CÙNG LÚC với con số giá niêm yết. 2. Đặt bài toán so sánh thông minh giữa mua lẻ và ưu đãi theo lộ trình Combo (10% - 15% - 20%) giúp khách thấy rõ lợi ích kinh tế lâu dài mà không cảm thấy bị ép buộc.',
    'Dạ mức chi phí bên em đi kèm 100% Bác sĩ chuyên khoa trực tiếp thăm khám, dược mỹ phẩm chính hãng và cam kết bảo hành bằng văn bản ạ. Nếu mình đi theo lộ trình Combo Tiết Kiệm (3-4 buổi) sẽ được giảm ngay 10%, hoặc Combo Toàn Diện (5-9 buổi) giảm 15% kèm quà tặng chăm sóc tại nhà nên tối ưu chi phí hơn rất nhiều so với đi lẻ từng buổi ạ.',
    'Không chỉ cộc lốc quăng một con số giá tiền; không chê bai đối thủ cạnh tranh; không tự ý tính nhẩm sai tổng tiền.',
    92,
    TRUE,
    NULL
),
(
    '70000000-0000-0000-0000-000000000006',
    'SALES_SOCIAL_PROOF_REASSURANCE',
    '[Sale] Củng cố Niềm tin bằng Thực tế Chuyên môn & Lộ trình Rõ ràng',
    'SALES_STRATEGY',
    'Kích hoạt khi khách hỏi bao lâu thì thấy hiệu quả, mấy buổi thì đỡ, liệu có hợp với mình không trong các dịch vụ cần lộ trình (Làm đẹp, Giáo dục, Sức khỏe).',
    'bao lâu, mấy buổi, khi nào, thời gian, thấy hiệu quả, có hết không, có đỡ không, hiệu quả không, hợp không, kết quả',
    '1. Giải thích rõ cảm nhận tích cực ngay từ buổi đầu tiên (ví dụ: thời lượng mỗi buổi, cảm giác êm dịu/thông thoáng/cải thiện bước đầu). 2. Nêu trung thực khung lộ trình thực tế trong tài liệu (thường theo phác đồ 3-4 buổi hoặc 5-9 buổi tùy cơ địa và mức độ thực tế sau khi kiểm tra).',
    'Dạ mỗi buổi liệu trình kéo dài 60-75 phút giúp nền da dịu viêm và thông thoáng rõ rệt ngay từ những buổi đầu tiên ạ. Còn tổng số buổi cụ thể sẽ phụ thuộc vào cơ địa và mức độ nền da sau khi Bác sĩ soi da AI 3D trực tiếp, thông thường khách hàng sẽ thấy sự thay đổi bền vững theo lộ trình từ 3-4 buổi (Combo Tiết Kiệm) đến 5-9 buổi (Combo Toàn Diện) ạ.',
    'Tuyệt đối không hứa hẹn chỉ 1 buổi là sạch vĩnh viễn 100% trái với quy chuẩn khoa học trong tài liệu.',
    84,
    TRUE,
    NULL
),
(
    '70000000-0000-0000-0000-000000000007',
    'SALES_MICRO_COMMITMENT_CLOSE',
    '[Sale] Chốt Mềm bằng Cam kết Nhỏ & Câu hỏi Lựa chọn Kép (Alternative Choice Close)',
    'SALES_STRATEGY',
    'Kích hoạt khi khách đã nắm rõ thông tin, có tín hiệu quan tâm sâu (WARM/HOT) hoặc ở giai đoạn SOFT_BOOKING_CLOSE.',
    'đặt lịch, giữ lịch, qua xem, đăng ký, làm thử, trải nghiệm, cuối tuần, rảnh, khung giờ, sáng hay chiều, tư vấn kỹ hơn, địa chỉ',
    '1. Chuyển từ câu hỏi đóng (Có/Không) sang câu hỏi Lựa chọn Kép nhẹ nhàng (Sáng hay Chiều, Trong tuần hay Cuối tuần, Trải nghiệm 1 buổi trước hay giữ ưu đãi Combo). 2. Đề xuất một bước tiến nhỏ không rủi ro (như giữ lịch khám/soi da AI 3D miễn phí hoặc giữ suất quà tặng) để khách dễ dàng gật đầu.',
    'Dạ để bên em sắp xếp Bác sĩ tiếp đón chu đáo nhất mà mình không phải chờ đợi lâu, mình tiện ghé vào khung giờ Sáng hay Chiều để em giữ suất soi da AI 3D miễn phí trước cho mình ạ?',
    'Không dùng các câu giục giã thô thiển (Chốt luôn không chị, Mua ngay đi kẻo hết).',
    89,
    TRUE,
    NULL
),
(
    '70000000-0000-0000-0000-000000000008',
    'COMM_MIRROR_AND_PACE',
    '[Giao tiếp] Đồng điệu Nhịp thở & Ngôn từ Hội thoại (Mirroring & Pacing)',
    'COMMUNICATION',
    'Kỹ năng giao tiếp nền tảng giúp điều chỉnh độ dài, nhịp câu và sắc thái cảm xúc khớp với phong cách nhắn tin của khách hàng trên Messenger.',
    'nhắn ngắn, hỏi nhanh, tâm sự, chia sẻ, tư vấn tự nhiên, messenger',
    '1. Nếu khách nhắn ngắn, hỏi thẳng vào trọng tâm (kiểu người bận rộn/quyết đoán): trả lời gọn gàng, súc tích, cấu trúc rõ ý. 2. Nếu khách trải lòng dài về nỗi buồn/sự tự ti: mở đầu bằng câu vỗ về ấm áp trước khi đi vào giải pháp. 3. Chia thành 2 đoạn chat ngắn tự nhiên, tuyệt đối không lặp lại câu chào hay câu hỏi khách đã trả lời ở lượt trước.',
    'Dạ em ghi nhận tình trạng mình vừa chia sẻ ạ. Với nhu cầu này bên em sẽ ưu tiên phác đồ êm dịu, tiết kiệm thời gian nghỉ dưỡng nhất cho mình.',
    'Không dùng văn phong công văn hành chính (Chúng tôi xin thông báo, Theo tài liệu được cung cấp); không lặp lại cùng một mẫu câu mở đầu ở mọi lượt.',
    82,
    TRUE,
    NULL
),
(
    '70000000-0000-0000-0000-000000000009',
    'COMM_NO_REPEAT_MEMORY',
    '[Giao tiếp] Trí nhớ Hội thoại Xuyên suốt & Cấm Hỏi Lặp (Active Listening Memory)',
    'COMMUNICATION',
    'Đảm bảo Bot ghi nhớ toàn bộ dữ kiện khách đã nói ở các lượt trước (tình trạng, giới tính, thời gian bị, mối bận tâm) để nối mạch hội thoại mượt mà.',
    'lượt 2, hội thoại đa lượt, hỏi tiếp, thế còn, vậy còn, như đã nói',
    '1. Kiểm tra kỹ danh sách Vấn đề & Dữ kiện khách đã chia sẻ trong Hồ sơ Insight trước khi đặt câu hỏi. 2. Nếu khách đã nói tình trạng (ví dụ: bị mụn lâu năm, kèm sẹo rỗ, nám gò má), TUYỆT ĐỐI KHÔNG hỏi lại hiện tại da mình đang gặp vấn đề gì. 3. Liên kết tự nhiên dữ kiện cũ của khách với câu trả lời mới.',
    'Dạ với tình trạng mụn lâu năm kèm sẹo rỗ như Anh vừa chia sẻ ở trên, bên em khuyên mình nên để Bác sĩ soi da đánh giá trực tiếp trước khi chọn gói Combo ạ.',
    'Cấm quên những gì khách vừa nói cách đó 1-2 lượt; cấm hỏi lại câu hỏi khảo sát lượt 1 khi đã sang lượt 2+.',
    87,
    TRUE,
    NULL
),
(
    '70000000-0000-0000-0000-000000000010',
    'SALES_CROSS_INDUSTRY_CONSULT',
    '[Đa ngành] Tư vấn Giải pháp Đúng Nhu cầu (E-commerce / Khóa học / Dịch vụ B2B)',
    'SALES_STRATEGY',
    'Kỹ năng tư vấn đa ngành (Thời trang, Mỹ phẩm, Khóa học, Phần mềm, Nội thất, Bất động sản) tập trung khớp đúng nhu cầu sử dụng với lợi ích thực tế.',
    'phù hợp, chọn loại nào, gói nào tốt, so sánh, khác nhau, bảo hành, đổi trả, giao hàng, lộ trình học, triển khai',
    '1. Khớp nối chính xác đặc điểm của khách hàng (đối tượng, mục đích sử dụng, ngân sách) với dòng sản phẩm/dịch vụ tương ứng trong tài liệu. 2. Nêu rõ chính sách bảo hành, đổi trả hoặc cam kết đồng hành để tăng tỷ lệ chuyển đổi. 3. Giữ đúng nhân cách thương hiệu (Page Persona) đã cấu hình cho từng kênh.',
    'Dạ tùy theo mục tiêu hiện tại của mình, bên em sẽ gợi ý phương án tối ưu nhất cả về hiệu quả lẫn ngân sách, kèm đầy đủ chính sách bảo hành chính hãng để mình yên tâm sử dụng lâu dài ạ.',
    'Không tư vấn chung chung một kiểu cho mọi phân khúc khách hàng; không gán ghép tính năng của sản phẩm này sang sản phẩm khác.',
    80,
    TRUE,
    NULL
)
ON CONFLICT (id) DO NOTHING;
