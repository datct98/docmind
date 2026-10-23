"""Memory-Aware Policy Engine & 5-Factor Confidence Scorer (Section 12 & 13)."""

from __future__ import annotations

from typing import Any

from app.config import CONFIG
from app.memory.fact_extractor import ExtractedTurnSignals
from app.memory.models import CustomerMemoryContext, NextBestAction


def evaluate_policy_and_confidence(
    signals: ExtractedTurnSignals,
    memory_ctx: CustomerMemoryContext,
    retrieval_quality: float = 0.85,
    groundedness: float = 0.90,
    tenant_policy: dict[str, Any] | None = None,
) -> tuple[float, NextBestAction, bool, str | None]:
    """Computes 5-factor confidence and determines NextBestAction (`REPLY`, `CLARIFY`, `HANDOFF`)."""
    policy = tenant_policy or {}
    session = memory_ctx.session

    # 1. Check memory consistency (whether current message conflicts with high-confidence system facts)
    memory_consistency = 0.95
    if signals.is_bot_misunderstanding:
        memory_consistency = 0.45

    intent_certainty = 0.90 if signals.intent != "DISCOVERY_OR_CONSULTATION" else 0.82
    policy_safety = 0.20 if signals.is_complaint_or_refund else 0.95

    # Section 12 Formula:
    # final_confidence = 0.30 * retrieval_quality + 0.25 * groundedness + 0.15 * memory_consistency + 0.15 * intent_certainty + 0.15 * policy_safety
    final_confidence = round(
        0.30 * max(0.0, min(1.0, retrieval_quality))
        + 0.25 * max(0.0, min(1.0, groundedness))
        + 0.15 * memory_consistency
        + 0.15 * intent_certainty
        + 0.15 * policy_safety,
        3,
    )

    # Hard Handoff Rules (Section 13)
    if session.human_takeover:
        return final_confidence, "HANDOFF", True, "Cuộc hội thoại đang ở chế độ nhân viên tiếp quản (human_takeover=true)."

    if signals.is_explicit_human_request:
        return final_confidence, "HANDOFF", True, "Khách hàng chủ động yêu cầu gặp nhân viên thật."

    if signals.is_complaint_or_refund:
        return final_confidence, "HANDOFF", True, "Khách hàng khiếu nại / yêu cầu hoàn tiền / phản ánh chất lượng."

    # Check if customer previously had complaints and is now negative/cautious (Section 10.2)
    has_prior_complaint = any(f.fact_key == "complaint_history" for f in memory_ctx.active_facts)
    if has_prior_complaint and signals.sentiment in ("negative", "cautious"):
        return (
            final_confidence,
            "HANDOFF",
            True,
            "Khách từng có lịch sử khiếu nại và đang có tâm lý không hài lòng — ưu tiên chuyển nhân viên chăm sóc.",
        )

    # Check consecutive bot failures (Section 10.5 & 13.8: clarification_count >= 2 or negative_feedback)
    effective_clarify = session.clarification_count + (1 if signals.is_bot_misunderstanding else 0)
    if effective_clarify >= 2 or (signals.is_bot_misunderstanding and session.clarification_count >= 1):
        return (
            final_confidence,
            "HANDOFF",
            True,
            f"Bot đã hỏi làm rõ hoặc hiểu chưa đúng ý khách {effective_clarify} lần liên tiếp.",
        )

    # Check phone call request (Section 10.3 & 18 Group 4)
    if signals.pii_meta.get("phones") or memory_ctx.preferred_contact == "phone":
        if signals.intent == "HUMAN_OR_CALL_REQUEST" or signals.pii_meta.get("phones"):
            return (
                final_confidence,
                "HANDOFF",
                True,
                "Khách hàng để lại số điện thoại hoặc yêu cầu gọi điện tư vấn trực tiếp.",
            )

    # Check repeated price objections (Section 10.1 & 18 Group 2)
    prior_price_obj = next((o.count for o in memory_ctx.objections if o.type == "price"), 0)
    current_price_obj = 1 if any(o[0] == "price" for o in signals.objections) else 0
    max_price_objections = int(policy.get("max_auto_price_objections", 4))
    if prior_price_obj + current_price_obj >= max_price_objections:
        return (
            final_confidence,
            "HANDOFF",
            True,
            f"Khách đã băn khoăn/chê giá {prior_price_obj + current_price_obj} lần — chuyển Sale hỗ trợ ưu đãi trực tiếp.",
        )

    # Retrieval / Confidence thresholds (Section 12 & 13.1-13.2)
    if retrieval_quality < 0.55 or final_confidence < CONFIG.clarify_confidence_threshold:
        return (
            final_confidence,
            "HANDOFF",
            True,
            f"Độ tin cậy tổng hợp ({final_confidence}) hoặc chất lượng tài liệu ({retrieval_quality}) dưới ngưỡng an toàn.",
        )

    if final_confidence < CONFIG.auto_reply_confidence_threshold:
        return final_confidence, "CLARIFY", False, None

    return final_confidence, "REPLY", False, None
