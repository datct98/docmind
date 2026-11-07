# PR-4 — Owner-supervised lab and observed quality failures (PARTIAL)

Parent: PR-3 `936ff77`. The lab is operational; release acceptance is **BLOCKED**, and no model was trained or switched. Workbook approval supervises behavior and evaluation. It does not certify newly generated wording. No blind holdout was opened, copied or used to develop code.

## Implementation and boundaries

`tools/v2/lab_dataset.py` verifies the original workbook SHA and all 30 owner labels against PR-0 coverage. The 20 episodes and 10 comparison pairs preserve GIU/SUA. SUA examples are negative/rewrite-required; every `positiveAnswer` remains null until a real review. Every record carries expected/forbidden behavior, source provenance, 1–5 rubric and hard gates. The fixture builder separates source-derived Spa data from synthetic rental, SaaS, service and counterfactual data. Those fixtures are explicitly forbidden from production import.

The opt-in replay harness executes the production Core with configured providers and records full visible turns, model envelopes, source references, strategies, memory, timings and failures. An offline customer actor sees only its persona and visible history, never the expected answer, business source or internal reasoning. It is evaluation infrastructure, not a new production agent. Reports export shuffled A/B human worksheets; scores remain empty. Eight-word overlap is only a review flag, never proof of copying or success. `attach_lab_observations.py` attaches actual outputs to original labels without promoting negative examples.

Lab-driven runtime changes are source-metadata preservation and bounded quality repair. Branch scope, regional-vs-branch status, source priority and unknown promotion validity previously disappeared between compiled data and `Evidence(id,title,text)`. They now accompany the same immutable source through snapshot/retrieval, writer, continuation and review. Only a bounded allowlist of metadata crosses this boundary; malformed metadata stays visibly unverified. Hits outside the snapshot receive one scoped batch hydration, not per-row queries. Legacy three-field Evidence remains compatible. Metadata constrains claims; it is not independent evidence of a new right or price.

A source/authority/delivery-checked reply with a QUALITY warning may receive one bounded improvement, with at most 16 seconds of the existing turn budget. A failed rewrite retains the previously checked reply instead of replacing it with a technical error. A BLOCKING draft is never saved this way. STOP closing review excludes irrelevant catalog/industry rules. Both still depend on probabilistic model judgments; real failures below demonstrate why reviewer PASS is insufficient. No canned dialogue lookup was added.

## Actual runs

| Run | Cases / turns | Fallback | p50 / p95 | Meaning |
|---|---:|---:|---:|---|
| All owner development cases | 30 / 46 | 5 | 17,242 / 39,496 ms | Exit 1; full coverage, not quality acceptance |
| Adaptive/counterfactual/user sequence | 5 / 19 | 1 | 18,732 / 45,001 ms | Exit 1; rental actor case invalid due to role contamination |
| PRIMARY configured writer probe | 4 / 7 | 1 | 30,185 / 45,003 ms | Exit 1; no evidence to switch production writer |
| Same cases with existing ALTERNATE writer | 4 / 7 | 0 | 18,046 / 28,341 ms | Single stochastic comparison; not a model ranking |

The final authority/quality-repair replay and its matched before/after results are in `authority-report/metrics.json` and `authority-transcripts.json`. It combines source transport, quality recovery and review changes; causal effects cannot be separated from one replay. Earlier fixtures/runs are retained under `artifacts/v2-pr4`, not overwritten. Provider failure/timeouts count as failures, not excluded samples.

That run **regressed**: 17 turns/10 scenarios, 6 fallbacks, p50 24,110ms/p95 45,003ms. On the 16 matched owner turns: baseline 2 fallbacks, p50 17,585ms, mean 2.1875 calls; candidate 6 fallbacks, p50 24,069ms, mean 2.5 calls. One STOP timed out and returned the generic retry request. The metadata helped qualify an undated promotion, but a follow-up missed an available cross-branch usage policy; the writer still overloaded replies and overpromised quality. This is evidence against rollout, not hidden as a transient outlier.

