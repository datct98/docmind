# AGY DIRECTIVE — SBR-01 WAVE 1 (CP1 → CP2 → CP3)

**Branch:** `refactor/aci-v2-cleanup`
**Baseline HEAD:** `e2451e00518e0cf516e912a46b67db5c85608bfa`
**Scope:** chỉ page Nàng Sen (`2850a8ef-ca4a-40eb-87fa-4f808640efee`). Các page khác chỉ là page test, bỏ qua, không sửa.
**Implementer:** AGY · **Reviewer độc lập:** Qwen · **Approver:** Owner

Mục tiêu của đợt: bot trả lời đúng điều khách hỏi, thoát được trạng thái recovery, biết nhận ra cơ hội đặt lịch mà không ép. Chưa phải đợt làm giọng văn (Batch 3).

---

## 0. Quy tắc chung (áp dụng cho cả đợt)

1. Xác nhận HEAD đúng baseline và working tree (tracked) sạch. Giữ nguyên file untracked của Owner trong `docs/analysis/`.
2. **Một thay đổi hành vi = một commit.** Mỗi thay đổi hành vi có flag cấu hình (property hoặc `persona_config`) để tắt mà không cần deploy lại.
3. Không sửa evaluator để che regression. Sửa evaluator chỉ khi có bằng chứng evaluator sai, và phải ghi riêng một commit.
4. Qwen và DeepSeek đều là model chính. Yêu cầu với harness:
   - Trước mỗi lần chạy replay, gọi 1 request inference thật tới model cấu hình và ghi HTTP status, model yêu cầu, model thực tế trả lời.
   - Telemetry từng lượt ghi tên model thực tế đã trả lời.
   - Lần chạy bị FAIL (exit code khác 0) nếu có lỗi 4xx/5xx từ provider hoặc output không phải JSON.
5. **Không thêm luật "CẤM" mới vào prompt** nếu có thể giải quyết bằng cách bỏ luật cũ hoặc sửa code. Prompt đổi phiên bản phải đổi `prompt_version`, đồng bộ bản nhúng trong `CleanAgentCatalogAndPromptBuilder`.
6. Không viết regex riêng cho một câu khách cụ thể (ví dụ T15). Dùng nhóm intent tổng quát.
7. Artifact replay thô (telemetry, prompt từng lượt) ghi vào thư mục gitignore, ví dụ `benchmark/sbr01/out/`. Chỉ commit báo cáo tóm tắt.
8. Không làm trong đợt này: đổi temperature hoặc thêm penalty, tách planner/writer hai lần gọi, đổi embedding, đổi timeout, rút gọn prompt theo ngưỡng cố định, booking API thật, few-shot từ hội thoại khách.

Dừng sau CP3 và chờ Owner nghiệm thu.

---

## CP1 — Trả nợ bằng chứng Batch 1A

**1.1 Preflight và ghi model** — theo quy tắc 0.4. Commit riêng.

**1.2 Rescore FIX-0 tại checkout sạch**
- Dùng `git worktree` sạch tại commit `05012cb`. Không dùng worktree đang chỉnh sửa.
- Ghi: HEAD SHA, `git status`, hash file evaluator, số lượt PASS/FAIL/UNVERIFIABLE theo từng trục, danh sách từng lượt vi phạm.
- Chỉ giữ **một** bộ số. Sửa lại bảng số FIX-0 trong evidence package cho khớp.

**1.3 Truy nguồn 11–13 lượt giảm độ liên quan sau FIX-1**
- Với mỗi lượt, dùng mutation trace để xác định mất nội dung do: `enforceGroundedApologyAgainstHistoryInPlace`, `stripUnsolicitedPackagePitchOnNegativeReactionInPlace`, cơ chế Java khác, hay raw LLM output đã thiếu nội dung.
- Không khôi phục template Java. Nếu lỗi do Java cắt nội dung, ghi lại để xử lý ở CP2.
- Thêm chỉ số chẩn đoán: số lượt câu trả lời cuối chỉ còn lời xin lỗi.

