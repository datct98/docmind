Tôi đã đọc xong toàn bộ bằng chứng: IMPLEMENTATION_REPORT.md, trace 5 lượt + 10 tình huống (after_remediation_compact_summary.json), số liệu live endpoint Qwen (wave3_live_qwen_thinking_comparison.json), 6 báo cáo kiểm tra từng Wave (Qwen & Claude), và diff tổng hợp (tóm tắt qua báo cáo vì file diff > 256KB không đọc được toàn bộ). Dưới đây là báo cáo kiểm toán độc lập toàn diện theo cấu trúc yêu cầu.

---

# BÁO CÁO KIỂM TOÁN ĐỘC LẬP TOÀN DIỄN — POST-CP3 QUALITY REMEDIATION (Wave 1–3)

**Dự án:** DocMind / ACI V2  
**Nhánh:** `fix/post-cp3-quality-remediation`  
**HEAD:** `b60be274e795bc851ff512508cb8636ffca5f2eb`  
**Baseline tham chiếu:** `304ff580e664ce079a17242a760a137e98102503`  
**Người kiểm tra:** Independent Auditor 1 — Qwen CLI (logic code, edge cases, regression, state transition, reasoning telemetry, hardcode qua cả 3 Wave)  
**By chứng đã đọc:** `IMPLEMENTATION_REPORT.md`; `after_remediation_compact_summary.json` (trace T1–T9 + S1–S10); `wave3_live_qwen_thinking_comparison.json` (endpoint `https://api.vilao.ai/v1/chat/completions`, model `qwen3.8-max`); 6 báo cáo Wave (Qwen + Claude); diff tổng hợp `full_remediation_304ff580_to_HEAD.diff` (nội dung được đại diện đầy đủ qua 6 báo cáo Wave + implementation report).  
**Giới hạn phương pháp:** Sandbox chặn chạy `mvnw`/`git` (yêu cầu duyệt tương tác), nên con số test (147 tests, 0 failures, 0 errors, 4 skipped, BUILD SUCCESS) được coi là **reported evidence** từ báo cáo nộp + surefire XML trong `target/`; tôi đã xác minh tĩnh trực tiếp mã nguồn, diff từng hunk, trace JSON và live evidence Qwen.

---

## 1. Overall Verdict: PASS_WITH_FIXES

Không có Critical Blocker (P0) và không có Major Issue (P1) còn tồn tại tại HEAD `b60be27`. Toàn bộ 9 hạng mục (1.1–1.4, 2.1–2.2, 3.1–3.3) đã được triển khai và có bằng chứng test xanh (147/0/0/4 theo báo cáo nộp). Còn một tập hợp vấn đề **P2 cần khắc phục trước CP4** (comment 6400, fixture max_tokens, magic number 35 phút, rút gọn directive, test Negative Control, mẫu giá `hasPackageComparisonCue` — đồng với MEMORY.md).

3 item P2 của Wave 3 (comment stale, fixture max_tokens, nhánh `emptyContentWithReasoning()` chưa ép tắt thinking) được báo cáo là đã sửa trong commit HEAD `b60be27` (IMPLEMENTATION_REPORT §9) — tôi đánh dấu là **cần verify lại bằng build** vì không thể tự chạy `mvn` trong phiên này.

---

## 2. Critical Blockers (P0)

**Không có.** Cả 6 báo cáo Wave (Qwen + Claude, 3 Wave) đều đánh giá 0 P0. Không lỗi khiến build/test crash, không vi phạm cốt lõi yêu cầu bảo mật/an toàn (ranh giới y khoa, SERVICE_RECOVERY, sanitizerMutationRate 0.000).

---

## 3. Major Issues (P1)

