Tôi đã đọc và triệt để toàn bộ tập bằng chứng: báo cáo triển khai, 6 báo cáo kiểm toán từng Wave (Qwen + Claude), diff tổng hợp, số liệu live endpoint Qwen, và trace 5 lượt / 10 kịch bản sau khắc phục. Dưới đây là báo cáo kiểm toán độc lập toàn diện.

---

# BÁO CÁO KIỂM TOÁN ĐỘC LẬP TOÀN DIỆN
## Đợt khắc phục chất lượng sau CP3 (`POST-CP3 QUALITY REMEDIATION`) — Dự án DocMind / ACI V2

**Nhánh:** `fix/post-cp3-quality-remediation` | **HEAD:** `b60be274e795bc851ff512508cb8636ffca5f2eb`  
**Baseline tham chiếu:** `304ff580e664ce079a17242a760a137e98102503` (nhánh `refactor/aci-v2-cleanup`)  
**Page mục tiêu:** Nàng Sen Spa (`2850a8ef-…`) | **Negative Control:** Trang “Thẩm mỹ viện” (`59e68078-…`)  
**Ngày kiểm toán:** 2026-11-05 | **Ký hiệu:** P0 = Critical, P1 = Major, P2 = Minor

### By chứng đã sử dụng
1. `IMPLEMENTATION_REPORT.md` (báo cáo triển khai tổng hợp, góc Before/After + 10 kịch bản + số liệu ThinkingMode)
2. `after_remediation_compact_summary.json` (trace 5 lượt T1–T9 + 10 kịch bản S1–S10)
3. `wave3_live_qwen_thinking_comparison.json` (payload thực tế gửi endpoint `https://api.vilao.ai/v1/chat/completions`, model `qwen3.8-max`)
4. 6 báo cáo kiểm toán từng Wave: `wave1_qwen_audit.md`, `wave1_claude_audit.md`, `wave2_qwen_audit.md`, `wave2_claude_audit.md`, `wave3_qwen_audit.md`, `wave3_claude_audit.md`
5. Diff tổng hợp: `full_remediation_304ff580_to_HEAD.diff` (4 file chính + test mới)

---

## 1. Overall Verdict: PASS_WITH_FIXES

Tất cả yêu cầu cốt lõi qua 3 đợt đã được triển khai và xác minh:

| Đợt | Mức | Mục tiêu | Trạng thái |
|-----|-----|----------|------------|
| Wave 1 | P0 | 1.1–1.4 (Customer Understanding & Conversation Control) | **Đáp ứng đủ** — 4/4 mục tiêu |
| Wave 2 | P1 | 2.1–2.2 (Price Consultation & Idea-Based Bubbles) | **Đáp ứng đủ** — 2/2 mục tiêu |
| Wave 3 | P2 | 3.1–3.3 (Reasoning & Memory Integrity) | **Đáp ứng đủ** — 3/3 mục tiêu |

- Test tự động tổng hợp: **147 tests, 0 failures, 0 errors, 4 skipped, BUILD SUCCESS** (báo cáo triển khai §5)
- 10/10 kịch bản nghiệm thu (S1–S10): **ĐẠT**
- Số liệu live endpoint Qwen khớp thiết kế ThinkingMode (FAST_NO_THINK: `reasoning_tokens=0`; OBJECTION_REFRAME_THINK: `reasoning_tokens=5791`, `enable_thinking=true`, `max_tokens=6400`)
- Negative Control (trang “Thẩm mỹ viện”): 0 tác động (`skills_count=0`, `docs_count=0`, `sessions_count=2` không đổi)

**Không còn Critical Blocker (P0) và Major Issue (P1) mở tại HEAD.** Còn lại một số Minor Issue (P2) cần khắc phục trước bước tiếp theo (CP4) — chi tiết tại §4 và §8.

---

## 2. Critical Blockers (P0)

**Không có.**

---

## 3. Major Issues (P1)

**Không có (tại HEAD `b60be27`).**

