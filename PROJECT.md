# Project: DocMind Fanpage Bot Overhaul, Báo Sai & Dạy AI Redesign, Page-Scoped / Global AI Playbook & Multi-Provider LLM

## Architecture

DocMind is an enterprise Sales Document Q&A, Fanpage Conversational Bot, and Self-Improving AI platform comprising:
1. **`doc-service-py`**: Python FastAPI microservice responsible for document format validation, MIME routing, and high-fidelity AST parsing (`.docx`, `.xlsx`, `.md`) into canonical schema blocks.
2. **`api-java`**: Spring Boot 4 / Java 21 backend providing:
   - Fanpage Bot conversational consultant (`/api/bot/fanpage/message`): multi-turn dialogue management, fault-tolerant Vietnamese honorific resolution, table/medical grounding, and phone lead capture / human handoff.
   - Self-Improving Meta-Learning & AI Playbook (`/api/feedback`, `/api/rules`): database persistence (PostgreSQL + pgvector), faithful rule extraction preserving user directives, page-scoped (`page_id`) and global (`page_id = null`) rule isolation, and injection into both Q&A chat and Fanpage Bot.
   - Unified Multi-Provider LLM Client: pluggable client abstraction driven entirely by `.env`, supporting Ollama, DeepSeek, Grok (xAI), OpenAI, Gemini, and custom OpenAI-compatible endpoints for both conversational chat and rule extraction.
3. **`frontend`**: Vue 3 SPA with scoped CSS tokens providing Sales Q&A chat, the Fanpage Bot Simulator modal with Messenger styling, the "Báo sai & Dạy AI" modal with root-cause & rule preview, and the "Bộ nhớ Kinh nghiệm AI (AI Playbook)" management panel supporting global/page-scoped filtering and editing.
4. **`e2e-tests`**: Opaque-box requirement-driven test suite verifying end-to-end multi-turn Fanpage dialogues, lead capture handoff, rule CRUD, mistake training, and multi-provider configuration.

---

## Feature Inventory

Every requirement from `ORIGINAL_REQUEST.md` (R1–R4) is cataloged below with its designated milestone:

| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| F1 | Clean "Lan Anh" Strings | Eliminate every trace of hardcoded customer name "Lan Anh" across backend prompts, service code, tests, and frontend components | M1 | Survey Bot |
| F2 | Turn-1 Only Greeting & Closing Rotation | Greet customer only on turn 1 (`history` empty); eliminate repeated greetings on turn >= 2; rotate closing questions based on history | M1 | Survey Bot |
| F3 | Fault-Tolerant Gender & Honorific Detection | Detect male self-identification across typos/shorthand ("mìn là nam", "mình làm nam", "toi la nam", "mk là nam", "t là nam", etc.) and enforce 100% "Anh" consistency (0 "Chị") | M1 | Survey Bot |
| F4 | Multi-Part Question & Strict Medical Grounding | Answer all query parts (male suitability + sessions/timeframe) using retrieved knowledge; never conflate Combo tiers; prevent unstated scar-cure claims | M1 | Survey Bot |
| F5 | Phone / Lead Capture & Pitch Silencing | Capture phone numbers / Zalo requests; immediately stop repeating price catalog/combos/discovery questions; set `needs_human_handoff=true` and confirm phone/Zalo callback | M1 | Survey Bot |
| F6 | PostgreSQL Null Parameter JDBC Fix | Fix `LearnedRuleRepositoryCustomImpl` native SQL parameter binding (`Types.OTHER` on null `page_id`, explicit typing on null `embedding`) enabling rule CRUD & active toggle | M2 | Survey Playbook |
| F7 | Faithful Rule Extraction | Redesign `MetaLearningService` heuristic fallback & prompt to preserve user's exact teaching instructions without generic distortion or unconditional digit stripping | M2 | Survey Playbook |
| F8 | Fanpage Bot `applied_rules` Injection | Add `applied_rules` field to `FanpageBotResponse`; inject matching Global + page-scoped active rules into Fanpage Bot prompt and return them in response | M2 | Survey Playbook |
| F9 | "Báo sai & Dạy AI" in Fanpage Simulator | Integrate "Báo sai & Dạy AI" button on assistant messages in `FanpageBotSimulatorModal.vue`, trigger `TeachAiModal`, and regenerate Messenger-styled reply | M2 | Survey Playbook |
| F10 | AI Playbook Page/Global Scoping | Support manual rule creation, editing, active toggling, and filtering by Global vs Page-scoped in `PlaybookModal.vue` and `mockData.js` | M2 | Survey Playbook |
| F11 | Unified `OpenAiCompatibleLlmClient` | Implement OpenAI Chat Completions client in `api-java` with Bearer auth, URL normalization, Jackson parsing, `<think>` tag stripping, and resilient error fallback | M3 | Survey LLM |
| F12 | Dynamic `LlmProperties` & `LlmConfiguration` | Auto-resolving presets for `ollama`, `deepseek`, `grok`, `openai`, `gemini`, `openrouter`, `custom`, and `stub` wired as primary `LlmClient` | M3 | Survey LLM |
| F13 | Complete Multi-Provider `.env` Presets | Documented, ready-to-use `.env` and `.env.example` with configuration blocks for all supported LLM providers and `docker-compose.yml` mapping | M3 | Survey LLM |
| F14 | Hermetic Testing & Zero-Key Isolation | Add `OpenAiCompatibleLlmClientTest` with `MockRestServiceServer`; ensure `application-test.properties` maintains zero external key dependency | M3 | Survey LLM |
| F15 | Opaque-Box E2E Acceptance Test Suite | Automated end-to-end test suite testing 4-turn Fanpage conversation, phone handoff, rule CRUD, training after mistakes, and multi-provider settings | M-E2E | E2E Testing Track |
| F16 | Live Integration, Build & Forensic Audit | Verification across `.\mvnw.cmd test`, `pytest`, `npm run build`, `docker compose up -d --build`, and Forensic Integrity Audit | M-Final | Implementation Track |

