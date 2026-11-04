# SBR-01 Replay Benchmark & Baseline Evaluation Suite

Bộ Replay Benchmark chính thức cho **SBR-01 (Sales Bot Refactor — Phase 1)** của dự án **DocMind — Facebook Fanpage Sales Chatbot**.

Mục tiêu:
1. Tái hiện chính xác **20 hội thoại khách hàng thực tế** từ PostgreSQL (`page_id = 2850a8ef-ca4a-40eb-87fa-4f808640efee`, Nàng Sen Spa — tổng cộng **93 lượt tin nhắn khách**, **186 tin nhắn**) đã được ẩn danh PII.
2. Chạy lặp độc lập **`N = 3` lần (`Run 1`, `Run 2`, `Run 3`)** cho mỗi hội thoại (**tổng 60 lần chạy hội thoại / 279 lượt bot trả lời**) với cùng cấu hình đầu vào.
3. Thu thập đầy đủ **Telemetry 5 tầng theo từng lượt (Mục 4.1–4.5)**:
   - Input & Context (lịch sử hội thoại, Catalog snapshot, KB chunks, system prompt, full dynamic prompt lưu theo từng lượt, trạng thái memory trước/sau lượt).
   - Skill & Decision (`availableSkills`, `preselectedSkillsInPrompt`, `activatedSkillCodes`, `excludedSkills` kèm lý do loại, `goodExamplesInjectedIntoPrompt`, `tacticalFocus`, `responsePlan`, `nextAction`, `ctaLevel`, `thinkingMode`).
   - Retrieval (`StubEmbeddingClient`, vector candidates, fulltext candidates, RRF fused chunks, thời gian truy xuất).
   - Output Pipeline (tách biệt rõ `raw_llm_output`, `post_referee_output`, `final_sent_output`, kèm danh sách từng bước Java override trên `AdaptiveCognitiveReflection` và từng bước Java sanitizer sửa nội dung bong bóng).
   - Latency Breakdown (`totalTurnMs`, `retrievalDurationMs`, `promptBuildDurationMs`, `llmDurationMs`, `refereeValidationDurationMs`, `repairDurationMs`, `retryCount`, `fallbackDurationMs`, số lượng token).
4. Chấm điểm độc lập trên **3 trục Rubric (Mục 5)**:
   - **A. Safety & Truth** *(Hard Gate — không trung bình hóa với điểm văn phong)*
   - **B. Task Fulfillment**
   - **C. Sales Conversation Quality**
5. Đảm bảo **Zero-Mutation** tuyệt đối trên các bảng dữ liệu vận hành trong PostgreSQL (`conversation_sessions`, `conversation_messages`, `sent_bubble_ledger`, `agent_skills`, `learned_rules`).

---

## 1. Cấu trúc thư mục

```text
benchmark/sbr01/
├── README.md                                      # Hướng dẫn vận hành & so sánh Replay Benchmark
├── SBR01_PHASE1_BASELINE_REPORT.md                # Báo cáo Baseline chi tiết (Phase 1)
├── fixtures/
│   ├── sbr01_20_conversations.json                # 20 hội thoại thật đã ẩn danh PII (CASE-01..CASE-20)
│   └── sbr01_kb_catalog_skills_snapshot.json      # Snapshot đối chiếu KB, Catalog, Skills, Learned Rules & System Prompt SHA-256
└── results/
    ├── sbr01_baseline_summary.json                # Tổng hợp kết quả toàn bộ 20 cases x 3 runs + phân vị p50/p95
    ├── cases/
    │   ├── CASE-01_replay_telemetry.json          # Telemetry đầy đủ của Session chính 6ccb68f3-6882-416a-ae7e-9c1ceed8ea62
    │   └── ... (CASE-02..CASE-20)
    └── prompts/
        ├── CASE-01_run1_T1.txt                    # Nội dung prompt cuối cùng gửi tới LLM ở từng lượt
        └── ...
```

---

## 2. Cách chạy Replay Benchmark

### 2.1. Kiểm tra tính toàn vẹn của bộ Fixture (Offline / Fast Check)

```powershell
cd d:\Project\digital-spirituality\docmind\api-java
mvn test "-Dtest=Sbr01ReplayBenchmarkHarnessTest#verifySbr01FixturesAndHarnessWiring"
```

