# DocMind E2E Test Infrastructure (M-E2E)

This document establishes the architecture, conventions, execution guide, and verification standards for the **End-to-End (E2E) Testing Track** of the DocMind Sales RAG & Self-Improving AI platform.

---

## 1. Testing Philosophy & Methodology

The DocMind E2E test suite adheres to an **opaque-box, requirement-driven testing methodology**:

1. **Opaque-Box Verification**: Tests interact exclusively through public APIs, canonical schema contracts (`docs/canonical-schema.md`), and HTTP interfaces (`/v1/parse`, `/api/chat`, `/api/feedback`, `/api/rules`). Tests do not couple to internal implementation details or private methods.
2. **Authoritative Expected Output Derivation**: Every test case derives its expected values directly from `ORIGINAL_REQUEST.md` and `PROJECT.md`.
3. **Multi-Tier Coverage**:
   - **Tier 1 (Feature Coverage)**: Individual features F1–F19 tested in isolation (minimum 5 tests per feature, $\ge 95$ tests).
   - **Tier 2 (Boundary & Corner Cases)**: Edge conditions, empty inputs, extreme file sizes, Unicode handling, malformed payloads (minimum 5 tests per feature, $\ge 95$ tests).
   - **Tier 3 (Cross-Feature Combinations)**: Pairwise and multi-service interaction flows (e.g., Excel ingestion $\to$ chunking $\to$ chat retrieval; Markdown tables $\to$ feedback loop $\to$ query expansion; conversational correction $\to$ playbook rule persistence $\to$ subsequent chat grounding).
   - **Tier 4 (Real-World Sales Scenarios)**: Realistic end-to-end sales operations with full-scale documents (pricing policy `.docx`, sales inventory `.xlsx` with 350+ rows across sheets, product catalog `.md`, and multi-turn mistake correction).
4. **Dual-Mode Execution**:
   - **Contract Simulator Mode (Default/Hermetic)**: High-speed, deterministic contract verification against specification oracles without requiring running databases or live servers. Ideal for isolated milestones and CI fast-feedback.
   - **Live Service Mode**: End-to-end integration against active FastAPI (`:8000`) and Spring Boot (`:8090`) services when booted.

---

## 2. Directory Layout

The E2E test harness and test suites reside in `tests_e2e/`:

```
d:\Project\digital-spirituality\docmind\
├── TEST_INFRA.md                       # This infrastructure specification
├── TEST_READY.md                       # Final test readiness report
└── tests_e2e\
    ├── __init__.py                     # Package marker
    ├── conftest.py                     # Pytest fixtures, hooks & configuration
    ├── run_e2e.py                      # Standalone zero-dependency executable test runner
    ├── harness\
    │   ├── __init__.py                 # Harness exports
    │   ├── models.py                   # Canonical block schema, DTOs & response models
    │   ├── client.py                   # Dual-mode E2E Client (Contract Oracle + Live HTTP)
    │   └── generators.py               # Document fixture generators (.docx, .xlsx 350+, .md)
    ├── test_tier1_feature_coverage.py  # Tier 1: F1-F19 isolated feature coverage (95+ tests)
    ├── test_tier2_boundary_corner.py   # Tier 2: F1-F19 boundary & corner cases (95+ tests)
    ├── test_tier3_cross_feature.py     # Tier 3: Pairwise cross-feature combinations
    └── test_tier4_sales_scenarios.py   # Tier 4: Real-world sales document scenarios
```

---

## 3. Test Tier Breakdown

