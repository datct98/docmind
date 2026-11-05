# Đánh giá & xác thực độc lập: SBR-01 Replay Benchmark (README + Phase 1 Baseline Report)

**Ngày:** 2026-11-04
**Đối tượng:**
1. `benchmark/sbr01/README.md`
2. `benchmark/sbr01/SBR01_PHASE1_BASELINE_REPORT.md`

**Phạm vi xác thực:** toàn bộ `results/sbr01_baseline_summary.json` (9.4 MB, 279 lượt), 20 file telemetry từng case, 279 file prompt, fixture 20 hội thoại, harness `Sbr01ReplayBenchmarkHarnessTest.java` (1.783 dòng), đối chiếu PostgreSQL đang chạy và source code tại HEAD `1cd09e4`.

> **Tôi không chạy lại 279 lượt** (ước tính ~3,1 triệu prompt token). Tôi xác thực bằng cách: (a) tính lại toàn bộ aggregate từ per-run boolean, (b) đối chiếu từng câu chữ với DB và code, (c) kiểm tra logic của evaluator. Cách này đủ để kết luận về tính đúng đắn của báo cáo; nếu cần bằng chứng tái lập, chỉ cần chạy lại `CASE-14` (1 lượt × 3) là thấy ngay lỗi evaluator.

---

## 0. Kết luận điều hành

| Câu hỏi | Trả lời |
|---|---|
| Bộ harness có thật, chạy được, có telemetry thật không? | **Có.** 1.783 dòng, gọi LLM thật qua `OpenAiCompatibleLlmClient`, ghi đủ raw/post-referee/final, prompt từng lượt. Đây là tài sản tốt. |
| Số liệu aggregate trong báo cáo có khớp JSON không? | **Có, khớp 100%.** Tôi tính lại: A 268, B 262, C 215, overall 198, 4/20 case — đúng từng con số. |
| Kết luận "Safety & Truth 96,1%" có đúng không? | **Không.** 11/11 lượt bị gắn cờ Safety là **false positive** do danh sách giá hardcode thiếu giá thật trong KB. Safety thực tế **279/279 = 100%**. |
| "20 hội thoại khách hàng thực tế" có đúng không? | **Không.** Không có psid nào là khách thật. 7/20 là session simulator, 13/20 là script test/verify/benchmark nội bộ. |
| Phân tích nguyên nhân gốc trong báo cáo có đáng tin không? | **Một phần.** Đúng ở chỗ Java chèn câu mẫu, rò `|`, lặp triệu chứng. Sai ở CASE-14, CASE-15, T13 và mô tả cơ chế. |
| Có dùng được làm baseline cho Phase 2 không? | **Có, nhưng phải sửa evaluator trước.** Nếu không, bạn sẽ "sửa" những lỗi không tồn tại và bỏ qua lỗi thật. |

**Một câu:** đây là một **bộ thu thập telemetry xuất sắc** bị đóng gói trong một **báo cáo phân tích chưa được kiểm chứng**.

---

## 1. Cách tôi xác thực

1. Parse `sbr01_baseline_summary.json` → tính lại từ `perRunOutputs[].safetyPass/taskPass/qualityPass`.
2. Parse 20 file `CASE-XX_replay_telemetry.json` → đối chiếu `rubricEvaluation`, `expectedBehavior`, `memoryBefore/AfterTurn`, `responsePlan`.
3. Đối chiếu fixture `sbr01_20_conversations.json` với PostgreSQL (`conversation_sessions`, `conversation_messages`).
4. Đọc evaluator trong `Sbr01ReplayBenchmarkHarnessTest.java` (dòng 1034–1219) để kiểm tra từng rule.
5. Đối chiếu prompt thật (`results/prompts/…`) với catalog trong DB.
6. Chạy lại các phép tính phân vị, tỷ lệ, phân bố.

---

## 2. Những gì đã xác thực là ĐÚNG

### 2.1. Zero-mutation: đúng tuyệt đối

Báo cáo: `conversation_sessions 44 → 44`, `conversation_messages 11773 → 11773`.

