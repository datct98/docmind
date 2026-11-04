# TARGET ARCHITECTURE — DOCMIND ADAPTIVE SALES INTELLIGENCE SYSTEM

> **Document Authority**: **TO-BE NORMATIVE MASTER DESIGN** (Tài liệu kiến trúc đích tổng thể cho giai đoạn `P3–P8`, tích hợp đầy đủ các yêu cầu tinh chỉnh `R1–R9`).  
> **Phase Gate**: `P2 — Architecture Lock Pending Project Owner Approval`  
> **Approval Status**: **PROPOSED** *(Chỉ chuyển sang `ACCEPTED` sau khi Chủ dự án phê duyệt Architecture Lock)*  
> **Baseline Commit Before P0**: `244a2ec8a120878f8f863cb0388d72d51ae8bc2e`  
> **P0 Cleanup Commit**: `f091ba557a079da86c8ae14cd7ccc19fe8ece59d`  
> **P2 Initial Draft Commit**: `5aa985d43b5ef6ea1621f37fa546bcae520633c2`

---

## 0. Danh mục Tài liệu Kiến trúc & Phân cấp Thẩm quyền (`R9 — Documentation Manifest`)

| STT | Tên File trong `docs/architecture/` | Mục đích (Purpose) | Cấp Thẩm quyền (Authority Level) | Trạng thái Phê duyệt (Status) |
|---:|---|---|---|---|
| 1 | [`ARCHITECTURE_DISCOVERY_REPORT.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/ARCHITECTURE_DISCOVERY_REPORT.md) | Báo cáo tổng hợp khảo sát hiện trạng hệ thống (`P1`), mã chết, logic trùng lặp và mâu thuẫn chính sách kinh doanh Mục VI.1. | **AS-IS AUDIT** (Chỉ ghi nhận hiện trạng) | `Verified (AS-IS)` |
| 2 | [`RUNTIME_DEPENDENCY_REPORT.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/RUNTIME_DEPENDENCY_REPORT.md) | Bằng chứng kiểm tra Docker containers, ports, volumes và việc gỡ bỏ an toàn Python `agent-brain` `:8095` (`P0`). | **AS-IS & P0 RUNTIME EVIDENCE** | `Verified (P0 Completed)` |
| 3 | [`GOD_CLASSES_AUDIT.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/GOD_CLASSES_AUDIT.md) | Kiểm toán chi tiết 8 God Classes (`45,509 LOC`, `292 regexes`) trong `service.bot`. | **AS-IS AUDIT** (Chỉ ghi nhận hiện trạng) | `Verified (AS-IS)` |
| 4 | [`STATE_OWNERSHIP_MAP.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/STATE_OWNERSHIP_MAP.md) | Bản đồ kiểm toán sự phân mảnh trạng thái trên 6 nơi lưu trữ và 4 điểm tranh quyền quyết định của hệ thống hiện tại. | **AS-IS AUDIT ONLY** *(Không bao giờ ghi đè thiết kế đích)* | `Verified (AS-IS)` |
| 5 | [`CURRENT_EXECUTION_FLOW.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/CURRENT_EXECUTION_FLOW.md) | Dấu vết thực thi từng dòng code (Line-by-Line Trace) của luồng `executeAciCognitiveTurn` hiện tại và các nhánh bị bypass. | **AS-IS AUDIT** (Chỉ ghi nhận hiện trạng) | `Verified (AS-IS)` |
| 6 | [`QWEN_INDEPENDENT_ARCHITECTURE_REVIEW.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/QWEN_INDEPENDENT_ARCHITECTURE_REVIEW.md) | Biên bản phản biện độc lập từ `Qwen3.8-Max` (qua Claude Code CLI) đối với gói Discovery & Design ban đầu. | **INDEPENDENT REVIEW RECORD** | `Completed (Step 4 Record)` |
| 7 | [`TARGET_ARCHITECTURE.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/TARGET_ARCHITECTURE.md) | Kiến trúc đích tổng thể 8 miền A–H, `Branch Intelligence` (`R7`), Telemetry học tập & đánh giá (`R8`) và kế hoạch phân rã 8 God Classes. | **TO-BE NORMATIVE MASTER DESIGN** | `Proposed (Pending Owner Lock)` |
| 8 | [`ARCHITECTURE_DECISIONS.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/ARCHITECTURE_DECISIONS.md) | Hồ sơ các Quyết định Kiến trúc (`ADR-01` -> `ADR-09`), các giả định chưa kiểm chứng và danh sách quyết định chờ Chủ dự án phê duyệt. | **TO-BE NORMATIVE ADR** | `Proposed (Pending Owner Lock)` |
| 9 | [`MODULE_OWNERSHIP.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/MODULE_OWNERSHIP.md) | Đặc tả quyền sở hữu từng module đích và ranh giới cứng giữa Gateway với `SalesTurnOrchestrator` kèm Sequence Diagram (`R4`). | **TO-BE NORMATIVE SPECIFICATION** | `Proposed (Pending Owner Lock)` |
| 10 | [`STATE_OWNERSHIP.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/STATE_OWNERSHIP.md) | Đặc tả quyền sở hữu trạng thái đích, Giao thức Nhất quán `ConversationMemoryStore` (`R2`) và Mô hình `FACT` / `HYPOTHESIS` / `UNKNOWN` (`R3`). | **TO-BE NORMATIVE SPECIFICATION** *(Thay thế hiện trạng trong `STATE_OWNERSHIP_MAP.md`)* | `Proposed (Pending Owner Lock)` |
| 11 | [`TURN_STRATEGY_CONTRACT.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/TURN_STRATEGY_CONTRACT.md) | Đặc tả hợp đồng chiến thuật lượt `TurnStrategyContract`, chính sách kinh doanh Mục VI, `Booking Progression & Question Budget` (`R5`) và `Branch Policy` (`R7`). | **TO-BE NORMATIVE SPECIFICATION** | `Proposed (Pending Owner Lock)` |
| 12 | [`LLM_EXECUTION_OPTIONS.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/LLM_EXECUTION_OPTIONS.md) | Đặc tả `Hybrid Adaptive Execution`, bộ định tuyến độ phức tạp `ExecutionModeSelector` (5 ca câu ngắn phức tạp) và `TurnLlmBudgetTracker` xuyên tầng (`R6`). | **TO-BE NORMATIVE SPECIFICATION** | `Proposed (Pending Owner Lock)` |
| 13 | [`MIGRATION_PLAN.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/MIGRATION_PLAN.md) | Lộ trình chuyển đổi từng giai đoạn `P0 -> P8` và kế hoạch kiểm thử bộ `SC01–SC15` tại `P7` (`R1`). | **GOVERNANCE & EXECUTION PLAN** | `Proposed (Pending Owner Lock)` |
| 14 | [`RISK_REGISTER.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/RISK_REGISTER.md) | Sổ đăng ký rủi ro kỹ thuật/nghiệp vụ, chế độ lỗi (failure modes), đánh đổi thiết kế (trade-offs) và phương án rollback. | **GOVERNANCE & RISK REGISTER** | `Proposed (Pending Owner Lock)` |
| 15 | [`ACCEPTANCE_TEST_PLAN.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/ACCEPTANCE_TEST_PLAN.md) | Kế hoạch nghiệm thu chuẩn hóa 15 kịch bản (`SC01–SC10` Core Business vs `SC11–SC15` Regression/Architecture Guards) và bộ chỉ số `R8` (`R1`). | **NORMATIVE ACCEPTANCE SPECIFICATION** | `Proposed (Pending Owner Lock)` |
| 16 | [`TEST_ACCEPTANCE_PLAN.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/TEST_ACCEPTANCE_PLAN.md) | Bảng tóm tắt tham chiếu nhanh đồng bộ 100% với `ACCEPTANCE_TEST_PLAN.md` (`SC01–SC15`, `R1`). | **NORMATIVE ACCEPTANCE SUMMARY** | `Proposed (Pending Owner Lock)` |

