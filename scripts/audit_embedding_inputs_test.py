import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).with_name("audit-embedding-inputs.py")
SPEC = importlib.util.spec_from_file_location("audit_embedding_inputs", SCRIPT)
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


def fake_encode(text):
    return range(len(text))


def document(document_id, text, active=True):
    return {"documentId": document_id, "searchableText": text, "active": active}


class EmbeddingInputAuditTest(unittest.TestCase):
    def test_exact_input_limit_passes_and_next_token_fails(self):
        ok = AUDIT.audit_snapshot({"pendingDocuments": [document("at-limit", "x" * 8192)]}, "a" * 64, fake_encode)
        over = AUDIT.audit_snapshot({"pendingDocuments": [document("over-limit", "x" * 8193)]}, "b" * 64, fake_encode)
        self.assertTrue(ok["ok"])
        self.assertEqual(ok["tokenStats"]["maximum"], 8192)
        self.assertFalse(over["ok"])
        self.assertIn("over-limit", over["errors"][0])

    def test_inactive_is_excluded_from_counts_tokens_and_32_document_batches(self):
        docs = [document(f"doc-{i}", "xx") for i in range(32)]
        docs.insert(7, document("inactive", "x" * 9000, active=False))
        docs.append(document("doc-32", "xxx"))
        report = AUDIT.audit_snapshot({"pendingDocuments": docs}, "c" * 64, fake_encode)
        self.assertTrue(report["ok"])
        self.assertEqual(report["documentCount"], 34)
        self.assertEqual(report["activeDocumentCount"], 33)
        self.assertEqual(report["inactiveDocumentCount"], 1)
        self.assertEqual(report["tokenStats"]["total"], 67)
        self.assertEqual(report["limits"]["batchCount"], 2)
        self.assertEqual(report["limits"]["maximumBatchTokens"], 64)

    def test_duplicate_ids_and_empty_active_text_fail(self):
        report = AUDIT.audit_snapshot({"pendingDocuments": [
            document("same", "x"), document("same", "y"), document("empty", "   ")
        ]}, "d" * 64, fake_encode)
        self.assertFalse(report["ok"])
        self.assertTrue(any("duplicate documentId" in error for error in report["errors"]))
        self.assertEqual(report["emptyActiveTextDocumentIds"], ["empty"])


if __name__ == "__main__":
    unittest.main()
