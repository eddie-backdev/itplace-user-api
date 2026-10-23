import http.client
import json
from pathlib import Path
import sys
import threading
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import serve


class StubEncoder:
    def __init__(self):
        self.calls = []

    def token_count(self, text):
        return len(text.split()) + 2

    def encode(self, texts):
        self.calls.append(list(texts))
        vector = [0.0] * serve.DIMENSIONS
        vector[0], vector[1] = 0.6, 0.8
        return [list(vector) for _ in texts]


class LocalEmbeddingApiTest(unittest.TestCase):
    def setUp(self):
        self.encoder = StubEncoder()
        self.server = serve.create_server(self.encoder, port=0)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.port = self.server.server_address[1]

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def request(self, method, path, payload=None, body=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=3)
        headers = {}
        if payload is not None:
            body = json.dumps(payload)
            headers["Content-Type"] = "application/json"
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse()
        result = response.status, json.loads(response.read())
        connection.close()
        return result

    def test_health_reports_pinned_model_and_dimension(self):
        status, body = self.request("GET", "/health")
        self.assertEqual(status, 200)
        self.assertEqual(body, {"status": "ok", "model": serve.MODEL_ID, "dimensions": 1024})

    def test_embeddings_match_openai_shape_and_normalized_float32_values(self):
        status, body = self.request("POST", "/v1/embeddings", {
            "model": serve.MODEL_ID,
            "input": ["first benefit", "second benefit"],
            "encoding_format": "float",
            "dimensions": serve.DIMENSIONS,
        })
        self.assertEqual(status, 200)
        self.assertEqual(body["model"], serve.MODEL_ID)
        self.assertEqual([row["index"] for row in body["data"]], [0, 1])
        self.assertEqual(len(body["data"][0]["embedding"]), 1024)
        self.assertEqual(body["data"][0]["embedding"][:2], [0.6, 0.8])
        self.assertEqual(body["usage"]["prompt_tokens"], 8)
        self.assertEqual(self.encoder.calls, [["first benefit", "second benefit"]])

    def test_rejects_unpinned_model_empty_texts_and_overlong_input_before_encoding(self):
        wrong_model = {"model": "nlpai-lab/KURE-v1", "input": ["text"], "dimensions": serve.DIMENSIONS}
        self.assertEqual(self.request("POST", "/v1/embeddings", wrong_model)[0], 400)
        self.assertEqual(self.request("POST", "/v1/embeddings", {"model": serve.MODEL_ID, "input": ["  "], "dimensions": serve.DIMENSIONS})[0], 400)
        too_long = "word " * (serve.MAX_TOKENS - 1)
        self.assertEqual(self.request("POST", "/v1/embeddings", {"model": serve.MODEL_ID, "input": [too_long], "dimensions": serve.DIMENSIONS})[0], 400)
        self.assertEqual(self.encoder.calls, [])

    def test_rejects_more_than_32_inputs_and_invalid_bind(self):
        payload = {"model": serve.MODEL_ID, "input": ["text"] * 33, "dimensions": serve.DIMENSIONS}
        self.assertEqual(self.request("POST", "/v1/embeddings", payload)[0], 400)
        with self.assertRaisesRegex(ValueError, "host"):
            serve.create_server(self.encoder, host="192.0.2.1", port=0)
        self.assertEqual(self.encoder.calls, [])

    def test_rejects_wrong_dimension_from_encoder(self):
        class BadEncoder(StubEncoder):
            def encode(self, texts):
                self.calls.append(list(texts))
                return [[1.0]]

        server = serve.create_server(BadEncoder(), port=0)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            conn = http.client.HTTPConnection("127.0.0.1", server.server_address[1], timeout=3)
            conn.request("POST", "/v1/embeddings", json.dumps({"model": serve.MODEL_ID, "input": ["text"], "dimensions": serve.DIMENSIONS}),
                         {"Content-Type": "application/json"})
            response = conn.getresponse()
            self.assertEqual(response.status, 500)
            self.assertEqual(json.loads(response.read())["error"]["message"], "local embedding failed")
            conn.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_rejects_requested_dimension_mismatch_including_boolean(self):
        base = {"model": serve.MODEL_ID, "input": ["text"]}
        for dimensions in (1536, True, "1024"):
            with self.subTest(dimensions=dimensions):
                status, _ = self.request("POST", "/v1/embeddings", {**base, "dimensions": dimensions})
                self.assertEqual(status, 400)
        self.assertEqual(self.encoder.calls, [])


if __name__ == "__main__":
    unittest.main()
