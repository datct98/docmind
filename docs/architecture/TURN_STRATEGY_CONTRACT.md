# TURN STRATEGY CONTRACT — SPECIFICATION & BUSINESS POLICY LOCK

> **Phase Gate**: `P2 — Architecture Design`  
> **Baseline Commit**: `f091ba557a079da86c8ae14cd7ccc19fe8ece59d`

---

## 1. Mục đích của `TurnStrategyContract`

`TurnStrategyContract` là **hợp đồng chiến thuật bất biến duy nhất** của mỗi lượt hội thoại, được tạo bởi `SalesStrategyPlanner` (từ đầu vào của `TurnUnderstandingEngine`, `ConversationMemoryStore`, `CatalogIntelligenceService` và `BranchIntelligenceService`) và được truyền xuống `ResponsePromptComposer`, `ConsultativeResponseGenerator` cùng `OutputTruthValidator`.

Không một thành phần nào phía sau `SalesStrategyPlanner` được phép tự ý suy diễn lại ý định khách hàng hoặc dùng regex viết lại chiến thuật hội thoại.

---

## 2. Cấu trúc Dữ liệu Chuẩn (`TurnStrategyContract` Record Schema)

```java
public record TurnStrategyContract(
        String turnId,
        int sequentialTurnNumber,
        ExecutionMode executionMode,                  // FAST_SINGLE_PASS | TWO_PHASE_STRATEGIC
        CanonicalTurnIntent primaryIntent,            // GREETING, BARE_PRICE_INQUIRY, EXPLICIT_PRICE_TABLE_REQUEST, SYMPTOM_CONSULTATION, ...
        List<CanonicalTurnIntent> secondaryIntents,   // Đa ý định trong cùng 1 câu khách
        CustomerEmotionState customerEmotion,         // NEUTRAL, IN_PAIN_TIRED, HESITANT, FRUSTRATED, ANNOYED_AT_BOT, READY_TO_COMMIT
        RelationshipStage relationshipStage,          // NEW_VISITOR, EXPLORING_NEEDS, EVALUATING_OPTIONS, OBJECTION_HANDLING, BOOKING_COMMITMENT, PAUSED
        List<ConfirmedFact> confirmedFacts,           // Sự thật khách đã xác nhận (pain, duration, preference, budget)
        List<String> retractedFacts,                  // Phạm vi/giả định khách đã phủ nhận (cấm nhắc lại)
        List<ActiveObjection> activeObjections,       // PRICE_TOO_HIGH, TIME_CONSTRAINT, TRUST_CONCERN, STYLE_COMPLAINT
        BranchTurnContext branchContext,              // SINGLE_BRANCH_AUTO | MULTI_BRANCH_DEFERRED | MULTI_BRANCH_DISCLOSE_NOW | REALTIME_CHECK_HANDOFF
        List<CatalogCandidateView> eligibleCatalogCandidates, // Gói dịch vụ hợp lệ đã lọc theo vùng/nhu cầu
        List<SelectedSkillView> selectedSkills,       // Tối đa 1-2 skill thực sự phù hợp lượt này
        TurnGoal turnGoal,                            // Mục tiêu duy nhất của lượt
        ResponseMode responseMode,                    // CONSULTATIVE_CLARIFY, EMPATHETIC_RECOMMEND, VALUE_REASSURANCE, APOLOGY_AND_REALIGN, RESPECTFUL_PAUSE, BOOKING_SLOT_CAPTURE, SAFE_HANDOFF
        int maxBubbles,                               // 1 hoặc 2
        int maxQuestionsAllowed,                      // 0 hoặc 1 (TUYỆT ĐỐI không > 1 câu hỏi mỗi lượt)
        List<TurnProhibition> mustNotDo,              // Danh sách các hành vi bị cấm trong lượt này
        PricePolicyMode pricePolicyMode,              // Chính sách báo giá khóa cứng theo Mục VI Master Directive
        BranchDisclosurePolicy branchDisclosurePolicy,// Chính sách hỏi/tiết lộ chi nhánh theo Mục V Master Directive
        List<BookingField> bookingMissingFields,      // Thứ tự các trường còn thiếu: [SERVICE, BRANCH, TIME_SLOT, PARTY_SIZE, PHONE]
        HandoffPolicy handoffPolicy                   // NONE | CONDITIONAL_BUSINESS_HANDOFF | HARD_SAFETY_HANDOFF | EXPLICIT_HUMAN_REQUEST
) {}
```

---

## 3. Bảng Ánh xạ Chính sách Kinh doanh đã Khóa (Mục VI Master Directive) vào `TurnStrategyContract`

