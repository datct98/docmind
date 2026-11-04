# GOD CLASSES AUDIT — DOCMIND BACKEND (`com.example.docmind`)

> **Phase Gate**: `P1 — Architecture Discovery`  
> **Baseline Commit**: `f091ba557a079da86c8ae14cd7ccc19fe8ece59d`  
> **Phương pháp**: Quét AST/Method/Regex tự động trên toàn bộ `154 files` (`86,550 LOC`) trong `api-java/src/main/java` kết hợp đọc đối chiếu mã nguồn trực tiếp.

---

## 1. Thống kê tổng quan mức độ tập trung mã nguồn (Đã xác minh)

- **Tổng quy mô `api-java/src/main/java`**: **154 files — `86,550 LOC`**
- **Tổng quy mô `api-java/src/test/java`**: **71 files — `40,966 LOC`**
- **Riêng 8 class lớn nhất trong package `com.example.docmind.service.bot`**: **`45,509 LOC` (chiếm `52.58%` toàn bộ backend Java!)** và chứa **292 biểu thức chính quy (`Pattern.compile`)**.

| Hạng | Tên Class | Đường dẫn File | Số dòng (LOC) | Số hàm (Methods) | Số Regex (`Pattern`) | Mức độ rủi ro kiến trúc |
|---:|---|---|---:|---:|---:|---|
| 1 | `CleanAgentOutputRefereeAndSanitizer` | `service/bot/CleanAgentOutputRefereeAndSanitizer.java` | **7,820** | 176 | **113** | **CRITICAL (God Validator + Mutator)** |
| 2 | `CleanAgentOrchestratorService` | `service/bot/CleanAgentOrchestratorService.java` | **6,718** | 103 | 28 | **CRITICAL (Dual-Orchestrator Monolith)** |
| 3 | `ConversationState` | `service/bot/ConversationState.java` | **5,928** | 160 | 32 | **CRITICAL (State + Cache + Regex NLP)** |
| 4 | `CleanAgentCatalogAndPromptBuilder` | `service/bot/CleanAgentCatalogAndPromptBuilder.java` | **5,773** | 106 | 16 | **HIGH (Catalog + Prompt + Policy Monolith)** |
| 5 | `AdaptiveConversationIntelligence` | `service/bot/AdaptiveConversationIntelligence.java` | **5,558** | 166 | 36 | **CRITICAL (DTOs + Planner + Reconciler)** |
| 6 | `FanpageBotService` | `service/bot/FanpageBotService.java` | **5,152** | 92 | 26 | **HIGH (Gateway + ~2,550 LOC Dead V1 Bot)** |
| 7 | `PrimaryActionRouter` | `service/bot/PrimaryActionRouter.java` | **4,703** | 60 | 30 | **HIGH (~95% Legacy V1 Router bypassed in V2)** |
| 8 | `AgentBrainService` | `service/bot/AgentBrainService.java` | **3,858** | 125 | 11 | **HIGH (In-Memory Store + Dual Memory Schemas)** |
| 9 | `FanpageBotPromptBuilder` | `service/bot/FanpageBotPromptBuilder.java` | **2,448** | 34 | 11 | **MEDIUM (Legacy V1 Prompt Builder)** |
| 10 | `PackageCatalogService` | `service/bot/PackageCatalogService.java` | **1,972** | 51 | 24 | **MEDIUM (Markdown Parser + Price Normalizer)** |
| 11 | `SentimentIntentLayer` | `service/bot/SentimentIntentLayer.java` | **1,496** | 41 | 31 | **MEDIUM (Legacy V1 Regex Intent Layer)** |
| 12 | `ThinkingModePolicy` | `service/bot/ThinkingModePolicy.java` | **1,456** | 39 | 12 | **MEDIUM (Thinking Budget + Telemetry Store)** |

---

## 2. Phân tích chi tiết 8 God Classes cốt lõi (Đã xác minh bằng dòng code)

