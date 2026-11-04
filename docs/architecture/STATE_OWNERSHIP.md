# STATE OWNERSHIP SPECIFICATION — SINGLE SOURCE OF TRUTH

> **Phase Gate**: `P2 — Architecture Design`  
> **Baseline Commit**: `f091ba557a079da86c8ae14cd7ccc19fe8ece59d`  
> **Tài liệu liên kết**: Xem đối chiếu hiện trạng tại [`STATE_OWNERSHIP_MAP.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/STATE_OWNERSHIP_MAP.md) và quyền sở hữu module tại [`MODULE_OWNERSHIP.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/MODULE_OWNERSHIP.md).

---

## 1. Bốn Thực thể Trạng thái Chuẩn hóa trong `ConversationMemoryStore`

Trong kiến trúc đích, toàn bộ trạng thái nhận thức, quan hệ khách hàng và đặt lịch được hợp nhất vào **`ConversationMemoryStore`** (lưu bền vững trong `conversation_sessions.aci_memory_state_json` với cơ chế khóa lạc quan `version` qua `ConversationSessionOptimisticMergeCoordinator`):

### 1.1. `CustomerRelationshipState`
- `inferredCustomerPronoun`: Đại từ xưng hô của khách (`"anh"`, `"chị"`, hoặc `null` -> tự động dùng đại từ trung tính `"mình"` / `"bạn"`).
- `pronounConfidence`: Độ tin cậy (`0.0` – `1.0`). Khi khách yêu cầu đổi cách xưng hô -> khóa cứng `1.0`.
- `relationshipStage`: `NEW_VISITOR` -> `EXPLORING_NEEDS` -> `EVALUATING_OPTIONS` -> `OBJECTION_HANDLING` -> `BOOKING_COMMITMENT` -> `PAUSED`.
- `priceObjectionCount`: Số lần khách thực sự phản đối giá (không tăng khi khách chỉ hỏi làm rõ chi tiết gói).
- `lastAssistantTurnGoal` & `lastActualSentSpeechAct`: Mục tiêu và hành động thực tế bot đã gửi ở lượt liền trước.

### 1.2. `ConfirmedFactLedger`
- `confirmedCustomerStatements`: Danh sách phát ngôn gốc của khách kèm `turnId` làm bằng chứng truy nguyên.
- `confirmedNeedsAndPains`: Các vùng đau mỏi / nhu cầu đã được khách xác nhận (`cổ vai gáy`, `mất ngủ`, `thư giãn body`...).
- `verifiedSymptomDuration`: Thời lượng triệu chứng khách đã nêu (`"1 tuần"`, `"mấy hôm nay"`...).
- `retractedOrForbiddenScopes`: Các vùng cơ thể, dịch vụ hoặc thành phần gói mà khách đã bác bỏ (ví dụ: `"không gội đầu"`, `"mình không đau cổ vai gáy"`). Bất kỳ mục nào nằm trong `retractedOrForbiddenScopes` đều bị xóa khỏi `confirmedNeedsAndPains` và bị cấm xuất hiện lại trong lời tư vấn của bot.

### 1.3. `BookingProgressState`
- `status`: `NONE` | `COLLECTING_DETAILS` | `READY_FOR_CONFIRMATION` | `CONFIRMED` | `HANDOFF_PENDING`.
- `serviceId` & `serviceName`: Gói dịch vụ khách đã chọn.
- `branchId` & `branchName`: Chi nhánh khách chọn (tự động điền nếu Page chỉ có 1 chi nhánh).
- `timeSlot`: Thời gian hẹn khách chọn.
- `partySize`: Số lượng khách (mặc định `1` nếu khách đặt cho bản thân, hoặc cập nhật khi khách báo đi nhiều người).
- `customerPhone`: Số điện thoại liên hệ.
- `missingBookingFields`: Danh sách các trường bắt buộc còn thiếu được tính toán tất định để bot chỉ hỏi đúng thông tin còn thiếu tiếp theo, **không bao giờ hỏi lại thông tin khách đã cung cấp**.

### 1.4. `ConversationTurnLedger`
- Đồng bộ trực tiếp với `SentBubbleLedger` để lưu vết từng lượt `(turnId, customerVerbatimMessage, assistantDeliveredBubbles, turnStrategySummary)`.

---

## 2. Quy tắc Xóa bỏ Trạng thái Dư thừa (`State Deduplication Rules`)

1. **Loại bỏ bản sao `StructuredBookingState` trong `ConversationState.SESSION_CACHE`**:
   - Mọi thao tác đọc/ghi trạng thái đặt lịch đều đi qua `ConversationMemoryStore.getBookingProgressState(pageId, senderId)` và `updateBookingProgressState(...)`.
2. **Loại bỏ `CustomerUnderstandingDraft2Snapshot` lồng ghép dư thừa**:
   - Các trường hữu ích của `Draft2Snapshot` (`painPoints`, `objectionHistory`, `selectedService`, `bookingTime`, `branchOrLocation`, `customerPhone`) được ánh xạ thẳng vào `ConfirmedFactLedger` và `BookingProgressState`, có hỗ trợ đọc tương thích ngược từ JSONB cũ trong PostgreSQL.
3. **Loại bỏ 35+ cờ boolean V1 trong `ConversationState`**:
   - Các cờ cũ như `softDelayedOnce`, `consultativeCloseAttempted`, `lossAversionTriggered` chỉ còn giữ ở mức tương thích đọc cho đến khi hoàn tất giai đoạn `P7`.