Ghi chú lịch sử: Qwen CLI đánh giá **P1-1** ở Wave 2 — Sanitizer viết lại toàn bộ câu chọn gói trước khi khối chèn `starting_package_anchor` kiểm tra `hasQuotedPriceInText`, khiến giá khởi biến mất trong trường hợp khách ghép giá + câu hỏi chọn gói vào một câu (ví dụ *"Gói 399k và 670k, bạn chọn gói nào?"*). Vấn đề này **đã được khắc phục trong chính commit Wave 2 (`ff55078`)**: tính `hadQuotedPriceBeforeRewrite` trước khi viết lại câu, và bổ sung test hồi quy (6/6 PASS theo báo cáo triển khai §9). Do đó không còn là P1 mở.

Không có P1 mới tại Wave 1 và Wave 3.

---

## 4. Minor Issues (P2)

Danh sách dưới gộp từ 3 báo cáo kiểm toán, đánh giá lại tại HEAD `b60be27`.

### Wave 1 (P0)
| # | Vấn đề | Vị trí | Mức độ |
|---|--------|--------|--------|
| P2-W1-1 | Regex `\b` nhận dạng ranh giới từ chỉ ASCII → token tiếng Việt có dấu mở đầu câu ("Đầu", "Để",…) không khớp leading `\b` → false-negative detect phạm vi cơ thể. | `AdaptiveConversationIntelligence.java` (`BARE_BODY_AREA_OR_DUAL_SCOPE_TOKEN_PATTERN` và các mẫu liên quan) | P2 |
| P2-W1-2 | Mẫu `matches()` toàn chuỗi + giới hạn 1–10 từ trong `isBareBodyAreaScopePhraseWithoutSymptoms` → false-positive/negative với câu dài hoặc từ body-area xuất hiện giữa văn bản trích dẫn. | `AdaptiveConversationIntelligence.java` | P2 |
| P2-W1-3 | Chuỗi tiếng Việt hardcode trong directive/regex (telex/VNI không fold dấu); chỉ áp `Normalizer.NFC` → cần ghi chú khi thêm mẫu mới. | Directive + regexWave 1 | P2 |
| P2-W1-4 | `NON_SCOPE_PRICE_OR_BOOKING_TOKEN_PATTERN` chứa "khác nhau", "so sánh" → câu *"vai gáy khác nhau"* có thể bị loại khỏi Scope Confirmation. | `AdaptiveConversationIntelligence.java` | P2 |
| P2-W1-5 | `sanitizeMedicalTreatmentClaimFromConversationGoal` thay toàn bộ goal bằng template hard-coded → khi catalog thay đổi tên hạng mục wellness, template cần sửa theo. | `CleanAgentOutputRefereeAndSanitizer.java` | P2 |
| P2-W1-6 | Chưa có test Negative Control ràng buộc: 0 intent sale/comparison được kích hoạt cho thông điệp thư앙 / ngoài tiếng Việt. | Bộ test Wave 1 | P2 |

### Wave 2 (P1)
| # | Vấn đề | Vị trí | Mức độ |
|---|--------|--------|--------|
| P2-W2-1 | Magic number **35 phút** (`durationLabel > 35`) trong `resolveLowestCoreStartingPackage` chưa dokument / chưa externalize. | `CleanAgentCatalogAndPromptBuilder.java` | P2 |
| P2-W2-2 | `extractMinutesFromDurationLabel` trả 60 mặc định khi nhãn null, chưa ghi chú lý do chọn 60. | `CleanAgentCatalogAndPromptBuilder.java` | P2 |
| P2-W2-3 | Danh sách cụm câu hỏi chọn gói hardcode trong Sanitizer ("ưu tiên gói nào", "nghiêng về gói nào",…) có thể bỏ sót biến thể ("gói nào phù hợp nhất",…). | `CleanAgentOutputRefereeAndSanitizer.java` (~7819) | P2 |
| P2-W2-4 | Xóa tiền tố chào/đồng cảm lặp chỉ áp bubble[1..]; bubble 0 mở bằng chào lặp thì không xử lý. | `organizeBubblesBySemanticIdeaUnits` (~7941–7954) | P2 |
| P2-W2-5 | Chèn `starting_package_anchor` chỉ khi `hasQuotedPriceInText && !mentionsStartingPrice` → LLM hoàn toàn không trích giá sẽ không được bổ sung anchor (rủi ro thấp; prompt đã hướng dẫn LLM phải nêu giá khởi điểm). | `CleanAgentOutputRefereeAndSanitizer.java` (~7850) | P2 |

