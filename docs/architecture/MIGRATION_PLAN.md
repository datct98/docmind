# MIGRATION PLAN — PHASED REFACTORING ROADMAP (P0 -> P8)

> **Phase Gate**: `P2 — Architecture Design (Mục X Master Directive)`  
> **Baseline Commit Before P0**: `244a2ec8a120878f8f863cb0388d72d51ae8bc2e`  
> **Current HEAD After P0**: `f091ba557a079da86c8ae14cd7ccc19fe8ece59d`

---

## 1. Lộ trình Thực thi Theo Từng Phase Gate (Strict Gated Execution)

Không một phase nào được phép vượt rào khi phase trước chưa có bằng chứng kiểm thử và commit riêng. Đặc biệt, **từ `P2` sang `P3` bắt buộc phải có phê duyệt Architecture Lock của Chủ dự án**.

| Phase | Tên Giai đoạn | Phạm vi Thực hiện | Tiêu chí Nghiệm thu (Exit Criteria) | Trạng thái |
|---|---|---|---|---|
| **P0** | **Runtime Cleanup** | Xác minh và gỡ bỏ hoàn toàn dịch vụ `agent-brain` `:8095` độc lập; giữ nguyên `doc-service-py` `:8091` và Java `AgentBrainService`. | - 0 container/listener trên cổng `8095`<br/>- Xóa thư mục `agent-brain/`<br/>- Pass JUnit `testP31`<br/>- Commit riêng `f091ba5` | **COMPLETED (`f091ba5`)** |
| **P1** | **Architecture Discovery** | Khảo sát toàn bộ repository ở chế độ chỉ đọc đối với logic nghiệp vụ; lập hồ sơ 8 God classes, luồng thực thi, quyền sở hữu state, mã chết và xung đột chính sách kinh doanh. | - Hoàn thành 5 tài liệu Discovery trong `docs/architecture/` với bằng chứng dòng code thực tế. | **COMPLETED** |
| **P2** | **Architecture Design & Independent Review** | Bàn giao gói Discovery & Design cho `Qwen3.8-Max` phản biện độc lập; tổng hợp phản biện, hoàn thiện thiết kế kiến trúc đích (`Adaptive Sales Intelligence System`) và **DỪNG TẠI ARCHITECTURE LOCK**. | - Có báo cáo phản biện độc lập từ `Qwen3.8-Max`<br/>- Hoàn thiện trọn bộ tài liệu `P2` trong `docs/architecture/`<br/>- **Chủ dự án phê duyệt Architecture Lock**. | **IN PROGRESS (Stopping at Gate)** |
| **P3** | **Core Decomposition & State Consolidation** | - Tạo khung các module miền B, D, G (`ConversationMemoryStore`, `ConfirmedFactLedger`, `BookingProgressState`, `TurnStrategyContract`, `SalesTurnOrchestrator`).<br/>- Hợp nhất quyền sở hữu trạng thái đặt lịch và sự thật khách hàng.<br/>- Dọn dẹp nhánh chết `executeCleanAgentTurn` và các hàm V1 chết trong `FanpageBotService`. | - Không còn nhân bản `StructuredBookingState`<br/>- Toàn bộ unit tests cốt lõi xanh<br/>- Commit riêng cho `P3`. | **LOCKED (Waiting Approval)** |
| **P4** | **Branch & Catalog Intelligence** | - Triển khai `BranchIntelligenceService` (v1 tĩnh/bán tĩnh: 1 vs N chi nhánh, thông tin chi nhánh, khác biệt dịch vụ/giá theo chi nhánh, tình trạng chỗ trống tĩnh/bán tĩnh, handoff khi cần kiểm tra lịch thời gian thực).<br/>- Tách `CatalogIntelligenceService` khỏi `CleanAgentCatalogAndPromptBuilder`. | - Unit tests & integration tests cho 1 chi nhánh vs nhiều chi nhánh và handoff chỗ trống thời gian thực<br/>- Commit riêng cho `P4`. | **LOCKED (Waiting Approval)** |
| **P5** | **Turn Strategy & Consultative Composer** | - Triển khai `TurnUnderstandingEngine`, `SalesStrategyPlanner` và `ExecutionModeSelector` (Hybrid Adaptive: Fast-Path 1-Call vs Two-Phase Strategic).<br/>- Áp dụng chính sách kinh doanh đã khóa tại Mục VI (bao gồm `FIRST_TURN_BARE_PRICE_CLARIFY_FIRST` cho Mục VI.1).<br/>- Triển khai `ResponsePromptComposer` & `ConsultativeResponseGenerator`. | - Kiểm chứng hành vi đúng Mục VI.1–VI.7 trên cả Fast-Path và Two-Phase<br/>- Commit riêng cho `P5`. | **LOCKED (Waiting Approval)** |
| **P6** | **Guardrail Simplification** | - Tách `OutputTruthValidator`, `SafetyAndEscalationGuard`, `DeterministicFallbackBuilder` từ `CleanAgentOutputRefereeAndSanitizer`.<br/>- **Gỡ bỏ các hàm regex cắt ghép câu in-place**; thay bằng cơ chế trả `ViolationFeedback` để regenerate có kiểm soát.<br/>- Giữ nguyên sự thật catalog, chống xin lỗi sai (`FalseApology`) và chống nhắc lại `retractedFacts`. | - 0 regex cắt ghép làm cụt câu hội thoại<br/>- 100% kiểm định giá/gói/an toàn vượt qua test<br/>- Commit riêng cho `P6`. | **LOCKED (Waiting Approval)** |
| **P7** | **Full Verification & Live Transcript Evaluation** | - Chạy toàn bộ unit tests, integration tests và bộ 10 kịch bản hội thoại thực tế (`SC01..SC10`) qua pipeline thật (`qwen3.8-max`).<br/>- Kiểm tra chéo độc lập lần 2 với `Qwen3.8-Max`. | - Điểm trung bình >= 85/100, không kịch bản nào < 80, 0 lỗi bịa giá/sai nghiệp vụ, kèm transcript thật<br/>- Commit riêng cho `P7`. | **LOCKED (Waiting Approval)** |
| **P8** | **Final Clean Commit & Handover** | - Kiểm tra sạch sẽ Git working tree, cập nhật tài liệu kiến trúc cuối cùng và bàn giao báo cáo nghiệm thu. | - Working tree sạch, Docker runtime healthy, đầy đủ hướng dẫn rollback. | **LOCKED (Waiting Approval)** |

---

## 2. Chiến lược Chuyển đổi Không Gián đoạn (Zero-Downtime Strangler Pattern)

1. **Bước 1 (Adapter & Contract Extraction)**: Định nghĩa các record bất biến (`TurnStrategyContract`, `BookingProgressState`, `BranchTurnContext`) và tạo các service mới bên cạnh class cũ.
2. **Bước 2 (State Bridge)**: Đảm bảo `ConversationMemoryStore` đọc/ghi tương thích 100% với cột JSONB `conversation_sessions.aci_memory_state_json` hiện có.
3. **Bước 3 (Pipeline Cutover)**: Chuyển `FanpageBotService.handleCustomerMessageInternal` sang gọi `SalesTurnOrchestrator` và rút bỏ dần các khối mã chết trong `CleanAgentOrchestratorService`, `CleanAgentOutputRefereeAndSanitizer`, `FanpageBotService`.
4. **Bước 4 (Test Migration)**: Cập nhật các bài test cũ đang gọi trực tiếp `executeCleanAgentTurn` sang kiểm thử qua cổng chính thức `FanpageBotService.handleCustomerMessage` hoặc `SalesTurnOrchestrator`.
