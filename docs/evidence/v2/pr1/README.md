# PR-1 — source preparation (PARTIAL; live replacement BLOCKED)

Baseline code: PR-0 `75f8c3c1f28bb31630a6c488f80cb6441b3b1ab7`.

Implemented a strict data compiler for the proposed seed. It does not execute supplied SQL or connect to the database. Four HTML checksums, all 114 row references, unique IDs/keys, per-document counts and 43 primary price/code cells are checked. Regional prices retain their regional scope; generic process descriptions cannot establish package inclusions. Three process-note rows lacked `record_kind`; normalization now labels them explicitly. Every record carries its source hash, row, authority and `FTS_ONLY` readiness.

Actual command: `python -X utf8 tools/v2/prepare_knowledge.py --output artifacts/v2-pr1/validated-knowledge.json`, exit 0: 4 documents, 114 chunks. `python -X utf8 -m unittest discover -s tools/v2 -p test_prepare_knowledge.py -v`, exit 0: 5 tests. Checks include source drift, unsupported SQL expressions, same-SKU branch differences, room classification and regional scope. An initial test attempted an unavailable sandbox temp directory; it was replaced by an in-memory checksum mutation, not skipped.

The generated development artifact is reproducible from the supplied ZIP. No old database knowledge was copied or deleted. No runtime API change, after conversation or performance improvement is claimed for this commit.

Remaining: verified Messenger-to-knowledge binding, guarded transactional replacement, Page-scoped stale rule/skill cleanup, cache invalidation, real embedding reindex, runtime retrieval and negative tenant tests. Current database is unchanged and still contains the unsafe legacy Page booking rule documented in PR-0. Destructive work remains blocked by appendix 05 §B; name matching and a UUID allowlist are insufficient proof. Semantic embedding remains unverified/stub, never reported as production-ready.

Source ambiguities remain recorded in PR-0 and the supplied conflict report. The compiler validates provenance and primary numeric fields; it is not a semantic endorsement of every marketing claim in the source cells. Clinical claims and branch availability still require runtime claim checks. This PR is not DONE.