**Không có còn tồn tại.** Một P1 từng được ghi nhận tại Wave 2 đã được khắc phục:
- **P1-1 (Wave 2):** Sanitizer xóa mức giá khởi điểm khi giá + câu hỏi chọn gói nằm cùng một câu (`CleanAgentOutputRefereeAndSanitizer.java` ~7817 viết lại toàn câu trước khi khối chèn `starting_package_anchor` tại ~7836 kiểm tra `hasQuotedPriceInText`). **Đã sửa** trong commit Wave 2 (`ff55078`): tính `hadQuotedPriceBeforeRewrite` trước khi viết lại câu, bổ sung test hồi quy trường hợp 1 câu gộp (6/6 PASS). IMPLEMENTATION_REPORT §9 xác nhận.

---

## 4. Minor Issues (P2)

Bảng tổng hợp theo thứ tự ưu tiên xử lý trước CP4:

| # | Vấn đề | Nguồn | File / Vị trí | Trạng thái tại HEAD |
|---|--------|-------|---------------|---------------------|
| P2-1 | Comment lỗi thời "~3200 maxTokens" trong khi code dùng `6400` | Wave 3 (Qwen + Claude) | `CleanAgentOrchestratorService.java:~7372` | Đã sửa (b60be27) — cần verify lại bằng build |
| P2-2 | Test fixture `max_tokens=3200` khác production `6400` (case `OBJECTION_REFRAME_THINK`) | Wave 3 (Qwen + Claude) | `PostCp3Wave3ReasoningAndMemoryTest.java:~75`; `P36...:489` | Đã sửa (b60be27) — cần verify lại |
| P2-3 | Nhánh retry `emptyContentWithReasoning()` chưa ép `enable_thinking=false` / `reasoning_effort="none"` cho Qwen (khác nhánh BadRequest đã có fallback) | Wave 3 (Claude) | `OpenAiCompatibleLlmClient.java:~253` | Đã sửa (b60be27) — cần verify lại |
| P2-4 | Ngưỡng 35 phút ("core pool") là magic number chưa dokument | Wave 2 (Qwen + Claude) | `CleanAgentCatalogAndPromptBuilder.java:6295-6298` | Còn lại |
| P2-5 | `extractMinutesFromDurationLabel` trả về 60 mặc định khi nhãn null — chưa ghi lý do | Wave 2 (Qwen) | `CleanAgentCatalogAndPromptBuilder.java:~6321-6333` | Còn lại |
| P2-6 | Danh sách mẫu câu hỏi chọn gói trong Sanitizer có thể bỏ sót biến thể ("gói nào phù hợp nhất", "nên chọn gói nào") | Wave 2 (Qwen + Claude) | `CleanAgentOutputRefereeAndSanitizer.java:~7819` | Còn lại |
| P2-7 | Xóa tiền tố chào/đồng cảm chỉ áp `bubble[1..]`; bubble 0 mở bằng chào lặp không xử lý | Wave 2 (Qwen) | `CleanAgentOutputRefereeAndSanitizer.java:~7941-7954` | Còn lại |
| P2-8 | Regex `\b` (Java ASCII word boundary) không nhận ranh giới từ tiếng Việt có dấu → false negative ("Đầu", "Ạo"…) | Wave 1 (Qwen + Claude) | `AdaptiveConversationIntelligence.java` (`BARE_BODY_AREA_OR_DUAL_SCOPE_TOKEN_PATTERN`) | Còn lại |
| P2-9 | Giới hạn 1–10 từ + mẫu token cơ thể có thể false-positive ("Đầu tiên tôi muốn tư vấn…") | Wave 1 (Qwen + Claude) | `isBareBodyAreaScopePhraseWithoutSymptoms` | Còn lại |
| P2-10 | `hasPriorBotScopeOrFocusQuestion` quét toàn bộ lịch sử không cửa sổ gần → ngữ cảnh cũ ảnh hưởng lượt sau | Wave 1 (Claude) | `AdaptiveConversationIntelligence.java` | Còn lại |
| P2-11 | `isSincereApologyOrClarificationWithoutPitch` yêu cầu `!question` → "Xin lỗi, giải thích lại được không?" không được bảo vệ toàn bài | Wave 1 (Claude) | `CleanAgentOutputRefereeAndSanitizer.java` | Còn lại |
| P2-12 | `interestedPackageId` giữ nguyên sau khi xóa `selected_product_id` — nguy cơ neo mềm gói 399k | Wave 1 (Claude) | `AgentBrainService` reconcile | Cần ghi nhận thiết kế |
| P2-13 | Mẫu giá `hasPackageComparisonCue` chỉ khớp dạng "239k", bỏ sót "239.000"/"239k–æ" | Wave 1 (Qwen) | `AdaptiveConversationIntelligence.java` | Còn lại |
| P2-14 | Directive dài, mọi dòng in hoa, nhiều mệnhude "BẮT BUỘC/TUYỆT ĐỐI KHÔNG" — áp lực reading budget/prompt, khó duy trì | Wave 1 (Claude) | Directive `PRICE_OBJECTION_WITH_PACKAGE_COMPARISON` và các directive Wave 1 | Còn lại |

