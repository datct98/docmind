# Đánh giá & nghiệm thu SBR-01 FIX BATCH 1 (FIX-0 → FIX-4)

**Ngày:** 2026-11-05
**Đối tượng:** `SBR01_FIX_BATCH1_EVIDENCE_PACKAGE.md` (bản Owner nhận)
**Đối chiếu:** 5 commit `05012cb → e2451e0`, 2 tài liệu baseline (`README.md`, `SBR01_PHASE1_BASELINE_REPORT.md`), source code tại HEAD `e2451e0`, PostgreSQL đang chạy, và toàn bộ artifact trong `benchmark/sbr01/results_fix*`.

---

## 0. Kết luận điều hành

| FIX | Nội dung | Code | Bằng chứng | Quyết định đề xuất |
|---|---|---|---|---|
| **FIX-0** | Ground lại evaluator Safety + trục medical | Tốt | **Số trong package không khớp artifact** | **Chấp thuận có điều kiện** — phải sửa bảng số & chạy lại tại HEAD |
| **FIX-1** | Bỏ template Java | Tốt | Đúng hướng, nhưng coverage bị phóng đại; có 11 lượt relevance giảm | **Chấp thuận có điều kiện** |
| **FIX-2** | Bỏ dấu `\|` khỏi history | Tốt | **Hợp lệ** (135 lượt live, 29→0) | **Chấp thuận** |
| **FIX-3** | Giữ dấu câu của LLM | Tốt | **Hợp lệ** (offline 9→0, 4→0; coverage 35 bước) | **Chấp thuận** |
| **FIX-4** | Prompt policy mới | Tốt | **KHÔNG HỢP LỆ** — qwen bị 423, 24/24 lượt không phải JSON, safety 0/24 | **TỪ CHỐI nghiệm thu** — phải chạy lại khi model hoạt động |

**Điểm tổng:** 6,5/10 — **đạt về kỹ thuật, chưa đạt về bằng chứng**.

**Một câu:** 5 bản vá đi đúng hướng và sửa đúng những lỗi tôi đã chỉ ra ở lượt trước, nhưng **gói bằng chứng không đáng tin bằng các bản vá bên trong nó** — hai chỗ nghiêm trọng nhất là FIX-0 báo số khác artifact và FIX-4 nghiệm thu trên một lần chạy mà model chính bị khoá.

---

## 1. Những gì tôi đã xác thực

1. `git log`: 5 commit tồn tại đúng thứ tự, parent SHA khớp chuỗi trong package.
2. `git diff --stat` từng commit: đúng file, đúng phạm vi (không sửa lung tung).
3. Đọc code thay đổi: `Sbr01SafetyEvaluator`, `CleanAgentOutputRefereeAndSanitizer`, `CleanAgentCatalogAndPromptBuilder`, `BubbleTextSanitizer`, `CleanAgentOrchestratorService`.
4. Đọc artifact: `results_fix0_rescored`, `results_fix1_offline`, `results_fix2_targeted`, `results_fix3_offline`, `results_fix4_case01`.
5. Đối chiếu số trong package với số trong artifact.
6. Kiểm tra prompt version + SHA-256.
7. Kiểm tra các root cause còn lại sau Batch 1.

---

## 2. Những gì làm tốt (đáng ghi nhận)

### 2.1. Sửa đúng chỗ, đúng thứ tự

Batch 1 nhắm đúng 4 lỗi tôi đã nêu:

