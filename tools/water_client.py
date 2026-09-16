#!/usr/bin/env python3
import argparse
import json
import os
import platform
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


APP_VERSION = "4.3.116"
PLATFORM_CODE = "00001"


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
        if not body:
            sections[current_name] = {}
        else:
            sections[current_name] = json.loads(body)
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


def write_sectioned_snapshot(path: str, sections: dict[str, dict]):
    ordered = ["login", "inuse_by_current_user", "last_used_by_current_user"]
    names = ordered + [name for name in sections.keys() if name not in ordered]
    parts: list[str] = []
    for name in names:
        parts.append(f"[{name}]")
        parts.append(json.dumps(sections.get(name, {}), ensure_ascii=False, indent=2))
        parts.append("")
    content = "\n".join(parts).rstrip() + "\n"
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(content)


def load_password_from_file(path: str) -> str:
    if not path or not os.path.exists(path):
        return ""
    return open(path, "r", encoding="utf-8").read().strip()


class WaterClient:
    def __init__(self, account: str | None, password: str | None, device_uuid: str | None = None, timeout: int = 15):
        self.account = account or ""
        self.password = password or ""
        self.timeout = timeout
        self.device_uuid = device_uuid or self._default_uuid()
        self.token = ""
        self.user_id = ""
        self.campus_id = ""
        self.username = ""

    def _default_uuid(self) -> str:
        seed = f"{platform.node()}:{socket.gethostname()}"
        return str(uuid.uuid5(uuid.NAMESPACE_DNS, seed))

    def _client_source(self) -> str:
        payload = {
            "areaId": self.campus_id,
            "customerId": self.user_id,
            "uuid": self.device_uuid,
            "sourceType": "Android",
            "appVersion": APP_VERSION,
            "platformCode": PLATFORM_CODE,
            "systemVersion": platform.platform(),
            "deviceInfo": f"{platform.system()}|{platform.machine()}",
            "networkInfo": "wifi|unknown",
        }
        return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))

    def _headers(self) -> dict[str, str]:
        return {
            "token": self.token,
            "key": "test",
            "reqSource": "app",
            "clientSource": self._client_source(),
            "Content-Type": "application/json; charset=utf-8",
        }

    def _request_json(self, method: str, url: str, body: dict | None = None, headers: dict | None = None):
        request_headers = self._headers()
        if headers:
            request_headers.update(headers)
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(url=url, data=data, headers=request_headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode("utf-8", errors="replace")
            raise ApiError(f"HTTP {exc.code}: {raw}") from exc
        except urllib.error.URLError as exc:
            raise ApiError(f"network error: {exc}") from exc

        try:
            return json.loads(raw)
        except json.JSONDecodeError as exc:
            raise ApiError(f"non-json response: {raw}") from exc

    def _request_form(self, method: str, url: str, form: dict[str, str]):
        data = urllib.parse.urlencode(form).encode("utf-8")
        headers = self._headers()
        headers["Content-Type"] = "application/x-www-form-urlencoded; charset=utf-8"
        request = urllib.request.Request(url=url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode("utf-8", errors="replace")
            raise ApiError(f"HTTP {exc.code}: {raw}") from exc
        except urllib.error.URLError as exc:
            raise ApiError(f"network error: {exc}") from exc

        try:
            return json.loads(raw)
        except json.JSONDecodeError as exc:
            raise ApiError(f"non-json response: {raw}") from exc

    def login(self) -> dict:
        payload = {
            "loginAccount": self.account,
            "password": self.password,
        }
        response = self._request_json("POST", "https://dcxy-customer-app.dcrym.com/app/customer/login", payload)
        data = response.get("data")
        if response.get("code") != 1000 or not isinstance(data, dict):
            raise ApiError(f"login failed: {response}")

        self.token = str(data.get("token", "") or "")
        self.user_id = str(data.get("customerId", "") or "")
        self.campus_id = str(data.get("areaId", "") or "")
        self.username = str(data.get("customerName", "") or "")
        if not all([self.token, self.user_id, self.campus_id]):
            raise ApiError(f"login response missing required fields: {response}")
        return response

    def load_login_snapshot(self, payload: dict):
        data = payload.get("data")
        if not isinstance(data, dict):
            raise ApiError(f"snapshot login section missing data: {payload}")
        self.token = str(data.get("token", "") or "")
        self.user_id = str(data.get("customerId", "") or "")
        self.campus_id = str(data.get("areaId", "") or "")
        self.username = str(data.get("customerName", "") or "")
        if not self.account:
            self.account = str(data.get("loginAccount", "") or data.get("customerPhone", "") or "")
        if not all([self.token, self.user_id, self.campus_id, self.account]):
            raise ApiError(f"snapshot login section missing required fields: {payload}")

    def is_authenticated(self) -> bool:
        return all([self.token, self.user_id, self.campus_id, self.account])

    def warm_up_area(self) -> dict:
        url = f"https://dcxy-base-app.dcrym.com/area/switchs?areaId={urllib.parse.quote(self.campus_id)}"
        return self._request_json("GET", url)

    def normalize_scan_value(self, raw_value: str) -> str:
        value = raw_value.strip()
        prefix = "https://www.dcrym.com?code="
        if value.startswith(prefix):
            value = value[len(prefix):]
            if len(value) > 2:
                value = value[2:]
        return value

    def fetch_device(self, raw_device_value: str) -> dict:
        device_query = self.normalize_scan_value(raw_device_value)
        url = f"https://gx-app-server.dcrym.com/dcxy/api/gx/devices/{urllib.parse.quote(device_query)}"
        response = self._request_json("GET", url)
        if response.get("code") != 1000 or not isinstance(response.get("data"), dict):
            raise ApiError(f"device lookup failed: {response}")
        return response

    def current_in_use_device(self) -> dict:
        url = (
            "https://gx-app-server.dcrym.com/dcxy/api/gx/devices/inuseByCurrentUser"
            f"?customerId={urllib.parse.quote(self.user_id)}&campusId={urllib.parse.quote(self.campus_id)}"
        )
        return self._request_json("GET", url)

    def last_used_device(self) -> dict:
        url = (
            "https://gx-app-server.dcrym.com/dcxy/api/gx/devices/lastUsedByCurrentUser"
            f"?customerId={urllib.parse.quote(self.user_id)}&campusId={urllib.parse.quote(self.campus_id)}"
        )
        return self._request_json("GET", url)

    def _device_action_payload(self) -> dict[str, str]:
        return {
            "customerId": self.user_id,
            "customerName": self.username,
            "customerPhone": self.account,
            "campusId": self.campus_id,
        }

    def start_water(self, canonical_device_code: str) -> dict:
        url = f"https://gx-app-server.dcrym.com/dcxy/api/gx/devices/{urllib.parse.quote(canonical_device_code)}/beginning"
        payload = self._device_action_payload()
        try:
            return self._request_json("POST", url, payload)
        except ApiError:
            return self._request_form("POST", url, {"data": json.dumps(payload, ensure_ascii=False)})

    def stop_water(self, canonical_device_code: str) -> dict:
        url = f"https://gx-app-server.dcrym.com/dcxy/api/gx/devices/{urllib.parse.quote(canonical_device_code)}/stoping"
        payload = self._device_action_payload()
        try:
            return self._request_json("POST", url, payload)
        except ApiError:
            return self._request_form("POST", url, {"data": json.dumps(payload, ensure_ascii=False)})

    def get_unpaid_order(self) -> dict:
        url = (
            "https://dcxy-customer-app.dcrym.com/consumeOrder/getUnpaidOrder"
            f"?customerId={urllib.parse.quote(self.user_id)}&source=0"
        )
        return self._request_json("GET", url)

    def get_order_detail_and_pay(self, order_id: str) -> dict:
        url = (
            "https://dcxy-customer-app.dcrym.com/consumeOrder/getOrderDetailAndPay/v4"
            f"?id={urllib.parse.quote(order_id)}"
        )
        return self._request_json("GET", url)


def print_json(title: str, payload: dict):
    print(f"\n[{title}]")
    print(json.dumps(payload, ensure_ascii=False, indent=2))


def extract_device_code_from_lookup(payload: dict) -> str:
    return str((payload.get("data") or {}).get("code") or "")


def extract_device_code_from_inuse(payload: dict) -> str:
    return str((payload.get("data") or {}).get("code") or "")


def extract_device_code_from_last_used(payload: dict, index: int) -> str:
    data = payload.get("data")
    if not isinstance(data, list) or not data:
        return ""
    if index < 0 or index >= len(data):
        raise ApiError(f"device index out of range: {index}")
    item = data[index]
    if not isinstance(item, dict):
        return ""
    return str(item.get("code") or "")


def main():
    parser = argparse.ArgumentParser(description="Auto login and one-click water start client")
    parser.add_argument("--account", help="loginAccount, usually phone number")
    parser.add_argument("--password", help="login password")
    parser.add_argument("--password-file", default=".env", help="fallback password file, used by refresh/live login")
    parser.add_argument("--login-json", default="login.json", help="sectioned snapshot file path")
    parser.add_argument("--action", choices=["start", "stop", "status", "refresh"], default="start", help="start water, stop water, query status, or refresh login snapshot")
    parser.add_argument("--device", help="raw QR content or canonical device code")
    parser.add_argument("--device-index", type=int, default=0, help="index in last_used_by_current_user when --device is omitted")
    parser.add_argument("--uuid", help="stable device uuid used in clientSource header")
    parser.add_argument("--poll-order", action="store_true", help="poll unpaid order once after starting")
    parser.add_argument("--auto-stop-after", type=int, help="seconds to wait after starting before sending stop request")
    parser.add_argument("--with-area-switch", action="store_true", help="call /area/switchs after login")
    parser.add_argument("--show-current", action="store_true", help="print in-use and last-used device info after login")
    args = parser.parse_args()

    client = WaterClient(args.account, args.password, device_uuid=args.uuid)
    snapshot_sections: dict[str, dict] = {}
    if args.login_json:
        try:
            snapshot_sections = parse_sectioned_snapshot(args.login_json)
        except FileNotFoundError:
            snapshot_sections = {}
        except json.JSONDecodeError as exc:
            raise ApiError(f"failed to parse snapshot file {args.login_json}: {exc}") from exc

    snapshot_login = snapshot_sections.get("login")
    if snapshot_login:
        client.load_login_snapshot(snapshot_login)

    if not client.password and args.action == "refresh":
        client.password = load_password_from_file(args.password_file)

    if client.password:
        login_result = client.login()
        print_json("login", login_result)
        snapshot_sections["login"] = login_result
    elif snapshot_login:
        print_json("login_snapshot", snapshot_login)
    else:
        raise ApiError("provide --password for live login, or provide a valid --login-json with a [login] section")

    if args.with_area_switch:
        print_json("area_switch", client.warm_up_area())

    need_status = args.show_current or args.action in {"status", "stop"}
    current_inuse = None
    last_used = snapshot_sections.get("last_used_by_current_user")
    if need_status:
        current_inuse = client.current_in_use_device()
        print_json("inuse_by_current_user", current_inuse)
        snapshot_sections["inuse_by_current_user"] = current_inuse
        last_used = client.last_used_device()
        print_json("last_used_by_current_user", last_used)
        snapshot_sections["last_used_by_current_user"] = last_used
    elif args.show_current is False and last_used:
        print_json("last_used_snapshot", last_used)

    if args.action == "refresh":
        write_sectioned_snapshot(args.login_json, snapshot_sections)
        print(f"\n[refreshed] {args.login_json}")
        return

    if args.action == "status":
        return

    canonical_device_code = ""
    if args.device:
        device_result = client.fetch_device(args.device)
        print_json("device_lookup", device_result)
        canonical_device_code = extract_device_code_from_lookup(device_result)
    elif args.action == "stop" and current_inuse is not None:
        canonical_device_code = extract_device_code_from_inuse(current_inuse)
    elif args.action == "start" and last_used is not None:
        canonical_device_code = extract_device_code_from_last_used(last_used, args.device_index)
    else:
        raise ApiError("no device source found; pass --device or provide usable last_used data in snapshot")

    if not canonical_device_code:
        raise ApiError("canonical device code is empty; provide --device or ensure there is an in-use device")

    if args.action == "stop":
        stop_result = client.stop_water(canonical_device_code)
        print_json("stop_water", stop_result)
        if args.poll_order:
            time.sleep(2)
            unpaid = client.get_unpaid_order()
            print_json("unpaid_order", unpaid)
            order_id = str(unpaid.get("orderId") or "")
            if order_id:
                print_json("order_detail_and_pay", client.get_order_detail_and_pay(order_id))
        return

    start_result = client.start_water(canonical_device_code)
    print_json("start_water", start_result)

    if args.auto_stop_after is not None:
        if args.auto_stop_after < 0:
            raise ApiError("--auto-stop-after must be >= 0")
        time.sleep(args.auto_stop_after)
        stop_result = client.stop_water(canonical_device_code)
        print_json("stop_water", stop_result)

    if args.poll_order:
        time.sleep(2)
        unpaid = client.get_unpaid_order()
        print_json("unpaid_order", unpaid)
        order_id = str(unpaid.get("orderId") or "")
        if order_id:
            print_json("order_detail_and_pay", client.get_order_detail_and_pay(order_id))


if __name__ == "__main__":
    try:
        main()
    except ApiError as exc:
        print(f"error: {exc}", file=sys.stderr)
        sys.exit(1)
