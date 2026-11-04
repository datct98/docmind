# MODULE OWNERSHIP & STATE OWNERSHIP SPECIFICATION

> **Phase Gate**: `P2 — Architecture Design`  
> **Baseline Commit**: `f091ba557a079da86c8ae14cd7ccc19fe8ece59d`

---

## 1. Bản đồ Quyền sở hữu Module Đích (`MODULE_OWNERSHIP.md`)

Mỗi module trong kiến trúc đích (`Adaptive Sales Intelligence System`) sở hữu một trách nhiệm duy nhất (Single Responsibility Principle) và giao tiếp qua các DTO/Contract bất biến:

| Miền (Domain) | Tên Module / Class Đích | Trách nhiệm Sở hữu Duy nhất (Must Own) | Điều Cấm Tuyệt Đối (Must NOT Do) |
|---|---|---|---|
| **A. Gateway & Persistence** | `InboundSessionPersistenceService` | Kiểm tra `HUMAN_TAKEOVER`, `OPT_OUT`, Re-consent, chống trùng `external_mid`, ghi WAL và bảng `conversation_sessions` / `conversation_messages`. | Không phân tích ý định bán hàng, không gọi LLM, không dựng prompt. |
| **A. Gateway & Persistence** | `SenderEpochRegistry` & `SentBubbleLedger` | Quản lý epoch chống race condition khi khách nhắn dồn dập và lưu sổ cái bong bóng thực gửi. | Không can thiệp nội dung câu trả lời. |
| **B. Memory & State** | `ConversationMemoryStore` | Lưu và nạp nguyên tử `CustomerRelationshipState`, `ConfirmedFactLedger`, `BookingProgressState`, `ConversationTurnLedger` (In-JVM + PostgreSQL JSONB `aci_memory_state_json`). | Không chứa regex phân loại câu hỏi hay logic lắp ghép câu trả lời. |
| **C. Intelligence** | `KnowledgeRetrievalService` | Truy xuất Hybrid RAG (BM25 + pgvector 384-dim + Reciprocal Rank Fusion) và nạp các quy tắc `LearnedRuleDto` đã duyệt của Page. | Không tự ý cắt bỏ gói dịch vụ hợp lệ hoặc quyết định chiến thuật trả lời. |
| **C. Intelligence** | `CatalogIntelligenceService` | Bóc tách gói dịch vụ từ tài liệu, xếp hạng `PRIMARY` / `ECONOMICAL_OPTION`, lọc theo vùng miền hoặc thành phần khách từ chối (`retractedFacts`). | Không tự sinh văn bản trả lời khách. |
| **C. Intelligence** | `BranchIntelligenceService` | Quản lý cấu trúc chi nhánh của Page (`singleBranch` vs `multiBranch`), thông tin từng chi nhánh, tình trạng chỗ trống tĩnh/bán tĩnh và quyết định `BranchTurnContext`. | Không gọi API đặt lịch thời gian thực bên ngoài phạm vi v1. |
| **D. Strategy Planner** | `TurnUnderstandingEngine` & `SalesStrategyPlanner` | Phân tích đa ý định, cập nhật `ConfirmedFactLedger` delta, áp dụng chính sách kinh doanh đã khóa (Mục V & VI) để xuất ra `TurnStrategyContract` bất biến. | Không trực tiếp viết lời thoại gửi khách. |
| **D. Strategy Planner** | `SkillSelectionEngine` | Chọn tối đa 1–2 kỹ năng (`AgentSkillDto`) khớp với `TurnGoal` và chặn mọi skill bán hàng khi khách đang phàn nàn/đính chính. | Không ghi đè `pricePolicyMode` của `SalesStrategyPlanner`. |
| **E. Composer** | `ResponsePromptComposer` & `ConsultativeResponseGenerator` | Chuyển `TurnStrategyContract` thành prompt ngắn gọn, gọi LLM sinh `reply_bubbles` tự nhiên và định dạng nhịp gửi qua `BubblePacingFormatter`. | Không tự ý thay đổi gói dịch vụ hay chính sách giá ngoài `TurnStrategyContract`. |
| **F. Guard** | `OutputTruthValidator` & `SafetyAndEscalationGuard` | Kiểm định giá/tên gói đối chiếu catalog, kiểm tra tuân thủ `TurnStrategyContract.mustNotDo`, kiểm tra an toàn y tế/bảo mật, kích hoạt tạo lại (regenerate) kèm lỗi cụ thể hoặc gọi `DeterministicFallbackBuilder`. | **TUYỆT ĐỐI KHÔNG dùng regex cắt ghép, xóa câu hay nối câu vào bong bóng của LLM.** |
| **G. Orchestrator** | `SalesTurnOrchestrator` | Điều phối tuần tự các bước từ A -> B -> C -> D -> E -> F -> H trong <= 400 dòng code. | Không chứa prompt template, không chứa regex nghiệp vụ. |
| **H. Audit & Eval** | `DecisionTraceBuilder` & `TurnTelemetryRecorder` | Ghi nhận `DecisionTrace` JSON và telemetry độ trễ/token/chế độ thực thi cho từng lượt. | Không làm thay đổi trạng thái nghiệp vụ hay kết quả trả về. |

