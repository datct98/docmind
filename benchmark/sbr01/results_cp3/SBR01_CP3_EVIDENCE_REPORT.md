# BÁO CÁO BẰNG CHỨNG SBR-01 WAVE 1 — CHECKPOINT 3 (`CP3.1` → `CP3.6`)
## Batch 2: Bán Hàng & Chuyển Đổi (Spa Nàng Sen)

- **Branch**: `refactor/aci-v2-cleanup`
- **Target Page ID (Production Scope)**: Nàng Sen (`2850a8ef-ca4a-40eb-87fa-4f808640efee`)
- **Negative Control Page ID (Isolation Check Only)**: Page thẩm mỹ viện (`59e68078-8327-4aa0-8cca-91410f783ad4`)
- **Primary Model (Replay A/B)**: `qwen3.8-max` (`117/117` lượt JSON hợp lệ mỗi cấu hình, `0` lỗi provider, `0` fallback)

---

## 1. Danh Mục Commit Độc Lập & Cờ Cấu Hình (`One Behavior Change = One Commit`)

| Hạng mục | Commit SHA | Thông điệp Commit | Cờ Cấu Hình Bật/Tắt (Toggle Flag) | Unit Test |
|---|---|---|---|---|
| **CP3.1** — `conversation_goal` Lifecycle | `dcf62678e525d935882179591dc9c3c937136b3b` | `feat(sbr01-cp3-1): enforce conversation_goal lifecycle with toggle flag` | `docmind.aci.cp3.goal-lifecycle.enabled` / `DOCMIND_ACI_CP3_GOAL_LIFECYCLE_ENABLED` | `Sbr01Cp3GoalLifecycleTest` (`3/3 PASS`) |
| **CP3.2** — Booking 3 Cấp theo Tín Hiệu Khách | `df82004a20ace2a80a684931437eabcc56b7e880` | `feat(sbr01-cp3-2): implement 3-tier signal-driven booking gate with toggle flag` | `docmind.aci.cp3.three-tier-booking.enabled` / `DOCMIND_ACI_CP3_THREE_TIER_BOOKING_ENABLED` | `Sbr01Cp3ThreeTierBookingTest` (`3/3 PASS`) |
| **CP3.3a** — Trung Hòa `good_example` (`V43`) | `738289af6e94ca4e771c8958b95a5ae19711743d` | `fix(sbr01-cp3-3a): neutralize discovery_empathy_and_scope good_example` | Flyway `V43` + Snapshot Sync | `Sbr01Cp3GoodExampleToggleTest` (`1/1 PASS`) |
| **CP3.3b** — Cờ Bật/Tắt `good_example` trong Prompt | `598990719fb001a8f4068f2bddb2d535ec5245b5` | `feat(sbr01-cp3-3b): add toggle flag for skill good_example prompt injection` | `docmind.aci.cp3.skill-good-example.enabled` / `DOCMIND_ACI_CP3_SKILL_GOOD_EXAMPLE_ENABLED` | `Sbr01Cp3GoodExampleToggleTest` (`1/1 PASS`) |
| **CP3.4** — Kiểm Toán 6 Skill & Thêm 4 Skill Nguyên Tắc (`V44`) | `ef4557ca8e0cfe55e0e95a2e082cc1d378195b74` | `feat(sbr01-cp3-4): audit Nang Sen skills and add 4 principle-based skills in V44` | `docmind.aci.cp3.skill-audit.enabled` / `DOCMIND_ACI_CP3_SKILL_AUDIT_ENABLED` | `Sbr01Cp3SkillAuditAndIsolationTest` (`3/3 PASS`) |

- **Tổng hợp Unit & Regression Suite (`CP1` + `CP2` + `CP3` + `ACI V3`)**: **`63/63 PASS`**, `exit code = 0` (log lưu tại `evidence/CP3_unit_and_regression_suite.log`).

---

## 2. CP3.1 — Vòng Đời `conversation_goal` (`dcf62678e525d935882179591dc9c3c937136b3b`)

