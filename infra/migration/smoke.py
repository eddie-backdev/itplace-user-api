#!/usr/bin/env python3
"""Small, dependency-free smoke checks for a user API deployment.

Public checks are read-only. Chat checks require --auth and environment
credentials; they make exactly three bounded chat requests.
"""

from __future__ import annotations

import argparse
import http.cookiejar
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request


class SmokeError(Exception):
    pass


class Client:
    def __init__(self, base_url: str, timeout: float):
        self.base = base_url.rstrip("/")
        self.timeout = timeout
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))

    def request(self, path: str, *, method: str = "GET", query=None, body=None,
                headers=None, accept="application/json"):
        url = self.base + path
        if query:
            url += "?" + urllib.parse.urlencode(query, doseq=True)
        data = None if body is None else json.dumps(body).encode("utf-8")
        request_headers = {"Accept": accept, "User-Agent": "Mozilla/5.0 (compatible; itplace-smoke/1.0)"}
        if data is not None:
            request_headers["Content-Type"] = "application/json"
        if headers:
            request_headers.update(headers)
        req = urllib.request.Request(url, data=data, headers=request_headers, method=method)
        try:
            with self.opener.open(req, timeout=self.timeout) as response:
                return response.status, response.headers, response.read()
        except urllib.error.HTTPError as exc:
            # Do not include response bodies: auth errors can contain user details.
            return exc.code, exc.headers, exc.read()
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            raise SmokeError(f"request failed ({type(exc).__name__})") from None

    def json(self, path: str, **kwargs):
        status, headers, raw = self.request(path, **kwargs)
        if status != 200:
            raise SmokeError(f"{path}: expected HTTP 200, got {status}")
        try:
            return headers, json.loads(raw)
        except (ValueError, UnicodeDecodeError):
            raise SmokeError(f"{path}: expected JSON response") from None

    def csrf(self):
        headers, payload = self.json("/api/v1/auth/csrf")
        data = payload.get("data") or {}
        header = data.get("headerName") or "X-XSRF-TOKEN"
        token = data.get("token") or headers.get(header)
        if not token:
            raise SmokeError("CSRF endpoint returned no token")
        return {header: token}


def envelope_data(payload):
    if not isinstance(payload, dict) or not isinstance(payload.get("data"), (dict, list)):
        raise SmokeError("API response did not contain expected data envelope")
    return payload["data"]


def run_public(client: Client):
    _, csrf = client.json("/api/v1/auth/csrf")
    if not isinstance(csrf.get("data"), dict) or not csrf["data"].get("token"):
        raise SmokeError("CSRF response missing token")
    print("csrf: HTTP 200 (token withheld)")

    _, refs = client.json("/api/v1/reference-data")
    ref_data = envelope_data(refs)
    print(f"reference-data: HTTP 200, sections={len(ref_data)}")

    _, benefits = client.json("/api/v1/benefits", query={"page": 0, "size": 3})
    benefit_data = envelope_data(benefits)
    rows = benefit_data.get("content", [])
    print(f"benefit-list: HTTP 200, returned={len(rows)}, total={benefit_data.get('totalElements', 'n/a')}")
    if rows:
        benefit_id = rows[0].get("benefitId") or rows[0].get("id")
        name = rows[0].get("benefitName") or rows[0].get("name") or "(name unavailable)"
        if benefit_id is None:
            raise SmokeError("benefit list row has no detail identifier")
        _, detail = client.json(f"/api/v1/benefits/{urllib.parse.quote(str(benefit_id))}")
        envelope_data(detail)
        print(f"benefit-detail: HTTP 200, {str(name)[:60]}")

    _, partners = client.json("/api/v1/benefits/partners", query={"page": 0, "size": 3})
    partner_data = envelope_data(partners)
    partner_rows = partner_data.get("content", [])
    print(f"partner-list: HTTP 200, returned={len(partner_rows)}, total={partner_data.get('totalElements', 'n/a')}")
    if partner_rows:
        partner_id = partner_rows[0].get("partnerId")
        partner_name = partner_rows[0].get("partnerName") or "(name unavailable)"
        if partner_id is None:
            raise SmokeError("partner list row has no detail identifier")
        _, detail = client.json(f"/api/v1/benefits/partners/{urllib.parse.quote(str(partner_id))}")
        envelope_data(detail)
        print(f"partner-detail: HTTP 200, {str(partner_name)[:60]}")

    coords = {"lat": "37.5665", "lng": "126.9780", "userLat": "37.5665", "userLng": "126.9780"}
    _, nearby = client.json("/api/v1/maps/nearby/search", query={**coords, "keyword": "편의점"})
    nearby_data = envelope_data(nearby)
    print(f"map-search: HTTP 200, stores={len(nearby_data)}")


