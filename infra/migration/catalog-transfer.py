#!/usr/bin/env python3
"""Build a one-shot compressed catalog import for a freshly restored isolated PG clone."""

from __future__ import annotations

import argparse
import csv
import gzip
import io
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from typing import Callable, Iterable, Sequence


TABLES = (
    "membership_program",
    "membership_grade",
    "membership_grade_alias",
    "membership_grade_match",
    "partner_category",
    "partner_category_alias",
    "benefitpolicy",
    "partner",
    "partneralias",
    "carrierpartneridentity",
    "rawbenefitsource",
    "benefit",
    "benefitcarrierpolicy",
    "carriertierbenefit",
    "benefitnormalizationrevision",
    "benefitsnapshotimportstate",
)

IDENTITY_QUERIES = {
    "partners": "SELECT partnerid, partnername FROM public.partner ORDER BY partnerid",
    "benefits": "SELECT benefitid, canonicalkey, partnerid, benefitname FROM public.benefit ORDER BY benefitid",
    "policies": "SELECT benefitcarrierpolicyid, benefitid, carrier, sourcekey, sourceurl "
                "FROM public.benefitcarrierpolicy ORDER BY benefitcarrierpolicyid",
}


@dataclass(frozen=True)
class IdMap:
    source_to_target: dict[int, int]
    target_keys: dict[int, tuple]


def normalize_name(value: str) -> str:
    return value.strip().lower()


def remap_canonical_partner_prefix(value: str, source_partner_id: int, target_partner_id: int) -> str:
    """Keep canonical benefit keys aligned when their leading partner ID is remapped."""
    prefix = f"{source_partner_id}:"
    return f"{target_partner_id}:{value[len(prefix):]}" if source_partner_id != target_partner_id and value.startswith(prefix) else value


def build_id_map(
    source_rows: Sequence[Sequence],
    target_rows: Sequence[Sequence],
    source_id: Callable[[Sequence], int],
    target_id: Callable[[Sequence], int],
    source_key: Callable[[Sequence], tuple],
    target_key: Callable[[Sequence], tuple],
) -> IdMap:
    """Map natural identities first, keep free source IDs, allocate above both sets."""
    target_by_key: dict[tuple, int] = {}
    target_keys: dict[int, tuple] = {}
    for row in target_rows:
        key, ident = target_key(row), int(target_id(row))
        if key in target_by_key or ident in target_keys:
            raise ValueError("duplicate identity in target catalog snapshot")
        target_by_key[key] = ident
        target_keys[ident] = key

    source_by_key: dict[tuple, int] = {}
    source_ids: set[int] = set()
    for row in source_rows:
        key, ident = source_key(row), int(source_id(row))
        if key in source_by_key or ident in source_ids:
            raise ValueError("duplicate identity in source catalog snapshot")
        source_by_key[key] = ident
        source_ids.add(ident)

    occupied = set(target_keys)
    mapping: dict[int, int] = {}
    next_id = max(source_ids | occupied, default=0) + 1
    for row in sorted(source_rows, key=lambda item: int(source_id(item))):
        ident, key = int(source_id(row)), source_key(row)
        existing = target_by_key.get(key)
        if existing is not None:
            mapping[ident] = existing
        elif ident not in occupied:
            mapping[ident] = ident
            occupied.add(ident)
        else:
            while next_id in occupied:
                next_id += 1
            mapping[ident] = next_id
            occupied.add(next_id)
            next_id += 1
    if len(set(mapping.values())) != len(mapping):
        raise ValueError("catalog identity map is not one-to-one")
    return IdMap(mapping, target_keys)


def build_catalog_maps(dev: dict, prod: dict) -> dict[str, IdMap]:
    partners = build_id_map(
        dev["partners"], prod["partners"], lambda r: r[0], lambda r: r[0],
        lambda r: (normalize_name(str(r[1])),), lambda r: (normalize_name(str(r[1])),),
    )
    dev_benefits = [
        (int(r[0]), remap_canonical_partner_prefix(str(r[1]), int(r[2]), partners.source_to_target[int(r[2])]), partners.source_to_target[int(r[2])], r[3])
        for r in dev["benefits"]
    ]
    prod_benefits = [(int(r[0]), str(r[1]), int(r[2]), r[3]) for r in prod["benefits"]]
    benefits = build_ambiguous_benefit_map(dev_benefits, prod_benefits)
    dev_policies = [
        (int(r[0]), benefits.source_to_target[int(r[1])], str(r[2]).upper(), str(r[3]), r[4])
        for r in dev["policies"]
    ]
    prod_policies = [(int(r[0]), int(r[1]), str(r[2]).upper(), str(r[3]), r[4]) for r in prod["policies"]]
    policies = build_id_map(
        dev_policies, prod_policies, lambda r: r[0], lambda r: r[0],
        lambda r: (r[2], r[3]), lambda r: (r[2], r[3]),
    )
    return {"partners": partners, "benefits": benefits, "policies": policies}


