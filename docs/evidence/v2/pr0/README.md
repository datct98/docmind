# PR-0 — baseline and reachability audit

Status: **PARTIAL**. No product quality improvement is claimed. Destructive PR-1 is **BLOCKED** until Messenger binding is verified. Independent implementation/testing may proceed using development fixtures. No push or external deployment performed.

Baseline: `2314bf13c9efe1fca8d4e00fdfcd9535686ddefc`, branch `fix/post-cp3-quality-remediation`. All tracked/untracked uncommitted work was rolled back before this audit. Ignored evidence artifacts were retained. Git rollback did not restore the database or running image. The local API was rebuilt from this baseline and restarted for the recorded replay; this is not the earlier uncommitted v4.1 runtime.

## Actual evidence

- Package: 29/29 manifest entries match SHA-256 and size. Source bundle is staged under `artifacts/v2-contract`; no owner blind holdout was opened.
- Owner workbook: 30 rows reconciled against JSON labels, 20 episodes (8 GIU/12 SUA), 10 pairs (7 GIU/3 SUA). `owner-coverage.json` maps every ID to expected behavior and future test contract. Execution remains NOT_RUN; mappings are not tests.
- `mvn -o -f api-java/pom.xml clean test`: exit **1**, Maven reports **1097 tests, 17 failures, 3 errors, 10 skipped**. Individual failures are in `baseline-tests.json`. Retained XML totals differ from the Maven aggregate; this limitation is preserved rather than silently normalizing the counts. Three errors reference the missing legacy Page FK. No failing test was deleted or suppressed.
- `docker compose build api-java`: exit **0**. `docker compose up -d --no-deps api-java`: exit **0**. Image/config evidence: `runtime-baseline.json`.
- Actual-model REST replay: `baseline-pilot-six-turn.jsonl`; synthetic sender, no Facebook messages. Latency/calls/failures: `baseline-conversation-metrics.json` and `runtime-replay-log.txt`. Both configured providers/models are unchanged.
- Frontend `npm --prefix frontend run build`: exit **0**. Python audit/replay scripts compile; owner audit exits **0**.
- Read-only DB audit: `db-preflight-summary.txt`, `kb-preflight-details.txt`, `stale-knowledge-inventory.txt`. No knowledge DELETE/UPDATE/INSERT was executed by this audit. Simulator/test activity creates its own test conversation records.
- Source inventory: 263 runtime/config source files, 126287 lines; 1069 tracked text files inventoried. Full inventory and exact-duplicate candidates are provided. Inventory does **not** mean every symbol has proven reachability. Unproven candidates remain KEEP.

## Reproduced customer failure

| Turn | Customer contribution | Observed baseline | LLM calls |
|---|---|---|---:|
| 1 | Giá | Greeting bubble, discovery question, no price | 1 |
| 2 | Vai gáy + đau đầu | Acknowledgment, asks area, no price | 1 |
| 3 | HN | Asks area again | 1 |
| 4 | Long Biên | Address/hours only, original price goal still open | 3 |
| 5 | Criticizes interrogation | Finally recommends DS03/DS02; regional offer treated as branch availability | 3 |
| 6 | Thôi nghỉ đi khỏi tư vấn | Apology + remembered symptoms + another question: **STOP violation** | 1 |

This is agent assessment of a real model replay, not human acceptance or a conversion benchmark. The pipeline can return successful validation while failing the customer journey. No after transcript exists in PR-0 because runtime behavior has not yet changed.

## Runtime architecture and decision conflicts

