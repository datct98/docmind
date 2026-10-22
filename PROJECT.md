# Project: DocMind Obsidian Knowledge Graph & Adaptive Reasoning Upgrade

## Architecture

DocMind is an enterprise Sales Document Q&A, Fanpage Conversational Bot, and Self-Improving AI platform upgraded with Obsidian's knowledge graph principles and adaptive reasoning:
1. **Document Ingestion & Parsing (`doc-service-py`)**:
   - High-fidelity AST parsing for `.docx`, `.xlsx`, `.md`, and HTML tables.
   - Non-destructive table structure preservation: preserves full column headers, forward-fills merged hierarchical grouping cells without data loss, and segments large sheets into bounded row windows with header replication.
2. **Knowledge Graph & Subpath Indexing (`api-java`)**:
   - `ObsidianMetadataCache`: Implements Obsidian's core `resolveSubpath` heading-inheritance principles (`Sheet/Title > Category > Section`).
   - Hybrid Full-Text & Dense Embedding: Indexes `heading_path` with PostgreSQL `to_tsvector` (Weight A) + `text` (Weight B) and heading-prefixed vector embeddings.
   - Bidirectional Entity Graph (`BacklinkGraph`): Links chunks across `PRICE_TABLE`, `FAQ`, `PROCESS`, and `PROMO` sharing package codes (`NS01`, etc.) or price points.
   - Map of Content (`MapOfContentService`): Dynamically builds a compact Page-level MOC summary in `<DOCUMENT_CONTEXT>` showing categories, branches, and exact min–max price ranges.
   - Stateful `PriceLadderTracker`: Parses `<CONVERSATION_HISTORY>` to track quoted prices, orchestrating monotonic down-sell steps (`399.000` → `239.000` / `129.000` + `10%` discount) without price oscillation or false human handoffs.
3. **Adaptive Reasoning & Empathy Guardrails (`api-java`)**:
   - `QueryComplexityClassifier` & `ReasoningBudget`: Routes simple queries to fast responses (4–8s) while allocating deep deliberation budgets (up to 20–30s) for complex objections and comparisons.
   - Empathetic Vietnamese Consultative Tone (`Em - Chị/Anh`): 100% price/factual grounding, zero leaked internal syntax (`[[...]]`, `[1]`, `#`, `**`, table pipes).
4. **Data Backfill & Live Synchronization (`docmind-postgres` & Docker)**:
   - Non-destructive Flyway migration (`V14`) and `ChunkMetadataBackfillService` enriching existing chunks (including Page "Nàng sen") with `tsv` heading paths and dynamic metadata without data loss.

---

## Feature Inventory

Every requirement from `ORIGINAL_REQUEST.md` (`## 2026-10-21T02:21:29Z`) and `DISPATCH.md` is cataloged below with its assigned milestone:

| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| F1 | Non-Destructive Table Preservation | Preserve 100% table structures, column headers, and sheet boundaries in `CanonicalBlock` and `ChunkEntity`; duplicate markdown headers across chunk splits | M1 | Survey R1 |
| F2 | Hierarchical Heading Path Indexing | Layer `resolveSubpath` heading inheritance on every chunk; index `heading_path` (Weight A) + `text` (Weight B) in PostgreSQL `tsv` and vector embeddings | M1 | Survey R1 |
| F3 | Centralized Vietnamese Query Normalizer | Normalize Messenger abbreviations (`ko`/`k` → `không`, `bn` → `bao nhiêu`, `sdt`...) and Telex typos (`muosn` → `muốn`) at query entry point | M1 | Survey R1 |
| F4 | Dynamic ChunkMetadata Extraction | Automatically extract `min_price`, `max_price`, `branch_tags`, `service_codes`, and `chunk_role` (`PRICE_TABLE`, `PROMO`, `FAQ`, `PROCESS`) without hardcoded package codes | M1 | Survey R1 |
| F5 | Bidirectional Backlinks Entity Graph | Cross-chunk backlink resolution linking package chunks to relevant FAQ comparisons, process steps, and promo discounts sharing codes/prices | M2 | Survey R2 |
| F6 | Dynamic Map of Content (MOC) | Compact Page-level MOC summary in `<DOCUMENT_CONTEXT>` showing all categories, branches, and exact min–max price ranges | M2 | Survey R2 |
| F7 | Stateful PriceLadderTracker | Extract previously quoted prices from `<CONVERSATION_HISTORY>` to guide sequential objections and down-sells (e.g. 399k → 239k → 129k + 10% promo) with 0 false handoff | M2 | Survey R2 |
| F8 | Adaptive Deep Reasoning Engine | Allocate dynamic reasoning budgets: 4–8s for simple turns vs up to 20–30s for complex multi-package objections and comparisons | M3 | Survey R3 |
| F9 | Empathetic Vietnamese Tone & Zero Syntax Leak | Strict `Em - Chị/Anh` consultative voice; clean Obsidian wikilinks `[[...]]`, markdown tables, citations, and internal rule IDs | M3 | Survey R3 |
| F10 | Non-Destructive DB Backfill | Flyway `V14` migration and `ChunkMetadataBackfillService` to update `tsv` and metadata for all existing Page "Nàng sen" chunks with 0 data loss | M4 | Survey R4 |
| F11 | Live Docker Synchronization | Container rebuild (`docmind-api`, `docmind-doc-service`, `docmind-frontend`) via `docker compose up -d --build` with `-DskipTests` and health verification | M4 | Survey R4 |
| F12 | Opaque-Box E2E Acceptance Test Suite | Comprehensive test suite (Tiers 1-4) derived from user requirements covering Page "Nàng sen" 5-turn probe, branch queries, FAQ comparisons | M-E2E | E2E Testing Track |
| F13 | Final Integration, Tier 5 Hardening & Forensic Audit | Verification across all test suites, live API checks, white-box adversarial coverage hardening (Tier 5), and independent Forensic Integrity Audit | M-Final | Implementation Track |

---

## Milestones

| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Non-Destructive Table Preservation & Obsidian MetadataCache/Subpath Indexing | F1, F2, F3, F4: Table preservation in parser/chunker, PostgreSQL `tsv` heading_path indexing, Vietnamese query normalizer, dynamic ChunkMetadata | None | DONE |
| M2 | Bidirectional Entity Graph (Backlinks), MOC & Stateful PriceLadderTracker | F5, F6, F7: `ObsidianMetadataCache` backlink resolution, dynamic Page-level MOC in `<DOCUMENT_CONTEXT>`, `PriceLadderTracker` | M1 | DONE |
| M3 | Adaptive Deep Reasoning (4–30s) & Empathetic Vietnamese Consultative Tone | F8, F9: Query complexity classifier, reasoning latency budget, wikilink & table syntax stripping, `SyntaxSanitizationHook` | M2 | DONE |
| M4 | Non-Destructive Database Backfill & Live Docker Synchronization | F10, F11: Flyway migration `V14`, `ChunkMetadataBackfillService`, Docker rebuild with `-DskipTests`, health verification | M1, M2, M3 | DONE |
| M-E2E | Opaque-Box E2E Testing Track | F12: Requirement-driven E2E test runner and test cases (Tiers 1-4); publish `TEST_READY.md` | None (Derives from ORIGINAL_REQUEST.md) | DONE |
| M-Final | Final Integration, 100% E2E Pass, Tier 5 Hardening & Holistic Forensic Audit | F13: 100% E2E test pass across Java, Python, Vue, Docker stack live testing, Tier 5 adversarial tests, Forensic Integrity Audit | M1, M2, M3, M4, M-E2E | DONE |

---

## Interface Contracts

### 1. Dynamic ChunkMetadata Contract
```java
public record ChunkMetadata(
    String chunkRole,       // PRICE_TABLE | PROMO | FAQ | PROCESS | GENERAL
    Long minPrice,          // e.g. 129000L
    Long maxPrice,          // e.g. 1990000L
    List<String> branchTags,// e.g. ["my_dinh", "quan_1", "bich_cau", "long_bien"]
    List<String> serviceCodes // e.g. ["NS01", "DS01", "DS02"]
) {}
```

