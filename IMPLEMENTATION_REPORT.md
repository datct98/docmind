# BÁO CÁO THỰC THI KHẮC PHỤC CHẤT LƯỢNG SAU CP3 (`IMPLEMENTATION_REPORT.md`)

> [!IMPORTANT]
> **Định hướng sản phẩm xuyên suốt:** **`FEEL FIRST. UNDERSTAND SECOND. SELL THIRD.`**
> Báo cáo này ghi nhận toàn bộ kết quả triển khai code phẫu thuật tối thiểu qua 3 đợt (**Wave 1: P0**, **Wave 2: P1**, **Wave 3: P2**), kết quả kiểm thử tự động (`147 tests run, 0 failures`), kết quả đối chiếu **Before / After** trực tiếp trên runtime container `docmind-api`, bộ nghiệm thu tổng hợp **10 tình huống thực tế**, số liệu đo `ThinkingMode` trên endpoint Qwen, và kết quả kiểm toán độc lập từ **Qwen CLI** & **Claude CLI**.

---

## 1. Baseline Commit & Nhánh Thực Thi

| Hạng mục | Giá trị xác thực (`FACT`) |
| :--- | :--- |
| **Repository** | `datct98/docmind` (`d:\Project\digital-spirituality\docmind`) |
| **Nhánh gốc tham chiếu** | `refactor/aci-v2-cleanup` |
| **Baseline HEAD đã kiểm định** | `304ff580e664ce079a17242a760a137e98102503` |
| **Nhánh thực thi cô lập** | `fix/post-cp3-quality-remediation` |
| **HEAD cuối sau Wave 3** | `b60be274e795bc851ff512508cb8636ffca5f2eb` |
| **Target Page** | Nàng Sen Spa (`2850a8ef-ca4a-40eb-87fa-4f808640efee`) |
| **Negative Control Page** | Trang “Thẩm mỹ viện” (`59e68078-8327-4aa0-8cca-91410f783ad4`) — **0 tác động** |

---

## 2. Danh Sách Commit Theo Từng Wave

| Wave | Mức ưu tiên | Commit SHA | Thông điệp Commit | Số file thay đổi |
| :--- | :--- | :--- | :--- | :--- |
| **Wave 1** | **P0** — Customer Understanding & Conversation Control | `013253bb63805e91c41bafba75a87b5e38cd0892` | `fix(bot): Wave 1 P0 customer understanding, contextual scope, mixed comparison & health boundary` | 5 files (`+676 / -53`) |
| **Wave 2** | **P1** — Price Consultation & Idea-Based Bubbles | `ff55078ba481ab67532f40eaa92bf314102de9ac` | `fix(bot): Wave 2 P1 general price starting anchor, need discovery & semantic idea-based bubbles` | 5 files (`+308 / -19`) |
| **Wave 3** | **P2** — Reasoning & Memory Integrity | `b60be274e795bc851ff512508cb8636ffca5f2eb` | `fix(bot): Wave 3 P2 ThinkingMode payload differentiation, unresolved_requests lifecycle & confirmed_facts validation` | 7 files (`+569 / -30`) |