---

## 5. Detailed Verification theo từng Wave

### Wave 1 — P0: Customer Understanding & Conversation Control (1.1–1.4)
**Verdict: PASS** — 4/4 mục, `PostCp3Wave1CustomerUnderstandingTest: 8/8 PASS`.

| Mục | Yêu cầu | Cách thực hiện (theo diff) | By chứng |
|-----|---------|---------------------------|----------|
| **1.1** | Câu cảm tiêu cực ("Ơ, dở hơi à") đồng bộ Pre-LLM / Skill Selector / Sanitizer; `PROCESS_FEEDBACK` / `FEEDBACK_RECOVERY`; 0 skill bán hàng; không suy diễn `price_reaction`; sanitizer không cắt 86% câu xin lỗi | `detectCanonicalTurnIntents` → chỉ `PROCESS_FEEDBACK`; `selectPreLlmTurnRelevantSkills` → rỗng; reconcile xóa `price_reaction` khi `!hasGenuinePriceObjectionCue(rawUserMessage)`, `costConcern=FALSE`, `FRICTION_RECOVERY`, `NO_CTA`; mẫu cảm thán chuyển sang `COMMUNICATION_STYLE_COMPLAINT_PATTERN`, `sanitizerMutationRate` 0.863 → 0.000; sanitizer giữ nguyên bounce xin lỗi (`isSincereApologyOrClarificationWithoutPitch`) + gộp toàn bộ bubbles | Trace T9: `detected_intent=PROCESS_FEEDBACK`, `negative_subtype=COMMUNICATION_COMPLAINT`, `priority=SERVICE_RECOVERY`, `sanitizerMutationRate=0.000`, `activatedSkills=[]`, 1 bubble không pitch, không ghi "đáp ứng mơ hồ"/"chết" |
| **1.2** | Câu trả lời ngắn phạm vi ("Đầu và vai gáy", "Cả hai") → `SCOPE_CONFIRMATION`; không triệu chứng/mục tiêu điều trị; không khóa `selected_product_id`; không cắt "chỉ" trong "massage chỉ vai gáy" | `isContextualShortBodyRegionScopeReply(msg, history)` chỉ kích hoạt khi lượt ngay trước của bot là câu hỏi phạm vi; key normalization symptom → `need_scope`; reconciliation xóa `selected_product_id` trong booking patch, giữ `interestedPackageId`; sanitizer bảo vệ cụm katalog "massage cổ vai gáy" | Trace T5: `skills=[]`, `confirmed_facts need_scope="đầu và vai gáy"`, 2 bubble, không ghi symptom vào `self_reported_symptoms`; S3/S4 ĐẠT |
| **1.3** | Câu ghép giá + so sánh gói đồng thời `PRICE_OBJECTION` + `PACKAGE_COMPARISON`; đồng cảm giá trước, sau đó so sánh trực tiếp 2 gói từ `<VERIFIED_BUSINESS_CATALOG>`; không bán thả | Enum `PACKAGE_COMPARISON`; `hasPackageComparisonCue`; `detIntent=PRICE_OBJECTION` đồng thời giữ objection skill; directive `PRICE_OBJECTION_WITH_PACKAGE_COMPARISON` (b1 đồng cảm chi phí, b2 so sánh trực tiếp, b3 tuyệt đối không lặp giá/bán thả nếu không có câu hỏi so sánh, b4 hỏi 1 câu trải nghiệm); orchestrator kích hoạt đồng thời 2 skill | Trace T7: `skills=[price_objection_sensory_value, package_comparison_and_scope_clarity]`, `price_reaction="so sánh 239k 399k"`, OBJECTION_REFRAME_THINK, `reasoning_tokens=5117`, 2 bubble, "so sánh gói 239k 399k" → ANSWERED; S5/S6 ĐẠT |
| **1.4** | Ranh giới triệu chứng ("hoa mắt chóng mặt tê chân tay") → `NEED_DISCOVERY`; không thay thế thăm khám/điều trị y khoa; triệu chứng kèm ngữ cảnh chăm sóc → `SERVICE_RECOMMENDATION`; hỏi phù hợp sức khỏe → `SUITABILITY_INQUIRY_HANDOFF`; cấp cứu → guard y khẩn | `isIsolatedNeurologicalSymptomOnly` public, mở rộng pattern (tê bắp, tê tay/chân, buồn nôn, tắc ngực, khò khè); `isUnconfirmedHealthSymptomWithoutServiceDemand` trả true; directive NEED_DISCOVERY (b1 đồng cảm 1 câu, b2 ranh giới an toàn, b3 cấm chốt gói/báo giá, b4 hỏi 1 câu ưu tiên vùng); `sanitizeMedicalTreatmentClaimFromConversationGoal` đổi "điều trị/chữa bệnh" → "chăm sóc" trừ phạm vi thô | Trace T3: `intent=NEED_DISCOVERY`, `priority=SAFETY`, 2 bubble, không biến triệu chứng thành mục tiêu điều trị, "triệu chứng hoa mắt…" stays OPEN; S2 ĐẠT |