### 2.1. [`CleanAgentOutputRefereeAndSanitizer.java`](file:///d:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/bot/CleanAgentOutputRefereeAndSanitizer.java) (`7,820 LOC`, 176 methods, 113 regexes)
- **Trách nhiệm đang bị trộn lẫn**:
  1. **JSON Output Parser & Token Estimator** (`L685–L960`): `parseStructuredLlmJsonOutput`, `estimateReflectionTokensFromJsonOutput`, `extractCleanBubblesFromOutput`.
  2. **Legacy Stage-Governed `CLEAN` Referee** (`L1035–L2215`): `validateCleanOutput`, `buildCleanRepairPrompt` (kiểm tra luật theo từng stage V1/CLEAN cũ).
  3. **Deterministic Fallback Bubble Builders** (`L2488–L3680`): `buildSafeMultiIndustryTurnFallbackBubbles`, `buildContextSafeAciV2Fallback`, `rebuildBubblesFromStructuredCatalog`, `buildSafeDiscoveryClarifyBubbles`.
  4. **ACI V2/V3 Truth, Authority & Safety Referee** (`L4610–L6515`): `checkAciV2TruthAuthorityAndSafety`, `findUnverifiedCatalogPriceMention`, `findWrongScopeBodyRegionLeak`, `findCrossTurnNearDuplicateBubble`, `isConfirmedBookingOverriddenByDraft`.
  5. **Post-LLM Heuristic Text Rewriters / Mutators (`InPlace`)** (`L6520–L7819`):
     - `enforceAciV2StyleComplaintNoCtaAndAntiClicheInPlace` (`L5239`)
     - `enforcePoliteClosingPauseSingleBubbleNoQuestionInPlace` (`L5571`)
     - `repairWrongPronounOpenersInPlace` (`L5911`)
     - `sanitizePatronizingEmpathyPhrasesInPlace` (`L6186`)
     - `stripInternalCatalogSkuCodesInPlace` (`L6222`)
     - `sanitizeAmbiguousPriceFormatsInPlace` (`L6342`)
     - `stripUngroundedTipPolicyClaimsInPlace` (`L6409`)
     - `enforceP37B2TurnActionAndBubbleIdeaDisciplineInPlace` (`L6525`)
     - `enforceGroundedApologyAgainstHistoryInPlace` (`L6960`)
     - `stripUnsolicitedPackagePitchOnNegativeReactionInPlace` (`L7140`)
- **Hệ quả kiến trúc**:
  - Vi phạm nghiêm trọng nguyên tắc **Output Guard chỉ Gác Cổng Sự Thật, Không Viết Lại Hội Thoại** (Mục VII.1.G của Master Directive).
  - Các hàm `*InPlace` dùng hàng chục regex để cắt ghép câu tiếng Việt sau khi LLM đã sinh câu trả lời, gây nguy cơ đứt mạch ngữ nghĩa hoặc tạo câu văn lắp ghép máy móc.

---

### 2.2. [`CleanAgentOrchestratorService.java`](file:///d:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/bot/CleanAgentOrchestratorService.java) (`6,718 LOC`, 103 methods, 28 regexes)
- **Trách nhiệm đang bị trộn lẫn**:
  1. **Orchestrator #1 — Legacy `CLEAN` Path (`executeCleanAgentTurn`, `L367–L2041`, ~1,675 LOC)**:
     - **Bằng chứng quan trọng**: Qua `git grep -n "executeCleanAgentTurn"`, **0 file trong `src/main/java`** gọi hàm này! `FanpageBotService.handleCustomerMessageInternal` (`L1278`) gọi thẳng `executeAciCognitiveTurn`. Toàn bộ `1,675 LOC` của `executeCleanAgentTurn` và các hàm phụ trợ (`L2042–L4214`, ~2,170 LOC) hiện chỉ còn phục vụ các bài test cũ (`C3A..C3D`)!
  2. **Orchestrator #2 — Active `LLM_COGNITIVE_V2` Path (`executeAciCognitiveTurn`, `L4215–L5825`, ~1,610 LOC)**:
     - Một phương thức duy nhất dài **1,610 dòng code**, ôm trọn: kiểm tra opt-out, giải quyết đại từ, phân loại gói catalog, giải quyết quote-reply, khôi phục 5 tầng bộ nhớ, tiền xử lý lựa chọn tương đối, dựng snapshot, dựng prompt, gọi LLM + retry, parse JSON, chạy 10+ hàm sanitize in-place, áp dụng booking patch, kiểm tra conditional handoff, chạy Referee V2 + repair loop LLM lần 2, chạy tiếp 5+ hàm sanitize in-place hậu repair, tính reading budget delay, ghi nhận `AgentBrainService`, `SentBubbleLedger`, `ConversationState`, telemetry và dựng `DecisionTrace`.
  3. **Decision Trace & Post-Turn DTO Mappers (`L5826–L6717`, ~890 LOC)**:
     - `buildAciV3DecisionTraceJson`, `mapAciReflectionToCustomerInsight`, `alignAciSelectedSkillsWithTurnContext`.