### 2.1. Quy tắc vòng đời đã thực thi trong `AgentBrainService.applyDraft2CustomerUnderstandingUpdatesLocked`
1. **Không ghi đè bởi cảm xúc/phàn nàn nhất thời**: Khi khách phàn nàn về cách tư vấn (`"Hỏi lại lắm thế"`, `"Đã mệt rồi còn hỏi vùng nào chăm sóc hơn..."`, `"cách nói rất gây hiểu lầm"`), `conversation_goal` giữ nguyên mục tiêu nhu cầu đã xác lập; trạng thái hội thoại chuyển sang `FRICTION_RECOVERY` ở kênh riêng (`conversation_state`).
2. **Nâng cấp mục tiêu chung sang mục tiêu cụ thể**: Lượt 1 hỏi chung (`"giá"` → `EXPLORE_SERVICES`), lượt 2 chia sẻ triệu chứng (`"tôi hoa mát trong mắt ù tai mệt mỏi"`) → tự động nâng cấp `conversation_goal` sang mục tiêu tư vấn theo triệu chứng/nhu cầu.
3. **Bảo toàn qua câu hỏi vận hành phụ**: Khi khách hỏi địa chỉ/giờ mở cửa/phòng riêng tư giữa chừng, mục tiêu chính không bị mất.
4. **Cập nhật khi có fact nhu cầu mới**: Khi khách bổ sung triệu chứng, phạm vi, ngân sách, gói quan tâm hoặc khung giờ, `conversation_goal` cập nhật theo fact mới.

### 2.2. Bằng chứng quỹ đạo `conversation_goal` trên `CASE-01` (`6ccb68f3`)
| Lượt | Tin nhắn khách | `goalBefore` | `goalAfter` | `stateAfter` | Ghi chú |
|---|---|---|---|---|---|
| `T1` | `"giá"` | `EXPLORE_SERVICES` | `EXPLORE_SERVICES` | `ACTIVE_CONSULTATION` | Khởi tạo mục tiêu chung khi khách mới hỏi giá trống |
| `T3` | `"tôi hoa mát trong mắt ù tai mệt mỏi"` | `EXPLORE_SERVICES` | `Tư vấn gói dịch vụ phù hợp với triệu chứng` | `ACTIVE_CONSULTATION` | Nâng cấp từ mục tiêu chung sang mục tiêu cụ thể theo triệu chứng |
| `T5` | `"Nó khác j nhau"` | `Tư vấn gói dịch vụ phù hợp với triệu chứng` | `Tư vấn gói dịch vụ phù hợp với triệu chứng` | `ACTIVE_CONSULTATION` | Giữ nguyên mục tiêu xuyên suốt khi khách hỏi so sánh |
| `T7` | `"Hỏi lại lắm thế"` | `Tư vấn gói dịch vụ phù hợp với triệu chứng` | `Tư vấn gói dịch vụ phù hợp với triệu chứng` | `FRICTION_RECOVERY` | **Không bị ghi đè thành phàn nàn**; tách riêng `stateAfter = FRICTION_RECOVERY` |
| `T9` | `"thế rốt cuộc 2 gói khác j nhau"` | `Tư vấn gói dịch vụ phù hợp với triệu chứng` | `Tư vấn gói dịch vụ phù hợp với triệu chứng` | `ACTIVE_CONSULTATION` | Thoát `FRICTION_RECOVERY`, giữ nguyên `conversation_goal` |
| `T11` | `"Đã mệt rồi còn hỏi vùng nào chăm sóc hơn, chăm 1 vùng rồi bỏ các vùng khác à"` | `Tư vấn gói dịch vụ phù hợp với triệu chứng` | `Tư vấn gói dịch vụ phù hợp với triệu chứng` | `ACTIVE_CONSULTATION` | Giữ nguyên mục tiêu tư vấn khi khách thắc mắc phạm vi |
| `T13` | `"haizzz, ngoài 2 gói trên còn gói nào khác k"` | `Tư vấn gói dịch vụ phù hợp với triệu chứng` | `Tư vấn gói dịch vụ phù hợp với triệu chứng` | `ACTIVE_CONSULTATION` | Giữ nguyên mục tiêu khi khách hỏi mở rộng danh mục |
| `T15` | `"thế mà lúc giới thiệu nói có 2 gói, làm người ta tưởng chỉ có 2 gói , cách nói rất gây hiểu lầm"` | `Tư vấn gói dịch vụ phù hợp với triệu chứng` | `Tư vấn gói dịch vụ phù hợp với triệu chứng` | `ACTIVE_CONSULTATION` | `0/117` lượt bị ghi đè `conversation_goal` bởi phàn nàn trên toàn bộ tập Replay |

---

## 3. CP3.2 — Cơ Chế Booking 3 Cấp Theo Tín Hiệu Khách (`df82004a20ace2a80a684931437eabcc56b7e880`)