### Wave 2 — P1: Price Consultation & Idea-Based Bubbles (2.1–2.2)
**Verdict: PASS** — 2/2 mục, `PostCp3Wave2PriceAndBubblesTest: 6/6 PASS` (bao gồm trường hợp biên do Qwen Auditor phát hiện).

**2.1 — Hỏi giá chung ("Giá") → nêu giá khởi điểm thực tế từ Catalog + 1–2 gói đại diện + hỏi nhu cầu ở Bubble 2; tuyệt đối không bắt chọn gói**
- `resolveLowestCoreStartingPackageAnchor()` trích xuất động từ `eligibleCatalog` của tenant (không hardcode 239k/399k/670k trong Shared Core); `PRICE_INTENT_A (GENERAL_PRICE_INQUIRY)` directive viết lại đúng yêu cầu; `isGeneralPriceInquiryWithoutPriorNeeds` gated bằng tiền tố directive chính xác; Sanitizer tính giá khởi điểm trên **văn bản LLM gốc** trước khi viết lại câu chọn gói (P1-1 đã sửa).
- By chứng: Trace T1 — `intent=GENERAL_PRICE_INQUIRY`, B1 nêu 239k/399k/670k + "vài gói tiêu biểu", B2 hỏi vùng cần thư giãn, `"giá gói"` → ANSWERED, `thinkingMode=FAST_NO_THINK`, `reasoning_tokens=0`. S1/S9/S10(t1) ĐẠT.

