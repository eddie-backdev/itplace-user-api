#!/usr/bin/env python3
"""Serve the pinned KURE-v1 encoder through an OpenAI embeddings API."""

import argparse
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import math
from pathlib import Path
import socket
from urllib.parse import urlsplit

MODEL = "nlpai-lab/KURE-v1"
REVISION = "8b418a58414668e75532ed045c22d9ca018ae2b2"
MODEL_ID = f"{MODEL}@{REVISION}"
DIMENSIONS = 1024
MAX_TOKENS = 8192
MAX_TEXTS = 32
MAX_BODY_BYTES = 4 * 1024 * 1024
ENCODE_BATCH_SIZE = 4
DEFAULT_STATE = Path.home() / ".local/state/itplace/kure-local-20261023"


class KureEncoder:
    def __init__(self, model):
        self.model = model
        self.tokenizer = model.tokenizer

    def token_count(self, text: str) -> int:
        return len(self.tokenizer.encode(text, add_special_tokens=True, truncation=False))

    def encode(self, texts: list[str]) -> list[list[float]]:
        vectors = self.model.encode(
            texts,
            batch_size=ENCODE_BATCH_SIZE,
            convert_to_numpy=True,
            normalize_embeddings=True,
            show_progress_bar=False,
        )
        return vectors.astype("float32", copy=False).tolist()


def select_device(requested: str, torch) -> str:
    if requested == "auto":
        return "mps" if torch.backends.mps.is_available() else "cpu"
    if requested == "mps" and not torch.backends.mps.is_available():
        raise ValueError("MPS was requested but is unavailable")
    return requested


def load_encoder(device: str, threads: int, cache_dir: Path) -> KureEncoder:
    import torch
    from sentence_transformers import SentenceTransformer

    selected = select_device(device, torch)
    torch.set_num_threads(threads)
    model = SentenceTransformer(
        MODEL,
        revision=REVISION,
        cache_folder=str(cache_dir),
        device=selected,
        model_kwargs={"torch_dtype": torch.float32},
        trust_remote_code=False,
    )
    model.max_seq_length = MAX_TOKENS
    return KureEncoder(model)


def create_server(encoder, host: str = "127.0.0.1", port: int = 18081) -> HTTPServer:
    if host not in ("127.0.0.1", "0.0.0.0"):
        raise ValueError("KURE server host must be 127.0.0.1 or 0.0.0.0")

    class Handler(BaseHTTPRequestHandler):
        server_version = "KureEmbedding/1"
        sys_version = ""

        def setup(self):
            super().setup()
            self.connection.settimeout(10)

        def log_message(self, _format, *_args):
            # Access logs include request paths and can accidentally expose user data.
            pass

        def respond(self, status: int, payload: dict):
            body = json.dumps(payload, separators=(",", ":"), allow_nan=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def fail(self, status: int, message: str):
            self.respond(status, {"error": {"message": message, "type": "invalid_request_error"}})

        def do_GET(self):
            if urlsplit(self.path).path != "/health":
                self.close_connection = True
                self.fail(404, "not found")
                return
            self.respond(200, {"status": "ok", "model": MODEL_ID, "dimensions": DIMENSIONS})

        def do_POST(self):
            if urlsplit(self.path).path != "/v1/embeddings":
                self.close_connection = True
                self.fail(404, "not found")
                return
            raw_length = self.headers.get("Content-Length", "")
            try:
                length = int(raw_length)
            except ValueError:
                self.fail(411, "content length is required")
                return
            if length < 0 or length > MAX_BODY_BYTES:
                self.fail(413, "request body exceeds the size limit")
                return
            if not self.headers.get("Content-Type", "").lower().startswith("application/json"):
                self.fail(415, "application/json is required")
                return
            try:
                raw_body = self.rfile.read(length)
                if len(raw_body) != length:
                    self.close_connection = True
                    self.fail(400, "request body was incomplete")
                    return
                payload = json.loads(raw_body)
            except socket.timeout:
                self.close_connection = True
                self.fail(408, "request body timed out")
                return
            except (UnicodeDecodeError, json.JSONDecodeError):
                self.fail(400, "request body must be valid JSON")
                return
            if not isinstance(payload, dict):
                self.fail(400, "request body must be an object")
                return
            if payload.get("model") != MODEL_ID:
                self.fail(400, "requested model does not match the pinned local model")
                return
            dimensions = payload.get("dimensions")
            if isinstance(dimensions, bool) or not isinstance(dimensions, int) or dimensions != DIMENSIONS:
                self.fail(400, f"dimensions must be the pinned {DIMENSIONS}-dimension output")
                return
            if payload.get("encoding_format", "float") != "float":
                self.fail(400, "only float encoding is supported")
                return
            texts = payload.get("input")
            if not isinstance(texts, list) or not 1 <= len(texts) <= MAX_TEXTS:
                self.fail(400, f"input must contain between 1 and {MAX_TEXTS} texts")
                return
            if any(not isinstance(text, str) or not text.strip() for text in texts):
                self.fail(400, "input texts must be non-empty strings")
                return
            try:
                counts = [encoder.token_count(text) for text in texts]
            except Exception:
                self.fail(400, "input could not be tokenized")
                return
            if any(count > MAX_TOKENS for count in counts):
                self.fail(400, f"input exceeds the {MAX_TOKENS}-token limit; truncation is disabled")
                return
            try:
                vectors = encoder.encode(texts)
                if not isinstance(vectors, list) or len(vectors) != len(texts):
                    raise ValueError("embedding count does not match request")
                rows = []
                for index, vector in enumerate(vectors):
                    if not isinstance(vector, list) or len(vector) != DIMENSIONS:
                        raise ValueError("embedding dimension does not match pinned model")
                    if any(isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value)
                           for value in vector):
                        raise ValueError("embedding contains an invalid value")
                    if not any(value != 0 for value in vector):
                        raise ValueError("embedding must not be all zero")
                    rows.append({"object": "embedding", "index": index, "embedding": vector})
            except Exception as error:
                # Do not log exception text, which may contain request data.
                print(f"KURE embedding failed ({type(error).__name__})", flush=True)
                self.respond(500, {"error": {"message": "local embedding failed", "type": "server_error"}})
                return
            self.respond(200, {
                "object": "list",
                "data": rows,
                "model": MODEL_ID,
                "usage": {"prompt_tokens": sum(counts), "total_tokens": sum(counts)},
            })

    return HTTPServer((host, port), Handler)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", choices=("auto", "cpu", "mps"), default="auto")
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--host", choices=("127.0.0.1", "0.0.0.0"), default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18081)
    parser.add_argument("--state", type=Path, default=DEFAULT_STATE)
    args = parser.parse_args()
    if args.threads < 1:
        parser.error("--threads must be positive")
    if not 1 <= args.port <= 65535:
        parser.error("--port must be between 1 and 65535")
    encoder = load_encoder(args.device, args.threads, args.state / "model-cache")
    server = create_server(encoder, host=args.host, port=args.port)
    print(f"KURE embeddings ready at http://{args.host}:{args.port} model={MODEL_ID} device={encoder.model.device}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