### 3.1. Phân tầng 3 cấp trong `CleanAgentOrchestratorService`
- **Cấp 1 — Tìm hiểu lịch (`TIER_1_SCHEDULE_EXPLORATION`)**: Mở khi khách hỏi giờ mở cửa, chi nhánh, còn lịch/còn giường không, hoặc hỏi cách đặt lịch (`gentle_next_step_by_signal`).
- **Cấp 2 — Chuẩn bị đặt lịch (`TIER_2_BOOKING_PREPARATION`)**: Mở trước `READY_TO_BOOK` khi khách đã nghe tư vấn và phát tín hiệu chọn gói thử / cân nhắc khung giờ (`warm_nod_to_gentle_scheduling`, `gentle_next_step_by_signal`). Không mở theo số lượt.
- **Cấp 3 — Thực thi đặt lịch (`TIER_3_BOOKING_EXECUTION`)**: Chỉ mở khi khách chủ động yêu cầu đặt lịch / giữ chỗ rõ ràng (`READY_TO_BOOK` → `booking_careful_preparation`, trạng thái `AWAITING_AVAILABILITY_CHECK`).
- **Chặn tuyệt đối (`CustomerBookingSignalTier.NONE`)**: Khi khách mới chia sẻ triệu chứng, phản đối giá, do dự tạm dừng, hoặc đang phàn nàn → chặn mọi skill đặt lịch.

### 3.2. Bằng chứng tiến trình `bookingReadiness` & Kích hoạt/Chặn Skill trên `CASE-09` (`bb00bb82`)
| Lượt | Tin nhắn khách | `bookingReadiness` | `buyingSignal` | `bookingStatus` | `preselectedSkills` | `activatedSkills` | Hành vi chuyển đổi |
|---|---|---|---|---|---|---|---|
| `T1` | `"Chào em, tư vấn cho chị với"` | `EXPLORING` | `NONE` | `NONE` | `[discovery_empathy_and_scope]` | `[discovery_empathy_and_scope]` | Chặn mọi booking skill khi chưa có tín hiệu |
| `T3` | `"Dạo này chị hay mất ngủ, đầu óc căng thẳng..."` | `EXPLORING` | `WEAK` | `NONE` | `[discovery_empathy_and_scope, soft_economical_bridge_and_compare]` | `[discovery_empathy_and_scope]` | Khám phá nhu cầu, 0 câu thúc đặt lịch |
| `T5` | `"Chị còn bị mỏi cổ vai gáy với tê tay... có gói nào phù hợp không?"` | `EXPLORING` | `WEAK` | `NONE` | `[soft_economical_bridge_and_compare]` | `[]` | Giới thiệu gói phù hợp, chưa mở booking |
| `T9` | `"Giữa gói massage cổ vai gáy với massage body tinh dầu thì khác nhau thế nào em?"` | `CONSIDERING` | `MODERATE` | `NONE` | `[package_comparison_and_scope_clarity, soft_economical_bridge_and_compare, warm_nod_to_gentle_scheduling]` | `[package_comparison_and_scope_clarity]` | Mở `package_comparison_and_scope_clarity` giải thích rõ phạm vi 2 gói |
| `T11` | `"Vậy cho chị chọn cái vừa đủ để thử tập trung vùng cổ vai gáy trước nhé"` | `CONSIDERING` | `MODERATE` | `NONE` | `[warm_nod_to_gentle_scheduling, gentle_next_step_by_signal]` | `[warm_nod_to_gentle_scheduling]` | **Booking Cấp 2** mở trước `READY_TO_BOOK` nhờ tín hiệu chọn gói thử; hỏi nhẹ thời điểm thuận tiện |
| `T17` | `"Ok để chị xem sắp xếp công việc chút đã nhé"` | `CONSIDERING` | `WEAK` | `NONE` | `[graceful_pause_keep_warmth]` | `[graceful_pause_keep_warmth]` | Tôn trọng khoảng nghỉ, `0` câu hỏi (`?` = 0), ngắt toàn bộ booking skill |
| `T19` | `"Em ơi tối mai lúc 18h30 giữ lịch cho chị gói vừa chọn lúc nãy nhé"` | `READY_TO_BOOK` | `STRONG` | `AWAITING_AVAILABILITY_CHECK` | `[package_comparison_and_scope_clarity, soft_economical_bridge_and_compare, warm_nod_to_gentle_scheduling]` | `[warm_nod_to_gentle_scheduling]` | **Booking Cấp 3**: Tiến trình đạt `READY_TO_BOOK`, chuyển `bookingStatus = AWAITING_AVAILABILITY_CHECK`, báo kiểm tra lịch trống thực tế |

