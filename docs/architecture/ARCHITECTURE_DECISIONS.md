# ARCHITECTURE DECISIONS RECORD (ADR) — DOCMIND ADAPTIVE SALES INTELLIGENCE

> **Phase Gate**: `P2 — Architecture Design & Independent Review`  
> **Baseline Commit Before P0**: `244a2ec8a120878f8f863cb0388d72d51ae8bc2e`  
> **Current HEAD After P0**: `f091ba557a079da86c8ae14cd7ccc19fe8ece59d`  
> **Status**: Chờ Chủ dự án phê duyệt tại **Architecture Lock (Bước 6)**

---

## ADR-01: Loại bỏ hoàn toàn Standalone Python Agent Brain (`:8095`) và giữ nguyên `doc-service-py` (`:8091`) + In-Process Java `AgentBrainService`

- **Bối cảnh & Bằng chứng**:
  - Dịch vụ `agent-brain` độc lập trên cổng `8095` không còn container hoạt động, `AgentBrainClient.java` đã xóa, nhưng thư mục `agent-brain/` (21 files, `2,714 LOC`) vẫn tồn tại trong repo.
  - Ngược lại, `doc-service-py` (`:8091`) đang xử lý `/v1/parse`, `/v1/jobs/*`, `/v1/embed` (384-dim) và `/internal/v1/nightly-extract`, còn `AgentBrainService.java` đang chạy in-process trong `docmind-api` (`:8090`).
- **Quyết định**:
  - Xóa hoàn toàn thư mục `agent-brain/` tại commit `f091ba557a079da86c8ae14cd7ccc19fe8ece59d` (`chore: retire standalone Python Agent Brain service (8095)`).
  - Bảo toàn tuyệt đối `doc-service-py` (`:8091`) và `AgentBrainService.java`.

---

## ADR-02: Phân rã 8 God Classes (`45,509 LOC`) thành 8 Miền Kiến trúc Hợp lý (Domains A–H) với Orchestrator Mỏng (`SalesTurnOrchestrator` <= 400 LOC)

- **Bối cảnh & Bằng chứng**:
  - 8 class trong `service.bot` chiếm `52.58%` toàn bộ backend (`45,509 LOC`) và chứa `~16,850 LOC` mã V1/CLEAN không còn chạy trên hot-path `LLM_COGNITIVE_V2` (bao gồm `executeCleanAgentTurn` `L367–L2041` trong `CleanAgentOrchestratorService.java` và `L1743–L4290` trong `FanpageBotService.java`).
- **Quyết định**:
  - Tách bạch rõ 8 miền A–H (`Inbound Gateway`, `Conversation Memory`, `Knowledge/Catalog/Branch Intelligence`, `Turn Understanding & Strategy Planner`, `Response Composer`, `Output Truth & Safety Guard`, `Thin Orchestrator`, `Audit & Telemetry`).
  - Dọn dẹp triệt để các nhánh mã chết V1/CLEAN theo lộ trình `P3–P7`.

---

## ADR-03: Hợp nhất Quyền sở hữu Trạng thái Phiên vào `ConversationMemoryStore` (Single Source of Truth)

- **Bối cảnh & Bằng chứng**:
  - Trạng thái đặt lịch (`StructuredBookingState`), triệu chứng (`confirmedScopes`) và phạm vi bị từ chối (`explicitForbiddenScopes`) đang bị lưu trùng lặp ở `ConversationState.SESSION_CACHE`, `AgentBrainService.CustomerBrainMemory` và `CustomerUnderstandingDraft2Snapshot`.
- **Quyết định**:
  - Giao quyền sở hữu duy nhất của `ConfirmedFactLedger`, `CustomerRelationshipState`, `BookingProgressState` và `ConversationTurnLedger` cho `ConversationMemoryStore` (lưu bền vững xuống `conversation_sessions.aci_memory_state_json` với cơ chế optimistic locking).
  - Loại bỏ việc lưu bản sao `StructuredBookingState` trong `ConversationState.SESSION_CACHE` và loại bỏ hàm regex ghi đè hậu kiểm `reconcileWithCurrentUtterance` (`665 LOC`).

---

## ADR-04: Thực thi LLM Thích ứng Hai Chế độ (`Hybrid Adaptive Execution`: Fast-Path 1-Call vs Two-Phase Strategic)

- **Bối cảnh & Bằng chứng**:
  - Gọi LLM 1 lần với monolith prompt (~20k chars) khiến model dễ rơi vào văn mẫu lắp ghép hoặc quên ràng buộc khi câu khách phức tạp; ngược lại gọi 2 lần cho cả câu chào đơn giản sẽ làm tăng độ trễ không cần thiết.
