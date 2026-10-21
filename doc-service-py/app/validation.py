"""Upload validation: magic-byte MIME detection, SHA-256, size guard.

MIME is decided by inspecting the *bytes* (magic signatures), NEVER the file
extension, so a ``.txt``-named file whose content starts with ``%PDF`` is
correctly reported as ``application/pdf``.

A small, dependency-free byte-signature sniffer is the authoritative detector
here (it works with no native libmagic). When the optional ``python-magic``
binding + libmagic are present on the host, we use its answer as a cross-check
and only fall back to our sniffer's mapping — this keeps CI green with just the
base requirements while giving richer detection in Docker (where libmagic1 is
installed).
"""

from __future__ import annotations

import hashlib

from app.config import get_settings
from app.logging_config import get_logger

log = get_logger()

# Office Open XML + legacy office + archive all share the ZIP magic (PK\x03\x04)
# or the OLE2 magic; we disambiguate OOXML by peeking for its inner part names.
_PK_ZIP = b"PK\x03\x04"
_OLE2 = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"

GENERIC_BINARY = "application/octet-stream"


def sha256_hex(data: bytes) -> str:
    """Return the lowercase hex SHA-256 digest of ``data`` (deterministic)."""
    return hashlib.sha256(data).hexdigest()


def enforce_size(data: bytes) -> None:
    """Raise ValueError if the upload exceeds the configured size guard."""
    limit = get_settings().max_upload_bytes
    size = len(data)
    if size == 0:
        raise ValueError("empty upload")
    if size > limit:
        raise ValueError(f"upload too large: {size} bytes > limit {limit}")


def _sniff_mime(data: bytes) -> tuple[str, str]:
    """Pure-python magic-byte sniff. Returns (mime, human_reason)."""
    head = data[:2048]

    # PDF: starts with %PDF (may be preceded by a tiny BOM/whitespace).
    stripped = head.lstrip()
    if stripped.startswith(b"%PDF"):
        return "application/pdf", "leading %PDF signature"

    # Images.
    if head.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png", "PNG signature"
    if head.startswith(b"\xff\xd8\xff"):
        return "image/jpeg", "JPEG SOI marker"
    if head[:6] in (b"GIF87a", b"GIF89a"):
        return "image/gif", "GIF signature"
    if head.startswith(b"RIFF") and head[8:12] == b"WEBP":
        return "image/webp", "RIFF/WEBP signature"
    if head.startswith(b"BM"):
        return "image/bmp", "BMP signature"
    if head[:4] in (b"II*\x00", b"MM\x00*"):
        return "image/tiff", "TIFF signature"

    # ZIP container -> could be OOXML office. Disambiguate by inner parts.
    if head.startswith(_PK_ZIP):
        probe = data  # inner part names appear later in the central directory
        if b"word/" in probe:
            return (
                "application/vnd.openxmlformats-officedocument."
                "wordprocessingml.document",
                "zip container with word/ parts (docx)",
            )
        if b"ppt/" in probe:
            return (
                "application/vnd.openxmlformats-officedocument."
                "presentationml.presentation",
                "zip container with ppt/ parts (pptx)",
            )
        if b"xl/" in probe:
            return (
                "application/vnd.openxmlformats-officedocument."
                "spreadsheetml.sheet",
                "zip container with xl/ parts (xlsx)",
            )
        return "application/zip", "generic ZIP container"

    # Legacy OLE2 office (.doc/.xls/.ppt).
    if head.startswith(_OLE2):
        return "application/x-ole-storage", "OLE2 compound file (legacy office)"

    # HTML — look for a tag near the start, case-insensitive (including Google Sheets HTML exports starting with <meta, <link, <style, <table, <div).
    lowered = head[:1024].lower().lstrip()
    if lowered.startswith((
        b"<!doctype html",
        b"<html",
        b"<head",
        b"<body",
        b"<meta ",
        b"<link ",
        b"<style",
        b"<table",
        b"<div class=\"ritz",
    )) or (b"<table" in lowered and (b"<tr" in lowered or b"<td" in lowered or b"<style" in lowered)):
        return "text/html", "leading HTML/table markup"

    # Text-ish: decodable as UTF-8 with no NUL bytes -> markdown/plain text.
    if b"\x00" not in head:
        try:
            head.decode("utf-8")
        except UnicodeDecodeError:
            pass
        else:
            # Heuristic markdown hints; otherwise plain text.
            md_hints = (b"# ", b"## ", b"```", b"](", b"* ", b"- ")
            if any(h in head for h in md_hints):
                return "text/markdown", "utf-8 text with markdown markers"
            return "text/plain", "decodable utf-8 text"

    return GENERIC_BINARY, "no known signature matched"


def _magic_mime(data: bytes) -> str | None:
    """Optional cross-check via python-magic/libmagic; None if unavailable."""
    try:
        import magic  # type: ignore  # lazy: only if python-magic + libmagic exist
    except Exception:  # pragma: no cover - import guard
        return None
    try:
        return magic.from_buffer(data, mime=True)  # type: ignore[no-any-return]
    except Exception:  # pragma: no cover - runtime guard
        return None


def detect_mime(data: bytes) -> str:
    """Detect the MIME type from magic bytes (NOT the filename extension).

    Logs stage=detect-mime with the resolved mime, the sniff reason, and the
    optional libmagic cross-check result.
    """
    sniffed, reason = _sniff_mime(data)
    magic_guess = _magic_mime(data)

    # Prefer our sniffer (deterministic, dependency-free). If the sniffer could
    # not identify the bytes but libmagic did, adopt libmagic's richer answer.
    resolved = sniffed
    if sniffed == GENERIC_BINARY and magic_guess:
        resolved = magic_guess
        reason = f"libmagic fallback ({magic_guess})"

    log.info(
        "mime detected",
        stage="detect-mime",
        mime=resolved,
        reason=reason,
        libmagic=magic_guess,
    )
    return resolved
