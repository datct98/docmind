# TURN STRATEGY CONTRACT — SPECIFICATION, BOOKING BUDGET & BRANCH POLICY

> **Document Authority**: **TO-BE NORMATIVE SPECIFICATION** (Quy định hợp đồng chiến thuật lượt `TurnStrategyContract`, chính sách kinh doanh khóa cứng Mục VI, cơ chế `Booking Progression & Question Budget` `R5`, và `Branch Intelligence` `R7`).  
> **Phase Gate**: `P2 — Architecture Lock Pending Project Owner Approval`  
> **Approval Status**: **PROPOSED** *(Chỉ chuyển sang `ACCEPTED` sau khi Chủ dự án phê duyệt Architecture Lock)*  
> **Baseline Commit Before P0**: `244a2ec8a120878f8f863cb0388d72d51ae8bc2e`  
> **P0 Cleanup Commit**: `f091ba557a079da86c8ae14cd7ccc19fe8ece59d`  
> **P2 Initial Draft Commit**: `5aa985d43b5ef6ea1621f37fa546bcae520633c2`

---

## 1. Mục đích & Tính Bất Biến của `TurnStrategyContract`

`TurnStrategyContract` là **hợp đồng chiến thuật bất biến duy nhất** của mỗi lượt hội thoại, được tạo bởi `SalesStrategyPlanner` và truyền xuống `ResponsePromptComposer`, `ConsultativeResponseGenerator` cùng `OutputTruthValidator`.

- **Cả hai chế độ thực thi (`FAST_SINGLE_PASS` và `TWO_PHASE_STRATEGIC`) đều bắt buộc tạo và tuân thủ `TurnStrategyContract` (`R6`)**:
  - Ở chế độ `FAST_SINGLE_PASS`: Khi tín hiệu đầu vào rõ ràng, không mơ hồ (ví dụ: lượt 1 hỏi giá cộc lốc `"Cho hỏi giá"` hoặc lượt khách báo tạm dừng `"Để chị xem thêm nhé"`), `SalesStrategyPlanner` khởi tạo `TurnStrategyContract` tất định trong Java (`< 1ms`) trước khi gọi LLM duy nhất 1 lần để viết lời thoại, và kiểm định đầu ra bằng cùng một `OutputTruthValidator`.
  - Ở chế độ `TWO_PHASE_STRATEGIC`: Pha 1 (`TurnUnderstandingEngine` + LLM Phase 1) phân tích câu nói phức tạp/đa ý định/ẩn ý để xuất ra `TurnUnderstandingResult`; sau đó `SalesStrategyPlanner` trong Java khóa cứng `TurnStrategyContract` trước khi chuyển sang Pha 2 (`ConsultativeResponseGenerator`).

---

## 2. Cấu trúc Dữ liệu Chuẩn (`TurnStrategyContract` Record Schema)

