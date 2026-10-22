# DocMind Obsidian Knowledge Graph & Adaptive Reasoning Upgrade — Test Infrastructure

## 1. Test Philosophy & Design Principles

The end-to-end test infrastructure for DocMind is architected on four core testing principles:

1. **Strictly Opaque-Box & Requirement-Driven**:
   - All assertions, expected values, budgets, and state transitions derive exclusively from the authoritative user specifications in `ORIGINAL_REQUEST.md` (`## 2026-10-21T02:21:29Z`) and the architectural contract definitions in `docmind/PROJECT.md`.
   - Zero coupling to internal Java or Python class names, private method signatures, or temporary implementation details. All tests interact via public REST APIs (`/api/bot/fanpage/message`, `/api/documents/parse`, `/api/chat`), database state, or canonical schemas (`docs/canonical-schema.md`).

2. **Progressive Testability & Milestone Decoupling**:
   - Verification tracks evaluate features systematically across milestones (M1: Table Preservation & Subpath Indexing, M2: Backlinks, MOC & PriceLadder, M3: Adaptive Reasoning & Tone, M4: DB Backfill & Docker Sync).
   - Tests do not assume mock shortcuts; they measure real system outputs, latencies, and schemas.

3. **Empirical Precision & Grounded Oracles**:
   - All expected pricing, service durations, branch catalogs, and FAQ resolutions are anchored directly in the verified knowledge base of Page **"Nàng sen"** (`2850a8ef-ca4a-40eb-87fa-4f808640efee`):
     * Hà Nội Massage NS01: `399.000 VNĐ` (60 phút full body).
     * Sài Gòn (Quận 1) Massage NS01: `570.000 VNĐ` (70 phút).
     * Hà Nội Dưỡng sinh DS02: `239.000 VNĐ` (60 phút).
     * Catalog Floor DS01: `129.000 VNĐ` (30 phút lấy ráy tai).
     * Sài Gòn Dưỡng sinh DS01: `129.000 VNĐ`, DS02: `260.000 VNĐ`.
     * Promo Policy: Giảm 10% khi mua từ 5 buổi hoặc từ 5 voucher.
     * FAQ Comparison 670k vs 780k: 780k có phòng VIP riêng khép kín, gội dưỡng sinh tại phòng không cần di chuyển giữa các bước.

4. **Adversarial & Boundary Rigor**:
   - Every input path exercises malformed Vietnamese abbreviations, extreme price queries (0 VNĐ to 100M VNĐ), prompt injection traps, and rapid bursts.

---

## 2. Feature Inventory & Coverage Mapping

| # | Feature Code | Feature Name | Source Requirement | Tier 1 (Coverage >=5) | Tier 2 (Boundary >=5) | Tier 3 (Cross-Feature) | Tier 4 (Real-World) |
|---|--------------|--------------|--------------------|:---------------------:|:---------------------:|:----------------------:|:-------------------:|
| 1 | **F1** | Non-Destructive Table Preservation | Survey R1, M1 | 5 tests | 5 tests | Pairwise with F2, F4 | Scenario 1, 6 |
| 2 | **F2** | Hierarchical Heading Path Indexing (`resolveSubpath`) | Survey R1, M1 | 5 tests | 5 tests | Pairwise with F3, F6 | Scenario 1, 6 |
| 3 | **F3** | Centralized Vietnamese Query Normalizer | Survey R1, M1 | 5 tests | 5 tests | Pairwise with F7 | Scenario 2 |
| 4 | **F4** | Dynamic ChunkMetadata Extraction | Survey R1, M1 | 5 tests | 5 tests | Pairwise with F5 | Scenario 6, 7 |
| 5 | **F5** | Bidirectional Backlinks Entity Graph | Survey R2, M2 | 5 tests | 5 tests | Pairwise with F4 | Scenario 7 |
| 6 | **F6** | Dynamic Map of Content (MOC) | Survey R2, M2 | 5 tests | 5 tests | Pairwise with F2 | Scenario 1, 6 |
| 7 | **F7** | Stateful PriceLadderTracker | Survey R2, M2 | 5 tests | 5 tests | Pairwise with F3 | Scenario 3, 4, 5 |
| 8 | **F8** | Adaptive Deep Reasoning Engine (4-8s vs 15-30s) | Survey R3, M3 | 5 tests | 5 tests | Pairwise with F7 | Scenario 1, 3, 4 |
| 9 | **F9** | Empathetic Vietnamese Tone & Zero Syntax Leak | Survey R3, M3 | 5 tests | 5 tests | Pairwise with Guardrails | Scenario 1 - 7 |
| 10 | **F10** | Non-Destructive DB Backfill | Survey R4, M4 | 5 tests | 5 tests | Pairwise with Schema | Scenario 1, 6 |
| 11 | **F11** | Live Docker Synchronization | Survey R4, M4 | 5 tests | 5 tests | Pairwise with Health | Scenario 1 - 7 |
| 12 | **F12** | Opaque-Box E2E Acceptance Suite | E2E Testing Track | Full Suite | Full Suite | Full Suite | Full Suite |
| 13 | **F13** | Integration & Forensic Audit | Implementation Track | Audit Checks | Audit Checks | Audit Checks | Audit Checks |

