"""Canonical block models and REST DTOs matching docs/canonical-schema.md and PROJECT.md."""

from __future__ import annotations

import enum
import re
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple


class BlockType(str, enum.Enum):
    HEADING = "heading"
    PARAGRAPH = "paragraph"
    TABLE = "table"
    FORMULA = "formula"
    KEY_VALUE = "key_value"
    CHECKBOX = "checkbox"
    IMAGE = "image"
    LIST = "list"


class ParseEngine(str, enum.Enum):
    PADDLEOCR = "paddleocr"
    DOCLING = "docling"
    PYMUPDF = "pymupdf"
    OFFICE = "office"


class RuleCategory(str, enum.Enum):
    PRICING = "PRICING"
    DISCOUNT = "DISCOUNT"
    POLICY = "POLICY"
    GENERAL = "GENERAL"


@dataclass
class CanonicalBlock:
    block_id: str
    page: int
    order: int
    type: str
    text: str
    markdown: str
    html: str
    latex: Optional[str]
    bbox: List[float]
    confidence: float
    heading_path: List[str]
    parse_engine: str
    parse_version: str

    def __post_init__(self):
        # Validate latex rule: non-null ONLY when type == formula
        if self.type == BlockType.FORMULA.value:
            if self.latex is None or not isinstance(self.latex, str):
                raise ValueError("Formula blocks MUST have non-null string latex")
        else:
            if self.latex is not None:
                raise ValueError(
                    f"Non-formula block '{self.type}' MUST have null latex, got: {self.latex}"
                )
        # Validate bbox has length 4
        if len(self.bbox) != 4:
            raise ValueError(f"BBox must be 4 elements, got {self.bbox}")
        # Validate confidence is in [0.0, 1.0]
        if not (0.0 <= self.confidence <= 1.0):
            raise ValueError(f"Confidence must be between 0.0 and 1.0, got {self.confidence}")

    def to_dict(self) -> Dict[str, Any]:
        return {
            "block_id": self.block_id,
            "page": self.page,
            "order": self.order,
            "type": self.type,
            "text": self.text,
            "markdown": self.markdown,
            "html": self.html,
            "latex": self.latex,
            "bbox": self.bbox,
            "confidence": self.confidence,
            "heading_path": list(self.heading_path),
            "parse_engine": self.parse_engine,
            "parse_version": self.parse_version,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> CanonicalBlock:
        return cls(
            block_id=str(data["block_id"]),
            page=int(data["page"]),
            order=int(data["order"]),
            type=str(data["type"]),
            text=str(data.get("text", "")),
            markdown=str(data.get("markdown", "")),
            html=str(data.get("html", "")),
            latex=data.get("latex"),
            bbox=[float(x) for x in data.get("bbox", [0.0, 0.0, 0.0, 0.0])],
            confidence=float(data.get("confidence", 1.0)),
            heading_path=list(data.get("heading_path", [])),
            parse_engine=str(data.get("parse_engine", "office")),
            parse_version=str(data.get("parse_version", "1.0")),
        )


@dataclass
class Source:
    filename: str
    sha256: str
    mime: str
    pages: int

    def to_dict(self) -> Dict[str, Any]:
        return {
            "filename": self.filename,
            "sha256": self.sha256,
            "mime": self.mime,
            "pages": self.pages,
        }


@dataclass
class Stats:
    engine_breakdown: Dict[str, int]

    def to_dict(self) -> Dict[str, Any]:
        return {"engine_breakdown": self.engine_breakdown}


@dataclass
class CanonicalDocument:
    document_id: str
    source: Source
    blocks: List[CanonicalBlock]
    stats: Stats

    def to_dict(self) -> Dict[str, Any]:
        return {
            "document_id": self.document_id,
            "source": self.source.to_dict(),
            "blocks": [b.to_dict() for b in self.blocks],
            "stats": self.stats.to_dict(),
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> CanonicalDocument:
        source_data = data["source"]
        source = Source(
            filename=source_data["filename"],
            sha256=source_data["sha256"],
            mime=source_data["mime"],
            pages=int(source_data.get("pages", 1)),
        )
        blocks = [CanonicalBlock.from_dict(b) for b in data.get("blocks", [])]
        stats = Stats(engine_breakdown=dict(data.get("stats", {}).get("engine_breakdown", {})))
        return cls(
            document_id=str(data["document_id"]),
            source=source,
            blocks=blocks,
            stats=stats,
        )


@dataclass
class Chunk:
    id: str
    document_id: str
    heading_path: List[str]
    source_block_ids: List[str]
    page: int
    bbox: List[float]
    text: str


@dataclass
class LearnedRule:
    id: str
    category: str
    trigger_condition: str
    root_cause: str
    generalized_rule: str
    retrieval_keywords: List[str]
    page_id: Optional[str] = None
    active: bool = True
    created_at: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())
    updated_at: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "category": self.category,
            "trigger_condition": self.trigger_condition,
            "root_cause": self.root_cause,
            "generalized_rule": self.generalized_rule,
            "retrieval_keywords": self.retrieval_keywords,
            "page_id": self.page_id,
            "active": self.active,
            "created_at": self.created_at,
            "updated_at": self.updated_at,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> LearnedRule:
        return cls(
            id=str(data.get("id", str(uuid.uuid4()))),
            category=str(data.get("category", "GENERAL")),
            trigger_condition=str(data.get("trigger_condition", "")),
            root_cause=str(data.get("root_cause", "")),
            generalized_rule=str(data.get("generalized_rule", "")),
            retrieval_keywords=list(data.get("retrieval_keywords", [])),
            page_id=data.get("page_id"),
            active=bool(data.get("active", True)),
            created_at=str(data.get("created_at", datetime.now(timezone.utc).isoformat())),
            updated_at=data.get("updated_at"),
        )


@dataclass
class FeedbackLog:
    id: str
    question: str
    faulty_answer: str
    user_correction: str
    extracted_rule_id: Optional[str]
    page_id: Optional[str] = None
    created_at: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())