---

## 4. CP3.3 — Xử Lý `good_example` & Thực Nghiệm A/B (`738289af` + `59899071`)

### 4.1. Thực thi 2 bước tách biệt
1. **Commit 1 (`738289af6e94ca4e771c8958b95a5ae19711743d`, Flyway `V43`)**: Trung hòa `good_example` và `tactical_instructions` của skill `discovery_empathy_and_scope` (`24000001-0000-4000-8000-000000000001`), xóa bỏ hoàn toàn cụm giả định `"đi tập nặng về"`.
2. **Commit 2 (`598990719fb001a8f4068f2bddb2d535ec5245b5`)**: Bổ sung cờ `docmind.aci.cp3.skill-good-example.enabled` trong `CleanAgentCatalogAndPromptBuilder` để bật/tắt việc tiêm `good_example` vào `PAGE_PLAYBOOK_COMPACT_DIRECTIVES` và `<AGENT_BRAIN_INSIGHT>`.

### 4.2. Bảng Đối Chiếu Thực Nghiệm A/B (`Config A: good_example=true` vs `Config B: good_example=false`)
Chạy trên cùng tập `7` cases trọng tâm (`CASE-01, CASE-02, CASE-05, CASE-06, CASE-09, CASE-15, CASE-19` × `N=3` runs = `117` lượt mỗi cấu hình) với `qwen3.8-max`:

| Chỉ số đo lường | Config A (`good_example=true`, dạng nguyên tắc) | Config B (`good_example=false`, ẩn hoàn toàn khỏi prompt) | Chênh lệch (`B - A`) | Đánh giá |
|---|---|---|---|---|
| Tổng số lượt (`jsonValid`) | `117 / 117` (`100%`) | `117 / 117` (`100%`) | `0` | Cả 2 cấu hình đạt 100% JSON hợp lệ, 0 lỗi provider |
| Số lần xuất hiện `"đi tập nặng về"` | `0 / 117` (`0.00%`) | `0 / 117` (`0.00%`) | `0.00%` | Triệt tiêu hoàn toàn giả định sai từ `good_example` cũ |
| **Tỷ lệ lặp khung câu mẫu (`Template Repetition Rate`)** | `1 / 117` (`0.85%`) | **`0 / 117` (`0.00%`)** | **`-0.85%` (Tốt hơn)** | Ẩn `good_example` giúp LLM diễn đạt tự nhiên hơn, 0% lặp câu |
| **Độ sát câu hỏi khách (`Task Fulfillment Pass`)** | `111 / 117` (`94.87%`) | **`113 / 117` (`96.58%`)** | **`+1.71%` (Tốt hơn)** | Config B bám sát câu hỏi khách hơn |
| **Chất lượng hội thoại tư vấn (`Conversational Quality Pass`)** | `105 / 117` (`89.74%`) | **`109 / 117` (`93.16%`)** | **`+3.42%` (Tốt hơn)** | Config B tăng +3.42% chất lượng hội thoại |
| **Tỷ lệ lỗi An toàn & Sự thật (`Safety & Truth Error Rate`)** | `1 / 117` (`0.85%` lỗi; `99.15%` PASS) | `1 / 117` (`0.85%` lỗi; `99.15%` PASS) | `0.00%` | Giữ nguyên độ an toàn 99.15% (trên `CASE-01` Run 1, Config B đạt `8/8` Safety PASS so với `7/8` ở Config A) |
| **Tỷ lệ gợi ý bước tiếp theo trên các lượt có cơ hội đặt lịch** | `22 / 41` (`53.66%`) | **`29 / 42` (`69.05%`)** | **`+15.39%` (Tốt hơn)** | Config B chủ động dẫn dắt bước tiếp theo mượt hơn khi khách có tín hiệu |
| **Tổng số lượt đạt chuẩn toàn diện (`Overall Turn Pass`)** | `100 / 117` (`85.47%`) | **`104 / 117` (`88.89%`)** | **`+3.42%` (Tốt hơn)** | **Khuyến nghị chọn Config B làm cấu hình vận hành tối ưu** |

---