**1.4 Chạy lại FIX-4 bằng model chính hoạt động**
- CASE-01 × 3, và 20 case × 1, tại HEAD hiện tại.
- Bảng so sánh với baseline cho T1, T3, T5, T7, T9, T11, T13, T15.

**1.5 Ranh giới y tế (mặc định đề xuất, Owner có thể sửa trước khi chạy)**
- Được nói: dịch vụ giúp thư giãn vùng đang mỏi; khuyên đi kiểm tra sức khỏe nếu hoa mắt, ù tai kéo dài hoặc đột ngột.
- Không được nói: dịch vụ chữa, giảm, hoặc "phù hợp với" ù tai, hoa mắt, chóng mặt, đau đầu.
- Tên gói trong Catalog giữ nguyên. Bot không tự dùng từ "trị liệu" khi nói về triệu chứng.
- Evaluator và prompt phải dùng cùng định nghĩa này.

**Gate CP1:** `jsonValidTurns` = 100%, không có lỗi provider, một bộ số FIX-0 duy nhất.

---

## CP2 — FIX-5: Service Recovery và Mixed Intent

Căn cứ: `FIX5_SERVICE_RECOVERY_STATE_DESIGN.md`. Bốn điểm nghẽn: `detectCanonicalTurnIntents` (return sớm), chỉ thị `"0 báo giá"` trong `<TURN_CONTEXT>`, `reconcileWithCurrentUtterance` dùng `secondaryIntents` của LLM, `stripUnsolicitedPackagePitchOnNegativeReactionInPlace`.

Nguyên tắc sửa:
1. **Intent của tin nhắn hiện tại quyết định hành động chính.** `secondaryIntents` vẫn giữ làm ngữ cảnh cảm xúc, nhưng không được dùng một mình để ghi đè lượt hiện tại sang `FRICTION_RECOVERY`.
2. **Recovery theo sự kiện, không dùng TTL cố định.**
   - Khách tiếp tục phàn nàn → giữ recovery.
   - Khách chuyển sang câu hỏi nghiệp vụ → recovery không được chặn việc trả lời.
   - Phàn nàn kèm câu hỏi → xin lỗi một câu và trả lời câu hỏi trong cùng lượt.
   - Không lặp lại lời xin lỗi hay nội dung recovery khi khách không phàn nàn tiếp.
3. **Sanitizer theo loại intent:**
   - Chỉ phàn nàn (ví dụ "Đừng ép chốt nữa", "Hỏi lại lắm thế") → chặn cả giới thiệu gói tự ý lẫn CTA.
   - Phàn nàn kèm yêu cầu cụ thể (so sánh, hỏi gói khác, hỏi phạm vi, hỏi giá) → giữ nội dung trả lời, chỉ cắt CTA đặt lịch không được yêu cầu.
4. Chỉ thị `"0 báo giá"` chỉ áp dụng cho lượt phàn nàn thuần túy.

Test bắt buộc: 6 test trong tài liệu thiết kế, cộng thêm
- 2 cách nói khác cho cùng một ý phàn nàn (ví dụ "sao không nói hết từ đầu", "giới thiệu thiếu quá") để chứng minh không fit riêng T15;
- `LIVE_GENERATED` cho toàn bộ CASE-01 (sửa README cho đúng tên tham số `LIVE_GENERATED`).

Phải giữ pass: `P38ForensicRegressionTest`, `AciV3GateAcceptanceTest` (đặc biệt `tc3_zeroUnsolicitedPitchOnNegativeReactionWhenNoExplicitPriceDemand`).

**Gate CP2:** T9, T11, T13, T15 trả lời đúng câu hỏi; T7 không giới thiệu gói; không còn câu trả lời chỉ gồm lời xin lỗi khi khách có câu hỏi.

---

## CP3 — Batch 2: Bán hàng và chuyển đổi (Spa)

