# MODULE OWNERSHIP & GATEWAY–ORCHESTRATOR BOUNDARY SPECIFICATION

> **Document Authority**: **TO-BE NORMATIVE SPECIFICATION** (Quy định thiết kế đích về ranh giới 8 miền A–H và ranh giới cứng giữa Gateway với `SalesTurnOrchestrator` theo `R4`; có hiệu lực cao hơn phần kiểm toán hiện trạng trong `ARCHITECTURE_DISCOVERY_REPORT.md`).  
> **Phase Gate**: `P2 — Architecture Lock Pending Project Owner Approval`  
> **Approval Status**: **PROPOSED** *(Chỉ chuyển sang `ACCEPTED` sau khi Chủ dự án phê duyệt Architecture Lock)*  
> **Baseline Commit Before P0**: `244a2ec8a120878f8f863cb0388d72d51ae8bc2e`  
> **P0 Cleanup Commit**: `f091ba557a079da86c8ae14cd7ccc19fe8ece59d`  
> **P2 Initial Draft Commit**: `5aa985d43b5ef6ea1621f37fa546bcae520633c2`

---

## 1. Bản đồ Quyền Sở hữu Module Đích (`MODULE_OWNERSHIP.md` — 8 Miền A–H)

Mỗi module trong kiến trúc đích (`Adaptive Sales Intelligence System`) sở hữu một trách nhiệm duy nhất (Single Responsibility Principle) và giao tiếp qua các DTO/Contract bất biến:

| Miền (Domain) | Tên Module / Class Đích | Trách nhiệm Sở hữu Duy nhất (Must Own) | Điều Cấm Tuyệt Đối (Must NOT Do) |
|---|---|---|---|
| **A. Inbound/Outbound Gateway** | `FanpageBotController`, `FanpageBotService` (Gateway Layer), `InboundSessionPersistenceService`, `DurableInboundWalRecoveryStore`, `SenderEpochRegistry`, `OutboundDeliveryDispatcher`, `SentBubbleLedger` | Vòng đời Meta Webhook, gom tin burst (`burstDebounceMs`), chống trùng `external_mid`, quản lý `SenderEpochRegistry`, WAL recovery, kiểm soát phiên (`HUMAN_TAKEOVER`, `OPT_OUT`, Re-consent), `Layer-2 Outbound Exit Fence`, phát bong bóng tuần tự ra Messenger từ Outbox `PENDING` và cập nhật trạng thái `ACCEPTED_BY_CHANNEL` / `FAILED` / `UNKNOWN` / `DELIVERED` (khi có delivery receipt thực tế). | **CẤM** phân loại ý định bán hàng (`SalesIntent`), **CẤM** chọn chính sách giá hay gói dịch vụ, **CẤM** gọi LLM tư vấn. |
| **B. Memory & State** | `ConversationMemoryStore`, `ConversationSessionOptimisticMergeCoordinator` | Lưu và nạp nguyên tử `CustomerIntelligenceLedger` (`FACT` / `HYPOTHESIS` / `UNKNOWN`), `CustomerRelationshipState`, `BookingProgressState` (PostgreSQL JSONB `aci_memory_state_json` + JPA `@Version` OCC) cùng việc khởi tạo bản ghi outbound `PENDING` trong `Tx-Commit`. | **CẤM** chứa regex phân loại câu hỏi hay logic lắp ghép câu trả lời; **CẤM** ghi `DELIVERED` trước khi kênh nhận thành công. |
| **C. Intelligence** | `KnowledgeRetrievalService`, `CatalogIntelligenceService`, `BranchIntelligenceService`, `PagePolicyConfigService` | Truy xuất Hybrid RAG, xếp hạng gói dịch vụ (`PRIMARY` / `ECONOMICAL_OPTION`), lọc bỏ phạm vi đã rút lại (`retractedFacts`), quản lý cấu hình chi nhánh tĩnh/bán tĩnh (`BranchTopology`, `customerLocation` vs `preferredBranchId`). | **CẤM** tự sinh văn bản trả lời khách; **CẤM** gọi hệ thống POS/lịch thời gian thực bên ngoài phạm vi v1. |
| **D. Strategy Planner** | `TurnUnderstandingEngine`, `ExecutionModeSelector`, `SalesStrategyPlanner`, `TurnStrategyContract`, `SkillSelectionEngine` | Phân tích đa ý định & độ phức tạp quyết định, cập nhật ứng viên `FACT`/`HYPOTHESIS` delta, áp dụng chính sách kinh doanh đã khóa (Mục V & VI) để xuất ra `TurnStrategyContract` bất biến trước khi sinh lời thoại. | **CẤM** trực tiếp viết lời thoại gửi khách; **CẤM** dùng độ dài câu đơn thuần để chọn `FAST_SINGLE_PASS`. |
| **E. Composer** | `ResponsePromptComposer`, `ConsultativeResponseGenerator`, `BubblePacingFormatter` | Chuyển `TurnStrategyContract` thành prompt ngắn gọn, gọi LLM (tuân thủ `TurnLlmBudgetTracker`) để sinh `reply_bubbles` tự nhiên và định dạng biểu diễn (`BubblePacingFormatter`). | **CẤM** tự ý thay đổi gói dịch vụ, chính sách giá hay `mustNotDo` ngoài `TurnStrategyContract`. |
| **F. Guard** | `OutputTruthValidator`, `SafetyAndEscalationGuard`, `DeterministicFallbackBuilder` | Kiểm định giá/tên gói đối chiếu catalog, kiểm tra tuân thủ `TurnStrategyContract.mustNotDo`, kiểm tra an toàn y tế/bảo mật, kích hoạt tối đa 1 lần regenerate kèm `ViolationFeedback` hoặc gọi `DeterministicFallbackBuilder`. | **TUYỆT ĐỐI CẤM dùng regex cắt ghép, xóa câu hay nối câu vào bong bóng của LLM (`Zero Semantic Surgery`).** |
| **G. Orchestrator** | `SalesTurnOrchestrator` | Chỉ điều phối vòng đời một lượt tư vấn bán hàng (`Sales-Turn Lifecycle`) sau khi Gateway đã xác nhận sự kiện inbound hợp lệ. Trả về `SalesTurnOutcome` thuần túy cho Gateway. | **CẤM** tự quản lý Meta webhook, burst debounce, WAL file hay tự phát tin nhắn trực tiếp ra Facebook Graph API. |
| **H. Audit & Eval** | `DecisionTraceBuilder`, `TurnTelemetryRecorder`, `TranscriptEvaluationHarness` | Ghi nhận `DecisionTrace` JSON và bộ chỉ số `TurnEvaluationTelemetry` (`R8`) cho từng lượt. | **CẤM** làm thay đổi trạng thái nghiệp vụ hay kết quả trả về. |