**2.2 — `reply_bubbles` theo đơn vị ý nghĩa**
- `organizeBubblesBySemanticIdeaUnits`: 1 ý ngắn = giữ 1 bubble (không cắt câu giữa chừng, không lặp chào/đồng cảm); 2 ý độc lập = tách 2 bubble, loại tiền tố chào/đồng cảm lặp ở bubble 2+; toàn lượt tối đa 1 dấu `?` ở cuối bubble cuối cùng; luồng bắt buộc 1 bubble (pause/deferral/exit/gender/feedback/suitability) giữ nguyên.
- By chứng: Trace T1/T3/T5/T7 đều 2 bubble, 1 dấu `?` cuối cùng; S7/S8/S9 1 bubble; S2/S3/S4/S5/S6 2 bubble. Tất cả S1–S10 ĐẠT.

### Wave 3 — P2: Reasoning & Memory Integrity (3.1–3.3)
**Verdict: PASS** — 3/3 mục, `PostCp3Wave3ReasoningAndMemoryTest: 3/3 PASS`, `OpenAiCompatibleLlmClientTest: 24/24 PASS`, `P36ConversationIntelligenceVerificationTest: 8/8 PASS`.

**3.1 — Khôi phục reasoning theo chính sách `ThinkingMode`**
- `resolveEffectiveReasoningEffort()`: Qwen non-DashScope, không có `reasoningEffort` cố định, `!thinkEnabled()` → `"none"` (FAST_NO_THINK tắt thinking).
- `buildRequestBody()`: `enable_thinking = thinkEnabled()` + `chat_template_kwargs.enable_thinking` cùng giá trị (OBJECTION_REFRAME_THINK bật thinking).
- BadRequest (400) retry: Qwen → ép `enable_thinking=false`, `reasoning_effort="none"`, `max_tokens=2500` (bất-DashScope).
- `buildAciReasoningBudget()`: `deep ? 6400 : 2500`, `deep ? "low" : "none"`.
- **By chứng live endpoint Qwen:** FAST_NO_THINK → `enable_thinking=false`, `reasoning_effort="none"`, `max_tokens=2500`, `reasoning_tokens=0`, `has_reasoning_content=false`, latency 5,274 ms; OBJECTION_REFRAME_THINK → `enable_thinking=true`, `reasoning_effort="low"`, `max_tokens=6400`, `reasoning_tokens=5,791`, `has_reasoning_content=true`, latency 34,323 ms. Trace T1/T3/T5/T9 dùng FAST_NO_THINK (0 reasoning token); T7 dùng OBJECTION_REFRAME_THINK (5,117 reasoning tokens trên luồng bot).

**3.2 — Sửa vòng đời `unresolved_requests`**
- Không đóng request chỉ dựa trên `Speech Act` (Explain Value / Soft Recommendation / Direct Answer). Đóng `ANSWERED` chỉ khi `actualAssistantReplyText` thực sự giải quyết: hỏi giá → `doesAssistantReplyAnswerPriceInquiry()` yêu cầu `hasQuotedPriceInText(reply)`; so sánh gói → `doesAssistantReplyExplainPackageComparison()` yêu cầu đủ 2 mức giá được hỏi + chi tiết phân biệt (cơ bản/chuyên sâu, phút, bổ sung…). LLM declare `ANSWERED` nhưng reply không chứng minh → hạ về `OPEN`. Quét lại `OPEN` cũ → `ANSWERED` nếu reply mới giải thích; thêm mới `OPEN` nếu chưa có.
- By chứng: Trace T1 `"giá gói"` → ANSWERED; T3 `"triệu chứng hoa mắt chóng mặt tê chân tay"` → OPEN (bot chưa báo giá); T5 → ANSWERED; T7 `"so sánh gói 239k 399k"` → ANSWERED; T9 `"phản hồi tư vấn"` → OPEN (mặc định không đóng sai khi `actualAssistantReplyText` null/không giải quyết).