---

## 2. Quyền sở hữu Trạng thái Đích (`STATE_OWNERSHIP.md`)

Bảng dưới đây khóa chặt **nguồn sự thật duy nhất (Single Authoritative Owner)** cho từng nhóm dữ liệu trong hệ thống đích:

| Thực thể Trạng thái (State Entity) | Owner Duy nhất (Read/Write Authority) | Nơi Lưu Bền Vững (Durable Backing) | Quy tắc Cập nhật (Mutation Rule) |
|---|---|---|---|
| `SessionControlState` (`controlMode`, `optedOut`, `lastInboundMid`) | `InboundSessionPersistenceService` | Bảng `conversation_sessions` (cột `control_mode`, `opted_out`, `version`) | Cập nhật nguyên tử qua SQL transaction trước khi vào Orchestrator và kiểm tra lại ở Layer-2 Exit Fence. |
| `ConfirmedFactLedger` (`confirmedPains`, `symptomDuration`, `preferences`, `retractedFacts`) | `ConversationMemoryStore` | `conversation_sessions.aci_memory_state_json` (PostgreSQL JSONB) | Chỉ được cập nhật một lần mỗi lượt sau khi `TurnUnderstandingEngine` xác thực bằng chứng từ lời khách (`CustomerStatementRecord`). |
| `CustomerRelationshipState` (`customerPronoun`, `pronounConfidence`, `relationshipStage`, `priceObjectionCount`, `lastTurnGoal`) | `ConversationMemoryStore` | `conversation_sessions.aci_memory_state_json` | Nếu chưa xác định giới tính -> giữ `"mình"` (hoặc `"bạn"` theo cấu hình Page). Khi khách đính chính (`"gọi anh nhé"`) -> khóa `pronounConfidence = 1.0`. |
| `BookingProgressState` (`serviceId`, `branchId`, `timeSlot`, `partySize`, `phone`, `status`, `missingBookingFields`) | `ConversationMemoryStore` | `conversation_sessions.aci_memory_state_json` | **Xóa bỏ bản sao rời rạc trong `ConversationState.SESSION_CACHE`**. Chỉ cập nhật qua `BookingPatchValidator` của `SalesStrategyPlanner`. Không bao giờ tự reset về `NONE` khi khách hỏi xen câu hỏi phụ hoặc chê đắt. |
| `ConversationTurnLedger` & `SentBubbleLedger` | `SentBubbleLedger` (`JdbcSentBubbleStore`) | Bảng `sent_bubble_ledger_entries` & `conversation_messages` | Ghi nhận đúng các bong bóng thực tế đã vượt qua `OutputTruthValidator` và gửi tới khách hàng. |