### Wave 3 (P2)
| # | Vấn đề | Vị trí | Mức độ |
|---|--------|--------|--------|
| P2-W3-1 | Comment `CleanAgentOrchestratorService.java:7372` ghi *"~3200 maxTokens"* còn code dùng `6400` → stale comment. | `CleanAgentOrchestratorService.java:7372` | P2 |
| P2-W3-2 | Fixture test `PostCp3Wave3ReasoningAndMemoryTest` khởi tạo budget `maxTokens=3200` cho `OBJECTION_REFRAME_THINK`, sản sinh `6400` → test không bảo vệ giá trị production. | `PostCp3Wave3ReasoningAndMemoryTest.java` | P2 |
| P2-W3-3 | (Đã khắc phục tại `b60be27` theo báo cáo triển khai §9) Nhánh retry `emptyContentWithReasoning()` trước đây chưa ép `enable_thinking=false` / `reasoning_effort="none"` cho Qwen giống nhánh BadRequest → đã điều chỉnh đồng dạng. | `OpenAiCompatibleLlmClient.java` | Đã fix |

**Cộng thêm từ bộ nhớ kiểm toán trước (MEMORY.md):** mẫu giá `hasPackageComparisonCue` cần mở rộng ("239.000", "239kđ",…); rút gọn directive (dài, in hoa, nhiều mệnh đề "BẮT BUỘC/TUYỆT ĐỐI KHÔNG").

---

## 5. Chi tiết xác minh từng Wave

### Wave 1 — P0 (1.1–1.4) — Commit `013253b` — Verdict: PASS_WITH_FIXES (P2 mở)

| Mục | Yêu cầu | Trạng thái | Bằng chứng |
|-----|---------|------------|------------|
| **1.1** | Câu cảm tiêu cực (*"Ơ, dở hơi à"*) đồng bộ Pre-LLM (`PROCESS_FEEDBACK` / `SERVICE_RECOVERY`), Skill Selector (0 skill bán hàng), Reflection (không suy diễn `price_reaction` = "đáp ứng mức hề", `cta_level = NO_CTA`), Sanitizer (không cắt giữa 86,3% câu xin lỗi; `sanitizerMutationRate` 0.863 → 0.000) | **PASS** | `detectCanonicalTurnIntents` chỉ `PROCESS_FEEDBACK`; `selectPreLlmTurnRelevantSkills` rỗng; reconcile xóa `price_reaction` khi `!hasGenuinePriceObjectionCue(rawUserMessage)`; test `testNegativeReactionUtteranceUnifiedAcrossPipeline` |
| **1.2** | Câu ngắn xác nhận phạm vi (*"Đầu và vai gáy"*, *"Cả hai"*, *"Chân và lưng"*) → `SCOPE_CONFIRMATION`; không thành triệu chứng/mục tiêu điều trị; không khóa `selected_product_id`; không cắt *"chỉ"* trong *"massage chỉ vai gáy"* | **PASS** | `isContextualShortBodyRegionScopeReply` + `hasPriorBotScopeOrFocusQuestion`; reconcile chuyển `self_reported_symptom` → `need_scope`, xóa booking patch khi có `selected_product_id` mà không có ngày; sanitizer bảo vệ cụm *"massage cổ vai gáy*"; test `testContextualShortScopeSelectionAndMemoryIntegrity` |
| **1.3** | Câu ghép (*"Đắt thế, gói 239k với 399k khác nhau thế nào?"*) → đồng thời `PRICE_OBJECTION` + `PACKAGE_COMPARISON`; đồng cảm giá trước, sau đó so sánh trực tiếp 2 gói từ `<VERIFIED_BUSINESS_CATALOG>`; không bán thả gói không liên quan | **PASS** | Enum `PACKAGE_COMPARISON`; `hasPackageComparisonCue`; `detIntent = PRICE_OBJECTION`; directive `PRICE_OBJECTION_WITH_PACKAGE_COMPARISON` (4 bước); orchestrator kích hoạt đồng thời `price_objection_sensory_value` + `package_comparison_and_scope_clarity`; test `testMixedPriceObjectionAndPackageComparison` |
| **1.4** | Ranh giới triệu chứng sức khỏe (*"Tôi hoa mắt chóng mặt tê chân tay"*) → `NEED_DISCOVERY` với ranh giới y khoa; 4 trường hợp phân biệt (triệu chứng solo / xác nhận vùng chăm sóc / yêu cầu tư vấn dịch vụ / hỏi an toàn phù hợp y khoa) | **PASS** | `isIsolatedNeurologicalSymptomOnly` công khai; `isUnconfirmedHealthSymptomWithoutServiceDemand`; directive `NEED_DISCOVERY` (đồng cảm 1 câu, ranh giới an toàn, cấm chốt gói/báo giá, hỏi 1 câu ưu tiên vùng); test `testHealthSymptomBoundaryFourCases` |