---

### 2.3. [`ConversationState.java`](file:///d:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/bot/ConversationState.java) (`5,928 LOC`, 160 methods, 32 regexes)
- **Trách nhiệm đang bị trộn lẫn**:
  1. **Mega-Record 55+ trường dữ liệu (`L33–L145`)**: Chứa cả state của luồng V1 cũ (`objectionHandled`, `priceQuotedCount`, `softDelayedOnce`, `consultativeCloseAttempted`), luồng `CLEAN` (`structuredBookingState`, `unresolvedCustomerRequests`) và luồng `ACI V2` (`explicitForbiddenScopes`, `aciRecentAssistantSpeechActs`).
  2. **In-Memory Static Caches toàn JVM (`L150–L480`)**:
     - `SESSION_CACHE` (`ConcurrentHashMap<String, SessionCacheEntry>`)
     - `WEBHOOK_MID_DEDUP_CACHE`
     - `PENDING_SECOND_BUBBLE_MAP`
     - `IdentityWeakMap`
  3. **Regex NLP & Heuristic Slot Extractors (`L1200–L4800`)**: Hàng trăm dòng regex bóc tách triệu chứng, thời lượng, khu vực (`resolveExplicitRegionAcrossTurns`), gói dịch vụ, thời gian đặt lịch (`extractStructuredBookingState`).
  4. **Hai cơ chế cập nhật state song song**:
     - `updateAfterSend` (`L2000+`): Dùng cho luồng V1/CLEAN cũ.
     - `recordAciV2TurnMinimalState` (`L855–L965`): Dùng cho luồng `LLM_COGNITIVE_V2` hiện tại.

---

### 2.4. [`CleanAgentCatalogAndPromptBuilder.java`](file:///d:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/bot/CleanAgentCatalogAndPromptBuilder.java) (`5,773 LOC`, 106 methods, 16 regexes)
- **Trách nhiệm đang bị trộn lẫn**:
  1. **Catalog Tiering, Deduplication & Scope Ranking (`L248–L730`)**: `rankAndTierCatalogForCleanAgent`, `deduplicateCatalogByCanonicalSignature`.
  2. **Quote-Reply Context Resolution (`L733–L980`)**: `resolveQuotedReplyContext`, `extractPresentedPackagesFromHistory`.
  3. **Legacy `CLEAN` Prompt Builder (`L981–L2975`, ~1,995 LOC)**: `buildCleanAgentPrompt`, `renderStructuredTurnContractXml` (không còn chạy trên hot-path `LLM_COGNITIVE_V2`).
  4. **ACI V2/V3 System Prompt Template & Single-Pass Prompt Assembler (`L2980–L5772`, ~2,790 LOC)**:
     - Nhúng trực tiếp hàng trăm dòng chỉ thị chính sách bán hàng vào chuỗi hằng số Java (`ACI_COGNITIVE_SYSTEM_PROMPT_TEMPLATE`) đồng thời đọc từ `config/aci_v2_system_prompt.md`.
     - Chứa hàm `buildAciTurnPlannerBlock` (`L4549+`) nơi phát sinh xung đột trực tiếp với Chính sách Kinh doanh Mục VI.1 của Master Directive (xem `ARCHITECTURE_DISCOVERY_REPORT.md` Mục 10).

---