1. REST `/api/bot/fanpage/message` and Messenger `/webhook` converge on `FanpageBotService.handleCustomerMessage`. Shared fences include inbound persistence, sender epoch, opt-out/control state and outbound ledger. Preserve these.
2. `SalesCoreGateway.enabledFor` selects the pilot; other Pages/disabled flag reach `CleanAgentOrchestratorService.executeAciCognitiveTurn`. ACI is active fallback, not dead code. `agentMode` is exposed but the observed non-Core route explicitly sets LLM_COGNITIVE_V2; do not infer legacy prompt reachability from that flag alone.
3. Core path: conversation-only context → planner → stage-based evidence loader → writer → validator → semantic reviewer → up to two repairs/recovery → gateway/memory CAS → existing delivery fences. Discovery/trust-repair/close can return planner text without semantic review or durable structured memory updates.
4. Stage-first policy is repeated in planner, writer, validator, reviewer and industry recommendation requirements. LOCATION rejects money; TRUST_REPAIR strips business evidence. These conflicts explain the failed useful-answer behavior.
5. Recovery deletes evidence, then changes the task to another question. Alternate provider selection bypasses the resilient wrapper; a slow planner can consume the entire 45-second turn. These are decision/recovery changes for PR-2, not grounds to change the model.
6. Knowledge authority is split across active DB documents, Page profile offers/policies, persona, Page-scoped learned rules/skills, ACI external prompt and embedded prompts. Current pilot has 6 documents/111 chunks, but only 3 curated Markdown documents/46 chunks active; the fourth HTML policy source is absent. All vectors are present, but runtime reports `provider_model=stub`: vector count is not semantic readiness.
7. One active Page rule instructs immediate booking confirmation from a stated hour. Core does not consume these learned rules; fallback does. It must be quarantined within verified Page scope during PR-1. Preserve unrelated/global rules.
8. Memory preserves raw customer sources and CAS revisions, but no durable goal/open-loop/strategy-outcome model exists. Fast-path discovery updates are discarded. New conversation state must keep facts separate from provisional hypotheses.
9. API profiling reports zero token values and labels whole core time as model latency; source loading occurs inside core timing too. Do not interpret these as measured token use or add the overlapping timings.

## Binding blocker and security findings

The simulator and allowlist use `2850a8ef-ca4a-40eb-87fa-4f808640efee`; configured webhook default uses `b23cd382-4f7a-4829-8c54-1d410da14bf8`, which is absent from current `knowledge_pages`. `resolveEffectivePageId` accepts a query UUID or UUID entry, otherwise ignores numeric Messenger Page identity and uses the default. There is no binding table. A read-only Graph `/me` request returned HTTP 400/code 100. No identity was verified, so **no deletion is permitted**. Requested missing numeric Page/callback identity from the owner; elapsed time is not approval.

Webhook HMAC is only checked when an app secret is configured; the local container has no app secret. Query UUID override is not bound to the numeric Page. These are existing security risks, not permission to weaken guards or infer another tenant. Production release remains blocked until verified mapping/signature behavior is addressed and tested.

## Package inconsistencies resolved for development

- Bundled `validate_lab.py` refers to an `owner_only` path and obsolete training layout. It was inspected but **not executed**. New development tooling uses explicit permitted paths only.
- Reference card JSON allows 3 cards; normative contract requires 0–2. Use 0–2.
- Reference rubric is 0–4; normative appendix uses 1–5. New evaluation uses 1–5 with an explicit version.
- Examples marked SUA are not positive examples. Owner prose that overstates medical effects/action receipts is not business evidence.
- Current V2 HTML/appendix says Long Biên NS04 does not establish hair wash. Earlier conversation stated otherwise; the sole-current-package rule supersedes that draft, but this conflict remains visible and must not be converted into an invented inclusion.
- Proposed SQL marks READY with NULL embeddings. Treat as curated text readiness only, never semantic readiness. Name matching in the supplied SQL is insufficient binding proof.

## Remaining work / gates

PR-0 is PARTIAL because verified external Page binding is missing and deletion-level reachability proof remains open for legacy candidates. Cleanup inventory begins now; PR-5 must supply before/after proofs and an independent commit. PR-1 deletion remains BLOCKED. PR-2/3/4 can develop against source-derived fixtures; they cannot claim live Page acceptance before data scope is resolved. Human comparison and owner-run blind acceptance are NOT_RUN. The codebase is **not declared clean**.