### Tier 1: Feature Coverage (F1 to F19 in Isolation)
Minimum 5 test cases per feature covering primary behavior (Happy Path):
- **F1 (Markdown AST Parsing)**: Heading hierarchy, pipe table detection, bullet list structuring, paragraph extraction, Vietnamese UTF-8 preservation.
- **F2 (Excel Full Row Parsing)**: 200+ row processing without truncation, 350+ row ingestion, multi-sheet traversal, all non-empty row accounting, column width independence.
- **F3 (Excel Empty Boundary Stripping)**: Trailing empty row elimination, trailing empty column pruning, internal empty row preservation, whitespace cell handling, sparse matrix cleaning.
- **F4 (Excel Bounded Window Partitioning)**: 100-row window chunking, worksheet title breadcrumb propagation, row 0 header duplication, monotonic block ordering, markdown pipe formatting.
- **F5 (Python Test Suite & Fixtures)**: Test runner discovery, fixture validity, canonical schema compliance, stats breakdown accounting, error status propagation.
- **F6 (Bounded Chunk Splitting)**: 1200-char boundary enforcement, paragraph boundary splitting, heading_path inheritance, source_block_ids tracking, bounding box union preservation.
- **F7 (Markdown Table Preservation in Chunks)**: Table markdown syntax preference, pipe formatting retention, header divider preservation, chunk readability for LLM.
- **F8 (Flyway V2 Schema Migration)**: `learned_rules` table schema, `feedback_logs` table schema, pgvector 384-dim embedding column, active flag default, foreign key integrity.
- **F9 (Explicit Feedback Loop)**: `POST /api/feedback` happy path, rule extraction, DB persistence, regenerated answer grounding, applied rules return.
- **F10 (Conversational Correction Detection)**: Multi-turn correction detection, correction trigger phrases, implicit rule extraction, `newly_learned_rule` attachment in `POST /api/chat`.
- **F11 (Root-Cause Rule Extraction)**: `root_cause` identification, `generalized_rule` abstraction (stripping volatile numbers/prices), `trigger_condition` mapping, `category` categorization, `retrieval_keywords` extraction.
- **F12 (Playbook Query Expansion & Prompt Injection)**: Active rule vector retrieval, keyword query augmentation, prompt rule injection, `applied_rules` in ChatResponse.
- **F13 (Rules CRUD REST API)**: `GET /api/rules` listing, `POST /api/rules` creation, `PUT /api/rules/{id}` update and toggle active, `DELETE /api/rules/{id}` removal, search & category filtering.
- **F14 (Java Test Suite Verification)**: Chunking slice test verification, meta-learning service verification, rule repository verification, controller contract verification.
- **F15 ("Báo sai & Dạy AI" Action Workflow)**: Modal launch trigger, feedback submission payload, rule preview display, assistant response update.
- **F16 (Applied & Learned Rule Badges)**: `applied_rules` badge rendering, `newly_learned_rule` highlight card rendering, collapsible badge details, badge styling tokens.
- **F17 (AI Playbook Manager Panel/Modal)**: Rule list view, category filter, active toggle action, rule editor dialog, rule deletion confirmation.
- **F18 (Dual Mode Support)**: Live mode endpoint routing, offline demo mock data fallback, state persistence across switches, error fallback graceful handling.
- **F19 (Frontend Build Cleanliness)**: Build script invocation, bundle output presence, asset path resolution, zero fatal errors.

### Tier 2: Boundary & Corner Cases (F1 to F19 Edge Conditions)
Minimum 5 test cases per feature covering adversarial conditions, stress limits, and malformed inputs:
- Zero-byte / empty files, whitespace-only files.
- Extreme sizes (1,000+ rows, 50KB markdown strings, 500-column sheets).
- Special characters, SQL injection strings, XSS payload escaping in markdown and feedback.
- Non-standard markdown tables (ragged columns, missing separators).
- Deeply nested headings (`#` to `######` exceeding 6 levels, jumping from `h1` to `h5`).
- Volatile price stripping edge cases (e.g., "$1,200.50", "500.000 VNĐ", "15% off", relative dates).
- Duplicate feedback submissions and rule deduplication.
- Malformed UUIDs, non-existent rule IDs, 404/400 error codes.
- Network timeouts and service failure fallbacks.