### 2.2. Chạy toàn bộ bộ Benchmark (20 hội thoại × `N=3` lần chạy = 279 lượt)

```powershell
cd d:\Project\digital-spirituality\docmind\api-java
mvn test "-Dtest=Sbr01ReplayBenchmarkHarnessTest#executeSbr01ReplayBenchmarkSuite" `
  "-Dsbr01.enabled=true" `
  "-Dsbr01.caseId=ALL" `
  "-Dsbr01.runs=3" `
  "-Dsbr01.parallelism=7"
```

### 2.3. Chạy riêng từng hội thoại hoặc riêng Session bắt buộc `6ccb68f3-6882-416a-ae7e-9c1ceed8ea62` (`CASE-01`)

Có thể truyền mã `CASE-01`..`CASE-20` hoặc truyền trực tiếp UUID session gốc vào `-Dsbr01.caseId`:

```powershell
# Chạy riêng CASE-01 (session 6ccb68f3-6882-416a-ae7e-9c1ceed8ea62) với N=3 lần chạy:
cd d:\Project\digital-spirituality\docmind\api-java
mvn test "-Dtest=Sbr01ReplayBenchmarkHarnessTest#executeSbr01ReplayBenchmarkSuite" `
  "-Dsbr01.enabled=true" `
  "-Dsbr01.caseId=CASE-01" `
  "-Dsbr01.runs=3"

# Hoặc truyền trực tiếp session UUID:
mvn test "-Dtest=Sbr01ReplayBenchmarkHarnessTest#executeSbr01ReplayBenchmarkSuite" `
  "-Dsbr01.enabled=true" `
  "-Dsbr01.caseId=6ccb68f3-6882-416a-ae7e-9c1ceed8ea62" `
  "-Dsbr01.runs=3"

# Chạy một nhóm hội thoại cụ thể (ví dụ CASE-01, CASE-09, CASE-13):
mvn test "-Dtest=Sbr01ReplayBenchmarkHarnessTest#executeSbr01ReplayBenchmarkSuite" `
  "-Dsbr01.enabled=true" `
  "-Dsbr01.caseId=CASE-01,CASE-09,CASE-13" `
  "-Dsbr01.runs=3"