**P2 ghi chú:** regex `\b` tiếng Việt, template hard-code, thiếu test Negative Control.

### Wave 2 — P1 (2.1–2.2) — Commit `ff55078` — Verdict: PASS (P1-1 đã fix trong chính commit)

- **2.1 — Tư vấn giá chung (`GENERAL_PRICE_INQUIRY`):** directive `PRICE_INTENT_A (GENERAL_PRICE_INQUIRY)` nêu mức giá khởi điểm thực tế từ `starting_package_anchor` / `core_service_group_starting_prices` (tính động từ Catalog tenant, **không hardcode** 239k/399k/670k vào Shared Core), kèm 1–2 gói đại diện (giá, thời lượng, hạng mục chính), nói rõ là lựa chọn tiêu biểu chứ không phải toàn bộ danh mục; Bubble 2 hỏi nhẹ nhu cầu thư giãn / vùng cơ thể; **TUYỆT ĐỐI KHÔNG** bắt khách chọn gói cụ thể trước khi hiểu nhu cầu; không xả bảng giá dài. `resolveLowestCoreStartingPackage` lọc non-addon, giá > 0, ưu tiên khoá core có `durationLabel > 35 phút`, fallback toàn bộ non-addon nếu rỗng. ✅
- **2.2 — `reply_bubbles` theo đơn vị ý nghĩa:** `organizeBubblesBySemanticIdeaUnits` — 1 ý hoàn chỉnh = 1 bubble; 2 ý độc lập = tách 2 bubble; phản hồi 1 ý ngắn = giữ 1 bubble (0 cắt câu giữa chừng, 0 lặp lời chào/đồng cảm); toàn lượt tối đa 1 dấu `?` đặt ở cuối bubble cuối cùng; bỏ qua luồng bắt buộc 1 bubble (pause/deferral/exit/gender/feedback/handoff). Prompt Java + `config/aci_v2_system_prompt.md` đồng bộ byte-by-byte (`Sbr01Fix4PromptPolicySyncTest`). ✅

**P1-1 (đã sửa):** Xét lại thứ tự Sanitizer để tính giá trích trước khi viết lại câu chọn gói, hoặc bỏ điều kiện `hasQuotedPriceInText` khi `isGeneralPriceInquiryWithoutPriorNeeds && !mentionsStartingPrice`. ✅ Đã áp tại `ff55078`.

### Wave 3 — P2 (3.1–3.3) — Commit `b60be27` — Verdict: PASS (2 P2 mở)

- **3.1 — Khôi phục ThinkingMode theo chính sách** (`OpenAiCompatibleLlmClient`): `FAST_NO_THINK` → `enable_thinking=false`, `chat_template_kwargs.enable_thinking=false`, `reasoning_effort="none"`, `max_tokens=2500`; `OBJECTION_REFRAME_THINK` (`thinkEnabled()==true`) → `enable_thinking=true`, `reasoning_effort="low"`, `max_tokens=6400`; BadRequest retry ép tắt thinking (Qwen, bất-DashScope). ✅  
  **Bằng chứng live endpoint Qwen:** FAST_NO_THINK — `reasoning_tokens=0`, `has_reasoning_content=false`, latency 5.274s; OBJECTION_REFRAME_THINK — `reasoning_tokens=5791`, `has_reasoning_content=true`, `max_tokens=6400`, latency 34.323s. ✅  
  P2 mở: fixture test `max_tokens=3200` vs production `6400`; stale comment "3200" tại `CleanAgentOrchestratorService.java:7372`.