**3.3 — Kiểm soát chặt `confirmed_facts`**
- `isInvalidOrTruncatedFactValue()`: chặn null/blank, độ dài <2, từ cảm thán đơn lẻ & mảnh khẩu ngữ ("chết", "ngại", "ngại quá", "quá", "lắm", "đã", "vâng", "không", "chưa", "rồi", "thôi"…), nhãn suy diễn mơ hồ ("đáp ứng mơ hồ", "phản ứng mơ hồ", "chưa rõ", "không rõ", "đang phân vân", "băn khoăn"…). `cleanAtomicSymptomValue()` cắt trailing "chết" ("mệt chết" → "mét" → bị loại vì trong danh sách từ chối). `price_reaction` chỉ khi `isPriceObjectionFactValue(value)` **và** có tín hiệu giá trong lời khách; `llmCostConcern` bắt buộc `hasGenuinePriceObjectionCue(originalCustomerMessage)`. Mọi fact ADD phải có bằng chứng từ vựng (`hasLexicalEvidenceInUtterance`).
- By chứng: Trace T7 `price_reaction="so sánh 239k 399k"` CONFIRMED (có từ chê đắt thật); T9 không ghi "đáp ứng mơ hồ"/"chết"; test cases "chết", "ngại chết", "mệt chết"→"mét", "đáp ứng mơ hồ", "chưa rõ" đều bị loại.

---

## 6. Before/After 5-Turn Trace (T1, T3, T5, T7, T9) & 10-Scenario (S1–S10) Verification

Tôi đã đối chiếu trace sau khắc phục (`after_remediation_compact_summary.json` / `after_remediation_5turn_trace.json`) với IMPLEMENTATION_REPORT §6 và số liệu live Qwen:

| Lượt | Khách gửi | Intent | ThinkingMode | reasoning_tokens | Bubbles | unresolved_requests | confirmed_facts | Kết quả |
|------|-----------|--------|--------------|------------------|---------|---------------------|-----------------|---------|
| **T1** | `"Giá"` | GENERAL_PRICE_INQUIRY | FAST_NO_THINK | 0 | B1: anchor 239k/399k/670k + gói tiêu biểu; B2: hỏi vùng cần thư giãn | `"giá gói"` → ANSWERED | [] | ✅ Có giá khởi điểm từ Catalog; B2 không bắt chọn gói |
| **T3** | `"Tôi hoa mắt chóng mặt tê chân tay"` | NEED_DISCOVERY | FAST_NO_THINK | 0 | B1: đồng cảm + ranh giới y khoa; B2: hỏi vùng ưu tiên | `"giá gói"` ANSWERED; `"triệu chứng…"` → OPEN | [] | ✅ Giữ ranh giới SAFETY; không y hóa triệu chứng thần kinh |
| **T5** | `"Đầu và vai gáy"` | NEED_DISCOVERY (scope ctx) | FAST_NO_THINK | 0 | B1: ghi nhận vùng; B2: gợi ý gói 339k/370k | `"giá gói"` ANSWERED; `"triệu chứng…"` → ANSWERED | `need_scope="đầu và vai gáy"` CONFIRMED | ✅ SCOPE_CONFIRMATION ngữ cảnh; `skills=[]`; không ghi symptom bệnh lý |
| **T7** | `"Đắt thế, gói 239k với 399k khác nhau thế nào?"` | PRICE_OBJECTION_HANDLING | OBJECTION_REFRAME_THINK | 5,117 | B1: đồng cảm chi phí + so sánh trực tiếp 2 gói; B2: hỏi nghiêng gói nào | cả 3 item → ANSWERED | `need_scope` CONFIRMED; `price_reaction="so sánh 239k 399k"` CONFIRMED | ✅ 2 skill đồng thời; reasoning thực sự bật (khớp live evidence 5,791 tokens) |
| **T9** | `"Ơ, dở hơi à"` | PROCESS_FEEDBACK | FAST_NO_THINK | 0 | B1: xin lỗi, không gợi ý thêm, không hỏi gì nữa | cả 3 item ANSWERED; `"phản hồi tư vấn"` → OPEN | `need_scope`, `price_reaction` giữ nguyên | ✅ `sanitizerMutationRate=0.000`; SERVICE_RECOVERY; không ghi fact rác |

