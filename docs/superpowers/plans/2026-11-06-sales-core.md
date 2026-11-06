# Sales Core implementation

Goal: thay đường quyết định tư vấn bằng core chung có industry pack, dữ kiện có nguồn và kiểm chứng hai ngành.
Architecture: guarded ingress → scoped evidence/profile → model decision + language → contract validator (one repair) → existing delivery.
Tech stack: Java 21, Spring, Jackson, JUnit, JSON resources; giữ hạ tầng hiện có.
Spec: ../specs/2026-11-06-sales-core-design.md
Global constraints: không push/deploy; không ngành/tình huống hardcode trong core; không xác nhận giao dịch giả; không sửa dữ liệu thật; không xóa hàng rào delivery.
Review focus: routing thực, nguồn dữ kiện/Page, giới hạn quyền, deadline, schema/prompt, tests khác fixture lời thoại.

## Task 1: core contracts, profiles, prompt, validation

Files: `api-java/src/main/java/com/example/docmind/service/salescore/*`; `api-java/src/main/resources/sales/*`; `api-java/src/test/java/com/example/docmind/service/salescore/*`.
Interfaces: TurnInput → TurnDecision/Result, IndustryProfile, BusinessProfile, Evidence.
RED: test profile isolation, question preservation, grounded facts, fabricated references/numbers, unsupported transaction, malformed JSON, bounded repair. Expected: absent new classes/failing cases.
GREEN: implement only universal policy + typed data; external industry packs. Expected: new unit tests pass.

## Task 2: runtime integration and scoped data

Files: FanpageBotService, SalesCoreGateway, configuration, integration tests.
Interfaces: consumes core Result; returns existing FanpageBotResponse, retains outer fences/ledger.
RED: test active route through public service, explicit document ownership, no old sanitizer, no cross-page context. Expected: route/data failures before wiring.
GREEN: wire before legacy planning; bounded context; config rollback. Expected: integration + old regression tests pass.

## Task 3: replay and handover

Files: standalone replay test/harness, docs/sales-core.md, validation evidence.
Interfaces: same production orchestrator/profile, synthetic fixtures only.
Run targeted and full offline suite, controlled provider replay if available, inspect actual transcripts for customer understanding, relevance, repetition and next step. Expected: evidence, not a claim based solely on labels. Record failures and limitations. Review full diff and UTF-8 before handover.

## Execution rulings / progress

- User explicitly authorized both planning and implementation. Proceed locally without another design permission gate; no deploy.
- No callable subagent tool is available; self-review each boundary. Keep changes in current workspace; no git commit is required for this task.
- Tasks 1→2 share TurnInput/Result; public response contract remains compatible. Tasks 2→3 use the exact production core, no alternative test-only planner.
- Initial evidence: old budget question is deleted by repeat-price sanitizer; price objection decision imposed before model. This is the regression target.
- Full-suite first verification: 1040 tests, 17 failures, 3 errors, 8 skipped; all 17 new offline tests passed. Several legacy tests require a fixed Page absent from local PostgreSQL, others assert prior currency formatting/planner behavior. Need baseline comparison before attributing these failures.
- Baseline comparison completed in isolated HEAD archive: 1022 tests, 17 failures, 3 errors, 7 skipped. Exactly the same 20 failed/error method names as modified tree (zero differences). Logs under artifacts/sales-core-baseline-suite.log and artifacts/sales-core-full-verified.log. No legacy assertions weakened.
- Implementation and handover complete locally: core/industry packs/runtime route/rollout flags/docs. Final package check: 56/56 related tests pass (31 new/snapshot + 25 Stage1/Stage2/ledger regressions); frontend build passes. No deploy/push.
- Live evidence: 12 synthetic turns across spa/rental without contract fallback; 6 turns using actual Page snapshot without fallback after fixing heading evidence and price priority. Quality warnings remain, mean latency ~18.5s synthetic/~20.7s Page snapshot. Do not label production-ready or claim conversion improvement. Default route disabled, Page allowlist available for controlled pilot. Details: docs/analysis/2026-11-07-sales-core-validation.md.