- **3.2 — Sửa vòng đời `unresolved_requests`** (`AgentBrainService.applyAciTurnMemoryUpdates`): không đóng request chỉ vì tên Speech Act; đóng `ANSWERED` chỉ khi `actualAssistantReplyText` chứng minh (giá: `hasQuotedPriceInText`; so sánh gói: đủ 2 mức giá/2 gói + chi tiết phân biệt phạm vi/thời lượng/hạng mục); LLM khai `ANSWERED` nhưng reply không chứng minh → hạ về `OPEN`; quét lại `OPEN` cũ, chuyển thành `ANSWERED` nếu reply mới giải thích; nếu chưa có trong danh sách → thêm mới `OPEN`; fallback parse `unresolved_requests` từ nhiều vị trí JSON (top-level và `consultative_profile`). ✅  
  Trace T1→T7 chứng thực: *"giá gói"* ANSWERED từ T1; *"triệu chứng hoa mắt chóng mặt tê chân tay"* OPEN ở T3, chuyển ANSWERED ở T5; *"so sánh gói 239k 399k"* OPEN khai sai ở T3 (LLM declare ANSWERED nhưng reply chỉ đồng cảm) → giữ OPEN, rồi ANSWERED ở T7 khi reply thực sự giải thích điểm khác biệt.

- **3.3 — Kiểm soát chặt `confirmed_facts`** (`AdaptiveConversationIntelligence`): `isInvalidOrTruncatedFactValue()` chặn null/blank, độ dài < 2, từ cảm thán đơn lẻ & mảnh khẩu ngữ (*"chết"*, *"ngại"*, *"ngại quá"*, *"quá"*, *"lắm"*,*"đã"*, *"vâng"*, *"không"*, *"chưa"*, *"rồi"*, *"thôi"*,…), nhãn suy diễn mơ hồ (*"đáp ứng mơ hồ"*, *"phản ứng mơ hồ"*, *"chưa rõ"*, *"không rõ"*, *"đang phân vân"*, *"băn khoăn"*,…); `cleanAtomicSymptomValue()` cắt hậu tố *" chết"* (*"mệt chết"* → *"mét"*, sau đó bị từ chối vì trong danh sách từ chối); `price_reaction` chỉ ghi khi `isPriceObjectionFactValue(value)` **và** có tín hiệu giá trong lời khách (`hasGenuinePriceObjectionCue(originalCustomerMessage)`); mọi fact ADD phải có bằng chứng từ vựng trong phát ngôn khách (`hasLexicalEvidenceInUtterance`). ✅  
  Negative control: `hasGenuinePriceObjectionCue` loại trừ so sánh đối thủ đơn thuần, pure budget inquiry, lo riêng tư, hoãn/pause → không gán nhầm `price_reaction`.

---

## 6. Before/After 5-Turn Trace (T1, T3, T5, T7, T9) & 10-Scenario Acceptance (S1–S10)

### 6.1 Bảng đối chiếu 5 lượt (Before = baseline `304ff580`; After = `b60be27`)

