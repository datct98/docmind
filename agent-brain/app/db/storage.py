"""SQLite / PostgreSQL storage repository with strict Tenant + Customer Isolation (Section 14.1)."""

from __future__ import annotations

import json
import sqlite3
import threading
from datetime import datetime, timezone
from typing import Any

from app.memory.conflict_resolver import should_supersede_existing_fact
from app.memory.decay_policy import is_fact_expired
from app.memory.models import (
    ConversationSummary,
    MemoryFact,
    ObjectionItem,
    PurchaseStage,
    SessionMemory,
)


class BrainStorageRepository:
    """Thread-safe relational storage implementing all 8 tables from the Memory Layer Spec."""

    def __init__(self, db_path: str = ":memory:") -> None:
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(db_path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._init_schema()

    def _init_schema(self) -> None:
        with self._lock:
            cur = self._conn.cursor()
            cur.executescript(
                """
                CREATE TABLE IF NOT EXISTS tenants (
                    id TEXT PRIMARY KEY,
                    shop_name TEXT NOT NULL,
                    platform_type TEXT NOT NULL DEFAULT 'multi_channel',
                    status TEXT NOT NULL DEFAULT 'ACTIVE',
                    persona_config TEXT NOT NULL DEFAULT '{}',
                    llm_settings TEXT NOT NULL DEFAULT '{}',
                    policy_config TEXT NOT NULL DEFAULT '{}',
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS customers (
                    id TEXT PRIMARY KEY,
                    tenant_id TEXT NOT NULL,
                    external_id TEXT NOT NULL,
                    display_name TEXT,
                    honorific TEXT DEFAULT 'Anh/Chị',
                    preferred_language TEXT DEFAULT 'vi',
                    preferred_contact TEXT,
                    purchase_stage TEXT DEFAULT 'UNKNOWN',
                    trust_level REAL DEFAULT 0.500,
                    last_interaction_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    UNIQUE (tenant_id, external_id)
                );

                CREATE TABLE IF NOT EXISTS conversations (
                    id TEXT PRIMARY KEY,
                    tenant_id TEXT NOT NULL,
                    customer_id TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'BOT_ACTIVE',
                    human_takeover INTEGER NOT NULL DEFAULT 0,
                    clarification_count INTEGER NOT NULL DEFAULT 0,
                    negative_feedback_count INTEGER NOT NULL DEFAULT 0,
                    last_intent TEXT,
                    detected_entities TEXT NOT NULL DEFAULT '{}',
                    last_handoff_reason TEXT,
                    last_message_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    conversation_id TEXT NOT NULL,
                    tenant_id TEXT NOT NULL,
                    customer_id TEXT NOT NULL,
                    sender TEXT NOT NULL,
                    content TEXT NOT NULL,
                    normalized_content TEXT,
                    intent TEXT,
                    sentiment TEXT,
                    metadata TEXT NOT NULL DEFAULT '{}',
                    created_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS memory_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    tenant_id TEXT NOT NULL,
                    customer_id TEXT NOT NULL,
                    conversation_id TEXT,
                    event_type TEXT NOT NULL,
                    payload TEXT NOT NULL DEFAULT '{}',
                    occurred_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS customer_memory_facts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    tenant_id TEXT NOT NULL,
                    customer_id TEXT NOT NULL,
                    fact_key TEXT NOT NULL,
                    fact_value TEXT NOT NULL,
                    source_type TEXT NOT NULL,
                    confidence REAL NOT NULL DEFAULT 0.500,
                    evidence_message_ids TEXT NOT NULL DEFAULT '[]',
                    effective_from TEXT NOT NULL,
                    effective_to TEXT,
                    last_confirmed_at TEXT NOT NULL,
                    is_active INTEGER NOT NULL DEFAULT 1,
                    superseded_by INTEGER,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );

                CREATE INDEX IF NOT EXISTS idx_facts_tenant_customer
                    ON customer_memory_facts (tenant_id, customer_id, is_active);

                CREATE TABLE IF NOT EXISTS customer_objections (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    tenant_id TEXT NOT NULL,
                    customer_id TEXT NOT NULL,
                    objection_type TEXT NOT NULL,
                    quote TEXT NOT NULL,
                    count INTEGER NOT NULL DEFAULT 1,
                    confidence REAL NOT NULL DEFAULT 0.90,
                    last_seen_at TEXT NOT NULL,
                    UNIQUE (tenant_id, customer_id, objection_type)
                );

                CREATE TABLE IF NOT EXISTS conversation_summaries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    tenant_id TEXT NOT NULL,
                    customer_id TEXT NOT NULL,
                    conversation_id TEXT NOT NULL,
                    summary_text TEXT NOT NULL,
                    sentiment TEXT,
                    intent_summary TEXT,
                    objections TEXT NOT NULL DEFAULT '[]',
                    purchase_stage TEXT,
                    next_best_action TEXT,
                    created_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS memory_feedback (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    tenant_id TEXT NOT NULL,
                    customer_id TEXT NOT NULL,
                    memory_type TEXT NOT NULL,
                    memory_id INTEGER NOT NULL,
                    feedback_type TEXT NOT NULL,
                    comment TEXT,
                    created_by TEXT,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
                """
            )
            self._conn.commit()

    def ensure_customer_and_conversation(
        self,
        tenant_id: str,
        customer_id: str,
        conversation_id: str,
        display_name: str | None = None,
    ) -> None:
        now_iso = datetime.now(timezone.utc).isoformat()
        with self._lock:
            cur = self._conn.cursor()
            cur.execute(
                """
                INSERT OR IGNORE INTO tenants (id, shop_name, created_at, updated_at)
                VALUES (?, ?, ?, ?)
                """,
                (tenant_id, tenant_id, now_iso, now_iso),
            )
            cur.execute(
                """
                INSERT OR IGNORE INTO customers (id, tenant_id, external_id, display_name, last_interaction_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (f"{tenant_id}:{customer_id}", tenant_id, customer_id, display_name, now_iso, now_iso, now_iso),
            )
            if display_name:
                cur.execute(
                    """
                    UPDATE customers SET display_name = COALESCE(display_name, ?), last_interaction_at = ?, updated_at = ?
                    WHERE tenant_id = ? AND external_id = ?
                    """,
                    (display_name, now_iso, now_iso, tenant_id, customer_id),
                )
            cur.execute(
                """
                INSERT OR IGNORE INTO conversations (id, tenant_id, customer_id, last_message_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (conversation_id, tenant_id, customer_id, now_iso, now_iso, now_iso),
            )
            self._conn.commit()

    def load_session(self, tenant_id: str, customer_id: str, conversation_id: str) -> SessionMemory:
        self.ensure_customer_and_conversation(tenant_id, customer_id, conversation_id)
        with self._lock:
            cur = self._conn.cursor()
            cur.execute(
                """
                SELECT * FROM conversations
                WHERE id = ? AND tenant_id = ? AND customer_id = ?
                """,
                (conversation_id, tenant_id, customer_id),
            )
            row = cur.fetchone()
            if not row:
                return SessionMemory(
                    conversation_id=conversation_id,
                    tenant_id=tenant_id,
                    customer_id=customer_id,
                )
            return SessionMemory(
                conversation_id=row["id"],
                tenant_id=row["tenant_id"],
                customer_id=row["customer_id"],
                status=row["status"],
                human_takeover=bool(row["human_takeover"]),
                clarification_count=int(row["clarification_count"]),
                negative_feedback_count=int(row["negative_feedback_count"]),
                last_intent=row["last_intent"],
                detected_entities=json.loads(row["detected_entities"] or "{}"),
                last_handoff_reason=row["last_handoff_reason"],
            )

    def update_session_state(
        self,
        tenant_id: str,
        customer_id: str,
        conversation_id: str,
        *,
        last_intent: str | None = None,
        increment_clarification: bool = False,
        reset_clarification: bool = False,
        increment_negative_feedback: bool = False,
        human_takeover: bool | None = None,
        handoff_reason: str | None = None,
        detected_entities: dict[str, Any] | None = None,
    ) -> SessionMemory:
        session = self.load_session(tenant_id, customer_id, conversation_id)
        clar_count = 0 if reset_clarification else session.clarification_count + (1 if increment_clarification else 0)
        neg_count = session.negative_feedback_count + (1 if increment_negative_feedback else 0)
        takeover = session.human_takeover if human_takeover is None else human_takeover
        merged_entities = dict(session.detected_entities)
        if detected_entities:
            merged_entities.update(detected_entities)
        now_iso = datetime.now(timezone.utc).isoformat()
        with self._lock:
            self._conn.execute(
                """
                UPDATE conversations
                SET clarification_count = ?,
                    negative_feedback_count = ?,
                    human_takeover = ?,
                    last_intent = COALESCE(?, last_intent),
                    detected_entities = ?,
                    last_handoff_reason = COALESCE(?, last_handoff_reason),
                    last_message_at = ?,
                    updated_at = ?
                WHERE id = ? AND tenant_id = ? AND customer_id = ?
                """,
                (
                    clar_count,
                    neg_count,
                    1 if takeover else 0,
                    last_intent,
                    json.dumps(merged_entities, ensure_ascii=False),
                    handoff_reason,
                    now_iso,
                    now_iso,
                    conversation_id,
                    tenant_id,
                    customer_id,
                ),
            )
            self._conn.commit()
        return self.load_session(tenant_id, customer_id, conversation_id)

    def get_customer_profile(self, tenant_id: str, customer_id: str) -> dict[str, Any]:
        with self._lock:
            cur = self._conn.cursor()
            cur.execute(
                """
                SELECT * FROM customers WHERE tenant_id = ? AND external_id = ?
                """,
                (tenant_id, customer_id),
            )
            row = cur.fetchone()
            return dict(row) if row else {}

    def update_customer_profile(
        self,
        tenant_id: str,
        customer_id: str,
        *,
        honorific: str | None = None,
        preferred_contact: str | None = None,
        purchase_stage: PurchaseStage | None = None,
        trust_delta: float = 0.0,
    ) -> None:
        now_iso = datetime.now(timezone.utc).isoformat()
        with self._lock:
            cur = self._conn.cursor()
            cur.execute(
                "SELECT trust_level FROM customers WHERE tenant_id = ? AND external_id = ?",
                (tenant_id, customer_id),
            )
            row = cur.fetchone()
            current_trust = float(row["trust_level"]) if row else 0.50
            new_trust = max(0.05, min(0.99, current_trust + trust_delta))
            cur.execute(
                """
                UPDATE customers
                SET honorific = COALESCE(?, honorific),
                    preferred_contact = COALESCE(?, preferred_contact),
                    purchase_stage = COALESCE(?, purchase_stage),
                    trust_level = ?,
                    last_interaction_at = ?,
                    updated_at = ?
                WHERE tenant_id = ? AND external_id = ?
                """,
                (
                    honorific,
                    preferred_contact,
                    purchase_stage,
                    round(new_trust, 3),
                    now_iso,
                    now_iso,
                    tenant_id,
                    customer_id,
                ),
            )
            self._conn.commit()

    def save_message(
        self,
        tenant_id: str,
        customer_id: str,
        conversation_id: str,
        sender: str,
        content: str,
        normalized_content: str | None = None,
        intent: str | None = None,
        sentiment: str | None = None,
    ) -> int:
        now_iso = datetime.now(timezone.utc).isoformat()
        with self._lock:
            cur = self._conn.cursor()
            cur.execute(
                """
                INSERT INTO messages (conversation_id, tenant_id, customer_id, sender, content, normalized_content, intent, sentiment, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (conversation_id, tenant_id, customer_id, sender, content, normalized_content, intent, sentiment, now_iso),
            )
            msg_id = int(cur.lastrowid or 0)
            self._conn.commit()
            return msg_id

    def log_event(
        self,
        tenant_id: str,
        customer_id: str,
        conversation_id: str | None,
        event_type: str,
        payload: dict[str, Any],
    ) -> None:
        now_iso = datetime.now(timezone.utc).isoformat()
        with self._lock:
            self._conn.execute(
                """
                INSERT INTO memory_events (tenant_id, customer_id, conversation_id, event_type, payload, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (tenant_id, customer_id, conversation_id, event_type, json.dumps(payload, ensure_ascii=False), now_iso),
            )
            self._conn.commit()

    def get_active_facts(self, tenant_id: str, customer_id: str) -> list[MemoryFact]:
        now = datetime.now(timezone.utc)
        with self._lock:
            cur = self._conn.cursor()
            cur.execute(
                """
                SELECT * FROM customer_memory_facts
                WHERE tenant_id = ? AND customer_id = ? AND is_active = 1
                ORDER BY confidence DESC, last_confirmed_at DESC
                """,
                (tenant_id, customer_id),
            )
            rows = cur.fetchall()
            active: list[MemoryFact] = []
            for r in rows:
                eff_to = datetime.fromisoformat(r["effective_to"]) if r["effective_to"] else None
                if is_fact_expired(eff_to, now):
                    continue
                active.append(
                    MemoryFact(
                        id=int(r["id"]),
                        tenant_id=r["tenant_id"],
                        customer_id=r["customer_id"],
                        fact_key=r["fact_key"],
                        fact_value=r["fact_value"],
                        source_type=r["source_type"],
                        confidence=float(r["confidence"]),
                        evidence_message_ids=json.loads(r["evidence_message_ids"] or "[]"),
                        effective_from=datetime.fromisoformat(r["effective_from"]),
                        effective_to=eff_to,
                        last_confirmed_at=datetime.fromisoformat(r["last_confirmed_at"]),
                        is_active=bool(r["is_active"]),
                        superseded_by=r["superseded_by"],
                    )
                )
            return active

    def upsert_fact_with_conflict_resolution(
        self,
        fact: MemoryFact,
        conversation_id: str | None = None,
    ) -> tuple[MemoryFact, list[int]]:
        """Inserts a new fact or updates/supersedes conflicting existing facts for `(tenant_id, customer_id)`."""
        now_iso = datetime.now(timezone.utc).isoformat()
        superseded_ids: list[int] = []
        with self._lock:
            existing_facts = [
                f for f in self.get_active_facts(fact.tenant_id, fact.customer_id) if f.fact_key == fact.fact_key
            ]
            cur = self._conn.cursor()

            # Check if identical fact already exists -> refresh last_confirmed_at & confidence
            for ex in existing_facts:
                if ex.fact_value.strip().lower() == fact.fact_value.strip().lower():
                    new_conf = max(ex.confidence, fact.confidence)
                    merged_ev = list(dict.fromkeys(ex.evidence_message_ids + fact.evidence_message_ids))
                    cur.execute(
                        """
                        UPDATE customer_memory_facts
                        SET confidence = ?,
                            source_type = ?,
                            evidence_message_ids = ?,
                            last_confirmed_at = ?,
                            effective_to = ?,
                            updated_at = ?
                        WHERE id = ? AND tenant_id = ? AND customer_id = ?
                        """,
                        (
                            new_conf,
                            fact.source_type,
                            json.dumps(merged_ev),
                            now_iso,
                            fact.effective_to.isoformat() if fact.effective_to else None,
                            now_iso,
                            ex.id,
                            fact.tenant_id,
                            fact.customer_id,
                        ),
                    )
                    self._conn.commit()
                    fact.id = ex.id
                    return fact, []

            # Insert new fact
            cur.execute(
                """
                INSERT INTO customer_memory_facts (
                    tenant_id, customer_id, fact_key, fact_value, source_type, confidence,
                    evidence_message_ids, effective_from, effective_to, last_confirmed_at,
                    is_active, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                """,
                (
                    fact.tenant_id,
                    fact.customer_id,
                    fact.fact_key,
                    fact.fact_value,
                    fact.source_type,
                    fact.confidence,
                    json.dumps(fact.evidence_message_ids),
                    fact.effective_from.isoformat(),
                    fact.effective_to.isoformat() if fact.effective_to else None,
                    fact.last_confirmed_at.isoformat(),
                    now_iso,
                    now_iso,
                ),
            )
            new_id = int(cur.lastrowid or 0)
            fact.id = new_id

            # Supersede conflicting old facts
            for ex in existing_facts:
                if ex.id and should_supersede_existing_fact(ex, fact, is_current_turn=True):
                    cur.execute(
                        """
                        UPDATE customer_memory_facts
                        SET is_active = 0, superseded_by = ?, updated_at = ?
                        WHERE id = ? AND tenant_id = ? AND customer_id = ?
                        """,
                        (new_id, now_iso, ex.id, fact.tenant_id, fact.customer_id),
                    )
                    superseded_ids.append(ex.id)

            self._conn.commit()

        if superseded_ids:
            self.log_event(
                fact.tenant_id,
                fact.customer_id,
                conversation_id,
                "MEMORY_CONFLICT_RESOLVED",
                {
                    "fact_key": fact.fact_key,
                    "new_value": fact.fact_value,
                    "new_fact_id": fact.id,
                    "superseded_fact_ids": superseded_ids,
                },
            )
        return fact, superseded_ids

    def record_objection(
        self,
        tenant_id: str,
        customer_id: str,
        objection_type: str,
        quote: str,
        confidence: float = 0.90,
    ) -> ObjectionItem:
        now = datetime.now(timezone.utc)
        now_iso = now.isoformat()
        with self._lock:
            cur = self._conn.cursor()
            cur.execute(
                """
                SELECT count FROM customer_objections
                WHERE tenant_id = ? AND customer_id = ? AND objection_type = ?
                """,
                (tenant_id, customer_id, objection_type),
            )
            row = cur.fetchone()
            if row:
                new_count = int(row["count"]) + 1
                cur.execute(
                    """
                    UPDATE customer_objections
                    SET count = ?, quote = ?, confidence = ?, last_seen_at = ?
                    WHERE tenant_id = ? AND customer_id = ? AND objection_type = ?
                    """,
                    (new_count, quote, confidence, now_iso, tenant_id, customer_id, objection_type),
                )
            else:
                new_count = 1
                cur.execute(
                    """
                    INSERT INTO customer_objections (tenant_id, customer_id, objection_type, quote, count, confidence, last_seen_at)
                    VALUES (?, ?, ?, ?, 1, ?, ?)
                    """,
                    (tenant_id, customer_id, objection_type, quote, confidence, now_iso),
                )
            self._conn.commit()
            return ObjectionItem(
                type=objection_type,  # type: ignore[arg-type]
                quote=quote,
                count=new_count,
                confidence=confidence,
                last_seen_at=now,
            )

    def get_objections(self, tenant_id: str, customer_id: str) -> list[ObjectionItem]:
        with self._lock:
            cur = self._conn.cursor()
            cur.execute(
                """
                SELECT * FROM customer_objections
                WHERE tenant_id = ? AND customer_id = ?
                ORDER BY count DESC, last_seen_at DESC
                """,
                (tenant_id, customer_id),
            )
            return [
                ObjectionItem(
                    type=r["objection_type"],
                    quote=r["quote"],
                    count=int(r["count"]),
                    confidence=float(r["confidence"]),
                    last_seen_at=datetime.fromisoformat(r["last_seen_at"]),
                )
                for r in cur.fetchall()
            ]

    def save_summary(self, summary: ConversationSummary) -> ConversationSummary:
        with self._lock:
            cur = self._conn.cursor()
            cur.execute(
                """
                INSERT INTO conversation_summaries (
                    tenant_id, customer_id, conversation_id, summary_text, sentiment,
                    intent_summary, objections, purchase_stage, next_best_action, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    summary.tenant_id,
                    summary.customer_id,
                    summary.conversation_id,
                    summary.summary_text,
                    summary.sentiment,
                    summary.intent_summary,
                    json.dumps([o.model_dump(mode="json") for o in summary.objections], ensure_ascii=False),
                    summary.purchase_stage,
                    summary.next_best_action,
                    summary.created_at.isoformat(),
                ),
            )
            summary.id = int(cur.lastrowid or 0)
            self._conn.commit()
            return summary

    def get_recent_summaries(self, tenant_id: str, customer_id: str, limit: int = 3) -> list[ConversationSummary]:
        with self._lock:
            cur = self._conn.cursor()
            cur.execute(
                """
                SELECT * FROM conversation_summaries
                WHERE tenant_id = ? AND customer_id = ?
                ORDER BY id DESC LIMIT ?
                """,
                (tenant_id, customer_id, limit),
            )
            results: list[ConversationSummary] = []
            for r in cur.fetchall():
                raw_objs = json.loads(r["objections"] or "[]")
                objs = [ObjectionItem(**o) for o in raw_objs]
                results.append(
                    ConversationSummary(
                        id=int(r["id"]),
                        tenant_id=r["tenant_id"],
                        customer_id=r["customer_id"],
                        conversation_id=r["conversation_id"],
                        summary_text=r["summary_text"],
                        sentiment=r["sentiment"] or "neutral",
                        intent_summary=r["intent_summary"],
                        objections=objs,
                        purchase_stage=r["purchase_stage"] or "UNKNOWN",
                        next_best_action=r["next_best_action"] or "REPLY",
                        created_at=datetime.fromisoformat(r["created_at"]),
                    )
                )
            return results

    def apply_memory_feedback(
        self,
        tenant_id: str,
        customer_id: str,
        memory_type: str,
        memory_id: int,
        feedback_type: str,
        comment: str | None,
        corrected_value: str | None,
        created_by: str,
    ) -> dict[str, Any]:
        now_iso = datetime.now(timezone.utc).isoformat()
        with self._lock:
            cur = self._conn.cursor()
            cur.execute(
                """
                INSERT INTO memory_feedback (
                    tenant_id, customer_id, memory_type, memory_id, feedback_type,
                    comment, created_by, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'APPLIED', ?, ?)
                """,
                (tenant_id, customer_id, memory_type, memory_id, feedback_type, comment, created_by, now_iso, now_iso),
            )
            if memory_type == "FACT":
                if corrected_value:
                    cur.execute(
                        """
                        UPDATE customer_memory_facts
                        SET fact_value = ?, source_type = 'human_verified', confidence = 1.0, updated_at = ?
                        WHERE id = ? AND tenant_id = ? AND customer_id = ?
                        """,
                        (corrected_value, now_iso, memory_id, tenant_id, customer_id),
                    )
                elif feedback_type in ("WRONG", "OUTDATED", "PRIVACY", "IRRELEVANT"):
                    cur.execute(
                        """
                        UPDATE customer_memory_facts
                        SET is_active = 0, updated_at = ?
                        WHERE id = ? AND tenant_id = ? AND customer_id = ?
                        """,
                        (now_iso, memory_id, tenant_id, customer_id),
                    )
            self._conn.commit()
        self.log_event(
            tenant_id,
            customer_id,
            None,
            "MEMORY_CORRECTED_BY_HUMAN",
            {
                "memory_type": memory_type,
                "memory_id": memory_id,
                "feedback_type": feedback_type,
                "corrected_value": corrected_value,
                "created_by": created_by,
            },
        )
        return {"status": "APPLIED", "memory_id": memory_id, "feedback_type": feedback_type}
