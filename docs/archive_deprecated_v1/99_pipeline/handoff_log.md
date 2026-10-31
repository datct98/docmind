> ⚠️ DEPRECATED ARCHITECTURE (DO NOT USE)
> Tài liệu này thuộc kiến trúc V1/C1-C3 cũ (Stage cứng / Regex tiền-LLM / Template cứng) và đã bị CÁCH LY. Nguồn chân lý duy nhất hiện hành là `ACI_V2_SOURCE_OF_TRUTH.md`.

# Antigravity Multi-Agent Team v1.0 — Handoff Log
1. **[Step 0 -> Step 1]** `00_user_brief/brief.md` -> Agent 1 (PO + Techlead) produced `prd.md`, `architecture_plan.md`, `acceptance_contract.yaml`, `test_strategy.md`, `risk_register.md`.
2. **[Step 1 -> Step 2]** `01_po_techlead/*` -> Agent 2 (Developer) enforced single-skill per turn (`MAX_ACTIVE_SKILLS = 1`), concise 3-bubble split (`BubbleCapHook`, `GuardrailPipeline` & `polishConversationalVietnamese`), fast Turn-1 sensory opening (`< 1s`), and ~10s post-price mood preservation in `FanpageBotService`, verified `109/109` Maven tests PASS, and produced `02_dev/*`.
3. **[Step 2 -> Step 3]** `02_dev/dev_handoff_package.md` -> Agent 3 (QA Adversarial) executed live 7-turn Hanoi objection/promo/hesitation/recovery, 4-turn Saigon multi-branch & package comparison, and 3-turn security/distress suites (`all_qa_passed=True`), verified fixes for `DEF-001` & `DEF-002`, and produced `03_qa/*`.
4. **[Step 3 -> Step 4]** `03_qa/qa_final_report.md` -> Agent 4 (Product UAT) executed end-to-end office-worker & male customer persona journeys and signed off `04_product_uat/uat_report.md`, `business_acceptance.yaml`, `release_notes.md`, and `user_test_package.md` (`APPROVED`).