- **FIX-0** bỏ `ALLOWED_NANG_SEN_CATALOG_PRICES_VND` và rule chi nhánh hardcode — đúng lỗi làm "Safety 96,1%" thành ảo. Evaluator mới parse `<VERIFIED_BUSINESS_CATALOG>` từ prompt của chính lượt, tách 6 trục, có `UNVERIFIABLE` thay vì chấm bừa. Đây là thiết kế đúng.
- **FIX-1** bỏ đúng 3 câu template Java tôi đã chỉ mặt (`buildSymptomAcknowledgementClause`, `buildSymptomAnchoredDiscoveryQuestion`, apology ghép). Chuyển sang sửa theo **câu** thay vì ghi đè cả bong bóng — đúng hướng.
- **FIX-2** bỏ `" | "` trong history — đúng nguyên nhân 37 lượt rò pipe (LLM bắt chước delimiter trong history).
- **FIX-3** bỏ `normalizeDeclarativeQuestionMarks` và nhánh tự đổi `.` → `?` — đúng lỗi tôi đã chỉ ra là báo cáo baseline mô tả sai cơ chế.
- **FIX-4** prompt mới xử lý đúng 3 lỗi: framing "nhiều gói, tiêu biểu", cấm hỏi "vùng nào" mặc định, cấm câu "sẽ không gợi ý nữa", thêm `NO_SYMPTOM_TREATMENT_CLAIM`.

### 2.2. Kỷ luật commit tốt

- 5 commit độc lập, mỗi commit một mục tiêu, parent tuyến tính.
- Không đụng `benchmark/sbr01/results/` gốc; kết quả mới ghi thư mục riêng.
- Giữ nguyên `docs/analysis/` untracked của Owner (`git status -s` chỉ còn `?? docs/analysis/`).
- Dừng trước FIX-5 đúng tuyên bố.

### 2.3. FIX-0 report trung thực hơn baseline report

`SBR01_FIX0_RESCORE_REPORT.md` có mục **Known limitations** ghi rõ:

- rule `T13_DID_NOT_INTRODUCE...` xoá 339k có thể false-fail;
- `T5` chỉ kiểm keyword, không kiểm đúng cặp gói;
- product verification bảo thủ.

Đây là thứ baseline report thiếu. Đáng khen.

---

## 3. Vấn đề nghiêm trọng về bằng chứng

### 3.1. FIX-4: lần chạy "live" không dùng model chính — và toàn bộ 24 lượt không phải JSON

Đây là lỗi nặng nhất của cả gói.

Trong `results_fix4_case01/cases/CASE-01_replay_telemetry.json`, mỗi lượt có:

```json
"rawJsonText": "[qwen-error] Không thể kết nối hoặc nhận phản hồi từ qwen (model: qwen3.8-max, endpoint: https://api.vilao.ai/v1/chat/completions). (Chi tiết: 423 Locked: {\"error\":{\"code\":\"SUBSCRIPTION_PENDING_CONFIRMATION\",\"message\":\"Pricing/billing cho model 'qwen3.8-max' vừa thay đổi và đang chờ bạn xác nhận...\"}})"
```

Tôi đếm: **24/24 lượt** có `qwen-error`. Hệ quả trong chính summary của FIX-4:

| Chỉ số | Giá trị thật trong artifact |
|---|---:|
| `jsonValidTurns` | **0 / 24** |
| `safetyAndTruthPassTurns` | **0 / 24** |
| `casesPass3Of3` | **0** |
| `taskFulfillmentPassTurns` | 22 / 24 |
| `salesQualityPassTurns` | 18 / 24 |

Mọi lượt đều bị gắn `INVALID_JSON_OR_EMPTY_LLM_BUBBLES`. Phản hồi gửi khách đến từ **đường dự phòng sau lỗi 423**, không phải `qwen3.8-max` với prompt mới.

**Vì sao điều này phá hỏng kết luận:** bảng so sánh FIX-4 trong package (T1 fixed, T3 fixed, T5 fixed, T13 fixed...) đang so **baseline LLM** với **fallback output của một lần provider bị khoá**, rồi gán phần cải thiện cho prompt mới. Không có bằng chứng nào cho thấy `qwen3.8-max` đọc prompt `aci-v2.13` và trả lời tốt hơn.

Tệ hơn: `SBR01_FIX4_CASE01_COMPARISON.md` có ghi `INVALID_JSON_OR_EMPTY_LLM_BUBBLES` ở từng lượt, nhưng **không giải thích đó là lỗi 423**. Package tóm tắt lại càng không nhắc. Người đọc package sẽ tưởng đây là "live replay thành công".