```java
public record TurnStrategyContract(
        String turnId,
        int sequentialTurnNumber,
        ExecutionMode executionMode,                  // FAST_SINGLE_PASS | TWO_PHASE_STRATEGIC
        CanonicalTurnIntent primaryIntent,            // GREETING, BARE_PRICE_INQUIRY, EXPLICIT_PRICE_TABLE_REQUEST, SYMPTOM_CONSULTATION, ...
        IntentExplicitness explicitnessLevel,         // BARE_IMPLICIT | SCOPED_OR_EXPLICIT
        List<CanonicalTurnIntent> secondaryIntents,   // Đa ý định trong cùng 1 câu khách
        CustomerEmotionState customerEmotion,         // NEUTRAL, IN_PAIN_TIRED, HESITANT, FRUSTRATED, ANNOYED_AT_BOT, READY_TO_COMMIT
        RelationshipStage relationshipStage,          // NEW_VISITOR, EXPLORING_NEEDS, EVALUATING_OPTIONS, OBJECTION_HANDLING, BOOKING_COMMITMENT, PAUSED
        List<CustomerIntelligenceEntry> confirmedFacts,// Chỉ gồm các mục đạt chuẩn FACT (CUSTOMER_EXPLICIT / CUSTOMER_CONFIRMED)
        List<CustomerIntelligenceEntry> activeHypotheses, // Các giả thuyết HYPOTHESIS (chỉ dùng để thăm dò, cấm khẳng định)
        List<String> retractedFacts,                  // Phạm vi/giả định khách đã phủ nhận (cấm nhắc lại tuyệt đối)
        List<ActiveObjection> activeObjections,       // PRICE_TOO_HIGH, TIME_CONSTRAINT, TRUST_CONCERN, STYLE_COMPLAINT
        BranchTurnContext branchContext,              // Bao gồm customerLocation, preferredBranchId, branchLocationMismatch, availabilityMode
        List<CatalogCandidateView> eligibleCatalogCandidates, // Gói dịch vụ hợp lệ đã lọc theo vùng/nhu cầu và loại bỏ retractedFacts
        List<SelectedSkillView> selectedSkills,       // Tối đa 1-2 skill thực sự phù hợp lượt này
        TurnGoal turnGoal,                            // Mục tiêu duy nhất của lượt
        ResponseMode responseMode,                    // CONSULTATIVE_CLARIFY, EMPATHETIC_RECOMMEND, VALUE_REASSURANCE, APOLOGY_AND_REALIGN, RESPECTFUL_PAUSE, BOOKING_SLOT_CAPTURE, SAFE_HANDOFF
        int maxBubbles,                               // 1 hoặc 2
        int maxQuestionsAllowed,                      // 0 hoặc 1 câu hỏi (trong Booking Flow cho phép gộp tối đa 2 slot liên quan trong 1 câu hỏi tự nhiên)
        List<BookingField> bookingMissingFields,      // Toàn bộ các trường đặt lịch còn thiếu trong BookingProgressState
        List<BookingField> nextBookingQuestionTarget, // 1 hoặc tối đa 2 trường liên quan được phép hỏi ngay trong câu hỏi của lượt này (R5)
        List<TurnProhibition> mustNotDo,              // Danh sách các hành vi bị cấm trong lượt này
        PricePolicyMode pricePolicyMode,              // Chính sách báo giá khóa cứng theo Mục VI Master Directive
        BranchDisclosurePolicy branchDisclosurePolicy,// Chính sách hỏi/tiết lộ chi nhánh theo Mục V Master Directive & R7
        HandoffPolicy handoffPolicy                   // NONE | CONDITIONAL_BUSINESS_HANDOFF | BOOKING_CONFIRMATION_HANDOFF | HARD_SAFETY_HANDOFF
) {}
```

---

## 3. Bảng Ánh xạ Chính sách Kinh doanh đã Khóa (Mục VI Master Directive) vào `TurnStrategyContract`

> [!IMPORTANT]
> **Quy tắc Phân loại Câu hỏi Giá 3 Trường hợp (`ADR-08`)**:
> 1. **`"Giá"`, `"Cho hỏi giá"` (Khách chưa xác định nhóm dịch vụ — `UNSCOPED_BARE_PRICE_INQUIRY`)**: Áp dụng `FIRST_TURN_BARE_PRICE_CLARIFY_FIRST` -> Chào theo thương hiệu + ghi nhận câu hỏi giá + hỏi **1 câu ngắn** để xác định nhu cầu/nhóm dịch vụ; chưa báo gói hoặc bảng giá ở phản hồi đầu tiên.
> 2. **`"Massage bao nhiêu?"`, `"Gội đầu dưỡng sinh giá sao?"` (Khách đã xác định nhóm dịch vụ — `CATEGORY_SCOPED_PRICE_INQUIRY`)**: Áp dụng `CATEGORY_PRICE_QUOTE_OR_MINIMAL_CLARIFY` -> Trả ngay mức giá / gói phù hợp của nhóm dịch vụ đó trong catalog xác thực, hoặc hỏi đúng **1 chi tiết tối thiểu** nếu cần để thu hẹp (ví dụ: massage thư giãn toàn thân hay trị liệu chuyên sâu / thời lượng 60p hay 90p). Tuyệt đối không hỏi lại từ đầu như khách chưa nêu nhóm dịch vụ.
> 3. **Dịch vụ cụ thể đã rõ hoặc xin bảng giá trực tiếp (`"Gói cổ vai gáy 60p bao nhiêu?"`, `"Cho xin bảng giá"` — `SPECIFIC_SERVICE_OR_PRICE_TABLE_REQUEST`)**: Áp dụng `DIRECT_SPECIFIC_PRICE_QUOTE` / `PROVIDE_VERIFIED_PRICE_RANGE_OR_REPRESENTATIVE_PACKAGES` -> Báo giá trực tiếp từ catalog đã xác minh.

