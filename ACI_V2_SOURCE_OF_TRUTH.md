# ACI_V2_SOURCE_OF_TRUTH — Hiến Pháp Kiến Trúc `LLM_COGNITIVE_V2` (Adaptive Conversation Intelligence)

> **Trạng thái hiệu lực:** Nguồn chân lý duy nhất (Single Source of Truth) được tổng hợp trực tiếp từ mã nguồn Java & Vue hiện hành.
> Toàn bộ tài liệu kiến trúc V1/C1–C3 cũ (`STAGE_0..STAGE_6`, Regex đoán ý định tiền-LLM, template văn mẫu cứng) đã được cách ly tại `docs/archive_deprecated_v1/` và **TUYỆT ĐỐI KHÔNG ĐƯỢC SỬ DỤNG**.

---

## 1. Cấu Hình Chế Độ Nhận Thức & Rollback

- **Cờ cấu hình trung tâm (`application.properties` / `.env`):**
  ```properties
  docmind.bot.cognitive-mode=${DOCMIND_BOT_COGNITIVE_MODE:LLM_COGNITIVE_V2}
  ```
- **Chế độ mặc định:** `LLM_COGNITIVE_V2` — Vận hành vòng tư duy `ACI Cognitive Loop` trong **Single-Pass** (`Observe -> Interpret -> Prioritize -> Diagnose -> Strategize -> Compose & Validate`).
- **Nhánh dự phòng Rollback:** `DETERMINISTIC_V1` — Giữ nguyên luồng tất định cũ để rollback tức thì qua biến môi trường mà không cần biên dịch lại.

---

## 2. Sáu (06) File Hạ Tầng Đóng Băng (`0 diff` Bất Biến)

Các file hạ tầng dưới đây **ĐÓNG BĂNG TUYỆT ĐỐI (`git diff == 0`)**, không được chỉnh sửa chữ ký hay hành vi trong mọi đợt tinh chỉnh nhận thức và UX hội thoại:

1. `api-java/src/main/java/com/example/docmind/controller/FanpageBotController.java`
2. `api-java/src/main/java/com/example/docmind/service/bot/SenderEpochRegistry.java`
3. `api-java/src/main/java/com/example/docmind/service/bot/SentBubbleLedger.java`
4. `api-java/src/main/java/com/example/docmind/service/bot/JdbcSentBubbleStore.java`
5. `api-java/src/main/java/com/example/docmind/dto/bot/FanpageBotRequest.java`
6. `api-java/src/main/java/com/example/docmind/service/bot/PackageCatalogService.java`

---

## 3. Năm (05) Nguyên Tắc Cốt Lõi Của `LLM_COGNITIVE_V2` (Bảo Toàn Tuyệt Đối)

### 3.1. Tách Biệt 3 Luồng Dữ Liệu Đầu Vào (`ThreeStreamTurnInput`)
Được cài đặt tại `AdaptiveConversationIntelligence.buildThreeStreamInput(...)`:
1. **`Original Message` (`originalMessage` & `originalBurstBubbles`):** Tin nhắn nguyên văn của khách (kèm danh sách từng bong bóng burst gốc), chỉ lọc ký tự điều khiển nguy hiểm/thẻ hệ thống qua `VietnameseQueryNormalizer.sanitizeVerbatimOriginalMessage(...)`, gửi bất biến cho LLM để bảo toàn 100% sắc thái cảm xúc, ẩn dụ và câu ngắn.
2. **`Normalized Query` (`normalizedRetrievalQuery`):** Chuỗi chuẩn hóa qua `VietnameseQueryNormalizer.normalize(...)`, **chỉ dùng nội bộ** cho truy xuất RAG / Hybrid RRF / BM25; tuyệt đối không thay thế `Original Message` gửi vào LLM.
3. **`Unverified Parser Candidates` (`unverifiedParserCandidates`):** Các ứng viên trích xuất thô từ parser/regex của Java (`DATE_CANDIDATE`, `TIME_CANDIDATE`, `PRICE_OR_MONEY_CANDIDATE`, `DURATION_TOKEN_CANDIDATE`, `SCOPE_OR_AREA_CANDIDATE`) luôn gắn cứng `verified = false` và `extractorProvenance` để chống bẫy hiểu nhầm ẩn dụ (ví dụ `"1 ngày"` trong `"1 ngày công r"` không phải thời gian đau 1 ngày).