### 3.2. FIX-0: số trong package không khớp artifact

Package §1 ghi:

| Chỉ số | Package | Artifact thật (`SBR01_FIX0_RESCORE_REPORT.md`) |
|---|---:|---:|
| Safety PASS cũ | 278/279 (99,6%) | **268/279** |
| Safety PASS mới | **223/279 (79,9%)** | **265/279 (95,0%)** |
| PRICE_GROUNDING PASS | 248/279 | 143 PASS + 136 N/A, **0 FAIL** |
| PRICE_HALLUCINATION / MISMATCH / UNVERIFIABLE | 1 / 5 / 25 | 0 / 0 / 0 (price); branch 1 fail + 120 unverifiable |
| MEDICAL_SAFETY_VIOLATION | **21/279** | **3/279** (+1 INCONCLUSIVE) |
| UNSUPPORTED_SERVICE_CLAIM | 6/279 | **10/279** |

Đây không phải lệch làm tròn. Đây là **hai bộ số khác nhau**. Artifact có `gitHead = f258687...+FIX0-wip`, tức được sinh từ worktree trước commit `05012cb`; package lại gán cho commit `05012cb`. Có thể package lấy từ một bản evaluator nháp (strict hơn) rồi evaluator được nới ra trước khi commit — nhưng như vậy **package đang mô tả một phiên bản không tồn tại trong repo**.

Hệ quả: con số "79,9%" trong package là sai. Con số đúng (theo artifact committed) là **95,0%**, và số medical là **3**, không phải 21.

### 3.3. FIX-1: "0/279" thực chất là 31 lượt replay + 248 lượt idempotence

Artifact `sbr01_fix1_offline_replay.json`:

```
totalStoredTurns = 279
turnsWithRecordedFinalGateInput = 31
turnsReplayedFromOldFinalForIdempotence = 248
turnsWhoseFinalChanged = 30
oldFinalTurnsWithJavaTemplate = 21
newFinalTurnsWithJavaTemplate = 0
idempotenceMismatches = 0
```

Nghĩa là chỉ **31 lượt** thực sự được đưa qua cổng mới; **248 lượt** chỉ được kiểm tra "chạy lại cổng mới trên final cũ có đổi không". Đó là kiểm tra idempotence hợp lý, nhưng **không chứng minh** cổng mới sẽ xử lý input pre-gate gốc giống vậy. Package ghi "Offline Sanitizer Replay trên 279 lượt baseline" — cách nói này phóng đại coverage.

Nghiêm trọng hơn, artifact tự ghi:

```
changedTurnsWhereRelevanceBelowRawLlm = 13
changedTurnsWhereRelevanceBelowOldFinal_templateEchoRemoved = 11
```

Tức **11–13 lượt** mà bản final mới **kém liên quan hơn** bản cũ (theo lexical relevance). Package lại tuyên bố "0 regressions". Đây là mâu thuẫn trực tiếp với artifact.

Ví dụ cụ thể: CASE-01 T15 Run2, sau FIX-1 chỉ còn `"Em xin lỗi vì cách nói khiến mình hiểu lầm ạ."` — một lời xin lỗi trống, không còn nội dung. Bỏ template là đúng, nhưng **không thay bằng nội dung tốt hơn** thì chất lượng giảm. Package chỉ ghi nhận điều này ở mục FIX-4/T15 như một "phụ thuộc FIX-5", không ghi ở FIX-1.

### 3.4. FIX-3: coverage 35 bước, không phải 279 lượt

Artifact: `recordedNormalizeSteps = 9`, `recordedP37B2Steps = 26` → **35 bước** được replay. Đúng là 9→0 và 4→0, nhưng package ghi "trên 279 lượt baseline". Cần nói rõ: chỉ các lượt có ghi nhận bước biến đổi mới được kiểm.

### 3.5. Lệch lệnh chạy và số dòng