- **Quyết định**:
  - Dùng `ExecutionModeSelector` phân nhánh:
    - `FAST_SINGLE_PASS` (1 lần gọi LLM): Cho lượt chào hỏi, hỏi giá cộc lốc lượt 1, báo bận/tạm dừng, FAQ vận hành đơn giản, hoặc điền 1 slot đặt lịch đơn lẻ.
    - `TWO_PHASE_STRATEGIC` (Pha 1 phân tích ý định & fact -> Java `SalesStrategyPlanner` khóa cứng `TurnStrategyContract` -> Pha 2 `ConsultativeResponseGenerator` sinh câu trả lời tự nhiên): Cho lượt đa ý định, phản đối giá, đính chính thông tin/phàn nàn, so sánh gói dịch vụ hoặc tư vấn đa chi nhánh.

---

## ADR-05: `OutputTruthValidator` chỉ Gác cổng Sự thật & An toàn — Chấm dứt Cắt ghép Câu bằng Regex (`*InPlace`)

- **Bối cảnh & Bằng chứng**:
  - `CleanAgentOutputRefereeAndSanitizer` (`7,820 LOC`, `113 regexes`) đang dùng hơn 15 hàm `*InPlace` để cắt câu, xóa câu hoặc ghép câu hỏi vào bong bóng của LLM, làm câu trả lời bị cụt ý hoặc vô hồn.
- **Quyết định**:
  - Xóa bỏ toàn bộ các hàm regex sửa nội dung/ngữ nghĩa (`Semantic Surgery In-Place`).
  - Chỉ giữ chuẩn hóa hình thức vô hại tại `BubblePacingFormatter` (trim khoảng trắng, xóa mã SKU nội bộ `[PKG_...]`, viết hoa chữ cái đầu).
  - Mọi vi phạm sự thật hoặc vi phạm `TurnStrategyContract.mustNotDo` được `OutputTruthValidator` trả về dưới dạng `ViolationFeedback` để regenerate có kiểm soát hoặc dùng `DeterministicFallbackBuilder`.

---

## ADR-06: Thiết kế `BranchIntelligenceService` v1 (Tĩnh / Bán tĩnh) & Xử lý Mâu thuẫn Chính sách Báo giá Lượt 1 (Mục VI.1 vs Code `244a2ec`)

- **Bối cảnh & Bằng chứng**:
  1. Hệ thống hiện chưa có mô hình `BranchIntelligence` chuẩn hóa (mới chỉ lọc chuỗi `"HN"`/`"HCM"`).
  2. Code tại commit `244a2ec` (`CleanAgentCatalogAndPromptBuilder.java` `L4595–L4630`) đang cho báo giá neo `239k/399k` ngay ở lượt 1 khi khách hỏi `"Giá?"`, mâu thuẫn với Mục VI.1 của Master Directive (*"Chưa giới thiệu các gói hoặc bảng giá trong phản hồi đầu tiên"*).
- **Quyết định (Trình Chủ dự án phê duyệt tại Architecture Lock)**:
  1. Triển khai `BranchIntelligenceService` v1 đọc từ cấu hình/KB/playbook: phân biệt Page 1 chi nhánh (không bao giờ hỏi chọn chi nhánh) vs Page nhiều chi nhánh (chỉ hỏi khi giá/dịch vụ khác nhau hoặc khi chốt lịch), và chuyển nhân viên (`REALTIME_CHECK_HANDOFF`) khi khách hỏi chỗ trống theo giờ thực tế chưa xác định.
  2. Khóa chặt 2 chế độ trong `PricePolicyMode`:
     - `FIRST_TURN_BARE_PRICE_CLARIFY_FIRST` (Mục VI.1): Khi khách mới ở lượt 1 chỉ hỏi giá chung/cộc lốc (`"Giá"`, `"Cho hỏi giá"`, `"Massage bao nhiêu?"`) mà chưa nêu nhóm dịch vụ/triệu chứng -> Chào thương hiệu + ghi nhận câu hỏi giá + hỏi 1 câu ngắn xác định nhóm dịch vụ (massage thư giãn hay dưỡng sinh) + **không báo gói/bảng giá ở phản hồi đầu tiên**.
     - `PROVIDE_VERIFIED_PRICE_RANGE_OR_REPRESENTATIVE_PACKAGES` (Mục VI.3): Khi khách chủ động xin bảng giá (`"Cho xin bảng giá"`) hoặc đã nêu rõ dịch vụ/triệu chứng (Mục VI.2) hoặc hỏi giá từ lượt 2 trở đi -> Báo ngay gói/khung giá xác thực.

---

## ADR-07: Tổng hợp Phản biện Độc lập của `Qwen3.8-Max` (Step 4 -> Step 5 Refinements)