| Kịch bản Nghiệp vụ (Master Directive Mục VI) | Tín hiệu Đầu vào (`primaryIntent` + `explicitnessLevel` + Context) | `pricePolicyMode` | `branchDisclosurePolicy` | `maxQuestionsAllowed` | `mustNotDo` (Kiểm định bởi `OutputTruthValidator`) |
|---|---|---|---|---:|---|
| **VI.1. Khách mới hỏi giá cộc lốc, chưa xác định nhóm dịch vụ** (`"Giá"`, `"Cho hỏi giá"`, `"Giá sao shop?"` ở lượt 1, `serviceCategory == UNKNOWN`) | `BARE_PRICE_INQUIRY` + `BARE_IMPLICIT` + `turn == 1` + `serviceCategory == UNKNOWN` | `FIRST_TURN_BARE_PRICE_CLARIFY_FIRST` | `DO_NOT_ASK_BRANCH` | `1` (Hỏi 1 câu ngắn để xác định nhóm dịch vụ khách quan tâm) | `NO_PACKAGE_NAMES`, `NO_PRICE_NUMBERS`, `NO_BRANCH_QUESTION`, `NO_BOOKING_CTA` |
| **VI.2A. Khách hỏi giá đã xác định nhóm dịch vụ** (`"Massage bao nhiêu?"`, `"Gội đầu dưỡng sinh giá sao?"`) | `CATEGORY_PRICE_INQUIRY` (`serviceCategory == FACT`, `specificService == UNKNOWN`) | `CATEGORY_PRICE_QUOTE_OR_MINIMAL_CLARIFY` | `ONLY_IF_PRICE_DIFFERS_BY_BRANCH` | `1` (Trả giá phù hợp trong catalog hoặc hỏi đúng 1 chi tiết tối thiểu nếu cần) | `NO_REASKING_KNOWN_CATEGORY`, `NO_FULL_CATALOG_DUMP`, `NO_FABRICATED_PRICES` |
| **VI.2B. Dịch vụ cụ thể hoặc triệu chứng đã rõ** (`"Gói cổ vai gáy 60p bao nhiêu?"`, `"Đau mỏi cổ vai gáy 1 tuần giá sao?"`) | `SPECIFIC_SERVICE_PRICE_INQUIRY` hoặc `SYMPTOM_OR_NEED_SHARED` (`specificService/symptom == FACT`) | `DIRECT_SPECIFIC_PRICE_QUOTE` (`QUOTE_1_TO_2_MATCHING_PACKAGES`) | `ONLY_IF_PRICE_DIFFERS_BY_BRANCH` | `1` (Câu hỏi định hướng nhẹ nhàng) | `NO_FULL_CATALOG_DUMP`, `NO_THAO_MAI_CLICHE`, `NO_UNGROUNDED_MEDICAL_CLAIM` |
| **VI.3. Khách xin bảng giá trực tiếp** (`"Cho xin bảng giá"`, `"Gửi bảng giá giúp mình"`, hoặc hỏi giá lại ở `turn > 1`) | `EXPLICIT_PRICE_TABLE_REQUEST` (hoặc `BARE_PRICE_INQUIRY` khi `turn > 1`) | `PROVIDE_VERIFIED_PRICE_RANGE_OR_REPRESENTATIVE_PACKAGES` | `DISCLOSE_IF_BRANCH_DEPENDENT` | `1` (Thu hẹp nhu cầu) | `NO_FABRICATED_PRICES`, `NO_INTERNAL_SKU_CODES` |
| **VI.4. Khách chê đắt / so sánh giá** (`"Đắt thế"`, `"Thế thôi đắt quá"`, `"Có gói nào mềm hơn không?"`) | `PRICE_OBJECTION` | `EXPLAIN_VALUE_AND_OFFER_LOWER_OR_TRIAL_OPTION` | `DO_NOT_ASK_BRANCH` | `1` (Hoặc `0` nếu đi kèm `WITHDRAWAL_INTENT` mạnh) | `NO_FABRICATED_DISCOUNT`, `NO_PUSHY_BOOKING_CTA`, `NO_REPEATING_REJECTED_HIGH_TIER_ONLY` |
| **VI.5. Khách sửa thông tin / phàn nàn cách bot trả lời** (`"Mình không hỏi cổ vai gáy"`, `"Không phải"`, `"Sao bot nói văn mẫu thế"`) | `CUSTOMER_CORRECTION` hoặc `STYLE_COMPLAINT` | `FORBID_PRICE_AND_PACKAGE_PITCH` | `DO_NOT_ASK_BRANCH` | `1` (Câu hỏi mở bám đúng điều khách vừa nói) | `NO_FALSE_APOLOGY`, `NO_REPEATING_RETRACTED_FACT`, `NO_PACKAGE_PITCH`, `NO_PRICE_MENTION`, `NO_BRANCH_QUESTION` |
| **VI.6. Khách muốn dừng / để suy nghĩ thêm** (`"Để mình xem thêm nhé"`, `"Bận rồi nói sau"`) | `POLITE_DEFERRAL_OR_PAUSE` | `FORBID_PRICE_AND_PACKAGE_PITCH` | `DO_NOT_ASK_BRANCH` | `0` (**Không đặt thêm câu hỏi kéo câu chuyện**) | `NO_FOLLOW_UP_QUESTION`, `NO_FOMO_SCARCITY_PRESSURE`, `SINGLE_BUBBLE_ONLY` |
| **VI.7. Khách muốn đặt lịch** (`"Đặt cho mình gói 60p chiều nay"`, `"Đặt luôn"`) | `BOOKING_INTENT` hoặc `BookingProgressState.isActive()` | `QUOTE_ONLY_SELECTED_PACKAGE_IF_ASKED` | `ASK_BRANCH_IF_MULTI_BRANCH_AND_MISSING` | `1` (Hỏi `nextBookingQuestionTarget`: tối đa 2 trường liên quan trong 1 câu hỏi tự nhiên) | `NO_REASKING_CONFIRMED_BOOKING_SLOTS`, `NO_PREMATURE_BOOKING_CONFIRMATION`, `NO_INVENTED_BRANCH_AVAILABILITY` |

