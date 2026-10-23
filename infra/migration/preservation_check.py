#!/usr/bin/env python3
"""Read-only row-count and content-hash comparison for the PG18 cutover clone."""

from __future__ import annotations

import argparse
import json
import pathlib
import shlex
import subprocess
import sys


TABLES = (
    "authcredential", "benefitusagerecord", "crawlrun", "exceptionword", "favorite",
    "forbiddenword", "inquiries", "partneridentityreview", "recommendation_benefits",
    "recommendation_rank_traces", "recommendations", "socialaccount", "store",
    "tierbenefit", "users",
)
EMPTY_LEGACY_TABLES = ("membershiphistory", "membership", "uplusdata")
DEFAULT_KEY = "~/.ssh/prod-app-server.key"


class CheckError(Exception):
    pass


def quote_ident(identifier: str) -> str:
    return '"' + identifier.replace('"', '""') + '"'


def projected_row_sql(table: str, source_columns: list[str], available_columns: list[str] | None = None) -> str:
    """Build JSON from projected fields once each, avoiding repeated geometry serialization."""
    available = set(source_columns if available_columns is None else available_columns)
    ignored = {"membershipid", "membershipgradecode", "membershipGradeCode"} if table == "users" else set()
    kept = [column for column in source_columns if column not in ignored]
    pairs = []
    for column in kept:
        value = f"row_data.{quote_ident(column)}"
        if table == "store" and column == "location":
            value = f"encode(ST_AsEWKB({value}), 'hex')"
        pairs.extend([_sql_text(column), value])
    if table != "users":
        return f"jsonb_build_object({', '.join(pairs)})"
    grades = [column for column in ("membershipgradecode", "membershipGradeCode") if column in available]
    grade = "COALESCE(" + ", ".join(f"row_data.{quote_ident(column)}" for column in grades) + ")" if grades else "NULL"
    pairs.extend(["'effective_grade'", grade])
    return f"jsonb_build_object({', '.join(pairs)})"


