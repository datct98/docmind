# SBR-01 Wave 1 — CP2 (`FIX-5`) Evidence Report
## Service Recovery & Mixed Intent Refactor

- **Branch**: `refactor/aci-v2-cleanup`
- **Parent Commit (`CP1`)**: `0a491af2143a259fcdd0e53cbf4c8443f6b48df3`
- **Target Page**: Nàng Sen (`2850a8ef-ca4a-40eb-87fa-4f808640efee`)
- **Configuration Toggle Flag**:
  - System Property: `-Ddocmind.aci.fix5.enabled=true|false`
  - Environment Variable: `DOCMIND_ACI_FIX5_ENABLED=true|false`
  - Default: `true`

---

## 1. Root Causes Addressed & Scope of Changes

### 1.1. Files Modified / Added
1. `api-java/src/main/java/com/example/docmind/service/bot/CleanAgentOutputRefereeAndSanitizer.java`
   - Added toggle flag `isFix5MixedIntentRecoveryEnabled()` / `setFix5MixedIntentRecoveryEnabled(Boolean)` (`docmind.aci.fix5.enabled` / `DOCMIND_ACI_FIX5_ENABLED`, default `true`).
   - Added semantic pattern `CATALOG_COMPLETENESS_OR_SCOPE_COVERAGE_COMPLAINT_PATTERN` and `MIXED_INTENT_SERVICE_OR_PACKAGE_INQUIRY_ON_COMPLAINT_PATTERN` + helpers `hasCatalogCompletenessOrProcessClarificationComplaint(String)` and `hasBusinessOrCatalogClarificationDemandOnComplaintTurn(String)`.
   - Extended `OVER_ASKING_OR_VERBOSITY_COMPLAINT_PATTERN` and `OVER_INTRUSION_EXPLICIT_PATTERN` to match `(?:lại\s+|đi\s+hỏi\s+lại\s+)?` between `hỏi` and `nhiều|dồn|lắm|...` so `"Hỏi lại lắm thế"` (`T7`) is classified as `OVER_INTRUSION` and included in `isProcessOrConsultationFeedbackComplaint`.
   - Refactored `stripUnsolicitedPackagePitchOnNegativeReactionInPlace`:
     - On **`COMPLAINT_WITH_BUSINESS_QUESTION`** turns (`explicitPriceOrDirectPkg || mixedBusinessOrCatalogClarification`), preserves grounded package/price/scope answers (`T11`, `T15`) and calls `stripOnlyUnsolicitedBookingCtaInPlace` to strip only unsolicited booking CTAs or trailing choice questions (including inline comma-separated choice clauses).
     - On **`PURE_COMPLAINT`** turns (`T7`), strips unsolicited package pitches, trailing questions (`?`), and redundant symptom-echo sentences.
2. `api-java/src/main/java/com/example/docmind/service/bot/VietnameseScopeSignalExtractor.java`
   - Guarded `GROUP_H_SPEECH_PERCEPTION_CONTRAST_PATTERN` in `isGroupHColloquialPushback` so catalog-completeness complaints (`hasBusinessOrCatalogClarificationDemandOnComplaintTurn`) are not misclassified as `WRONG_SCOPE_COLLOQUIAL`.
3. `api-java/src/main/java/com/example/docmind/service/bot/AdaptiveConversationIntelligence.java`
   - `detectCanonicalTurnIntents`: when `isStyleOrProcessFeedback` co-occurs with `hasBusinessOrCatalogClarificationDemandOnComplaintTurn` or `hasExplicitPriceDemandInMessage`, emits both `SERVICE_OR_OPERATIONAL_INQUIRY` and `PROCESS_FEEDBACK`.
   - `buildLightweightCustomerUnderstandingPlan`: emits `"0 báo giá"` only on pure complaints; on `COMPLAINT_WITH_BUSINESS_QUESTION`, emits `FEEDBACK_RECOVERY (COMPLAINT_WITH_BUSINESS_OR_CATALOG_CLARIFICATION)` instructing brief 1-clause acknowledgment + direct scope/catalog clarification (`cta_level = NO_CTA`, `0` questions).
   - `reconcileWithCurrentUtterance`:
     - Prevents stale `secondaryIntents()` (`PROCESS_FEEDBACK`, `STYLE_COMPLAINT`) from re-triggering `processFeedbackComplaint` when `rawUserMessage` is not a complaint.
     - Routes `mixedComplaintWithBusinessDemand` (`T11`, `T15`) to `ACTIVE_CONSULTATION`, `CLARIFICATION`/`PRICE_INFORMATION`, `Direct Answer`, `NO_CTA` while keeping `PROCESS_FEEDBACK` in `secondaryIntents`.
     - Exits sticky `FRICTION_RECOVERY` / `SERVICE_RECOVERY` to `ACTIVE_CONSULTATION` when the customer moves on to a non-complaint turn (`T9`, `T13`).
