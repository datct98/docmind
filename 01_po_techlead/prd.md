# PRD — Đại Tu Trải Nghiệm Hội Thoại Giàu Cảm Xúc & Chốt Sale Tự Nhiên (DocMind Fanpage Bot v2.0)

## 1. Tuyên bố vấn đề (Problem Statement)
Qua kiểm toán toàn diện hệ thống DocMind (`api-java` và `agent-brain`), nguyên nhân gốc rễ khiến người dùng cảm thấy hội thoại hiện tại **"tụt mood, chỉ muốn thoát ra ngay"** nằm ở 5 điểm nghẽn hệ thống:

1. **Cơ chế Ghi đè Cứng nhắc (Brittle Deterministic Overwrites) xóa sổ cảm xúc tự nhiên của LLM**:
   - Trong `FanpageBotService.java` (`enforcePriceLadderConsistency`, `synthesizeDynamicExplanationOrSymptomReply`, `enforceAntiInformationDumpingOnBroadPrice`) và `AntiInfoDumpingHook.java`, chỉ cần câu trả lời của LLM thiếu một từ khóa phụ (như `"ngâm chân"` hay `"kiểm chứng"`), hệ thống lập tức **vứt bỏ toàn bộ văn phong đồng cảm tự nhiên của LLM** và thay thế bằng một chuỗi văn bản mẫu (template) khô cứng, lặp đi lặp lại.
2. **Cơ chế Gộp Bong Bóng & Cắt Câu Hỏi Kết (Bubble Collapsing & Mechanical Closing Questions)**:
   - Ở Lượt 1 (`stripLeakedNumbersFromTurnOneReply` và `polishConversationalVietnamese`), hệ thống vô tình ép gộp cấu trúc 3 nhịp cảm xúc thành 2 đoạn văn dày đặc hoặc thay câu hỏi tự nhiên bằng câu hỏi khảo sát máy móc từ `questionPool` tĩnh (`"Chị muốn đặt lịch vào khung giờ Sáng hay Chiều để bên em giữ phòng riêng trước ạ?"`).
3. **Đứt gãy Cảm xúc ở Lượt 1 khi khách hỏi dịch vụ kèm chi phí**:
   - Khi khách mở lời hỏi chung (`"Cho mình hỏi về dịch vụ bên bạn, chi phí như thế nào?"`), `isTurnOneImpressionGateActive` bị tắt và `AntiInfoDumpingHook` lập tức ghi đè bằng thông báo hành chính khô khan (`"Các gói chăm sóc và trị liệu bên em dao động từ 129k - 580k tùy thời lượng..."`), thiếu hoàn toàn sự chào đón ấm áp và cảm xúc chữa lành của Nàng Sen Spa.
4. **Phản ứng Sau Báo Giá (Post-Price Turn) thiếu sự nâng niu tâm lý**:
   - Sau khi khách nghe giá và phản ứng (`"Giá hơi cao nhỉ"`, `"Thế có ưu đãi gì không?"`, `"Để mình suy nghĩ thêm đã"`), các bản thay thế trong `enforcePriceLadderConsistency` đi thẳng vào liệt kê thông số gói thay vì công nhận cảm xúc của khách trước (Validate -> Reassure -> Soft Bridge).
5. **Xung đột giữa Chuẩn 3 Bong Bóng (`<= 3` bubbles) và các Hook/Test cũ (`<= 2` bubbles)**:
   - Một số kịch bản 3 bong bóng cảm xúc bị cắt/gộp thô bạo khiến nhịp đọc trên Messenger bị ngợp chữ.

## 2. Mục tiêu Sản phẩm (Product Objectives)
- **OBJ-1 (Emotional Resonance - Chạm cảm xúc từ giây đầu)**: 100% các lượt trả lời mở đầu và tư vấn triệu chứng đều mở nhịp bằng sự đồng cảm chân thành, giàu hình ảnh xúc giác (thảo dược tươi nấu ấm, giải tỏa điểm bó cơ căng cứng, nhẹ bẫng đôi vai, chườm ngải cứu ấm...).
- **OBJ-2 (Natural 3-Beat Messenger Pacing)**: Phản hồi chia thành 2–3 bong bóng chat ngắn gọn (`<= 3` bubbles), mỗi bong bóng đóng một vai trò tâm lý rõ rệt:
  - *Bubble 1*: Đồng cảm sâu sắc / Đón nhận cảm xúc của khách hàng.
  - *Bubble 2*: Giải pháp trải nghiệm giàu hình ảnh + Neo giá trị minh bạch (trọn gói 0 Tip, đặc quyền đi kèm).
  - *Bubble 3*: Câu hỏi gợi mở nhẹ nhàng, tự nhiên hoặc lời chúc ấm áp không gây áp lực (khi khách cần suy nghĩ thêm).