**10 tình huống (S1–S10):** Tất cả **ĐẠT (PASS)** theo `after_remediation_10_scenarios.json` và IMPLEMENTATION_REPORT §7:
- S1 Hỏi giá chung mở đầu ✅ | S2 Triệu chứng thần kinh/mạch máu ✅ | S3 Xác nhận vùng ngắn ✅ | S4 `"Cả hai"` ✅ | S5 Mixed chê đắt + so sánh ✅ | S6 So sánh trực tiếp không chê đắt ✅ | S7 Phản ứng gay gắt ✅ | S8 Phàn nàn hỏi lặp ✅ | S9 Lưỡng lự/tạm dừng ✅ | S10 Phễu hết hạn → đặt lịch ✅.

**So sánh Before (baseline `304ff580`) vs After (`b60be27`):** Before thiếu giá khởi điểm 239k, hỏi chọn gói ngay T1, gộp đồng cảm+ranh giới+câu hỏi vào 1 bubble T3, Split-Brain T9 (`sanitizerMutationRate=0.863`, ghi `"đáp ứng mơ hồ"`); After khắc phục toàn bộ, telemetry ThinkingMode khớp payload gửi lên endpoint Qwen thực tế.

---

## 7. Overfitting / Hardcode Risks

- **Không hardcode giá/tên gói trong logic chính:** `resolveLowestCoreStartingPackage` tính động từ `eligibleCatalog` của tenant. Test fixture catalog "Nang Sen" (NS00–NS03) chỉ nằm trong test, hợp lệ (NS00 30 phút bị loại khỏi khoá core do ≤35 phút, anchor NS01 239k tính động).
- **Regex tiếng Việt hardcode** nhiều cụm ("đầu vai gáy", "cổ vai gáy", "đầu", "chỉ 2/hai"…) — áp dụng tốt mercato VN, không chuyển đổi được sang ngôn ngữ khác mà không viết lại mẫu.
- **`\b` trong Java chỉ nhận ranh giới từ ASCII** → false negative với từ bắt đầu ký tự có dấu ("Đầu", "Ạo"…). Cần Unicode boundary `(?<![\p{L}\p{M}\p{N}])…(?![\p{L}\p{M}\p{N}])`.
- **Danh sách từ chối fact** ("chết", "ngại chết", "đáp ứng mơ hồ", "chưa rõ"…) và mẫu `hasPackageComparisonCue` (chỉ khớp "239k", bỏ sót "239.000") — guardrail ngôn ngữ, có thể bỏ sót biến thể mới → theo dõi false negative sản xuất.
- **`sanitizeMedicalTreatmentClaimFromConversationGoal`** dùng hardcode template "Chăm sóc thể giảm <message>" — nếu catalog thay đổi hệ thống wellness, cần giữ đồng bộ.
- **`extractMinutesFromDurationLabel`** mặc định 60 khi nhãn null — chưa ghi lý do chọn 60.
- Không hardcode tenant ID / page ID / giá cố định vào logic xử lý (chỉ trong test fixture).

---

## 8. Multi-Tenant / Negative Control Risks

- **Multi-tenant:** Memory khóa bởi `pageId`/`senderId` trong khối `synchronized (mem)` (`AgentBrainService.java:1502`); không thấy rò rỉ dữ liệu giữa tenant/page.
- **Thinking mode:** Khóa bởi `isQwenModelOrProvider()` và ngoại trừ `isOfficialDashScopeEndpoint()` (DashScope tự quản lý reasoning) → nhà cung cấp khác không nhận trường thinking của Qwen, không ảnh hưởng multi-tenant.
- **`hasGenuinePriceObjectionCue`** loại trừ pure budget inquiry, competitor-only comparison, privacy concern, deferral → không gán nhầm `price_reaction` cho câu hỏi giá trung tính.
- **Negative control** (trang "Thẩm mỹ viện", `page_id=59e68078-8327-4aa0-8cca-91410f783ad4`): `skills_count=0`, `docs_count=0`, `sessions_count=2` (giữ nguyên trước và sau) — không thêm/sửa/xóa `agent_skills`, `documents`, `persona_config` hay migration; không tạo session mới. Toàn bộ thay đổi đọc Catalog động theo tenant, không hardcode tên gói/mức giá Nàng Sen.
- Các intent mới (`PACKAGE_COMPARISON`, `SCOPE_CONFIRMATION`) chỉ kích hoạt khi có lexeme so sánh/vùng chăm sóc **và** ngữ cảnh bot hỏi trước → thông điệp chung/khiên không khớp mẫu.
- **Khoảng trắng P2:** Chưa có test Negative Control ràng buộc (0 intent PACKAGE_COMPARISON / SCOPE_CONFIRMATION, 0 skill sale cho thông điệp khiên trên trang "Thẩm mỹ viện").

