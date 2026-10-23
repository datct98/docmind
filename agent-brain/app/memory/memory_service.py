"""Core MemoryService implementing Retain -> Recall -> Reflect (Section 15 & 22)."""

from __future__ import annotations

from datetime import datetime, timezone

from app.config import CONFIG
from app.db.storage import BrainStorageRepository
from app.memory.fact_extractor import ExtractedTurnSignals, extract_turn_signals
from app.memory.models import (
    ConversationSummary,
    CustomerMemoryContext,
    MemoryFact,
    ObjectionItem,
    PurchaseStage,
    ReflectResponse,
)
from app.memory.normalizer import mask_sensitive_pii


class MemoryService:
    """Manages short-term Session Memory, structured Customer Facts, Objections, and Episodic Summaries."""

    def __init__(self, repo: BrainStorageRepository) -> None:
        self._repo = repo

    def recall_customer_context(
        self,
        tenant_id: str,
        customer_id: str,
        conversation_id: str,
        query: str,
        customer_name: str | None = None,
        limit: int = 8,
    ) -> CustomerMemoryContext:
        session = self._repo.load_session(tenant_id, customer_id, conversation_id)
        profile = self._repo.get_customer_profile(tenant_id, customer_id)
        active_facts = self._repo.get_active_facts(tenant_id, customer_id)[:limit]
        objections = self._repo.get_objections(tenant_id, customer_id)
        summaries = self._repo.get_recent_summaries(tenant_id, customer_id, limit=2)

        honorific = profile.get("honorific") or "Anh/Chị"
        for f in active_facts:
            if f.fact_key == "honorific" and f.fact_value:
                honorific = f.fact_value
                break

        preferred_contact = profile.get("preferred_contact")
        for f in active_facts:
            if f.fact_key == "preferred_contact" and f.fact_value:
                preferred_contact = f.fact_value
                break

        stage: PurchaseStage = profile.get("purchase_stage") or "UNKNOWN"  # type: ignore[assignment]
        trust_level = float(profile.get("trust_level", 0.50))

        memory_block = self._format_compact_memory_block(
            honorific=honorific,
            stage=stage,
            trust_level=trust_level,
            preferred_contact=preferred_contact,
            facts=active_facts,
            objections=objections,
            summaries=summaries,
        )

        return CustomerMemoryContext(
            tenant_id=tenant_id,
            customer_id=customer_id,
            conversation_id=conversation_id,
            display_name=customer_name or profile.get("display_name"),
            honorific=honorific,
            preferred_contact=preferred_contact,
            purchase_stage=stage,
            trust_level=trust_level,
            session=session,
            active_facts=active_facts,
            objections=objections,
            recent_summaries=summaries,
            customer_memory_block=memory_block,
        )

    def retain_turn(
        self,
        tenant_id: str,
        customer_id: str,
        conversation_id: str,
        user_message: str,
        assistant_reply: str | None = None,
        customer_name: str | None = None,
        summarize: bool = False,
    ) -> tuple[list[MemoryFact], list[int], list[ObjectionItem], PurchaseStage, ConversationSummary | None]:
        self._repo.ensure_customer_and_conversation(tenant_id, customer_id, conversation_id, customer_name)

        masked_user, _ = mask_sensitive_pii(user_message)
        signals: ExtractedTurnSignals = extract_turn_signals(
            tenant_id=tenant_id,
            customer_id=customer_id,
            raw_message=user_message,
        )
        msg_id = self._repo.save_message(
            tenant_id=tenant_id,
            customer_id=customer_id,
            conversation_id=conversation_id,
            sender="customer",
            content=masked_user,
            normalized_content=signals.normalized_text,
            intent=signals.intent,
            sentiment=signals.sentiment,
        )

        if assistant_reply:
            masked_bot, _ = mask_sensitive_pii(assistant_reply)
            self._repo.save_message(
                tenant_id=tenant_id,
                customer_id=customer_id,
                conversation_id=conversation_id,
                sender="assistant",
                content=masked_bot,
            )

        stored_facts: list[MemoryFact] = []
        superseded_ids: list[int] = []
        for f in signals.facts:
            f.evidence_message_ids = [msg_id]
            saved_f, sup_ids = self._repo.upsert_fact_with_conflict_resolution(f, conversation_id=conversation_id)
            stored_facts.append(saved_f)
            superseded_ids.extend(sup_ids)

        for obj_type, quote, conf in signals.objections:
            masked_quote, _ = mask_sensitive_pii(quote)
            self._repo.record_objection(tenant_id, customer_id, obj_type, masked_quote, conf)

        # Update session counters & customer stage/trust
        is_clarify_reply = bool(
            assistant_reply
            and ("ý mình là" in assistant_reply.lower() or "chưa rõ ý" in assistant_reply.lower())
        )
        self._repo.update_session_state(
            tenant_id,
            customer_id,
            conversation_id,
            last_intent=signals.intent,
            increment_clarification=is_clarify_reply or signals.is_bot_misunderstanding,
            increment_negative_feedback=signals.is_bot_misunderstanding or signals.is_complaint_or_refund,
        )

        trust_delta = -0.15 if signals.is_complaint_or_refund else (0.05 if signals.purchase_stage == "READY_TO_BUY" else 0.0)
        honorific = next((f.fact_value for f in stored_facts if f.fact_key == "honorific"), None)
        pref_contact = next((f.fact_value for f in stored_facts if f.fact_key == "preferred_contact"), None)
        self._repo.update_customer_profile(
            tenant_id,
            customer_id,
            honorific=honorific,
            preferred_contact=pref_contact,
            purchase_stage=signals.purchase_stage,
            trust_delta=trust_delta,
        )

        all_objections = self._repo.get_objections(tenant_id, customer_id)
        summary_obj: ConversationSummary | None = None
        if summarize:
            summary_obj = self.summarize_conversation(tenant_id, customer_id, conversation_id)

        return stored_facts, superseded_ids, all_objections, signals.purchase_stage, summary_obj

    def summarize_conversation(
        self,
        tenant_id: str,
        customer_id: str,
        conversation_id: str,
    ) -> ConversationSummary:
        facts = self._repo.get_active_facts(tenant_id, customer_id)
        objections = self._repo.get_objections(tenant_id, customer_id)
        profile = self._repo.get_customer_profile(tenant_id, customer_id)
        stage: PurchaseStage = profile.get("purchase_stage") or "CURIOUS"  # type: ignore[assignment]

        fact_snippets = [f"{f.fact_key}={f.fact_value}" for f in facts[:5]]
        obj_snippets = [f"{o.type} (x{o.count})" for o in objections]
        parts: list[str] = []
        if fact_snippets:
            parts.append("Dữ kiện khách: " + ", ".join(fact_snippets))
        if obj_snippets:
            parts.append("Rào cản: " + ", ".join(obj_snippets))
        parts.append(f"Giai đoạn: {stage}")
        summary_text = ". ".join(parts) + "."

        summary = ConversationSummary(
            tenant_id=tenant_id,
            customer_id=customer_id,
            conversation_id=conversation_id,
            summary_text=summary_text,
            sentiment="negative" if stage == "COMPLAINING" else "neutral",
            intent_summary=stage,
            objections=objections,
            purchase_stage=stage,
            next_best_action="HANDOFF" if stage in ("COMPLAINING", "READY_TO_BUY") else "REPLY",
            created_at=datetime.now(timezone.utc),
        )
        return self._repo.save_summary(summary)

    def reflect_customer_journey(self, tenant_id: str, customer_id: str) -> ReflectResponse:
        """Generates structured 7-point sales reflection for human handoff / follow-up (Section 11.3)."""
        facts = self._repo.get_active_facts(tenant_id, customer_id)
        objections = self._repo.get_objections(tenant_id, customer_id)
        profile = self._repo.get_customer_profile(tenant_id, customer_id)
        stage = profile.get("purchase_stage") or "UNKNOWN"
        pref_contact = profile.get("preferred_contact") or "chat"

        interests = [
            f"{f.fact_key}: {f.fact_value}"
            for f in facts
            if f.fact_key in ("product_interest", "symptom_or_need", "preferred_size", "color_preference", "price_anchor")
        ]
        barriers = [f"{o.type} (lặp lại {o.count} lần): \"{o.quote}\"" for o in objections]
        intent_fact = next((f.fact_value for f in facts if f.fact_key == "purchase_intent"), None)
        intent_level = intent_fact or (
            "Cao (Sẵn sàng chốt)" if stage == "READY_TO_BUY" else "Trung bình - Đang tìm hiểu/So sánh"
        )

        has_complaint = any(f.fact_key == "complaint_history" for f in facts)
        price_obj_count = next((o.count for o in objections if o.type == "price"), 0)
        should_call = pref_contact == "phone" or price_obj_count >= 2 or stage == "READY_TO_BUY"

        if has_complaint:
            action = "Quản lý/CSKH liên hệ xử lý khiếu nại ngay lập tức, tuyệt đối không gửi tin nhắn bán hàng tự động."
            risk = "CAO: Khách có lịch sử phản ánh/khiếu nại, cần ưu tiên xoa dịu cảm xúc."
        elif price_obj_count >= 2:
            action = "Nhân viên Sale chủ động tư vấn gói trải nghiệm mềm nhất hoặc kiểm tra suất ưu đãi trực tiếp, tránh để bot lặp lại báo giá."
            risk = "Thấp - Khách nhạy cảm về giá."
        elif stage == "HESITATING":
            action = "Giữ phiếu ưu tiên tạm thời cho khách, nhắc nhẹ đúng 1 lần vào khung giờ khách hẹn, không spam."
            risk = "Thấp."
        else:
            action = "Tiếp tục hỗ trợ chốt khung giờ/phương án thực thi cụ thể."
            risk = "Không có rủi ro đặc biệt."

        md = (
            f"- **Mối quan tâm chính:** {', '.join(interests) if interests else 'Đang tìm hiểu chung'}\n"
            f"- **Rào cản chính:** {', '.join(barriers) if barriers else 'Chưa phát hiện rào cản lớn'}\n"
            f"- **Mức độ ý định mua:** {intent_level}\n"
            f"- **Hành động đề xuất cho Sale:** {action}\n"
            f"- **Khuyến nghị gọi điện:** {'Có' if should_call else 'Chưa cần thiết (ưu tiên chat)'}"
        )

        return ReflectResponse(
            tenant_id=tenant_id,
            customer_id=customer_id,
            interested_items=interests or ["Đang khám phá danh mục"],
            primary_barriers=barriers or ["Chưa có rào cản"],
            purchase_intent_level=intent_level,
            bot_assessment=f"Giai đoạn hiện tại: {stage} (Độ tin cậy khách hàng: {profile.get('trust_level', 0.5):.2f})",
            recommended_human_action=action,
            should_call_customer=should_call,
            compliance_or_complaint_risk=risk,
            summary_markdown=md,
        )

    def _format_compact_memory_block(
        self,
        *,
        honorific: str,
        stage: PurchaseStage,
        trust_level: float,
        preferred_contact: str | None,
        facts: list[MemoryFact],
        objections: list[ObjectionItem],
        summaries: list[ConversationSummary],
    ) -> str:
        lines: list[str] = [
            f"- Xưng hô đã xác nhận: {honorific} | Giai đoạn mua: {stage} | Độ tin cậy: {trust_level:.2f}"
        ]
        if preferred_contact:
            lines.append(f"- Kênh liên hệ ưa thích: {preferred_contact}")
        if facts:
            fact_items = [
                f"{f.fact_key}={f.fact_value} ({f.source_type}, conf={f.confidence:.2f})"
                for f in facts
                if f.fact_key not in ("honorific", "preferred_contact")
            ]
            if fact_items:
                lines.append("- Sự thật đã ghi nhận: " + "; ".join(fact_items[:6]))
        if objections:
            obj_items = [f"{o.type} (x{o.count})" for o in objections]
            lines.append("- Rào cản tâm lý đã nêu: " + ", ".join(obj_items))
        if summaries:
            lines.append(f"- Tóm tắt phiên trước: {summaries[0].summary_text}")

        block = "\n".join(lines)
        max_chars = CONFIG.max_memory_block_tokens * 3
        return block[:max_chars]
