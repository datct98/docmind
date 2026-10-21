"""Cold-Path LangGraph Engine (`nightly_training_graph`).

Implements the 2-node Nightly Self-Training & Security Audit Architecture:
1. `deep_extractor_node`: Masks PII from successful/failed sales conversation
   logs and extracts structured `GoldenSampleRule` objects (Pydantic schema)
   with `cue_signal`, `generalized_rule`, `contrastive_evidence`, `dimension`,
   and `funnel_stage`.
2. `security_auditor_node`: Independently audits every extracted sample/rule
   for Indirect Prompt Injection (memory poisoning), unauthorized discount
   promises, and PII leaks before assigning `PENDING_REVIEW` (Staging Queue)
   or `REJECTED`.
"""

from __future__ import annotations

import re
from typing import Any, TypedDict

from pydantic import BaseModel, Field

from app.agent.security import audit_extracted_sample, mask_pii

try:
    from langgraph.graph import END, START, StateGraph

    _HAS_LANGGRAPH = True
except Exception:  # pragma: no cover
    _HAS_LANGGRAPH = False
    END = "__end__"
    START = "__start__"
    StateGraph = None  # type: ignore[assignment]


class GoldenSampleRuleModel(BaseModel):
    """Strict Pydantic schema for extracted sales lessons / few-shot samples."""

    funnel_stage: str = Field(default="CONSULTING", description="CONSULTING | OBJECTION_PRICE | CLOSING | SUPPORT")
    dimension: str = Field(default="INV", description="INV (Invariant Procedure) | DOM (Domain Fact)")
    category: str = Field(default="PSYCHOLOGICAL_PERSUASION")
    trigger_condition: str = Field(..., description="When this rule/sample should activate")
    cue_signal: str = Field(..., description="Recognizable customer signal in conversation")
    root_cause: str = Field(..., description="Why the sales response succeeded or why the AI failed")
    generalized_rule: str = Field(..., description="Actionable rule stripped of volatile customer PII")
    contrastive_evidence: str = Field(..., description="❌ Phản hồi yếu vs ✅ Phản hồi chốt sale chuẩn")
    retrieval_keywords: list[str] = Field(default_factory=list)
    review_status: str = Field(default="PENDING_REVIEW", description="PENDING_REVIEW | REJECTED")
    security_passed: bool = Field(default=True)
    security_risk_level: str = Field(default="LOW")
    security_violations: list[str] = Field(default_factory=list)
    security_audit_notes: str = Field(default="")


class NightlyTrainingState(TypedDict, total=False):
    page_id: str | None
    conversations: list[dict[str, Any]]
    sanitized_conversations: list[dict[str, Any]]
    extracted_samples: list[dict[str, Any]]
    audited_samples: list[dict[str, Any]]
    passed_count: int
    rejected_count: int


_STOPWORDS = {
    "và", "của", "cho", "với", "nhưng", "tôi", "mình", "em", "anh", "chị",
    "ạ", "nhé", "dạ", "thì", "là", "có", "không", "được", "các", "một",
}


def _extract_clean_keywords(text: str, limit: int = 6) -> list[str]:
    tokens = re.findall(r"(?u)[\wÀ-ỹ]{3,}", (text or "").lower())
    keywords: list[str] = []
    for tok in tokens:
        if tok not in _STOPWORDS and not tok.isdigit() and tok not in keywords:
            keywords.append(tok)
        if len(keywords) >= limit:
            break
    return keywords or ["tư vấn", "chốt lịch"]