def build_ambiguous_benefit_map(source_rows: Sequence[Sequence], target_rows: Sequence[Sequence]) -> IdMap:
    """Benefit natural keys may repeat; retain same-ID exact matches, then pair remaining identities."""
    targets: dict[tuple, list[int]] = {}
    target_keys: dict[int, tuple] = {}
    for row in target_rows:
        key, ident = (int(row[2]), str(row[1])), int(row[0])
        targets.setdefault(key, []).append(ident)
        if ident in target_keys:
            raise ValueError("duplicate benefit ID in target catalog snapshot")
        target_keys[ident] = key
    sources: dict[tuple, list[int]] = {}
    source_keys: dict[int, tuple] = {}
    for row in source_rows:
        key, ident = (int(row[2]), str(row[1])), int(row[0])
        sources.setdefault(key, []).append(ident)
        if ident in source_keys:
            raise ValueError("duplicate benefit ID in source catalog snapshot")
        source_keys[ident] = key

    for key, ids in targets.items():
        if len(ids) > len(sources.get(key, ())):
            raise ValueError("development catalog is missing a production benefit identity")

    occupied = set(target_keys)
    mapping: dict[int, int] = {}
    next_id = max(set(source_keys) | occupied, default=0) + 1
    for key, ids in sources.items():
        remaining = set(targets.get(key, ()))
        # Exact same-ID identities take precedence, preserving stable IDs wherever possible.
        for source_id in ids:
            if source_id in remaining:
                mapping[source_id] = source_id
                remaining.remove(source_id)
        unmatched = [source_id for source_id in ids if source_id not in mapping]
        available = sorted(remaining)
        for source_id, target_id in zip(unmatched, available):
            mapping[source_id] = target_id
        unmatched = unmatched[len(available):]
        for source_id in unmatched:
            if source_id not in occupied:
                mapping[source_id] = source_id
                occupied.add(source_id)
            else:
                while next_id in occupied:
                    next_id += 1
                mapping[source_id] = next_id
                occupied.add(next_id)
                next_id += 1
    if len(set(mapping.values())) != len(mapping):
        raise ValueError("benefit identity map is not one-to-one")
    return IdMap(mapping, target_keys)


def _sql_literal(value) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, (int, float)):
        return str(value)
    return "'" + str(value).replace("'", "''") + "'"


def _quoted(identifier: str) -> str:
    if not re.fullmatch(r"[a-z_][a-z0-9_]*", identifier):
        raise ValueError(f"unsafe catalog identifier: {identifier!r}")
    return '"' + identifier + '"'


def _values(rows: Iterable[Sequence]) -> str:
    return ",\n".join("(" + ", ".join(_sql_literal(v) for v in row) + ")" for row in rows)


def _psql_command(container: str, database: str, user: str, sql: str) -> list[str]:
    return ["docker", "exec", "-i", container, "psql", "-X", "-A", "-t", "-U", user, "-d", database, "-c", sql]