---

## 4. Giải quyết Quan hệ Giữa `maxQuestionsAllowed` và `bookingMissingFields` (`R5 — Booking Progression & Question Budget`)

### 4.1. Phân biệt `bookingMissingFields` (Trạng thái) với `nextBookingQuestionTarget` (Hành động Lượt Hiện tại)
- **`bookingMissingFields`**: Là danh sách toàn bộ các trường thông tin đặt lịch còn thiếu trong `BookingProgressState` tại thời điểm hiện tại (ví dụ: `[SERVICE, BRANCH, TIME_SLOT, PARTY_SIZE, PHONE]`).
- **`nextBookingQuestionTarget`**: Là **1 hoặc tối đa 2 trường có liên quan mật thiết** được `SalesStrategyPlanner` trích từ đầu danh sách `bookingMissingFields` để bot hỏi trong lượt hiện tại.
- **Định nghĩa chính xác của `maxQuestionsAllowed = 1`**:
  - Trong **Tư vấn Thông thường (`General Consultation`)**: Bot chỉ được đặt tối đa **1 câu hỏi đơn** để thăm dò nhu cầu hoặc định hướng gói dịch vụ.
  - Trong **Luồng Đặt lịch (`Active Booking Flow` khi khách đã chủ động muốn đặt lịch)**: `maxQuestionsAllowed = 1` có nghĩa là **tối đa 1 câu văn nghi vấn (1 dấu `?`)**, nhưng câu hỏi tự nhiên đó **được phép gộp tối đa 2 trường đặt lịch có liên quan trực tiếp** (`Compound Slot Question`, ví dụ: `BRANCH + TIME_SLOT` hoặc `PARTY_SIZE + PHONE`) để không bắt khách trả lời lắt nhắt 5 lượt liên tiếp và **không bao giờ gây deadlock đặt lịch**.

### 4.2. Thứ tự Ưu tiên Thu thập Trường Đặt lịch Theo Ngữ cảnh (`Context-Aware Slot Priority`)

1. **Trường hợp Page chỉ có 1 Chi nhánh (`Single-Branch Page`)**:
   - Nếu không có tín hiệu lệch vùng miền (`branchLocationMismatch == false`), `branchId` được tự động điền (`SINGLE_BRANCH_AUTO`).
   - Thứ tự ưu tiên `bookingMissingFields`:
     - **Ưu tiên 1**: `SERVICE` (Gói dịch vụ khách muốn đặt — nếu khách nói `"Đặt luôn"` sau khi bot vừa giới thiệu 2 gói thì hỏi xác nhận gói nào).
     - **Ưu tiên 2**: `TIME_SLOT` (Ngày + khung giờ dự kiến đến).
     - **Ưu tiên 3**: Gộp `PARTY_SIZE + PHONE` trong 1 câu hỏi tự nhiên (*"Dạ mình đi mấy người và cho em xin số điện thoại để lễ tân giữ lịch nhé ạ?"*). (Nếu khách đã xưng `"cho chị đặt 1 suất"` thì `PARTY_SIZE = 1` đã là `FACT`, chỉ hỏi `PHONE`).