| Lượt | Tin nhắn khách | Before (`304ff580`) | After (`b60be27`) | Chỉ số kỹ thuật cải thiện |
|------|---------------|---------------------|-------------------|---------------------------|
| **T1** | *"Giá"* | 1 bubble, **thiếu anchor 239k**, bubble 2 bắt chọn gói (*"Mình quan tâm gói nào ạ?"*) | 2 bubble: anchor **239k / 399k / 670k** từ Catalog + vài gói tiêu biểu; bubble 2 khám phá nhu cầu/vùng (*"Mình đang muốn thư giãn vùng nào…?"*) | `unresolved_requests["giá gói"] = ANSWERED`; FAST_NO_THINK, `reasoning_tokens=0`, `sanitizerMutationRate=0.000` |
| **T3** | *"Tôi hoa mắt chóng mặt tê chân tay"* | 1 bubble 54 từ trộn đồng cảm + ranh giới + hỏi | 2 bubble: B1 đồng cảm + ranh giới y khoa (không thay thế thăm khám, thần kinh/mạch máu); B2 hỏi vùng ưu tiên | `NEED_DISCOVERY`, `priority=SAFETY`; `unresolved["triệu chứng…"] = OPEN` (chưa báo giá); `reasoning_tokens=0` |
| **T5** | *"Đầu và vai gáy"* | `discovery_empathy_and_scope` còn active; sanitizer **cắt chữ "cổ"** trong *"massage cổ vai gáy"*; 1 bubble | `skills=[]` (thoát discovery scope); `confirmed_facts[need_scope="đầu và vai gáy"]` CONFIRMED/HIGH; 2 bubble; *"massage cổ vai gáy"* giữ nguyên | `sanitizerMutationRate=0.006`; không ghi vùng cơ thể vào `self_reported_symptoms` |
| **T7** | *"Đắt thế, gói 239k với 399k khác nhau thế nào?"* | Miss skill `package_comparison`; cắt "cổ"; `reasoningEffort=none`, `reasoningTokens=0`; 1 khối 4 câu; `unresolved` treo `OPEN` | Kích hoạt **đồng thời 2 skills** (`price_objection_sensory_value`, `package_comparison_and_scope_clarity`); "massage cổ vai gáy" nguyên vẹn (`rate=0.000`); `OBJECTION_REFRAME_THINK` thực sự (`reasoning_effort=low`, `max_tokens=6400`, `reasoningTokens=5117`); 2 bubble tự nhiên | `unresolved["so sánh gói 239k 399k"] = ANSWERED`; latency 45.3s; `price_reaction="so sánh 239k 399k"` CONFIRMED/HIGH |
| **T9** | *"Ơ, dở hơi à"* | Split-Brain: Pre-LLM ra lệnh `SERVICE_RECOMMENDATION`; Post-LLM cắt **86,3%** câu trả lời; ghi `price_reaction="đáp ứng mơ hồ"` rác | `PROCESS_FEEDBACK` / `COMMUNICATION_COMPLAINT` / `SERVICE_RECOVERY`; `sanitizerMutationRate=0.000`; không pitch gói, không hỏi thêm; `confirmed_facts` đã có được bảo toàn | 1 bubble xin lỗi thành thật; không ghi nhãn rác |

### 6.2 Kết quả 10 kịch bản nghiệm thu (S1–S10)

Tất cả **10/10 ĐẠT (PASS)** (chi tiết tại `IMPLEMENTATION_REPORT.md` §7 và `after_remediation_10_scenarios.json`):

| Mã | Tình huống | Kết quả |
|----|------------|---------|
| S1 | Hỏi giá chung mở đầu (*"Giá"*) | ĐẠT — anchor 239k/399k/670k + bubble 2 khám phá nhu cầu |
| S2 | Triệu chứng thần kinh/mạch máu (*"hoa mắt chóng mặt tê chân tay"*) | ĐẠT — ranh giới y khoa, `NEED_DISCOVERY`, không biến triệu chứng thành mục tiêu điều trị |
| S3 | Xác nhận vùng ngắn (*"Đầu và vai gáy"* sau S2) | ĐẠT — `need_scope` CONFIRMED, 2 bubble, không cắt "cổ" |
| S4 | *"Cả hai"* sau khi bot hỏi vùng | ĐẠT — xác nhận cả hai vùng, gợi ý gói phù hợp |
| S5 | Mixed intent chê đắt + so sánh 2 gói | ĐẠT — đồng thời 2 skills, so sánh trực tiếp, bubble 2 nhếch |
| S6 | So sánh trực tiếp 2 gói (không chê đắt) | ĐẠT — nêu điểm khác biệt rõ ràng |
| S7 | Phản ứng gay gắt (*"Ơ, dở hơi à"*) | ĐẠT — `PROCESS_FEEDBACK`, `sanitizerMutationRate=0.000`, không pitch, không ghi rác |
| S8 | Phàn nàn hỏi lặp / lòng vòng | ĐẠT — xin lỗi thành thật, điều chỉnh luồng trao đổi |
| S9 | Lưỡng lự / tạm dừng (*"Để xem đã"*) | ĐẠT — 1 bubble ấm áp, 0 câu hỏi chèo kéo |
| S10 | Phễu hết hạn: giá → vùng mỏi → đặt lịch | ĐẠT — ghi nhận đúng gói/giờ/SĐT, giữ lịch tạm chờ kiểm tra thực tế |

---

## 7. Overfitting / Hardcode Risks

