# ARCHITECTURE DECISIONS RECORD (ADR) — DOCMIND ADAPTIVE SALES INTELLIGENCE

> **Document Authority**: **TO-BE NORMATIVE ADR SPECIFICATION** (Ghi nhận toàn bộ quyết định kiến trúc `ADR-01` -> `ADR-09`, trạng thái `Proposed / Accepted / Superseded`, danh sách các giả định chưa kiểm chứng và các quyết định trình Chủ dự án phê duyệt theo `R1–R9`).  
> **Phase Gate**: `P2 — Architecture Lock Pending Project Owner Approval`  
> **Approval Status**: **PROPOSED** *(Trừ `ADR-01` thuộc `P0` đã thực thi, toàn bộ `ADR-02` -> `ADR-09` đều ở trạng thái `PROPOSED` và chỉ chuyển sang `ACCEPTED` sau khi Chủ dự án phê duyệt Architecture Lock)*  
> **Baseline Commit Before P0**: `244a2ec8a120878f8f863cb0388d72d51ae8bc2e`  
> **P0 Cleanup Commit**: `f091ba557a079da86c8ae14cd7ccc19fe8ece59d`  
> **P2 Initial Draft Commit**: `5aa985d43b5ef6ea1621f37fa546bcae520633c2`

---

## 1. Bảng Tổng hợp Trạng thái Các Quyết định Kiến trúc (`Proposed / Accepted / Superseded` — `R9`)