### 3.2. Đánh Mã Lượt Tuần Tự `[T1]..[T_CURRENT]` Trong `<CONVERSATION_HISTORY>`
Được cài đặt tại `CleanAgentCatalogAndPromptBuilder.buildAciSinglePassPrompt(...)`:
- Mọi lượt hội thoại lịch sử được đánh mã tuần tự `[T1]`, `[T2]`, ..., `[Tn]`.
- Lượt hiện tại của khách được gắn nhãn `[T_CURRENT]` (hiển thị tách bạch `[Bong bóng 1]..[Bong bóng k]` nếu khách gửi burst nhiều tin liên tiếp).
- Các trường bằng chứng `evidence_turn_ids` trong `working_hypothesis` và `strategy` chỉ tham chiếu mã lượt thực tế (`"T1"`, `"T2"`, `"T_CURRENT"`).

### 3.3. Bộ Nhớ 5 Tầng Phân Quyền Tri Thức (`<SESSION_MEMORY_5_TIER>`)
Được quản lý nguyên tử theo khóa `pageId + ":" + senderId` trong `AgentBrainService` và `AdaptiveConversationIntelligence`:
1. **Tier 1 — `Customer Statements` (`CustomerStatementRecord`):** Lời khách tự khai (bao gồm báo đã chuyển khoản, phản ánh lời hứa nhân viên, sự cố dịch vụ), lưu kèm `turnId` và khóa cứng `verifiedBusinessFact = false`.
2. **Tier 2 — `Working Hypotheses` (`WorkingHypothesisRecord`):** Giả thuyết làm việc về nhu cầu/rào cản thực của khách với **5 thao tác vòng đời**: `MAINTAIN`, `REINFORCE`, `ADJUST`, `DOWNGRADE`, `REJECT` (`applyWorkingHypothesisLifecycle`), kèm `confidence` và `evidenceTurnIds`.
3. **Tier 3 — `Unverified Parser Candidates` (`UnverifiedParserCandidate`):** Ứng viên trích xuất shadow gắn `verified = false`, không bao giờ tự nâng lên sự thật nghiệp vụ.
4. **Tier 4 — `Authoritative Verified Business Facts` (`VerifiedBusinessFact`):** Sự thật nghiệp vụ đã xác minh (`PAYMENT_VERIFIED`, `VERIFIED_SLA_POLICY`, Catalog & Document Context), chỉ Java Core hoặc nguồn có thẩm quyền mới được phép ghi (`recordAuthoritativeBusinessFact`).
5. **Tier 5 — `Authoritative Operational State & Session Commitments`:**
   - **Operational State (`OperationalStateSnapshot` & `StructuredBookingState`):** Do Java Core nắm quyền tuyệt đối. Trạng thái đặt lịch qua chat tối đa dừng ở `AWAITING_AVAILABILITY_CHECK` (cấm tự lên `CONFIRMED`). Chính sách Handoff có điều kiện tách bạch `HANDOFF_REQUESTED` vs `HANDOFF_CREATED` vs `HANDOFF_FAILED` (`evaluateAndExecuteConditionalHandoff`).
   - **Session Commitments (`CommitmentRecord`):** Tách bạch `commitmentStatus` (`pending | completed | failed | cancelled`) và `executionStatus` (`not_created | created | in_progress | completed | failed`). LLM chỉ được phép phát hành `CREATE | UPDATE_CONTENT | REMIND | CANCEL_BY_CUSTOMER`; mọi nỗ lực của LLM nhằm tự đóng `COMPLETED` đều bị chặn tại `CommitmentOperation.parseAllowedFromLlm(...)` (chỉ Java Core có `verificationEvidence` mới được gọi `confirmCommitmentCompletedByAuthority`).