def _sql_text(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def metadata_sql(table: str) -> str:
    return ("SELECT column_name FROM information_schema.columns "
            "WHERE table_schema='public' AND table_name=" + _sql_text(table) +
            " ORDER BY ordinal_position")


def table_exists_sql(table: str) -> str:
    return ("SELECT EXISTS (SELECT 1 FROM information_schema.tables "
            "WHERE table_schema='public' AND table_name=" + _sql_text(table) + ")")


def digest_sql(table: str, columns: list[str], available_columns: list[str] | None = None) -> str:
    if not columns:
        raise CheckError(f"table has no columns: {table}")
    row_expr = projected_row_sql(table, columns, available_columns)
    return (
        "WITH projected AS (SELECT " + row_expr + " AS row_value FROM public." + quote_ident(table) + " row_data), "
        "row_hashes AS (SELECT md5(row_value::text) AS row_hash FROM projected) "
        "SELECT (SELECT count(*) FROM projected)::text || E'\\t' || "
        "COALESCE(md5(string_agg(row_hash, '' ORDER BY row_hash)), '') FROM row_hashes"
    )


class RemotePsql:
    def __init__(self, host: str, key: pathlib.Path, container: str, database: str, user: str):
        self.host, self.key = host, key.expanduser()
        self.container, self.database, self.user = container, database, user

    def query(self, sql: str) -> str:
        remote = " ".join(shlex.quote(arg) for arg in [
            "docker", "exec", "-e", "PGOPTIONS=-c statement_timeout=30000", self.container,
            "psql", "-X", "-A", "-t", "-F", "\t",
            "-v", "ON_ERROR_STOP=1", "-U", self.user, "-d", self.database, "-c", sql,
        ])
        command = ["ssh", "-i", str(self.key), "-o", "BatchMode=yes",
                   "-o", "StrictHostKeyChecking=yes", self.host, remote]
        try:
            result = subprocess.run(command, check=True, text=True, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, timeout=45)
        except (OSError, subprocess.SubprocessError) as exc:
            # Suppress remote diagnostics: never risk exposing row values or connection details.
            raise CheckError(f"remote read failed for container {self.container} ({type(exc).__name__})") from None
        return result.stdout.rstrip("\r\n")

    def columns(self, table: str) -> list[str]:
        if self.query(table_exists_sql(table)) != "t":
            raise CheckError(f"required table absent in {self.container}: {table}")
        value = self.query(metadata_sql(table))
        columns = value.splitlines() if value else []
        if not columns:
            raise CheckError(f"required table has no columns in {self.container}: {table}")
        return columns

    def digest(self, table: str, columns: list[str]) -> tuple[int, str]:
        value = self.query(digest_sql(table, columns))
        try:
            count, digest = value.split("\t", 1)
            return int(count), digest
        except (ValueError, TypeError):
            raise CheckError(f"invalid digest result for {self.container}:{table}") from None


def verify_legacy_state(source: RemotePsql, target: RemotePsql) -> dict:
    legacy = {}
    for table in EMPTY_LEGACY_TABLES:
        if source.query(table_exists_sql(table)) != "t":
            raise CheckError(f"expected legacy table absent in source: {table}")
        count, _ = source.digest(table, source.columns(table))
        legacy[table] = count
        if count:
            raise CheckError(f"legacy table must be empty: {table} has {count} rows")
        if target.query(table_exists_sql(table)) == "t":
            target_count, _ = target.digest(table, target.columns(table))
            if target_count:
                raise CheckError(f"legacy table must be absent or empty in target: {table} has {target_count} rows")

    columns = source.columns("users")
    if "membershipid" not in columns:
        raise CheckError("source users.membershipid column is absent")
    nulls = source.query('SELECT count(*) FILTER (WHERE "membershipid" IS NULL)::text || E\'\\t\' || count(*)::text FROM public."users"')
    try:
        null_count, user_count = (int(value) for value in nulls.split("\t", 1))
    except (ValueError, TypeError):
        raise CheckError("could not verify users.membershipid null count") from None
    if null_count != user_count:
        raise CheckError("source users.membershipid contains non-null values")
    if "membershipid" in target.columns("users"):
        raise CheckError("target users.membershipid was not removed")
    return {"empty_legacy_tables": legacy, "users_membershipid_null": null_count}


def run(args) -> tuple[dict, bool]:
    if args.source_container == args.target_container:
        raise CheckError("source and target containers must differ")
    if args.target_container != "postgresql18-migration":
        raise CheckError("target must be the prepared postgresql18-migration container")
    source = RemotePsql(args.ssh_host, pathlib.Path(args.ssh_key), args.source_container, args.database, args.db_user)
    target = RemotePsql(args.ssh_host, pathlib.Path(args.ssh_key), args.target_container, args.database, args.db_user)

    legacy = verify_legacy_state(source, target)
    results = []
    matches = True
    for table in TABLES:
        source_columns = source.columns(table)
        target_columns = target.columns(table)
        required_target = [column for column in source_columns
                           if table != "users" or column not in {"membershipid", "membershipgradecode", "membershipGradeCode"}]
        if table != "users":
            missing = sorted(set(source_columns) - set(target_columns))
        else:
            missing = sorted(set(required_target) - set(target_columns))
        if missing:
            raise CheckError(f"target missing projected columns in {table}: {','.join(missing)}")

        source_count, source_hash = source.digest(table, source_columns)
        target_value = target.query(digest_sql(table, source_columns, target_columns))
        try:
            target_count_text, target_hash = target_value.split("\t", 1)
            target_count = int(target_count_text)
        except (ValueError, TypeError):
            raise CheckError(f"invalid digest result for {target.container}:{table}") from None
        same = source_count == target_count and source_hash == target_hash
        matches &= same
        results.append({"table": table, "source_rows": source_count, "target_rows": target_count, "match": same})
    return {"legacy_checks": legacy, "tables": results}, matches


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ssh-host", default="ubuntu@134.185.107.168")
    parser.add_argument("--ssh-key", default=DEFAULT_KEY)
    parser.add_argument("--source-container", default="postgresql")
    parser.add_argument("--target-container", default="postgresql18-migration")
    parser.add_argument("--database", default="itplace")
    parser.add_argument("--db-user", default="postgres")
    args = parser.parse_args()
    try:
        summary, matches = run(args)
    except CheckError as exc:
        print(json.dumps({"status": "FAIL", "reason": str(exc)}, ensure_ascii=False))
        return 2
    summary["status"] = "PASS" if matches else "MISMATCH"
    print(json.dumps(summary, ensure_ascii=False, separators=(",", ":")))
    return 0 if matches else 1


if __name__ == "__main__":
    raise SystemExit(main())
