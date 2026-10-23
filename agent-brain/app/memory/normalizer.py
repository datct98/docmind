"""Vietnamese conversational normalizer & PII defense layer."""

from __future__ import annotations

import re

_SLANG_MAP: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r"(?i)(?<!\w)trc(?!\w)"), "trước"),
    (re.compile(r"(?i)(?<!\w)1b(?!\w)"), "1 buổi"),
    (re.compile(r"(?i)(?<!\w)(\d+)b(?!\w)"), r"\1 buổi"),
    (re.compile(r"(?i)(?<!\w)km(?!\w)"), "khuyến mãi"),
    (re.compile(r"(?i)(?<!\w)ko(?!\w)"), "không"),
    (re.compile(r"(?i)(?<!\w)k(?!\w)"), "không"),
    (re.compile(r"(?i)(?<!\w)j(?!\w)"), "gì"),
    (re.compile(r"(?i)(?<!\w)bn(?!\w)"), "bao nhiêu"),
    (re.compile(r"(?i)(?<!\w)đc(?!\w)"), "được"),
    (re.compile(r"(?i)(?<!\w)dc(?!\w)"), "được"),
    (re.compile(r"(?i)(?<!\w)ib(?!\w)"), "nhắn tin"),
    (re.compile(r"(?i)(?<!\w)sdt(?!\w)"), "số điện thoại"),
]

_PHONE_RE = re.compile(r"(?<!\d)(?:0|\+84)[35789]\d(?:[\s.\-]?\d){7}(?!\d)")
_EMAIL_RE = re.compile(r"\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b")
_CARD_OR_SECRET_RE = re.compile(r"\b(?:\d[ -]*?){13,19}\b|sk-[A-Za-z0-9_\-]{16,}")


def normalize_vietnamese_message(text: str) -> str:
    if not text or not text.strip():
        return ""
    out = text.strip()
    for pattern, replacement in _SLANG_MAP:
        out = pattern.sub(replacement, out)
    return re.sub(r"\s+", " ", out).strip()


def mask_sensitive_pii(text: str) -> tuple[str, dict[str, list[str]]]:
    """Masks phone numbers, emails, credit cards, and API keys for safe logging & storage."""
    if not text:
        return "", {}
    phones = [re.sub(r"[\s.\-]", "", m.group(0)) for m in _PHONE_RE.finditer(text)]
    emails = [m.group(0) for m in _EMAIL_RE.finditer(text)]

    masked = _CARD_OR_SECRET_RE.sub("[REDACTED_SECRET]", text)
    masked = _EMAIL_RE.sub("[MASKED_EMAIL]", masked)

    def _mask_phone(m: re.Match[str]) -> str:
        raw = re.sub(r"[\s.\-]", "", m.group(0))
        if len(raw) >= 7:
            return f"{raw[:4]}***{raw[-3:]}"
        return "[MASKED_PHONE]"

    masked = _PHONE_RE.sub(_mask_phone, masked)
    meta: dict[str, list[str]] = {}
    if phones:
        meta["phones"] = phones
    if emails:
        meta["emails"] = emails
    return masked, meta