2. **Trường hợp Page có Nhiều Chi nhánh (`Multi-Branch Page`)**:
   - **Nhánh 2A — Dịch vụ/Bảng giá khác nhau theo chi nhánh và khách chưa chọn gói**:
     - Ưu tiên 1: `BRANCH` -> Ưu tiên 2: `SERVICE` -> Ưu tiên 3: `TIME_SLOT` -> Ưu tiên 4: `PARTY_SIZE + PHONE`.
   - **Nhánh 2B — Khách đã chọn gói dịch vụ (hoặc giá đồng nhất giữa các chi nhánh) và muốn đặt lịch**:
     - Ưu tiên 1 (Gộp tự nhiên): **`[BRANCH, TIME_SLOT]`** (*"Dạ để em giữ lịch gói Cổ Vai Gáy 60 phút, mình tiện qua cơ sở Cầu Giấy hay Đống Đa và vào khung giờ nào ạ?"*).
     - Ưu tiên 2 (Gộp tự nhiên): **`[PARTY_SIZE, PHONE]`**.

### 4.3. Ví dụ Hội thoại Thực tế Chứng minh Không Deadlock (`R5 Examples`)

#### Ví dụ 1: Page 1 Chi nhánh (Nắng Sen Spa — chỉ có 1 cơ sở tại Hà Nội)
- **Khách (Turn 1)**: *"Mình muốn đặt gói trị liệu cổ vai gáy 60 phút chiều nay."*
  - Đã có `FACT`: `serviceId = cổ vai gáy 60p`, `date = hôm nay (buổi chiều)`, `partySize = 1 (ngầm định từ "mình", hoặc hỏi kèm)`, `branchId = HN_DEFAULT (auto)`.
  - `bookingMissingFields = [TIME_SLOT (giờ cụ thể), PHONE]`.
  - `nextBookingQuestionTarget = [TIME_SLOT, PHONE]`.
- **Bot (Turn 1)**: *"Dạ em ghi nhận lịch gói Trị liệu Cổ Vai Gáy 60 phút chiều nay cho mình tại cơ sở Nắng Sen ạ. Mình dự kiến qua vào khoảng mấy giờ chiều và cho em xin số điện thoại để lễ tân giữ phòng nhé ạ?"* *(1 câu hỏi duy nhất thu thập trọn vẹn 2 trường cuối cùng!)*

#### Ví dụ 2: Page Nhiều Chi nhánh (2 cơ sở: Cầu Giấy & Đống Đa)
- **Khách (Turn 1)**: *"Đặt cho chị gói thư giãn 90p tối nay nhé."*
  - Đã có `FACT`: `serviceId = thư giãn 90p`, `date = tối nay`.
  - `bookingMissingFields = [BRANCH, TIME_SLOT, PARTY_SIZE, PHONE]`.
  - `nextBookingQuestionTarget = [BRANCH, TIME_SLOT]`.
- **Bot (Turn 1)**: *"Dạ vâng ạ, tối nay chị muốn đặt gói Thư Giãn 90 phút ở cơ sở Cầu Giấy hay Đống Đa và vào khoảng mấy giờ để em kiểm tra phòng ạ?"*
- **Khách (Turn 2)**: *"Cầu Giấy lúc 19h30 nhé, chị đi 2 người."*
  - Cập nhật `FACT`: `branchId = Cầu Giấy`, `timeSlot = 19:30 tối nay`, `partySize = 2`.
  - `bookingMissingFields = [PHONE]`.
  - `nextBookingQuestionTarget = [PHONE]`.
- **Bot (Turn 2)**: *"Dạ em đã ghi nhận 2 suất gói Thư Giãn 90 phút lúc 19h30 tối nay tại cơ sở Cầu Giấy ạ. Chị cho em xin số điện thoại để lễ tân liên hệ xác nhận phòng đôi cho mình nhé ạ?"*

---

## 5. Đặc tả `Branch Intelligence` v1 (Tĩnh / Bán tĩnh — `R7`)

