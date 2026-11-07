"""Validate the owner's proposed seed as DATA, without executing any supplied SQL.

Only explicitly named development sources are opened. This command never connects
to a database; a verified Page binding is a separate prerequisite for replacement.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import uuid

from bs4 import BeautifulSoup

LITERAL = r"'((?:[^']|'')*)'"
DOC = re.compile(r"\(" + LITERAL + r"::uuid," + LITERAL + "," + LITERAL + r",(\d+)\)")
CHUNK = re.compile(r"\(" + LITERAL + r"::uuid," + LITERAL + r"::uuid," + LITERAL
                   + r"::uuid,(\d+),ARRAY\[((?:'[^']*(?:''[^']*)*'\s*,?\s*)*)\]::text\[\],"
                   + LITERAL + "," + LITERAL + r"::jsonb\)", re.DOTALL)


def unquote(value):
    return value.replace("''", "'")


def parse_values(sql, table, pattern):
    marker = "INSERT INTO " + table + " "
    start = sql.index("VALUES", sql.index(marker)) + len("VALUES")
    # The regex consumes complete quoted strings, including semicolons/newlines.
    result, offset = [], start
    while True:
        separator = re.match(r"\s*", sql[offset:])
        offset += separator.end()
        match = pattern.match(sql, offset)
        if not match:
            raise ValueError(f"Unparsed {table} data at offset {offset}")
        result.append(match.groups())
        offset = match.end()
        offset += re.match(r"\s*", sql[offset:]).end()
        if sql[offset] == ";":
            return result
        if sql[offset] != ",":
            raise ValueError("Unexpected seed delimiter")
        offset += 1


def prepare(package):
    sql = (package / "assets/db/nang_sen_replace_knowledge.sql").read_text(encoding="utf-8")
    manifest = json.loads((package / "assets/db/seed_manifest.json").read_text(encoding="utf-8"))
    docs, html_rows = [], {}
    for doc_id, filename, expected_sha, count in parse_values(sql, "_nangsen_seed_docs", DOC):
        filename = unquote(filename)
        if Path(filename).name != filename or filename not in manifest["files"]:
            raise ValueError("Unapproved source filename")
        raw = (package / "assets/business-html" / filename).read_bytes()
        sha = hashlib.sha256(raw).hexdigest()
        if sha != expected_sha:
            raise ValueError("Source checksum mismatch: " + filename)
        rows = {}
        for tr in BeautifulSoup(raw, "html.parser").select("tr"):
            cells = [c.get_text(" ", strip=True) for c in tr.find_all(["th", "td"])]
            if cells and cells[0].isdigit():
                rows[int(cells[0])] = cells[1:]
        html_rows[filename] = rows
        docs.append(dict(id=str(uuid.UUID(doc_id)), filename=filename, sha256=sha,
                         expected_chunks=int(count)))
    chunks = []
    for row in parse_values(sql, "_nangsen_seed_chunks", CHUNK):
        chunk_id, block_id, doc_id, order, headings, content, raw_meta = row
        meta = json.loads(unquote(raw_meta))
        doc = next(d for d in docs if d["id"] == doc_id)
        if meta["source_filename"] != doc["filename"]:
            raise ValueError("Document/source mismatch")
        source_rows = meta["source_row"]
        source_rows = source_rows if isinstance(source_rows, list) else [source_rows]
        if not source_rows or any(r not in html_rows[doc["filename"]] for r in source_rows):
            raise ValueError("Missing source row: " + meta["seed_key"])
        source_cells = [html_rows[doc["filename"]][r] for r in source_rows]
        content = unquote(content)
        structured = {}
        meta.setdefault("record_kind", "PROCESS_NOTE")
        if meta["record_kind"] in {"PRICE", "REGIONAL_PRICE"}:
            cells = source_cells[0]
            # Primary table columns outrank promotional prose and secondary process columns.
            code, name, price, duration = cells[:4]
            if code not in meta["service_codes"] or price not in content:
                raise ValueError("Primary price/code changed: " + meta["seed_key"])
            structured = dict(service_code=code, name=name, listed_price=price, duration=duration,
                              inclusion=cells[4], room=cells[5] if len(cells) > 5 else None)
            if "VAT 8%" not in content:
                raise ValueError("Price lacks VAT condition")
        meta.update(source_sha256=doc["sha256"], provenance_normalized=True,
                    embedding_status="FTS_ONLY", source_priority=(
                        "LOW_FAQ_CLAIM" if meta["record_kind"] == "FAQ_CLAIM"
                        else "GENERAL_PROCESS_NOT_PACKAGE_INCLUSION" if meta["record_kind"] in {"GENERAL_PROCESS", "PROCESS_NOTE"}
                        else "PRIMARY_TABLE_OR_POLICY"))
        chunks.append(dict(id=str(uuid.UUID(chunk_id)), block_id=str(uuid.UUID(block_id)),
                           document_id=doc_id, order=int(order),
                           headings=[unquote(x) for x in re.findall(LITERAL, headings)],
                           text=content, metadata=meta, structured=structured, source_cells=source_cells))
    if len(docs) != manifest["document_count"] or len(chunks) != manifest["chunk_count"]:
        raise ValueError("Manifest count mismatch")
    for key in ("id", "block_id", "order"):
        if len({c[key] for c in chunks}) != len(chunks):
            raise ValueError("Duplicate " + key)
    if len({c["metadata"]["seed_key"] for c in chunks}) != len(chunks):
        raise ValueError("Duplicate seed key")
    counts = Counter(c["document_id"] for c in chunks)
    if any(counts[d["id"]] != d["expected_chunks"] for d in docs):
        raise ValueError("Per-source count mismatch")
    return dict(schema_version=1, readiness="FTS_ONLY", binding_status="UNVERIFIED",
                documents=docs, chunks=chunks)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--package", type=Path, default=Path("artifacts/v2-contract"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = prepare(args.package)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(dict(documents=len(result["documents"]), chunks=len(result["chunks"]),
                         readiness=result["readiness"], database_mutated=False)))


if __name__ == "__main__":
    main()
