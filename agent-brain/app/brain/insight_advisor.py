"""Hot-Path (<10ms) Cognitive Memory & Psychological Insight Advisor."""

from __future__ import annotations

import time

from app.brain.policy_engine import evaluate_policy_and_confidence
from app.brain.skill_matrix import select_psychological_skill
from app.memory.fact_extractor import extract_turn_signals
from app.memory.memory_service import MemoryService
from app.memory.models import RecallAndAdviseRequest, RecallAndAdviseResponse


class InsightAdvisor:
    """Combines sub-millisecond Memory Recall, Signal Extraction, Skill Matrix, and Policy Engine."""

    def __init__(self, memory_service: MemoryService) -> None:
        self._memory = memory_service

    def recall_and_advise(self, req: RecallAndAdviseRequest) -> RecallAndAdviseResponse:
        t0 = time.perf_counter()

        # 1. Recall existing customer & session memory
        mem_ctx = self._memory.recall_customer_context(
            tenant_id=req.tenant_id,
            customer_id=req.customer_id,
            conversation_id=req.conversation_id,
            query=req.message,
            customer_name=req.customer_name,
        )

        # 2. Extract real-time signals from current message (Rule 2.3: current message > old memory)
        signals = extract_turn_signals(
            tenant_id=req.tenant_id,
            customer_id=req.customer_id,
            raw_message=req.message,
        )

        # Resolve effective honorific & stage (current turn overrides old memory)
        honorific = next(
            (f.fact_value for f in signals.facts if f.fact_key == "honorific"),
            mem_ctx.honorific,
        )
        effective_stage = (
            signals.purchase_stage
            if signals.purchase_stage != "CURIOUS" or mem_ctx.purchase_stage == "UNKNOWN"
            else mem_ctx.purchase_stage
        )

        # 3. Select the single best 3-beat psychological skill (with Context Engineering metadata)
        skill_directive, archetype, buying_temp = select_psychological_skill(
            stage=effective_stage,
            intent=signals.intent,
            memory_ctx=mem_ctx,
            domain_meta=req.domain_Metadata,
        )

        # 4. Evaluate 5-factor confidence & handoff policy
        final_conf, next_action, requires_human, handoff_reason = evaluate_policy_and_confidence(
            signals=signals,
            memory_ctx=mem_ctx,
            retrieval_quality=req.retrieval_quality,
            groundedness=req.groundedness,
            tenant_policy=req.tenant_policy,
        )

        latency_ms = round((time.perf_counter() - t0) * 1000.0, 2)

        return RecallAndAdviseResponse(
            tenant_id=req.tenant_id,
            customer_id=req.customer_id,
            conversation_id=req.conversation_id,
            normalized_message=signals.normalized_text,
            masked_message=signals.masked_text,
            honorific=honorific,
            purchase_stage=effective_stage,
            sentiment=signals.sentiment,
            intent=signals.intent,
            buyer_archetype=archetype,
            buying_temperature=buying_temp,
            final_confidence=final_conf,
            next_best_action=next_action,
            requires_human=requires_human,
            handoff_reason=handoff_reason,
            active_skill=skill_directive,
            customer_memory_block=mem_ctx.customer_memory_block,
            active_facts=mem_ctx.active_facts,
            objections=mem_ctx.objections,
            latency_ms=latency_ms,
        )