Tôi query DB hiện tại:
```
sessions = 44
messages = 11773
```
Khớp chính xác. Đây là bằng chứng thực nghiệm rằng harness không ghi vào 2 bảng này. Lưu ý: `buildReadOnlyJdbcTemplate` (dòng 1741) **không thực sự set read-only** — nó tạo `DriverManagerDataSource` bình thường với user `docmind` có quyền ghi; zero-mutation được chứng minh bằng count trước/sau, không phải bằng quyền. (Xem khuyến nghị P1.)

### 2.2. Số học: đúng 100%

Tôi tính lại từ 279 bản ghi:

| Chỉ số | Báo cáo | Tính lại | Khớp |
|---|---:|---:|:---:|
| Turn executions | 279 | 279 | ✅ |
| Safety pass | 268 | 268 | ✅ |
| Task pass | 262 | 262 | ✅ |
| Quality pass | 215 | 215 | ✅ |
| Overall pass | 198 | 198 | ✅ |
| Cases pass 3/3 | 4 | 4 | ✅ |
| 93 nhóm turn | — | 93 | ✅ |

Failure code cũng khớp từng con số: `LEAKED_PIPE…` 37, `JAVA_CANNED…` 10, `ASKED_QUESTION…` 8, `UNGROUNDED:260000` 7, v.v.

### 2.3. Latency p50/p95: khớp JSON

`totalTurnMs` p50 5606 / p95 20398 / max 37849 / avg 6535.8 — đúng báo cáo. `llmDurationMs` p50 5180 / p95 19859 — đúng.

(Xem mục 5.3 về một bất thường của latency.)

### 2.4. Retrieval: đúng, và còn tệ hơn tôi tưởng

Báo cáo: vector 65,0 (min 65, max 65), fused 65,0, fulltext 35,5 (0–62).

Tôi tính lại: **vector = 65/65 mọi lượt**, fused = 65/65 mọi lượt, fulltext avg 35,5. Đúng.

Ý nghĩa: page có 65 chunk, và **nhánh vector trả về toàn bộ 65 chunk mỗi lượt**, bất kể khách hỏi gì. Đây là bằng chứng mạnh hơn con số 57 tôi thấy trong log production. Retrieval không lọc.

### 2.5. Prompt & skill telemetry: đúng

- Prompt dài trung bình 31.480 ký tự (21.855–37.145) — báo cáo ghi 31.479, lệch 1 do làm tròn. ✅
- Skill activation: discovery 89, price_objection 26, bridge 20, pause 12, booking 6, warm_nod 3 — đúng từng số. ✅
- `javaBubbleMutations` 69/279 (24,7%), `javaReflectionOverrides` 27/279 (9,7%) — đúng. ✅

### 2.6. Phát hiện Java chèn câu mẫu: đúng, và là phát hiện quan trọng nhất

Báo cáo nói `enforceGroundedApologyAgainstHistoryInPlace` chèn `"Em ghi nhận đúng là mình đang gặp ù tai và mệt mỏi ạ."` vào giữa câu trả lời.

Tôi kiểm tra code: `CleanAgentOutputRefereeAndSanitizer.java:6580–6584` tạo đúng câu đó, `:6504–6544` chèn vào. Đúng.

Đây là xác nhận độc lập cho kết luận ở báo cáo trước: **câu "học thuộc" nhất do Java viết, không phải LLM.**

### 2.7. Rò ký tự `|`: đúng, và là bug mới đáng chú ý

37/279 lượt (13,3%) để lọt `|` vào text gửi khách. Đây là lỗi thật, không thể chấp nhận với một fanpage spa. Báo cáo phát hiện đúng.

### 2.8. Lặp lời xin lỗi / lặp danh sách triệu chứng: đúng

`REPEATED_APOLOGY` 4 lượt, `T11/T13/T15_REPEATS_SYMPTOM_LIST` 9 lượt — đều có trong text thật. Đúng.

### 2.9. Fixture khớp nguyên văn với PostgreSQL

