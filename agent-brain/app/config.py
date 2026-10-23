"""Configuration settings for Agent Brain service."""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class BrainConfig:
    db_path: str = os.getenv("AGENT_BRAIN_DB_PATH", ":memory:")
    postgres_dsn: str | None = os.getenv("AGENT_BRAIN_POSTGRES_DSN")
    max_memory_block_tokens: int = int(os.getenv("AGENT_BRAIN_MAX_MEMORY_TOKENS", "500"))
    max_summary_tokens: int = int(os.getenv("AGENT_BRAIN_MAX_SUMMARY_TOKENS", "350"))
    default_purchase_intent_ttl_hours: int = 72
    default_objection_ttl_days: int = 14
    default_preference_ttl_days: int = 180
    auto_reply_confidence_threshold: float = 0.80
    clarify_confidence_threshold: float = 0.65


CONFIG = BrainConfig()