## 5. CP3.4 — Kiểm Toán Skill Nàng Sen & Kiểm Chứng Cách Ly Cross-Page (`ef4557ca8e0cfe55e0e95a2e082cc1d378195b74`)

### 5.1. Bảng Kiểm Toán 6 Skill Hiện Có Của Nàng Sen (`page_id = 2850a8ef-ca4a-40eb-87fa-4f808640efee`)

| ID | `code` | `trigger_signals` | Lỗi liên quan trước kiểm toán | Quyết định (Giữ / Sửa / Xóa) |
|---|---|---|---|---|
| `24000001-...-0001` | `discovery_empathy_and_scope` | `SHARING_CONDITION_OR_CONTEXT, ACKNOWLEDGED_GENERAL_INFO` | Chứa ví dụ `"đi tập nặng về"` và nhúng câu thoại mẫu dài trong `tactical_instructions` khiến bot hỏi vùng cơ thể máy móc | **SỬA (GIỮ ACTIVE)**: Rút gọn về nguyên tắc ngắn, xóa toàn bộ câu thoại mẫu nhúng trong `tactical_instructions` |
| `24000001-...-0002` | `price_objection_sensory_value` | `PRICE_OBJECTION_FIRST_TIME, PRICE_OBJECTION` | Nhúng câu thoại mẫu hoàn chỉnh dễ gây lặp văn phong khi khách chê đắt | **SỬA (GIỮ ACTIVE)**: Chuyển sang nguyên tắc ngắn (đồng cảm ngân sách + nêu giá trị thời lượng/quy trình thật trong Catalog) |
| `24000001-...-0003` | `soft_economical_bridge_and_compare` | `PRICE_HESITATION_AFTER_VALUE_DEFENSE, PRICE_OBJECTION_REPEAT_OR_DOWNSELL_REQUEST` | Ôm đồm cả cầu nối gói tiết kiệm lẫn so sánh gói, kèm câu mẫu dài | **SỬA (GIỮ ACTIVE)**: Chuyên biệt hóa cho cầu nối gói tiết kiệm cùng nhóm dịch vụ; tách phần so sánh gói sang skill riêng |
| `24000001-...-0004` | `warm_nod_to_gentle_scheduling` | `POSITIVE_CONFIRMATION_SIGNAL, POSITIVE_INTEREST_READY_FOR_TRIAL_INVITE` | Nhúng câu mẫu cố định về góc giường/rèm riêng dễ lặp lại nguyên văn | **SỬA (GIỮ ACTIVE — Booking Cấp 2)**: Viết dạng nguyên tắc ngắn khi khách đồng ý thử gói, hỏi nhẹ chi nhánh/khung giờ |
| `24000001-...-0005` | `booking_careful_preparation` | `ACCEPTED_PROPOSED_TIME_SLOT, PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS` | Chứa câu thoại mẫu hoàn chỉnh trong `tactical_instructions` | **SỬA (GIỮ ACTIVE — Booking Cấp 3)**: Chỉ mở khi khách yêu cầu đặt lịch rõ; ghi nhận gói + giờ và báo chờ lễ tân kiểm tra chỗ trống (`AWAITING_AVAILABILITY_CHECK`) |
| `24000001-...-0006` | `graceful_pause_keep_warmth` | `POLITE_CLOSING_ACKNOWLEDGMENT, HESITATION_OR_DEFERRAL` | Chứa câu chúc mẫu dài dễ lặp lại máy móc | **SỬA (GIỮ ACTIVE)**: Nguyên tắc tôn trọng khoảng nghỉ trong 1 bong bóng ngắn, `0` câu hỏi, `0` chào thêm gói mới |

### 5.2. Bảng 4 Skill Nguyên Tắc Ngắn Bổ Sung Cho Nàng Sen (Flyway `V44`)

