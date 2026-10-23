"""Memory Decay & TTL Policy per Fact Category (Section 9.1 & 14.3)."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone


def compute_expiry_for_fact(
    fact_key: str,
    source_type: str,
    now: datetime | None = None,
) -> datetime | None:
    """Computes `effective_to` timestamp according to fact stability and source reliability."""
    base = now or datetime.now(timezone.utc)
    key = (fact_key or "").lower()

    # Complaint history never decays automatically (or 365 days minimum)
    if "complaint" in key or "refund" in key:
        return None

    # Purchase intent decays quickly (48-72h) unless re-confirmed
    if "purchase_intent" in key or "time_slot" in key or "hold_request" in key:
        hours = 48 if source_type == "inferred" else 72
        return base + timedelta(hours=hours)

    # Recent objection decays in 7-14 days
    if "objection" in key:
        days = 7 if source_type == "inferred" else 14
        return base + timedelta(days=days)

    # Budget range decays at medium speed (30-60 days)
    if "budget" in key or "price_anchor" in key:
        days = 21 if source_type == "inferred" else 45
        return base + timedelta(days=days)

    # Stable customer preferences (size, color, contact channel, location, honorific)
    if source_type == "inferred":
        return base + timedelta(days=30)
    return base + timedelta(days=180)


def is_fact_expired(effective_to: datetime | None, now: datetime | None = None) -> bool:
    if effective_to is None:
        return False
    current = now or datetime.now(timezone.utc)
    if effective_to.tzinfo is None:
        effective_to = effective_to.replace(tzinfo=timezone.utc)
    return current >= effective_to