---

## 1. Năm Nguyên tắc Kiến trúc Đích (`Adaptive Sales Intelligence System`)

1. **Một Nguồn Sự Thật Duy Nhất kèm Giao thức Nhất quán Rõ ràng (`R2 & R3`)**:
   - Xóa bỏ hoàn toàn việc lưu trùng trạng thái giữa `ConversationState.SESSION_CACHE`, `AgentBrainService` và `Draft2Snapshot`.
   - `ConversationMemoryStore` quản lý tập trung `CustomerIntelligenceLedger` (phân biệt rõ **`FACT`**, **`HYPOTHESIS`**, **`UNKNOWN`** với luật chống tự xác nhận giả thuyết và chống hồi sinh fact đã bác bỏ) cùng `BookingProgressState` (cập nhật qua `BookingPatch` lũy đẳng và JPA `@Version` OCC + `Monotonic Merge`).
2. **Ranh giới Cứng Giữa Gateway và `SalesTurnOrchestrator` (`R4`)**:
   - Gateway (Miền A) sở hữu vòng đời Meta webhook, debounce, dedup `external_mid`, `SenderEpochRegistry`, WAL recovery, kiểm soát phiên (`HUMAN_TAKEOVER`, `OPT_OUT`) và `Layer-2 Outbound Exit Fence`.
   - `SalesTurnOrchestrator` (Miền G, `<= 400 LOC`) chỉ điều phối vòng đời lượt tư vấn bán hàng sau khi sự kiện inbound đã hợp lệ; không tự quản lý webhook hay tự phát tin ra Messenger.