- Bản diff từng Wave và tổng hợp được lưu tại:
  - [wave1_commit.diff](file:///C:/Users/Lecoo/.gemini/antigravity/brain/e28df741-8f7d-43e3-accf-a481b29616a3/evidence/wave1_commit.diff)
  - [wave2_commit.diff](file:///C:/Users/Lecoo/.gemini/antigravity/brain/e28df741-8f7d-43e3-accf-a481b29616a3/evidence/wave2_commit.diff)
  - [wave3_commit.diff](file:///C:/Users/Lecoo/.gemini/antigravity/brain/e28df741-8f7d-43e3-accf-a481b29616a3/evidence/wave3_commit.diff)
  - [full_remediation_304ff580_to_HEAD.diff](file:///C:/Users/Lecoo/.gemini/antigravity/brain/e28df741-8f7d-43e3-accf-a481b29616a3/evidence/full_remediation_304ff580_to_HEAD.diff)

---

## 3. Danh Sách File Đã Sửa Và Mục Đích Từng File

| STT | File | Wave liên quan | Mục đích thay đổi |
| :--- | :--- | :--- | :--- |
| 1 | [AdaptiveConversationIntelligence.java](file:///d:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/bot/AdaptiveConversationIntelligence.java) | Wave 1, 2, 3 | Đồng bộ nhận diện phản hồi gay gắt về cách tư vấn (`"Ơ, dở hơi à"`), nhận diện xác nhận vùng cơ thể theo ngữ cảnh câu hỏi trước đó (`isContextualShortBodyRegionScopeReply`), hỗ trợ mixed intent `PRICE_OBJECTION + PACKAGE_COMPARISON`, tách ranh giới 4 trường hợp triệu chứng sức khỏe, chuẩn hóa chỉ thị `PRICE_INTENT_A (GENERAL_PRICE_INQUIRY)`, và chặn `confirmed_facts` suy diễn/cắt cụt (`isInvalidOrTruncatedFactValue`). |
| 2 | [CleanAgentOrchestratorService.java](file:///d:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/bot/CleanAgentOrchestratorService.java) | Wave 1, 3 | Cho phép chọn đồng thời `price_objection_sensory_value` và `package_comparison_and_scope_clarity` ở lượt mixed intent; không giữ `discovery_empathy_and_scope` khi khách đã xác nhận vùng cơ thể; truyền `combinedReplyText` vào bộ cập nhật memory để đối chiếu nội dung thực tế trước khi đóng `unresolved_requests`; cấp `maxTokens = 6400` cho `DEEP_REASONING` (`OBJECTION_REFRAME_THINK`). |
| 3 | [CleanAgentOutputRefereeAndSanitizer.java](file:///d:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/bot/CleanAgentOutputRefereeAndSanitizer.java) | Wave 1, 2 | Đồng bộ mẫu phản ứng gay gắt về cách tư vấn sang `COMMUNICATION_STYLE_COMPLAINT_PATTERN` để chấm dứt xung đột Split-Brain (`sanitizerMutationRate` giảm từ `0.863` về `0.000`); bảo vệ tên hạng mục chuẩn trong Catalog (`"massage cổ vai gáy"`) khỏi bị cắt chữ `"cổ"`; đảm bảo báo giá khởi điểm thực tế từ Catalog ở `GENERAL_PRICE_INQUIRY`; và tổ chức `reply_bubbles` theo đơn vị ý nghĩa (`organizeBubblesBySemanticIdeaUnits`). |
| 4 | [CleanAgentCatalogAndPromptBuilder.java](file:///d:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/bot/CleanAgentCatalogAndPromptBuilder.java) | Wave 2 | Trích xuất động `starting_package_anchor` từ Catalog hiện hành của từng tenant (không hardcode giá vào Shared Core) và đồng bộ bản sao in-code của system prompt với `config/aci_v2_system_prompt.md`. |
| 5 | [config/aci_v2_system_prompt.md](file:///d:/Project/digital-spirituality/docmind/config/aci_v2_system_prompt.md) | Wave 2 | Đồng bộ quy tắc `Price Intent (A)` (nêu giá khởi điểm + 1–2 gói đại diện + hỏi nhẹ nhu cầu/vùng mỏi thay vì bắt khách chọn gói cụ thể) và quy tắc tách `bubbles` theo đơn vị ý nghĩa. |
| 6 | [AgentBrainService.java](file:///d:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/bot/AgentBrainService.java) | Wave 1, 3 | Ngăn ghi vùng cơ thể vào `self_reported_symptoms` khi khách chỉ chọn vùng chăm sóc; kiểm tra nội dung câu trả lời thực tế của bot (`doesAssistantReplyAnswerPriceInquiry`, `doesAssistantReplyExplainPackageComparison`) trước khi chuyển `unresolved_requests` sang `ANSWERED`; chặn tự động gắn `"khách phản ứng giá đắt"` khi lượt khách không có tín hiệu phản đối giá thực sự (`hasGenuinePriceObjectionCue`). |
| 7 | [OpenAiCompatibleLlmClient.java](file:///d:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/llm/OpenAiCompatibleLlmClient.java) | Wave 3 | Khôi phục truyền tham số reasoning theo đúng `ThinkingMode`: tắt thinking (`enable_thinking=false`, `reasoning_effort="none"`) ở `FAST_NO_THINK`, bật thinking (`enable_thinking=true`, `reasoning_effort="low"`) ở `OBJECTION_REFRAME_THINK` (`thinkEnabled() == true`), kèm cơ chế fallback tắt thinking an toàn khi retry `BadRequest` hoặc `emptyContentWithReasoning()`. |
| 8 | [PostCp3Wave1CustomerUnderstandingTest.java](file:///d:/Project/digital-spirituality/docmind/api-java/src/test/java/com/example/docmind/service/bot/PostCp3Wave1CustomerUnderstandingTest.java) | Wave 1 | Bộ unit/integration test kiểm chứng toàn bộ 4 hạng mục P0 của Wave 1 (`8/8 PASS`). |
| 9 | [PostCp3Wave2PriceAndBubblesTest.java](file:///d:/Project/digital-spirituality/docmind/api-java/src/test/java/com/example/docmind/service/bot/PostCp3Wave2PriceAndBubblesTest.java) | Wave 2 | Bộ unit/integration test kiểm chứng toàn bộ 2 hạng mục P1 của Wave 2 và trường hợp biên do Qwen Auditor phát hiện (`6/6 PASS`). |
| 10 | [PostCp3Wave3ReasoningAndMemoryTest.java](file:///d:/Project/digital-spirituality/docmind/api-java/src/test/java/com/example/docmind/service/bot/PostCp3Wave3ReasoningAndMemoryTest.java) | Wave 3 | Bộ unit/integration test kiểm chứng toàn bộ 3 hạng mục P2 của Wave 3 (`3/3 PASS`). |
| 11 | [P36ConversationIntelligenceVerificationTest.java](file:///d:/Project/digital-spirituality/docmind/api-java/src/test/java/com/example/docmind/service/bot/P36ConversationIntelligenceVerificationTest.java) & [OpenAiCompatibleLlmClientTest.java](file:///d:/Project/digital-spirituality/docmind/api-java/src/test/java/com/example/docmind/service/llm/OpenAiCompatibleLlmClientTest.java) | Wave 3 | Cập nhật kỳ vọng test payload `OBJECTION_REFRAME_THINK` khớp với chính sách `enable_thinking=true` khi `thinkEnabled() == true`. |

---

## 4. Chi Tiết Sửa Đổi Theo Từng Hạng Mục (`P0`, `P1`, `P2`)

### 4.1. Wave 1 — `P0`: Customer Understanding & Conversation Control
- **1.1 — Chấm dứt xung đột Split-Brain giữa Pre-LLM Planner, Skill Selector và Post-LLM Sanitizer (`"Ơ, dở hơi à"`):**
  - Chuyển mẫu phản ứng cảm thán về cách tư vấn (`\b(?:ơ\s*[,،]?\s*)?(?:dở\s+hơi|bị\s+dở|ngáo|lẩm\s+cẩm)\s*(?:à|hả|vậy|thế)\b`) sang `COMMUNICATION_STYLE_COMPLAINT_PATTERN` và bổ sung ngữ cảnh kiểm tra lượt trước trong `AdaptiveConversationIntelligence`.
  - Khi khách phản ứng `"Ơ, dở hơi à"`, cả Pre-LLM Planner lẫn Post-LLM Sanitizer đều thống nhất nhận diện là `PROCESS_FEEDBACK` (`SERVICE_RECOVERY`), không ra lệnh tư vấn gói mới (`SERVICE_RECOMMENDATION`), không ghi đè `price_reaction` suy diễn, và không cắt 86,3% câu trả lời (`sanitizerMutationRate` giảm từ `0.863` xuống `0.000`).
- **1.2 — Nhận diện câu trả lời xác nhận vùng cơ thể ngắn gọn theo ngữ cảnh (`"Đầu và vai gáy"`, `"Cả hai"`, `"Chân và lưng"`):**
  - Bổ sung `isContextualShortBodyRegionScopeReply(msg, history)` trong `AdaptiveConversationIntelligence`: chỉ nhận diện câu ngắn nêu bộ phận cơ thể là `SCOPE_CONFIRMATION` khi lượt ngay trước của bot là câu hỏi khám phá vùng mỏi/nhu cầu (`lastAssistantAskedNeedOrScopeQuestion`) hoặc khách đã chia sẻ triệu chứng trước đó.
  - Ngăn `AgentBrainService` ghi nhầm vùng cơ thể khách chọn (`"đầu và vai gáy"`) thành triệu chứng bệnh lý trong `self_reported_symptoms`.
  - Sửa `sanitizeUnsolicitedNeckFromHeadShoulderScopeInPlace()` trong `CleanAgentOutputRefereeAndSanitizer` để bảo vệ cụm danh từ hạng mục chuẩn của gói trong Catalog (`"massage cổ vai gáy"`).
- **1.3 — Xử lý câu gộp `PRICE_OBJECTION + PACKAGE_COMPARISON` (`"Đắt thế, gói 239k với 399k khác nhau thế nào?"`):**
  - Giữ đồng thời hai tín hiệu `PRICE_OBJECTION_HANDLING` và `PACKAGE_COMPARISON_REQUEST` trong `AdaptiveConversationIntelligence`.
  - Sửa điều kiện lọc skill tại `CleanAgentOrchestratorService.selectPreLlmTurnRelevantSkills()` để kích hoạt đồng thời cả `price_objection_sensory_value` và `package_comparison_and_scope_clarity`.
- **1.4 — Khóa ranh giới triệu chứng sức khỏe (`"Tôi hoa mắt chóng mặt tê chân tay"`):**
  - Tuyệt đối không gộp `tê|chân|tay` vào `hasMusculoskeletalOrCareContext` để biến triệu chứng thần kinh thành nhu cầu mua gói spa.
  - Phân biệt rõ 4 trường hợp trong `AdaptiveConversationIntelligence`: (1) Chia sẻ triệu chứng sức khỏe (`HEALTH_SAFETY_BOUNDARY`), (2) Xác nhận vùng cơ thể muốn chăm sóc (`SCOPE_CONFIRMATION`), (3) Chủ động yêu cầu tư vấn dịch vụ, và (4) Hỏi về độ an toàn/phù hợp y khoa (`SUITABILITY_INQUIRY_HANDOFF` — chuyển nhân viên/CSKH xác nhận).

### 4.2. Wave 2 — `P1`: Price Consultation & Idea-Based Bubbles
- **2.1 — Chuẩn hóa tư vấn khi khách hỏi `"Giá"` (`GENERAL_PRICE_INQUIRY`):**
  - Bổ sung `resolveLowestCoreStartingPackageAnchor()` trong `CleanAgentCatalogAndPromptBuilder` để trích xuất động gói cơ bản có giá khởi điểm thấp nhất từ Catalog hiện hành của trang (không hardcode `239k`/`399k`/`670k` vào Shared Core).
  - Đồng bộ `config/aci_v2_system_prompt.md`, bản sao in-code và `PRICE_INTENT_A` trong `AdaptiveConversationIntelligence`: nêu rõ mức giá khởi điểm cùng 1–2 gói đại diện, nói rõ đây là vài lựa chọn tiêu biểu chứ không phải toàn bộ danh mục, và hỏi nhẹ nhu cầu thư giãn / vùng cơ thể ở Bubble 2 (tuyệt đối không hỏi *"Mình quan tâm gói nào ạ?"* khi chưa hiểu nhu cầu).
- **2.2 — Chia `reply_bubbles` theo đơn vị ý nghĩa (`organizeBubblesBySemanticIdeaUnits`):**
  - Đồng bộ xuyên suốt từ System Prompt -> JSON Schema -> Parser -> Validator -> `organizeBubblesBySemanticIdeaUnits` trong `CleanAgentOutputRefereeAndSanitizer`:
    - Phản hồi 1 ý ngắn (xin lỗi ngắn, tôn trọng khoảng nghỉ `"Để xem đã"`, xác nhận đặt lịch) = giữ 1 bubble duy nhất, không cắt câu giữa chừng.
    - Phản hồi có 2 ý độc lập (Bubble 1: chào/đồng cảm + báo giá hoặc so sánh gói; Bubble 2: câu hỏi mở khám phá nhu cầu/lựa chọn) = tách 2 bubble theo ranh giới ý nghĩa, loại bỏ lời chào/đồng cảm lặp ở đầu Bubble 2, và đảm bảo toàn lượt có tối đa 1 dấu `?` ở cuối bubble cuối cùng.

### 4.3. Wave 3 — `P2`: Reasoning & Memory Integrity
- **3.1 — Khôi phục `ThinkingMode` theo chính sách trong `OpenAiCompatibleLlmClient`:**
  - Khi `thinkingMode == FAST_NO_THINK` (`thinkEnabled() == false`): gửi `enable_thinking = false`, `chat_template_kwargs = {"enable_thinking": false}`, `reasoning_effort = "none"`, `max_tokens = 2500`.
  - Khi `thinkingMode == OBJECTION_REFRAME_THINK` (`thinkEnabled() == true`): gửi `enable_thinking = true`, `chat_template_kwargs = {"enable_thinking": true}`, `reasoning_effort = "low"`, `max_tokens = 6400`.
  - Bổ sung cơ chế an toàn: nếu retry do `BadRequest` hoặc `emptyContentWithReasoning()`, tự động ép `enable_thinking = false`, `reasoning_effort = "none"`, `max_tokens = 2500` để đảm bảo luôn trả về JSON hợp lệ.
- **3.2 — Sửa vòng đời `unresolved_requests` trong `AgentBrainService`:**
  - Không đóng request chỉ dựa trên tên `Speech Act`.
  - Đối chiếu trực tiếp với văn bản phản hồi thực tế của bot (`actualAssistantReplyText`): yêu cầu hỏi giá chỉ chuyển sang `ANSWERED` khi phản hồi đã nêu mức giá (`doesAssistantReplyAnswerPriceInquiry`); yêu cầu so sánh gói chỉ chuyển sang `ANSWERED` khi phản hồi đã nêu hai gói/mức giá và giải thích điểm khác biệt về phạm vi/thời lượng/hạng mục (`doesAssistantReplyExplainPackageComparison`).
- **3.3 — Kiểm soát chặt `confirmed_facts`:**
  - Chặn toàn bộ các mảnh từ cảm thán/khẩu ngữ cắt cụt (`"chết"`, `"ngại chết"`, `"quá"`, `"lắm"`) và các nhãn suy diễn mơ hồ (`"đáp ứng mơ hồ"`, `"phản ứng mơ hồ"`, `"chưa rõ"`) tại `isInvalidOrTruncatedFactValue()`.
  - Làm sạch hậu tố cảm thán `" chết"` khỏi triệu chứng (`"mệt chết"` -> `"mệt"`, `"ngại chết"` -> loại bỏ).
  - Bắt buộc kiểm tra `hasGenuinePriceObjectionCue(originalCustomerMessage)` trước khi ghi nhận `price_reaction`.

---

## 5. Kết Quả Unit Tests, Integration Tests & Regression Tests

Lệnh kiểm thử đã chạy:
```powershell
Set-Location "d:\Project\digital-spirituality\docmind\api-java"
mvn test "-Dtest=PostCp3Wave3ReasoningAndMemoryTest,PostCp3Wave2PriceAndBubblesTest,PostCp3Wave1CustomerUnderstandingTest,OpenAiCompatibleLlmClientTest,P38ForensicRegressionTest,P36ConversationIntelligenceVerificationTest,Sbr01*Test"
```

| Bộ kiểm thử | Số test | Failures | Errors | Skipped | Trạng thái |
| :--- | :---: | :---: | :---: | :---: | :---: |
| `PostCp3Wave1CustomerUnderstandingTest` | 8 | 0 | 0 | 0 | **PASS** |
| `PostCp3Wave2PriceAndBubblesTest` | 6 | 0 | 0 | 0 | **PASS** |
| `PostCp3Wave3ReasoningAndMemoryTest` | 3 | 0 | 0 | 0 | **PASS** |
| `OpenAiCompatibleLlmClientTest` | 24 | 0 | 0 | 0 | **PASS** |
| `P36ConversationIntelligenceVerificationTest` | 8 | 0 | 0 | 0 | **PASS** |
| `P38ForensicRegressionTest` | 45 | 0 | 0 | 0 | **PASS** |
| `Sbr01*Test` (Fix0–Fix5, Prompt Sync, Replay Harness) | 53 | 0 | 0 | 4 | **PASS** |
| **TỔNG CỘNG** | **147** | **0** | **0** | **4** | **BUILD SUCCESS** |

---

## 6. Bảng Đối Chiếu Before / After Trên Kịch Bản Chuẩn 5 Lượt (`T1`, `T3`, `T5`, `T7`, `T9`)

- **Phiên Before (Baseline `304ff580`):** `fb-customer-audit-postcp3-25d1f78c` ([controlled_test_trace.json](file:///C:/Users/Lecoo/.gemini/antigravity/brain/e28df741-8f7d-43e3-accf-a481b29616a3/scratch/controlled_test_trace.json))
- **Phiên After (Sau Wave 1–3 `b60be274`):** `fb-customer-after-postcp3-9db40d34` ([after_remediation_5turn_trace.json](file:///C:/Users/Lecoo/.gemini/antigravity/brain/e28df741-8f7d-43e3-accf-a481b29616a3/evidence/after_remediation_5turn_trace.json))

| Lượt & Tin nhắn khách | Before (`304ff580`) | After (`b60be274` — Sau Wave 1–3) | Các chỉ số kỹ thuật cải thiện (`FACT`) |
| :--- | :--- | :--- | :--- |
| **`T1`**: `"Giá"` | **Bubble 1:** `"Chào mình ạ 🌷 Rất vui được đón tiếp mình tại Nàng Sen! Bên em có Gói Massage cơ bản 60 phút giá 399k và Gói Massage phục hồi + dưỡng sinh 120 phút giá 670k ạ."`<br>**Bubble 2:** `"Đây là vài gói tiêu biểu, bên em còn nhiều gói khác theo nhu cầu. Mình quan tâm gói nào ạ?"`<br>*(Bỏ sót gói khởi điểm `239k`; hỏi khách chọn gói ngay khi chưa biết nhu cầu)* | **Bubble 1:** `"Nàng Sen chào mình ạ 🌷 Rất vui vì hôm nay mình ghé qua đây. Gói dưỡng sinh cơ bản 60 phút khởi điểm từ 239k, Massage cơ bản 60 phút từ 399k, Massage phục hồi + dưỡng sinh 120 phút từ 670k. Đây là vài gói tiêu biểu, bên em còn các gói khác phù hợp nhiều nhu cầu hơn ạ."`<br>**Bubble 2:** `"Mình đang muốn thư giãn vùng nào hay mong muốn trải nghiệm như thế nào ạ?"` | - Có mặt giá khởi điểm `239k` + `399k` + `670k` từ Catalog.<br>- Bubble 2 hỏi khám phá nhu cầu/vùng mỏi thay vì bắt chọn gói.<br>- `unresolved_requests`: `[{"item": "giá gói", "status": "ANSWERED"}]`. |
| **`T3`**: `"Tôi hoa mắt chóng mặt tê chân tay"` | **Bubble 1:** `"Em rất tiếc khi nghe mình đang cảm thấy hoa mắt, chóng mặt và tê chân tay ạ. Các triệu chứng này liên quan đến sức khỏe, trong khi liệu trình bên em chỉ hỗ trợ thư giãn cơ thể chứ không thay thế thăm khám hay điều trị y khoa đâu ạ. Mình đang muốn thư giãn vùng nào trên cơ thể nhất ạ?"`<br>*(Gộp cả đồng cảm, ranh giới y khoa và câu hỏi vào 1 bong bóng 54 từ)* | **Bubble 1:** `"Em hiểu mình đang cảm thấy hoa mắt, chóng mặt và tê chân tay, thật không dễ dàng ạ. Các liệu trình tại Nàng sen hướng tới chăm sóc thư giãn cơ thể, không thay thế thăm khám hay điều trị y khoa đối với các triệu chứng thần kinh hay mạch máu ạ."`<br>**Bubble 2:** `"Mình đang muốn ưu tiên thư giãn vùng cơ thể nào nhất ạ?"` | - Giữ nghiêm ranh giới y khoa (`priority=SAFETY`).<br>- Tách thành 2 bong bóng theo đơn vị ý nghĩa (Bubble 1: đồng cảm + ranh giới an toàn; Bubble 2: câu hỏi vùng ưu tiên).<br>- Không biến triệu chứng thần kinh thành mục tiêu điều trị trong `confirmed_facts`. |
| **`T5`**: `"Đầu và vai gáy"` | **Bubble 1:** `"Dạ em ghi nhận mình muốn chăm sóc vùng đầu và vai gáy ạ. Mình có thể tham khảo Gói dưỡng sinh cơ bản 60 phút giá 239k, gồm gội đầu cơ bản và massage vai gáy nhẹ nhàng thư giãn. Mình thấy gói này phù hợp với nhu cầu của mình chưa ạ?"`<br>*(Vẫn chọn `discovery_empathy_and_scope`; Sanitizer cắt mất chữ `"cổ"` trong `"massage cổ vai gáy"`; 1 bong bóng duy nhất)* | **Bubble 1:** `"Em ghi nhận mình muốn ưu tiên thư giãn vùng đầu và vai gáy ạ. Các triệu chứng mình đang gặp là điều em cần lưu ý để kỹ thuật viên chăm sóc nhẹ nhàng, phù hợp nhất ạ."`<br>**Bubble 2:** `"Mình có thể tham khảo Gói dưỡng sinh cao cấp 80 phút 339k tại chi nhánh Hà Nội, hoặc Gói dưỡng sinh cao cấp 80 phút 370k tại chi nhánh Hồ Chí Minh ạ. Gói này gồm gội đầu cao cấp kết hợp massage chăm sóc chuyên sâu vùng vai gáy, giúp thư giãn và giảm căng mỏi ạ. Mình muốn trải nghiệm gói này hay muốn tìm hiểu thêm về thời lượng gói khác ạ?"` | - Nhận diện xác nhận vùng theo ngữ cảnh, thoát khỏi `discovery_empathy_and_scope` (`activatedSkills=[]`, `selectedSpeechAct=Soft Recommendation`).<br>- `confirmed_facts`: `[need_scope = "đầu và vai gáy"]`.<br>- Tách 2 bong bóng rõ ràng. |
| **`T7`**: `"Đắt thế, gói 239k với 399k khác nhau thế nào?"` | **Bubble 1:** `"Em hiểu mình băn khoăn về chi phí ạ. Gói dưỡng sinh cơ bản 60 phút 239k gồm gội đầu cơ bản và massage vai gáy, nhẹ nhàng thư giãn. Gói Massage cơ bản 60 phút 399k là trị liệu toàn thân, giúp giảm đau mỏi toàn thân hơn. Mình muốn trải nghiệm hướng nào ạ?"`<br>*(Bỏ sót skill `package_comparison_and_scope_clarity`; cắt chữ `"cổ"`; `reasoningEffort=none`, `reasoningTokens=0`; dồn 4 câu vào 1 bong bóng; `unresolved_requests` vẫn treo `OPEN`)* | **Bubble 1:** `"Dạ em hiểu mình cảm thấy chi phí hơi cao ạ. Gói 239k là dưỡng sinh cơ bản 60 phút, tập trung gội đầu và massage cổ vai gáy. Gói 399k là massage cơ bản 60 phút, chăm sóc toàn thân, giúp giảm đau mỏi gân cốt rộng hơn. Cả hai đều phòng chung yên tĩnh, rèm riêng kín đáo ạ."`<br>**Bubble 2:** `"Mình nghiêng về trải nghiệm gói nào hơn ạ?"` | - Kích hoạt đồng thời **cả 2 skills**: `[price_objection_sensory_value, package_comparison_and_scope_clarity]`.<br>- Giữ nguyên vẹn `"massage cổ vai gáy"` (`sanitizerMutationRate = 0.000`).<br>- Bật `OBJECTION_REFRAME_THINK` thực sự: `reasoningEffort=low`, `maxTokens=6400`, **`reasoningTokens=5117`**.<br>- Tách 2 bong bóng tự nhiên.<br>- Đóng `unresolved_requests`: `"so sánh gói 239k 399k" -> ANSWERED`. |
| **`T9`**: `"Ơ, dở hơi à"` | **Bubble 1:** `"Em hiểu mình băn khoăn ạ."`<br>*(Xung đột Split-Brain: Pre-LLM ra lệnh `SERVICE_RECOMMENDATION`, Post-LLM cắt 86,3% câu trả lời `sanitizerMutationRate = 0.863`; ghi đè `price_reaction = "đáp ứng mơ hồ"`)* | **Bubble 1:** `"Em xin lỗi vì cách tư vấn vừa rồi chưa phù hợp ạ. Em sẽ không gợi ý thêm và không hỏi gì nữa ạ."` | - Đồng bộ Pre-LLM & Post-LLM: `detected_intent = PROCESS_FEEDBACK`, `negative_subtype = COMMUNICATION_COMPLAINT`, `priority = SERVICE_RECOVERY`.<br>- **`sanitizerMutationRate = 0.000`** (giảm từ `0.863` về `0.000`).<br>- Bảo toàn nguyên vẹn `confirmed_facts`, không ghi `"đáp ứng mơ hồ"` hay `"chết"`. |

---

## 7. Kết Quả Bộ Kiểm Thử Nghiệm Thu Tổng Hợp 10 Tình Huống (`S1`–`S10`)

Toàn bộ dữ liệu trace chi tiết được lưu tại [after_remediation_10_scenarios.json](file:///C:/Users/Lecoo/.gemini/antigravity/brain/e28df741-8f7d-43e3-accf-a481b29616a3/evidence/after_remediation_10_scenarios.json).

| Mã | Tình huống kiểm thử | Đầu vào của khách | Phản hồi thực tế sau khắc phục (`reply_bubbles`) | Kết quả |
| :--- | :--- | :--- | :--- | :---: |
| **S1** | Hỏi giá chung mở đầu | `"Giá"` | **B1:** `"Nàng Sen chào mình ạ 🌷 Rất vui vì hôm nay mình ghé qua đây. Gói dưỡng sinh cơ bản 60 phút khởi điểm từ 239k, Massage cơ bản 60 phút từ 399k, Massage phục hồi + dưỡng sinh 120 phút từ 670k. Đây là vài gói tiêu biểu, bên em còn các gói khác phù hợp nhiều nhu cầu hơn ạ."`<br>**B2:** `"Mình đang muốn thư giãn vùng nào hay mong muốn trải nghiệm như thế nào ạ?"` | **ĐẠT** |
| **S2** | Nêu triệu chứng sức khỏe thần kinh/mạch máu | `"Tôi hoa mắt chóng mặt tê chân tay"` | **B1:** `"Em hiểu mình đang cảm thấy hoa mắt, chóng mặt và tê chân tay, thật không dễ dàng ạ. Các liệu trình tại Nàng sen hướng tới chăm sóc thư giãn cơ thể, không thay thế thăm khám hay điều trị y khoa đối với các triệu chứng thần kinh hay mạch máu ạ."`<br>**B2:** `"Mình đang muốn ưu tiên thư giãn vùng cơ thể nào nhất ạ?"` | **ĐẠT** |
| **S3** | Trả lời ngắn xác nhận vùng cơ thể | `"Đầu và vai gáy"` (sau khi bot hỏi vùng ở S2) | **B1:** `"Em ghi nhận mình muốn ưu tiên thư giãn vùng đầu và vai gáy ạ. Các triệu chứng mình đang gặp là điều em cần lưu ý để kỹ thuật viên chăm sóc nhẹ nhàng, phù hợp nhất ạ."`<br>**B2:** `"Mình có thể tham khảo Gói dưỡng sinh cao cấp 80 phút 339k tại chi nhánh Hà Nội, hoặc Gói dưỡng sinh cao cấp 80 phút 370k tại chi nhánh Hồ Chí Minh ạ. Gói này gồm gội đầu cao cấp kết hợp massage chăm sóc chuyên sâu vùng vai gáy, giúp thư giãn và giảm căng mỏi ạ. Mình muốn trải nghiệm gói này hay muốn tìm hiểu thêm về thời lượng gói khác ạ?"` | **ĐẠT** |
| **S4** | Trả lời ngắn `"Cả hai"` sau khi bot hỏi vùng | T1: `"Mình bị mỏi đầu và vai gáy quá"` -> T2: `"Cả hai"` | **B1:** `"Em hiểu mình muốn chăm sóc cả hai vùng đầu và vai gáy ạ. Gói dưỡng sinh cao cấp 80 phút — 339k, gồm gội đầu cao cấp + massage chăm sóc chuyên sâu cổ vai gáy, sẽ rất phù hợp với nhu cầu của mình ạ."`<br>**B2:** `"Mình muốn trải nghiệm gói này hay muốn tìm hiểu thêm về thời lượng và hạng mục khác ạ?"` | **ĐẠT** |
| **S5** | Mixed intent: vừa chê đắt vừa hỏi so sánh 2 gói | `"Đắt thế, gói 239k với 399k khác nhau thế nào?"` | **B1:** `"Dạ em hiểu mình cảm thấy chi phí hơi cao ạ. Gói 239k là dưỡng sinh cơ bản 60 phút, tập trung gội đầu và massage cổ vai gáy. Gói 399k là massage cơ bản 60 phút, chăm sóc toàn thân, giúp giảm đau mỏi gân cốt rộng hơn. Cả hai đều phòng chung yên tĩnh, rèm riêng kín đáo ạ."`<br>**B2:** `"Mình nghiêng về trải nghiệm gói nào hơn ạ?"` | **ĐẠT** |
| **S6** | Hỏi trực tiếp sự khác nhau giữa 2 gói (không chê đắt) | `"Gói 239k với 399k khác nhau thế nào ạ?"` | **B1:** `"Gói dưỡng sinh cơ bản 60 phút 239k gồm gội đầu cơ bản + massage cổ vai gáy, vùng chăm sóc đầu cổ vai gáy. Gói Massage cơ bản 60 phút 399k là massage trị liệu toàn thân 60 phút giúp giảm đau mỏi, thư giãn gân cốt, kèm ăn nhẹ thực dưỡng."`<br>**B2:** `"Mình muốn chăm sóc vùng đầu cổ vai hay toàn thân ạ?"` | **ĐẠT** |
| **S7** | Phản ứng gay gắt đời thường | `"Ơ, dở hơi à"` | **B1:** `"Em xin lỗi vì cách tư vấn vừa rồi chưa phù hợp ạ. Em sẽ không gợi ý thêm và không hỏi gì nữa ạ."` (`sanitizerMutationRate = 0.000`, không pitch gói, không ghi `price_reaction` rác) | **ĐẠT** |
| **S8** | Phàn nàn bot hỏi lặp / trả lời lòng vòng | `"Sao em cứ hỏi đi hỏi lại lòng vòng thế, trả lời thẳng vào trọng tâm đi"` | **B1:** `"Dạ em thành thật xin lỗi mình vì trải nghiệm chưa trọn vẹn và phản hồi vừa rồi bị gián đoạn ạ. Để bên em điều chỉnh ngay cách trao đổi và kết nối bộ phận phụ trách hỗ trợ chu đáo nhất cho mình nhé ạ."` | **ĐẠT** |
| **S9** | Trả lời lưỡng lự / tạm dừng | `"Để xem đã"` | **B1:** `"Dạ em ghi nhận mình muốn cân nhắc thêm ạ. Khi mình đã quyết định, em luôn sẵn sàng hỗ trợ ạ 🌷"` (1 bong bóng ấm áp, 0 câu hỏi chèo kéo) | **ĐẠT** |
| **S10** | Đi trọn phễu: Hỏi giá -> Chia sẻ vùng mỏi -> Đặt lịch | T1: `"Giá"` -> T2: `"Mình mỏi cổ vai gáy"` -> T3: `"Đặt lịch cho chị gói 239k lúc 19h tối nay nhé, sđt 0912345678"` | **B1:** `"Dạ em đã ghi nhận: Gói dưỡng sinh cơ bản 60 phút — 239k, tối nay lúc 19h, số điện thoại 0912345678 ạ. Em sẽ hỗ trợ giữ lịch tạm chờ kiểm tra lịch trống thực tế tại cơ sở nhé."` (Ghi nhận đúng gói, giờ, SĐT và giữ lịch tạm chờ kiểm tra chỗ trống thực tế) | **ĐẠT** |

---

## 8. Số Liệu Đo Thực Tế Cho `ThinkingMode` Trên Endpoint Qwen

Bằng chứng JSON chi tiết được lưu tại [wave3_live_qwen_thinking_comparison.json](file:///C:/Users/Lecoo/.gemini/antigravity/brain/e28df741-8f7d-43e3-accf-a481b29616a3/evidence/wave3_live_qwen_thinking_comparison.json) và log `[LLM]` trong [after_remediation_5turn_trace.json](file:///C:/Users/Lecoo/.gemini/antigravity/brain/e28df741-8f7d-43e3-accf-a481b29616a3/evidence/after_remediation_5turn_trace.json):

| Chế độ (`ThinkingMode`) | Cấu hình gửi lên `https://api.vilao.ai/v1/chat/completions` (`model=qwen3.8-max`) | `prompt_tokens` | `reasoning_tokens` | `completion_tokens` | `has_reasoning_content` | `latency_ms` |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: |
| **`FAST_NO_THINK`** (Đo trực tiếp & Lượt `T1`, `T3`, `T5`, `T9`) | `enable_thinking=false`, `chat_template_kwargs={"enable_thinking":false}`, `reasoning_effort="none"`, `max_tokens=2500` | `5,722` (độc lập) / `12,304–13,490` (trên luồng bot) | **`0`** | `450–573` | `false` | `4,681–9,078 ms` |
| **`OBJECTION_REFRAME_THINK`** (Đo trực tiếp & Lượt `T7`) | `enable_thinking=true`, `chat_template_kwargs={"enable_thinking":true}`, `reasoning_effort="low"`, `max_tokens=6400` | `5,735` (độc lập) / `13,563` (trên luồng bot `T7`) | **`5,791`** (độc lập) / **`5,117`** (trên luồng bot `T7`) | `6,267` / `5,844` | `true` | `34,323–44,433 ms` |

---

## 9. Lịch Sử Các Vấn Đề Do Qwen CLI Và Claude CLI Phát Hiện Cùng Cách AGY Đã Xử Lý

| Wave | Auditor | Verdict ban đầu | Phát hiện của Auditor | Cách AGY đã xử lý & xác minh lại |
| :--- | :--- | :--- | :--- | :--- |
| **Wave 1** | **Qwen CLI** ([wave1_qwen_audit.md](file:///C:/Users/Lecoo/.gemini/antigravity/brain/e28df741-8f7d-43e3-accf-a481b29616a3/evidence/wave1_qwen_audit.md)) & **Claude CLI** ([wave1_claude_audit.md](file:///C:/Users/Lecoo/.gemini/antigravity/brain/e28df741-8f7d-43e3-accf-a481b29616a3/evidence/wave1_claude_audit.md)) | `PASS_WITH_FIXES` (`0 P0, 0 P1`) | Ghi nhận P2 về việc chuẩn hóa khoảng trắng/dấu câu trong `isContextualShortBodyRegionScopeReply` và kiểm tra biên khi tên gói có chứa `"massage cổ vai gáy"`. | Đã khóa chặt kiểm tra ngữ cảnh câu hỏi trước đó và bổ sung unit test trong `PostCp3Wave1CustomerUnderstandingTest` (`8/8 PASS`). |
| **Wave 2** | **Qwen CLI** ([wave2_qwen_audit.md](file:///C:/Users/Lecoo/.gemini/antigravity/brain/e28df741-8f7d-43e3-accf-a481b29616a3/evidence/wave2_qwen_audit.md)) & **Claude CLI** ([wave2_claude_audit.md](file:///C:/Users/Lecoo/.gemini/antigravity/brain/e28df741-8f7d-43e3-accf-a481b29616a3/evidence/wave2_claude_audit.md)) | Qwen: `PASS_WITH_FIXES` (`0 P0, 1 P1`); Claude: `PASS` (`0 P0, 0 P1`) | **P1-1 (Qwen CLI):** Nếu LLM gộp cả báo giá (`399k`, `670k`) và câu hỏi chọn gói (`"bạn chọn gói nào?"`) vào **cùng 1 câu duy nhất**, bước viết lại câu hỏi chọn gói ở dòng `7817` xóa mất mức giá trước khi khối chèn `starting_package_anchor` (`239k`) ở dòng `7836` kiểm tra `hasQuotedPriceInText`. | Đã sửa ngay trong `CleanAgentOutputRefereeAndSanitizer.java` (commit `ff55078ba481ab67532f40eaa92bf314102de9ac`): tính `hadQuotedPriceBeforeRewrite` trước khi viết lại câu hỏi chọn gói và bổ sung test hồi quy cho trường hợp 1 câu gộp (`6/6 PASS`). |
| **Wave 3** | **Qwen CLI** ([wave3_qwen_audit.md](file:///C:/Users/Lecoo/.gemini/antigravity/brain/e28df741-8f7d-43e3-accf-a481b29616a3/evidence/wave3_qwen_audit.md)) & **Claude CLI** ([wave3_claude_audit.md](file:///C:/Users/Lecoo/.gemini/antigravity/brain/e28df741-8f7d-43e3-accf-a481b29616a3/evidence/wave3_claude_audit.md)) | Qwen: `PASS_WITH_FIXES` (`0 P0, 0 P1, 2 P2`); Claude: `PASS` (`0 P0, 0 P1, 2 P2`) | **P2-1 (Qwen CLI):** Nhánh retry `emptyContentWithReasoning()` tại `OpenAiCompatibleLlmClient.java:253` chưa ép `enable_thinking=false` và `reasoning_effort="none"` cho Qwen giống nhánh `BadRequest`.<br>**P2-2 (Qwen & Claude CLI):** Cập nhật comment tại `CleanAgentOrchestratorService.java:7372` và đồng bộ `maxTokens=6400` trong `PostCp3Wave3ReasoningAndMemoryTest`. | Đã sửa cả 3 điểm trong commit `b60be274e795bc851ff512508cb8636ffca5f2eb` và chạy lại toàn bộ test (`35/35` trong Wave 3 suite, `147/147` toàn bộ suite — `BUILD SUCCESS`). |

---

## 10. Xác Nhận Negative Control Không Bị Tác Động

Kết quả truy vấn trực tiếp trên PostgreSQL (`docmind-postgres`) đối với trang Negative Control **“Thẩm mỹ viện”** (`page_id = 59e68078-8327-4aa0-8cca-91410f783ad4`):
```json
{"skills_count" : 0, "docs_count" : 0, "sessions_count" : 2}
```
- **Không** thêm/sửa/xóa bất kỳ `agent_skills`, `documents`, `persona_config` hay migration nào trên trang “Thẩm mỹ viện”.
- **Không** tạo thêm bất kỳ `conversation_sessions` mới nào trên trang “Thẩm mỹ viện” (`sessions_count = 2` giữ nguyên như trước khi thực hiện).
- Toàn bộ thay đổi trong Shared Core (`AdaptiveConversationIntelligence`, `CleanAgentOrchestratorService`, `CleanAgentOutputRefereeAndSanitizer`, `CleanAgentCatalogAndPromptBuilder`, `AgentBrainService`, `OpenAiCompatibleLlmClient`) đều đọc dữ liệu động từ Catalog của từng tenant, không hardcode tên gói hay mức giá của Nàng Sen vào mã nguồn xử lý.

---

## 11. Hướng Dẫn Rollback An Toàn Nếu Cần

Toàn bộ công việc được thực hiện cô lập trên nhánh `fix/post-cp3-quality-remediation` và **chưa merge** vào `refactor/aci-v2-cleanup` hay `master`.

Nếu cần hoàn nguyên về đúng trạng thái trước khi thực thi:
1. **Hoàn nguyên toàn bộ nhánh về Baseline (`304ff580e664ce079a17242a760a137e98102503`):**
   ```powershell
   Set-Location "d:\Project\digital-spirituality\docmind"
   git checkout refactor/aci-v2-cleanup
   docker compose up -d --build api-java
   ```
2. **Hoàn nguyên từng Wave riêng lẻ trên nhánh `fix/post-cp3-quality-remediation`:**
   - Hoàn nguyên chỉ Wave 3 (`b60be274`): `git revert b60be274e795bc851ff512508cb8636ffca5f2eb`
   - Hoàn nguyên chỉ Wave 2 (`ff55078b`): `git revert ff55078ba481ab67532f40eaa92bf314102de9ac`
   - Hoàn nguyên chỉ Wave 1 (`013253bb`): `git revert 013253bb63805e91c41bafba75a87b5e38cd0892`