### 5.1. Tách biệt Hoàn toàn `customerLocation` và `preferredBranchId`
Trong `BranchTurnContext` và `ConversationMemoryStore`:
1. **`customerLocation`** (`CustomerRelationshipState.customerLocation`): Nơi khách đang sinh sống hoặc nhắc tới trong hội thoại (ví dụ: `"Chị ở Quận 3 TP.HCM"`, `"Anh ở bên Long Biên"`). Đây là thuộc tính địa lý của khách hàng.
2. **`preferredBranchId`** (`BookingProgressState.branchId`): Cơ sở cụ thể của Spa mà khách muốn đặt lịch đến trải nghiệm.
3. **Tại sao bắt buộc tách biệt?**
   - Một khách hàng đang ở `"TP.HCM"` (`customerLocation = HCM`) nhắn vào Page chỉ có cơ sở tại `"Hà Nội"` có thể: (a) nhắn nhầm Page vì không biết Page ở Hà Nội, HOẶC (b) sắp bay ra Hà Nội công tác tuần tới, HOẶC (c) đặt lịch tặng người thân đang ở Hà Nội.
   - Do đó, khi `customerLocation != pageBranchRegion`, `BranchIntelligenceService` bật cờ **`branchLocationMismatch = true`** dưới dạng **tín hiệu cảnh báo tư vấn (`Consultative Signal`)**, **TUYỆT ĐỐI KHÔNG tự động kết luận khách không thể đến chi nhánh và không từ chối phục vụ**.
   - Khi `branchLocationMismatch == true`: `branchDisclosurePolicy` chuyển sang `DISCLOSE_BRANCH_CITY_AND_CLARIFY_GENTLY` -> Bot trả lời câu hỏi của khách bình thường, đồng thời nêu rõ một cách lịch sự vị trí cơ sở (*"Dạ hiện tại bên em có cơ sở tại Hà Nội..."*) để khách chủ động xác nhận.

### 5.2. Phân biệt `UNKNOWN_REALTIME` trong Tư vấn Thông thường vs trong Luồng Đặt lịch (`R7`)
Vì phạm vi `BranchIntelligenceService` v1 là **tĩnh / bán tĩnh** (đọc từ cấu hình Page / KB / Playbook, không nối với hệ thống POS quản lý giường theo thời gian thực), trạng thái `staticAvailabilityStatus = UNKNOWN_REALTIME` được xử lý khác nhau tùy giai đoạn:

| Ngữ cảnh Hội thoại | Câu hỏi / Hành động của Khách | `handoffPolicy` & Hành vi Bắt buộc |
|---|---|---|
| **1. Đang ở giai đoạn Tư vấn / Thăm dò (`BookingProgressState.status == NONE`)** | Khách chỉ hỏi thăm dò: *"Tối thứ 7 bên mình có hay bị full phòng không?"* hoặc *"19h tối nay còn phòng trống không?"* (chưa chốt gói dịch vụ). | - **Cấm bịa**: Không bao giờ tự trả lời *"Dạ còn trống 2 phòng ạ"*.<br/>- Báo thông tin khung giờ hoạt động/lưu ý giờ cao điểm bán tĩnh (nếu có trong KB), giải thích trung thực rằng tình trạng phòng trống theo giờ cần lễ tân kiểm tra trực tiếp, và gợi ý khách chọn gói/khung giờ để lễ tân kiểm tra ngay (`CONDITIONAL_BUSINESS_HANDOFF` nếu khách chỉ hỏi đúng 1 câu kiểm tra phòng). |
| **2. Đang trong Luồng Đặt lịch (`BookingProgressState` đã có `serviceId`, `branchId`, `timeSlot`)** | Khách đang chốt lịch: *"Vậy đặt cho chị gói Cổ Vai Gáy 60p lúc 19h tối nay ở cơ sở Cầu Giấy nhé, SĐT 0912345678."* | - **Không làm gãy luồng đặt lịch**: Ghi nhận đầy đủ các trường vào `BookingProgressState`, chuyển trạng thái sang `AWAITING_AVAILABILITY_CHECK` và gắn `handoffPolicy = BOOKING_CONFIRMATION_HANDOFF`.<br/>- Bot xác nhận đã tiếp nhận đầy đủ yêu cầu đặt lịch (gói, chi nhánh, giờ, SĐT) và báo chuyên viên/lễ tân kiểm tra phòng thực tế rồi liên hệ xác nhận ngay cho khách. |