@dataclass
class Citation:
    document_id: str
    page: int
    text: str
    heading_path: List[str]
    bbox: List[float]


@dataclass
class ChatRequest:
    question: str
    document_ids: Optional[List[str]] = None
    top_k: int = 5
    previous_question: Optional[str] = None
    previous_answer: Optional[str] = None
    page_id: Optional[str] = None


@dataclass
class ChatResponse:
    answer: str
    citations: List[Dict[str, Any]]
    applied_rules: List[Dict[str, Any]]
    newly_learned_rule: Optional[Dict[str, Any]] = None


@dataclass
class FeedbackRequest:
    question: str
    faulty_answer: str
    user_correction: str
    document_ids: Optional[List[str]] = None
    page_id: Optional[str] = None
    source: Optional[str] = None  # "CHAT" or "FANPAGE_BOT"


@dataclass
class FeedbackResponse:
    learned_rule: Dict[str, Any]
    regenerated_answer: str
    applied_rules: List[Dict[str, Any]]


@dataclass
class ConversationTurn:
    role: str
    content: str

    def to_dict(self) -> Dict[str, Any]:
        return {"role": self.role, "content": self.content}

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> ConversationTurn:
        return cls(role=str(data.get("role", "user")), content=str(data.get("content", "")))


@dataclass
class FanpageBotRequest:
    message: str
    page_id: Optional[str] = None
    sender_id: Optional[str] = None
    recipient_id: Optional[str] = None
    customer_name: Optional[str] = None
    messages: Optional[List[str]] = None
    history: List[ConversationTurn] = field(default_factory=list)
    document_ids: Optional[List[str]] = None
    top_k: int = 5

    def to_dict(self) -> Dict[str, Any]:
        return {
            "page_id": self.page_id,
            "sender_id": self.sender_id,
            "recipient_id": self.recipient_id,
            "customer_name": self.customer_name,
            "message": self.message,
            "messages": self.messages,
            "history": [h.to_dict() if isinstance(h, ConversationTurn) else h for h in self.history],
            "document_ids": self.document_ids,
            "top_k": self.top_k,
        }


