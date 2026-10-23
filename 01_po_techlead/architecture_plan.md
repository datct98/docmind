# Architecture Plan — Emotional Sales Engine & Multi-Agent Pipeline v1.0

## 1. Kiến trúc tổng thể (System Architecture)
Hệ thống tư vấn bán hàng giàu cảm xúc của DocMind vận hành trên kiến trúc Hybrid Neuro-Symbolic gồm 3 tầng:

```mermaid
flowchart TD
    User([Khách hàng trên Fanpage / Web]) --> API[docmind-api :8090\nFanpageBotController & FanpageBotService]
    API --> Brain[docmind-agent-brain :8095\nCustomerInsight + SkillMatrix + PolicyEngine]
    API --> RAG[Hybrid RRF + Obsidian Backlink Graph + MOC]
    RAG --> PG[(PostgreSQL + pgvector)]
    Brain --> Prompt[FanpageBotPromptBuilder\n3-Beat Emotional & Sensory Prompting]
    Prompt --> LLM[Ollama / OpenAI-Compatible LLM\n+ Post-Price ~10s Deliberation Pass]
    LLM --> PostProc[Soft-Touch Post-Processor & GuardrailPipeline\nHonorific + AntiInfoDumping + ClosingQuestion + BubbleCap]
    PostProc --> API
```

## 2. Phân tích Kỹ thuật & Thiết kế Giải pháp (Root-Cause Remediation)

### 2.1. Tầng 1: Agent Brain (`docmind-agent-brain` - Python FastAPI)
- **File mục tiêu**: `agent-brain/app/brain/skill_matrix.py`, `agent-brain/app/brain/insight_advisor.py`.
- **Thay đổi**:
  - Nâng cấp 9 kỹ năng bán hàng (`SKILL_01` -> `SKILL_09`) để truyền cảm hứng cảm xúc thực thụ: sử dụng ngôn từ giàu xúc giác (ấm áp, dịu nhẹ, thả lỏng sâu), loại bỏ văn phong mệnh lệnh cứng nhắc.
  - Bổ sung hướng dẫn nhịp điệu 2-3 bong bóng chat tự nhiên (`<= 3` bubbles) để đồng bộ hoàn toàn với `api-java`.

### 2.2. Tầng 2: Prompt & Turn-1 Fast Impression (`FanpageBotPromptBuilder.java`)
- **File mục tiêu**: `api-java/src/main/java/com/example/docmind/service/bot/FanpageBotPromptBuilder.java`.
- **Thay đổi**:
  - Nâng cấp `buildImpressiveThreeBubbleTurnOneOpener(honorific, lowerMsg)`: Đa dạng hóa các kịch bản mở đầu 3 bong bóng giàu cảm xúc cho khách hỏi chung, khách đau mỏi vai gáy/lưng, khách mất ngủ/stress, khách hỏi gội đầu dưỡng sinh.
  - Sửa `stripLeakedNumbersFromTurnOneReply`: Khi câu trả lời ở Lượt 1 có 3 đoạn văn tự nhiên, giữ nguyên cả 3 đoạn văn (thay vì chỉ lấy `paragraphs[0]` và `paragraphs[paragraphs.length - 1]` làm mất đoạn giữa!).
  - Nâng cấp hướng dẫn `formatPriceLadderGuidance`: Yêu cầu LLM luôn mở đầu bằng 1 câu **đồng cảm sâu sắc & nâng niu tâm lý** trước khi báo giá hoặc hạ bậc gói dịch vụ.