3. **Một Hợp đồng Chiến thuật Lượt Bất biến (`TurnStrategyContract` — `R5 & R6`)**:
   - Mọi lượt hội thoại (cả `FAST_SINGLE_PASS` lẫn `TWO_PHASE_STRATEGIC`) đều phải tạo và tuân thủ `TurnStrategyContract`.
   - Giải quyết dứt điểm bài toán đặt lịch (`R5`): phân biệt `bookingMissingFields` với `nextBookingQuestionTarget`, cho phép 1 câu hỏi tự nhiên gộp tối đa 2 trường đặt lịch liên quan (`BRANCH + TIME_SLOT` hoặc `PARTY_SIZE + PHONE`) để tránh hỏi lắt nhắt và tránh deadlock.
4. **Định tuyến LLM Theo Độ Phức tạp Quyết định & Kiểm soát Ngân sách Retry Xuyên Tầng (`R6`)**:
   - `ExecutionModeSelector` không bao giờ dựa vào độ dài câu đơn thuần; định tuyến dựa trên độ phức tạp quyết định, độ mơ hồ ngữ dụng, rủi ro chính sách và ngữ cảnh lượt trước.
   - Kiểm soát tách biệt **Logical LLM Calls** (`<= 2` cho Fast-Path, `<= 3` cho Two-Phase) và **Actual Provider HTTP Attempts** (`<= 3` hoặc `<= 4` xuyên suốt mọi tầng kể cả retry mạng/JSON).
5. **Output Guard Chỉ Gác Cổng Sự Thật & An Toàn — Chấm dứt Phẫu thuật Ngữ nghĩa bằng Regex**:
   - Xóa bỏ các hàm regex cắt ghép câu in-place làm cụt ý hội thoại; `OutputTruthValidator` kiểm định đối chiếu với `TurnStrategyContract` và trả `ViolationFeedback` để regenerate có kiểm soát (tối đa 1 lần) hoặc chuyển sang `DeterministicFallbackBuilder`.

---

## 2. Sơ đồ 8 Miền Kiến trúc Hợp lý (8 Logical Domains A–H)