- Package §1 ghi `-Dsbr01.rescore.enabled=true`; harness đọc `@EnabledIfSystemProperty(named = "sbr01.rescore")` — **không có `.enabled`**. Với lệnh trong package, test rescore sẽ **bị skip**, không chạy. README (FIX-0) ghi đúng `-Dsbr01.rescore=true`.
- Package ghi `Sbr01SafetyEvaluator.java` **610 dòng**; file thực tế **666 dòng**. `Sbr01SafetyEvaluatorTest` có 28 `@Test` — đúng.
- Package ghi "Tests run: 29". Nếu dùng đúng lệnh đã ghi, chỉ có 28 test chạy (rescore bị skip). Con số 29 cho thấy họ đã dùng `sbr01.rescore=true`, không phải lệnh trong package.

### 3.6. Provenance của artifact

Cả ba artifact offline đều có `gitHead` dạng `<sha>+FIXx-worktree` (WIP), không phải commit sạch:

- FIX-0: `f258687...+FIX0-wip` (dù commit là `05012cb`)
- FIX-1: `05012cb+FIX1-worktree`
- FIX-3: `48b6c4e+FIX3-worktree`

Với một gói nghiệm thu, artifact phải sinh từ đúng SHA được nêu. Hiện tại không có artifact nào chứng minh "code tại `05012cb` cho ra số này".

---

## 4. Đánh giá code từng FIX

### FIX-0 — Tốt, có một quyết định cần Owner chốt

**Đúng:**
- Bỏ hardcode, parse catalog từ prompt — đúng nguyên tắc "ground on rendered context".
- Tách 6 trục, có `UNVERIFIABLE`/`INCONCLUSIVE` — tốt hơn nhị phân.
- Medical evaluator có quan hệ triệu chứng × vị từ × phủ định — hợp lý.

**Cần chốt:**
- `UNVERIFIABLE` (120 branch + 30 product) **không** bị tính là fail trong `safetyPassTurns` (265 = 279 − 14 fail thật). Nhưng report ghi "UNVERIFIABLE is never counted as PASS" — câu này dễ gây hiểu nhầm. Cần định nghĩa rõ: UNVERIFIABLE là **neutral**, không phải pass, không phải fail.
- Chính sách medical đang **rất chặt**: "giảm đau đầu", "phù hợp với tê tay" bị coi là vi phạm. Đây là lựa chọn copywriting, không phải "safety" thuần tuý. Owner cần quyết: spa có được nói "giảm đau đầu" không? Nếu không, 10 `UNSUPPORTED_SERVICE_CLAIM` là đúng; nếu có, evaluator đang quá tay.

### FIX-1 — Đúng hướng, nhưng để lại khoảng trống

**Đúng:**
- Bỏ 3 câu template, chuyển sang sentence-level — đúng.
- 21→0 template — có artifact.

**Rủi ro:**
- 11 lượt relevance giảm; T15 thành lời xin lỗi trống.
- Không thay template bằng cơ chế buộc LLM phải nói cụ thể (validator `NO_CUSTOMER_SPECIFIC_REFERENCE` tôi đề xuất vẫn chưa có).
- `knownSymptoms` không còn dùng — tốt, nhưng cần đảm bảo LLM vẫn nhắc đúng triệu chứng khi cần.

### FIX-2 — Sạch

- `renderBotBubblesForHistory` nối bằng `"\n    "` — đúng.
- Live replay 135 lượt, `jsonValidTurns=135`, không có qwen-error, 29→0 pipe. Đây là bằng chứng mạnh nhất trong Batch 1.
- Hạn chế: chỉ 7 case; package không báo cáo điểm tổng (cases pass 2/7, safety 134/135, task 124/135, quality 113/135). Nên có bảng đầy đủ.

### FIX-3 — Sạch, ít rủi ro

- Bỏ `normalizeDeclarativeQuestionMarks` + nhánh `.`→`?` — đúng.
- 9→0 và 4→0 trên 35 bước ghi nhận. Rủi ro thấp.
- Cần kiểm thêm: một số test cũ có thể dựa vào hành vi chuẩn hoá dấu câu; full suite pass là tín hiệu tốt, nhưng nên có test hồi quy cho câu hỏi thật.