### 2.3. Tầng 3: Post-Processing & Post-Price Deliberation (`FanpageBotService.java`)
- **File mục tiêu**: `api-java/src/main/java/com/example/docmind/service/bot/FanpageBotService.java`.
- **Thay đổi**:
  1. **Bảo toàn 3 bong bóng ở Lượt 1 (`polishConversationalVietnamese` & `splitIntoBubbles`)**:
     - Không gộp thô bạo đoạn 1 và đoạn 2 khi đoạn 1 đã là một câu chào + đồng cảm hoàn chỉnh (> 45 ký tự).
  2. **Làm mềm hóa `enforcePriceLadderConsistency` (Chấm dứt ghi đè thô bạo làm tụt mood)**:
     - Khi LLM đã báo đúng mức giá mục tiêu (`hasTargetPrice`) và không rò rỉ gói sai (`!leakedEarCare129`, `!hasRejectedOrHigherPrice`), **tôn trọng văn phong tự nhiên của LLM** thay vì bắt buộc phải khớp từng chữ cứng nhắc.
     - Khi LLM trượt giá và cần fallback sang câu mẫu của `enforcePriceLadderConsistency`, nâng cấp câu mẫu thành văn phong **3 nhịp giàu cảm xúc, dịu dàng, tinh tế** (chia sẻ sự thấu hiểu -> vẽ ra trải nghiệm thảo dược ấm áp & minh bạch 0 Tip -> gợi mở nhẹ nhàng).
  3. **Làm giàu cảm xúc cho `synthesizeDynamicExplanationOrSymptomReply`, `enforceAntiInformationDumpingOnBroadPrice`, và `generateGroundedFallbackReply`**:
     - Thay thế các câu trả lời liệt kê khô khan bằng ngôn từ đồng cảm sâu sắc, chạm vào cảm giác mệt mỏi của khách hàng trước khi đưa thông tin gói và chi phí.
  4. **Tinh chỉnh `deliberatePostPriceMoodPreservationPass`**:
     - Duy trì nhịp suy nghĩ sâu `TARGET_POST_PRICE_THINKING_MS = 10_000L` (10 giây) trên LLM thực tế ở các lượt ngay sau báo giá, đồng thời cải thiện prompt tự phản biện (`buildPostPriceRefinementPrompt`) để bản viết lại mượt mà, giàu cảm xúc và chốt sale khéo léo.

### 2.4. Tầng 4: Guardrail Pipeline (`com.docmind.guardrail.*`)
- **File mục tiêu**: `AntiInfoDumpingHook.java`, `ClosingQuestionHook.java`, `BubbleCapHook.java`.
- **Thay đổi**:
  - `AntiInfoDumpingHook`: Nâng cấp văn bản thu gọn cho ngành Spa/Dưỡng sinh để vừa ngắn gọn, vừa ấm áp giàu cảm xúc, không mang giọng văn thông báo hành chính.
  - `ClosingQuestionHook`: Tránh ghi đè câu hỏi tự nhiên của LLM nếu câu hỏi đó đã mở, lịch sự và phù hợp ngữ cảnh.
  - `BubbleCapHook`: Giữ tương thích tuyệt đối với các Unit Test hiện hữu (`<= 2` bubbles khi test truyền vào các đoạn văn ngắn hoặc 4+ đoạn văn rời rạc) đồng thời bảo toàn cấu trúc 3 bong bóng cảm xúc (`<= 3` bubbles) cho hội thoại thực tế.

### 2.5. Tầng 5: Quy trình Multi-Agent Team v1.0 (`99_pipeline/run_multi_agent_pipeline.py`)
- Thiết kế Orchestrator tự động kiểm soát vòng đời 4 Agent:
  - **Stage 1 (PO + Techlead Gate)**: Kiểm tra tính hợp lệ và đầy đủ của hợp đồng nghiệm thu `01_po_techlead/acceptance_contract.yaml`.
  - **Stage 2 (Senior Dev Gate)**: Kiểm tra kết quả Unit Test, Happy-Path E2E và thông tin build (`02_dev/build_info.json`).
  - **Stage 3 (Black-Box QA Gate)**: Chạy độc lập hoàn toàn qua HTTP API (`http://localhost:8090/api/bot/fanpage/message`), không đọc source code trong `api-java` hay `agent-brain`. Thực thi 4 bộ test:
    1. `deterministic_policy_report.json` (Chính sách giá, danh xưng, không lộ mã nội bộ, số bong bóng `<= 3`).
    2. `random_scenario_report.json` (Mô phỏng khách hàng đa tính cách với seed cố định).
    3. `adversarial_report.json` (Tấn công ép giá 50k, hỏi dịch vụ ngoài luồng, prompt injection, chê đắt liên hoàn).
    4. `llm_judge_report.json` (Chấm điểm 5 tiêu chí Cảm xúc & Chốt Sale: Empathy, Naturalness, Mood Preservation, Value Framing, Conversion Progression).
  - **Stage 4 (Product PO UAT Gate)**: Tổng hợp báo cáo nghiệm thu kinh doanh (`business_acceptance.yaml`, `uat_report.md`, `release_notes.md`, `user_test_package.md`) và khóa phiên bản tại `99_pipeline/version_lock.json`.