### Tier 3: Cross-Feature Combinations (Pairwise Interactions)
Pairwise and end-to-end pipeline integrations:
1. **Excel Ingestion + Chunk Splitting + Retrieval**: Multi-sheet 350+ row Excel parsed, partitioned into 100-row blocks, chunked with header retention, retrieved by semantic query.
2. **Markdown Table + Prompt Injection + Chat Grounding**: Markdown product table parsed, preserved in chunk text, retrieved in chat, verified in LLM answer citations.
3. **Conversational Correction + Query Expansion**: Chat turn 1 gives wrong answer $\to$ Chat turn 2 user corrects $\to$ auto-learns rule $\to$ Chat turn 3 question expanded with `retrieval_keywords` $\to$ correct answer.
4. **Explicit Feedback + Playbook Rule Toggle + Chat Verification**: User submits feedback via `/api/feedback` $\to$ rule created $\to$ rule toggled inactive via `/api/rules/{id}` $\to$ verified NOT applied in chat $\to$ toggled active $\to$ verified applied.
5. **Document Ingestion + Rule Injection + Citation Tracking**: Uploading document, generating chunks, querying with active playbook rule, verifying both `citations` and `applied_rules` in response.

### Tier 4: Real-World Sales Scenarios
Realistic end-to-end sales enterprise workflows:
1. **Scenario 1: Pricing Policy `.docx`**:
   - Ingests sales pricing policy document with tiered volume discounts, reseller margins, and regional rules.
   - Verifies canonical block extraction with heading paths.
   - Queries system on volume discount rules; verifies grounded response.
2. **Scenario 2: Large Sales Inventory `.xlsx` (350+ Rows across Sheets)**:
   - Ingests multi-sheet sales workbook with "Điện Thoại" (200 rows), "Laptop & Tablet" (150 rows), "Phụ Kiện" (100 rows).
   - Verifies zero truncation, 100-row window partitioning, sheet name breadcrumbs.
   - Queries specific SKU stock counts and prices; verifies precise retrieval.
3. **Scenario 3: Product Catalog `.md`**:
   - Ingests nested markdown catalog with detailed specifications, tables, bullet points.
   - Verifies structured parsing and markdown table preservation in chunks.
   - Multi-turn conversational inquiry.
4. **Scenario 4: Multi-Turn Sales Error Correction & Meta-Learning**:
   - AI answers sales commission question incorrectly based on outdated row.
   - User corrects: "Theo chính sách Q4, chiết khấu đại lý cấp 1 đạt doanh số trên 500 triệu là 18%, không phải 15%".
   - Verifies meta-rule extracted without hardcoded 500M or 18% values (abstracts to "Kiểm tra chính sách theo quý hiện tại và ngưỡng doanh số bậc cao nhất").
   - Subsequent question on Q4 commission applies the rule and returns accurate result.

---

## 4. How to Run the Tests

### Option A: Standalone Zero-Dependency Runner (Recommended)
The standalone runner requires only standard Python:

```bash
# Run all tiers (Tiers 1-4)
python tests_e2e/run_e2e.py

# Run a specific tier
python tests_e2e/run_e2e.py --tier 1
python tests_e2e/run_e2e.py --tier 2
python tests_e2e/run_e2e.py --tier 3
python tests_e2e/run_e2e.py --tier 4

# Filter by feature ID
python tests_e2e/run_e2e.py --feature F1
python tests_e2e/run_e2e.py --feature F11

# Run in Live Service Mode (requires running doc-service-py and api-java)
python tests_e2e/run_e2e.py --mode live
```

### Option B: Pytest Runner
If `pytest` is installed in your Python environment:

```bash
# Run the entire E2E test suite
pytest tests_e2e -v

# Run by tier
pytest tests_e2e/test_tier1_feature_coverage.py -v
pytest tests_e2e/test_tier2_boundary_corner.py -v
pytest tests_e2e/test_tier3_cross_feature.py -v
pytest tests_e2e/test_tier4_sales_scenarios.py -v

# Run with keyword filter
pytest tests_e2e -k "F11 or F12"
```

---

## 5. Verification & Acceptance Criteria
- **Pass Rate**: 100% of tests must pass.
- **Coverage**: All features F1–F19 covered with $\ge 5$ tests each in Tier 1 and $\ge 5$ tests each in Tier 2.
- **Contract Fidelity**: Canonical block schema, REST DTOs, and meta-learning constraints validated against authoritative specifications.
