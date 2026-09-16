#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import platform
import socket
import threading
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from tkinter import BOTH, END, LEFT, RIGHT, VERTICAL, W, Button, Entry, Frame, Label, Scrollbar, StringVar, Tk, Toplevel
from tkinter import messagebox
from tkinter.ttk import Treeview


APP_VERSION = "4.3.116"
PLATFORM_CODE = "00001"
LOGIN_URL = "https://dcxy-customer-app.dcrym.com/app/customer/login"
DEVICE_DETAIL_URL = "https://gx-app-server.dcrym.com/dcxy/api/gx/devices/{device_code}"
BEGIN_URL = "https://gx-app-server.dcrym.com/dcxy/api/gx/devices/{device_code}/beginning"
CONFIG_PATH = Path(__file__).resolve().parent.parent / "water_app_config.json"


class ApiError(RuntimeError):
    pass


@dataclass
class DeviceItem:
    code: str
    position: str = ""


@dataclass
class AppConfig:
    account: str = ""
    password: str = ""
    customer_id: str = ""
    token: str = ""
    device_uuid: str = ""
    devices: list[DeviceItem] = field(default_factory=list)

    @classmethod
    def load(cls, path: Path) -> "AppConfig":
        if not path.exists():
            return cls(device_uuid=generate_device_uuid())
        raw = json.loads(path.read_text(encoding="utf-8"))
        devices = [DeviceItem(**item) for item in raw.get("devices", []) if item.get("code")]
        return cls(
            account=str(raw.get("account", "") or ""),
            password=str(raw.get("password", "") or ""),
            customer_id=str(raw.get("customer_id", "") or ""),
            token=str(raw.get("token", "") or ""),
            device_uuid=str(raw.get("device_uuid", "") or generate_device_uuid()),
            devices=devices,
        )

    def save(self, path: Path) -> None:
        payload = {
            "account": self.account,
            "password": self.password,
            "customer_id": self.customer_id,
            "token": self.token,
            "device_uuid": self.device_uuid or generate_device_uuid(),
            "devices": [{"code": item.code, "position": item.position} for item in self.devices],
        }
        path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def generate_device_uuid() -> str:
    seed = f"{platform.node()}:{socket.gethostname()}"
    return str(uuid.uuid5(uuid.NAMESPACE_DNS, seed))


class WaterApi:
    def __init__(self, config: AppConfig):
        self.config = config

    def _client_source(self) -> str:
        payload = {
            "areaId": "",
            "customerId": self.config.customer_id,
            "uuid": self.config.device_uuid or generate_device_uuid(),
            "sourceType": "Android",
            "appVersion": APP_VERSION,
            "platformCode": PLATFORM_CODE,
            "systemVersion": platform.platform(),
            "deviceInfo": f"{platform.system()}|{platform.machine()}",
            "networkInfo": "wifi|unknown",
        }
        return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))

    def _headers(self, include_token: bool = True) -> dict[str, str]:
        headers = {
            "key": "test",
            "reqSource": "app",
            "clientSource": self._client_source(),
            "Content-Type": "application/json; charset=utf-8",
        }
        if include_token:
            headers["token"] = self.config.token
        return headers

    def _request_json(self, method: str, url: str, body: dict | None = None, include_token: bool = True) -> dict:
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(url=url, method=method, headers=self._headers(include_token=include_token), data=data)
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                raw = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode("utf-8", errors="replace")
        except urllib.error.URLError as exc:
            raise ApiError(f"network error: {exc}") from exc
        try:
            return json.loads(raw)
        except json.JSONDecodeError as exc:
            raise ApiError(f"non-json response: {raw}") from exc

    def login(self) -> str:
        if not self.config.account or not self.config.password:
            raise ApiError("account/password is empty")
        response = self._request_json(
            "POST",
            LOGIN_URL,
            {"loginAccount": self.config.account, "password": self.config.password},
            include_token=False,
        )
        if response.get("code") != 1000 or not isinstance(response.get("data"), dict):
            raise ApiError(f"login failed: {response.get('msg', response)}")
        token = str(response["data"].get("token", "") or "")
        if not token:
            raise ApiError("login response missing token")
        self.config.token = token
        return token

    def _is_token_invalid(self, response: dict) -> bool:
        code = response.get("code")
        message = str(response.get("msg", "") or "")
        if code == -2:
            return True
        hints = ("重新登录", "已登出", "会话已过期", "登陆过期")
        return any(hint in message for hint in hints)

    def request_with_relogin(self, method: str, url: str, body: dict | None = None) -> dict:
        response = self._request_json(method, url, body=body, include_token=True)
        if self._is_token_invalid(response):
            self.login()
            response = self._request_json(method, url, body=body, include_token=True)
        return response

    def fetch_device_position(self, device_code: str) -> str:
        response = self.request_with_relogin("GET", DEVICE_DETAIL_URL.format(device_code=device_code))
        if response.get("code") != 1000 or not isinstance(response.get("data"), dict):
            raise ApiError(f"device query failed: {response.get('msg', response)}")
        return str(response["data"].get("position", "") or "")

    def open_water(self, device_code: str) -> dict:
        if not self.config.customer_id:
            raise ApiError("customerId is empty")
        response = self.request_with_relogin(
            "POST",
            BEGIN_URL.format(device_code=device_code),
            body={"customerId": self.config.customer_id},
        )
        if response.get("code") != 1000:
            raise ApiError(f"open water failed: {response.get('msg', response)}")
        return response


