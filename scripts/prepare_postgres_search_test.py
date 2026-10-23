import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("prepare-postgres-search.py")
SPEC = importlib.util.spec_from_file_location("prepare_postgres_search", SCRIPT)
CLI = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLI)


class PreparationArgumentsTest(unittest.TestCase):
    def env_file(self, content: str) -> Path:
        temp = tempfile.NamedTemporaryFile(mode="w", delete=False)
        temp.write(content)
        temp.close()
        self.addCleanup(Path(temp.name).unlink, missing_ok=True)
        return Path(temp.name)

    def test_remote_source_or_replica_is_rejected(self):
        env = self.env_file("SPRING_PROFILES_ACTIVE=local\nPG_URL=jdbc:postgresql://db.example:5432/test\n")
        with patch.dict(os.environ, {}, clear=True), self.assertRaisesRegex(ValueError, "local"):
            CLI.validate_env(env, None)

    def test_source_replica_database_must_match_without_override(self):
        env = self.env_file("SPRING_PROFILES_ACTIVE=local\nPG_URL=jdbc:postgresql://localhost:5432/source\n"
                            "PG_REPLICA_URL=jdbc:postgresql://localhost:5432/replica\n")
        with patch.dict(os.environ, {}, clear=True), self.assertRaisesRegex(ValueError, "same local"):
            CLI.validate_env(env, None)

    def test_database_override_forces_both_verified_data_sources(self):
        env = self.env_file("SPRING_PROFILES_ACTIVE=local\nPG_URL=jdbc:postgresql://localhost:5432/source\n"
                            "PG_REPLICA_URL=jdbc:postgresql://localhost:5432/replica\n"
                            "PG_USERNAME=reader\nPG_PASSWORD=secret\n")
        with patch.dict(os.environ, {}, clear=True):
            resolved = CLI.validate_env(env, "restore_copy")
        self.assertEqual(resolved["PG_URL"], "jdbc:postgresql://localhost:5432/restore_copy")
        self.assertEqual(resolved["PG_REPLICA_URL"], resolved["PG_URL"])
        self.assertEqual(resolved["SPRING_DATASOURCE_SOURCE_JDBC_URL"], resolved["PG_URL"])
        self.assertEqual(resolved["SPRING_DATASOURCE_REPLICA_JDBC_URL"], resolved["PG_URL"])
        self.assertEqual(resolved["SPRING_DATASOURCE_REPLICA_PASSWORD"], "secret")

    def test_invalid_stage_hash_fails_before_env_or_database_access(self):
        result = subprocess.run([sys.executable, str(SCRIPT), "stage", "--expected-hash", "bad",
                                 "--output-dir", "/tmp/unused-preparation-test"],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn("64-character lowercase SHA-256", result.stderr)

    def test_embed_requires_reviewed_snapshot_hash_before_environment_access(self):
        result = subprocess.run([sys.executable, str(SCRIPT), "embed", "--expected-hash", "bad",
                                 "--output-dir", "/tmp/unused-preparation-test"],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn("64-character lowercase SHA-256", result.stderr)

    def test_embed_only_accepts_loopback_ollama(self):
        CLI.validate_local_embedding_env({"EMBEDDING_PROVIDER": "ollama", "EMBEDDING_BASE_URL": "http://localhost:11434"})
        with self.assertRaisesRegex(ValueError, "EMBEDDING_PROVIDER=ollama"):
            CLI.validate_local_embedding_env({"EMBEDDING_PROVIDER": "openai"})
        with self.assertRaisesRegex(ValueError, "loopback"):
            CLI.validate_local_embedding_env({"EMBEDDING_PROVIDER": "ollama", "EMBEDDING_BASE_URL": "https://ollama.example"})

    def test_embed_accepts_pinned_local_kure_and_fills_safe_defaults(self):
        env = {"EMBEDDING_PROVIDER": "local"}
        CLI.validate_local_embedding_env(env)
        self.assertEqual(env["EMBEDDING_MODEL"], CLI.KURE_MODEL)
        self.assertEqual(env["EMBEDDING_DIMENSIONS"], "1024")
        self.assertEqual(env["EMBEDDING_BASE_URL"], "http://127.0.0.1:18081")

    def test_embed_accepts_exact_trusted_kure_container_host(self):
        CLI.validate_local_embedding_env({"EMBEDDING_PROVIDER": "local",
                                          "EMBEDDING_BASE_URL": "http://kure-embedding:18081",
                                          "EMBEDDING_TRUSTED_HOSTS": "kure-embedding"})

    def test_local_embed_rejects_model_dimension_and_non_loopback_mismatch(self):
        with self.assertRaisesRegex(ValueError, "pinned KURE"):
            CLI.validate_local_embedding_env({"EMBEDDING_PROVIDER": "local", "EMBEDDING_MODEL": "mutable/model"})
        with self.assertRaisesRegex(ValueError, "1024"):
            CLI.validate_local_embedding_env({"EMBEDDING_PROVIDER": "local", "EMBEDDING_DIMENSIONS": "1536"})
        with self.assertRaisesRegex(ValueError, "loopback"):
            CLI.validate_local_embedding_env({"EMBEDDING_PROVIDER": "local", "EMBEDDING_BASE_URL": "http://embedding.internal"})


if __name__ == "__main__":
    unittest.main()