### 2.5. [`AdaptiveConversationIntelligence.java`](file:///d:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/bot/AdaptiveConversationIntelligence.java) (`5,558 LOC`, 166 methods, 36 regexes)
- **Trách nhiệm đang bị trộn lẫn**:
  1. **35+ Nested Records & Enums (`L35–L1850`)**: Từ `ThreeStreamTurnInput`, `CustomerStatementRecord`, `WorkingHypothesisRecord`, `AdaptiveCognitiveReflection` cho tới `CustomerUnderstandingDraft2Snapshot`, `LightweightCustomerUnderstandingPlan`, `TurnContextSnapshot`.
  2. **Post-LLM Reflection Parser & Regex Reconciler (`L1855–L2980`)**: `parseFromJsonNode`, `reconcileWithCurrentUtterance` (`L2043–L2708`, 665 dòng ghi đè kết quả suy luận của LLM bằng regex Java!).
  3. **Conditional Handoff & Authoritative Booking Patch Engine (`L3350–L4650`)**: `evaluateAndExecuteConditionalHandoff`, `applyAuthoritativeBookingPatch`, `resolveContextualOptionFromUtterance`.
  4. **Pre-LLM Deterministic Turn Planner (`L4740–L5557`)**: `buildLightweightCustomerUnderstandingPlan`, `detectCanonicalTurnIntents`, `classifyPriceIntentSubMode`.

---

### 2.6. [`FanpageBotService.java`](file:///d:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/bot/FanpageBotService.java) (`5,152 LOC`, 92 methods, 26 regexes)
- **Trách nhiệm đang bị trộn lẫn**:
  1. **Active Inbound Gateway & Persistence Guard (`L586–L1298`, ~712 LOC)**: `handleCustomerMessage`, `handleCustomerMessageInternal` (quản lý lifecycle, outbound fencing, inbound persistence guard, hybrid RAG retrieval, rồi chuyển giao sang `cleanAgentOrchestratorService.executeAciCognitiveTurn`).
  2. **Proactive Re-engage (`L1305–L1650`)**: `handleReengage`.
  3. **Dead/Legacy V1 Deterministic Bot Methods (`L1743–L4290`, ~2,550 LOC)**: Các hàm tạo bảng so sánh, lắp ghép template V1 (`synthesizeDynamicComparisonResponse`, `parseAllTablePackages`, `polishConversationalVietnamese`, `buildConsultativeReplyFromChunks`...) hoàn toàn bị bỏ qua khi chạy `executeAciCognitiveTurn`.
  4. **Shared Helpers & Audit Logging (`L4291–L5151`)**: `resolvePagePersona`, `isUniversalAcuteMedicalEmergency`, `emitLiveAuditJsonLog`.

---

### 2.7. [`PrimaryActionRouter.java`](file:///d:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/bot/PrimaryActionRouter.java) (`4,703 LOC`, 60 methods, 30 regexes)
- **Bằng chứng quan trọng**:
  - Trên luồng hoạt động thực tế `executeAciCognitiveTurn`, `PrimaryActionRouter.route(...)` và `PrimaryActionRouter.executeActionBubbles(...)` **hoàn toàn không được gọi**.
  - Luồng `LLM_COGNITIVE_V2` chỉ mượn 3 phần tử từ class 4,703 dòng này:
    1. Enum `PrimaryActionRouter.PrimaryAction`
    2. Record `PrimaryActionRouter.RoutingDecision`
    3. Hàm tĩnh `PrimaryActionRouter.isStrictSystemOptOutMessage(String)` (`L1019` trong `FanpageBotService.java` và `L4336` trong `CleanAgentOrchestratorService.java`).
  - Hơn **4,500 LOC** còn lại trong `PrimaryActionRouter.java` là bộ máy phân luồng kịch bản cứng thời kỳ V1/CLEAN.

---

### 2.8. [`AgentBrainService.java`](file:///d:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/bot/AgentBrainService.java) (`3,858 LOC`, 125 methods, 11 regexes)
- **Trách nhiệm đang bị trộn lẫn**:
  1. **In-JVM Customer Memory Store (`ConcurrentHashMap<String, CustomerBrainMemory>`)** đồng bộ hai chiều với PostgreSQL JSONB qua `AciSessionMemoryPersistenceService`.
  2. **Legacy Brain API (`L350–L1950`)**: `recallAndAdvise`, `retainTurnWithReflection`, `deriveStrategicAdvice` (phục vụ `executeCleanAgentTurn` cũ).
  3. **ACI V2/V3 5-Tier & Draft2 Memory Engine (`L1955–L3857`)**: `applyAciTurnMemoryUpdates`, `applyDraft2CustomerUnderstandingUpdatesLocked`, `recallBookingState`, `recordBookingState`, `syncOptedOutState`.