Sau khi `Qwen3.8-Max` hoàn thành phản biện độc lập tại [`QWEN_INDEPENDENT_ARCHITECTURE_REVIEW.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/QWEN_INDEPENDENT_ARCHITECTURE_REVIEW.md), 6 quyết định kỹ thuật bổ sung sau đây được khóa vào Kiến trúc Đích:

1. **Khóa cấp hội thoại (Per-Conversation Striped Lock) + OCC + JSONB Upcaster cho `ConversationMemoryStore`**:
   - Dùng `Striped<Lock>` theo khóa `pageId::senderId` trong JVM kết hợp `version` (Optimistic Concurrency Control) trên bảng `conversation_sessions` và `BookingPatch` có `patchId` (idempotent granular patch) để chống race condition khi khách nhắn dồn dập nhiều webhook.
   - Bổ sung `_schemaVersion` và bộ `LegacyMemoryUpcaster` đọc tương thích ngược cả `StructuredBookingState` lẫn `Draft2Snapshot` mà không làm rớt trường của các phiên đang mở.
2. **Làm rõ ranh giới `BranchIntelligenceService` (`BranchContextSnapshot`)**:
   - Bổ sung phát hiện lệch vùng địa lý (`branchLocationMismatch`: ví dụ khách ở TP.HCM nhắn vào Page chỉ có 1 cơ sở tại Hà Nội -> thông báo lịch sự vị trí cơ sở thay vì tự động gán lịch hẹn Hà Nội trong im lặng).
   - Khi Page có nhiều chi nhánh (`multiBranch`) và khách bước vào luồng đặt lịch, ưu tiên đưa `BRANCH` lên đầu `bookingMissingFields` (`BranchBookingPriority`).
   - Phân biệt `UNKNOWN_REALTIME` khi đang tư vấn chung vs khi khách đã chốt gói + giờ hẹn (`BOOKING_CONFIRMATION_HANDOFF`: giữ nguyên thông tin đặt lịch và báo chuyên viên xác nhận chỗ trống thực tế).
3. **Phân biệt sắc thái Mục VI.1 vs VI.2 vs VI.3 bằng `explicitnessLevel` + `serviceGroup` Detection**:
   - Nếu khách hỏi `"Giá gội đầu dưỡng sinh bao nhiêu?"` ngay ở lượt 1 (đã có nhóm dịch vụ `"gội đầu dưỡng sinh"`), xếp vào **Mục VI.2 (`QUOTE_1_TO_2_MATCHING_PACKAGES`)** chứ không ép hỏi lại như Mục VI.1.
   - Nếu khách hỏi `"Bảng giá massage bên mình sao?"` / `"Cho xin bảng giá"` (có từ khóa yêu cầu bảng giá tường minh), xếp vào **Mục VI.3 (`EXPLICIT_PRICE_TABLE_REQUEST`)**.
   - Chỉ khi khách hỏi giá chung/cộc lốc ở lượt 1 mà chưa có nhóm dịch vụ/triệu chứng (`"Giá"`, `"Cho hỏi giá"`, `"Massage bao nhiêu?"`) mới kích hoạt **Mục VI.1 (`FIRST_TURN_BARE_PRICE_CLARIFY_FIRST`)**.
4. **Bộ lọc `PragmaticSignalPreFilter` trong `ExecutionModeSelector` & Ngân sách Cứng Tối đa 3 Lần Gọi LLM / Lượt**:
   - Không phân loại `FAST_SINGLE_PASS` dựa trên độ dài câu đơn thuần: những câu ngắn nhưng mang hàm ý phản đối/rút lui (như `"Thế thôi đắt quá"` — 4 từ nhưng kết hợp `PRICE_OBJECTION` + `WITHDRAWAL_INTENT`) bắt buộc đi qua `TWO_PHASE_STRATEGIC`.
   - Khóa cứng **Hard LLM Call Budget <= 3 calls/turn** (tối đa 1 lần regenerate sau khi `OutputTruthValidator` bắt lỗi; nếu vẫn vi phạm thì chuyển ngay sang `DeterministicFallbackBuilder`).
5. **Bảng Phân loại Tường minh 15+ Hàm `*InPlace`**:
   - **Giữ tại `BubblePacingFormatter` (Chỉ xử lý hình thức/biểu diễn)**: `stripInternalCatalogSkuCodesInPlace` (`[PKG_...]`), chuẩn hóa khoảng trắng/dấu câu, viết hoa chữ cái đầu, tính `PriceQuoteReadingBudget`.
   - **Xóa bỏ & Chuyển thành Validator Rule trong `OutputTruthValidator` (Cấm phẫu thuật ngữ nghĩa bằng regex)**: `enforceGroundedApologyAgainstHistoryInPlace` -> `FalseApologyRule`; `stripUnsolicitedPackagePitchOnNegativeReactionInPlace` -> `UnsolicitedPitchOnCorrectionRule`; `enforcePoliteClosingPauseSingleBubbleNoQuestionInPlace` -> `QuestionOnRespectfulPauseRule`; `enforceP37B2TurnActionAndBubbleIdeaDisciplineInPlace` & `enforceAciV2StyleComplaintNoCtaAndAntiClicheInPlace` -> `TurnStrategyProhibitionRule`.
6. **Mở rộng Bộ Kịch bản Nghiệm thu lên 15 Kịch bản (`SC01..SC15`)**:
   - Bổ sung 5 kịch bản kiểm tra biên từ phản biện của `Qwen3.8-Max` (`SC11..SC15`) vào `ACCEPTANCE_TEST_PLAN.md`.