---

## 2. Ranh giới Cứng Giữa Gateway (Miền A) và `SalesTurnOrchestrator` (Miền G) (`R4`)

### 2.1. Bảng Phân định Trách nhiệm Gateway vs Orchestrator

| Khía cạnh | Gateway Layer (Miền A) | `SalesTurnOrchestrator` (Miền G) |
|---|---|---|
| **Đầu vào (Input)** | HTTP Payload thô từ Meta Webhook (`/webhook`) hoặc Simulator (`/message`). | `ValidatedTurnContext`: Sự kiện inbound đã qua dedup, đã lưu `Tx-Entry`, kèm `epochToken`, `baseSessionVersion` và `hydratedMemory`. |
| **Quản lý Epoch & Concurrency** | Sở hữu `SenderEpochRegistry`: gọi `advanceEpoch(pageId, senderId)` khi nhận tin nhắn mới. | Chỉ gọi hàm đọc `epochToken.isCurrent()` tại các điểm checkpoint để tự hủy sớm (`SUPERSEDED_ABORTED`) nếu có tin nhắn mới hơn. |
| **Quản lý Giao dịch DB** | Sở hữu `Tx-Entry` (ghi nhận inbound message + kiểm tra `HUMAN_TAKEOVER`/`OPT_OUT`), `Layer-2 Exit Fence` (`verifyOutboundStillPermittedFresh`) và `Tx-Delivery-Update` (cập nhật trạng thái `SentBubbleLedger` từ `PENDING` sang `ACCEPTED_BY_CHANNEL` / `FAILED` / `UNKNOWN` / `SUPPRESSED_BY_FENCE`, hoặc `DELIVERED` khi có delivery receipt thực tế). | Ủy quyền cho `ConversationMemoryStore` thực hiện `Tx-Commit` (cập nhật `aci_memory_state_json` + `BookingPatch` + ghi bản ghi outbound ở trạng thái **`PENDING`** qua OCC) ngay sau khi `OutputTruthValidator` thông qua câu trả lời. |
| **Quyền Gửi Tin Nhắn Ra Ngoài** | **Độc quyền**: Chỉ Gateway (`OutboundDeliveryDispatcher`) mới được gọi `sendSequentialBubblesToFacebook` sau khi `Layer-2 Exit Fence` xác nhận phiên vẫn ở trạng thái `BOT_ACTIVE`, `opted_out == false` và `epochToken.isCurrent()`. | **Không có quyền gửi mạng**: Chỉ trả về danh sách `replyBubbles` đề xuất trong `SalesTurnOutcome`. |
| **Quyết định Nghiệp vụ Tư vấn** | **Không có quyền**: Chỉ chặn trực tiếp Jailbreak bảo mật hoặc lệnh dừng hệ thống tường minh (`STOP`/`HỦY ĐĂNG KÝ`). | **Độc quyền điều phối**: Gọi Miền C, D, E, F để lập `TurnStrategyContract`, sinh câu trả lời và kiểm định sự thật. |

### 2.2. Sequence Diagram Ranh giới Gateway ↔ `SalesTurnOrchestrator` & Các Điểm Commit / Abort / Rollback (`R4`)