**3.1 Vòng đời `conversation_goal`** (`AgentBrainService` quanh dòng 2034)
- Điều tra và ghi lại vì sao goal bị giữ sau khi khách đã chuyển ý.
- Quy tắc mới: trạng thái cảm xúc hoặc phàn nàn không được thành mục tiêu dài hạn. Goal chỉ cập nhật khi có thông tin mới về nhu cầu (triệu chứng, phạm vi, thời gian, chi nhánh) hoặc khách đổi ý rõ ràng.

**3.2 Booking 3 cấp** (`resolveAciTurnActivatedSkills`, quanh dòng 6659–6680)
- Cấp 1 *khám phá lịch*: tìm hiểu mong muốn về lịch.
- Cấp 2 *chuẩn bị*: gợi ý cơ sở, ngày, khung giờ khi khách thể hiện ý định.
- Cấp 3 *thực hiện*: xác nhận đặt lịch, chỉ khi khách yêu cầu rõ.
- Cấp 1–2 được kích hoạt trước `READY_TO_BOOK` **chỉ khi có tín hiệu**: khách hỏi về lịch, cơ sở, giờ; đồng ý thử gói; hỏi cách đặt. Không kích hoạt theo số lượt hội thoại.
- Test: replay các tình huống khách đồng ý thử gói, muốn đặt lịch, chọn ngày giờ; ghi `bookingReadiness`, `activatedSkillCodes`, skill bị loại và lý do.

**3.3 `good_example`**
- Commit riêng 1: sửa ví dụ "đi tập nặng về" trong `discovery_empathy_and_scope` bằng ví dụ trung tính (đây là sai ngữ cảnh rõ ràng).
- Commit riêng 2: thêm flag tắt việc đưa `good_example` vào prompt.
- A/B: cùng bộ replay, cấu hình A (hiện tại) và B (không có `good_example`), CASE-01 và các lượt rủi ro × 3. Báo cáo mức lặp khung câu và độ liên quan. Owner quyết định giữ cấu hình nào.

**3.4 Skill audit cho Nàng Sen** (migration Flyway mới, V43 trở đi)
- Lập bảng 6 skill hiện có: trigger, lỗi liên quan, quyết định giữ, sửa hay xóa.
- Thêm tối đa 4 skill nhỏ: giải thích và so sánh gói; làm rõ danh mục gói; thoát recovery và quay lại tư vấn; gợi ý bước tiếp theo nhẹ nhàng theo tín hiệu khách.
- Skill viết dạng nguyên tắc ngắn, không nhúng câu trả lời mẫu hoàn chỉnh để model chép.
- Không khôi phục bộ skill V16. Không đụng tới page khác.

**3.5 Chỉ số Sales Progression (chẩn đoán, không phải hard gate)**
- Có lời mời hoặc bước tiếp theo ở các lượt có cơ hội đặt lịch; khách phải nhắc lại yêu cầu; số câu hỏi mỗi lượt; tiến triển `bookingReadiness`.
- Mẫu số là các lượt có cơ hội đặt lịch, không phải tổng số lượt.

**3.6 Messenger simulator**
- AGY cung cấp kịch bản chat theo session `6ccb68f3` để Owner chat khoảng 10 phút sau CP3.

**Gate CP3:** Safety về giá không giảm; không có lỗi y tế mới; booking không xuất hiện khi không có tín hiệu; kết quả A/B có số liệu.

---

## Báo cáo

Sau mỗi commit (ngắn gọn):
`FIX ID · Parent SHA · Commit SHA · Files changed · Tests + exit code · Trước · Sau · Regression · Status`

Sau mỗi checkpoint, Qwen kiểm tra trực tiếp diff, source và artifact (không chỉ đọc báo cáo của AGY), trả PASS / FAIL / INCONCLUSIVE **riêng cho từng hạng mục**. Hạng mục FAIL được sửa bằng commit bổ sung, không chạy lại toàn bộ.

AGY không tự tuyên bố nghiệm thu. Owner quyết định kết quả cuối.