### FIX-4 — Prompt tốt, bằng chứng hỏng

**Prompt diff rất đúng:**
- thêm "vài gói tiêu biểu, còn các gói khác";
- cấm hỏi "vùng nào" mặc định;
- cấm "sẽ không gợi ý nữa";
- thêm `NO_SYMPTOM_TREATMENT_CLAIM`;
- thêm nhánh so sánh đúng cặp gói & liệt kê gói khác.

**Nhưng:**
- Không có bằng chứng hành vi hợp lệ (mục 3.1).
- `NO_SYMPTOM_TREATMENT_CLAIM` có thể **quá tay**: cấm cả "phù hợp với vùng mỏi" sẽ làm bot mất khả năng giải thích vì sao gói hợp với khách — đúng cơ chế bán hàng cốt lõi. Owner cần xem lại ranh giới: cấm "chữa/điều trị/giảm triệu chứng bệnh lý" là đúng; cấm "phù hợp với vùng đang mỏi" là quá chặt.
- Sync test chỉ kiểm chuỗi, không kiểm hành vi. Nó không thể thay thế live replay.

---

## 5. Batch 1 **không** sửa gì (quan trọng cho kỳ vọng)

Tôi kiểm tra lại source tại HEAD `e2451e0`:

| Root cause tôi đã nêu | Batch 1 có sửa? |
|---|---|
| Java chèn câu template | ✅ FIX-1 |
| Rò `\|` | ✅ FIX-2 |
| Tự đổi dấu câu | ✅ FIX-3 |
| Hỏi "vùng nào" mặc định | ✅ FIX-4 |
| Bẫy "chỉ có 2 gói" | ✅ FIX-4 |
| **`good_example` nhồi vào prompt (252/279 lượt)** | ❌ **Vẫn còn** (`CleanAgentCatalogAndPromptBuilder:2485–2492`) |
| **Temperature 0.35, không frequency/presence penalty** | ❌ **Vẫn còn** |
| **`conversation_goal` bị khoá bởi cảm xúc** | ❌ **Vẫn còn** (`AgentBrainService:2034`) |
| **Closing skill bị chặn tới `READY_TO_BOOK`** | ❌ **Vẫn còn** |
| **Không có trục đo tiến trình bán hàng** | ❌ **Vẫn còn** |

Nghĩa là: sau Batch 1, bot sẽ **sạch hơn về kỹ thuật và bớt máy móc hơn**, nhưng **vẫn chưa có chiến lược chốt sale**. FIX-5 (design-only) chỉ giải quyết "service recovery state transition", không giải quyết closing.

Đây là kỳ vọng Owner cần đặt đúng: Batch 1 = **vệ sinh hội thoại**, chưa phải **nâng cấp bán hàng**.

---

## 6. Bảng điểm thực tế (theo artifact, không theo package)

| Hạng mục | Điểm | Ghi chú |
|---|---:|---|
| Mục tiêu & phạm vi | 9/10 | Đúng 5 lỗi ưu tiên, chia commit gọn |
| Chất lượng code | 8/10 | Sửa đúng, ít rủi ro; FIX-4 prompt hơi quá tay |
| Bằng chứng FIX-0 | 4/10 | Số không khớp artifact; provenance WIP |
| Bằng chứng FIX-1 | 6/10 | Đúng hướng nhưng coverage & relevance bị bỏ qua |
| Bằng chứng FIX-2 | 9/10 | Live replay hợp lệ, số khớp |
| Bằng chứng FIX-3 | 8/10 | Offline hợp lệ, coverage hẹp |
| Bằng chứng FIX-4 | **1/10** | Model chính bị 423; 24/24 non-JSON; không đo được prompt mới |
| Governance | 8/10 | Tách implementer/reviewer/approver; giữ boundary |
| **Tổng** | **6,5/10** | Đạt kỹ thuật, chưa đạt bằng chứng |

---

## 7. Điều kiện nghiệm thu đề xuất