```mermaid
flowchart TB
    subgraph Domain_A["A. Inbound/Outbound Gateway & Session Control"]
        Ctrl["FanpageBotController\n(Webhook Lifecycle, Burst Debounce, Graph API Sender)"]
        Inb["InboundSessionPersistenceService\n(Tx-Entry, Dedup external_mid, Layer-1 & Layer-2 Exit Fence)"]
        WAL["DurableInboundWalRecoveryStore"]
        Epoch["SenderEpochRegistry"]
        Ledger["SentBubbleLedger"]
    end

    subgraph Domain_G["G. Thin Sales Turn Orchestrator (<= 400 LOC)"]
        Orch["SalesTurnOrchestrator\n(Pure Sales-Turn Lifecycle Coordinator)"]
    end

    subgraph Domain_B["B. Conversation Memory & Customer Intelligence"]
        Mem["ConversationMemoryStore\n(DB-First Write-Through + Evict-on-Error)"]
        OCC["ConversationSessionOptimisticMergeCoordinator\n(Tx-Commit JPA @Version OCC + Monotonic Merge)"]
        Intel["CustomerIntelligenceLedger\n(FACT | HYPOTHESIS | UNKNOWN + Lifecycle)"]
        Rel["CustomerRelationshipState\n(pronoun, stage, customerLocation)"]
        Book["BookingProgressState\n(serviceId, branchId, timeSlot, partySize, phone, appliedPatchIds)"]
    end

    subgraph Domain_C["C. Knowledge, Catalog & Branch Intelligence"]
        RAG["KnowledgeRetrievalService"]
        Cat["CatalogIntelligenceService"]
        Branch["BranchIntelligenceService (v1 Static/Semi-Static)\n(customerLocation vs preferredBranchId, availabilityMode)"]
        Pol["PagePolicyConfigService"]
    end

    subgraph Domain_D["D. Turn Understanding & Strategy Planner"]
        Sel["ExecutionModeSelector\n(Complexity, Ambiguity, Risk & Context Router)"]
        Und["TurnUnderstandingEngine"]
        Plan["SalesStrategyPlanner"]
        Contract["TurnStrategyContract\n(bookingMissingFields vs nextBookingQuestionTarget)"]
        Skill["SkillSelectionEngine"]
    end

    subgraph Domain_E["E. Response Composer & Budget Tracker"]
        Prompt["ResponsePromptComposer"]
        Gen["ConsultativeResponseGenerator\n+ TurnLlmBudgetTracker (Logical vs HTTP Attempts)"]
        Pace["BubblePacingFormatter\n(Presentation-only: SKU mask, whitespace, reading delay)"]
    end

    subgraph Domain_F["F. Output Truth & Safety Guard"]
        Truth["OutputTruthValidator\n(Zero Semantic Regex Surgery)"]
        Safe["SafetyAndEscalationGuard"]
        Fall["DeterministicFallbackBuilder"]
    end

    subgraph Domain_H["H. Audit, Telemetry & Evaluation (R8)"]
        Trace["DecisionTraceBuilder"]
        Tele["TurnTelemetryRecorder\n(TurnEvaluationTelemetry)"]
        Eval["TranscriptEvaluationHarness\n(SC01-SC10 Business + SC11-SC15 Guard Suite)"]
    end

    Ctrl --> Inb --> Orch
    Orch --> Mem --> OCC
    Orch --> RAG & Cat & Branch & Pol
    Orch --> Sel --> Und --> Plan --> Contract
    Skill --> Contract
    Contract --> Prompt --> Gen --> Truth & Safe
    Truth -->|Pass| Pace
    Truth -->|ViolationFeedback (Max 1 Regen)| Gen
    Safe -->|Hard Escalation / Budget Exhausted| Fall
    Pace --> Orch --> Inb --> Ledger & Ctrl
    Orch --> Trace & Tele
```

---

## 3. Thiết kế `BranchIntelligenceService` v1 (`R7`)