### 2. Map of Content (MOC) Contract
Injected into `<DOCUMENT_CONTEXT>`:
```text
=== MAP OF CONTENT (MOC) ===
- DỊCH VỤ MASSAGE:
  * Chi nhánh Hà Nội (Mỹ Đình, Bích Câu, Long Biên): 399.000 VNĐ - 1.990.000 VNĐ (NS01 - NS07)
  * Chi nhánh TP.HCM (Quận 1): 570.000 VNĐ - 2.150.000 VNĐ (NS01 - NS06)
- GỘI ĐẦU DƯỠNG SINH:
  * Chi nhánh Hà Nội: 129.000 VNĐ - 990.000 VNĐ (DS01 - DS06)
  * Chi nhánh TP.HCM (Quận 1): 129.000 VNĐ - 1.100.000 VNĐ (DS01 - DS06)
- CHÍNH SÁCH ƯU ĐÃI:
  * Giảm 10% khi mua liệu trình từ 5 buổi hoặc từ 5 voucher.
============================
```

### 3. Stateful PriceLadderTracker Contract
```java
public record PriceLadderResult(
    boolean isPriceObjection,
    boolean isTrialRequest,
    Long previousPriceQuoted,
    Long targetPrice,
    String targetPackageName,
    String discountPolicy,
    boolean isCatalogFloor,
    String rationale
) {}
```

### 4. Adaptive Reasoning Budget Contract
```java
public enum QueryComplexity {
    SIMPLE(4_000, 8_000, "low"),
    DEEP_REASONING(15_000, 30_000, "medium");

    private final int minLatencyMs;
    private final int maxLatencyMs;
    private final String reasoningEffort;
}
```

---

## Code Layout & File Boundaries

- **Milestone M1 (Table Preservation & Subpath Indexing)**:
  - `docmind/doc-service-py/app/engines/office_parser.py`
  - `docmind/api-java/src/main/java/com/example/docmind/service/ChunkBuilder.java`
  - `docmind/api-java/src/main/java/com/example/docmind/repository/ChunkRepositoryCustomImpl.java`
  - `docmind/api-java/src/main/java/com/example/docmind/service/DocumentService.java`
  - `docmind/api-java/src/main/java/com/example/docmind/service/text/VietnameseQueryNormalizer.java`
  - `docmind/api-java/src/main/java/com/example/docmind/service/metadata/ChunkMetadataExtractor.java`
  - `docmind/api-java/src/main/java/com/example/docmind/dto/ChunkMetadata.java`

- **Milestone M2 (Backlinks, MOC & PriceLadderTracker)**:
  - `docmind/api-java/src/main/java/com/example/docmind/service/graph/ObsidianMetadataCache.java`
  - `docmind/api-java/src/main/java/com/example/docmind/service/graph/MapOfContentService.java`
  - `docmind/api-java/src/main/java/com/example/docmind/service/sales/PriceLadderTracker.java`
  - `docmind/api-java/src/main/java/com/example/docmind/service/retrieval/RetrievalService.java`
  - `docmind/api-java/src/main/java/com/example/docmind/service/bot/FanpageBotPromptBuilder.java`
  - `docmind/api-java/src/main/java/com/example/docmind/service/bot/FanpageBotService.java`

- **Milestone M3 (Adaptive Reasoning & Tone)**:
  - `docmind/api-java/src/main/java/com/example/docmind/service/reasoning/QueryComplexityClassifier.java`
  - `docmind/api-java/src/main/java/com/example/docmind/service/llm/ReasoningBudget.java`
  - `docmind/api-java/src/main/java/com/example/docmind/service/llm/OpenAiCompatibleLlmClient.java`
  - `docmind/api-java/src/main/java/com/docmind/guardrail/SyntaxSanitizationHook.java`
  - `docmind/api-java/src/main/java/com/docmind/guardrail/GuardrailPipeline.java`

- **Milestone M4 (DB Backfill & Live Docker Sync)**:
  - `docmind/api-java/src/main/resources/db/migration/V14__obsidian_metadata_cache_backfill.sql`
  - `docmind/api-java/src/main/java/com/example/docmind/service/metadata/ChunkMetadataBackfillService.java`
  - `docmind/docker-compose.yml`

- **Milestone M-E2E (E2E Testing Track)**:
  - `docmind/TEST_INFRA.md`
  - `docmind/TEST_READY.md`
  - `docmind/tests_e2e/test_obsidian_adaptive_acceptance.py`
