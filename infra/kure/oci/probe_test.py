"""Model-free contract test for probe.py."""

import json
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import sys
import threading
import unittest
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parent))
import probe


class StubHandler(BaseHTTPRequestHandler):
    requests = []

    def log_message(self, *_args):
        pass

    def do_GET(self):
        body = json.dumps({"status": "ok", "model": probe.MODEL, "dimensions": probe.DIMENSIONS}).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        self.requests.append(request)
        rows = []
        for index, _query in enumerate(request["input"]):
            vector = [0.0] * probe.DIMENSIONS
            vector[0] = 1.0
            rows.append({"index": index, "embedding": vector})
        body = json.dumps({"model": probe.MODEL, "data": rows}).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class ProbeTest(unittest.TestCase):
    def test_single_query_stub_contract_without_model(self):
        server = HTTPServer(("127.0.0.1", 0), StubHandler)
        StubHandler.requests = []
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            result = probe.run(SimpleNamespace(
                base_url=f"http://127.0.0.1:{server.server_port}", timeout=2, repeats=2, warmup=1, batch_size=1,
            ))
        finally:
            server.shutdown()
            server.server_close()
            thread.join()
        self.assertEqual(result["model"], probe.MODEL)
        self.assertEqual(result["query_count"], len(probe.QUERIES))
        self.assertEqual(result["repeats"], 2)
        self.assertEqual(result["request_attempted"], 15)
        self.assertEqual(result["request_failed"], 0)
        self.assertTrue(all(len(request["input"]) == 1 for request in StubHandler.requests))
        self.assertGreaterEqual(result["request_ms"]["p95"], result["request_ms"]["p50"])

    def test_bulk_stub_reports_batch_and_request_counts(self):
        server = HTTPServer(("127.0.0.1", 0), StubHandler)
        StubHandler.requests = []
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            result = probe.run(SimpleNamespace(
                base_url=f"http://127.0.0.1:{server.server_port}", timeout=2, repeats=1, warmup=0, batch_size=4,
            ))
        finally:
            server.shutdown()
            server.server_close()
            thread.join()
        self.assertEqual(result["batch_size"], 4)
        self.assertEqual(result["request_attempted"], 2)
        self.assertEqual([len(request["input"]) for request in StubHandler.requests], [4, 1])


if __name__ == "__main__":
    unittest.main()