---

## 3. Test Architecture & Runner Invocation

### Architecture Diagram
```text
+---------------------------------------------------------------------------------+
|                       E2E Test Runner (pytest / standalone)                     |
|            docmind/tests_e2e/test_obsidian_adaptive_acceptance.py               |
+---------------------------------------------------------------------------------+
        |                                                                 |
        | HTTP (REST JSON)                                                | SQL / Subprocess
        v                                                                 v
+-------------------------------+                       +-------------------------+
|     docmind-api (:8090)       |                       |   docmind-postgres      |
|  POST /api/bot/fanpage/message|                       |      (:5433)            |
|  POST /api/documents/parse    |                       | SELECT ... FROM chunks  |
|  GET  /api/health             |                       | heading_path, tsv       |
+-------------------------------+                       +-------------------------+
        |                                                                 ^
        |                                                                 |
        v                                                                 |
+-------------------------------+-----------------------------------------+
|   Obsidian Knowledge Graph    |  BacklinkGraph, MOC, PriceLadderTracker |
+-------------------------------+-----------------------------------------+
```

### Environment Configuration
The test suite respects standard environment overrides:
- `API_JAVA_URL`: Target API endpoint (default: `http://localhost:8090`).
- `PAGE_ID`: Active test Fanpage ID (default: `2850a8ef-ca4a-40eb-87fa-4f808640efee` — Page "Nàng sen").
- `DOCMIND_E2E_MODE`: `live` (calls running Docker stack) or `contract` (uses internal reference validator).

### Invocation Commands

1. **Full Acceptance Test Suite (All Tiers)**:
   ```bash
   python -m pytest docmind/tests_e2e/test_obsidian_adaptive_acceptance.py -v
   ```

2. **Standalone Execution (Zero-dependency directly via Python)**:
   ```bash
   python docmind/tests_e2e/test_obsidian_adaptive_acceptance.py
   ```

3. **Specific Tier Execution via Pytest Markers**:
   ```bash
   # Run only Tier 1: Feature Coverage
   python -m pytest docmind/tests_e2e/test_obsidian_adaptive_acceptance.py -k "tier1" -v

   # Run only Tier 2: Boundary & Corner Cases
   python -m pytest docmind/tests_e2e/test_obsidian_adaptive_acceptance.py -k "tier2" -v

   # Run only Tier 3: Pairwise Combinations
   python -m pytest docmind/tests_e2e/test_obsidian_adaptive_acceptance.py -k "tier3" -v

   # Run only Tier 4: Real-World 5-Turn Probe on Page 'Nàng sen'
   python -m pytest docmind/tests_e2e/test_obsidian_adaptive_acceptance.py -k "tier4" -v
   ```

---

## 4. 4-Tier Test Methodology

### Tier 1: Feature Coverage (Isolation, >=5 Tests Per Feature)
- **F1: Table Preservation**:
  * T1.1.1: Preserve Markdown pipe syntax (`| ... |`) and column alignments.
  * T1.1.2: Replicate column headers across split chunks for long tables.
  * T1.1.3: Maintain cell value pairings without collapsing into comma-delimited strings.
  * T1.1.4: Forward-fill merged hierarchical category cells without data loss.
  * T1.1.5: Verify multi-sheet HTML/Excel parsing retains table block types.
- **F2: Hierarchical Heading Path Indexing**:
  * T1.2.1: Full inheritance breadcrumb (`Sheet > Category > Section`).
  * T1.2.2: Heading-path search matches with Weight A in PostgreSQL tsvector.
  * T1.2.3: Hybrid search combining heading path + table cell text.
  * T1.2.4: Resolution of branch tags from heading paths (`Cơ sở Nàng Sen Quận 1`).
  * T1.2.5: Graceful fallback for root chunks lacking parent headings.
