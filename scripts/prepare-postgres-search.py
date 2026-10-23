#!/usr/bin/env python3
"""Prepare local PostgreSQL benefit search, with explicit local embedding only."""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import urlsplit

WORKSPACE = Path(__file__).resolve().parents[2]
API_DIR = Path(__file__).resolve().parents[1]
DEFAULT_ENV = WORKSPACE / "userapi-local.env"
KURE_MODEL = "nlpai-lab/KURE-v1@8b418a58414668e75532ed045c22d9ca018ae2b2"


def jdbc_target(value: str, name: str) -> tuple[str, str, str, str]:
    match = re.fullmatch(r"jdbc:postgresql://([^/]+)/([^?;]+)(.*)", value or "")
    if not match:
        raise ValueError(f"{name} must be a PostgreSQL JDBC URL with an explicit database")
    authority, database, suffix = match.groups()
    parsed = urlsplit("postgresql://" + authority)
    host = (parsed.hostname or "").lower()
    if host not in {"localhost", "127.0.0.1", "::1", "host.docker.internal"}:
        raise ValueError(f"{name} must point to a local host")
    return host, str(parsed.port or 5432), database, suffix


def validate_env(env_file: Path, database_override: str | None) -> dict[str, str]:
    helper_path = WORKSPACE / "scripts" / "local-user-api.py"
    spec = importlib.util.spec_from_file_location("local_user_api", helper_path)
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    configured = helper.read_env(env_file)
    effective = os.environ.copy()
    effective.update(configured)
    helper.assert_local_database(effective)
    primary = jdbc_target(effective.get("PG_URL", ""), "PG_URL")
    replica_url = effective.get("PG_REPLICA_URL") or effective.get("PG_URL", "")
    effective["PG_REPLICA_URL"] = replica_url
    replica = jdbc_target(replica_url, "PG_REPLICA_URL")
    target_db = database_override or primary[2]
    if not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_$-]{0,62}", target_db):
        raise ValueError("database override must be a plain PostgreSQL database name")
    if primary[:2] != replica[:2] or (not database_override and primary[2] != replica[2]):
        raise ValueError("source and replica must resolve to the same local PostgreSQL host, port, and database")
    if database_override:
        for key in ("PG_URL", "PG_REPLICA_URL"):
            base = effective.get(key) or effective["PG_URL"]
            host, port, _, suffix = jdbc_target(base, key)
            authority = f"[{host}]" if ":" in host else host
            effective[key] = f"jdbc:postgresql://{authority}:{port}/{target_db}{suffix}"
    if "PG_REPLICA_USERNAME" not in effective:
        effective["PG_REPLICA_USERNAME"] = effective.get("PG_USERNAME", "")
    if "PG_REPLICA_PASSWORD" not in effective:
        effective["PG_REPLICA_PASSWORD"] = effective.get("PG_PASSWORD", "")
    if not effective.get("PG_USERNAME") or not effective.get("PG_REPLICA_USERNAME"):
        raise ValueError("source and replica PostgreSQL usernames are required")
    for role, prefix in (("SOURCE", "PG"), ("REPLICA", "PG_REPLICA")):
        effective[f"SPRING_DATASOURCE_{role}_JDBC_URL"] = effective[f"{prefix}_URL"]
        effective[f"SPRING_DATASOURCE_{role}_USERNAME"] = effective.get(f"{prefix}_USERNAME", "")
        effective[f"SPRING_DATASOURCE_{role}_PASSWORD"] = effective.get(f"{prefix}_PASSWORD", "")
    effective["SPRING_PROFILES_ACTIVE"] = "local,postgres"
    return effective


def validate_local_embedding_env(env: dict[str, str]) -> None:
    provider = env.get("EMBEDDING_PROVIDER", "openai").strip().lower()
    if provider not in {"ollama", "local"}:
        raise ValueError("embed mode requires EMBEDDING_PROVIDER=ollama or local")
    if provider == "local":
        if env.get("EMBEDDING_MODEL", KURE_MODEL) != KURE_MODEL:
            raise ValueError("local embed mode requires the pinned KURE EMBEDDING_MODEL")
        if env.get("EMBEDDING_DIMENSIONS", "1024") != "1024":
            raise ValueError("local KURE embed mode requires EMBEDDING_DIMENSIONS=1024")
        env.setdefault("EMBEDDING_MODEL", KURE_MODEL)
        env.setdefault("EMBEDDING_DIMENSIONS", "1024")
        env.setdefault("EMBEDDING_BASE_URL", "http://127.0.0.1:18081")
    endpoint = urlsplit(env.get("EMBEDDING_BASE_URL", "http://127.0.0.1:11434"))
    trusted_hosts = {host.strip().lower() for host in env.get("EMBEDDING_TRUSTED_HOSTS", "").split(",") if host.strip()}
    if (endpoint.scheme != "http" or (endpoint.hostname not in {"localhost", "127.0.0.1", "::1"}
            and (endpoint.hostname or "").lower() not in trusted_hosts)
            or endpoint.username is not None or endpoint.password is not None or endpoint.query or endpoint.fragment
            or endpoint.path not in {"", "/"}):
        raise ValueError("embed mode requires an HTTP loopback or explicitly trusted EMBEDDING_BASE_URL")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("audit", "stage", "verify", "embed"))
    parser.add_argument("--env-file", type=Path, default=DEFAULT_ENV)
    parser.add_argument("--database", help="explicit local database override for a restore rehearsal")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--expected-hash", help="reviewed SHA-256 from audit; required for stage and embed")
    args = parser.parse_args()
    if args.mode in {"stage", "embed"}:
        if not args.expected_hash or not re.fullmatch(r"[0-9a-f]{64}", args.expected_hash):
            parser.error(f"{args.mode} requires --expected-hash with a 64-character lowercase SHA-256")
    elif args.expected_hash:
        parser.error("--expected-hash is only valid with stage")
    try:
        if not args.env_file.is_file():
            raise ValueError("env file does not exist")
        child_env = validate_env(args.env_file, args.database)
        if args.mode == "embed":
            validate_local_embedding_env(child_env)
    except (OSError, ValueError) as error:
        print(f"Preparation stopped: {error}", file=sys.stderr)
        return 2

    args.output_dir.mkdir(parents=True, exist_ok=True)
    java_args = [args.mode, str(args.output_dir.resolve())]
    if args.expected_hash:
        java_args.append(args.expected_hash)
    child_env["ITPLACE_PREPARE_ARGS"] = json.dumps(java_args)
    log_path = args.output_dir / "preparation.log"
    descriptor = os.open(log_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w") as private_log:
        result = subprocess.run(["./gradlew", "--no-daemon", "-q", "preparePostgresSearch"],
                                cwd=API_DIR, env=child_env, stdout=private_log,
                                stderr=subprocess.STDOUT, text=True)
    os.chmod(log_path, 0o600)
    if result.returncode:
        print(f"Preparation failed; private diagnostics saved to {log_path}.", file=sys.stderr)
        return result.returncode
    lines = [line for line in log_path.read_text().splitlines() if line.strip().startswith("{")]
    if not lines:
        print("Preparation ended without a summary.", file=sys.stderr)
        return 1
    print(lines[-1])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