def deep_extractor_node(state: NightlyTrainingState) -> NightlyTrainingState:
    """Node 1: Strip PII from conversation logs and extract structured GoldenSampleRuleModel items."""
    raw_convs = state.get("conversations") or []
    sanitized_convs: list[dict[str, Any]] = []
    extracted: list[dict[str, Any]] = []

    for conv in raw_convs:
        customer_msg_raw = (conv.get("customer_message") or conv.get("question") or "").strip()
        sale_reply_raw = (conv.get("sale_reply") or conv.get("winning_reply") or conv.get("correction") or "").strip()
        faulty_reply_raw = (conv.get("faulty_reply") or conv.get("faulty_answer") or "").strip()
        outcome = (conv.get("outcome") or "SUCCESS").upper()

        cust_masked, cust_pii = mask_pii(customer_msg_raw)
        sale_masked, sale_pii = mask_pii(sale_reply_raw)
        faulty_masked, _ = mask_pii(faulty_reply_raw)

        sanitized_convs.append({
            "customer_message": cust_masked,
            "sale_reply": sale_masked,
            "faulty_reply": faulty_masked,
            "outcome": outcome,
            "pii_redacted": bool(cust_pii["pii_masked"] or sale_pii["pii_masked"]),
        })

        if not cust_masked and not sale_masked:
            continue

        # Classify funnel stage & dimension
        lower_cust = cust_masked.lower()
        if any(k in lower_cust for k in ("đắt", "cao quá", "giảm giá", "combo", "suy nghĩ thêm")):
            funnel_stage = "OBJECTION_PRICE"
            category = "PSYCHOLOGICAL_PERSUASION"
        elif any(k in lower_cust for k in ("đặt lịch", "giữ suất", "sáng", "chiều", "[phone_redacted]")):
            funnel_stage = "CLOSING"
            category = "SALES_CLOSING"
        elif any(k in lower_cust for k in ("bảo hành", "sưng", "địa chỉ")):
            funnel_stage = "SUPPORT"
            category = "CUSTOMER_SUPPORT"
        else:
            funnel_stage = "CONSULTING"
            category = "CONSULTATIVE_PACING"

        cue_signal = (
            f"Khi khách ở giai đoạn {funnel_stage} nhắn: \"{cust_masked[:140]}\""
        )
        root_cause = (
            conv.get("root_cause")
            or "Nhân viên Sale thực chiến giữ đúng mạch chủ đề, giải tỏa rào cản tâm lý và kết thúc bằng câu hỏi chốt lịch hẹn Sáng/Chiều."
        )
        generalized_rule = (
            conv.get("generalized_rule")
            or f"[{funnel_stage}] Khi gặp tình huống tương tự \"{cust_masked[:90]}\", hãy áp dụng cách phản hồi thực chiến: {sale_masked[:220]}"
        )
        before_text = faulty_masked or "Trả lời chung chung, lệch chủ đề hoặc thiếu câu hỏi điều hướng chốt lịch."
        contrastive_evidence = f"❌ Trước: {before_text}\n✅ Sau (Chuẩn Sale): {sale_masked[:240]}"

        model = GoldenSampleRuleModel(
            funnel_stage=funnel_stage,
            dimension=conv.get("dimension") or "INV",
            category=category,
            trigger_condition=cust_masked[:160] or "Tình huống tư vấn khách hàng trên Fanpage",
            cue_signal=cue_signal,
            root_cause=root_cause,
            generalized_rule=generalized_rule,
            contrastive_evidence=contrastive_evidence,
            retrieval_keywords=_extract_clean_keywords(f"{cust_masked} {sale_masked}"),
        )
        extracted.append(model.model_dump())

    return {
        **state,
        "sanitized_conversations": sanitized_convs,
        "extracted_samples": extracted,
    }


def security_auditor_node(state: NightlyTrainingState) -> NightlyTrainingState:
    """Node 2: Independent AI Security Auditor verifying every extracted rule/sample."""
    extracted = state.get("extracted_samples") or []
    audited: list[dict[str, Any]] = []
    passed_count = 0
    rejected_count = 0

    for item in extracted:
        verdict = audit_extracted_sample(
            cue_signal=item.get("cue_signal", ""),
            generalized_rule=item.get("generalized_rule", ""),
            contrastive_evidence=item.get("contrastive_evidence", ""),
        )
        updated = {
            **item,
            "security_passed": verdict.passed,
            "security_risk_level": verdict.risk_level,
            "security_violations": verdict.violations,
            "security_audit_notes": verdict.audit_notes,
            "review_status": "PENDING_REVIEW" if verdict.passed else "REJECTED",
        }
        if verdict.passed:
            passed_count += 1
        else:
            rejected_count += 1
        audited.append(updated)

    return {
        **state,
        "audited_samples": audited,
        "passed_count": passed_count,
        "rejected_count": rejected_count,
    }


def _build_compiled_nightly_graph():
    if not _HAS_LANGGRAPH or StateGraph is None:
        return None
    builder = StateGraph(NightlyTrainingState)
    builder.add_node("deep_extractor_node", deep_extractor_node)
    builder.add_node("security_auditor_node", security_auditor_node)
    builder.add_edge(START, "deep_extractor_node")
    builder.add_edge("deep_extractor_node", "security_auditor_node")
    builder.add_edge("security_auditor_node", END)
    return builder.compile()


_COMPILED_NIGHTLY_GRAPH = _build_compiled_nightly_graph()


def run_nightly_training_graph(payload: dict[str, Any]) -> dict[str, Any]:
    """Execute the 2-node Cold-Path Nightly Training & Security Audit LangGraph."""
    initial_state: NightlyTrainingState = {
        "page_id": payload.get("page_id"),
        "conversations": payload.get("conversations") or [],
    }
    if _COMPILED_NIGHTLY_GRAPH is not None:
        result = _COMPILED_NIGHTLY_GRAPH.invoke(initial_state)
    else:
        s1 = deep_extractor_node(initial_state)
        result = security_auditor_node(s1)

    return {
        "engine": "langgraph-nightly-auditor" if _COMPILED_NIGHTLY_GRAPH is not None else "nightly-fallback",
        "page_id": result.get("page_id"),
        "passed_count": result.get("passed_count", 0),
        "rejected_count": result.get("rejected_count", 0),
        "samples": result.get("audited_samples", []),
    }