def parse_sse(raw: bytes):
    event = "message"
    data_lines = []
    for line in raw.decode("utf-8", errors="replace").splitlines() + [""]:
        if not line:
            if data_lines:
                try:
                    payload = json.loads("\n".join(data_lines))
                except ValueError:
                    payload = None
                yield event, payload
            event, data_lines = "message", []
        elif line.startswith("event:"):
            event = line[6:].strip()
        elif line.startswith("data:"):
            data_lines.append(line[5:].lstrip())


def run_chat(client: Client):
    email = os.environ.get("ITPLACE_SMOKE_EMAIL")
    password = os.environ.get("ITPLACE_SMOKE_PASSWORD")
    if not email or not password:
        raise SmokeError("--auth requires ITPLACE_SMOKE_EMAIL and ITPLACE_SMOKE_PASSWORD")

    csrf_headers = client.csrf()
    status, _, _ = client.request("/api/v1/auth/login", method="POST", body={"email": email, "password": password}, headers=csrf_headers)
    if status != 200:
        raise SmokeError(f"login: expected HTTP 200, got {status}; credentials and response withheld")
    print("login: HTTP 200 (identity and cookies withheld)")
    csrf_headers = client.csrf()  # Login may rotate the CSRF token.

    cases = [
        ("out-of-scope", "서울에서 내일 비가 오나요?", "OUT_OF_SCOPE"),
        ("movie-benefit", "영화 할인 혜택 추천해줘", "ANSWER"),
        ("convenience-store-benefit", "편의점에서 쓸 수 있는 혜택 추천해줘", "ANSWER"),
    ]
    for label, question, expected in cases:
        status, headers, raw = client.request(
            "/api/v1/questions/chat/stream", method="POST",
            body={"question": question, "carrier": "SKT", "grade": "VIP"},
            headers=csrf_headers, accept="text/event-stream")
        if status != 200:
            raise SmokeError(f"chat {label}: expected HTTP 200, got {status}; body withheld")
        if "text/event-stream" not in headers.get("Content-Type", ""):
            raise SmokeError(f"chat {label}: expected event stream")
        terminal = None
        for event, payload in parse_sse(raw):
            if event == "error":
                raise SmokeError(f"chat {label}: server emitted error event")
            if event == "done" and isinstance(payload, dict):
                terminal = payload
        if terminal is None:
            raise SmokeError(f"chat {label}: no completion event")
        result = terminal.get("data", terminal)
        chat_status = result.get("status")
        rows = result.get("partners") or []
        if expected == "OUT_OF_SCOPE" and (chat_status != expected or rows):
            raise SmokeError(f"chat {label}: expected {expected} with zero results, got status={chat_status}, count={len(rows)}")
        if expected == "ANSWER" and (chat_status != expected or not rows):
            raise SmokeError(f"chat {label}: expected retrieved answer, got status={chat_status}, count={len(rows)}")
        names = [str(row.get("partnerName", ""))[:40] for row in rows[:3]]
        suffix = f", partners={names}" if names else ""
        print(f"chat {label}: HTTP 200, status={chat_status}, count={len(rows)}{suffix}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080", help="User API base URL")
    parser.add_argument("--health-base-url", help="Optional internal management URL for GET /actuator/health")
    parser.add_argument("--timeout", type=float, default=35.0)
    parser.add_argument("--auth", action="store_true", help="Opt in to login and three bounded chat checks")
    args = parser.parse_args()
    if not args.base_url.startswith(("http://", "https://")):
        parser.error("--base-url must start with http:// or https://")
    client = Client(args.base_url, args.timeout)
    try:
        if args.health_base_url:
            health_status, _, _ = Client(args.health_base_url, args.timeout).request("/actuator/health")
            if health_status != 200:
                raise SmokeError(f"health check: expected HTTP 200, got {health_status}")
            print("health: HTTP 200")
        run_public(client)
        if args.auth:
            run_chat(client)
    except SmokeError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    print("PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
