"""REST API routes for the standalone Agent Brain service."""

from __future__ import annotations

from typing import Any
from fastapi import APIRouter

from app.brain.insight_advisor import InsightAdvisor
from app.config import CONFIG
from app.db.storage import BrainStorageRepository
from app.memory.memory_service import MemoryService
from app.memory.models import (
    CustomerMemoryContext,
    MemoryFeedbackRequest,
    RecallAndAdviseRequest,
    RecallAndAdviseResponse,
    ReflectRequest,
    ReflectResponse,
    RetainTurnRequest,
    RetainTurnResponse,
)

router = APIRouter(prefix="/v1/brain", tags=["agent-brain"])

_repo = BrainStorageRepository(CONFIG.db_path)
_memory_service = MemoryService(_repo)
_advisor = InsightAdvisor(_memory_service)


@router.get("/health")
def health_check() -> dict[str, str]:
    return {"status": "ok", "service": "agent-brain", "version": "1.0.0"}


@router.post("/recall-and-advise", response_model=RecallAndAdviseResponse)
def recall_and_advise(req: RecallAndAdviseRequest) -> RecallAndAdviseResponse:
    return _advisor.recall_and_advise(req)


@router.post("/retain", response_model=RetainTurnResponse)
def retain_turn(req: RetainTurnRequest) -> RetainTurnResponse:
    facts, superseded_ids, objections, stage, summary = _memory_service.retain_turn(
        tenant_id=req.tenant_id,
        customer_id=req.customer_id,
        conversation_id=req.conversation_id,
        user_message=req.user_message,
        assistant_reply=req.assistant_reply,
        customer_name=req.customer_name,
        summarize=req.summarize,
    )
    return RetainTurnResponse(
        status="ok",
        extracted_facts=facts,
        superseded_fact_ids=superseded_ids,
        objections=objections,
        purchase_stage=stage,
        summary=summary,
    )


@router.post("/reflect", response_model=ReflectResponse)
def reflect_journey(req: ReflectRequest) -> ReflectResponse:
    return _memory_service.reflect_customer_journey(req.tenant_id, req.customer_id)


@router.post("/feedback")
def submit_memory_feedback(req: MemoryFeedbackRequest) -> dict[str, Any]:
    return _repo.apply_memory_feedback(
        tenant_id=req.tenant_id,
        customer_id=req.customer_id,
        memory_type=req.memory_type,
        memory_id=req.memory_id,
        feedback_type=req.feedback_type,
        comment=req.comment,
        corrected_value=req.corrected_value,
        created_by=req.created_by,
    )


@router.get("/customers/{tenant_id}/{customer_id}", response_model=CustomerMemoryContext)
def get_customer_memory(tenant_id: str, customer_id: str, conversation_id: str = "default") -> CustomerMemoryContext:
    return _memory_service.recall_customer_context(
        tenant_id=tenant_id,
        customer_id=customer_id,
        conversation_id=conversation_id,
        query="",
    )