---

## Milestones

| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Fanpage Bot Conversational Naturalness & Lead Capture (R1, R2) | F1, F2, F3, F4, F5: Eliminate "Lan Anh", turn-1 greeting only, closing question rotation, fault-tolerant gender detection, combo/medical grounding, phone lead capture & pitch silencing | None | DONE |
| M2 | Redesign of "Báo Sai & Dạy AI" & AI Playbook (R3) | F6, F7, F8, F9, F10: Fix JDBC parameter binding in `LearnedRuleRepositoryCustomImpl`, faithful rule extraction, `applied_rules` in Fanpage Bot, Simulator modal integration, Playbook page/global scoping | Interface Contract M1 ↔ M2 | DONE |
| M3 | Unified Multi-Provider LLM Configuration via `.env` (R4) | F11, F12, F13, F14: `OpenAiCompatibleLlmClient`, `LlmProperties`, `LlmConfiguration`, `.env` templates, mock unit tests | None | DONE |
| M-E2E | Opaque-Box E2E Testing Track | F15: Requirement-driven E2E test runner and test cases (Tiers 1-4) derived from `ORIGINAL_REQUEST.md`; publish `TEST_READY.md` | None (Derives from ORIGINAL_REQUEST.md) | DONE |
| M-Final | Final Integration, E2E Pass & Forensic Audit | F16: 100% E2E test pass across Java, Python, Vue, Docker stack live testing, Tier 5 adversarial tests, Forensic Integrity Audit | M1, M2, M3, M-E2E | DONE |

---

## Interface Contracts

### 1. Fanpage Bot Message Contract (`/api/bot/fanpage/message`)

- **Request**:
  ```json
  {
    "page_id": "uuid (optional)",
    "sender_id": "string (optional)",
    "recipient_id": "string (optional)",
    "message": "string (required)",
    "history": [
      {
        "role": "user | assistant",
        "content": "string"
      }
    ]
  }
  ```
- **Response**:
  ```json
  {
    "page_id": "uuid",
    "reply_text": "string (direct text)",
    "reply_messages": ["string (split bubbles)"],
    "sales_stage": "GREETING | DISCOVERY | VALUE_CONSULTATION | OBJECTION_HANDLING | CLOSING | HANDOFF",
    "needs_human_handoff": true | false,
    "handoff_reason": "string (e.g. 'Customer provided phone number: 0968221644')",
    "consultation_profile": {
      "customer_name": "string (null if not self-introduced)",
      "customer_gender": "MALE | FEMALE | UNKNOWN",
      "preferred_honorific": "Anh | Chị | Bạn",
      "phone_number": "string",
      "budget_range": "string"
    },
    "applied_rules": [
      {
        "id": "uuid",
        "category": "string",
        "generalized_rule": "string",
        "trigger_condition": "string"
      }
    ]
  }
  ```