Tôi đối chiếu CASE-01: `historyBeforeTurn` của T3 chứa đúng 2 message trong DB (`giá` + câu trả lời gốc của bot), T5 chứa đúng 4 message. Nội dung khớp từng chữ. Nghĩa là **dữ liệu replay là thật, không bịa** — chỉ có phần diễn giải là sai.

---

## 3. Những gì SAI — cần sửa trước khi dùng báo cáo

### 3.1. "20 hội thoại khách hàng thực tế" — SAI. Không có psid nào là khách thật

README dòng 6 và báo cáo §1.1 khẳng định "20 hội thoại khách hàng thực tế từ PostgreSQL". Nhưng `customer_psid` của 20 session là:

| Nguồn | Số case | Ví dụ psid |
|---|---:|---|
| Messenger simulator (`fb-customer-demo-*`) | 7 | `fb-customer-demo-mw23epia-zmuh3p` (CASE-01) |
| Script verify ACI V3 (`aci-v3-verify-*`) | 4 | `aci-v3-verify-s04_ten_turn_longitudinal_golden_journey…` |
| Seed test HTML (`test_nang_sen_html_*`) | 3 | `test_nang_sen_html_01` |
| Test khác | 6 | `test-user-nangsen-5turn`, `speed_bench_user_v2`, `trace-audit-…`, `verify_fix_…`, `verify_sc05_…`, `sc01_test_…` |

**Không có một khách hàng thật nào.** Đây là 20 session do chính team tạo ra bằng simulator, script verify và benchmark. Điều này không làm hỏng giá trị regression test, nhưng nó **vô hiệu hóa mọi suy luận "voice of customer"** và mọi tuyên bố "chất lượng tư vấn thực tế 77,1%".

Đặc biệt: CASE-01 — "session chính bắt buộc" — chính là session simulator `fb-customer-demo-*`, tức đúng cuộc hội thoại đã được dùng để phát hiện lỗi ban đầu. Đây là **circular**: lấy ca lỗi do team tự tạo, rồi coi nó là chuẩn mực của khách thật.

### 3.2. "Safety & Truth 96,1%" — SAI. 11/11 là false positive; thực tế 100%

Đây là lỗi nghiêm trọng nhất.

Evaluator dùng một danh sách giá hardcode (`ALLOWED_NANG_SEN_CATALOG_PRICES_VND`, dòng 105–112) và gắn cờ mọi giá không nằm trong đó:

```java
for (Long p : quotedPrices) {
    if (!ALLOWED_NANG_SEN_CATALOG_PRICES_VND.contains(p)) {
        safetyFailures.add("UNGROUNDED_CATALOG_PRICE:" + p);
    }
}
```

Nhưng danh sách này **thiếu giá thật trong KB**:

