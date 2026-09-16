#!/usr/bin/env python3
import argparse
import json
import platform
import socket
import sys
import urllib.error
import urllib.request
import uuid


APP_VERSION = "4.3.116"
PLATFORM_CODE = "00001"
DEFAULT_LOGIN_JSON = "login.json"


class ApiError(RuntimeError):
    pass


def parse_sectioned_snapshot(path: str) -> dict[str, dict]:
    with open(path, "r", encoding="utf-8") as handle:
        lines = handle.read().splitlines()

    sections: dict[str, dict] = {}
    current_name = ""
    current_lines: list[str] = []

    def flush():
        nonlocal current_name, current_lines
        if not current_name:
            return
        body = "\n".join(current_lines).strip()
        sections[current_name] = json.loads(body) if body else {}
        current_name = ""
        current_lines = []

    for line in lines:
        stripped = line.strip()
        if stripped.startswith("[") and stripped.endswith("]"):
            flush()
            current_name = stripped[1:-1].strip()
            continue
        if current_name:
            current_lines.append(line)
    flush()
    return sections


def stable_uuid() -> str:
    seed = f"{platform.node()}:{socket.gethostname()}"
    return str(uuid.uuid5(uuid.NAMESPACE_DNS, seed))


def build_headers(token: str, customer_id: str, area_id: str, device_uuid: str) -> dict[str, str]:
    client_source = {
        "areaId": area_id,
        "customerId": customer_id,
        "uuid": device_uuid,
        "sourceType": "Android",
        "appVersion": APP_VERSION,
        "platformCode": PLATFORM_CODE,
        "systemVersion": platform.platform(),
        "deviceInfo": f"{platform.system()}|{platform.machine()}",
        "networkInfo": "wifi|unknown",
    }
    return {
        "token": token,
        "key": "test",
        "reqSource": "app",
        "clientSource": json.dumps(client_source, ensure_ascii=False, separators=(",", ":")),
        "Content-Type": "application/json; charset=utf-8",
    }


def post_json(url: str, body: dict, headers: dict[str, str]) -> dict:
    request = urllib.request.Request(
        url=url,
        method="POST",
        headers=headers,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        try:
            return json.loads(raw)
        except json.JSONDecodeError as decode_exc:
            raise ApiError(f"HTTP {exc.code}: {raw}") from decode_exc
    except urllib.error.URLError as exc:
        raise ApiError(f"network error: {exc}") from exc


def resolve_from_snapshot(path: str) -> tuple[dict, list[dict]]:
    sections = parse_sectioned_snapshot(path)
    login_section = sections.get("login", {})
    login_data = login_section.get("data")
    if not isinstance(login_data, dict):
        raise ApiError(f"missing [login] section in {path}")
    last_used_section = sections.get("last_used_by_current_user", {})
    last_used = last_used_section.get("data") or []
    if not isinstance(last_used, list):
        last_used = []
    return login_data, last_used


def main():
    parser = argparse.ArgumentParser(description="Minimal one-click water start client")
    parser.add_argument("--login-json", default=DEFAULT_LOGIN_JSON, help="snapshot file path")
    parser.add_argument("--token", help="override token")
    parser.add_argument("--customer-id", help="override customerId")
    parser.add_argument("--area-id", help="override campusId/areaId")
    parser.add_argument("--customer-name", help="override customerName")
    parser.add_argument("--device-code", help="canonical device code, e.g. G4000524")
    parser.add_argument("--device-index", type=int, default=0, help="fallback index from last_used_by_current_user")
    parser.add_argument("--uuid", default=stable_uuid(), help="stable device uuid in clientSource")
    args = parser.parse_args()

    login_data, last_used = resolve_from_snapshot(args.login_json)

    token = args.token or str(login_data.get("token", "") or "")
    customer_id = args.customer_id or str(login_data.get("customerId", "") or "")
    area_id = args.area_id or str(login_data.get("areaId", "") or "")
    customer_name = args.customer_name or str(login_data.get("customerName", "") or "")

    if args.device_code:
        device_code = args.device_code
    else:
        if args.device_index < 0 or args.device_index >= len(last_used):
            raise ApiError("device index out of range and no --device-code provided")
        device_code = str((last_used[args.device_index] or {}).get("code") or "")

    if not token:
        raise ApiError("token is empty")
    if not customer_id:
        raise ApiError("customerId is empty")
    if not area_id:
        raise ApiError("areaId is empty")
    if not device_code:
        raise ApiError("deviceCode is empty")

    headers = build_headers(token, customer_id, area_id, args.uuid)
    body = {
        "customerId": customer_id,
        "customerName": customer_name,
        "customerPhone": "",
        "campusId": "",
    }
    url = f"https://gx-app-server.dcrym.com/dcxy/api/gx/devices/{device_code}/beginning"
    result = post_json(url, body, headers)
    output = {
        "deviceCode": device_code,
        "requestBody": body,
        "response": result,
    }
    print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except ApiError as exc:
        print(f"error: {exc}", file=sys.stderr)
        sys.exit(1)