| ID | `code` | Vai trò nghiệp vụ | Nguyên tắc hành động (`tactical_instructions`) |
|---|---|---|---|
| `24000001-...-0007` | `package_comparison_and_scope_clarity` | Giải thích và so sánh gói | Đối chiếu trực diện đúng các gói vừa nêu theo 3 trục: phạm vi vùng chăm sóc, thời lượng thực hiện và hạng mục cốt lõi trong Catalog; giữ đúng tên gói và giá, không đổi gói, không hỏi lại vùng mỏi |
| `24000001-...-0008` | `catalog_breadth_clarification` | Làm rõ danh mục gói | Xác nhận ngắn gọn bên spa có nhiều gói đa dạng theo từng nhu cầu rồi giới thiệu ngay 2–3 gói khác trong Catalog kèm giá và thời lượng chuẩn xác |
| `24000001-...-0009` | `service_recovery_to_consultation_pivot` | Thoát recovery và quay lại tư vấn | Nhận lỗi trực diện tối đa 1 câu ngắn (không lặp lại lời xin lỗi nếu lượt trước đã xin lỗi) và trả lời đầy đủ câu hỏi nghiệp vụ của khách bằng dữ liệu Catalog; ngắt hoàn toàn lời mời đặt lịch |
| `24000001-...-0010` | `gentle_next_step_by_signal` | Gợi ý bước tiếp theo nhẹ nhàng theo tín hiệu khách | Khi khách chủ động hỏi lịch trống, chi nhánh, giờ mở cửa, cách đặt lịch hoặc đồng ý thử gói (Booking Cấp 1–2): trả lời rõ thông tin khách hỏi và gợi ý nhẹ 1 bước tiếp theo phù hợp tín hiệu |

### 5.3. Kiểm chứng Negative Control Cross-Page Isolation (`Page thẩm mỹ viện`)
- **Database Verification (`docmind-postgres`)**:
  - `SELECT page_id, count(*) FROM agent_skills GROUP BY page_id;` → Chỉ duy nhất page Nàng Sen (`2850a8ef-ca4a-40eb-87fa-4f808640efee`) có `10` skills; `Page thẩm mỹ viện` (`59e68078-8327-4aa0-8cca-91410f783ad4`) có `0` skills, `0` KB Nàng Sen, và không bị tác động bởi `V43`/`V44`.
- **Unit Test Verification (`Sbr01Cp3SkillAuditAndIsolationTest#negativeControlBeautyClinicPageIsStrictlyIsolatedFromNangSen`)**:
  - Khi dựng prompt cho `Page thẩm mỹ viện` (`59e68078-8327-4aa0-8cca-91410f783ad4`), prompt không chứa chuỗi `"Nàng Sen"` hay bất kỳ `skill_code` nào của Nàng Sen (`PASS`).

---

## 6. CP3.5 — Chẩn Đoán Tiến Trình Bán Hàng (`Sales Progression Diagnostics`)

> **Lưu ý mẫu số theo đúng chỉ thị CP3.5**: Tỷ lệ lời mời trải nghiệm / bước tiếp theo được tính trên **tổng số lượt có cơ hội mời đặt lịch (`Booking Opportunity Turns`)**, tuyệt đối không lấy mẫu số là tổng toàn bộ lượt hội thoại (vì các lượt khám phá triệu chứng đầu phiên, lượt phàn nàn, hoặc lượt khách xin tạm dừng không phải là cơ hội mời đặt lịch).

| Chỉ số Sales Progression | Config A (`good_example=true`) | Config B (`good_example=false`) | Mục tiêu / Ràng buộc CP3.5 |
|---|---|---|---|
| Tổng số lượt hội thoại trong tập Replay (`7 cases × 3 runs`) | `117` | `117` | — |
| **Số lượt có cơ hội tiến triển đặt lịch (`Booking Opportunity Turns`)** | `41` | `42` | Mẫu số chuẩn cho tỷ lệ mời trải nghiệm / bước tiếp theo |
| **Tỷ lệ có lời mời trải nghiệm / gợi ý bước tiếp theo trên các lượt có cơ hội** | `22 / 41` (**`53.66%`**) | `29 / 42` (**`69.05%`**) | Tăng mạnh ở Config B (`+15.39%`) mà không ép chốt sớm |
| **Số câu hỏi tối đa trên mỗi lượt (`Max Questions / Turn`)** | **`1`** (`0/117` lượt `> 1` câu hỏi) | **`1`** (`0/117` lượt `> 1` câu hỏi) | Đạt tuyệt đối ràng buộc **`<= 1` câu hỏi / lượt (`100%`)** |
| **Tỷ lệ khách phải lặp lại yêu cầu do bot bỏ sót câu hỏi nghiệp vụ ở lượt phàn nàn (`CASE-01` T9, T11, T13, T15)** | `0 / 12` (`0.00%` — `12/12` lượt `taskPass=true`) | `0 / 12` (`0.00%` — `12/12` lượt `taskPass=true`) | Bot trả lời ngay câu hỏi so sánh/danh mục trong cùng lượt |
| **Tiến trình `bookingReadiness` (`EXPLORING → CONSIDERING → READY_TO_BOOK`)** | Đạt chuẩn trên `CASE-09` (`3/3` runs) & `CASE-06` | Đạt chuẩn trên `CASE-09` (`3/3` runs) & `CASE-06` | Chuyển trạng thái theo đúng tín hiệu khách hàng |