@dataclass
class ReplyBubble:
    text: str
    typing_delay_ms: int = 600

    def to_dict(self) -> Dict[str, Any]:
        return {"text": self.text, "typing_delay_ms": self.typing_delay_ms}

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> ReplyBubble:
        return cls(text=str(data.get("text", "")), typing_delay_ms=int(data.get("typing_delay_ms", 600)))


@dataclass
class ExtractedLead:
    has_buying_or_booking_signal: bool = False
    phone_number: Optional[str] = None
    preferred_time: Optional[str] = None
    summary_note: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "has_buying_or_booking_signal": self.has_buying_or_booking_signal,
            "phone_number": self.phone_number,
            "preferred_time": self.preferred_time,
            "summary_note": self.summary_note,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> ExtractedLead:
        return cls(
            has_buying_or_booking_signal=bool(data.get("has_buying_or_booking_signal", False)),
            phone_number=data.get("phone_number"),
            preferred_time=data.get("preferred_time"),
            summary_note=data.get("summary_note"),
        )


@dataclass
class ConsultationProfile:
    customer_name: Optional[str] = None
    customer_gender: Optional[str] = None  # MALE | FEMALE | UNKNOWN
    preferred_honorific: Optional[str] = None  # Anh | Chị | Bạn
    phone_number: Optional[str] = None
    budget_range: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "customer_name": self.customer_name,
            "customer_gender": self.customer_gender,
            "preferred_honorific": self.preferred_honorific,
            "phone_number": self.phone_number,
            "budget_range": self.budget_range,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> ConsultationProfile:
        return cls(
            customer_name=data.get("customer_name"),
            customer_gender=data.get("customer_gender"),
            preferred_honorific=data.get("preferred_honorific"),
            phone_number=data.get("phone_number"),
            budget_range=data.get("budget_range"),
        )


@dataclass
class FanpageBotResponse:
    reply_text: str
    reply_bubbles: List[ReplyBubble] = field(default_factory=list)
    reply_messages: List[str] = field(default_factory=list)
    sales_stage: str = "GREETING"
    confidence_score: float = 1.0
    needs_human_handoff: bool = False
    handoff_category: Optional[str] = None
    handoff_reason: Optional[str] = None
    extracted_lead: Optional[ExtractedLead] = None
    consultation_profile: Optional[ConsultationProfile] = None
    applied_rules: List[Dict[str, Any]] = field(default_factory=list)
    page_id: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "page_id": self.page_id,
            "reply_text": self.reply_text,
            "reply_bubbles": [b.to_dict() for b in self.reply_bubbles],
            "reply_messages": list(self.reply_messages),
            "sales_stage": self.sales_stage,
            "confidence_score": self.confidence_score,
            "needs_human_handoff": self.needs_human_handoff,
            "handoff_category": self.handoff_category,
            "handoff_reason": self.handoff_reason,
            "extracted_lead": self.extracted_lead.to_dict() if self.extracted_lead else None,
            "consultation_profile": self.consultation_profile.to_dict() if self.consultation_profile else None,
            "applied_rules": list(self.applied_rules),
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> FanpageBotResponse:
        bubbles = [ReplyBubble.from_dict(b) for b in data.get("reply_bubbles", [])]
        lead_data = data.get("extracted_lead")
        lead = ExtractedLead.from_dict(lead_data) if lead_data else None
        prof_data = data.get("consultation_profile")
        prof = ConsultationProfile.from_dict(prof_data) if prof_data else None
        messages = list(data.get("reply_messages", []))
        if not messages and bubbles:
            messages = [b.text for b in bubbles]
        return cls(
            reply_text=str(data.get("reply_text", "")),
            reply_bubbles=bubbles,
            reply_messages=messages,
            sales_stage=str(data.get("sales_stage", "GREETING")),
            confidence_score=float(data.get("confidence_score", 1.0)),
            needs_human_handoff=bool(data.get("needs_human_handoff", False)),
            handoff_category=data.get("handoff_category"),
            handoff_reason=data.get("handoff_reason"),
            extracted_lead=lead,
            consultation_profile=prof,
            applied_rules=list(data.get("applied_rules", [])),
            page_id=data.get("page_id"),
        )

