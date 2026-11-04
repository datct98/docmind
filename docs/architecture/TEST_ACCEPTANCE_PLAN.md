# TEST ACCEPTANCE PLAN — UNIFIED SC01–SC15 QUICK REFERENCE (`R1`)

> **Document Authority**: **NORMATIVE ACCEPTANCE SUMMARY** (Đồng bộ 100% với [`ACCEPTANCE_TEST_PLAN.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/ACCEPTANCE_TEST_PLAN.md) để đáp ứng đầy đủ cả hai tên file tại Mục X.P2 và Mục XIII của Master Directive).  
> **Phase Gate**: `P2 — Architecture Lock Pending Project Owner Approval`  
> **Approval Status**: **PROPOSED** *(Chỉ chuyển sang `ACCEPTED` sau khi Chủ dự án phê duyệt Architecture Lock)*  
> **Baseline Commit Before P0**: `244a2ec8a120878f8f863cb0388d72d51ae8bc2e`  
> **P0 Cleanup Commit**: `f091ba557a079da86c8ae14cd7ccc19fe8ece59d`  
> **P2 Initial Draft Commit**: `5aa985d43b5ef6ea1621f37fa546bcae520633c2`

---

## 1. Tóm tắt Hai Cổng Nghiệm thu Tách biệt tại `P7` (`R1`)

1. **Cổng 1 — Core Business Conversation Scenarios (`SC01–SC10`)**:
   - Đánh giá chất lượng hội thoại tư vấn thực tế và độ chính xác nghiệp vụ.
   - Yêu cầu: **10/10 kịch bản đạt `PASS` nhị phân** (0 bịa giá, 0 sai thông tin gói, 0 vi phạm chính sách Mục VI), **điểm trung bình `SC01–SC10 >= 85/100`**, và **không kịch bản nào `< 80/100`**.
2. **Cổng 2 — Regression & Architecture Guard Scenarios (`SC11–SC15`)**:
   - Đánh giá nhị phân (`PASS` / `FAIL`) đối với các chốt chặn kiến trúc (`R2, R3, R5, R6, R7`).
   - Yêu cầu: **5/5 kịch bản đạt `PASS` nhị phân (100%)**. **Tuyệt đối không cộng gộp kết quả kỹ thuật của `SC11–SC15` vào điểm trung bình chất lượng hội thoại của `SC01–SC10`**.

---

## 2. Danh sách Đầy đủ 15 Kịch bản Bắt buộc Chạy trong `P7` (`SC01–SC15`)

- **`SC01` (Core Business)**: Khách mới hỏi giá ngắn gọn/cộc lốc (`"Giá"`, `"Cho hỏi giá"`, `"Massage bao nhiêu?"`) — tuân thủ Mục VI.1 (`FIRST_TURN_BARE_PRICE_CLARIFY_FIRST`).
- **`SC02` (Core Business)**: Khách đã nêu triệu chứng / nhóm dịch vụ cụ thể rồi hỏi giá — tuân thủ Mục VI.2 (`QUOTE_1_TO_2_MATCHING_PACKAGES`).
- **`SC03` (Core Business)**: Khách chê đắt và muốn phương án thấp hơn — tuân thủ Mục VI.4.
- **`SC04` (Core Business)**: Khách hỏi đa ý trong 1 tin nhắn (chi nhánh + giờ mở cửa + giá gói) — định tuyến `TWO_PHASE_STRATEGIC`.
- **`SC05` (Core Business)**: Khách sửa thông tin bot hiểu sai — xin lỗi trung thực, đưa vào `RETRACTED`, không chào gói/hỏi chi nhánh trong lượt xin lỗi (Mục VI.5).
- **`SC06` (Core Business)**: Khách phàn nàn bot trả lời máy móc — cầu thị, không tự nhận người thật, không kèm CTA bán hàng.
- **`SC07` (Core Business)**: Khách hỏi chi nhánh & đặt lịch trên Page 1 chi nhánh vs Page nhiều chi nhánh — gộp hỏi `[BRANCH, TIME_SLOT]` tự nhiên, không gây deadlock (`R5 & R7`).
- **`SC08` (Core Business)**: Khách muốn tạm dừng (`"Để mình xem thêm nhé"`) — tôn trọng, đúng 1 bong bóng, `maxQuestionsAllowed = 0` (Mục VI.6).
- **`SC09` (Core Business)**: Khách quay lại đặt lịch sau nhiều vòng — giữ nguyên `FACT`, chỉ hỏi `nextBookingQuestionTarget`, không hỏi lại thông tin đã xác nhận (`R5`).
- **`SC10` (Core Business)**: Khách yêu cầu gặp nhân viên hoặc hỏi ngoài phạm vi tài liệu — kích hoạt `needsHumanHandoff = true`.
- **`SC11` (Architecture Guard — `R6`)**: Định tuyến chuẩn xác 5 ca câu ngắn nhưng phức tạp (`"Thế thôi đắt quá."`, `"Không phải."`, `"Chịu em."`, `"Đặt luôn."`, `"Ừ/Ok"` sau câu hỏi chưa đủ điều kiện chốt lịch).
- **`SC12` (Architecture Guard — `R6`)**: Kiểm soát ngân sách xuyên tầng `TurnLlmBudgetTracker` (`logicalLlmCalls <= 3`, `actualHttpAttempts <= 4`, fallback tất định an toàn).
- **`SC13` (Architecture Guard — `R3`)**: Kiểm chứng vòng đời `FACT` / `HYPOTHESIS` / `UNKNOWN`, chống tự xác nhận giả thuyết (`No Self-Confirming Hypothesis`) và chống hồi sinh thông tin đã bác bỏ (`RETRACTED`).
- **`SC14` (Architecture Guard — `R7`)**: Tách biệt `customerLocation` vs `preferredBranchId` (`branchLocationMismatch`) và phân biệt `UNKNOWN_REALTIME` giữa tư vấn chung với luồng chốt lịch.
- **`SC15` (Architecture Guard — `R2 & Guard`)**: Kiểm chứng `Zero Semantic Regex Surgery` (nguyên vẹn ngữ pháp) và tính nhất quán `ConversationMemoryStore` (OCC retry, `BookingPatch` idempotency, DB-First cache invalidation).

Xem chi tiết từng cột `Expected Behavior`, `Forbidden Behavior` và yêu cầu bằng chứng tại [`ACCEPTANCE_TEST_PLAN.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/ACCEPTANCE_TEST_PLAN.md).