---

## 7. CP3.6 — Kịch Bản Test Nhanh 10 Phút Trên Messenger Simulator Cho Owner

Owner có thể mở **Messenger Simulator** trên giao diện quản trị của Page **Nàng Sen** (`2850a8ef-ca4a-40eb-87fa-4f808640efee`) và nhập lần lượt 8 bước dưới đây (dựa trên hội thoại gốc `6ccb68f3` kết hợp nhánh chuyển đổi đặt lịch) để nghiệm thu trực quan trong 10 phút:

| Bước | Tin nhắn nhập thử của Owner | Điểm cần quan sát ở phản hồi của Bot | Hành vi đạt chuẩn kỳ vọng |
|---|---|---|---|
| **1** | `giá` | Cách mở đầu + báo giá + minh bạch độ rộng danh mục | Chào ấm áp, nêu 2 gói tiêu biểu kèm giá & thời lượng (`239k` / `399k`), nói rõ *"đây là vài gói tiêu biểu, bên em còn các gói khác"*, tối đa 1 câu hỏi. |
| **2** | `tôi hoa mắt chóng mặt ù tai mệt mỏi` | Ghi nhận triệu chứng + `conversation_goal` + không giả định nguyên nhân | Ghi nhận đúng triệu chứng khách vừa nêu, **tuyệt đối không có cụm `"đi tập nặng về"`**, nâng cấp `conversation_goal` sang tư vấn theo triệu chứng. |
| **3** | `2 gói đó khác gì nhau` | Skill `package_comparison_and_scope_clarity` | So sánh trực diện gói `239k` (gội dưỡng sinh + cổ vai gáy 60p) và gói `399k` (massage trị liệu toàn thân 60p, ăn nhẹ thực dưỡng), không tự ý đổi sang gói khác. |
| **4** | `Hỏi lại lắm thế` | Phàn nàn thuần (`PURE_COMPLAINT`) + bảo toàn `conversation_goal` | Nhận lỗi ngắn gọn, dịu giọng, **`0` câu hỏi (`?` = 0)**, **`0` lời mời đặt lịch**, `conversation_goal` trong Brain Inspector không bị ghi đè thành `"phàn nàn"`. |
| **5** | `Đã mệt rồi còn hỏi vùng nào chăm sóc hơn, chăm 1 vùng rồi bỏ các vùng khác à` | Phàn nàn kèm thắc mắc phạm vi (`COMPLAINT_WITH_BUSINESS_QUESTION`) | Tối đa 1 câu nhận lỗi trực diện + giải thích rõ ngay phạm vi gói chăm sóc vùng chuyên biệt vs gói toàn thân; `0` CTA đặt lịch. |
| **6** | `haizzz, ngoài 2 gói trên còn gói nào khác k` | Skill `catalog_breadth_clarification` + không lặp lời xin lỗi | Không lặp lại lời xin lỗi ở đầu câu; giới thiệu ngay các gói khác trong Catalog (`339k` 80p, `580k` 100p...) kèm giá & thời lượng chuẩn xác. |
| **7** | `Vậy cho mình thử gói dưỡng sinh 239k trước nhé, tối nay bên mình còn lịch không?` | **Booking Cấp 1–2** (`warm_nod_to_gentle_scheduling` + `gentle_next_step_by_signal`) | Giữ đúng gói `239k` khách chọn (không ép upsell sang gói đắt), xác nhận khung giờ hoạt động và hỏi nhẹ giờ cụ thể/cơ sở khách tiện ghé. |
| **8** | `Giữ lịch cho mình 19h tối nay ở cơ sở Hà Nội nhé, sđt 0912345678` | **Booking Cấp 3** (`booking_careful_preparation`, `READY_TO_BOOK`) | Ghi nhận đúng gói `239k`, `19h tối nay`, cơ sở Hà Nội và SĐT; thông báo rõ **đang giữ lịch tạm chờ lễ tân kiểm tra giường/KTV trống thực tế (`AWAITING_AVAILABILITY_CHECK`)**, không tự tuyên bố đã xác nhận lịch chính thức. |