### 2.6. Tầng 6: Cấu hình LLM Theo Tác vụ (`qwen2.5:14b-instruct-q5_K_M` + API Key Providers)
- **File mục tiêu**: `OllamaProperties.java`, `LlmProperties.java`, `OllamaLlmClient.java`, `OpenAiCompatibleLlmClient.java`, `LlmConfiguration.java`, `LlmHealthController.java`, `docs/ollama/Modelfile.qwen2.5-14b-sales-q5`.
- **Thiết kế**:
  - Hỗ trợ `qwen2.5:14b-instruct-q5_K_M` làm model mặc định cho Ollama, kèm cơ chế tự động fallback runtime sang `qwen2.5:14b` / `qwen2.5:7b` khi model Q5_K_M trả về HTTP 404 (chưa pull) hoặc HTTP 500 (OOM).
  - Áp dụng bộ tham số chuyên biệt theo tác vụ:
    1. **Sales Fanpage Chat**: `num_ctx=4096`, `temperature=0.2`, `top_p=0.9`, `repeat_penalty=1.08`, `num_predict=600`.
    2. **Internal Knowledge QA**: `temperature=0.1`, `top_p=0.85`, `num_predict=900`, `repeat_penalty=1.05`.
    3. **Memory / Rule Extraction & QA Judge**: `temperature=0.0`, `top_p=0.8`.

### 2.7. Tầng 7: Hệ thống Upload Playbook (`PB-*`) & Sample Dialogues (`FD-*`) Auto-ACTIVE (`.yaml`, `.md`, `.json`)
- **File mục tiêu**: `V17__page_playbooks_and_sample_dialogues.sql`, `PlaybookUploadService.java`, `SampleDialogueEntity.java`, `SampleDialogueRepository.java`, `RuleController.java`, `FanpageBotPromptBuilder.java`, `PlaybookModal.vue`.
- **Thiết kế**:
  - Hỗ trợ parse cả 3 định dạng **YAML (`.yaml`/`.yml`)**, **Markdown (`.md`/`.markdown`)**, **JSON (`.json`)**.
  - **Auto-ACTIVE (Bỏ bước duyệt tay)**: Mọi Playbook Rule (`PB-*`) và Sample Dialogue (`FD-*`) hợp lệ vượt qua kiểm định an toàn (`auditPlaybookRule`, `auditSampleDialogue`) đều được lưu trực tiếp với `active = true`, `review_status = 'APPROVED'`.
  - **Auto-Quarantine**: Tự động chặn (`active = false`, `review_status = 'REJECTED'`) các mẫu âm tính/độc hại chứa PII chưa che, cam kết chữa khỏi vĩnh viễn 100%, hoặc Prompt Injection (`FD-SPA-BAD-001`, `FD-SPA-BAD-002`).
  - **Prompt Injection**: Truy xuất top 1–2 `<SAMPLE_DIALOGUES>` theo `page_id` + `intent` + `symptom` để làm mẫu phong cách (kèm chỉ dẫn cấm sao chép máy móc, mọi con số giá lấy từ `<DOCUMENT_CONTEXT>`).