```

### 2.4. Các tham số cấu hình dòng lệnh (`System Properties`)

| Tham số | Mặc định | Ý nghĩa |
|---|---|---|
| `-Dsbr01.enabled` | `false` | Đặt `true` để kích hoạt thực thi LLM thật. |
| `-Dsbr01.caseId` | `ALL` | `ALL` hoặc danh sách mã `CASE-01,CASE-02` / UUID session phân cách bằng dấu phẩy. |
| `-Dsbr01.runs` | `3` | Số lần chạy độc lập cho mỗi hội thoại (`N=3`). |
| `-Dsbr01.historyMode` | `HISTORICAL_TRANSCRIPT` | `HISTORICAL_TRANSCRIPT` (dùng lịch sử thật của khách tại mỗi lượt `T_k` để tái hiện chính xác ngữ cảnh từng lượt) hoặc `LIVE_BOT_ACCUMULATED` (dùng phản hồi mới của bot làm lịch sử cho lượt kế tiếp). |
| `-Dsbr01.parallelism` | `6` | Số luồng chạy song song giữa các hội thoại độc lập. |
| `-Dsbr01.outputDir` | `benchmark/sbr01/results` | Thư mục xuất kết quả JSON và prompt text. |

---

## 3. Cách đọc báo cáo kết quả

1. **Tổng quan toàn bộ Suite (`benchmark/sbr01/results/sbr01_baseline_summary.json`)**:
   - `zeroMutationVerified`: Khẳng định số bản ghi `conversation_sessions` và `conversation_messages` trong PostgreSQL trước và sau khi chạy không thay đổi (`true`).
   - `overallSuiteMetrics`:
     - `casesPassingAll3Runs`: Số hội thoại đạt PASS tuyệt đối ở tất cả các lượt qua cả 3 lần chạy (`3/3`).
     - `safetyAndTruthTurnPassRate`, `taskFulfillmentTurnPassRate`, `salesConversationQualityTurnPassRate`, `overallTurnPassRate`: Tỷ lệ lượt đạt trên từng trục đánh giá.
     - `latencyStatsMs`: Phân vị `p50`, `p95`, `max`, `avg` của `totalTurnMs`, `llmDurationMs`, `retrievalDurationMs`, `validationDurationMs`, `repairDurationMs`.
2. **Chi tiết từng hội thoại (`benchmark/sbr01/results/cases/CASE-XX_replay_telemetry.json`)**:
   - `persistentFailures3Of3`: Các lỗi xuất hiện lặp lại ở cả `3/3` lần chạy (do logic Java cứng, prompt hoặc retrieval tất định).
   - `intermittentFailuresAcrossRuns`: Các lỗi chỉ xuất hiện ở `1/3` hoặc `2/3` lần chạy (do biến thiên sinh văn bản của LLM ở `temperature = 0.35`).
   - `turnSummaries`: Đối chiếu trực tiếp từng lượt `T1, T3, T5...` qua 3 lần chạy (`rawBubbles` vs `postRefereeBubbles` vs `finalBubbles`, `javaReflectionOverrides`, `javaBubbleMutations`, `failures`).
   - `runs[i].turns[j]`: Chứa toàn bộ dữ liệu nguyên bản 5 nhóm telemetry (Mục 4.1–4.5) của lần chạy `i`, lượt `j`.
3. **Nội dung Prompt thực tế gửi tới LLM (`benchmark/sbr01/results/prompts/CASE-XX_runY_TZ.txt`)**:
   - Cho phép kiểm tra chính xác câu chữ trong `<SYSTEM_PROMPT>`, `<SCOPE_FILTERED_CATALOG>`, `<AGENT_SKILLS>`, `<STRUCTURED_CONVERSATION_STATE>`, `<CONVERSATION_HISTORY>` tại bất kỳ lượt nào.

---

## 4. Cách so sánh kết quả giữa hai lần chạy (Trước và Sau mỗi bản vá ở Phase 2+)

Khi bước sang các phase sửa lỗi tiếp theo, để đối chiếu khách quan trước/sau bản vá:

1. **Giữ nguyên `benchmark/sbr01/results/sbr01_baseline_summary.json`** làm mốc chuẩn Phase 1 (hoặc sao lưu sang `benchmark/sbr01/results/baseline_p1/`).
2. Chạy lại Replay Benchmark xuất ra thư mục mới bằng tham số `-Dsbr01.outputDir`:
   ```powershell
   mvn test "-Dtest=Sbr01ReplayBenchmarkHarnessTest#executeSbr01ReplayBenchmarkSuite" `
     "-Dsbr01.enabled=true" `
     "-Dsbr01.caseId=ALL" `
     "-Dsbr01.runs=3" `
     "-Dsbr01.outputDir=benchmark/sbr01/results_after_patch"
   ```
3. Đối chiếu 4 chỉ số bắt buộc giữa `sbr01_baseline_summary.json` (trước) và `results_after_patch/sbr01_baseline_summary.json` (sau):
   - **Cổng an toàn (`safetyAndTruthTurnPassRate`)**: Bắt buộc giữ nguyên `100%` (không phát sinh bất kỳ lỗi bịa giá, sai chi nhánh, chẩn đoán y khoa hay xác nhận lịch giả nào).
   - **Tỷ lệ hoàn thành nhiệm vụ (`taskFulfillmentTurnPassRate`)** và **Chất lượng hội thoại (`salesConversationQualityTurnPassRate`)**: So sánh số lỗi `persistentFailures3Of3` đã được triệt tiêu trên `CASE-01` (`T1..T15`) và `CASE-02..CASE-20`.
   - **Số lần Java can thiệp (`javaBubbleMutations` / `javaReflectionOverrides`)**: Kiểm chứng xem bản trả lời tự nhiên của LLM có còn bị Java chèn câu mẫu cứng hay sửa dấu câu sai ngữ cảnh không.
   - **Độ trễ (`latencyStatsMs.totalTurnMs.p50` & `p95`)**: Kiểm tra tác động lên thời gian phản hồi mỗi lượt.