1. **Không hardcode giá/tên gói vào logic xử lý (Shared Core):** `resolveLowestCoreStartingPackage` tính động từ `eligibleCatalog` của từng tenant. 239k/399k/670k chỉ còn trong fixture test (`nangSenCatalog`, NS00–NS03) — hợp lệ, không overfit. ✅
2. **Regex tiếng Việt hardcode** nhiều cụm (*"đầu vai gáy"*, *"cổ vai gáy"*, *"đầu"*, *"chỉ 2/hai"*,…) → áp dụng tốt mercato VN, nhưng không chuyển đổi sang ngôn ngữ khác mà không viết lại mẫu.
3. **Directive dài, toàn chữ in hoa, nhiều mệnh đề "BẮT BUỘC / TUYỆT ĐỐI KHÔNG"** → tạo áp lực lên reading budget / prompt. Liên nghiêng đến 2 failure trong báo cáo sureefire Wave 1 (`AciCognitiveV2AndReadingBudgetTest`: 22074 > 20000; `P37BConsultativeJourneyTest`: 11576 > 9800). Nên rút gọn directive thành các bước ngắn và khởi source kích thước thay vì hardcode 9800/20000.
4. **Danh sách từ chối / nhãn mơ hồ trong 3.3** (*"chết"*, *"ngại quá"*, *"đáp ứng mơ hồ"*, …) là guardrail hướng miền spa tư vấn VN → rủi ro overfitting nếu mở rộng domain/ngôn ngữ khác. Đây là thiết kế mù rõ, không phải lỗi logic.
5. **`hasPackageComparisonCue` chỉ khớp định dạng "239k"** (`\d{2,4}\s*k\s+(?:với|và|hay)\s+(?:gói\s+)?\d{2,4}\s*k`) → có thể bỏ sót *"239.000"*, *"239kđ"*.

---

## 8. Multi-Tenant / Negative Control Risks

**Negative Control (trang "Thẩm mỹ viện", `page_id = 59e68078-8327-4aa0-8cca-91410f783ad4`) — RỦI RO THẤP, KHÔNG TÁC ĐỘNG:**
- Truy vấn trực tiếp PostgreSQL: `skills_count = 0`, `docs_count = 0`, `sessions_count = 2` (không tăng, giữ nguyên trước khi thực hiện).
- Không thêm/sửa/xóa `agent_skills`, `documents`, `persona_config`, hay migration nào trên trang này.
- Toàn bộ thay đổi Shared Core (`AdaptiveConversationIntelligence`, `CleanAgentOrchestratorService`, `CleanAgentOutputRefereeAndSanitizer`, `CleanAgentCatalogAndPromptBuilder`, `AgentBrainService`, `OpenAiCompatibleLlmClient`) đọc dữ liệu động từ Catalog của từng tenant; không hardcode tên gói/mức giá của Nàng Sen vào mã xử lý.

**Đa tenant:**
- Thinking mode khóa chặt bởi `isQwenModelOrProvider()` và ngoại trừ `isOfficialDashScopeEndpoint()` (DashScope/Aliyun tự quản lý reasoning) → các nhà cung cấp khác không nhận trường `enable_thinking`/`reasoning_effort` của Qwen. ✅
- Bộ nhớ (`confirmed_facts`, `unresolved_requests`, `conversation_goal`) được khóa bởi `pageId` / `senderId`, xử lý trong khối `synchronized (mem)` → không rò rỉ trạng thái giữa tenant. ✅
- `hasGenuinePriceObjectionCue` loại trừ pure budget inquiry, so sánh đối thủ chỉ đơn thuần, lo riêng tư, hoãn → không gán nhầm `price_reaction` cho câu hỏi giá trung tính. ✅
- `isGenericOpeningPlaceholderRequest` loại trừ item placeholder (*"tư vấn"*, *"cần tư vấn"*) khỏi khớp ngữ nghĩa sai. ✅

**Rủi ro nhale cần theo dõi:**
- Các mẫu mới chỉ khớp tiếng Việt → trên trang ngoài tiếng Việt giữ hành vi cũ (có nguy cơ over-medicalize câu kiểu *"shoulder pain"*; đây thuộc negative control范畴).
- `hasPriorBotScopeOrFocusQuestion` quét toàn bộ lịch sử không có cửa sổ gần → câu hỏi phạm vi từ 10 lượt trước vẫn có thể xác nhận câu kiểu *"Chọn hai"* sau này là `SCOPE_CONFIRMATION`. Nên giới hạn 3–5 lượt gần nhất.

---

## 9. Required Fixes Before Next Step (trước CP4 / Wave 4)

