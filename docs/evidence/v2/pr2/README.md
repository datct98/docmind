# PR-2 — Goal-first state and recovery (PARTIAL)

Baseline: PR-1 `57f0c46`. Runtime default still uses the existing planner route. The unified decision+wording path is experimental (`sales.core.goal-first-enabled=false` by default); no quality acceptance or release is claimed.

Implemented: durable quoted goal/open loops/concern/hypothesis/engagement alongside facts; current-customer evidence for pause/resume; independent review for the prior listening shortcut; source-preserving recovery; interruption-aware configured provider fallback; strict JSON envelope handling; malformed optional memory cannot erase a usable response. A domain-neutral lexical evidence window selects complete source records, marks truncation and favors current customer corrections over assistant text. No semantic-embedding claim is made. For the experimental envelope, the server resolves selected evidence IDs to immutable full source records; an independent semantic review must still validate entailment/scope/rights. Numerical and customer-source validators remain active.

Actual verification:
- `mvn -o -f api-java/pom.xml -Dtest=Sales*Test,ChunkMetadataBackfillService*Test test`, exit 0: 112 tests, 0 failures/errors, 2 skipped. This run includes the pending PR-5 backfill subset; PR-2 tests cover source swaps, tenant references, malformed optional JSON, reviewer rejection, cancellation, memory and delivery. Log: `artifacts/v2-pr2/unit-final.log`.
- Earlier full-source model ablations failed and were interrupted after reproducible failures. Their artifacts were retained. The complete 10-scenario/26-turn run `unified-replay-3` failed: 10 fallbacks, p50 18,255ms, p95 45,014ms, mean 2.19 model calls. This is NOT a passing benchmark. Compact full transcripts: `failed-ablation-transcripts.json`; raw calls: `artifacts/v2-pr2/unified-replay-3/replay.json`.
- That run exposed a recovery-envelope defect and citation retyping failures. Both were corrected after the run. The canonical-reference rerun is in progress and will be recorded separately, never overwriting that failure evidence.
- Production-model providers are unchanged; selected writer remains the already configured ALTERNATE with resilient primary fallback. No new agent or trained model was created.

Observed before/after: the PR-0 baseline continued asking after STOP; interim replay now can return PAUSE and a short close. Some current variants still time out. An earlier candidate recommended too many packages and had a regional/branch ambiguity despite a QUALITY review warning. These prevent declaring improved sales quality or accepting the reviewer as truth.

Data: source fixture is compiled from the official four HTML sources and 114 validated curated records; it is an offline fixture, NOT the live Page database. Live replacement remains blocked by unresolved Page binding. No owner blind holdout is accessed. Owner workbook mapping is from PR-0; complete execution/human judgement is still outstanding.

Cleanup: duplicated stage-based prohibitions were reduced in the active policy; the legacy ACI route, outbound, migrations and memory CAS are retained. Unified generation is an additional experimental path pending comparative evidence; this is explicit architecture debt, not a clean-code claim. Independent deletion/consolidation belongs to PR-5.

Remaining gates: current-head real replay, comparable baseline on identical source/config, strategy engine (PR-3), complete adaptive lab (PR-4), hard failure review, human assessment, UI acceptance and Page binding. Overall PR-2 remains PARTIAL.