### Chấp thuận có điều kiện: FIX-0, FIX-1

1. Sửa bảng số FIX-0 trong package cho khớp `SBR01_FIX0_RESCORE_REPORT.md` (268→265; 3 medical; 10 unsupported; 0 price fail).
2. Chạy lại rescore tại đúng HEAD sạch (`05012cb` hoặc sau), artifact ghi `gitHead = <sha>` không có `+wip`.
3. FIX-1: bổ sung bảng 11 lượt relevance giảm; xác nhận đây là chấp nhận được hay cần vá; bổ sung validator "phải nhắc lại nội dung khách" thay vì chỉ xoá template.

### Chấp thuận: FIX-2, FIX-3

4. Bổ sung báo cáo đầy đủ 7 case cho FIX-2 (cases pass, safety/task/quality), không chỉ pipe.
5. Ghi rõ coverage 35 bước của FIX-3.

### Từ chối: FIX-4

6. **Chạy lại toàn bộ CASE-01 × 3 khi `qwen3.8-max` hoạt động** (hoặc chấp nhận chính thức fallback model và ghi rõ model nào).
7. Thêm **preflight gate** vào harness: nếu `jsonValidTurns < 100%` hoặc có `qwen-error`/`INVALID_JSON` → **fail run**, không được ghi "exit 0" như thành công.
8. Trong báo cáo FIX-4, phân biệt rõ `raw_llm_output` thật vs fallback; không gọi fallback là raw LLM.
9. Owner chốt lại phạm vi `NO_SYMPTOM_TREATMENT_CLAIM`: cấm claim điều trị bệnh lý, nhưng cho phép mô tả "phù hợp với vùng đang mỏi" (nếu muốn giữ khả năng bán hàng).

---

## 8. Khuyến nghị cho Batch 2/3

1. **Batch 2 — Sales Progression** (chưa có trong kế hoạch): thêm trục đo & cơ chế chốt:
   - `close_attempt_rate`, `next_step_rate`, `question_budget`, `readiness_progression`;
   - mở khoá closing từ `RECOMMENDED`;
   - 2-option close + micro-commitment;
   - dùng ưu đãi 5 buổi −10%.
2. **Batch 2 — Repetition & voice**: bỏ `good_example`, thêm frequency/presence penalty, nâng temperature, sentence-level repeat guard.
3. **Batch 2 — Goal lock**: sửa `AgentBrainService:2034` để goal không bị cảm xúc nhất thời chiếm.
4. **Batch 3 — Dữ liệu thật**: thay 20 fixture synthetic bằng hội thoại khách thật; hiện 0/20 là khách thật.

---

## 9. Kết luận

Gói bằng chứng này **hơn hẳn** báo cáo baseline ở chỗ: có commit riêng, có test, có offline replay, có giới hạn được thừa nhận. Nó cũng sửa đúng những lỗi tôi đã chỉ ra.

Nhưng nó mắc đúng căn bệnh của báo cáo baseline: **số liệu trong bản tóm tắt không khớp artifact**, và lần này nghiêm trọng hơn — FIX-4 được trình bày như một live replay thành công trong khi model chính bị khoá và 24/24 lượt là non-JSON.

**Không nên nghiệm thu nguyên trạng.** Nên:

- **Nhận** FIX-2, FIX-3;
- **Nhận có điều kiện** FIX-0, FIX-1 sau khi sửa số & chạy lại tại HEAD;
- **Trả lại** FIX-4 để chạy lại khi provider hoạt động, kèm preflight gate.

Và quan trọng nhất: **đừng đánh đồng "Batch 1 xong" với "bot đã bán hàng tốt hơn".** Batch 1 làm bot bớt máy móc; nó chưa làm bot biết chốt.

---

*Mọi kết luận trong tài liệu này đều truy vết được: commit SHA + file + dòng, hoặc artifact JSON trong `benchmark/sbr01/results_fix*/`. Các phép đếm được thực hiện lại tại 2026-11-05 06:5x (Asia/Bangkok).*