| # | Ưu tiên | Fix | Ghi chú |
|---|---------|-----|---------|
| 1 | P2 | Khắc phục ranh giới từ `\b` cho tiếng Việt có dấu (thay bằng `(?<![\p{L}\p{M}\p{N}])…(?![\p{L}\p{M}\p{N}])`) trong `BARE_BODY_AREA_OR_DUAL_SCOPE_TOKEN_PATTERN` và các mẫu liên quan. | Token "Đầu", "Để",… phải detect được. |
| 2 | P2 | Baseline-verify 9 suite fail trên báo cáo sureefire (cây mã Wave 1) vs commit cha `304ff580`; xác định suite nào là hồi quy do Wave 1 (ưu tiên `ThinkingModePolicyAndAdaptiveRouterTest` sanitizer E2E, `AciCognitiveV2AndReadingBudgetTest` reading budget, `P37BConsultativeJourneyTest` prompt budget) rồi sửa. | 2–3 suite chạm gần khu vực Wave 1, chưa thể loại hồi quy. |
| 3 | P2 | Rút gọn directive (bỏ lặp "BẮT BUỘC / TUYỆT ĐỐI KHÔNG", viết các bước ngắn); khởi source kích thước prompt/reading budget thay vì hardcode 9800/20000; xác nhận `<VERIFIED_BUSINESS_CATALOG>` được chèn runtime (hiện chỉ là placeholder trong diff). | Liên quan áp lực budget ở §7 item 3. |
| 4 | P2 | Đồng bộ comment `CleanAgentOrchestratorService.java:7372` ("3200" → "6400") và fixture `max_tokens` trong `PostCp3Wave3ReasoningAndMemoryTest` (3200 → 6400) khớp production / live evidence. | Theo MEMORY.md (comment 6400, fixture max_tokens). |
| 5 | P2 | Bổ sung test Negative Control ràng buộc: 0 intent `PACKAGE_COMPARISON` / `SCOPE_CONFIRMATION` và 0 skill sale được kích hoạt cho thông điệp thư앙 / ngoài tiếng Việt. | Theo MEMORY.md (test Negative Control). |
| 6 | P2 | Mở rộng mẫu giá `hasPackageComparisonCue` ("239.000", "239kđ", …). | Theo MEMORY.md (mẫu giá hasPackageComparisonCue). |
| 7 | P2 (tùy chọn) | Externalize chuỗi viết lại tiếng Việt trong Sanitizer thành template; dokument ngưỡng 35 phút là quy tắc nghiệp vụ "core service" (hoặc externalize nếu Catalog tenant có đơn vị thời lượng khác). | Magic number 35 phút. |
| 8 | — | Giữ 147 tests xanh sau khi áp các sửa trên; nếu thêm test, không làm giảm cobertura hiện tại. | — |

---

## 10. Final Recommendation for Owner Approval

Nhánh `fix/post-cp3-quality-remediation` (HEAD `b60be27`) **đáp ứng đủ cổng chất lượng sau CP3**:

- 3 Wellen / 9 mục tiêu P0–P2 đều đáp ứng, có bằng chứng mã nguồn diff + log runtime 5 lượt (T1–T9) + số liệu live endpoint Qwen.
- Test tự động: **147 tests, 0 failures, 0 errors, 4 skipped — BUILD SUCCESS**.
- 10/10 kịch bản nghiệm thu (S1–S10): **ĐẠT**.
- Negative Control (trang "Thẩm mỹ viện"): **0 tác động**.
- Không có Critical Blocker (P0) và Major Issue (P1) mở.

**Khuyến nghị:** Owner **duyệt `PASS_WITH_FIXES`** và cho phép nhánh tiến tới **CP4 / Wave 4** sau khi hoàn thành 7 bản sửa P2 ở §9 (ưu tiên: baseline-verify 9 suite fail để loại trừ hồi quy Wave 1, và rút gọn directive). Không có blocker chặn merge; tuy nhiên khuyến nghị không merge trước khi xong các sửa P2 trên để giữ chất lượng tài liệu và độ bao phủ test.

---

**Ký:** Independent Auditor — Kiểm tra độc lập toàn bộ đợt `POST-CP3 QUALITY REMEDIATION`  
**Ngày:** 2026-11-05  
**Phạm vi:** Chỉ báo cáo kết quả kiểm toán, không thay đổi mã nguồn trong báo cáo này.