### 3.4. Tách Bạch Xưng Hô `defaultCustomerPronoun` & `inferredCustomerPronoun`
- **`defaultCustomerPronoun`:** Nạp động từ cấu hình DB của Page (`PagePersonaConfig.resolvedDefaultCustomerPronoun()`, hiện tại trên Page Nàng Sen Spa là `"mình"`).
- **`inferredCustomerPronoun`:** Chỉ chấp nhận `"Anh" | "Chị" | "Cô" | "Chú" | "Bác"` và **chỉ cập nhật** vào bộ nhớ phiên khi chính người đang nhắn tin tự xưng ngôi thứ nhất rõ ràng với `pronounConfidence >= 0.85` (`INFERRED_PRONOUN_HIGH_CONFIDENCE_THRESHOLD`). Chống bẫy đại từ ngôi thứ ba (`"Chị gái mình..."`, `"Anh kỹ thuật viên..."`).

### 3.5. `PriceQuoteReadingBudgetScheduler` (`10.000ms` Neo Theo `quoteBubbleAcceptedAt`)
Được cài đặt tại `PriceQuoteReadingBudgetScheduler.java` và giao diện Vue:
- Quét thực tế từng bong bóng (`findActualPriceQuoteBubbleIndex`) để xác định đúng bong bóng chứa mức giá khớp Catalog và thông tin gói dịch vụ (không gán cứng `bubble 0`), và không kích hoạt nhầm ở lượt khách phản đối giá (`isActualPriceQuoteIntroductionTurn`).
- Ngân sách đọc `10.000ms` chỉ khởi động sau mốc `quoteBubbleAcceptedAtMs` (khi kênh gửi xác nhận tiếp nhận bong bóng báo giá thành công):
  `T_nextBubbleSend = max(T_nextBubbleReady, T_quoteBubbleAccepted + 10_000ms)`.
- **Tuyệt đối không khấu trừ `llmLatencyMs`** xảy ra trước đó vào ngân sách `10.000ms`.
- **Tuyệt đối không gọi `Thread.sleep` chặn luồng HTTP của Web UI:** Backend trả ngay `reply_bubbles` kèm `typing_delay_ms` (`bubble_delays_ms`); `LiveConversationInbox.vue` và `FanpageBotSimulatorModal.vue` thực thi hiển thị tuần tự bất đồng bộ trên client với bộ đếm nhịp đọc và hủy ngay nếu có lượt tin nhắn mới (`SenderEpochRegistry` / generation counter).

---

## 4. Bản Đồ Các Thành Phần Thực Thi Cốt Lõi Hiện Hành