| Mã ADR | Tên Quyết định Kiến trúc | Giai đoạn Áp dụng | Trạng thái Hiện tại (`R9`) | Tài liệu Quy chiếu Chi tiết |
|---|---|---|---|---|
| **ADR-01** | Loại bỏ hoàn toàn Standalone Python Agent Brain (`:8095`), bảo toàn tuyệt đối `doc-service-py` (`:8091`) và Java `AgentBrainService` | `P0` | **ACCEPTED & IMPLEMENTED (`f091ba5`)** | [`RUNTIME_DEPENDENCY_REPORT.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/RUNTIME_DEPENDENCY_REPORT.md) |
| **ADR-02** | Phân rã 8 God Classes (`45,509 LOC`) thành 8 Miền A–H theo Đơn nhiệm (SRP) và thiết lập Ranh giới Cứng giữa Gateway (Miền A) với `SalesTurnOrchestrator` (Miền G) (`R4`) | `P3–P7` | **PROPOSED** *(Pending Owner Lock)* | [`MODULE_OWNERSHIP.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/MODULE_OWNERSHIP.md) |
| **ADR-03** | Hợp nhất Quyền sở hữu Trạng thái vào `ConversationMemoryStore` với Giao thức Nhất quán `Tx-Entry` / `Tx-Commit` (Transactional Outbox `PENDING`), JPA `@Version` OCC + `Monotonic Merge`, `BookingPatch` lũy đẳng, `DB-First Write-Through` và Cam kết Concurrency `Single-Instance` v1 (`R2`) | `P3` | **PROPOSED** *(Pending Owner Lock)* | [`STATE_OWNERSHIP.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/STATE_OWNERSHIP.md) |
| **ADR-04** | Mô hình Trí tuệ Khách hàng Ba Trạng thái (`FACT` / `HYPOTHESIS` / `UNKNOWN`) kèm 4 Nguồn gốc Chuẩn (`CUSTOMER_EXPLICIT`, `CUSTOMER_CONFIRMED`, `BOT_INFERENCE`, `SYSTEM_AUTHORITATIVE`) và 5 Trạng thái Vòng đời (`ADD, UPDATE, SUPERSEDE, RETRACT, UNCERTAIN`) (`R3`) | `P3–P5` | **PROPOSED** *(Pending Owner Lock)* | [`STATE_OWNERSHIP.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/STATE_OWNERSHIP.md) |
| **ADR-05** | Thực thi LLM Thích ứng (`Hybrid Adaptive Execution`), Định tuyến `ExecutionModeSelector` theo Độ phức tạp/Mơ hồ/Rủi ro/Ngữ cảnh và Ngân sách Retry Xuyên Tầng `TurnLlmBudgetTracker` (`R6`) | `P5` | **PROPOSED** *(Pending Owner Lock)* | [`LLM_EXECUTION_OPTIONS.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/LLM_EXECUTION_OPTIONS.md) |
| **ADR-06** | Giải quyết Quan hệ Giữa `maxQuestionsAllowed` và `bookingMissingFields` qua `nextBookingQuestionTarget` (Cho phép gộp tối đa 2 trường liên quan trong 1 câu hỏi tự nhiên) (`R5`) | `P5` | **PROPOSED** *(Pending Owner Lock)* | [`TURN_STRATEGY_CONTRACT.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/TURN_STRATEGY_CONTRACT.md) |
| **ADR-07** | Thiết kế `BranchIntelligenceService` v1 (Tĩnh/Bán tĩnh): Tách biệt `customerLocation` vs `preferredBranchId`, xử lý `branchLocationMismatch` và phân biệt `UNKNOWN_REALTIME` theo giai đoạn (`R7`) | `P4` | **PROPOSED** *(Pending Owner Lock)* | [`TURN_STRATEGY_CONTRACT.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/TURN_STRATEGY_CONTRACT.md) |
| **ADR-08** | Phân loại Chính sách Câu hỏi Giá 3 Trường hợp (Chưa có nhóm dịch vụ vs Đã có nhóm dịch vụ vs Dịch vụ cụ thể/Bảng giá) và Chấm dứt Phẫu thuật Ngữ nghĩa bằng Regex (`Zero Semantic Surgery`) | `P5–P6` | **PROPOSED** *(Pending Owner Lock)* | [`TURN_STRATEGY_CONTRACT.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/TURN_STRATEGY_CONTRACT.md) |
| **ADR-09** | Chuẩn hóa Hệ thống Telemetry Học tập (`R8`) và Khung Nghiệm thu Hai Cổng Tách biệt cho Bộ 15 Kịch bản `SC01–SC15` (`R1`) | `P6–P7` | **PROPOSED** *(Pending Owner Lock)* | [`ACCEPTANCE_TEST_PLAN.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/ACCEPTANCE_TEST_PLAN.md) |
| *(ADR-OLD-01)* | *(Đề xuất cũ trong bản nháp đầu: dùng `pg_advisory_xact_lock` trên mọi lượt cập nhật `aci_memory_state_json`)* | — | **SUPERSEDED BY ADR-03 (`R2`)** *(Chỉ dùng JPA `@Version` OCC + `Monotonic Merge` cho cập nhật thường; không khóa advisory lock khi chưa cần thiết)* | [`STATE_OWNERSHIP.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/STATE_OWNERSHIP.md) |
| *(ADR-OLD-02)* | *(Hành vi tại commit `244a2ec`: Báo ngay gói neo `239k/399k` ở lượt 1 khi khách chỉ nhắn `"Giá?"` chưa có nhóm dịch vụ)* | — | **SUPERSEDED BY ADR-08 (Chờ Chủ dự án xác nhận)** | [`ARCHITECTURE_DISCOVERY_REPORT.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/ARCHITECTURE_DISCOVERY_REPORT.md) |

---

## 2. Chi tiết Các Quyết định Kiến trúc (`ADR-01` -> `ADR-09`)

### ADR-01: Loại bỏ hoàn toàn Standalone Python Agent Brain (`:8095`) và giữ nguyên `doc-service-py` (`:8091`) + In-Process Java `AgentBrainService`
- **Trạng thái**: `ACCEPTED & IMPLEMENTED` (`commit f091ba557a079da86c8ae14cd7ccc19fe8ece59d`).
- **Bằng chứng Git Diff & Runtime**: 0 container/listener trên cổng `8095`. Theo `git show --stat f091ba557a079da86c8ae14cd7ccc19fe8ece59d`: tổng cộng **`23 files changed, 11 insertions(+), 2,714 deletions(-)`**, trong đó **xóa 21 files (`D`)** thuộc thư mục `agent-brain/` (`2,708 dòng bị xóa`) và **cập nhật 2 files (`M`)** (`CleanAgentArchitectureVerificationTest.java` `+5 dòng`; `docs/architecture/docmind_system_architecture.html` `+6/-6 dòng`). Pass `CleanAgentArchitectureVerificationTest#testP31`, bảo toàn 100% `doc-service-py` (`:8091`) và `AgentBrainService.java`.

### ADR-02: Phân rã 8 God Classes & Thiết lập Ranh giới Cứng Gateway ↔ `SalesTurnOrchestrator` (`R4`)
- **Trạng thái**: `PROPOSED`.
- **Quyết định**:
  - Gateway (Miền A) sở hữu độc quyền vòng đời Meta webhook, burst debounce, dedup `external_mid`, `SenderEpochRegistry`, WAL recovery, kiểm soát phiên (`HUMAN_TAKEOVER`, `OPT_OUT`), `Layer-2 Outbound Exit Fence` và `OutboundDeliveryDispatcher` gửi tin ra Messenger. Gateway tuyệt đối không phân tích ý định bán hàng hay chọn gói dịch vụ.
  - `SalesTurnOrchestrator` (Miền G) chỉ điều phối vòng đời lượt tư vấn bán hàng từ `ValidatedTurnContext` và trả về `SalesTurnOutcome`. Tiêu chí nghiệm thu phân rã dựa trên tính tuân thủ ranh giới trách nhiệm (Single Responsibility & Zero Boundary Leak), **không dùng giới hạn cứng số dòng code (LOC) của một class làm acceptance gate**.

### ADR-03: Giao thức Nhất quán Dữ liệu `ConversationMemoryStore`, Transactional Outbox & Giới hạn Concurrency v1 (`R2`)
- **Trạng thái**: `PROPOSED` *(Thay thế đề xuất cũ `ADR-OLD-01` về việc lạm dụng advisory lock)*.
- **Quyết định**:
  - Tách mỗi lượt thành: (1) `Tx-Entry` (`REQUIRES_NEW`), (2) Pha suy luận RAG/LLM không giữ kết nối DB, (3) `Tx-Commit` (`REQUIRES_NEW`) ghi nguyên tử `aci_memory_state_json` + `BookingProgressState` + tạo bản ghi outbound trong `SentBubbleLedger` ở trạng thái **`PENDING`**, và (4) `OutboundDeliveryDispatcher` kiểm tra `Layer-2 Exit Fence` rồi mới gửi tin và cập nhật trạng thái (`ACCEPTED_BY_CHANNEL`, `FAILED`, `UNKNOWN`, hoặc `DELIVERED` khi có receipt thực tế).
  - `BookingPatch` lũy đẳng nhờ khóa tất định `patchId = "bp_" + SHA256_HEX_32(sessionId + ":" + inboundExternalMidOrTurnId + ":" + canonicalMutationsJson)` (lưu trong `applied_booking_patch_ids` giới hạn 32 phần tử FIFO) và kiểm tra `turnSeq`.
  - Cập nhật cache theo nguyên tắc **DB-First, Cache-Second (Write-Through kèm Evict-on-Error)**.
  - **Giới hạn cam kết Concurrency v1**: Topology triển khai thực tế hiện tại là **Single-Instance (`1` container `docmind-api`)**. Vì `SenderEpochRegistry` và `Striped<Lock>` nằm trong bộ nhớ JVM và hệ thống chưa trang bị persistent fencing token hay bài kiểm thử liên instance tại Dispatcher, **v1 chỉ cam kết chặn stale outbound và đảm bảo thứ tự lượt trên môi trường Single-Instance**. Không bổ sung Redis distributed lock khi chưa có nhu cầu thực tế.

### ADR-04: Mô hình Trí tuệ Khách hàng `FACT` / `HYPOTHESIS` / `UNKNOWN` (`R3`)
- **Trạng thái**: `PROPOSED`.
- **Quyết định**:
  - Phân biệt rõ 3 mức nhận thức (`FACT`, `HYPOTHESIS`, `UNKNOWN`), 4 nguồn gốc chuẩn (`sourceType`: `CUSTOMER_EXPLICIT`, `CUSTOMER_CONFIRMED`, `BOT_INFERENCE`, `SYSTEM_AUTHORITATIVE`) và 5 thao tác vòng đời (`ADD, UPDATE, SUPERSEDE, RETRACT, UNCERTAIN`).
  - Khóa cứng 2 luật bất khả xâm phạm: (1) `HYPOTHESIS` (`BOT_INFERENCE`) không bao giờ tự biến thành `FACT` chỉ vì bot lặp lại nó ở lượt sau; (2) Thông tin đã bị khách bác bỏ (`RETRACT`) không bao giờ được hồi sinh trong bộ nhớ hay trong lời tư vấn.

### ADR-05: Định tuyến `ExecutionModeSelector` theo Độ phức tạp & Ngân sách Retry Xuyên Tầng `TurnLlmBudgetTracker` (`R6`)
- **Trạng thái**: `PROPOSED`.
- **Quyết định**:
  - Cấm dùng độ dài câu làm tiêu chí chọn `FAST_SINGLE_PASS`. Định tuyến dựa trên 4 chiều: Decision Complexity, Pragmatic Ambiguity, Policy/Safety Risk, và Conversation Context (bắt buộc định tuyến đúng cả 5 ca câu ngắn phức tạp: `"Thế thôi đắt quá."`, `"Không phải."`, `"Chịu em."`, `"Đặt luôn."`, `"Ừ/Ok"`).
  - Cả `FAST_SINGLE_PASS` và `TWO_PHASE_STRATEGIC` đều tạo và tuân thủ `TurnStrategyContract`.
  - Tách biệt `logicalLlmCalls` (`<= 2` cho Fast-Path, `<= 3` cho Two-Phase) và `actualHttpAttempts` (`<= 3` cho Fast-Path, `<= 4` cho Two-Phase xuyên suốt mọi tầng).

### ADR-06: Giải quyết Quan hệ Giữa `maxQuestionsAllowed` và `bookingMissingFields` (`R5`)
- **Trạng thái**: `PROPOSED`.
- **Quyết định**:
  - Phân biệt `bookingMissingFields` (danh sách toàn bộ trường còn thiếu) với `nextBookingQuestionTarget` (1 hoặc tối đa 2 trường có liên quan mật thiết được hỏi trong lượt hiện tại).
  - Trong luồng đặt lịch, cho phép 1 câu hỏi tự nhiên gộp tối đa 2 trường liên quan (`[BRANCH, TIME_SLOT]` hoặc `[PARTY_SIZE, PHONE]`) để tránh hỏi lắt nhắt và triệt tiêu hoàn toàn nguy cơ deadlock trên cả Page 1 chi nhánh lẫn Page nhiều chi nhánh.

### ADR-07: Thiết kế `BranchIntelligenceService` v1 (`R7`)
- **Trạng thái**: `PROPOSED`.
- **Quyết định**:
  - Giữ phạm vi v1 ở mức tĩnh/bán tĩnh (từ cấu hình/KB/playbook, không mở rộng thành POS hay realtime capacity system).
  - Tách biệt `customerLocation` và `preferredBranchId`; xử lý `branchLocationMismatch` như tín hiệu tư vấn (thông báo rõ vị trí cơ sở và hỏi xác nhận nhẹ nhàng, không tự ý loại khách).
  - Phân biệt `UNKNOWN_REALTIME` khi đang tư vấn chung (`CONDITIONAL_BUSINESS_HANDOFF`) với khi khách đã chốt gói + chi nhánh + giờ hẹn (`BOOKING_CONFIRMATION_HANDOFF` + `AWAITING_AVAILABILITY_CHECK`).

### ADR-08: Chính sách Phân loại Câu hỏi Giá 3 Trường hợp & Chấm dứt Phẫu thuật Ngữ nghĩa bằng Regex
- **Trạng thái**: `PROPOSED`.
- **Quyết định**:
  - Phân biệt rõ 3 trường hợp hỏi giá trong `TurnStrategyContract`:
    1. **Khách chưa xác định nhóm dịch vụ (`"Giá"`, `"Cho hỏi giá"`)**: Áp dụng `FIRST_TURN_BARE_PRICE_CLARIFY_FIRST` -> chào thương hiệu + ghi nhận câu hỏi giá + hỏi 1 câu ngắn để xác định nhu cầu (chưa báo gói/bảng giá ở phản hồi đầu tiên).
    2. **Khách đã xác định nhóm dịch vụ (`"Massage bao nhiêu?"`, `"Gội đầu dưỡng sinh giá sao?"`)**: Áp dụng `CATEGORY_PRICE_QUOTE_OR_MINIMAL_CLARIFY` -> trả ngay mức giá/gói phù hợp của nhóm dịch vụ đó trong catalog hoặc hỏi đúng 1 chi tiết tối thiểu nếu cần.
    3. **Dịch vụ cụ thể đã rõ hoặc khách xin bảng giá trực tiếp (`"Gói cổ vai gáy 60p giá bao nhiêu?"`, `"Cho xin bảng giá"`)**: Áp dụng `DIRECT_SPECIFIC_PRICE_QUOTE` / `PROVIDE_VERIFIED_PRICE_RANGE_OR_REPRESENTATIVE_PACKAGES` -> báo giá trực tiếp từ catalog xác thực.
  - Loại bỏ toàn bộ các hàm regex cắt ghép câu in-place (`Zero Semantic Surgery`) trong `CleanAgentOutputRefereeAndSanitizer`; chỉ giữ xử lý hình thức tại `BubblePacingFormatter`.

### ADR-09: Hệ thống Telemetry Học tập (`R8`) & Khung Nghiệm thu Hai Cổng `SC01–SC15` (`R1`)
- **Trạng thái**: `PROPOSED`.
- **Quyết định**:
  - Thu thập đầy đủ 8 nhóm chỉ số `TurnEvaluationTelemetry` (`R8`); không tuyên bố tăng tỷ lệ chốt đơn thực tế khi chưa có dữ liệu thử nghiệm thực nghiệm.
  - Chạy đầy đủ cả 15 kịch bản `SC01–SC15` trong `P7`, tách biệt hoàn toàn Cổng 1 (`SC01–SC10` Core Business: điểm trung bình `>= 85/100`, min `>= 80/100`, 0 lỗi nghiệp vụ) và Cổng 2 (`SC11–SC15` Regression & Architecture Guards: `5/5 PASS` nhị phân, không gộp vào điểm trung bình hội thoại).

---

## 3. Rà soát Các Giả định Chưa Được Xác minh Thực nghiệm (`Unverified Assumptions Audit` — `R9`)

Tuân thủ nguyên tắc phân biệt rõ điều đã xác minh (`Verified`) và điều chưa xác minh (`Unverified`):
1. **`[UNVERIFIED-01]` Phân bố tỷ lệ lượt `FAST_SINGLE_PASS` vs `TWO_PHASE_STRATEGIC` trên lưu lượng thực tế**:
   - Ước tính tỷ lệ ~35–45% Fast-Path và ~55–65% Two-Phase hiện là **Suy luận kỹ thuật (`Inferred`)** từ tập kịch bản mẫu; tỷ lệ thực tế sẽ chỉ được xác minh sau khi thu thập `TurnEvaluationTelemetry` ở `P7` và môi trường thực.
2. **`[UNVERIFIED-02]` Độ trễ p95 của `TWO_PHASE_STRATEGIC` trên cổng `api.vilao.ai` (`qwen3.8-max`) dưới tải đồng thời**:
   - Đã xác minh (`Verified`) một lệnh gọi đơn lẻ tốn ~3.6s – 14.5s tùy độ dài prompt; tuy nhiên độ trễ p95 của luồng 2 pha với prompt rút gọn khi chạy liên hoàn 15 kịch bản `SC01–SC15` vẫn là **Chưa xác minh (`Unverified`)** cho đến khi chạy thực tế ở `P5–P7`.
3. **`[UNVERIFIED-03]` Tác động đến tỷ lệ chuyển đổi đặt lịch thực tế (Real-world Booking Conversion Rate)**:
   - **Chưa xác minh (`Unverified`)**: Chỉ có thể kết luận sau khi chạy thử nghiệm thực tế trên hội thoại khách hàng thật với bộ telemetry `R8`.
4. **`[LIMITATION-01]` Giới hạn Concurrency Multi-Instance chưa nằm trong cam kết v1**:
   - Topology hiện tại chỉ chạy **1 container `docmind-api` (`Single-Instance`)**. Hệ thống chưa có persistent fencing token xuyên instance ở tầng Outbound Dispatcher và chưa có môi trường kiểm thử multi-instance; do đó v1 chỉ cam kết chặn stale outbound trên `Single-Instance`.

---

## 4. Danh sách Quyết định Cần Chủ Dự Án Phê duyệt để Mở Khóa Architecture Lock (`R9`)

Xin Chủ dự án xem xét và phê duyệt 5 quyết định cốt lõi sau để chuyển các ADR từ `PROPOSED` sang `ACCEPTED` và cho phép bắt đầu `P3`:

1. **[Quyết định 1 — Chính sách Phân loại Câu hỏi Giá 3 Trường hợp (`ADR-08`)]**: Phê duyệt phân biệt 3 trường hợp: (a) `"Giá"`, `"Cho hỏi giá"` (chưa xác định nhóm dịch vụ -> hỏi 1 câu ngắn xác định nhu cầu); (b) `"Massage bao nhiêu?"` (đã xác định nhóm dịch vụ -> trả giá phù hợp trong catalog hoặc hỏi đúng 1 chi tiết tối thiểu nếu cần); (c) Dịch vụ cụ thể đã rõ hoặc xin bảng giá trực tiếp -> báo giá trực tiếp.
2. **[Quyết định 2 — Giao thức Nhất quán `ConversationMemoryStore`, Transactional Outbox & Cam kết `Single-Instance` v1 (`ADR-03 & ADR-04`)]**: Phê duyệt cơ chế `Tx-Entry` / `Tx-Commit` (ghi outbound `PENDING`) / `OutboundDeliveryDispatcher` (`ACCEPTED_BY_CHANNEL`, `FAILED`, `UNKNOWN`, `DELIVERED` khi có receipt), JPA `@Version` OCC + `Monotonic Merge`, `BookingPatch` lũy đẳng theo `patchId = "bp_" + SHA256_HEX_32(...)`, cam kết concurrency v1 cho `Single-Instance`, và mô hình `FACT` / `HYPOTHESIS` / `UNKNOWN` với 4 provenance chuẩn.
3. **[Quyết định 3 — Ranh giới Gateway ↔ `SalesTurnOrchestrator` & Cơ chế Gộp Câu hỏi Đặt lịch (`ADR-02 & ADR-06`)]**: Phê duyệt ranh giới cứng giữa Miền A (Gateway + Outbound Dispatcher) và Miền G (`SalesTurnOrchestrator`), cùng quy tắc cho phép 1 câu hỏi tự nhiên trong luồng đặt lịch gộp tối đa 2 trường liên quan (`nextBookingQuestionTarget`: ví dụ `[BRANCH, TIME_SLOT]` hoặc `[PARTY_SIZE, PHONE]`).
4. **[Quyết định 4 — Định tuyến `ExecutionModeSelector`, Ngân sách `TurnLlmBudgetTracker` & `BranchIntelligence` v1 (`ADR-05 & ADR-07`)]**: Phê duyệt định tuyến theo 4 chiều độ phức tạp (bắt buộc xử lý đúng 5 ca câu ngắn phức tạp), ngân sách cứng `<= 3 Logical Calls / <= 4 HTTP Attempts`, và mô hình `BranchIntelligenceService` v1 tĩnh/bán tĩnh (`customerLocation` vs `preferredBranchId`, `UNKNOWN_REALTIME`).
5. **[Quyết định 5 — Khung Nghiệm thu Hai Cổng `SC01–SC15` & `Zero Semantic Regex Surgery` (`ADR-08 & ADR-09`)]**: Phê duyệt việc gỡ bỏ các hàm regex cắt ghép câu in-place (chuyển thành Validator Rules trong `OutputTruthValidator`) và nghiệm thu tách biệt Cổng 1 (`SC01–SC10` Core Business) với Cổng 2 (`SC11–SC15` Architecture Guards) tại `P7`.