- **OBJ-3 (Post-Price Mood Preservation & ~10s Deliberate Thinking)**: Ở các lượt ngay sau khi báo giá (trên môi trường LLM thực), hệ thống suy nghĩ sâu ~10s, kiểm định chất lượng cảm xúc để tuyệt đối không làm khách tụt mood khi chê đắt, hỏi khuyến mãi hay lưỡng lự.
- **OBJ-4 (Consultative Sales Closing)**: Chuyển đổi mượt mà từ đồng cảm -> gợi mở trải nghiệm buổi lẻ đầu tiên -> giữ suất ưu đãi / xin SĐT & khung giờ hẹn tự nhiên.
- **OBJ-5 (Antigravity Multi-Agent Team Pipeline v1.0)**: Tích hợp hoàn chỉnh quy trình 4 Agent độc lập ngữ cảnh kèm bộ chạy tự động và kiểm định hộp đen nghiêm ngặt.

## 3. Yêu cầu Chức năng (Functional Requirements)
- **FR-01**: Nâng cấp `FanpageBotPromptBuilder` và `skill_matrix.py` với bộ hướng dẫn ngôn ngữ cảm xúc xúc giác (Sensory & Emotional Empathy Lexicon) cho từng tình huống: đau mỏi vai gáy/lưng, mất ngủ/stress, hỏi giá lần đầu, chê đắt lần 1/lần 2, hỏi ưu đãi, xin suy nghĩ thêm, và chốt lịch.
- **FR-02**: Cải tiến `enforcePriceLadderConsistency`, `enforceAntiInformationDumpingOnBroadPrice`, `AntiInfoDumpingHook`, `ClosingQuestionHook`, và `generateGroundedFallbackReply` để:
  - Bảo toàn câu văn tự nhiên của LLM nếu đã bám sát mức giá mục tiêu và không vi phạm chính sách.
  - Khi cần can thiệp chỉnh sửa hoặc fallback, tạo ra phản hồi giàu cảm xúc, mềm mại, tinh tế như chuyên viên tư vấn thật thay vì văn mẫu hành chính.
- **FR-03**: Hoàn thiện cơ chế 3 bong bóng (`<= 3` bubbles) ở Lượt 1 ấn tượng (`buildImpressiveThreeBubbleTurnOneOpener`) và các lượt tư vấn cảm xúc sâu, đồng thời giữ tương thích ngược hoàn toàn với toàn bộ bộ Unit Test hiện có.
- **FR-04**: Xây dựng hệ thống Multi-Agent Pipeline (`99_pipeline/run_multi_agent_pipeline.py`) thực thi kiểm định tự động qua 4 cổng PO -> Dev -> Black-Box QA -> Product UAT.
- **FR-05 (Cấu hình Model `qwen2.5:14b-instruct-q5_K_M` & Tham số Suy luận Đa Tác vụ)**:
  - Hỗ trợ đầy đủ tham số `num_ctx = 4096`, `temperature = 0.2`, `top_p = 0.9`, `repeat_penalty = 1.08`, `num_predict = 600` cho Fanpage Sales Chat; `temperature = 0.1`, `top_p = 0.85`, `num_predict = 900`, `repeat_penalty = 1.05` cho Knowledge QA; và `temperature = 0.0`, `top_p = 0.8` cho Memory/Rule Extraction & Judge trên cả Ollama và OpenAI-compatible API Key providers.
  - Tự động fallback không gián đoạn sang `qwen2.5:14b` / `qwen2.5:7b` nếu `qwen2.5:14b-instruct-q5_K_M` chưa được pull trên máy chủ.
- **FR-06 (Hệ thống Upload Playbook & Hội thoại Mẫu `.yaml`, `.md`, `.json` Kích hoạt Ngay theo Page)**:
  - Cung cấp API `POST /api/rules/upload-playbook`, `POST /api/rules/upload-playbook-file`, `GET /api/rules/sample-dialogues` và giao diện `PlaybookModal.vue` cho phép tải lên Playbook (`PB-*`) và Hội thoại mẫu (`FD-*`) theo từng Page.
  - Bỏ bước duyệt thủ công: tự động kích hoạt `ACTIVE` (`active = true`, `review_status = 'APPROVED'`) ngay lập tức cho các mục hợp lệ và tự động cách ly (`active = false`, `review_status = 'REJECTED'`) các mẫu chứa PII, cam kết chữa khỏi vĩnh viễn hoặc Prompt Injection (`FD-SPA-BAD-001`, `FD-SPA-BAD-002`).
- **FR-07 (Truy xuất `<SAMPLE_DIALOGUES>` & Neo Giá Trực Tiếp từ KB, Không Dùng API Giá Ngoài)**:
  - Truy xuất 1–2 hội thoại mẫu (`FD-*`) sát ngữ cảnh nhất theo `page_id` + `intent` + `symptom` để bơm vào `FanpageBotPromptBuilder` (kèm cờ `do_not_copy_literally`), giúp bot học nhịp điệu tư vấn tự nhiên trong khi toàn bộ bảng giá lấy trực tiếp từ `<DOCUMENT_CONTEXT>`.