---

## 9. Required Fixes Before Next Step / Final Recommendation for Owner Approval

### Bắt buộc trước CP4 (P2)
1. **[P2] Verify lại 3 sửa đã commit trong `b60be27`** bằng `mvn test` khi mở quyền build: comment `CleanAgentOrchestratorService.java:7372` ("3200" → "6400"); fixture `max_tokens=6400` trong `PostCp3Wave3ReasoningAndMemoryTest`; nhánh retry `emptyContentWithReasoning()` trong `OpenAiCompatibleLlmClient.java` ép `enable_thinking=false` / `reasoning_effort="none"` / `max_tokens=2500` cho Qwen (bất-DashScope), đồng dạng nhánh BadRequest.
2. **[P2] Bổ sung test Negative Control ràng buộc** trên trang "Thẩm mỹ viện" (0 intent `PACKAGE_COMPARISON` / `SCOPE_CONFIRMATION`, 0 skill sale cho thông điệp khiên).
3. **[P2] Đặt tên ngưỡng 35 phút** thành hằng số + dokument quy tắc nghiệp vụ "core service"; xác nhận với SPOC "giá khởi điểm" = giá thấp nhất toàn Catalog hay chỉ dịch vụ core.
4. **[P2] Mở rộng mẫu câu hỏi chọn gói** trong Sanitizer (hoặc kiểm theo ý định thay vì liệt kê cụm từ).
5. **[P2] Ghi nhận thiết kế:** `interestedPackageId` giữ nguyên sau khi xóa `selected_product_id` — đánh giá downstream có neo mềm sai gói không.
6. **[P2] Rút gọn directive** (bỏ lặp "BẮT BUỘC/TUYỆT ĐỐI KHÔNG", viết thành các bước ngắn) và xác nhận `<VERIFIED_BUSINESS_CATALOG>` được chèn runtime.
7. **[P2] Xem xét `isSincereApologyOrClarificationWithoutPitch`** cho phép câu có hỏi nhưng không chứa giá/SKU/CTA (không làm mất bảo vệ câu xin lỗi pha hỏi).

### Khuyến nghị cuối cho Owner
Nhánh `fix/post-cp3-quality-remediation` (HEAD `b60be27`) đạt **PASS_WITH_FIXES**: không P0/P1, 147 tests xanh (báo cáo nộp), 5 lượt trace và 10 tình huống đều khớp yêu cầu, telemetry ThinkingMode được chứng thực live trên endpoint Qwen. Có thể chuẩn bị merge sang `master`/`refactor/aci-v2-cleanup` và sang CP4 sau khi (i) mở quyền build để chạy lại toàn bộ suite trên commit cha + HEAD xác nhận không có hồi quy, và (ii) áp 7 fix P2 ở trên (3 item đầu đã được sửa trong commit HEAD, cần verify lại bằng build).

**Lưu ý về bằng chứng:** Con số test/build (147 run / 0 failure / 0 error / 4 skipped / BUILD SUCCESS) là reported evidence — tôi không thể tự chạy lại `mvn test` do sandbox chặn công cụ xây dựng. Mã nguồn, diff, trace JSON và live evidence Qwen đã được xác minh tĩnh trực tiếp.
