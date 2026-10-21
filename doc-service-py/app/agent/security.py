"""Defense-in-Depth Security Layer for FastAPI + LangGraph Agent Engine.

Provides:
1. Constant-time HMAC internal token verification (`hmac.compare_digest`).
2. PII Masking (Vietnamese phone numbers, emails, street addresses).
3. AI Security Auditor for Nightly Cold-Path extraction to block Indirect
   Prompt Injection (memory poisoning) and unauthorized discount/price rules.
"""

from __future__ import annotations

import hmac
import re
from dataclasses import dataclass, field
from typing import Any

from app.config import get_settings

# Vietnamese phone number pattern (supports spaces/dots/dashes)
_VN_PHONE_RE = re.compile(
    r"(?<!\d)(?:\+84|84|0)(?:3[2-9]|5[25689]|7[06-9]|8[1-9]|9[0-46-9])(?:[\s.\-]?\d){7}(?!\d)"
)
_EMAIL_RE = re.compile(r"\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b")
_ADDRESS_RE = re.compile(
    r"(?i)\b(?:số\s+\d+[A-Za-z0-9/\-]*\s+(?:đường|phố|ngõ|hẻm)\s+[^\n,;.]{3,40})"
)

# Indirect Prompt Injection / Memory Poisoning signatures
_INJECTION_PATTERNS: list[tuple[re.Pattern[str], str]] = [
    (
        re.compile(
            r"(?i)(?:ignore\s+(?:all\s+)?(?:previous|prior|above)\s+instructions|"
            r"disregard\s+system\s+prompt|reveal\s+(?:your\s+)?(?:system\s+prompt|api\s*key)|"
            r"bỏ\s+qua\s+(?:mọi|tất\s+cả|toàn\s+bộ)\s+(?:quy\s+tắc|hướng\s+dẫn|lệnh)|"
            r"quên\s+(?:hết|tất\s+cả)\s+(?:quy\s+tắc|hướng\s+dẫn)|"
            r"tiết\s+lộ\s+(?:system\s+prompt|api\s*key|mã\s+bí\s+mật)|"
            r"jailbreak|dan\s+mode|developer\s+override)"
        ),
        "INDIRECT_PROMPT_INJECTION",
    ),
    (
        re.compile(
            r"(?i)(?:customer_psid\s*=\s*['\"]?(?:all|\*|admin)|"
            r"truy\s+cập\s+đơn\s+hàng\s+của\s+khách\s+khác|"
            r"bypass\s+psid)"
        ),
        "CROSS_TENANT_PSID_BYPASS",
    ),
]

# Unauthorized discount / zero-price promises (excludes legitimate "miễn phí soi da AI 3D")
_UNAUTHORIZED_DISCOUNT_RE = re.compile(
    r"(?i)(?:giảm\s+(?:giá\s+)?(?:[3-9]\d|100)\s*%|"
    r"chiết\s+khấu\s+(?:[3-9]\d|100)\s*%|"
    r"miễn\s+phí\s+(?:100%\s+)?(?:toàn\s+bộ|tất\s+cả|mọi)\s+(?:liệu\s+trình|dịch\s+vụ|gói)|"
    r"(?:báo\s+giá|bán|đồng\s+giá)\s+0\s*(?:đ|vnđ|vnd|đồng)\s+cho\s+(?:mọi|tất\s+cả|toàn\s+bộ))"
)


def verify_internal_token(provided_token: str | None) -> bool:
    """Verify X-Internal-Token or Authorization Bearer using constant-time hmac.compare_digest."""
    expected = (get_settings().docmind_internal_token or "").strip()
    if not expected:
        return True
    if not provided_token:
        return False
    token_str = provided_token.strip()
    if token_str.lower().startswith("bearer "):
        token_str = token_str[7:].strip()
    return hmac.compare_digest(token_str.encode("utf-8"), expected.encode("utf-8"))


def mask_pii(text: str | None) -> tuple[str, dict[str, Any]]:
    """Mask PII (phone numbers, emails, street addresses) in text.

    Returns (masked_text, metadata_dict_with_extracted_pii).
    """
    if not text:
        return "", {"phones": [], "emails": [], "pii_masked": False}

    phones: list[str] = []
    for match in _VN_PHONE_RE.finditer(text):
        normalized = re.sub(r"[\s.\-]", "", match.group(0))
        if normalized not in phones:
            phones.append(normalized)

    emails = list(dict.fromkeys(_EMAIL_RE.findall(text)))

    masked = _VN_PHONE_RE.sub("[PHONE_REDACTED]", text)
    masked = _EMAIL_RE.sub("[EMAIL_REDACTED]", masked)
    masked = _ADDRESS_RE.sub("[ADDRESS_REDACTED]", masked)

    return masked, {
        "phones": phones,
        "emails": emails,
        "pii_masked": masked != text,
    }


@dataclass
class SecurityAuditVerdict:
    passed: bool
    risk_level: str = "LOW"
    violations: list[str] = field(default_factory=list)
    audit_notes: str = ""


def audit_extracted_sample(
    cue_signal: str,
    generalized_rule: str,
    contrastive_evidence: str | None = None,
) -> SecurityAuditVerdict:
    """Independent Security Auditor node check for Cold-Path extracted rules/samples."""
    combined = f"{cue_signal or ''}\n{generalized_rule or ''}\n{contrastive_evidence or ''}"
    violations: list[str] = []

    for pattern, code in _INJECTION_PATTERNS:
        if pattern.search(combined):
            violations.append(code)

    if _UNAUTHORIZED_DISCOUNT_RE.search(combined):
        violations.append("UNAUTHORIZED_DISCOUNT_OR_PRICE_PROMISE")

    # Ensure raw customer phone numbers are never hardcoded into a generalized rule
    if _VN_PHONE_RE.search(generalized_rule or ""):
        violations.append("HARDCODED_CUSTOMER_PII_IN_RULE")

    if violations:
        return SecurityAuditVerdict(
            passed=False,
            risk_level="HIGH",
            violations=violations,
            audit_notes=(
                "AI Security Auditor CHẶN quy tắc do phát hiện rủi ro bảo mật/nghiệp vụ: "
                + ", ".join(violations)
            ),
        )

    return SecurityAuditVerdict(
        passed=True,
        risk_level="LOW",
        violations=[],
        audit_notes="AI Security Auditor: Đạt chuẩn an toàn (0 Prompt Injection, 0 sai lệch chính sách giá, 0 lộ PII).",
    )