4. `api-java/src/test/java/com/example/docmind/service/bot/Sbr01Fix5ServiceRecoveryTransitionTest.java`
   - Added 8 unit tests covering all 6 scenarios in `FIX5_SERVICE_RECOVERY_STATE_DESIGN.md` (`T7`, `T9`, `T11`, `T13`, `T15`, `T15 CTA strip`), 2 paraphrased catalog-completeness complaints (`"Sao không nói hết các gói từ đầu mà chỉ nêu 2 gói?"`, `"Giới thiệu thiếu quá làm mình tưởng bên em chỉ có mỗi 2 gói đó"`), and the `docmind.aci.fix5.enabled` toggle flag.
5. `api-java/src/test/java/com/example/docmind/service/bot/Sbr01ReplayBenchmarkHarnessTest.java`
   - Documented `-Dsbr01.historyMode=LIVE_GENERATED` in class Javadoc per `SBR01-WAVE-1-DIRECTIVE.md` section 2.2.

---

## 2. Unit & Regression Test Verification

| Test Suite | Command | Result |
|---|---|---|
| `Sbr01Fix5ServiceRecoveryTransitionTest` | `mvn test -Dtest=Sbr01Fix5ServiceRecoveryTransitionTest` | **8/8 PASS** (exit code `0`) |
| `P38ForensicRegressionTest` | `mvn test -Dtest=P38ForensicRegressionTest` | **45/45 PASS** (exit code `0`) |
| `P37ADecisionIntegrityAndFallbackTest` | `mvn test -Dtest=P37ADecisionIntegrityAndFallbackTest` | **5/5 PASS** (exit code `0`) |
| `Sbr01Fix3PunctuationPreservationTest` | `mvn test -Dtest=Sbr01Fix3PunctuationPreservationTest` | **5/5 PASS** (exit code `0`) |

---

## 3. Live Replay Verification (`qwen3.8-max`, 0 Fallbacks, 0 Provider Errors)

### 3.1. `CASE-01 × 3` — `HISTORICAL_TRANSCRIPT` vs `LIVE_GENERATED` (`CP1.4` vs `CP2 FIX-5`)

| Metric | `CP1.4` (`FIX-4`, Historical) | `CP2` (`FIX-5`, `HISTORICAL_TRANSCRIPT`) | `CP2` (`FIX-5`, `LIVE_GENERATED`) |
|---|---:|---:|---:|
| **`benchmarkRunValid`** | `true` | `true` | `true` |
| **Model (`qwen3.8-max`)** | `24/24` (`0` fallbacks) | `24/24` (`0` fallbacks) | `24/24` (`0` fallbacks) |
| **Safety & Truth Pass** | `22/24` (`91.7%`) | **`24/24` (`100.0%`)** | **`24/24` (`100.0%`)** |
| **Task Fulfillment Pass** | `18/24` (`75.0%`) | **`23/24` (`95.8%`)** | **`22/24` (`91.7%`)** |
| **Sales Quality Pass** | `16/24` (`66.7%`) | **`18/24` (`75.0%`)** | **`18/24` (`75.0%`)** |

#### Per-Turn Breakdown on Target Service Recovery & Mixed-Intent Sequence (`T7` → `T9` → `T11` → `T13` → `T15`)