Chi tiết các quy tắc nghiệp vụ đã được khóa trong [`TURN_STRATEGY_CONTRACT.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/TURN_STRATEGY_CONTRACT.md#L120-L150):
1. **Phạm vi v1**: Chỉ sử dụng dữ liệu tĩnh / bán tĩnh từ cấu hình Page, Knowledge Base và Playbook; không mở rộng thành hệ thống POS hoặc quản lý công suất phòng thời gian thực.
2. **Tách biệt `customerLocation` và `preferredBranchId`**:
   - `customerLocation` thuộc `CustomerRelationshipState` (nơi khách đang ở).
   - `preferredBranchId` thuộc `BookingProgressState` (chi nhánh khách muốn đặt).
   - Khi `customerLocation` khác tỉnh/thành với chi nhánh của Page, bật tín hiệu tư vấn `branchLocationMismatch = true` để bot thông báo rõ vị trí chi nhánh và hỏi xác nhận lịch sự, **không tự động kết luận khách không thể tới chi nhánh**.
3. **Phân biệt `UNKNOWN_REALTIME` theo giai đoạn**:
   - Trong **tư vấn chung (`BookingProgressState.status == NONE`)**: Trả lời trung thực rằng phòng trống thực tế cần lễ tân kiểm tra theo giờ và kích hoạt `CONDITIONAL_BUSINESS_HANDOFF` nếu khách hỏi chuyên sâu về chỗ trống ngay lúc đó.
   - Trong **luồng chốt lịch (`serviceId`, `branchId`, `timeSlot` đã có)**: Giữ nguyên luồng đặt lịch, thu thập nốt `phone` (nếu thiếu), chuyển `BookingProgressState.status = AWAITING_AVAILABILITY_CHECK` và gắn `BOOKING_CONFIRMATION_HANDOFF` để nhân viên xác nhận phòng thực tế cho khách.
4. **Không gây deadlock trên Page nhiều chi nhánh**: Khi khách muốn đặt lịch trên Page nhiều chi nhánh, `SalesStrategyPlanner` cho phép gộp `[BRANCH, TIME_SLOT]` vào cùng 1 câu hỏi tự nhiên (`nextBookingQuestionTarget`).

---

## 4. Hệ thống Telemetry Học tập & Đánh giá (`R8 — Learning & Evaluation Telemetry`)

Để đánh giá chất lượng tư vấn và hiệu quả chiến thuật một cách khoa học, `TurnTelemetryRecorder` (Miền H) thu thập cấu trúc `TurnEvaluationTelemetry` trên mỗi lượt:

| Nhóm Chỉ số (`R8`) | Các Trường Telemetry Cụ thể | Mục đích Đo lường & Phân tích |
|---|---|---|
| **1. `conversationProgression`** | `stageBefore`, `stageAfter`, `progressionOutcome` (`ADVANCED`, `STALLED`, `REGRESSED`, `PAUSED_GRACEFULLY`, `CONVERTED_TO_BOOKING`, `HANDOFF`) | Đo xem mỗi lượt trả lời của bot giúp cuộc hội thoại tiến lên hay bị giậm chân tại chỗ. |
| **2. `customerAcceptanceSignal`** | `priorTurnCustomerReaction` (`ACCEPTED_OPTION`, `REJECTED_OPTION`, `CORRECTED_BOT_ASSUMPTION`, `COMPLAINED_STYLE`, `ANSWERED_SLOT`, `DEFERRED`) | Đánh giá phản ứng thực tế của khách ở lượt $N$ đối với chiến thuật của bot ở lượt $N-1$. |
| **3. `bookingConversion`** | `bookingStatusBefore`, `bookingStatusAfter`, `capturedFieldsCount`, `missingFieldsCount`, `hasConfirmedPhone` | Theo dõi phễu chuyển đổi từ lúc khách quan tâm đến khi thu thập đủ thông tin đặt lịch. |
| **4. `dropOffContext`** | `lastAssistantTurnGoal`, `lastPricePolicyMode`, `lastSelectedSkills`, `hadUnresolvedObjection`, `idleDurationSeconds` | Xác định điểm gãy (khách im lặng bỏ đi sau bước báo giá hay sau câu hỏi chi nhánh). |
| **5. `objectionCategories`** | `activeObjectionTypes` (`PRICE_TOO_HIGH`, `DURATION_TOO_LONG`, `BRANCH_DISTANCE`, `SERVICE_COMPONENT_DISLIKE`, `TRUST_CONCERN`) | Thống kê các rào cản thực tế khiến khách ngập ngừng theo từng Page. |
| **6. `repeatedQuestionAudit`** | `repeatedQuestionDetected` (`boolean`), `repeatedSlotOrFactKey` | Phát hiện lỗi bot hỏi lại thông tin đã là `FACT` trong `CustomerIntelligenceLedger`. |
| **7. `strategyEffectiveness`** | `(turnGoal, pricePolicyMode, selectedSkills) -> nextTurnCustomerReaction` | Dữ liệu đầu vào cho Cold-Path Nightly Analysis để biết skill/chiến thuật nào hiệu quả hay gây phản cảm. |
| **8. `llmExecutionMetrics`** | `executionMode` (`FAST_SINGLE_PASS` \| `TWO_PHASE_STRATEGIC`), `logicalLlmCalls`, `actualHttpAttempts`, `phase1LatencyMs`, `phase2LatencyMs`, `totalTurnLatencyMs`, `promptTokens`, `completionTokens`, `reasoningTokens`, `validatorRegenerateCount`, `deterministicFallbackUsed` | Giám sát chặt chẽ độ trễ, chi phí token, tỷ lệ phải regenerate và tỷ lệ rơi về fallback. |

> [!NOTE]
> **Nguyên tắc Trung thực Kỹ thuật (`R8 Disclaimer`)**: Việc thiết kế kiến trúc mới và bộ telemetry trên giúp loại bỏ lỗi cắt câu cơ học và kiểm soát chặt chẽ chính sách nghiệp vụ; tuy nhiên, **chúng tôi tuyệt đối không tuyên bố kiến trúc mới làm tăng tỷ lệ chốt đơn (conversion rate) thực tế** cho đến khi có số liệu đo kiểm thực nghiệm trên lưu lượng khách hàng thật.

---

## 5. Kế hoạch Phân rã 8 God Classes sang Kiến trúc Đích (Mục VIII Master Directive)

| God Class hiện tại (LOC) | Sẽ được phân rã / chuyển giao vào các Module Đích | Trạng thái cuối sau `P7` |
|---|---|---|
| `CleanAgentOrchestratorService` (`6,718 LOC`) | - Dọn dẹp nhánh `executeCleanAgentTurn` (~3,840 LOC không chạy trên prod)<br/>- Tách điều phối lượt bán hàng sang `SalesTurnOrchestrator` (<= 400 LOC)<br/>- Tách trace sang `DecisionTraceBuilder` | Giữ facade mỏng tương thích ngược hoặc thay thế bởi `SalesTurnOrchestrator` |
| `CleanAgentOutputRefereeAndSanitizer` (`7,820 LOC`) | - `OutputTruthValidator` (kiểm tra giá, gói, `FACT`/`RETRACT`, `FalseApology`, `TurnStrategyContract.mustNotDo`)<br/>- `SafetyAndEscalationGuard` (y tế, bảo mật, AI honesty)<br/>- `DeterministicFallbackBuilder`<br/>- **Xóa bỏ các hàm regex cắt ghép câu in-place (`Semantic Surgery`)** | Giảm > 70% LOC, tách thành 3 class chuyên biệt trong `guard/` |
| `ConversationState` (`5,928 LOC`) | - Chuyển quyền sở hữu state sang `ConversationMemoryStore` (`CustomerIntelligenceLedger`, `CustomerRelationshipState`, `BookingProgressState`)<br/>- Loại bỏ bản sao `StructuredBookingState` trong `SESSION_CACHE` | Thu gọn thành DTO/Snapshot bất biến nhẹ |
| `CleanAgentCatalogAndPromptBuilder` (`5,773 LOC`) | - Dọn dẹp `buildCleanAgentPrompt` cũ (~1,995 LOC)<br/>- Tách xếp hạng gói sang `CatalogIntelligenceService`<br/>- Tách dựng prompt sang `ResponsePromptComposer` | Tách thành `CatalogIntelligenceService` + `ResponsePromptComposer` |
| `AdaptiveConversationIntelligence` (`5,558 LOC`) | - Tách hiểu ý định sang `TurnUnderstandingEngine` + `ExecutionModeSelector`<br/>- Tách lập kế hoạch sang `SalesStrategyPlanner` + `TurnStrategyContract`<br/>- Loại bỏ `reconcileWithCurrentUtterance` (665 dòng regex ghi đè hậu kiểm) | Tách thành `TurnUnderstandingEngine` + `SalesStrategyPlanner` + `TurnStrategyContract` |
| `FanpageBotService` (`5,152 LOC`) | - Giữ phần Gateway điều phối `handleCustomerMessage` (~650 LOC)<br/>- Dọn dẹp ~2,550 LOC hàm V1 chết (`L1743–L4290`) | Thu gọn còn < 1,200 LOC |
| `PrimaryActionRouter` (`4,703 LOC`) | - Chuyển `isStrictSystemOptOutMessage` sang `SafetyAndEscalationGuard`<br/>- Cô lập/dọn dẹp ~4,500 LOC máy trạng thái V1 không còn dùng | Decommission / thu gọn còn enum telemetry |
| `AgentBrainService` (`3,858 LOC`) | - Hợp nhất thành `ConversationMemoryStore` (xóa nhánh legacy `recallAndAdvise` V1)<br/>- Chuẩn hóa lưu trữ `FACT`/`HYPOTHESIS`/`UNKNOWN` và `BookingProgressState` | Tinh gọn, giữ 100% tương thích JSONB PostgreSQL |
