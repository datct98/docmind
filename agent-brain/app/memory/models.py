"""Pydantic data contracts for Agent Brain Memory & Psychological Insight."""

from __future__ import annotations

from datetime import datetime
from typing import Any, Literal
from pydantic import BaseModel, Field

SourceType = Literal["explicit", "inferred", "system", "human_verified"]
PurchaseStage = Literal[
    "UNKNOWN",
    "CURIOUS",
    "COMPARING",
    "PRICE_SENSITIVE",
    "HESITATING",
    "SKEPTICAL",
    "READY_TO_BUY",
    "ABANDONED",
    "COMPLAINING",
    "POST_SALE_SUPPORT",
]
NextBestAction = Literal["REPLY", "CLARIFY", "HANDOFF", "FOLLOW_UP_LATER", "DO_NOT_CONTACT"]
FeedbackType = Literal["WRONG", "OUTDATED", "CONFLICT", "PRIVACY", "IRRELEVANT"]


class MemoryFact(BaseModel):
    id: int | None = None
    tenant_id: str
    customer_id: str
    fact_key: str
    fact_value: str
    source_type: SourceType = "explicit"
    confidence: float = Field(default=0.90, ge=0.0, le=1.0)
    evidence_message_ids: list[int] = Field(default_factory=list)
    effective_from: datetime
    effective_to: datetime | None = None
    last_confirmed_at: datetime
    is_active: bool = True
    superseded_by: int | None = None


class ObjectionItem(BaseModel):
    type: Literal["price", "shipping_time", "quality", "trust", "timing", "other"]
    quote: str
    count: int = 1
    confidence: float = 0.90
    last_seen_at: datetime


class SessionMemory(BaseModel):
    conversation_id: str
    tenant_id: str
    customer_id: str
    status: str = "BOT_ACTIVE"
    human_takeover: bool = False
    clarification_count: int = 0
    negative_feedback_count: int = 0
    last_intent: str | None = None
    detected_entities: dict[str, Any] = Field(default_factory=dict)
    pending_questions: list[str] = Field(default_factory=list)
    last_handoff_reason: str | None = None


class ConversationSummary(BaseModel):
    id: int | None = None
    tenant_id: str
    customer_id: str
    conversation_id: str
    summary_text: str
    sentiment: str = "neutral"
    intent_summary: str | None = None
    objections: list[ObjectionItem] = Field(default_factory=list)
    purchase_stage: PurchaseStage = "UNKNOWN"
    next_best_action: NextBestAction = "REPLY"
    created_at: datetime


class PsychologicalSkillDirective(BaseModel):
    skill_code: str
    skill_name: str
    academic_origin: str
    trigger_reason: str
    bubble_plan: list[str] = Field(
        description="2 to 3 bubble instructions corresponding to the 3-beat psychological blueprint"
    )
    subtle_flex_hint: str
    scarcity_or_close_hint: str
    prompt_directive: str
    audience_voice_cadence: str = ""
    price_disclosure_gate: str = "UNLOCKED_TAILORED_VALUE"


class CustomerMemoryContext(BaseModel):
    tenant_id: str
    customer_id: str
    conversation_id: str
    display_name: str | None = None
    honorific: str = "Anh/Chị"
    preferred_contact: str | None = None
    purchase_stage: PurchaseStage = "UNKNOWN"
    trust_level: float = 0.50
    session: SessionMemory
    active_facts: list[MemoryFact] = Field(default_factory=list)
    objections: list[ObjectionItem] = Field(default_factory=list)
    recent_summaries: list[ConversationSummary] = Field(default_factory=list)
    customer_memory_block: str = ""


class RecallAndAdviseRequest(BaseModel):
    tenant_id: str
    customer_id: str
    conversation_id: str
    message: str
    customer_name: str | None = None
    retrieval_quality: float = Field(default=0.85, ge=0.0, le=1.0)
    groundedness: float = Field(default=0.90, ge=0.0, le=1.0)
    domain_Metadata: dict[str, Any] = Field(default_factory=dict)
    tenant_policy: dict[str, Any] = Field(default_factory=dict)


class RecallAndAdviseResponse(BaseModel):
    tenant_id: str
    customer_id: str
    conversation_id: str
    normalized_message: str
    masked_message: str
    honorific: str
    purchase_stage: PurchaseStage
    sentiment: str
    intent: str
    buyer_archetype: str
    buying_temperature: int
    final_confidence: float
    next_best_action: NextBestAction
    requires_human: bool
    handoff_reason: str | None = None
    active_skill: PsychologicalSkillDirective
    customer_memory_block: str
    active_facts: list[MemoryFact]
    objections: list[ObjectionItem]
    latency_ms: float


class RetainTurnRequest(BaseModel):
    tenant_id: str
    customer_id: str
    conversation_id: str
    user_message: str
    assistant_reply: str | None = None
    customer_name: str | None = None
    summarize: bool = False


class RetainTurnResponse(BaseModel):
    status: str = "ok"
    extracted_facts: list[MemoryFact] = Field(default_factory=list)
    superseded_fact_ids: list[int] = Field(default_factory=list)
    objections: list[ObjectionItem] = Field(default_factory=list)
    purchase_stage: PurchaseStage
    summary: ConversationSummary | None = None


class ReflectRequest(BaseModel):
    tenant_id: str
    customer_id: str
    conversation_id: str | None = None


class ReflectResponse(BaseModel):
    tenant_id: str
    customer_id: str
    interested_items: list[str]
    primary_barriers: list[str]
    purchase_intent_level: str
    bot_assessment: str
    recommended_human_action: str
    should_call_customer: bool
    compliance_or_complaint_risk: str
    summary_markdown: str


class MemoryFeedbackRequest(BaseModel):
    tenant_id: str
    customer_id: str
    memory_type: Literal["FACT", "SUMMARY", "OBJECTION"] = "FACT"
    memory_id: int
    feedback_type: FeedbackType
    comment: str | None = None
    corrected_value: str | None = None
    created_by: str = "admin"