```mermaid
sequenceDiagram
    autonumber
    participant Meta as Meta Webhook / Client
    participant GW as Gateway (Controller + InboundPersistence + OutboundDispatcher)
    participant Epoch as SenderEpochRegistry (Single-Instance v1)
    participant DB as PostgreSQL (conversation_sessions & sent_bubble_ledger_entries)
    participant Orch as SalesTurnOrchestrator (Domain G)
    participant Mem as ConversationMemoryStore (Domain B)
    participant Pipeline as Domains C + D + E + F (RAG, Planner, Composer, Guard)

    Meta->>GW: POST /webhook (hoặc /message)
    GW->>GW: Burst Debounce (gom tin nhắn dồn dập <= burstDebounceMs)
    GW->>Epoch: advanceEpoch(pageId, senderId) -> cấp epochToken=E_n
    GW->>DB: [COMMIT POINT 1: Tx-Entry (REQUIRES_NEW)]<br/>1. INSERT conversation_messages ON CONFLICT (session_id, external_mid) DO NOTHING<br/>2. Kiểm tra control_mode (HUMAN_TAKEOVER) & opted_out<br/>3. Đọc baseSessionVersion = V_k
    alt Duplicate external_mid hoặc HUMAN_TAKEOVER / OPT_OUT
        DB-->>GW: SUPPRESS_DUPLICATE / BLOCK_TAKEOVER / BLOCK_OPT_OUT
        GW-->>Meta: HTTP 200 (Silent Suppressed — Không gọi Orchestrator)
    else Inbound hợp lệ (BOT_ACTIVE)
        GW->>Mem: refreshTurnBaseFromDb(pageId, senderId, V_k)
        Mem-->>GW: HydratedMemorySnapshot (Version V_k)
        GW->>Orch: executeSalesTurn(ValidatedTurnContext{epochToken=E_n, baseVersion=V_k, memory})
        Orch->>Pipeline: 1. Retrieve Knowledge, Catalog & BranchContextSnapshot (Domain C)
        Orch->>Epoch: [ABORT CHECKPOINT 1: BEFORE_LLM] isCurrent(E_n)?
        alt Epoch E_n đã bị thay thế bởi tin nhắn mới E_{n+1}
            Epoch-->>Orch: false (Superseded)
            Orch-->>GW: SalesTurnOutcome.aborted("SUPERSEDED_BEFORE_LLM")
        else Epoch E_n vẫn còn hiệu lực
            Orch->>Pipeline: 2. TurnUnderstanding & SalesStrategyPlanner -> khóa TurnStrategyContract (Domain D)<br/>3. ConsultativeResponseGenerator (Fast-Path hoặc Two-Phase, Domain E)
            Orch->>Epoch: [ABORT CHECKPOINT 2: AFTER_LLM_BEFORE_GUARD] isCurrent(E_n)?
            Orch->>Pipeline: 4. OutputTruthValidator & SafetyAndEscalationGuard (Domain F)<br/>(Tối đa 1 lần Regenerate nếu vi phạm, hoặc DeterministicFallbackBuilder)
            Orch->>Epoch: [ABORT CHECKPOINT 3: BEFORE_MEMORY_COMMIT] isCurrent(E_n)?
            alt Epoch E_n vẫn còn hiệu lực
                Orch->>Mem: commitTurnMemoryAndOutboxPending(baseVersion=V_k, memoryDelta, bookingPatch, replyBubbles)
                Mem->>DB: [COMMIT POINT 2: Tx-Commit (REQUIRES_NEW + JPA @Version OCC)]<br/>1. UPDATE conversation_sessions SET aci_memory_state_json=..., version=V_k+1 WHERE id=? AND version=V_k<br/>2. INSERT sent_bubble_ledger_entries (status = PENDING, outboundIdempotencyKey)<br/>(Nếu OCC conflict -> Reload freshDb + MonotonicMerge tối đa 6 lần; lỗi terminal -> Evict JVM cache)
                DB-->>Mem: PersistedSessionOutcome(V_{k+1})
                Mem->>Mem: Write-Through cập nhật JVM Cache (chỉ sau khi DB commit thành công)
                Orch-->>GW: SalesTurnOutcome(outboundPendingIds, replyBubbles, decisionTrace, telemetry)
                GW->>DB: [EXIT FENCE CHECK: Layer-2 Fresh Read + Epoch Check]<br/>verifyOutboundStillPermittedFresh(pageId, senderId) && Epoch.isCurrent(E_n)
                alt Nhân viên vừa bấm HUMAN_TAKEOVER hoặc Epoch E_n đã bị thay thế
                    DB-->>GW: Permitted = false
                    GW->>DB: [COMMIT POINT 3A: Tx-Delivery-Update]<br/>UPDATE sent_bubble_ledger_entries SET status = SUPPRESSED_BY_FENCE
                else Permitted = true
                    GW->>Meta: sendSequentialBubblesToFacebook (Gửi từng bong bóng PENDING)
                    GW->>DB: [COMMIT POINT 3B: Tx-Delivery-Update]<br/>UPDATE sent_bubble_ledger_entries SET status = ACCEPTED_BY_CHANNEL (HTTP 200) | FAILED | UNKNOWN (Timeout)
                    Note over Meta,DB: Khi Meta gửi webhook message_deliveries (delivery receipt thực tế):<br/>UPDATE sent_bubble_ledger_entries SET status = DELIVERED
                end
            end
        end
    end
```
