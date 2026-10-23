#!/usr/bin/env python3
"""Audit snapshot text-embedding-3-small inputs offline; requires `python -m pip install tiktoken`."""

import argparse
import hashlib
import json
from pathlib import Path
import sys

MODEL = "text-embedding-3-small"
ENCODING = "cl100k_base"
MAX_INPUT_TOKENS = 8192
MAX_REQUEST_TOKENS = 300_000
BATCH_SIZE = 32


def percentile(sorted_values: list[int], percent: int) -> int | None:
    if not sorted_values:
        return None
    rank = max(1, (percent * len(sorted_values) + 99) // 100)
    return sorted_values[rank - 1]


def audit_snapshot(snapshot: dict, source_sha256: str, encode) -> dict:
    documents = snapshot.get("pendingDocuments")
    if not isinstance(documents, list):
        raise ValueError("snapshot must contain a pendingDocuments array")

    errors = []
    ids = set()
    active_counts = []
    active_ids = []
    empty_text_ids = []
    inactive = 0
    for index, document in enumerate(documents):
        if not isinstance(document, dict):
            errors.append(f"pendingDocuments[{index}] is not an object")
            continue
        document_id = document.get("documentId")
        if not isinstance(document_id, str) or not document_id:
            errors.append(f"pendingDocuments[{index}] has no documentId")
        elif document_id in ids:
            errors.append(f"duplicate documentId: {document_id}")
        else:
            ids.add(document_id)

        is_active = document.get("active")
        if not isinstance(is_active, bool):
            errors.append(f"{document_id or index}: active must be boolean")
            continue
        if not is_active:
            inactive += 1
            continue

        text = document.get("searchableText")
        if not isinstance(text, str) or not text.strip():
            empty_text_ids.append(document_id or str(index))
            errors.append(f"{document_id or index}: active searchableText is empty")
            continue
        token_count = len(encode(text))
        active_counts.append(token_count)
        active_ids.append(document_id)
        if token_count > MAX_INPUT_TOKENS:
            errors.append(f"{document_id}: {token_count} tokens exceeds per-input limit {MAX_INPUT_TOKENS}")

    batches = [active_counts[start:start + BATCH_SIZE] for start in range(0, len(active_counts), BATCH_SIZE)]
    batch_totals = [sum(batch) for batch in batches]
    for batch_index, token_count in enumerate(batch_totals):
        if token_count > MAX_REQUEST_TOKENS:
            errors.append(f"batch {batch_index + 1}: {token_count} tokens exceeds request limit {MAX_REQUEST_TOKENS}")

    ordered_counts = sorted(active_counts)
    max_tokens = max(active_counts, default=0)
    longest_id = active_ids[active_counts.index(max_tokens)] if active_counts else None
    return {
        "model": MODEL,
        "encoding": ENCODING,
        "sourceSha256": source_sha256,
        "documentCount": len(documents),
        "activeDocumentCount": sum(1 for document in documents if isinstance(document, dict) and document.get("active") is True),
        "inactiveDocumentCount": inactive,
        "activeInputCount": len(active_counts),
        "emptyActiveTextDocumentIds": empty_text_ids,
        "tokenStats": {
            "total": sum(active_counts),
            "maximum": max_tokens,
            "percentiles": {f"p{p}": percentile(ordered_counts, p) for p in (50, 90, 95, 99)},
            "longestDocumentId": longest_id,
        },
        "limits": {"perInputTokens": MAX_INPUT_TOKENS, "perRequestTokens": MAX_REQUEST_TOKENS,
                   "batchSize": BATCH_SIZE, "batchCount": len(batches),
                   "maximumBatchTokens": max(batch_totals, default=0)},
        "errors": errors,
        "ok": not errors,
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source_snapshot", type=Path, help="source-snapshot.json from the preparation audit")
    parser.add_argument("--output", type=Path, help="write report JSON here instead of stdout")
    args = parser.parse_args(argv)
    try:
        raw = args.source_snapshot.read_bytes()
        snapshot = json.loads(raw)
    except (OSError, json.JSONDecodeError) as error:
        parser.error(f"cannot read source snapshot: {error}")
    try:
        import tiktoken
    except ImportError:
        parser.error("tiktoken is required; install it with: python -m pip install tiktoken")
    report = audit_snapshot(snapshot, hashlib.sha256(raw).hexdigest(), tiktoken.get_encoding(ENCODING).encode)
    output = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(output, encoding="utf-8")
    else:
        sys.stdout.write(output)
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