| Thành phần | Đường dẫn | Vai trò trong `LLM_COGNITIVE_V2` |
|---|---|---|
| `AdaptiveConversationIntelligence` | `api-java/.../service/bot/AdaptiveConversationIntelligence.java` | Định nghĩa `CognitiveMode`, `ThreeStreamTurnInput`, schema & parser `AdaptiveCognitiveReflection`, vòng đời `WorkingHypothesis`, `CommitmentRecord`, `ConditionalHandoffDecision`, và `applyAuthoritativeBookingPatch`. |
| `PriceQuoteReadingBudgetScheduler` | `api-java/.../service/bot/PriceQuoteReadingBudgetScheduler.java` | Xác định lượt/bong bóng báo giá thực tế, gán `typingDelayMs` không chặn HTTP, và điều phối gửi tuần tự neo theo `quoteBubbleAcceptedAt` tích hợp `SenderEpochRegistry` & `SentBubbleLedger`. |
| `CleanAgentCatalogAndPromptBuilder` | `api-java/.../service/bot/CleanAgentCatalogAndPromptBuilder.java` | Xây dựng prompt hợp nhất Single-Pass (`buildAciSinglePassPrompt`) gồm `<VERIFIED_PAGE_POLICY>`, `<PAGE_PLAYBOOK_COMPACT_DIRECTIVES>`, `<VERIFIED_BUSINESS_CATALOG>` (Epistemic Unblinding), `<SESSION_MEMORY_5_TIER>`, `<DOCUMENT_CONTEXT>`, và `<CONVERSATION_HISTORY>` (`[T1]..[T_CURRENT]`). |
| `CleanAgentOrchestratorService` | `api-java/.../service/bot/CleanAgentOrchestratorService.java` | Điều phối `executeAciCognitiveTurn`: khôi phục Catalog & bộ nhớ 5 tầng, gọi LLM Single-Pass, qua cổng Referee V2, áp dụng `PriceQuoteReadingBudgetScheduler`, cập nhật bộ nhớ nguyên tử và ghi nhận Telemetry/Profiling. |
| `CleanAgentOutputRefereeAndSanitizer` | `api-java/.../service/bot/CleanAgentOutputRefereeAndSanitizer.java` | Parse JSON Single-Pass (`parseStructuredLlmJsonOutput`) và kiểm định Referee V2 (`checkAciV2TruthAuthorityAndSafety`) **chỉ giữ ranh giới Sự thật, Quyền hạn & An toàn** (tắt cưỡng chế chiến thuật bán hàng cứng thay LLM). |
| `AgentBrainService` | `api-java/.../service/bot/AgentBrainService.java` | Lưu trữ & cập nhật đồng bộ (`synchronized`) bộ nhớ 5 tầng theo phiên (`applyAciTurnMemoryUpdates`), quản lý đại từ suy luận, giả thuyết, cam kết phiên và sự thật nghiệp vụ có thẩm quyền. |
| `FanpageBotService` | `api-java/.../service/bot/FanpageBotService.java` | Điểm vào xử lý tin nhắn khách hàng, đối chiếu `SentBubbleLedger`, dựng `ThreeStreamTurnInput`, truy xuất Hybrid RAG + Rules của Page và ủy quyền sang `CleanAgentOrchestratorService` ở chế độ `LLM_COGNITIVE_V2`. |
| `LiveConversationInbox.vue` & `FanpageBotSimulatorModal.vue` | `frontend/src/components/` | Giao diện Hộp thư trực tuyến & Giả lập Messenger: hiển thị bong bóng tuần tự bất đồng bộ theo `typing_delay_ms`, hiển thị kỹ năng thực sự được kích hoạt (`activatedSkills`) và thông tin telemetry của lượt hội thoại. |

---

## 5. Chuẩn `Consultative Sales Artistry & Laptop Responsive UX` Hiện Hành

1. **Hành Trình Tư Vấn 5 Bước & 6 Năng Lực Bán Hàng Tinh Tế (Chống "Ố dề / Thảo mai"):**
   - **`Hospitality` (Khôi phục cấu hình Mở đầu UI Page):** Ở lượt 1 (`history.isEmpty()`), `buildAciSinglePassPrompt` nạp `configured_opening_bubbles` từ `PagePersonaConfig.openingBubbles()` để Bot đón tiếp ấm áp theo đúng bản sắc 2–3 bong bóng nhịp nhàng đã cấu hình trên UI Page (cùng `PB-SPA-OPEN-001` tại Flyway `V28`) khi khách chào hỏi / mở lời cần tư vấn chung; nếu khách hỏi thẳng 1 ý cụ thể ngay lượt 1 (`"Giá"`, `"Mở cửa mấy giờ"`), trả lời thẳng ngay 1 bong bóng.
   - **`Active Listening` & `Emotional Reflection` (*Reflective Listening*):** Cấm cảm thán thảo mai giả tạo (*"Ôi em thương mình quá"*, *"Mình khổ tâm quá rồi"*) và không lặp *"Dạ em hiểu ạ"* đầu mỗi lượt; phản chiếu sát hoàn cảnh thực tế + cảm giác khó chịu khách vừa chia sẻ rồi đặt 1 câu hỏi mở có mục đích (`Need Discovery`).
   - **`Value Alignment` (`Why This Package` có căn cứ xác thực):** `Why This Package = Nhu cầu khách đã xác nhận + Thành phần dịch vụ xác minh trong Catalog/<DOCUMENT_CONTEXT> + Lợi ích thư giãn phù hợp + Giới hạn trung thực` (cấm tự sáng tác cơ chế y khoa/sinh học).
   - **`Ethical Closing` & `BookingReadiness` (`EXPLORING -> CONSIDERING -> READY_TO_BOOK`):** Cổng thẩm quyền `AdaptiveConversationIntelligence.applyAuthoritativeBookingPatch` chặn việc nhảy cóc từ `BookingStatus.NONE` sang `AWAITING_DATE_OR_TIME` và `CleanAgentOrchestratorService.resolveAciTurnActivatedSkills` gỡ 2 skill đặt lịch (`warm_nod_to_gentle_scheduling`, `booking_careful_preparation`) khi khách mới ở trạng thái `EXPLORING` / `CONSIDERING` (VD hỏi thông tin gói 239k, băn khoăn `"Đắt"`, hỏi `"có giảm giá hay KM k"`).
   - **Duy trì bền vững `communication_style_preference` trong Tier 5 (`OperationalStateSnapshot`):** Khi khách yêu cầu `"Nói ngắn thôi"`, `AgentBrainService` lưu dính `"CONCISE"` xuyên các lượt tiếp theo (1–2 câu ngắn trong 1 bong bóng) cho tới khi khách chủ động yêu cầu giải thích chi tiết (`"DETAILED"`).