class WaterApp:
    def __init__(self, root: Tk):
        self.root = root
        self.root.title("Min Water App")
        self.root.geometry("900x560")
        self.config = AppConfig.load(CONFIG_PATH)
        self.api = WaterApi(self.config)
        self.status_var = StringVar(value="Ready")
        self.account_var = StringVar(value=self.config.account)
        self.password_var = StringVar(value=self.config.password)
        self.customer_id_var = StringVar(value=self.config.customer_id)
        self.device_code_var = StringVar(value="")
        self.tree: Treeview | None = None
        self._build_ui()
        self.refresh_table()

    def _build_ui(self) -> None:
        top = Frame(self.root, padx=12, pady=12)
        top.pack(fill=BOTH)

        Label(top, text="Account").grid(row=0, column=0, sticky=W, padx=(0, 8), pady=4)
        Entry(top, textvariable=self.account_var, width=24).grid(row=0, column=1, sticky=W, pady=4)

        Label(top, text="Password").grid(row=0, column=2, sticky=W, padx=(16, 8), pady=4)
        Entry(top, textvariable=self.password_var, show="*", width=24).grid(row=0, column=3, sticky=W, pady=4)

        Label(top, text="CustomerId").grid(row=1, column=0, sticky=W, padx=(0, 8), pady=4)
        Entry(top, textvariable=self.customer_id_var, width=24).grid(row=1, column=1, sticky=W, pady=4)

        Button(top, text="Save Config", command=self.on_save_config, width=14).grid(row=0, column=4, padx=(16, 8), pady=4)
        Button(top, text="Login", command=lambda: self.run_async(self.on_login_clicked), width=14).grid(row=0, column=5, pady=4)
        Button(top, text="Refresh Positions", command=lambda: self.run_async(self.on_refresh_positions), width=14).grid(row=1, column=4, padx=(16, 8), pady=4)

        add_frame = Frame(self.root, padx=12)
        add_frame.pack(fill=BOTH)
        Label(add_frame, text="DeviceId").pack(side=LEFT)
        Entry(add_frame, textvariable=self.device_code_var, width=24).pack(side=LEFT, padx=(8, 8))
        Button(add_frame, text="Add Device", command=lambda: self.run_async(self.on_add_device), width=14).pack(side=LEFT)

        table_frame = Frame(self.root, padx=12, pady=12)
        table_frame.pack(fill=BOTH, expand=True)

        columns = ("code", "position")
        self.tree = Treeview(table_frame, columns=columns, show="headings", height=18)
        self.tree.heading("code", text="Device Code")
        self.tree.heading("position", text="Position")
        self.tree.column("code", width=180, anchor=W)
        self.tree.column("position", width=560, anchor=W)
        self.tree.pack(side=LEFT, fill=BOTH, expand=True)

        scrollbar = Scrollbar(table_frame, orient=VERTICAL, command=self.tree.yview)
        scrollbar.pack(side=RIGHT, fill="y")
        self.tree.configure(yscrollcommand=scrollbar.set)

        action_bar = Frame(self.root, padx=12, pady=8)
        action_bar.pack(fill=BOTH)
        Button(action_bar, text="Open Water For Selected", command=lambda: self.run_async(self.on_open_selected), width=20).pack(side=LEFT)
        Button(action_bar, text="Refresh Selected Position", command=lambda: self.run_async(self.on_refresh_selected), width=20).pack(side=LEFT, padx=(8, 0))
        Button(action_bar, text="Delete Selected", command=self.on_delete_selected, width=16).pack(side=LEFT, padx=(8, 0))

        status = Label(self.root, textvariable=self.status_var, anchor=W, padx=12, pady=10)
        status.pack(fill=BOTH)

    def set_status(self, text: str) -> None:
        self.root.after(0, lambda: self.status_var.set(text))

    def run_async(self, func) -> None:
        threading.Thread(target=self._run_guarded, args=(func,), daemon=True).start()

    def _run_guarded(self, func) -> None:
        try:
            func()
        except ApiError as exc:
            self.set_status(str(exc))
            self.root.after(0, lambda: messagebox.showerror("Error", str(exc)))
        except Exception as exc:  # pragma: no cover
            self.set_status(str(exc))
            self.root.after(0, lambda: messagebox.showerror("Error", str(exc)))

    def sync_config_from_inputs(self) -> None:
        self.config.account = self.account_var.get().strip()
        self.config.password = self.password_var.get().strip()
        self.config.customer_id = self.customer_id_var.get().strip()
        if not self.config.device_uuid:
            self.config.device_uuid = generate_device_uuid()

    def save_config(self) -> None:
        self.sync_config_from_inputs()
        self.config.save(CONFIG_PATH)

    def on_save_config(self) -> None:
        self.save_config()
        self.set_status(f"Saved {CONFIG_PATH.name}")

    def on_login_clicked(self) -> None:
        self.save_config()
        token = self.api.login()
        self.config.save(CONFIG_PATH)
        self.set_status(f"Login success, token suffix: {token[-8:]}")
        self.root.after(0, lambda: messagebox.showinfo("Login", "Login success"))

    def refresh_table(self) -> None:
        if self.tree is None:
            return
        for item in self.tree.get_children():
            self.tree.delete(item)
        for device in self.config.devices:
            self.tree.insert("", END, iid=device.code, values=(device.code, device.position))

    def _find_device(self, code: str) -> DeviceItem | None:
        for item in self.config.devices:
            if item.code == code:
                return item
        return None

    def on_add_device(self) -> None:
        code = self.device_code_var.get().strip()
        if not code:
            raise ApiError("deviceId is empty")
        self.save_config()
        device = self._find_device(code)
        if device is None:
            device = DeviceItem(code=code)
            self.config.devices.append(device)
        if self.config.token:
            try:
                device.position = self.api.fetch_device_position(code)
            except ApiError:
                pass
        self.config.save(CONFIG_PATH)
        self.root.after(0, self.refresh_table)
        self.root.after(0, lambda: self.device_code_var.set(""))
        self.set_status(f"Saved device {code}")

    def _selected_code(self) -> str:
        if self.tree is None:
            return ""
        selected = self.tree.selection()
        if not selected:
            raise ApiError("select one device first")
        return str(selected[0])

    def on_refresh_selected(self) -> None:
        code = self._selected_code()
        self.save_config()
        position = self.api.fetch_device_position(code)
        device = self._find_device(code)
        if device is not None:
            device.position = position
            self.config.save(CONFIG_PATH)
        self.root.after(0, self.refresh_table)
        self.set_status(f"Position refreshed for {code}")

    def on_refresh_positions(self) -> None:
        self.save_config()
        if not self.config.devices:
            raise ApiError("no device saved")
        for device in self.config.devices:
            try:
                device.position = self.api.fetch_device_position(device.code)
            except ApiError:
                continue
        self.config.save(CONFIG_PATH)
        self.root.after(0, self.refresh_table)
        self.set_status("Positions refreshed")

    def on_delete_selected(self) -> None:
        code = self._selected_code()
        self.config.devices = [item for item in self.config.devices if item.code != code]
        self.config.save(CONFIG_PATH)
        self.refresh_table()
        self.set_status(f"Deleted {code}")

    def on_open_selected(self) -> None:
        code = self._selected_code()
        self.save_config()
        response = self.api.open_water(code)
        self.config.save(CONFIG_PATH)
        self.set_status(f"Open water success for {code}")
        self.root.after(0, lambda: messagebox.showinfo("Open Water", f"{code}\n{json.dumps(response, ensure_ascii=False)}"))


def main() -> None:
    root = Tk()
    app = WaterApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