| Turn | Customer Utterance | `CP1.4` (`FIX-4`) Overall | `CP2` (`HISTORICAL_TRANSCRIPT`) | `CP2` (`LIVE_GENERATED`) | Sample `CP2` Bot Reply |
|---|---|---:|---:|---:|---|
| **`T7`** | `"Hỏi lại lắm thế"` | `2/3` (`T7_REPEATED_PACKAGE_PITCH_ON_ANNOYANCE`) | **`3/3` (`100%`)** | **`3/3` (`100%`)** | `"Em xin lỗi vì đã hỏi nhiều, làm mình bực mình ạ. Em sẽ không hỏi thêm gì nữa. Mình cần em hỗ trợ thông tin gì thì cứ nói, em trả lời thẳng ạ."` |
| **`T9`** | `"thế rốt cuộc 2 gói khác j nhau"` | `3/3` | **`3/3` (`100%`)** | **`3/3` (`100%`)** | `"Gói dưỡng sinh cơ bản 60 phút 239k: gội đầu cơ bản + massage cổ vai gáy, phòng chung có rèm riêng. Massage cơ bản 60 phút 399k: massage trị liệu toàn thân chuyên sâu, không bao gồm gội đầu, phòng chung yên tĩnh có rèm riêng."` |
| **`T11`** | `"Đã mệt rồi còn hỏi vùng nào chăm sóc hơn, chăm sóc cả chứ còn vùng nào hơn, cách nói chuyện rất thiếu thông cảm vs khách hàng"` | `3/3` | **`3/3` (`100%`)** | **`3/3` (`100%`)** | `"Em xin lỗi nếu cách diễn đạt khiến mình thấy khó chịu ạ. Gói 239k là gội đầu + massage cổ vai gáy 60 phút, gói 399k là massage trị liệu toàn thân 60 phút (chăm cả đầu, cổ vai gáy, lưng và tay chân), cả hai đều ở phòng chung có rèm riêng ạ."` |
| **`T13`** | `"haizzz, ngoài 2 gói trên còn gói nào khác k"` | `3/3` | **`3/3` (`100%`)** | **`3/3` (`100%`)** | `"Bên em còn nhiều gói khác ạ. Gói Massage phục hồi 100 phút 580k và Gói Massage phục hồi + dưỡng sinh 120 phút 670k đều chăm trọn vùng cổ, vai, gáy, lưng và chân tay, giúp giải tỏa mỏi mệt toàn thân rất sâu ạ."` |
| **`T15`** | `"thế mà lúc giới thiệu nói có 2 gói, làm người ta tưởng chỉ có 2 gói , cách nói rất gây hiểu lầm"` | `0/3` (`T15_MISSING_CATALOG_CLARIFICATION_ON_MISLEADING_INTRO_COMPLAINT`) | **`3/3` (`100%`)** | **`3/3` (`100%`)** | `"Em xin lỗi vì cách diễn đạt khiến mình tưởng chỉ có 2 gói ạ. Ngoài 2 gói trên, bên em còn có gói dưỡng sinh cao cấp 80 phút 339k, gói phục hồi 100 phút 580k và gói kết hợp 120 phút 670k để mình dễ lựa chọn theo thời gian và nhu cầu ạ."` |

### 3.2. Mixed-Intent & Complaint Cases (`CASE-02, CASE-05, CASE-06, CASE-09, CASE-11, CASE-19 × 3`)

- **Artifact**: `docmind/artifacts/sbr01/cp2_fix5_mixed_cases_n3/sbr01_baseline_summary.json`
- **Log**: `C:\Users\Lecoo\.gemini\antigravity\brain\e28df741-8f7d-43e3-accf-a481b29616a3\evidence\CP2_fix5_mixed_cases_n3.log`
- **Telemetry**: `benchmarkRunValid=true`, `qwen3.8-max` `111/111`, `fallbackTurns=0`, `providerErrorTurns=0`, `jsonValidTurns=111/111`, `zeroMutationVerified=true`.

| Case ID | Total Turn Executions (`N=3`) | Safety & Truth | Task Fulfillment | Sales Quality | Overall Turn Pass | `casePass3Of3` |
|---|---:|---:|---:|---:|---:|---|
| **`CASE-02`** | `27` | `26/27` (`96.3%`) | `27/27` (`100%`) | `27/27` (`100%`) | `26/27` (`96.3%`) | `false` (`2/3` runs 100%) |
| **`CASE-05`** | `12` | `12/12` (`100%`) | `12/12` (`100%`) | `12/12` (`100%`) | `12/12` (`100%`) | **`true`** |
| **`CASE-06`** | `12` | `12/12` (`100%`) | `12/12` (`100%`) | `12/12` (`100%`) | `12/12` (`100%`) | **`true`** |
| **`CASE-09`** | `30` | `30/30` (`100%`) | `30/30` (`100%`) | `30/30` (`100%`) | `30/30` (`100%`) | **`true`** |
| **`CASE-11`** | `21` | `21/21` (`100%`) | `21/21` (`100%`) | `21/21` (`100%`) | `21/21` (`100%`) | **`true`** |
| **`CASE-19`** | `9` | `9/9` (`100%`) | `9/9` (`100%`) | `9/9` (`100%`) | `9/9` (`100%`) | **`true`** |
| **Total (`6` cases)** | **`111`** | **`110/111` (`99.1%`)** | **`111/111` (`100.0%`)** | **`111/111` (`100.0%`)** | **`110/111` (`99.1%`)** | **`5/6` (`83.3%`)** |
