"""FastAPI application entrypoint for standalone Agent Brain service."""

from __future__ import annotations

from fastapi import FastAPI
from app.api.routes import router as brain_router

app = FastAPI(
    title="Agent Brain - Cognitive Memory & Psychological Sales Strategy Engine",
    version="1.0.0",
    description="Reusable Memory Layer (Retain -> Recall -> Reflect) & 5 Core Psychological Sales Skills",
)

app.include_router(brain_router)


@app.get("/healthz")
async def healthz() -> dict[str, str]:
    """Container health probe."""
    return {"status": "ok", "service": "agent-brain"}

