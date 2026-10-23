#!/usr/bin/env python3
"""Verify and measure the pinned KURE HTTP embeddings service."""

import argparse
import json
import math
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit

MODEL = "nlpai-lab/KURE-v1@8b418a58414668e75532ed045c22d9ca018ae2b2"
DIMENSIONS = 1024
QUERIES = (
    "편의점에서 받을 수 있는 할인 혜택 알려줘",
    "영화관 할인과 예매 혜택이 궁금해",
    "통신사 멤버십으로 카페에서 할인받는 방법",
    "주말에 가족과 외식할 때 쓸 수 있는 제휴 혜택",
    "대중교통이나 택시 이용 시 받을 수 있는 포인트 혜택",
)


class ProbeFailure(Exception):
    def __init__(self, message, report):
        super().__init__(message)
        self.report = report


def request_json(url, payload=None, timeout=30):
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode()
    request = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"} if data else {})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)


def percentile(values, fraction):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(fraction * len(ordered)) - 1)]


def run(args):
    base = args.base_url.rstrip("/")
    url = urlsplit(base)
    if url.scheme not in ("http", "https") or not url.netloc:
        raise ValueError("--base-url must be an HTTP(S) URL")
    health = request_json(base + "/health", timeout=args.timeout)
    if health.get("status") != "ok" or health.get("model") != MODEL or health.get("dimensions") != DIMENSIONS:
        raise ValueError("health response does not match pinned KURE model and dimensions")

    report = {
        "base_url": base,
        "model": MODEL,
        "dimensions": DIMENSIONS,
        "query_count": len(QUERIES),
        "queries": list(QUERIES),
        "batch_size": args.batch_size,
        "warmup": args.warmup,
        "repeats": args.repeats,
        "status": "running",
        "request_attempted": 0,
        "request_failed": 0,
        "request_succeeded": 0,
        "request_ms": None,
        "measurement": "per-HTTP-request latency for fixed queries; excludes retrieval/ranking",
    }
    timings = []
    chunks = [QUERIES[i:i + args.batch_size] for i in range(0, len(QUERIES), args.batch_size)]
    for run_number in range(args.warmup + args.repeats):
        for chunk in chunks:
            report["request_attempted"] += 1
            started = time.perf_counter()
            try:
                result = request_json(base + "/v1/embeddings", {
                    "model": MODEL, "dimensions": DIMENSIONS, "input": list(chunk), "encoding_format": "float",
                }, timeout=args.timeout)
                if result.get("model") != MODEL:
                    raise ValueError("embedding response model does not match pin")
                rows = result.get("data")
                if not isinstance(rows, list) or len(rows) != len(chunk):
                    raise ValueError("embedding count does not match request query count")
                for index, row in enumerate(rows):
                    if row.get("index") != index:
                        raise ValueError("embedding indices are missing or out of order")
                    vector = row.get("embedding")
                    if not isinstance(vector, list) or len(vector) != DIMENSIONS:
                        raise ValueError("embedding dimension does not match 1024")
                    if any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) for v in vector):
                        raise ValueError("embedding contains non-finite or non-numeric values")
                    norm = math.sqrt(sum(v * v for v in vector))
                    if not math.isfinite(norm) or abs(norm - 1.0) > 0.01:
                        raise ValueError("embedding is not normalized")
            except (urllib.error.URLError, TimeoutError, ValueError, KeyError, TypeError, AttributeError) as error:
                report["request_failed"] += 1
                report["status"] = "failed"
                report["error"] = {"type": type(error).__name__, "message": str(error)}
                raise ProbeFailure(str(error), report) from error
            report["request_succeeded"] += 1
            elapsed_ms = (time.perf_counter() - started) * 1000
            if run_number >= args.warmup:
                timings.append(elapsed_ms)

    report["status"] = "ok"
    report["request_ms"] = {"p50": round(percentile(timings, 0.50), 2), "p95": round(percentile(timings, 0.95), 2)}
    return report


def write_report(path, report):
    output = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if path:
        with open(path, "w", encoding="utf-8") as stream:
            stream.write(output)
    else:
        sys.stdout.write(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18081")
    parser.add_argument("--repeats", type=int, default=5)
    parser.add_argument("--warmup", type=int, default=1)
    parser.add_argument("--batch-size", type=int, default=1, help="queries per HTTP request (1-5)")
    parser.add_argument("--timeout", type=float, default=8, help="per-request timeout in seconds")
    parser.add_argument("--out", help="write JSON result to this path; stdout otherwise")
    args = parser.parse_args()
    if args.repeats < 1 or args.warmup < 0 or not 1 <= args.batch_size <= len(QUERIES) or args.timeout <= 0:
        parser.error("--repeats must be positive, --warmup non-negative, --batch-size 1-5, and --timeout positive")
    try:
        write_report(args.out, run(args))
        return 0
    except ProbeFailure as error:
        if args.out:
            write_report(args.out, error.report)
        print(f"probe failed: {type(error).__name__}: {error}", file=sys.stderr)
        return 1
    except (urllib.error.URLError, TimeoutError, ValueError, KeyError, TypeError, AttributeError) as error:
        print(f"probe failed: {type(error).__name__}: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