The lost policy led to a final bounded source-window correction: reserve up to one quarter of context for ranked policy/promotion conditions, and allow the last two visible assistant messages to supply weak relevance signals for follow-up subjects. Current customer corrections still dominate. Assistant words never become a business source. Two regressions verify terms survive a crowded branch catalog and a prior false assistant quote cannot replace the actual price. A null-metadata compatibility defect was caught on the first test run and corrected; `window-unit.log` preserves the failure, `window-unit-fixed.log` records **126 tests, zero failures/errors, 2 skips, exit0**. The four-turn targeted follow-up is recorded in `window-report/` and does not replace the failed 17-turn result or validate the whole suite.

The final targeted run completed 4 turns, zero fallback, p50 16,831ms/p95 26,685ms, mean 2.5 calls, exit0. It correctly answered the cross-branch card restriction and counterfactual price/room/VAT. It **still failed the promotion truth gate**: “đang được giảm 10%” and an offer to calculate the discounted total contradicted the unknown effective date, despite a later caveat and reviewer PASS. NS02 output was factually scoped but still longer than necessary. This correction is useful source-recall evidence; it is not proof that the sales bot is ready. Broad and adaptive replay after this last source-window change is NOT_RUN.

Actual selected regression after metadata/quality changes: `mvn -o -f api-java/pom.xml '-Dtest=Sales*Test,ChunkMetadataBackfillService*Test' '-Dsurefire.reportNameSuffix=authority' test`: **124 tests, 0 failures/errors, 2 opt-in skips, exit 0**. This includes companion PR-5 working-tree changes, not an isolated PR-4-only build. New tests cover scoped metadata hydration, malformed/foreign metadata, quality revision and retention when the retry provider fails. The existing blocking-factual test remains. A prior full run exposed a 16-second minimum retry threshold that incorrectly skipped repair under short configured deadlines; it was fixed, and the original assertion passed unchanged. Failed log `artifacts/v2-pr5/full-current-final.log` is retained.

`python -m unittest discover -s tools/v2 -p 'test_*.py'`: **10 tests, exit 0**, including original compiler tests and dataset/label/provenance tests. Full integrated regression, real PostgreSQL memory test, frontend and Docker builds are reported in PR-5. Maven green subsets do not override observed model failures.

## Observed quality and what is not proven

All 30 owner-case visible dialogues were read, including failed responses. Agent observations are recorded separately from human scores. Warmth is inconsistent: some direct price answers are useful, but several replies still dump package details, add unrequested upgrades, ask compound/repeated questions, say “chốt luôn” too soon, or replace uncertainty with strong reassurance. One promotion response treated an undated discount as current; a regional service was treated as branch availability. STOP could still receive an invitation to return or a technical fallback. Booking/handoff language sometimes promised work without a receipt. These are release blockers, not cosmetic TODOs.

The adaptive rental actor twice spoke as the shop. That case is invalid for customer-experience inference. The actor prompt was corrected to label speakers CUSTOMER/SHOP; the correction itself is not a passing new adaptive run. The original Spa counterfactual correctly used 747,000đ/75 minutes/private room/VAT included; this is one controlled fact-adaptation observation, not broad generalization.

No two-reader scoring, owner blind result, live conversion, live Page import or final UI customer acceptance exists. No claim of “high EQ”, “sales expert trained”, “Wao” or complete cross-industry ability is justified. The mandatory lab has more work: independent valid adaptive cases, style-pair ranking, hard-gate variants, accepted corrections and genuine human comparison. PR-4 remains PARTIAL.

## Reproduction

Use explicit permitted paths only. Compile tests first, then run the opt-in provider rehearsal separately from compilation:

```powershell
python tools/v2/build_lab_fixtures.py --output artifacts/v2-pr4/fixtures-authority
python -m unittest discover -s tools/v2 -p 'test_*.py'
mvn -o -f api-java/pom.xml '-Dtest=SalesCoreLiveReplayTest' '-Dsales.live=true' '-Dsales.goalFirst=true' '-Dsales.strategies=true' '-Dsales.writer=ALTERNATE' '-Dsales.cases=../artifacts/v2-pr4/authority-cases.json' '-Dsales.output=../artifacts/v2-pr4/authority-replay' surefire:test
```

The live command uses existing local provider credentials and incurs model calls. It never connects to the Page database or Facebook. Do not overwrite an existing evidence directory when comparing versions. Raw local artifacts contain prompts and source data; the committed exports contain visible transcripts and metrics, not credentials.
