> ⚠️ DEPRECATED ARCHITECTURE (DO NOT USE)
> Tài liệu này thuộc kiến trúc V1/C1-C3 cũ (Stage cứng / Regex tiền-LLM / Template cứng) và đã bị CÁCH LY. Nguồn chân lý duy nhất hiện hành là `ACI_V2_SOURCE_OF_TRUTH.md`.

# Agent 2 (Developer) — Implementation Notes
- **Timestamp**: `2026-10-23T08:32:30Z`
- **Input Artifacts Consumed**: `01_po_techlead/prd.md`, `01_po_techlead/architecture_plan.md`, `01_po_techlead/acceptance_contract.yaml`

## 1. Root Cause Analysis of Previous Test Regressions & Dry Replies
1. **Why 28/90 Unit Tests Failed Previously**:
   - Unbounded additions to `FanpageBotPromptBuilder` caused `SlimPromptAntiBloatingTest` to exceed the strict `<= 2800` character budget on complex multi-turn prompts and `<= 55%` overhead ratio on simple greetings.
   - Aggressive regex edits in `PriceLadderTracker`, `AntiInfoDumpingHook`, and `BubbleCapHook` broke deterministic invariants expected by `FanpageBotServiceTest`, `GuardrailPipelineTest`, and `FanpageBotUpgradeE2eTest`.
2. **Why Bot Replies Needed Single-Skill Focus & Concise 3-Bubble Split**:
   - Activating 2 skills simultaneously diluted psychological focus; enforcing `MAX_ACTIVE_SKILLS = 1` in `CustomerInsightEngine.selectActiveSkills` and `AgentBrainService.synchronizeActiveSkills` ensures every turn focuses on the single highest-priority skill.
   - `BubbleCapHook` previously collapsed paragraph 3 into paragraph 2 (`capBubbles` merged `paragraphs.get(2)` into `paragraphs.get(1)`), creating long 2-bubble walls of text. Updating `BubbleCapHook` to allow up to 3 concise bubbles (`<= 3`) and updating `polishConversationalVietnamese` so 3-paragraph replies (Empathy + Solution + Closing Question) are not collapsed back into 2 bubbles cleanly separates Empathy (Bubble 1), Grounded Value/Price (Bubble 2), and Closing Question (Bubble 3).

## 2. Key Engineering Changes Implemented
1. **Single-Skill Per Turn Enforcement (`CustomerInsightEngine.java`, `AgentBrainService.java`)**:
   - `selectActiveSkills` and `synchronizeActiveSkills` now return at most **1 single skill** (`MAX_ACTIVE_SKILLS = 1`) per turn.
2. **Concise 3-Bubble Messenger Formatting (`BubbleCapHook.java`, `GuardrailPipeline.java`, `FanpageBotService.java`)**:
   - `BubbleCapHook` preserves up to 3 natural paragraphs (`<= 3` bubbles) and automatically splits a trailing closing question into Bubble 3 if Bubble 2 exceeds 180 characters.
   - `polishConversationalVietnamese` only merges paragraph 1 and paragraph 2 if paragraph 1 is a short standalone greeting (`<= 45` chars) or when there are `> 3` paragraphs, preserving 3-bubble replies across both intercepted and LLM/fallback paths.
   - `buildImpressiveTurn1WellnessOpening` and `deliberatePostPriceMoodPreservationPass` craft concise, emotionally warm 3-bubble replies (or 2 bubbles on explicit hesitation with 0 questions), including post-price package comparison and trial acceptance turns.
3. **Impressive Fast Turn-1 Sensory Opening & Grounded Post-Price ~10s Deliberate Mood Preservation (`FanpageBotService.java`)**:
   - Turn 1 symptom/greeting openers deliver immediate, deeply empathetic sensory validation in `< 1s` (3 concise bubbles).
   - Every turn after price is quoted deliberates for `~10s` (`targetDeliberationMs = 9_600L`) — both inside `deliberatePostPriceMoodPreservationPass` and on post-LLM fallback turns when `hasPriorPriceInHistory` is true — to preserve customer mood and avoid robotic instant replies.
4. **Zero-Pressure Hesitation Respect & Handoff Guardrail Isolation (`ClosingQuestionHook.java` & `FanpageBotService.java`)**:
   - When the customer expresses hesitation (`"uk để suy nghĩ đã"`, `"để chị suy nghĩ thêm"`), trailing questions (`?`) are stripped and replaced with a warm, zero-pressure blessing.