def _query_rows(container: str, database: str, user: str, sql: str) -> list[list[str | None]]:
    copy_sql = f"COPY ({sql}) TO STDOUT WITH (FORMAT csv, NULL '\\N')"
    result = subprocess.run(
        _psql_command(container, database, user, copy_sql),
        check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    return [
        [None if cell == "\\N" else cell for cell in row]
        for row in csv.reader(io.StringIO(result.stdout.decode("utf-8")))
    ]


def _query_metadata(container: str, database: str, user: str, sql: str) -> list[list[str]]:
    command = _psql_command(container, database, user, sql)
    command[command.index("-c"):command.index("-c") + 1] = ["-F", "\t", "-c"]
    result = subprocess.run(
        command,
        check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    return [line.split("\t") for line in result.stdout.splitlines() if line]


def _writable_columns(metadata: Sequence[Sequence[str]]) -> list[str]:
    return [row[0] for row in metadata if row[1] == "NEVER" and not (row[2] == "YES" and row[3] == "ALWAYS")]


def _validate_snapshot_shape(snapshot: dict, name: str, width: int) -> None:
    if not isinstance(snapshot.get(name), list) or any(
        not isinstance(row, list) or len(row) != width for row in snapshot[name]
    ):
        raise ValueError(f"invalid {name} identity snapshot shape")


def _assert_identity_snapshot(snapshot: dict, container: str, database: str, user: str, label: str) -> None:
    widths = {"partners": 2, "benefits": 4, "policies": 5}
    if set(snapshot) != set(widths):
        raise ValueError(f"{label} identity snapshot must contain only partners, benefits, and policies")
    for name, query in IDENTITY_QUERIES.items():
        _validate_snapshot_shape(snapshot, name, widths[name])
        actual = _query_rows(container, database, user, query)
        expected = [[None if value is None else str(value) for value in row] for row in snapshot[name]]
        if len(actual) != len(expected) or {tuple(row) for row in actual} != {tuple(row) for row in expected}:
            raise ValueError(f"{label} {name} identities do not match the saved snapshot")


def _table_metadata(container: str, database: str, user: str, table: str) -> tuple[list[str], list[str]]:
    columns = _query_metadata(
        container, database, user,
        "SELECT column_name, is_generated, is_identity, COALESCE(identity_generation,'') "
        "FROM information_schema.columns "
        f"WHERE table_schema='public' AND table_name={_sql_literal(table)} ORDER BY ordinal_position",
    )
    if not columns:
        raise ValueError(f"required source catalog table is absent: {table}")
    pks = _query_metadata(
        container, database, user,
        "SELECT a.attname FROM pg_index i "
        "JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=ANY(i.indkey) "
        f"WHERE i.indrelid='public.{table}'::regclass AND i.indisprimary ORDER BY array_position(i.indkey, a.attnum)",
    )
    writable = _writable_columns(columns)
    if not writable:
        raise ValueError(f"required source catalog table has no writable columns: {table}")
    return writable, [row[0] for row in pks]


def _copy_table_to_gzip(
    out: gzip.GzipFile, container: str, database: str, user: str,
    table: str, columns: list[str], primary_key: list[str],
) -> int:
    col_sql = ", ".join(_quoted(column) for column in columns)
    order_sql = ", ".join(_quoted(column) for column in primary_key) if primary_key else ""
    query = f"SELECT {col_sql} FROM public.{_quoted(table)}" + (f" ORDER BY {order_sql}" if order_sql else "")
    command = _psql_command(container, database, user, f"COPY ({query}) TO STDOUT WITH (FORMAT csv)")
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    assert process.stdout is not None
    count = 0
    try:
        for line in process.stdout:
            out.write(line)
            count += 1
        stderr = process.stderr.read() if process.stderr else b""
        code = process.wait()
    except BaseException:
        process.kill()
        process.wait()
        raise
    if code:
        raise RuntimeError(f"read-only catalog export failed for {table}: {stderr.decode(errors='replace')[:300]}")
    count_rows = _query_rows(container, database, user, f"SELECT count(*) FROM public.{_quoted(table)}")
    return int(count_rows[0][0])


def _emit_expected(out: gzip.GzipFile, name: str, columns: Sequence[str], rows: Sequence[Sequence]) -> None:
    numeric = {"partnerid", "benefitid", "benefitcarrierpolicyid"}
    out.write(f"CREATE TEMP TABLE _expected_{name} ({', '.join(_quoted(c) + (' bigint' if c in numeric else ' text') for c in columns)}) ON COMMIT DROP;\n".encode())
    if rows:
        normalized = [[None if value is None else str(value) for value in row] for row in rows]
        out.write((f"INSERT INTO _expected_{name} ({', '.join(_quoted(c) for c in columns)}) VALUES\n" + _values(normalized) + ";\n").encode())


def _emit_maps(out: gzip.GzipFile, dev: dict, prod: dict, maps: dict[str, IdMap]) -> None:
    _emit_expected(out, "prod_partner", ("partnerid", "partnername"), prod["partners"])
    _emit_expected(out, "dev_partner", ("partnerid", "partnername"), dev["partners"])
    _emit_expected(out, "prod_benefit", ("benefitid", "canonicalkey", "partnerid", "benefitname"), prod["benefits"])
    _emit_expected(out, "dev_benefit", ("benefitid", "canonicalkey", "partnerid", "benefitname"), dev["benefits"])
    _emit_expected(out, "prod_policy", ("benefitcarrierpolicyid", "benefitid", "carrier", "sourcekey", "sourceurl"), prod["policies"])
    _emit_expected(out, "dev_policy", ("benefitcarrierpolicyid", "benefitid", "carrier", "sourcekey", "sourceurl"), dev["policies"])

    map_specs = {
        "partner": ("partners", ("source_id", "target_id", "natural_key"),
                    ((int(row[0]), maps["partners"].source_to_target[int(row[0])], normalize_name(str(row[1]))) for row in dev["partners"])),
        "benefit": ("benefits", ("source_id", "target_id", "partner_id", "canonical_key"),
                    ((int(row[0]), maps["benefits"].source_to_target[int(row[0])], maps["partners"].source_to_target[int(row[2])], remap_canonical_partner_prefix(str(row[1]), int(row[2]), maps["partners"].source_to_target[int(row[2])])) for row in dev["benefits"])),
        "policy": ("policies", ("source_id", "target_id", "benefit_id", "carrier", "source_key"),
                   ((int(row[0]), maps["policies"].source_to_target[int(row[0])], maps["benefits"].source_to_target[int(row[1])], str(row[2]).upper(), str(row[3])) for row in dev["policies"])),
    }
    for name, (_, columns, rows) in map_specs.items():
        materialized = list(rows)
        types = ["bigint" if column.endswith("id") or column in ("source_id", "target_id") else "text" for column in columns]
        out.write((f"CREATE TEMP TABLE _map_{name} (" + ", ".join(f"{_quoted(c)} {t} NOT NULL" for c,t in zip(columns,types)) + ") ON COMMIT DROP;\n").encode())
        out.write((f"INSERT INTO _map_{name} ({', '.join(_quoted(c) for c in columns)}) VALUES\n" + _values(materialized) + ";\n").encode())


def _emit_guards(out: gzip.GzipFile) -> None:
    checks = (
        ("partner", "partnerid, partnername", "partnerid, partnername"),
        ("benefit", "benefitid, canonicalkey, partnerid, benefitname", "benefitid, canonicalkey, partnerid, benefitname"),
        ("policy", "benefitcarrierpolicyid, benefitid, carrier, sourcekey, sourceurl", "benefitcarrierpolicyid, benefitid, carrier, sourcekey, sourceurl"),
    )
    body = ["DO $guard$ BEGIN",
            "IF EXISTS (SELECT 1 FROM public.rawbenefitsource) OR EXISTS (SELECT 1 FROM public.benefitnormalizationrevision) THEN RAISE EXCEPTION 'catalog transfer requires a fresh clone with empty source/revision tables'; END IF;"]
    for name, cols, expected_cols in checks:
        table = {"partner": "partner", "benefit": "benefit", "policy": "benefitcarrierpolicy"}[name]
        body.append(
            f"IF EXISTS (SELECT {cols} FROM public.{table} EXCEPT SELECT {expected_cols} FROM _expected_prod_{name}) "
            f"OR EXISTS (SELECT {expected_cols} FROM _expected_prod_{name} EXCEPT SELECT {cols} FROM public.{table}) "
            f"THEN RAISE EXCEPTION 'production {name} identity snapshot changed'; END IF;"
        )
        stage_table = {"partner": "partner", "benefit": "benefit", "policy": "benefitcarrierpolicy"}[name]
        body.append(
            f"IF EXISTS (SELECT {cols} FROM pg_temp._stage_{stage_table} EXCEPT SELECT {expected_cols} FROM _expected_dev_{name}) "
            f"OR EXISTS (SELECT {expected_cols} FROM _expected_dev_{name} EXCEPT SELECT {cols} FROM pg_temp._stage_{stage_table}) "
            f"THEN RAISE EXCEPTION 'development {name} identity snapshot changed'; END IF;"
        )
    body.extend([
        "IF EXISTS (SELECT 1 FROM _map_partner m JOIN public.partner p ON p.partnerid=m.target_id "
        "WHERE lower(btrim(p.partnername)) <> m.natural_key) THEN RAISE EXCEPTION 'partner ID map collision'; END IF;",
        "IF EXISTS (SELECT 1 FROM pg_temp._stage_partneralias s JOIN _map_partner m ON m.source_id=s.partnerid JOIN public.partneralias p ON p.normalizedalias=s.normalizedalias WHERE p.partnerid<>m.target_id) THEN RAISE EXCEPTION 'partner alias already belongs to a different production partner'; END IF;",
        "IF EXISTS (SELECT 1 FROM pg_temp._stage_carrierpartneridentity s JOIN _map_partner m ON m.source_id=s.partnerid JOIN public.carrierpartneridentity p ON p.carrier=s.carrier AND p.sourcepartnerid=s.sourcepartnerid WHERE p.partnerid<>m.target_id) THEN RAISE EXCEPTION 'carrier partner identity already belongs to a different production partner'; END IF;",
        "IF EXISTS (SELECT 1 FROM _map_benefit m JOIN public.benefit b ON b.benefitid=m.target_id "
        "WHERE b.partnerid <> m.partner_id OR b.canonicalkey IS DISTINCT FROM m.canonical_key) "
        "THEN RAISE EXCEPTION 'benefit ID map collision'; END IF;",
        "IF EXISTS (SELECT 1 FROM _map_policy m JOIN public.benefitcarrierpolicy p ON p.benefitcarrierpolicyid=m.target_id "
        "WHERE upper(p.carrier) <> m.carrier OR p.sourcekey IS DISTINCT FROM m.source_key) "
        "THEN RAISE EXCEPTION 'policy ID map collision'; END IF;",
        "IF EXISTS (SELECT 1 FROM pg_temp._stage_benefitcarrierpolicy p LEFT JOIN pg_temp._stage_benefitpolicy b ON b.benefitpolicyid=p.benefitlimit WHERE p.benefitlimit IS NOT NULL AND b.benefitpolicyid IS NULL) THEN RAISE EXCEPTION 'dangling development benefit limit reference'; END IF;",
        "IF EXISTS (SELECT 1 FROM pg_temp._stage_benefitcarrierpolicy p LEFT JOIN pg_temp._stage_rawbenefitsource r ON r.id=p.sourceevidenceid WHERE p.sourceevidenceid IS NOT NULL AND r.id IS NULL) THEN RAISE EXCEPTION 'dangling development policy evidence reference'; END IF;",
        "IF EXISTS (SELECT 1 FROM pg_temp._stage_benefitnormalizationrevision r LEFT JOIN _map_policy p ON p.source_id=r.policyid WHERE p.source_id IS NULL) THEN RAISE EXCEPTION 'dangling development normalization policy reference'; END IF;",
        "IF EXISTS (SELECT 1 FROM pg_temp._stage_benefitnormalizationrevision r LEFT JOIN pg_temp._stage_rawbenefitsource s ON s.id=r.sourceevidenceid WHERE r.sourceevidenceid IS NOT NULL AND s.id IS NULL) THEN RAISE EXCEPTION 'dangling development normalization evidence reference'; END IF;",
        "IF EXISTS (SELECT 1 FROM pg_temp._stage_benefitcarrierpolicy p LEFT JOIN _map_benefit b ON b.source_id=p.benefitid "
        "WHERE b.source_id IS NULL) THEN RAISE EXCEPTION 'dangling development policy benefit reference'; END IF;",
        "IF EXISTS (SELECT 1 FROM pg_temp._stage_carriertierbenefit t LEFT JOIN _map_policy p ON p.source_id=t.benefitcarrierpolicyid "
        "WHERE p.source_id IS NULL) THEN RAISE EXCEPTION 'dangling development tier policy reference'; END IF;",
        "END $guard$;",
    ])
    out.write(("\n".join(body) + "\n").encode())


def _emit_import(out: gzip.GzipFile, columns: dict[str, list[str]], primary_keys: dict[str, list[str]]) -> None:
    # Refresh seed metadata by stable primary key; retain prod-only aliases/relationships below.
    for table in ("membership_program", "membership_grade", "membership_grade_alias", "membership_grade_match", "partner_category", "partner_category_alias"):
        cols = columns[table]
        names = ", ".join(_quoted(c) for c in cols)
        pk = primary_keys[table]
        if not pk:
            raise ValueError(f"reference table has no primary key: {table}")
        conflict = ", ".join(_quoted(c) for c in pk)
        updates = [c for c in cols if c not in pk]
        action = "DO UPDATE SET " + ", ".join(f"{_quoted(c)}=EXCLUDED.{_quoted(c)}" for c in updates) if updates else "DO NOTHING"
        out.write(f"INSERT INTO public.{_quoted(table)} ({names}) SELECT {names} FROM pg_temp._stage_{table} ON CONFLICT ({conflict}) {action};\n".encode())

    out.write(b"CREATE TEMP TABLE _map_benefitpolicy (source_id bigint PRIMARY KEY, target_id bigint NOT NULL UNIQUE) ON COMMIT DROP;\n")
    out.write(b"SELECT setval(pg_get_serial_sequence('public.benefitpolicy','benefitpolicyid'), GREATEST(COALESCE((SELECT MAX(benefitpolicyid) FROM public.benefitpolicy),1), COALESCE((SELECT MAX(benefitpolicyid) FROM pg_temp._stage_benefitpolicy),1)), true);\n")
    out.write(b"INSERT INTO _map_benefitpolicy SELECT s.benefitpolicyid, COALESCE(t.benefitpolicyid, CASE WHEN NOT EXISTS (SELECT 1 FROM public.benefitpolicy x WHERE x.benefitpolicyid=s.benefitpolicyid) THEN s.benefitpolicyid ELSE nextval(pg_get_serial_sequence('public.benefitpolicy','benefitpolicyid')) END) FROM pg_temp._stage_benefitpolicy s LEFT JOIN public.benefitpolicy t ON t.code=s.code ORDER BY s.benefitpolicyid;\n")
    bpcols = columns["benefitpolicy"]
    bpselect = ["m.target_id" if c == "benefitpolicyid" else f"s.{_quoted(c)}" for c in bpcols]
    out.write((f"INSERT INTO public.benefitpolicy ({', '.join(_quoted(c) for c in bpcols)}) SELECT {', '.join(bpselect)} FROM pg_temp._stage_benefitpolicy s JOIN _map_benefitpolicy m ON m.source_id=s.benefitpolicyid ON CONFLICT (benefitpolicyid) DO UPDATE SET " + ", ".join(f"{_quoted(c)}=EXCLUDED.{_quoted(c)}" for c in bpcols if c != "benefitpolicyid") + ";\n").encode())

    partner_cols = columns["partner"]
    partner_expr = ["m.target_id" if c == "partnerid" else f"s.{_quoted(c)}" for c in partner_cols]
    partner_updates = []
    for c in partner_cols:
        if c == "partnerid":
            continue
        if c == "image":
            partner_updates.append(f"{_quoted(c)}=COALESCE(public.partner.{_quoted(c)}, EXCLUDED.{_quoted(c)})")
        else:
            partner_updates.append(f"{_quoted(c)}=EXCLUDED.{_quoted(c)}")
    out.write((f"INSERT INTO public.partner ({', '.join(_quoted(c) for c in partner_cols)}) SELECT {', '.join(partner_expr)} FROM pg_temp._stage_partner s JOIN _map_partner m ON m.source_id=s.partnerid ON CONFLICT (partnerid) DO UPDATE SET " + ", ".join(partner_updates) + ";\n").encode())

    # Alias/partner identity rows keep their existing production relationships on natural-key conflict.
    alias_cols = columns["partneralias"]
    alias_expr = ["m.target_id" if c == "partnerid" else f"s.{_quoted(c)}" for c in alias_cols if c != "partneraliasid"]
    alias_insert_cols = [c for c in alias_cols if c != "partneraliasid"]
    out.write((f"INSERT INTO public.partneralias ({', '.join(_quoted(c) for c in alias_insert_cols)}) SELECT {', '.join(alias_expr)} FROM pg_temp._stage_partneralias s JOIN _map_partner m ON m.source_id=s.partnerid ON CONFLICT DO NOTHING;\n").encode())
    cpi_cols = columns["carrierpartneridentity"]
    cpi_insert_cols = [c for c in cpi_cols if c != "carrierpartneridentityid"]
    cpi_expr = ["m.target_id" if c == "partnerid" else f"s.{_quoted(c)}" for c in cpi_insert_cols]
    out.write((f"INSERT INTO public.carrierpartneridentity ({', '.join(_quoted(c) for c in cpi_insert_cols)}) SELECT {', '.join(cpi_expr)} FROM pg_temp._stage_carrierpartneridentity s JOIN _map_partner m ON m.source_id=s.partnerid ON CONFLICT DO NOTHING;\n").encode())

    raw_cols = columns["rawbenefitsource"]
    raw_insert_cols = [c for c in raw_cols if c != "id"]
    out.write((f"INSERT INTO public.rawbenefitsource ({', '.join(_quoted(c) for c in raw_insert_cols)}) SELECT {', '.join('s.' + _quoted(c) for c in raw_insert_cols)} FROM pg_temp._stage_rawbenefitsource s ON CONFLICT (carrier, sourceurl, sha256) DO NOTHING;\n").encode())
    out.write(b"CREATE TEMP TABLE _map_rawsource (source_id bigint PRIMARY KEY, target_id bigint NOT NULL UNIQUE) ON COMMIT DROP;\n")
    out.write(b"INSERT INTO _map_rawsource SELECT s.id, t.id FROM pg_temp._stage_rawbenefitsource s JOIN public.rawbenefitsource t ON t.carrier=s.carrier AND t.sourceurl=s.sourceurl AND t.sha256=s.sha256;\n")

    benefit_cols = columns["benefit"]
    benefit_expr = [
        "m.target_id" if c == "benefitid" else ("pm.target_id" if c == "partnerid" else ("m.canonical_key" if c == "canonicalkey" else f"s.{_quoted(c)}"))
        for c in benefit_cols
    ]
    benefit_updates = [f"{_quoted(c)}=EXCLUDED.{_quoted(c)}" for c in benefit_cols if c != "benefitid"]
    out.write((f"INSERT INTO public.benefit ({', '.join(_quoted(c) for c in benefit_cols)}) SELECT {', '.join(benefit_expr)} FROM pg_temp._stage_benefit s JOIN _map_benefit m ON m.source_id=s.benefitid JOIN _map_partner pm ON pm.source_id=s.partnerid ON CONFLICT (benefitid) DO UPDATE SET " + ", ".join(benefit_updates) + ";\n").encode())

    policy_cols = columns["benefitcarrierpolicy"]
    policy_expr = []
    for c in policy_cols:
        if c == "benefitcarrierpolicyid": policy_expr.append("m.target_id")
        elif c == "benefitid": policy_expr.append("m.benefit_id")
        elif c == "benefitlimit": policy_expr.append("bpm.target_id")
        elif c == "sourceevidenceid": policy_expr.append("rsm.target_id")
        else: policy_expr.append(f"s.{_quoted(c)}")
    out.write(b"CREATE TEMP TABLE _stage_policy_source_map (source_policy_id bigint, source_evidence_id bigint) ON COMMIT DROP;\n")
    out.write((f"INSERT INTO _stage_policy_source_map SELECT benefitcarrierpolicyid, sourceevidenceid FROM pg_temp._stage_benefitcarrierpolicy;\n").encode())
    policy_updates = [f"{_quoted(c)}=EXCLUDED.{_quoted(c)}" for c in policy_cols if c != "benefitcarrierpolicyid"]
    out.write((f"INSERT INTO public.benefitcarrierpolicy ({', '.join(_quoted(c) for c in policy_cols)}) SELECT {', '.join(policy_expr)} FROM pg_temp._stage_benefitcarrierpolicy s JOIN _map_policy m ON m.source_id=s.benefitcarrierpolicyid LEFT JOIN _map_benefitpolicy bpm ON bpm.source_id=s.benefitlimit LEFT JOIN _map_rawsource rsm ON rsm.source_id=s.sourceevidenceid ON CONFLICT (benefitcarrierpolicyid) DO UPDATE SET " + ", ".join(policy_updates) + ";\n").encode())

    # Only policies represented by the import are replaced; other production tier rows remain untouched.
    out.write(b"DO $refs$ BEGIN IF to_regclass('public.benefit_search_document') IS NOT NULL THEN IF EXISTS (SELECT 1 FROM public.benefit_search_document d JOIN _map_policy m ON m.target_id=d.benefitcarrierpolicyid) THEN RAISE EXCEPTION 'remove/rebuild imported search documents before replacing catalog tiers'; END IF; END IF; END $refs$;\n")
    out.write(b"DELETE FROM public.carriertierbenefit t USING _map_policy m WHERE t.benefitcarrierpolicyid=m.target_id;\n")
    out.write(b"CREATE TEMP TABLE _map_tier (source_id bigint PRIMARY KEY, target_id bigint NOT NULL UNIQUE) ON COMMIT DROP;\n")
    out.write(b"WITH source AS (SELECT carriertierbenefitid AS source_id FROM pg_temp._stage_carriertierbenefit), collisions AS (SELECT s.source_id, row_number() OVER (ORDER BY s.source_id) AS collision_num FROM source s JOIN public.carriertierbenefit p ON p.carriertierbenefitid=s.source_id), stats AS (SELECT GREATEST(COALESCE((SELECT MAX(carriertierbenefitid) FROM public.carriertierbenefit),0), COALESCE((SELECT MAX(source_id) FROM source),0)) AS max_id) INSERT INTO _map_tier SELECT s.source_id, CASE WHEN c.source_id IS NULL THEN s.source_id ELSE stats.max_id+c.collision_num END FROM source s LEFT JOIN collisions c USING (source_id) CROSS JOIN stats;\n")
    tier_cols = columns["carriertierbenefit"]
    tier_expr = ["tm.target_id" if c == "carriertierbenefitid" else ("pm.target_id" if c == "benefitcarrierpolicyid" else f"s.{_quoted(c)}") for c in tier_cols]
    out.write((f"INSERT INTO public.carriertierbenefit ({', '.join(_quoted(c) for c in tier_cols)}) SELECT {', '.join(tier_expr)} FROM pg_temp._stage_carriertierbenefit s JOIN _map_tier tm ON tm.source_id=s.carriertierbenefitid JOIN _map_policy pm ON pm.source_id=s.benefitcarrierpolicyid;\n").encode())

    # Source revisions point through the resolved natural-key source IDs and remapped policy IDs.
    revision_cols = columns["benefitnormalizationrevision"]
    revision_expr = [
        ("pm.target_id" if c == "policyid" else "rsm.target_id" if c == "sourceevidenceid" else f"s.{_quoted(c)}")
        for c in revision_cols
    ]
    out.write((f"INSERT INTO public.benefitnormalizationrevision ({', '.join(_quoted(c) for c in revision_cols)}) SELECT {', '.join(revision_expr)} FROM pg_temp._stage_benefitnormalizationrevision s JOIN _map_policy pm ON pm.source_id=s.policyid LEFT JOIN _map_rawsource rsm ON rsm.source_id=s.sourceevidenceid ON CONFLICT (id) DO NOTHING;\n").encode())

    snapshot_cols = columns["benefitsnapshotimportstate"]
    snapshot_updates = [f"{_quoted(c)}=GREATEST(public.benefitsnapshotimportstate.{_quoted(c)}, EXCLUDED.{_quoted(c)})" for c in snapshot_cols if c != "carrier" and c.lower() == "lastappliedcrawledat"]
    if not snapshot_updates:
        snapshot_updates = [f"{_quoted(c)}=EXCLUDED.{_quoted(c)}" for c in snapshot_cols if c != "carrier"]
    out.write((f"INSERT INTO public.benefitsnapshotimportstate ({', '.join(_quoted(c) for c in snapshot_cols)}) SELECT {', '.join(_quoted(c) for c in snapshot_cols)} FROM pg_temp._stage_benefitsnapshotimportstate ON CONFLICT (carrier) DO UPDATE SET " + ", ".join(snapshot_updates) + ";\n").encode())

    out.write(b"DO $sequences$ DECLARE r record; seq text; max_value bigint; BEGIN FOR r IN SELECT * FROM (VALUES ('partner','partnerid'),('benefit','benefitid'),('benefitcarrierpolicy','benefitcarrierpolicyid'),('carriertierbenefit','carriertierbenefitid'),('rawbenefitsource','id'),('benefitnormalizationrevision','id'),('partneralias','partneraliasid'),('carrierpartneridentity','carrierpartneridentityid'),('benefitpolicy','benefitpolicyid')) AS t(table_name,column_name) LOOP seq := pg_get_serial_sequence('public.'||r.table_name,r.column_name); IF seq IS NOT NULL THEN EXECUTE format('SELECT COALESCE(MAX(%I),1) FROM public.%I',r.column_name,r.table_name) INTO max_value; PERFORM setval(seq,max_value,true); END IF; END LOOP; END $sequences$;\n")

    out.write(b"SELECT 'partners', count(*) FROM pg_temp._stage_partner;\nSELECT 'benefits', count(*) FROM pg_temp._stage_benefit;\nSELECT 'policies', count(*) FROM pg_temp._stage_benefitcarrierpolicy;\nSELECT 'tiers', count(*) FROM pg_temp._stage_carriertierbenefit;\nSELECT 'raw_sources', count(*) FROM pg_temp._stage_rawbenefitsource;\nSELECT 'normalization_revisions', count(*) FROM pg_temp._stage_benefitnormalizationrevision;\nCOMMIT;\n")


def build_sql_artifact(
    output: pathlib.Path, dev: dict, prod: dict, maps: dict[str, IdMap],
    container: str, database: str, user: str, force: bool = False,
) -> dict[str, int]:
    if output.exists() and not force:
        raise FileExistsError(f"output already exists (pass --force to replace): {output}")
    table_columns: dict[str, list[str]] = {}
    table_pks: dict[str, list[str]] = {}
    for table in TABLES:
        table_columns[table], table_pks[table] = _table_metadata(container, database, user, table)

    output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, temporary_name = tempfile.mkstemp(prefix=output.name + ".", suffix=".tmp", dir=output.parent)
    os.fchmod(fd, 0o600)
    counts: dict[str, int] = {}
    try:
        with os.fdopen(fd, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=9, mtime=0) as out:
            out.write(b"-- Generated from read-only development catalog export; apply only to an isolated production clone.\nBEGIN;\nSET LOCAL lock_timeout='5s';\nSET LOCAL statement_timeout='15min';\n")
            for table in TABLES:
                out.write(f"CREATE TEMP TABLE _stage_{table} (LIKE public.{_quoted(table)} INCLUDING DEFAULTS EXCLUDING CONSTRAINTS EXCLUDING INDEXES) ON COMMIT DROP;\n".encode())
            for table in TABLES:
                cols = ", ".join(_quoted(c) for c in table_columns[table])
                out.write(f"COPY pg_temp._stage_{table} ({cols}) FROM STDIN WITH (FORMAT csv);\n".encode())
                counts[table] = _copy_table_to_gzip(out, container, database, user, table, table_columns[table], table_pks[table])
                out.write(b"\\.\n")
            _emit_maps(out, dev, prod, maps)
            _emit_guards(out)
            _emit_import(out, table_columns, table_pks)
        os.replace(temporary_name, output)
        os.chmod(output, 0o600)
    except BaseException:
        try:
            os.unlink(temporary_name)
        except FileNotFoundError:
            pass
        raise
    return counts


def _read_snapshot(path: pathlib.Path) -> dict:
    with path.open(encoding="utf-8") as stream:
        data = json.load(stream)
    if not isinstance(data, dict):
        raise ValueError("identity snapshot must be a JSON object")
    return data


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    default_state = pathlib.Path.home() / ".local/state/itplace/production-migration-20261024"
    parser.add_argument("--dev-identity", type=pathlib.Path, default=default_state / "dev-catalog-identity.json")
    parser.add_argument("--prod-identity", type=pathlib.Path, default=default_state / "prod-catalog-identity.json")
    parser.add_argument("--output", type=pathlib.Path, default=default_state / "catalog-transfer.sql.gz")
    parser.add_argument("--docker-container", default="postgresql")
    parser.add_argument("--database", default="itplace")
    parser.add_argument("--user", default="postgres")
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args(argv)

    try:
        dev, prod = _read_snapshot(args.dev_identity), _read_snapshot(args.prod_identity)
        _assert_identity_snapshot(dev, args.docker_container, args.database, args.user, "development")
        maps = build_catalog_maps(dev, prod)
        counts = build_sql_artifact(args.output, dev, prod, maps, args.docker_container, args.database, args.user, args.force)
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"catalog transfer generation failed: {error}", file=sys.stderr)
        return 1
    total = sum(counts.values())
    print(f"Generated read-only-source catalog payload: {args.output}")
    print(f"Staged rows: {total}; partner remaps: {sum(a != b for a,b in maps['partners'].source_to_target.items())}; "
          f"benefit remaps: {sum(a != b for a,b in maps['benefits'].source_to_target.items())}; "
          f"policy remaps: {sum(a != b for a,b in maps['policies'].source_to_target.items())}")
    print("Destination production identity checks are embedded in the SQL; production is never connected by this generator.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