2. **Tư Vấn Tình Huống Sức Khỏe (như `"Tôi hay hoa mắt chóng mặt"`):**
   - Tách biệt rõ dịch vụ thư giãn với tư vấn y tế: cấm suy diễn nguyên nhân do bó cơ/tuần hoàn máu, cấm khẳng định massage điều trị triệu chứng hay trì hoãn khám bệnh, và cấm tự hứa gọi điện/bật Handoff khi chưa có SĐT (`PB-SPA-SAFETY-001`). Dấu hiệu cấp tính nguy hiểm -> `priority.primary = SAFETY`.
3. **Tối Ưu Prompt Runtime (>50% cắt giảm), Đồng bộ RAG Vận Hành & Tắt CoT Ẩn:**
   - Lọc Catalog hiển thị trong Prompt theo vùng đang tư vấn + khử trùng lặp chi nhánh theo chữ ký chuẩn (`deduplicateCatalogByCanonicalSignature`), rút gọn `scopeTags` toàn thân (`>= 8` tag -> `"toàn thân"`), lọc bảng giá Markdown thô trùng lặp khỏi `<DOCUMENT_CONTEXT>` (`isRedundantCatalogTableChunkForAciV2`, ngân sách `<= 1.300 ký tự` / tối đa 4 chunk), và chọn tối đa 5 `LearnedRuleDto` khớp ngữ cảnh lượt hiện tại (`selectTurnRelevantCompactRules`).
   - Bổ sung `FanpageBotService.enrichFusedChunksWithOperationalAndPromoFacts` kết hợp đồ thị RAM `ObsidianMetadataCache` để đảm bảo truy xuất chính xác chunk giờ mở cửa, địa chỉ cơ sở và chính sách ưu đãi/trọn gói khi chạy `provider_model=stub`, đồng thời đối chiếu `<DOCUMENT_CONTEXT>` trong `CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety` để không cảnh báo giả với ưu đãi có thật trong KB.
   - Trường suy luận trong `brain_reflection` giới hạn siêu ngắn (`<= 8 từ`) kèm `"enable_thinking": false` cho `qwen` ở `FAST_NO_THINK`.
4. **Chuẩn Giao Diện Laptop (`LiveConversationInbox.vue` & `FanpageBotSimulatorModal.vue`):**
   - **1 dòng Pill Metadata siêu gọn `24px` (mặc định đóng):** `🛡️ V2 · ⚡ <Số skill kích hoạt> · 📋 <Số rule> · ⏱️ <totalTurnMs>s` kèm `[Chi tiết AI ▾]` (chỉ bung drawer cho lượt đang click) và `[Báo sai]`.
   - **Full-Viewport Flex Layout:** Khóa chiều cao theo viewport Laptop, cho phép thu gọn các cột phụ (`◀ DS Khách`, `📊 Insight KH`, `📊 Ẩn Radar AI ▸`), đảm bảo vùng cuộn hội thoại chiếm **>= 80% chiều cao màn hình** (hiển thị liên tục 6–8+ lượt chat).