| Kịch bản Nghiệp vụ (Master Directive Mục VI) | Tín hiệu Đầu vào (`primaryIntent` + Context) | `pricePolicyMode` | `branchDisclosurePolicy` | `maxQuestionsAllowed` | `mustNotDo` (Cấm tuyệt đối — Kiểm định bởi `OutputTruthValidator`) |
|---|---|---|---|---:|---|
| **VI.1. Khách mới hỏi giá cộc lốc** (`"Giá"`, `"Cho hỏi giá"`, `"Massage bao nhiêu?"` ở lượt đầu, chưa rõ nhóm dịch vụ) | `BARE_PRICE_INQUIRY` + `turn == 1` + `confirmedFacts.serviceGroup == null` | `FIRST_TURN_BARE_PRICE_CLARIFY_FIRST` | `DO_NOT_ASK_BRANCH` | `1` (Hỏi nhóm dịch vụ: thư giãn hay dưỡng sinh) | `NO_PACKAGE_NAMES`, `NO_PRICE_NUMBERS`, `NO_BRANCH_QUESTION`, `NO_BOOKING_CTA` |
| **VI.2. Khách đã nêu nhu cầu / triệu chứng** (`"Đau mỏi cổ vai gáy 1 tuần"`, `"Muốn tìm gói thư giãn"`) | `SYMPTOM_OR_NEED_SHARED` | `QUOTE_1_TO_2_MATCHING_PACKAGES` | `ONLY_IF_PRICE_DIFFERS_BY_BRANCH` | `1` (Câu hỏi định hướng nhẹ nhàng) | `NO_FULL_CATALOG_DUMP`, `NO_THAO_MAI_CLICHE`, `NO_UNGROUNDED_MEDICAL_CLAIM` |
| **VI.3. Khách xin bảng giá trực tiếp** (`"Cho xin bảng giá"`, `"Gửi bảng giá giúp mình"`, hoặc hỏi giá lần 2) | `EXPLICIT_PRICE_TABLE_REQUEST` (hoặc `BARE_PRICE_INQUIRY` khi `turn > 1`) | `PROVIDE_VERIFIED_PRICE_RANGE_OR_REPRESENTATIVE_PACKAGES` | `DISCLOSE_IF_BRANCH_DEPENDENT` | `1` (Thu hẹp nhu cầu) | `NO_FABRICATED_PRICES`, `NO_INTERNAL_SKU_CODES` |
| **VI.4. Khách chê đắt / so sánh giá** (`"Đắt thế"`, `"Có gói nào mềm hơn không?"`) | `PRICE_OBJECTION` | `EXPLAIN_VALUE_AND_OFFER_LOWER_OR_TRIAL_OPTION` | `DO_NOT_ASK_BRANCH` | `1` (Hỏi ý kiến về phương án mềm hơn) | `NO_FABRICATED_DISCOUNT`, `NO_PUSHY_BOOKING_CTA`, `NO_REPEATING_REJECTED_HIGH_TIER_ONLY` |
| **VI.5. Khách sửa thông tin / phàn nàn cách bot trả lời** (`"Mình không hỏi cổ vai gáy"`, `"Sao bot nói văn mẫu thế"`) | `CUSTOMER_CORRECTION` hoặc `STYLE_COMPLAINT` | `FORBID_PRICE_AND_PACKAGE_PITCH` | `DO_NOT_ASK_BRANCH` | `1` (Câu hỏi mở bám đúng điều khách vừa nói) | `NO_FALSE_APOLOGY`, `NO_REPEATING_RETRACTED_FACT`, `NO_PACKAGE_PITCH`, `NO_PRICE_MENTION`, `NO_BRANCH_QUESTION` |
| **VI.6. Khách muốn dừng / để suy nghĩ thêm** (`"Để mình xem thêm nhé"`, `"Bận rồi nói sau"`) | `POLITE_DEFERRAL_OR_PAUSE` | `FORBID_PRICE_AND_PACKAGE_PITCH` | `DO_NOT_ASK_BRANCH` | `0` (**Không đặt thêm câu hỏi kéo câu chuyện**) | `NO_FOLLOW_UP_QUESTION`, `NO_FOMO_SCARCITY_PRESSURE`, `SINGLE_BUBBLE_ONLY` |
| **VI.7. Khách muốn đặt lịch** (`"Đặt cho mình gói 60p chiều nay"`) | `BOOKING_INTENT` hoặc `BookingProgressState.isActive()` | `QUOTE_ONLY_SELECTED_PACKAGE_IF_ASKED` | `ASK_BRANCH_ONLY_IF_MULTI_BRANCH_AND_MISSING` | `1` (Hỏi 1–2 thông tin còn thiếu tiếp theo trong `bookingMissingFields`) | `NO_REASKING_CONFIRMED_BOOKING_SLOTS`, `NO_INVENTED_BRANCH_AVAILABILITY` |

---

## 4. Hợp đồng Kiểm định tại `OutputTruthValidator` (Không Regex Rewrite)

Khi `ConsultativeResponseGenerator` sinh ra `List<ReplyBubble>`, `OutputTruthValidator` đối chiếu trực tiếp với `TurnStrategyContract`:
1. Nếu `pricePolicyMode == FIRST_TURN_BARE_PRICE_CLARIFY_FIRST` mà bong bóng chứa số tiền (`239k`, `399.000đ`...) hoặc tên gói cụ thể -> Báo lỗi `VIOLATION_FIRST_TURN_BARE_PRICE_MUST_CLARIFY_FIRST`.
2. Nếu `mustNotDo` chứa `NO_PACKAGE_PITCH` (lượt khách đính chính/phàn nàn) mà bong bóng vẫn chào gói/báo giá -> Báo lỗi `VIOLATION_UNSOLICITED_PITCH_ON_CORRECTION`.
3. Nếu `maxQuestionsAllowed == 0` (lượt khách báo bận/tạm dừng) mà bong bóng chứa dấu `?` hoặc câu hỏi nghi vấn -> Báo lỗi `VIOLATION_QUESTION_ON_RESPECTFUL_PAUSE`.
4. Nếu bong bóng chứa giá tiền không khớp với `eligibleCatalogCandidates` -> Báo lỗi `VIOLATION_UNGROUNDED_CATALOG_PRICE`.
5. Nếu bong bóng nhắc lại từ khóa nằm trong `retractedFacts` để gán cho khách -> Báo lỗi `VIOLATION_RETRACTED_FACT_RESURFACED`.

Khi có bất kỳ lỗi nào ở trên, `OutputTruthValidator` **không dùng regex cắt câu** mà gửi nguyên vẹn `TurnStrategyContract + ViolationFeedback` vào pha tạo lại câu trả lời (`Regenerate once with explicit violation feedback`), hoặc sử dụng `DeterministicFallbackBuilder` tương ứng với `TurnGoal` nếu lần tạo lại vẫn không đạt.
