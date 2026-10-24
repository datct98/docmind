"""Runtime configuration for doc-service-py.

Uses pydantic-settings so every value is overridable by an environment
variable. Names and defaults mirror the shared docker-compose contract
(DOCMIND_LOG_LEVEL, DOCMIND_PERSIST_ARTIFACTS) and FEAT-001's env style.
"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

import os
from typing import Any
from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Service settings (env-var backed, ${ENV:default} style)."""

    model_config = SettingsConfigDict(env_prefix="", case_sensitive=False)

    # Logging verbosity for the structured JSON logger.
    docmind_log_level: str = "INFO"

    # When true, process_parse_job writes debug artifacts (rendered page
    # images / raw per-engine output placeholders) under artifacts/<job_id>.
    docmind_persist_artifacts: bool = False

    # Bind host/port. Compose publishes 8091; keep that as the default.
    host: str = "0.0.0.0"
    port: int = 8091

    # Directory for persisted debug artifacts (relative to the service root).
    artifacts_dir: Path = Path("artifacts")

    # Upload size guard (bytes). 50 MiB default; validation enforces it.
    max_upload_bytes: int = 50 * 1024 * 1024

    # Model names reserved for later real-engine wiring (not used by stubs).
    paddleocr_lang: str = "en"
    embedding_model: str = "sentence-transformers/all-MiniLM-L6-v2"

    # HTTP header the Java orchestrator sets so logs share one correlation id.
    correlation_header: str = "X-Correlation-Id"

    # Shared HMAC secret token for internal communication between Spring Boot and FastAPI.
    # Supports both DOCMIND_INTERNAL_TOKEN and INTERNAL_API_TOKEN environment variables.
    docmind_internal_token: str = "docmind-internal-secret-token-2026"

    # Unified Multi-Provider LLM Configuration from .env
    llm_provider: str = "ollama"
    llm_base_url: str = ""
    llm_api_key: str = ""
    llm_model: str = ""
    llm_temperature: float = 0.2

    @model_validator(mode="before")
    @classmethod
    def resolve_internal_token(cls, values: Any) -> Any:
        if isinstance(values, dict):
            token = (
                os.environ.get("DOCMIND_INTERNAL_TOKEN")
                or values.get("docmind_internal_token")
                or os.environ.get("INTERNAL_API_TOKEN")
            )
            if token:
                values["docmind_internal_token"] = token
            for env_key, field_name in (
                ("LLM_PROVIDER", "llm_provider"),
                ("LLM_BASE_URL", "llm_base_url"),
                ("LLM_API_KEY", "llm_api_key"),
                ("LLM_MODEL", "llm_model"),
                ("LLM_TEMPERATURE", "llm_temperature"),
            ):
                env_val = os.environ.get(env_key)
                if env_val is not None and str(env_val).strip() != "":
                    values[field_name] = str(env_val).strip()
        return values


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Return a process-wide cached Settings instance."""
    return Settings()
