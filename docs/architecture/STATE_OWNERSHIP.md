# STATE OWNERSHIP & CONSISTENCY SPECIFICATION (TO-BE NORMATIVE DESIGN)

> **Document Authority**: **TO-BE NORMATIVE SPECIFICATION** (Quy định thiết kế đích cho quyền sở hữu trạng thái, giao thức nhất quán dữ liệu `R2` và mô hình trí tuệ khách hàng `R3`; có hiệu lực cao hơn tài liệu khảo sát hiện trạng [`STATE_OWNERSHIP_MAP.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/STATE_OWNERSHIP_MAP.md)).  
> **Phase Gate**: `P2 — Architecture Lock Pending Project Owner Approval`  
> **Approval Status**: **PROPOSED** *(Chỉ chuyển sang `ACCEPTED` sau khi Chủ dự án phê duyệt Architecture Lock)*  
> **Baseline Commit Before P0**: `244a2ec8a120878f8f863cb0388d72d51ae8bc2e`  
> **P0 Cleanup Commit**: `f091ba557a079da86c8ae14cd7ccc19fe8ece59d`  
> **P2 Initial Draft Commit**: `5aa985d43b5ef6ea1621f37fa546bcae520633c2`

---

## 1. Phân cấp Tài liệu & Bảng Quyền Sở hữu Trạng thái Đích (R9)

- [`STATE_OWNERSHIP_MAP.md`](file:///d:/Project/digital-spirituality/docmind/docs/architecture/STATE_OWNERSHIP_MAP.md) là **Báo cáo Kiểm toán Hiện trạng (`AS-IS AUDIT`)**, chỉ dùng để ghi nhận sự phân mảnh của hệ thống cũ trước khi tái cấu trúc.
- Tài liệu này (`STATE_OWNERSHIP.md`) là **Đặc tả Thiết kế Đích (`TO-BE NORMATIVE`)**. Khi triển khai `P3–P7`, mọi module bắt buộc tuân theo quyền sở hữu trạng thái và giao thức nhất quán dưới đây:

| Thực thể Trạng thái (State Entity) | Owner Duy nhất (Read/Write Authority) | Nơi Lưu Bền Vững (Durable Backing) | Cơ chế Đồng bộ & Bảo vệ |
|---|---|---|---|
| **1. `SessionControlState`** (`controlMode`, `optedOut`, `optedOutAt`, `reOptInAt`, `lastInboundMid`) | `InboundSessionPersistenceService` (Miền A — Gateway) | Bảng `conversation_sessions` (`control_mode`, `opted_out`, `version`) & `conversation_messages` (`ux_conversation_messages_session_external_mid`) | Giao dịch `Tx-Entry` (`REQUIRES_NEW`) ở đầu lượt + kiểm tra đọc tươi ở `Layer-2 Exit Fence`. |
| **2. `CustomerIntelligenceLedger`** (`FACT`, `HYPOTHESIS`, `UNKNOWN` + `CustomerStatementRecord`) | `ConversationMemoryStore` (Miền B — Memory) | `conversation_sessions.aci_memory_state_json` (PostgreSQL JSONB, được bảo vệ bởi JPA `@Version`) | Cập nhật qua `ConversationSessionOptimisticMergeCoordinator` (`Tx-Commit`) với luật `Monotonic Merge` theo `turnSeq` và độ ưu tiên trạng thái. |
| **3. `CustomerRelationshipState`** (`customerPronoun`, `pronounConfidence`, `relationshipStage`, `priceObjectionCount`, `customerLocation`) | `ConversationMemoryStore` (Miền B — Memory) | `conversation_sessions.aci_memory_state_json` + cột `honorific` trên `conversation_sessions` | Khóa tự tin `1.0` khi khách chủ động đính chính xưng hô; tách biệt hoàn toàn `customerLocation` với `preferredBranchId`. |
| **4. `BookingProgressState`** (`serviceId`, `branchId`, `timeSlot`, `partySize`, `customerPhone`, `status`, `appliedBookingPatchIds`) | `ConversationMemoryStore` (Miền B — Memory) | `conversation_sessions.aci_memory_state_json` + cột `captured_phone`, `preferred_time` | **Xóa bỏ bản sao trong `ConversationState.SESSION_CACHE`**. Chỉ cập nhật qua `BookingPatch` có `patchId` bất biến và kiểm tra idempotency. |
| **5. `SentBubbleLedger` (Transactional Outbox & Delivery Ledger)** (`outboundIdempotencyKey`, `bubbleText`, `deliveryStatus`, `externalMid`, `deliveredAt`) | `OutboundDeliveryDispatcher` & `SentBubbleLedger` / `JdbcSentBubbleStore` (Miền A — Gateway) | Bảng `sent_bubble_ledger_entries` & `conversation_messages` | `Tx-Commit` ghi bản ghi ở trạng thái `PENDING`. Sau khi qua `Layer-2 Exit Fence`, Dispatcher gửi tin và cập nhật sang `ACCEPTED_BY_CHANNEL`, `FAILED`, `UNKNOWN`, hoặc `DELIVERED` (chỉ khi có delivery receipt thực tế). |

---

## 2. Giao thức Nhất quán Dữ liệu `ConversationMemoryStore` (`R2 — Consistency Protocol`)

### 2.1. Nguyên tắc Cốt lõi & Giới hạn Cam kết Concurrency v1 (`Single-Instance`)
- **Topology triển khai hiện tại (`Verified`)**: Hệ thống hiện triển khai **Single-Instance (`1` container `docmind-api` `:8090` duy nhất)** kết nối tới `1` container `docmind-postgres` (`:5433->5432`) và `1` container `docmind-doc-service` (`:8091`).
- **Đã xác minh trong mã nguồn hiện tại**: Hệ thống đã có [`ConversationSessionOptimisticMergeCoordinator.java`](file:///d:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/bot/ConversationSessionOptimisticMergeCoordinator.java) (`L54–L283`, sử dụng JPA `@Version` trên bảng `conversation_sessions` với `MAX_OPTIMISTIC_MERGE_ATTEMPTS = 6`) và [`AciCustomerBrainMemorySnapshot.java`](file:///d:/Project/digital-spirituality/docmind/api-java/src/main/java/com/example/docmind/service/bot/AciCustomerBrainMemorySnapshot.java) (`L680–L1043`, hàm `monotonicMerge`).
- **Giới hạn chính xác cam kết Concurrency của v1**:
  - `Striped<Lock>` và `SenderEpochRegistry` chỉ nằm trong bộ nhớ RAM của một JVM duy nhất.
  - JPA `@Version` OCC chỉ bảo vệ tính nhất quán khi ghi vào dòng `conversation_sessions` lúc `Tx-Commit`, nhưng **OCC hoặc JVM-local lock KHÔNG đảm bảo chặn hoàn toàn stale outbound (tin nhắn của lượt cũ gửi trễ ra kênh) giữa nhiều Java instances** nếu chưa có **persistent fencing token / sequence lock** ở tầng Outbound Dispatcher và chưa có kiểm thử liên instance.
  - Do đó, **kiến trúc v1 chỉ cam kết (guarantees) đầy đủ tính chất Single-Turn Winner, Early Epoch Preemption và Stale Outbound Suppression trên topology `Single-Instance` hiện tại**.
  - **Không bổ sung Redis distributed lock và không giữ `pg_advisory_xact_lock` trong suốt lượt hội thoại** vì chưa có nhu cầu chạy multi-instance thực tế. `pg_advisory_xact_lock` chỉ được dùng duy nhất trong giao dịch ngắn (< 5ms) khi khởi tạo dòng `conversation_sessions` lần đầu tiên (`findOrCreateSessionWithAdvisoryLockInTx` tại `AciSessionMemoryPersistenceService.java` `L160`).

### 2.2. Giải đáp 7 Yêu cầu Thiết kế Nhất quán Dữ liệu (R2.1 – R2.7)

#### (1) Ranh giới Giao dịch (`Transaction Boundary`) khi Đọc State, Áp dụng Patch, Tăng `version`, Lưu `patchId` và Ghi Outbox `PENDING`
Mỗi lượt hội thoại được tách thành **các giao dịch cơ sở dữ liệu ngắn (`REQUIRES_NEW`)** bao quanh **1 pha suy luận không giữ kết nối DB** ở giữa (nhằm tránh làm cạn kiệt HikariCP connection pool trong 3–8 giây chờ LLM):

1. **Giao dịch 1 — `Tx-Entry` (`REQUIRES_NEW`, ~5–15ms, tại `InboundSessionPersistenceService` — Miền A)**:
   - Kiểm tra `control_mode`, `opted_out`, chèn tin nhắn khách vào `conversation_messages` với ràng buộc duy nhất `UNIQUE (session_id, external_mid)` (`V32`).
   - Đọc `ConversationSessionEntity` hiện tại: lấy `(sessionId, baseDbVersion, aciMemoryStateJson)`.
   - Giải phóng kết nối DB ngay sau khi `Tx-Entry` commit.
2. **Pha Tính toán Không Giữ Giao dịch DB (`Stateless Compute & LLM Phase` — Miền C, D, E, F)**:
   - Thực hiện Hybrid RAG, chạy `TurnUnderstandingEngine` / `SalesStrategyPlanner` / `ConsultativeResponseGenerator` / `OutputTruthValidator`.
   - Tạo ra ứng viên cập nhật bộ nhớ (`MemoryDeltaCandidate`), bản vá đặt lịch (`BookingPatch` kèm `patchId`, `turnSeq`) và danh sách bong bóng đầu ra đã qua kiểm định.
3. **Giao dịch 2 — `Tx-Commit` (`REQUIRES_NEW`, ~5–15ms, tại `ConversationSessionOptimisticMergeCoordinator` — Miền B phối hợp Sổ cái Outbox)**:
   - Trong cùng **một giao dịch SQL nguyên tử**:
     - Nạp hoặc gắn `ConversationSessionEntity` với `@Version = baseDbVersion`.
     - Kiểm tra `BookingPatch.patchId` chưa tồn tại trong `applied_booking_patch_ids`.
     - Áp dụng `MemoryDeltaCandidate` + `BookingPatch` + ghi thêm `patchId` vào danh sách `applied_booking_patch_ids` (giới hạn 32 mã gần nhất theo cơ chế FIFO) bên trong `aci_memory_state_json`.
     - Cập nhật đồng bộ cột `captured_phone` và `preferred_time` trên dòng `conversation_sessions`.
     - Thực thi `UPDATE conversation_sessions SET aci_memory_state_json = ?, captured_phone = ?, preferred_time = ?, updated_at = ?, version = version + 1 WHERE id = ? AND version = ?`.
     - **Ghi bản ghi Outbound vào `sent_bubble_ledger_entries` / `conversation_messages` ở trạng thái khởi tạo `PENDING`** kèm khóa lũy đẳng `outboundIdempotencyKey = "ob_" + SHA256_HEX_32(sessionId + ":" + turnId + ":" + bubbleIndex)`. **Tuyệt đối không ghi `DELIVERED` trước khi kênh nhận thành công.**
4. **Pha Phát tin & Cập nhật Trạng thái Sổ cái — `Outbound Dispatch & Tx-Delivery-Update` (tại `OutboundDeliveryDispatcher` — Miền A)**:
   - Sau khi `Tx-Commit` hoàn tất, Dispatcher kiểm tra quyền gửi tại `Layer-2 Outbound Exit Fence` (`verifyOutboundStillPermittedFresh` + `SenderEpochRegistry.isCurrent(epochToken)`), thực hiện gửi từng bong bóng `PENDING` và cập nhật trạng thái thực tế (`ACCEPTED_BY_CHANNEL`, `FAILED`, `UNKNOWN`, hoặc `SUPPRESSED_BY_FENCE`; trạng thái `DELIVERED` chỉ được cập nhật khi nhận được delivery receipt thực tế).

#### (2) Xử lý Xung đột OCC (`OCC Conflict Handling`): Re-read, Re-apply Patch, Bounded Retry & Terminal Failure
Khi câu lệnh `UPDATE ... WHERE id = ? AND version = expectedBaseVersion` trả về `0 rows` (ném `OptimisticLockingFailureException` / `StaleStateException`):
1. **Re-read**: Mở giao dịch `REQUIRES_NEW` mới, đọc bản ghi `freshDb` mới nhất vừa được luồng khác commit từ PostgreSQL (`sessionRepository.findById(sessionId)`).
2. **Check Control & Epoch Preemption**:
   - Nếu `freshDb.controlMode` đã chuyển sang `HUMAN_TAKEOVER` hoặc `freshDb.optedOut == true`, hoặc `SenderEpochRegistry` báo lượt hiện tại đã bị thay thế bởi tin nhắn mới hơn của khách -> giữ lại các `FACT` hợp lệ khách vừa cung cấp trong lượt này (nếu có) nhưng không tạo bản ghi outbound `PENDING` (hoặc đánh dấu `SUPPRESSED_BY_FENCE`).
3. **Re-apply Patch via Monotonic Merge**:
   - Giải tuần tự `dbSnapshot = AciCustomerBrainMemorySnapshot.fromJson(freshDb.getAciMemoryStateJson())`.
   - Áp dụng hàm tất định `merged = monotonicMerge(dbSnapshot, candidateSnapshot)` và tái áp dụng `BookingPatch` (chỉ ghi đè các trường mà `BookingPatch.turnSeq >= dbSnapshot.bookingFieldTurnSeq` và `patchId` chưa có trong `dbSnapshot.appliedBookingPatchIds`).
4. **Bounded Retry**:
   - Thử lại tối đa `MAX_OPTIMISTIC_MERGE_ATTEMPTS = 6` lần với backoff ngắn có jitter (`15ms, 30ms, 60ms, 120ms, 240ms`).
5. **Terminal Failure Behavior (Khi hết 6 lần thử vẫn xung đột hoặc DB lỗi)**:
   - Ghi log cảnh báo mức cao `[MEMORY-OCC-EXHAUSTED] sessionId=... turnId=...`.
   - **Evict ngay lập tức** entry của `(pageId, senderId)` khỏi bộ nhớ đệm `memoryStore` trong JVM để tránh giữ state bẩn.
   - Không ghi đè mù quáng xuống DB và đánh dấu `memoryCommitStatus = DEGRADED_UNCOMMITTED` trong `decisionTrace`.

#### (3) Tính Lũy đẳng của `BookingPatch` (`Idempotency of BookingPatch`)
```java
public record BookingPatch(
        String patchId,             // Deterministic: "bp_" + SHA256_HEX_32(sessionId + ":" + inboundExternalMidOrTurnId + ":" + canonicalMutationsJson)
        int turnSeq,                // Số thứ tự lượt tăng dần
        String sourceTurnId,        // ID lượt sinh ra patch
        Map<BookingField, FieldMutation> mutations // SET_CONFIRMED | REPLACE_USER_CHANGE | CLEAR_RETRACTED
) {}
```
- **Công thức `patchId` thống nhất toàn hệ thống**: `patchId = "bp_" + SHA256_HEX_32(sessionId + ":" + inboundExternalMidOrTurnId + ":" + canonicalMutationsJson)`. Nếu cùng một sự kiện inbound (`external_mid`) hoặc cùng một lượt bị retry trong vòng lặp OCC từ bước 1 đến bước 6, `patchId` sinh ra luôn trùng khớp 100%.
- **Quy tắc Lũy đẳng (Idempotency Rule)**:
   - Trong `aci_memory_state_json`, mảng `applied_booking_patch_ids` lưu 32 `patchId` gần nhất theo FIFO.
   - Nếu `applied_booking_patch_ids.contains(patch.patchId())` -> Bỏ qua ngay lập tức (No-op), trả về trạng thái hiện tại.
   - Mỗi trường trong `BookingProgressState` (`serviceId`, `branchId`, `timeSlot`, `partySize`, `customerPhone`) lưu kèm `updatedTurnSeq`. Một `BookingPatch` đến muộn có `patch.turnSeq < field.updatedTurnSeq` **không bao giờ** ghi đè được giá trị đã được khách cập nhật ở lượt mới hơn.

#### (4) Thời điểm Cập nhật Cache & Cơ chế Vô hiệu hóa / Phục hồi khi DB Commit Thành công nhưng Cache Update Thất bại
- **Quy tắc Vàng: DB-First, Cache-Second (Write-Through kèm Version Gate & Evict-on-Failure)**:
  1. Tuyệt đối **không cập nhật `memoryStore` trong JVM trước khi `Tx-Commit` xuống PostgreSQL hoàn tất**.
  2. Sau khi `Tx-Commit` trả về `PersistedSessionOutcome(sessionId, newDbVersion, mergedSnapshot)`:
     - Trong khối `try`, gọi `memoryStore.compute(cacheKey, (k, existing) -> (existing == null || newDbVersion >= existing.dbVersion()) ? new CachedMemoryEntry(newDbVersion, mergedSnapshot) : existing)`.
  3. **Xử lý khi DB commit thành công nhưng cập nhật cache lỗi (hoặc bất kỳ ngoại lệ runtime nào sau DB commit)**:
     - Trong khối `catch (Throwable t)`, bắt buộc gọi `memoryStore.remove(cacheKey)` (Cache Invalidation).
     - Ở lượt tiếp theo, khi `refreshTurnBaseFromDb(pageId, senderId)` thấy cache trống (cache miss), hệ thống tự động nạp lại (`Lazy Hydration`) bản ghi đã commit thành công từ PostgreSQL (`loadLatestSessionMemory`). Nhờ đó, **sự cố bộ nhớ đệm trong JVM không bao giờ làm lệch trạng thái phiên**.

#### (5) Phạm vi Cam kết Single-Instance v1 và Giới hạn Khi Chạy Nhiều Java Instances (`Multi-Instance Limitation`)
- **Xác minh Topology Hiện tại**: Hệ thống triển khai thực tế trên `docker-compose.yml` chỉ có **1 instance `docmind-api` (`Single-Instance`)**.
- **Hành vi trên `Single-Instance` (Được cam kết đầy đủ trong v1)**:
  - `SenderEpochRegistry` (in-JVM) + `Striped<Lock>` + JPA `@Version` OCC + `Layer-2 Exit Fence` phối hợp chặt chẽ trong cùng 1 không gian bộ nhớ và DB để hủy sớm lượt cũ (`SUPERSEDED_ABORTED`) và chặn hoàn toàn stale outbound.
- **Giới hạn nếu chạy Nhiều Java Instances (Không nằm trong cam kết v1)**:
  - Dù `refreshTurnBaseFromDb` và JPA `@Version` OCC giúp tránh ghi đè mù quáng cột JSONB trong PostgreSQL, nhưng vì `SenderEpochRegistry` và `Striped<Lock>` là JVM-local và hệ thống **chưa có persistent sequence/fencing token kiểm tra nguyên tử cùng lệnh gọi HTTP ra Graph API cũng như chưa có kiểm thử liên instance**, OCC không thể tự chặn một instance khác đang gửi HTTP request trễ ra Messenger.
  - Do đó, **tài liệu kiến trúc v1 ghi nhận rõ: chỉ cam kết concurrency và chặn stale outbound trên `Single-Instance`**. Khi nào phát sinh nhu cầu scale ngang nhiều instance `docmind-api`, hệ thống sẽ cần bổ sung persistent outbound fencing token trong DB ở phiên bản sau.

#### (6) Xử lý Webhook Trùng lặp (Duplicate) và Webhook Đến Không Đúng Thứ tự (Out-of-Order)
1. **Webhook Trùng lặp (`Duplicate Webhook` cùng `external_mid`)**:
   - Chặn ngay tại `InboundSessionPersistenceService.evaluateEntryAndPersistCustomer`:
     - Tầng 1: `WEBHOOK_MID_DEDUP_CACHE` (in-JVM fast check).
     - Tầng 2 (Quyết định): Ràng buộc `UNIQUE (session_id, external_mid)` (`V32__p01_message_external_mid_unique_index.sql`) trong PostgreSQL (`INSERT ... ON CONFLICT DO NOTHING`). Nếu trùng `external_mid`, trả về `EntryAction.SUPPRESS_DUPLICATE_INBOUND` và dừng ngay lập tức (0 lần gọi LLM, 0 tin nhắn gửi lặp).
2. **Webhook Đến Không Đúng Thứ tự / Dồn dập (`Out-of-Order / Burst Webhooks` trên Single-Instance)**:
   - **Cửa sổ gom tin (`Burst Debounce`)**: `FanpageBotController` gom các tin nhắn cách nhau `<= burstDebounceMs` thành một lượt xử lý duy nhất.
   - **Cơ chế Epoch (`SenderEpochRegistry`)**: Nếu tin nhắn mới $M_2$ đến khi tin nhắn cũ $M_1$ đang chờ LLM, epoch của `(pageId, senderId)` tăng lên. $M_1$ kiểm tra epoch tại 3 điểm (`BEFORE_LLM`, `AFTER_LLM_BEFORE_GUARD`, `BEFORE_MEMORY_COMMIT`) và tại `OutboundDeliveryDispatcher` để tự hủy (`SUPERSEDED_ABORTED` / `SUPPRESSED_BY_FENCE`) trước khi gửi tin.
   - **Cơ chế Timestamp / Sequence Guard**: Nếu Meta gửi gói tin có `webhookTimestamp < session.lastInboundTimestamp` và nội dung mâu thuẫn với sự thật đã chốt ở lượt mới hơn, `compareVersionedFactWinner` (dựa trên `turnSeq` và `observedAt`) giữ nguyên sự thật của lượt mới hơn.

#### (7) Làm rõ Delivery Ledger, Transactional Outbox và Tính Nguyên tử Thực tế Giữa Memory, Booking State và Outbound Delivery
Vì không tồn tại giao thức Two-Phase Commit (2PC) xuyên suốt PostgreSQL và máy chủ Facebook Graph API, hệ thống áp dụng mẫu **Transactional Outbox kết hợp Sổ cái Trạng thái Gửi tin (`SentBubbleLedger`)**:

1. **Ranh giới Nguyên tử trong `Tx-Commit`**:
   - Trong cùng giao dịch `Tx-Commit`, `ConversationMemoryStore` lưu `CustomerIntelligenceLedger` + `BookingProgressState` + `applied_booking_patch_ids` vào `conversation_sessions`, đồng thời ghi các bản ghi bong bóng đầu ra vào `sent_bubble_ledger_entries` ở trạng thái **`PENDING`** kèm `outboundIdempotencyKey = "ob_" + SHA256_HEX_32(sessionId + ":" + turnId + ":" + bubbleIndex)`.
   - **Tuyệt đối KHÔNG ghi `DELIVERED` hay `ACCEPTED_BY_CHANNEL` trong `Tx-Commit` trước khi kênh thực sự nhận request thành công.**
2. **Bảng Phân biệt Các Trạng thái của Bản ghi Outbound (`DeliveryStatus`)**:
   - **`PENDING`**: Bản ghi đã được ghi bền vững trong `Tx-Commit`, đang chờ `OutboundDeliveryDispatcher` kiểm tra quyền gửi và phát tin.
   - **`SUPPRESSED_BY_FENCE`**: Trước khi gửi, `verifyOutboundStillPermittedFresh` phát hiện phiên đã chuyển sang `HUMAN_TAKEOVER` / `OPT_OUT` hoặc `SenderEpochRegistry` báo lệch epoch -> hủy gửi ra mạng; các `FACT` khách cung cấp trong `Tx-Commit` vẫn được bảo toàn cho nhân viên xem, nhưng bong bóng này không được tính là đã gửi cho khách.
   - **`ACCEPTED_BY_CHANNEL`**: Facebook Graph API thực sự nhận request thành công (trả về `HTTP 200` kèm `message_id`), hoặc đối với kênh Simulator đồng bộ là payload đã được ghi nhận vào phản hồi kênh thành công.
   - **`DELIVERED`**: **Chỉ** được cập nhật khi nhận được sự kiện webhook `message_deliveries` (delivery receipt thực tế từ Meta kèm `watermark` / `mids`) xác nhận tin nhắn đã hiển thị/tới thiết bị của khách hàng.
   - **`FAILED`**: Request gửi tới Facebook Graph API thất bại xác định (ví dụ `ConnectTimeoutException` khi chưa mở được kết nối, hoặc HTTP `4xx/5xx` xác nhận Meta từ chối xử lý).
   - **`UNKNOWN`**: Gặp `SocketTimeoutException` / `ReadTimeoutException` hoặc đứt kết nối mạng **sau khi HTTP request đã được truyền đi**, khiến hệ thống không thể xác định chắc chắn Facebook Graph API đã nhận và phát bong bóng đó cho khách hay chưa.
3. **Cơ chế Retry, Idempotency và Xử lý Timeout (`UNKNOWN`)**:
   - **Idempotency**: Mỗi bong bóng được định danh duy nhất bởi `outboundIdempotencyKey`. Dispatcher chỉ lấy các bản ghi đang ở trạng thái `PENDING` để phát tuần tự theo `bubbleIndex`.
   - **Retry có điều kiện khi lỗi xác định**: Nếu gặp lỗi trước khi kênh xử lý (`ConnectTimeoutException` hoặc HTTP `429 / 502 / 503 / 504`), Dispatcher kiểm tra lại `verifyOutboundStillPermittedFresh` + `epochToken.isCurrent()` và thử gửi lại tối đa **1 lần** (backoff `300ms`). Nếu vẫn thất bại, đánh dấu bản ghi là **`FAILED`** và **dừng gửi các bong bóng tiếp theo (`bubbleIndex > k`)** trong cùng lượt để không làm cụt đầu câu trả lời của khách.
   - **Xử lý khi Timeout không xác định kết quả (`UNKNOWN`)**: Vì Facebook Send API không hỗ trợ idempotency key cho tin nhắn văn bản thông thường, nếu retry mù quáng khi gặp `ReadTimeoutException` sẽ có nguy cơ gửi lặp 2 bong bóng giống hệt nhau. Do đó, Dispatcher chuyển bản ghi sang **`UNKNOWN`**, không tự động retry mù quáng, và dừng các bong bóng kế tiếp trong lượt. Nếu sau đó nhận được webhook `message_echoes` hoặc `message_deliveries` từ Meta khớp với bong bóng đó, trạng thái sẽ được đối chiếu nâng lên `ACCEPTED_BY_CHANNEL` / `DELIVERED`. Ở lượt kế tiếp, `SentBubbleLedger.reconcile` chỉ coi các bong bóng `ACCEPTED_BY_CHANNEL` hoặc `DELIVERED` là thông tin khách chắc chắn đã nhận; với bong bóng `UNKNOWN`, hệ thống gắn cờ không chắc chắn để bot không mặc định khách đã đọc câu hỏi trong bong bóng đó.

---

## 3. Mô hình Trí tuệ Khách hàng (`R3 — Customer Intelligence Model`: `FACT` vs `HYPOTHESIS` vs `UNKNOWN`)

### 3.1. Ba Phân loại Nhận thức (`Epistemic Classification`)
Không tạo thêm hàng loạt class rời rạc; toàn bộ mô hình được chuẩn hóa ngay trong schema của `AciCustomerBrainMemorySnapshot` (`ConfirmedFactLedger`):

| Phân loại (`EpistemicStatus`) | Định nghĩa Nghiệp vụ | Điều kiện Hình thành | Quyền Sử dụng trong Tư vấn |
|---|---|---|---|
| **`FACT`** | Thông tin đã được khách hàng trực tiếp nói ra hoặc xác nhận rõ ràng. | Phải có `sourceType` là `CUSTOMER_EXPLICIT` hoặc `CUSTOMER_CONFIRMED`, kèm `evidenceQuote` trích từ lời khách và `sourceTurnId` (`confidence >= 0.85`). | Được dùng làm nền tảng khẳng định trong lời tư vấn, lọc gói dịch vụ (`CatalogIntelligenceService`) và điền vào `BookingProgressState`. |
| **`HYPOTHESIS`** | Giả thuyết do bot/LLM suy luận từ tín hiệu gián tiếp nhưng **chưa được khách xác nhận**. | `sourceType = BOT_INFERENCE`, `confidence` trong khoảng `[0.30, 0.79]`, kèm lý do suy luận (`rationale`). | Chỉ được dùng để **đặt câu hỏi thăm dò mở** (ví dụ: *"Không biết mình đang tìm gói thư giãn nhẹ nhàng hay trị liệu chuyên sâu vùng mỏi ạ?"*). **CẤM** dùng `HYPOTHESIS` để khẳng định khách bị bệnh/đau vùng đó hoặc tự khóa gói dịch vụ. |
| **`UNKNOWN`** | Thuộc tính cần cho tư vấn/đặt lịch nhưng chưa có bằng chứng đủ tin cậy (`confidence < 0.30`) hoặc khách trả lời lấp lửng (`"chưa biết nữa"`). | Trạng thái mặc định của mọi slot khi bắt đầu phiên hoặc sau khi bị hạ bậc `UNCERTAIN`. | Đưa vào danh sách thông tin cần làm rõ (`clarificationCandidates` hoặc `bookingMissingFields`) theo đúng thứ tự ưu tiên. |

### 3.2. Schema Chi tiết cho Mỗi Mục Thông tin (`CustomerIntelligenceEntry`)

```json
{
  "key": "pain_region.neck_shoulder",
  "value": "cổ vai gáy",
  "epistemic_status": "FACT | HYPOTHESIS | UNKNOWN",
  "lifecycle_state": "CURRENT | SUPERSEDED | RETRACTED | UNCERTAIN",
  "source_type": "CUSTOMER_EXPLICIT | CUSTOMER_CONFIRMED | BOT_INFERENCE | SYSTEM_AUTHORITATIVE",
  "evidence_quote": "chị bị đau mỏi cổ vai gáy cả tuần nay rồi",
  "source_turn_id": "T1",
  "updated_turn_id": "T1",
  "last_confirmed_turn_id": "T1",
  "turn_seq": 1,
  "confidence": 0.95,
  "created_at": "2026-11-04T04:00:00Z",
  "updated_at": "2026-11-04T04:00:00Z"
}
```

### 3.3. Năm Trạng thái Vòng đời (`Lifecycle Operations: ADD, UPDATE, SUPERSEDE, RETRACT, UNCERTAIN`)

```mermaid
stateDiagram-v2
    [*] --> UNKNOWN
    UNKNOWN --> HYPOTHESIS : ADD (BOT_INFERENCE)
    UNKNOWN --> FACT : ADD (CUSTOMER_EXPLICIT)
    HYPOTHESIS --> FACT : UPDATE (Chỉ khi có CUSTOMER_EXPLICIT / CUSTOMER_CONFIRMED ở lượt khách mới)
    HYPOTHESIS --> RETRACTED : RETRACT (Khách bác bỏ giả định)
    HYPOTHESIS --> UNCERTAIN : UNCERTAIN (Khách trả lời lấp lửng / hết hiệu lực sau 2 lượt không xác nhận)
    FACT --> FACT : UPDATE (Làm rõ chi tiết / làm mới lastConfirmedTurnId)
    FACT --> SUPERSEDED : SUPERSEDE (Khách đổi ý: đổi giờ hẹn, đổi gói dịch vụ)
    FACT --> RETRACTED : RETRACT (Khách phủ nhận / đính chính: "mình không hỏi cổ vai gáy")
    FACT --> UNCERTAIN : UNCERTAIN (Khách báo chưa chắc chắn: "chưa biết chiều mai có rảnh không")
```

### 3.4. Hai Luật Bất Khả Xâm Phạm của Mô hình Trí tuệ Khách hàng (`R3 Invariants`)

1. **Luật Chống Tự Xác Nhận Giả Thuyết (`No Self-Confirming Hypothesis Rule`)**:
   - Một `HYPOTHESIS` (`sourceType == BOT_INFERENCE`) **KHÔNG BAO GIỜ** được tự động nâng cấp thành `FACT` chỉ vì bot đã nhắc đến nó trong bong bóng trả lời ở lượt trước hoặc vì khách chỉ nói `"Ừ"`, `"Giá sao?"` mà không xác nhận trực tiếp giả thuyết đó.
   - Hàm kiểm tra chuyển trạng thái trong `ConversationMemoryStore` bắt buộc kiểm tra:
     `canPromoteToFact(entry, incomingDelta)` chỉ trả về `true` khi `incomingDelta.sourceType() in {CUSTOMER_EXPLICIT, CUSTOMER_CONFIRMED}` VÀ `incomingDelta.evidenceQuote()` nằm trong phát ngôn thực tế của khách (`CustomerStatementRecord`).
   - Nếu một `HYPOTHESIS` tồn tại quá 2 lượt mà không được khách xác nhận, nó tự động hạ về `UNCERTAIN` / hết hiệu lực dẫn dắt câu hỏi.

2. **Luật Chống Hồi Sinh Thông Tin Đã Bác Bỏ (`Anti-Resurrection of Retracted Facts Rule`)**:
   - Khi khách sửa hoặc phủ nhận một thông tin (ví dụ: `"Mình không bị đau cổ vai gáy"`, `"Chị không muốn gói có gội đầu"`), mục đó chuyển sang `lifecycle_state = RETRACTED` và được ghi vào tập khóa cấm `retractedOrForbiddenScopes` kèm `retractedTurnSeq`.
   - Trong `AciCustomerBrainMemorySnapshot.compareVersionedFactWinner`, trạng thái `RETRACTED` / `NEGATED` có độ ưu tiên cao nhất (`rank = 4` vs `CURRENT rank = 1`).
   - Mọi kết quả truy xuất RAG (`KnowledgeRetrievalService`), xếp hạng gói (`CatalogIntelligenceService`) hoặc đề xuất từ LLM ở các lượt `turnSeq <= retractedTurnSeq` (hoặc ở các lượt sau nhưng không có phát ngôn `CUSTOMER_EXPLICIT` đảo ngược rõ ràng từ chính khách hàng) đều bị chặn bởi bộ lọc `RetractedFactFilter`: thông tin cũ **tuyệt đối không thể hồi sinh** trong bộ nhớ hay trong lời tư vấn.