- **F3: Messenger Abbreviation & Typo Normalizer**:
  * T1.3.1: Telex typo correction (`muosn` -> `muốn`).
  * T1.3.2: Shorthand normalization (`ko`, `k` -> `không`).
  * T1.3.3: Price/quantity shorthand (`bn` -> `bao nhiêu`).
  * T1.3.4: Contact inquiry shorthand (`sdt`, `đt` -> `số điện thoại`).
  * T1.3.5: Mixed abbreviations in single sentence (`"mk muosn hoi gia bn k dat"`).
- **F4: Dynamic ChunkMetadata Extraction**:
  * T1.4.1: Min and max price extraction from table columns.
  * T1.4.2: Branch tag identification from text and headers (`my_dinh`, `quan_1`).
  * T1.4.3: Service code identification (`NS01`, `DS01`, `DS02`).
  * T1.4.4: Role classification for pricing tables (`PRICE_TABLE`).
  * T1.4.5: Role classification for Q&A tables (`FAQ`).

### Tier 2: Boundary & Corner Cases (Extreme Inputs & Robustness)
- **T2.1**: Extreme price queries: 0 VNĐ / Free service inquiry handling.
- **T2.2**: Extreme high price queries: 100M - 1B VNĐ catalog ceiling handling.
- **T2.3**: Empty string or pure whitespace message payload handling (graceful 400 or safe fallback).
- **T2.4**: Rapid burst inquiries (consecutive messages within milliseconds).
- **T2.5**: Non-existent package code inquiry (`NS99`, `DS88`) without hallucination.
- **T2.6**: Complex Vietnamese accents (mixed uppercase diacritics and unaccented inputs).
- **T2.7**: Multi-turn history scaling (extended conversation context without token overflow).

### Tier 3: Pairwise & Cross-Feature Interactions
- **T3.1**: Branch filter + price floor inquiry (`"cơ sở Quận 1 giá thấp nhất là bao nhiêu?"`).
- **T3.2**: Typo normalization + price objection (`"muosn thử nhưng cao the"`).
- **T3.3**: Dynamic branch switching across turns (Hà Nội -> Sài Gòn).
- **T3.4**: FAQ comparison combined with price resistance (`"gói 670k và 780k khác gì nhau mà đắt thế"`).
- **T3.5**: Service code linked to promotion policy (`"gói DS01 có được giảm 10% khi mua 5 buổi không?"`).

### Tier 4: Real-World Scenarios (The Page 'Nàng sen' Dialogue Probe)
Page ID: `2850a8ef-ca4a-40eb-87fa-4f808640efee`
- **Scenario 1 (Turn 1)**: `"giá"` -> quotes `399.000 VNĐ` (or `570k` Q1), latency 4–8s, concise bubbles (<= 2).
- **Scenario 2 (Turn 2)**: `"tôi muosn thử trải nghiệm trước"` -> retains package, moves to soft booking, latency 4–8s.
- **Scenario 3 (Turn 3)**: `"cao thế"` -> down-sells to `239.000 VNĐ` (60 phút), latency 15–30s (< 30s), `needs_human_handoff = false`.
- **Scenario 4 (Turn 4)**: `"nhưng hơi đắt"` -> down-sells to `129.000 VNĐ` (30 phút floor), latency 15–30s (< 30s), `needs_human_handoff = false`.
- **Scenario 5 (Turn 5)**: `"có gói nào rẻ hơn không"` -> confirms `129.000 VNĐ` is floor + offers 10% promo policy (5 buổi/voucher), `needs_human_handoff = false`.
- **Scenario 6 (Cross-Section 1)**: `"cơ sở Quận 1 Sài Gòn có gói nào?"` -> retrieves Sài Gòn packages (`570k` / `129k` / `260k`).
- **Scenario 7 (Cross-Section 2)**: `"gói 670k và 780k khác gì nhau?"` -> accurately cites FAQ regarding private VIP room shampoo service.
- **Scenario 8 (Zero Syntax Leak)**: 0 wikilinks `[[...]]`, 0 citations `[1]`, 0 markdown header hashes `#`, 0 table pipes `|` in customer bubbles.
- **Scenario 9 (Vietnamese Tone)**: 100% consistent `Em - Chị` or `Em - Anh` consultative voice, 0 phantom names.
