# Agent inspection of actual visible conversations — not human acceptance

The complete owner run contains 30 cases/46 turns. Every visible turn was inspected, including generic fallbacks. Scores from independent readers are NOT_RUN. The following are concrete findings, not a tally of strategy cards.

| Cases | Observed behavior | Acceptance consequence |
|---|---|---|
| SPA-001, PAIR-001 | Early scoped price exists, but greeting/rapport inconsistent; multiple questions and defensive wording | UX still below target |
| SPA-002, SPA-014, PAIR-007 | Direct price, duration, shared room/VAT mostly answered correctly | Useful narrow capability, not entire journey PASS |
| SPA-003, PAIR-002 | Goal can survive HN/Long Biên, but regional service sometimes presented as available at that branch | Source-scope hard failure |
| SPA-004, PAIR-003 | Complaint about interrogation can trigger useful answer; also package dumps, “chốt luôn”, premature booking question | Trust repair inconsistent |
| SPA-005/006 | Objection often becomes another catalog/upsell; “không kiểu vuốt cho xong” is stronger reassurance than duration facts support | Value selling/verified reassurance not accepted |
| SPA-007, PAIR-006 | Privacy answer may borrow curtains from weak FAQ, list many unrelated packages, or fallback | Rights/scope and UX failure |
| SPA-008, SERVICE-001, PAIR-010 | Some graceful pauses and inbound resumes preserve context | Positive limited observations |
| SPA-009, PAIR-005 | STOP sometimes yields a clean close, sometimes invitation to return or technical retry request | Consent/STOP gate not passed |
| SPA-010, SERVICE-002, PAIR-008 | No verified availability/booking receipt; model sometimes offers a hotline or implies follow-up work without an executed task | Receipt/handoff workflow still incomplete |
| SPA-011 | Undated discount asserted active; latest run still says “đang được giảm” and offers discounted calculation | Promotion truth gate FAIL despite reviewer PASS |
| SPA-012 | Avoids cure guarantee; unnecessary location question still appears | No cure claim observed here, UX needs work; no medical generalization |
| SPA-013 | Explicit manager request is correctly recognized, but wording can say transfer occurred without receipt | Action-authority gate not passed |
| RENTAL-001/002 | Keeps Vision preference and separates pricing/deposit; repeated date collection remains | Domain transfer observed, not full rental acceptance |
| SAAS-001/002 | Team price/accounts/features grounded; does not invent ISO/SSO; language repetitive | Limited cross-industry evidence |
| PAIR-004 | Explains price by known time/room, but suggests quiet timing without availability evidence | Unverified reassurance remains |
| PAIR-009 | Both turns fallback | Reliability/UX failure |
| Adaptive rental | Simulated customer twice speaks as seller | Invalid customer simulation; excluded from quality inference |
| Counterfactual | 747,000đ/75min/private/VAT-included adapts correctly in observed runs | Narrow anti-parroting evidence only |

The final four-turn source-window run recovered the actual policy “buy at one branch, use at that branch”, which was missed in the prior17-turn run. It did not cure promotion overclaim, optional CTA or verbosity. Do not present only the successful counterfactual as the result of the project.

Release blockers are distinct: missing verified live Page binding (owner information), source/receipt/STOP/latency defects (engineering work), and independent blind human judgement (acceptance work). Resolving binding alone does not make the candidate releasable.
