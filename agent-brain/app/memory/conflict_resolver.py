"""Conflict Resolution Engine for Customer Memory Facts (Section 2.3 & 9.2).

Enforces the invariant priority order:
`current_explicit_message > human_verified_fact > system_fact > recent_summary > inferred_old_fact`
"""

from __future__ import annotations

from app.memory.models import MemoryFact

_SOURCE_PRIORITY: dict[str, int] = {
    "human_verified": 4,
    "explicit": 3,
    "system": 3,
    "inferred": 1,
}

# Singular fact keys where a new value supersedes a conflicting previous value
_SINGULAR_FACT_KEYS: set[str] = {
    "color_preference",
    "preferred_size",
    "preferred_contact",
    "budget_range",
    "location_hint",
    "honorific",
    "purchase_intent",
    "selected_option",
    "target_package",
    "time_slot_preference",
}


def should_supersede_existing_fact(existing: MemoryFact, incoming: MemoryFact, is_current_turn: bool = True) -> bool:
    """Determines if `incoming` fact should supersede `existing` fact of the same `fact_key`."""
    if existing.fact_key != incoming.fact_key:
        return False

    norm_old = existing.fact_value.strip().lower()
    norm_new = incoming.fact_value.strip().lower()
    if norm_old == norm_new:
        return False

    # Rule 2.3: Current explicit user message ALWAYS overrides old facts
    if is_current_turn and incoming.source_type in ("explicit", "human_verified"):
        return True

    # For singular keys, compare source priority and confidence
    if existing.fact_key in _SINGULAR_FACT_KEYS:
        old_prio = _SOURCE_PRIORITY.get(existing.source_type, 1)
        new_prio = _SOURCE_PRIORITY.get(incoming.source_type, 1)
        if new_prio > old_prio:
            return True
        if new_prio == old_prio and incoming.confidence >= existing.confidence:
            return True

    return False