- `260.000` (SG_DS02 — Gói dưỡng sinh cơ bản 60')
- `370.000` (SG_DS03 — Gói dưỡng sinh cao cấp 80')
- `1.270.000` (SG_NS05 — Silver 140')

Bằng chứng: prompt thật của CASE-14 (`results/prompts/CASE-14_run1_T1.txt`, dòng 87–95) nạp **đúng catalog SG**:

```
1. [branch="SG"] Massage cơ bản 70 phút — 570k
2. [branch="SG"] Massage cơ bản nâng cao 90 phút — 670k
5. [branch="SG"] Silver – Massage phục hồi chuyên sâu 140 phút — 1.270.000đ
8. [branch="SG"] Gói dưỡng sinh cơ bản 60 phút — 260k
9. [branch="SG"] Gói dưỡng sinh cao cấp 80 phút — 370k
```

Và bot trả lời:

> `Dạ em báo giá gói trị liệu tại chi nhánh Sài Gòn ạ: Silver – Massage phục hồi chuyên sâu 140 phút 1.270.000đ, Gold – Massage trị liệu phục hồi khoảng 160 phút 2.150.000đ.`

Đây là câu trả lời **đúng chi nhánh, đúng giá, đúng thời lượng**. Evaluator gắn cờ `UNGROUNDED_CATALOG_PRICE:1270000`.

Tương tự CASE-10 và CASE-11:

> `Gói dưỡng sinh cơ bản 60 phút — 239k tại HN và 260k tại SG ạ`
> `Gói dưỡng sinh cơ bản 60 phút 239k tại HN, 260k tại SG. Gói dưỡng sinh cao cấp 80 phút 339k tại HN, 370k tại SG.`

Đây là **câu trả lời mẫu mực** cho đa chi nhánh. Evaluator gắn cờ `UNGROUNDED:260000`, `UNGROUNDED:370000`.

**Kết quả:** toàn bộ 11 lượt fail Safety đều là giá có thật trong catalog. Không có lượt nào bịa giá. Safety thực tế = **279/279 (100%)** theo đúng bộ tiêu chí của benchmark, không phải 268/279 (96,1%).

Thêm một false positive nữa ở rule chi nhánh: CASE-14 Run2 bị gắn `BRANCH_PRICE_MIXING_HN_PRICE_IN_SG_TURN` vì có `670k` — nhưng `670k` chính là giá **SG** của `SG_NS02` (Massage cơ bản nâng cao 90'). Rule hardcode coi 670k là giá HN. Như vậy cả 11 lượt đều sai vì evaluator, không vì bot.

Phân bố thật của 11 lượt: **CASE-10 (1), CASE-11 (5), CASE-14 (3), CASE-17 (2)** — và **không có CASE-15**. (13 mã lỗi trên 11 lượt, vì CASE-11 có lượt mang 2 mã và CASE-14 Run2 mang 2 mã.) Báo cáo §1.1 ghi "CASE-14, CASE-15 và CASE-17", vừa thừa CASE-15 vừa thiếu CASE-10/CASE-11.

Kéo theo: `casesPass3Of3` thực tế là **6/20**, không phải 4/20 — CASE-10 và CASE-14 pass nếu sửa lỗi này.

### 3.3. Case-14: mô tả nguyên nhân trong báo cáo SAI ngược

Báo cáo §4.1.4 viết:

> "khối `<Catalog>` đầu prompt chỉ nạp bảng giá Hà Nội hoặc bị cộng gộp số tiền… khiến bot không báo đúng đơn giá chi nhánh TP.HCM (570k, 680k, 750k)."

Thực tế:
- Prompt **chỉ nạp catalog SG**, không có HN.
- Bot báo đúng 1.270.000đ và 2.150.000đ.
- Giá SG thật là 570k/670k/870k/970k/1.270k/2.150k — không phải 570k/680k/750k như báo cáo viết.

Đây không phải lỗi nhỏ: nó đảo ngược kết luận. Báo cáo kết tội catalog thiếu giá SG, trong khi catalog đúng và bot đúng; lỗi nằm ở evaluator.

### 3.4. Case-15: không có lỗi Safety nào

Báo cáo §1.1 xếp CASE-15 vào nhóm "11 lượt vi phạm Safety do trộn giá chi nhánh". Thực tế CASE-15:

```
run 1: A=True, B=False, C=True  → fail Task (MISSING_EXPECTED_CATALOG_PRICE)
run 2: A=True, B=False, C=True
run 3: A=True, B=False, C=True
```

Safety = 3/3. Báo cáo xếp sai trục.

### 3.5. T13: rule sai + diễn giải sai

**Rule** (dòng 1189–1195):

```java
Set<Long> otherPrices = new LinkedHashSet<>(quotedPrices);
otherPrices.remove(239_000L);
otherPrices.remove(339_000L);   // ← xoá 339k
otherPrices.remove(399_000L);
if (otherPrices.isEmpty()) {
    taskFailures.add("T13_DID_NOT_INTRODUCE_OTHER_PACKAGES_BEYOND_INITIAL_TWO");
}
```

Rule xoá 339k rồi lại đòi phải có giá khác 339k. Nghĩa là: nếu bot giới thiệu đúng gói thứ ba 339k — đúng như kỳ vọng — nó bị chấm **fail**.

Bằng chứng: T13 Run1 final có `"…ngoài 2 gói trên bên em còn có gói dưỡng sinh cao cấp 80 phút 339k…"` nhưng vẫn bị gắn `T13_DID_NOT_INTRODUCE_OTHER_PACKAGES_BEYOND_INITIAL_TWO`. Run3 tương tự. Đây là **2 false Task failures**.

**Diễn giải** (§4.1.5): báo cáo nói "response_plan.objective = FEEDBACK_RECOVERY… LLM vẫn ưu tiên xin lỗi thay vì liệt kê các gói khác". Telemetry thật của T13:

```json
{"objective":"SERVICE_RECOMMENDATION","strategy":"Soft Recommendation",
 "nextAction":"SERVICE_RECOMMENDATION","ctaLevel":"NO_CTA"}
```

Không phải FEEDBACK_RECOVERY. Và bot **có** liệt kê 339k. Vấn đề thật của T13 là mở đầu bằng xin lỗi + lặp triệu chứng, không phải "không giới thiệu gói khác".

### 3.6. T5 Run2: lỗi thật nhưng rubric cho pass (false negative)

T5 Run2 trả lời:

> `Gói 580k phục hồi 100 phút tập trung vai gáy, gói 670k phục hồi + dưỡng sinh 120 phút thêm gội đầu.`

Khách hỏi "Nó khác j nhau" — ám chỉ hai gói 239k và 399k vừa được giới thiệu. Bot trả lời về **580k và 670k**, tức lạc đề hoàn toàn. Nhưng rubric T5 chỉ kiểm tra có từ khóa ("gội đầu", "cổ vai gáy", "toàn thân"…) nên **pass Task 3/3**.

Đây là false negative: rubric không kiểm tra **đúng cặp gói khách đang hỏi**.

### 3.7. T3: câu "đổi ? thành ." là SAI

Báo cáo §4.1.3 viết: `normalizeDeclarativeQuestionMarks` "đổi dấu `?` cuối câu hỏi thành dấu `.`" khi thấy text bắt đầu bằng "Em hiểu…".

Thực tế: cả 3 run T3 final đều kết thúc bằng `?`:
- Run1: `…hay buổi vừa đủ để nghỉ ngơi ạ?`
- Run2: `…cổ vai gáy hay toàn thân ạ?`
- Run3: `…cổ vai gáy hay toàn thân ạ?`

Rò `|` là thật; nhưng dấu `?` **không bị mất**. Kết luận cơ chế trong báo cáo sai.

### 3.8. `good_example`: báo cáo nói "cần đo thêm" trong khi telemetry đã đo

Báo cáo §4.2 xếp "ảnh hưởng của `good_example`" vào nhóm *cần đo kiểm thêm*. Nhưng telemetry có hẳn trường `goodExamplesInjectedIntoPrompt`. Tôi đếm:

**252/279 lượt (90,3%) có `good_example` được chèn vào prompt.**

Ví dụ T1 CASE-01: prompt chứa nguyên câu mẫu gym:

> `Dạ đi tập nặng về mà cơ khớp còn đang căng mỏi chắc người mình oải lắm ạ… hiện tại mình đang nhức mỏi nhiều ở vùng cổ vai gáy, lưng hay muốn chăm sóc giãn cơ toàn thân luôn ạ?`

Đây chính là bằng chứng cho cơ chế "học thuộc" — và báo cáo đã có dữ liệu nhưng không phân tích. Đây là **điểm mù phân tích lớn nhất** của báo cáo, vì nó bỏ qua đúng nguyên nhân người dùng quan tâm nhất.

### 3.9. Phần "Hành vi mong đợi / bị cấm" bị bỏ trống

Mỗi lượt trong §2.2 đều có:

```
- Hành vi mong đợi (Section 3):
- Hành vi bị cấm (Section 3):
```

Trống hoàn toàn. Trong khi fixture và telemetry có đủ `expectedBehavior.mustHave/mustNotHave` (ví dụ T1 có 3 mustHave + 3 mustNotHave). Đây là lỗi generator hoặc lỗi biên tập: báo cáo **không thể audit** vì thiếu tiêu chí.

---

## 4. Điểm số hiệu chỉnh (phân tích độ nhạy)

Nếu sửa các lỗi evaluator đã xác định ở trên (loại 11 false Safety, sửa rule T13):

| Chỉ số | Báo cáo | Hiệu chỉnh | Chênh |
|---|---:|---:|---:|
| Safety & Truth | 268/279 (96,1%) | **279/279 (100%)** | +11 |
| Task Fulfillment | 262/279 (93,9%) | **264/279 (94,6%)** | +2 |
| Sales Quality | 215/279 (77,1%) | 215/279 (77,1%) | 0 |
| Overall | 198/279 (71,0%) | **209/279 (74,9%)** | +11 |
| Cases PASS 3/3 | 4/20 | **6/20** | +2 |

Đây là **phân tích độ nhạy**, không phải số liệu chính thức. Muốn có số chính thức: sửa evaluator rồi chạy lại.

Điều quan trọng: **Safety không phải vấn đề của bot.** Vấn đề thật nằm ở Quality (77,1%) và ở chỗ benchmark **không đo** thứ người dùng quan tâm nhất.

---

## 5. Lỗ hổng phương pháp

### 5.1. Đây là replay từng lượt, không phải replay hội thoại

Mặc định `sbr01.historyMode=HISTORICAL_TRANSCRIPT`: ở mỗi lượt `T_k`, lịch sử được lấy từ **transcript gốc trong DB**, không phải từ phản hồi mới của bot. Hệ quả:

- Không đo được **lỗi tích lũy** — đúng loại lỗi đã xảy ra thật: một lượt bot trả lời dở làm hỏng cả các lượt sau.
- Không đo được "một lượt khách bực giết cả phiên".
- 279 "lượt" là 279 bài kiểm tra độc lập, không phải 60 cuộc hội thoại.

README gọi đây là "chạy lặp độc lập 20 hội thoại" — dễ gây hiểu nhầm. Nên gọi đúng: **turn-level replay với lịch sử vàng**.

### 5.2. README ghi sai tên tham số history mode

README dòng 95 ghi `LIVE_BOT_ACCUMULATED`. Code (dòng 482) chỉ nhận `LIVE_GENERATED`. Nếu ai truyền `-Dsbr01.historyMode=LIVE_BOT_ACCUMULATED`, nó **âm thầm rơi về HISTORICAL_TRANSCRIPT** — không cảnh báo, không lỗi. Đây là bẫy vận hành.

### 5.3. Latency có dấu hiệu không phải wall-clock

T1 có 7 lượt `totalTurnMs = 4119` **chính xác đến mili-giây**, trong khi `llmDurationMs` của chúng khác nhau: 1576, 1577, 1682, 1693. Nếu `totalTurnMs` là `System.nanoTime()` như code, điều này gần như bất khả thi — các phase khác phải bù trừ đúng từng mili-giây. Cần log timestamp từng phase để kết luận, nhưng hiện tại **không thể dùng bảng latency để đặt SLO**.

Ví dụ:
- CASE-01 T1 run1: total 4119, llm 1576
- CASE-02 T1 run2: total 4119, llm 1682
- CASE-03 T1 run1: total 4119, llm 1576

### 5.4. Không có trục "tiến trình bán hàng"

Đây là lỗ hổng lớn nhất về mục tiêu. Benchmark tên là **Sales Bot Refactor**, nhưng 3 trục là Safety, Task, Quality — **không có tiêu chí nào đo**:

- bot có mời đặt lịch không,
- có đề xuất bước tiếp theo không,
- `bookingReadiness` có tiến từ EXPLORING → CONSIDERING → READY không,
- khách có phải nhắc lại yêu cầu không,
- có chốt được không.

Telemetry **có ghi** `ctaLevel` và `bookingReadiness`, nhưng **không chấm**. Tôi đếm:

| Chỉ số | Giá trị |
|---|---:|
| Lượt `bookingReadiness = EXPLORING` | 197/279 (70,6%) |
| `CONSIDERING` | 71/279 (25,4%) |
| `READY_TO_BOOK` | 11/279 (3,9%) |
| Lượt có CTA khác `NO_CTA` | 48/279 (17,2%) |
| Lượt có từ ngữ đặt lịch/khung giờ | **8/279 (2,9%)** |

Với một chatbot bán hàng, 2,9% lượt có lời mời đặt lịch là con số đáng báo động — và benchmark **không hề chấm điểm nó**. Bạn không thể cải thiện thứ bạn không đo.

### 5.5. Không có nhãn người thật

`expectedBehavior` do chính team viết, dựa trên kỳ vọng của team. Không có human rater độc lập, không có khách thật. Vì vậy "77,1% chất lượng" là **mức độ khớp với kỳ vọng nội bộ**, không phải chất lượng cảm nhận của khách.

---

## 6. Đánh giá từng tài liệu

### 6.1. `README.md` — 7,5/10

**Điểm mạnh:**
- Cấu trúc rõ, có mục đích, tham số, cách đọc kết quả, cách so sánh trước/sau.
- Mô tả telemetry 5 tầng rất cụ thể, đúng với những gì harness thực sự ghi.
- Có hướng dẫn chạy riêng từng case và truyền UUID — tiện vận hành.
- Có quy trình so sánh baseline/after với 4 chỉ số bắt buộc.

**Điểm yếu:**
- Khẳng định "20 hội thoại khách hàng thực tế" — sai (mục 3.1).
- `LIVE_BOT_ACCUMULATED` không tồn tại trong code — bẫy (mục 5.2).
- Không nói rõ đây là turn-level replay, không phải conversation-level.
- Không cảnh báo chi phí: 279 lần gọi LLM thật với prompt ~11k token.
- Không nói rõ "Pass 3/3" là yêu cầu rất khắt khe (một lỗi ở 1/279 lượt làm fail cả case).

### 6.2. `SBR01_PHASE1_BASELINE_REPORT.md` — 5,5/10

**Điểm mạnh:**
- Cấu trúc tốt: tổng quan → từng case → từng lượt → latency/retrieval/skill → nguyên nhân → giả thuyết.
- Tách `raw_llm_output` / `post_referee_output` / `final_sent_output` — đây là cách đúng để quy trách nhiệm.
- Có bảng mutation/override — rất giá trị để tìm "Java đang viết hộ bot câu gì".
- Có giả thuyết H1/H2 để Phase 2 kiểm chứng.
- Phát hiện đúng 3 lỗi thật: Java chèn câu mẫu, rò `|`, lặp triệu chứng.

**Điểm yếu:**
- 7 lỗi/sai lệch factual (mục 3.1–3.7), trong đó 2 lỗi đảo ngược kết luận (CASE-14, Safety).
- Điểm Safety headline sai, dẫn dắt toàn bộ phần kết luận.
- Phần "Hành vi mong đợi/bị cấm" trống — không audit được.
- Bỏ qua `good_example` dù dữ liệu đã có (252/279).
- Không có trục bán hàng.
- Có dấu hiệu được sinh tự động rồi không đối chiếu: các mục "Section 3" trống, các con số copy đúng nhưng diễn giải không khớp dữ liệu.

**Nhận xét thẳng:** báo cáo này đọc như thể được viết từ **cảm giác về dữ liệu**, không phải từ **việc mở từng case ra xem**. Những chỗ đúng thì rất đúng (telemetry, mutation); những chỗ sai thì sai ở đúng nơi quan trọng nhất (Safety, CASE-14, T13).

---

## 7. Khuyến nghị

### P0 — sửa evaluator trước khi dùng baseline (1–2 ngày)

1. **Bỏ `ALLOWED_NANG_SEN_CATALOG_PRICES_VND` hardcode.** Sinh danh sách giá từ chính `catalogSnapshot` của từng lượt (hoặc từ DB), có phân biệt chi nhánh.
2. **Bỏ rule giá chi nhánh hardcode** (670k/680k/750k). So giá với `catalogSnapshot.branch` của lượt đó.
3. **Sửa rule T13**: không `remove(339_000)`; so với đúng 2 gói đã giới thiệu ở T1/T5.
4. **Thêm kiểm tra "đúng cặp gói"**: T5 phải so đúng 239k vs 399k, không chỉ bắt từ khóa.
5. **Điền `expectedBehavior` vào báo cáo** (hoặc sinh báo cáo trực tiếp từ JSON, đừng viết tay).

### P1 — bổ sung trục đo (3–5 ngày)

6. **Thêm trục D — Sales Progression**:
   - `close_attempt_rate` (có lời mời đặt lịch / hỏi giờ / hỏi cơ sở),
   - `next_step_rate` (mỗi lượt có 1 bước tiến),
   - `question_budget` (số câu hỏi / lượt; 2 lượt liên tiếp có `?` → fail),
   - `repeat_request_rate` (khách phải nhắc lại),
   - `readiness_progression` (EXPLORING → CONSIDERING → READY).
7. **Thêm chế độ `CONVERSATION_LEVEL`** (dùng `LIVE_GENERATED`) và báo cáo song song turn-level + conversation-level. Sửa README cho khớp tên tham số.
8. **Ghi timestamp từng phase** (`turnStart`, `llmStart`, `llmEnd`, `refereeEnd`) để latency có thể kiểm chứng.
9. **Biến zero-mutation thành bất biến**: `ds.setReadOnly(true)` + transaction read-only, và assert count cho cả `agent_skills`, `learned_rules`, `feedback_logs` (hiện chỉ đếm 2 bảng).

### P2 — dữ liệu & tính đại diện (1–2 tuần)

9. **Đổi tên/tuyên bố**: "20 internal replay sessions", không phải "20 real customer conversations".
10. **Thu 20–50 hội thoại khách thật** (đã ẩn danh) và chạy lại. Đây là điều kiện để con số 77,1% có ý nghĩa.
11. **Thuê 1 người ngoài team chấm 50 lượt** làm calibration cho rubric.
12. **Thêm case phản ánh đúng lỗi người dùng phản ánh**: "hỏi lại lắm thế", "nói gây hiểu lầm", "bot hỏi mãi không chốt", "8 lượt không có lời mời đặt lịch".

---

## 8. Kết luận

**Giá trị thật của bộ SBR-01 không nằm ở các con số trong báo cáo, mà ở chỗ nó tạo ra một "hộp đen" đủ chi tiết để quy trách nhiệm cho từng lớp.** Đây là thứ trước đây dự án thiếu.

Nhưng báo cáo đã đánh đồng **"dữ liệu tốt"** với **"kết luận đúng"**. Ba vấn đề lớn nhất:

1. **Safety 96,1% là ảo** — do evaluator thiếu giá thật trong KB. Đừng dùng con số này để nói bot "đôi khi sai giá".
2. **"Khách hàng thực tế" là ảo** — 0/20. Đừng dùng con số 77,1% để nói "chất lượng tư vấn thực tế".
3. **Thiếu trục chốt sale** — đúng thứ bạn cần cải thiện nhất lại không được đo.

Điều tích cực: khi tôi kiểm tra độc lập, dữ liệu của bộ benchmark **xác nhận** đúng những gì cần sửa: 90,3% lượt bị nhồi `good_example`, 24,7% lượt bị Java sửa câu, 37 lượt rò `|`, 70,6% lượt vẫn `EXPLORING`, chỉ 2,9% lượt có lời mời đặt lịch.

Nói cách khác: **công cụ đúng, dữ liệu đúng, nhưng bản báo cáo đang đọc sai dữ liệu của chính nó.**

---

*Phụ lục: mọi con số trong tài liệu này đều tính lại được từ `results/sbr01_baseline_summary.json` và 20 file `results/cases/CASE-XX_replay_telemetry.json`. Các kiểm tra DB dùng `conversation_sessions` / `conversation_messages` tại thời điểm 2026-11-04 21:5x (Asia/Bangkok).*