### 2. AI Playbook & Feedback Contract

#### Rules CRUD: `/api/rules`
- `GET /api/rules`: Returns array of `LearnedRuleDto`, supports query params `page_id=uuid|null`, `active=true|false`, `search=string`, `category=string`.
- `POST /api/rules`: Create rule with `{ category, trigger_condition, root_cause, generalized_rule, retrieval_keywords, active, page_id }`.
- `PUT /api/rules/{id}`: Update rule or toggle `active` status, optionally updating `page_id`.
- `DELETE /api/rules/{id}`: Delete rule by ID.

#### Feedback Submission: `POST /api/feedback`
- Request:
  ```json
  {
    "question": "string (required)",
    "faulty_answer": "string (required)",
    "user_correction": "string (required)",
    "document_ids": ["uuid"] (optional),
    "page_id": "uuid" (optional),
    "source": "CHAT | FANPAGE_BOT" (optional)
  }
  ```
- Response:
  ```json
  {
    "learned_rule": {
      "id": "uuid",
      "page_id": "uuid (nullable)",
      "category": "string",
      "root_cause": "string",
      "generalized_rule": "string",
      "active": true
    },
    "regenerated_answer": "string",
    "applied_rules": [ ... ]
  }
  ```

### 3. Unified Multi-Provider LLM Configuration (`.env` / `application.properties`)

- `LLM_PROVIDER`: `ollama` | `deepseek` | `grok` | `openai` | `gemini` | `openrouter` | `custom` | `stub`
- `LLM_BASE_URL`: provider endpoint (e.g. `https://api.deepseek.com/v1`, `https://api.x.ai/v1`, `https://api.openai.com/v1`, `https://generativelanguage.googleapis.com/v1beta/openai`)
- `LLM_API_KEY`: API authentication key (Bearer token)
- `LLM_MODEL`: model identifier (e.g. `deepseek-chat`, `grok-2-latest`, `gpt-4o-mini`, `gemini-2.5-flash`, `qwen2.5:7b`)
- `LLM_TEMPERATURE`: default `0.1`
- `LLM_TIMEOUT_SECONDS`: default `60`

---

## Code Layout & File Boundaries

- **Milestone M1 (Fanpage Bot & Lead Capture)**:
  - `api-java/src/main/java/com/example/docmind/service/bot/FanpageBotPromptBuilder.java`
  - `api-java/src/main/java/com/example/docmind/service/bot/FanpageBotService.java`
  - `api-java/src/test/java/com/example/docmind/service/bot/FanpageBotServiceTest.java`
- **Milestone M2 (Training AI & AI Playbook)**:
  - `api-java/src/main/resources/db/migration/V6__feedback_page_id.sql`
  - `api-java/src/main/java/com/example/docmind/repository/LearnedRuleRepositoryCustomImpl.java`
  - `api-java/src/main/java/com/example/docmind/service/MetaLearningService.java`
  - `api-java/src/main/java/com/example/docmind/controller/FeedbackController.java`
  - `api-java/src/main/java/com/example/docmind/dto/bot/FanpageBotResponse.java`
  - `frontend/src/components/FanpageBotSimulatorModal.vue`
  - `frontend/src/components/TeachAiModal.vue`
  - `frontend/src/components/PlaybookModal.vue`
  - `frontend/src/mockData.js`
- **Milestone M3 (Multi-Provider LLM Client)**:
  - `api-java/src/main/java/com/example/docmind/config/LlmProperties.java`
  - `api-java/src/main/java/com/example/docmind/config/LlmConfiguration.java`
  - `api-java/src/main/java/com/example/docmind/service/llm/OpenAiCompatibleLlmClient.java`
  - `api-java/src/main/resources/application.properties`
  - `api-java/src/test/resources/application-test.properties`
  - `api-java/src/test/java/com/example/docmind/service/llm/OpenAiCompatibleLlmClientTest.java`
  - `docmind/.env`
  - `docmind/.env.example`
  - `docmind/docker-compose.yml`
- **Milestone M-E2E (E2E Testing Track)**:
  - `TEST_INFRA.md`
  - `TEST_READY.md`
  - `tests_e2e/` (or dedicated automated test script)
