#!/usr/bin/env python3
"""ESP-Drone web gateway.

This server exposes the UDP text protocol used by MainPresenter.java and
serves a browser implementation of the Android MainActivity screen.
It has no third-party dependencies.
"""

import argparse
import json
import re
import secrets
import socket
import threading
import time
from collections import deque
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Deque, Dict, Optional, Tuple
from urllib.parse import parse_qs, urlparse


ROOT = Path(__file__).resolve().parent
WEB_ROOT = ROOT / "web"
DRAWABLE_ROOT = ROOT / "res" / "drawable-xhdpi"
WEB_FILES = {"/": (WEB_ROOT / "index.html", "text/html; charset=utf-8"),
             "/web.css": (WEB_ROOT / "web.css", "text/css; charset=utf-8"),
             "/app.js": (WEB_ROOT / "app.js", "application/javascript; charset=utf-8")}
DRAWABLES = {
    "battery": "ic_battery_std_black_18dp.png",
    "wifi": "ic_wifi_tethering_black_18dp.png",
    "connect": "ic_action_import_export.png",
    "settings": "ic_action_settings_light.png",
    "horizontal": "ic_horizontal.png",
}
ALTITUDE_TARGET = re.compile(r"(?:TGT=|ALT TGT=)([-\d.]+)")


def clamp(value: float, lower: float, upper: float) -> float:
    return max(lower, min(upper, value))


class UavBridge:
    """One UDP peer, matching the text commands sent by MainPresenter."""

    def __init__(self, host: str, port: int) -> None:
        self.host = host
        self.port = port
        self.connected = False
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.socket.settimeout(0.25)
        self.stop_event = threading.Event()
        self.lock = threading.Lock()
        self.lines: Deque[str] = deque(maxlen=120)
        self.last_telemetry_at: Optional[float] = None
        self.altitude_target: Optional[float] = None
        self.roll = self.pitch = self.yaw = self.throttle = 0.0
        self.last_input_at = 0.0
        self.last_altitude_adjust_at = 0.0

    def start(self) -> None:
        threading.Thread(target=self._receive_loop, name="uav-web-rx", daemon=True).start()
        threading.Thread(target=self._keepalive_loop, name="uav-web-keepalive", daemon=True).start()
        threading.Thread(target=self._control_watchdog, name="uav-web-watchdog", daemon=True).start()

    def close(self) -> None:
        self.stop_event.set()
        try:
            self.socket.close()
        except OSError:
            pass

    def connect(self, host: str, port: int) -> None:
        if not host.strip() or not 1 <= port <= 65535:
            raise ValueError("IP ESP32 hoặc cổng UDP không hợp lệ")
        with self.lock:
            self.host, self.port, self.connected = host.strip(), port, True
            self.last_telemetry_at = None
        self._raw_send(b"\n")  # lets ESP32 learn this PC's UDP source port
        self.request_snapshot()

    def disconnect(self) -> None:
        self.setpoint(0.0, 0.0, 0.0, 0.0)
        with self.lock:
            self.connected = False

    def command(self, command: str) -> None:
        with self.lock:
            connected = self.connected
        if connected:
            self._raw_send((command + "\n").encode("utf-8"))

    def setpoint(self, roll: float, pitch: float, yaw: float, throttle: float) -> None:
        roll, pitch = clamp(roll, -80.0, 80.0), clamp(pitch, -80.0, 80.0)
        yaw, throttle = clamp(yaw, -300.0, 300.0), clamp(throttle, -1.0, 1.0)
        with self.lock:
            self.roll, self.pitch, self.yaw, self.throttle = roll, pitch, yaw, throttle
            self.last_input_at = time.monotonic()
            connected = self.connected
        if connected:
            self._raw_send(("@SP SET %.2f %.2f %.2f\n" % (roll, pitch, yaw)).encode("utf-8"))

    def request_snapshot(self) -> None:
        def worker() -> None:
            for command in ("s", "@PID GET", "@MAH GET", "@SP GET", "@TRIM GET", "@ALT GET", "@TKO GET", "@LAND GET"):
                if self.stop_event.wait(0.06):
                    return
                self.command(command)
        threading.Thread(target=worker, name="uav-web-snapshot", daemon=True).start()

    def state(self) -> Dict[str, object]:
        with self.lock:
            age = None if self.last_telemetry_at is None else round(time.monotonic() - self.last_telemetry_at, 2)
            return {"connected": self.connected, "host": self.host, "port": self.port,
                    "telemetryAge": age, "altitudeTarget": self.altitude_target,
                    "setpoint": {"roll": self.roll, "pitch": self.pitch, "yaw": self.yaw, "throttle": self.throttle},
                    "lines": list(self.lines)}

    def _raw_send(self, payload: bytes) -> None:
        with self.lock:
            target: Tuple[str, int] = (self.host, self.port)
        try:
            self.socket.sendto(payload, target)
        except OSError:
            pass

    def _receive_loop(self) -> None:
        while not self.stop_event.is_set():
            try:
                packet, _ = self.socket.recvfrom(4096)
            except socket.timeout:
                continue
            except OSError:
                return
            lines = [line.strip() for line in packet.decode("utf-8", errors="replace").splitlines() if line.strip()]
            if not lines:
                continue
            with self.lock:
                self.lines.extend(lines)
                self.last_telemetry_at = time.monotonic()
                for line in lines:
                    match = ALTITUDE_TARGET.search(line)
                    if match:
                        try:
                            self.altitude_target = float(match.group(1))
                        except ValueError:
                            pass

    def _keepalive_loop(self) -> None:
        while not self.stop_event.wait(5.0):
            with self.lock:
                connected = self.connected
            if connected:
                self._raw_send(b"\n")

    def _control_watchdog(self) -> None:
        """The Android app continually sends neutral input on release; do that for a lost browser too."""
        while not self.stop_event.wait(0.10):
            with self.lock:
                stale = self.connected and time.monotonic() - self.last_input_at > 0.45
                active = any((self.roll, self.pitch, self.yaw, self.throttle))
                throttle, target = self.throttle, self.altitude_target
                adjust = (self.connected and not stale and throttle != 0.0 and target is not None
                          and time.monotonic() - self.last_altitude_adjust_at >= 0.50)
                if adjust:
                    self.last_altitude_adjust_at = time.monotonic()
            if stale and active:
                self.setpoint(0.0, 0.0, 0.0, 0.0)
            elif adjust:
                new_target = clamp(target + 0.01 * abs(throttle) * (1 if throttle > 0 else -1), 0.0, 2.0)
                with self.lock:
                    self.altitude_target = new_target
                self.command("@ALT TGT %.3f" % new_target)


class Controller:
    def __init__(self, bridge: UavBridge, token: str) -> None:
        self.bridge, self.token = bridge, token

    def authorized(self, handler: BaseHTTPRequestHandler) -> bool:
        query = parse_qs(urlparse(handler.path).query)
        supplied = handler.headers.get("X-Access-Token") or (query.get("token") or [""])[0]
        return secrets.compare_digest(supplied, self.token)

    def action(self, data: Dict[str, object]) -> Tuple[int, Dict[str, object]]:
        action = data.get("action")
        try:
            if action == "connect":
                self.bridge.connect(str(data.get("host", "")), int(data.get("port", 0)))
            elif action == "disconnect":
                self.bridge.disconnect()
            elif action == "setpoint":
                self.bridge.setpoint(float(data.get("roll", 0)), float(data.get("pitch", 0)),
                                     float(data.get("yaw", 0)), float(data.get("throttle", 0)))
            elif action == "refresh":
                self.bridge.request_snapshot()
            elif action in {"flight", "arm", "kill", "takeoff", "land"}:
                commands = {"flight": "f", "arm": "r", "kill": "k", "takeoff": "@ALT TAKEOFF", "land": "l"}
                self.bridge.command(commands[action])
            else:
                return HTTPStatus.BAD_REQUEST, {"error": "Lệnh không được hỗ trợ"}
        except (TypeError, ValueError):
            return HTTPStatus.BAD_REQUEST, {"error": "Dữ liệu điều khiển không hợp lệ"}
        return HTTPStatus.OK, {"ok": True, "state": self.bridge.state()}


class Handler(BaseHTTPRequestHandler):
    server_version = "ESPDroneWeb/2.0"

    @property
    def controller(self) -> Controller:
        return self.server.controller  # type: ignore[attr-defined]

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path in WEB_FILES:
            file_path, content_type = WEB_FILES[path]
            self._send_bytes(HTTPStatus.OK, file_path.read_bytes(), content_type)
        elif path.startswith("/android-res/"):
            name = DRAWABLES.get(path.rsplit("/", 1)[-1])
            image = DRAWABLE_ROOT / name if name else None
            if image is None or not image.is_file():
                self._json(HTTPStatus.NOT_FOUND, {"error": "Không tìm thấy tài nguyên"})
            else:
                self._send_bytes(HTTPStatus.OK, image.read_bytes(), "image/png")
        elif path == "/api/state":
            if self.controller.authorized(self):
                self._json(HTTPStatus.OK, self.controller.bridge.state())
            else:
                self._json(HTTPStatus.UNAUTHORIZED, {"error": "Thiếu hoặc sai token"})
        else:
            self._json(HTTPStatus.NOT_FOUND, {"error": "Không tìm thấy"})

    def do_POST(self) -> None:
        if urlparse(self.path).path != "/api/control" or not self.controller.authorized(self):
            self._json(HTTPStatus.UNAUTHORIZED, {"error": "Không được phép"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            body = json.loads(self.rfile.read(length).decode("utf-8"))
            if not isinstance(body, dict):
                raise ValueError
        except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
            self._json(HTTPStatus.BAD_REQUEST, {"error": "JSON không hợp lệ"})
            return
        status, result = self.controller.action(body)
        self._json(status, result)

    def _json(self, status: int, body: Dict[str, object]) -> None:
        self._send_bytes(status, json.dumps(body, ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8")

    def _send_bytes(self, status: int, body: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, _format: str, *_args: object) -> None:
        return


def main() -> int:
    parser = argparse.ArgumentParser(description="Web version of the ESP-Drone Android controller")
    parser.add_argument("esp_host", help="IP ESP32, ví dụ 192.168.1.19")
    parser.add_argument("--esp-port", type=int, default=4210)
    parser.add_argument("--listen", default="127.0.0.1", help="0.0.0.0 để máy khác trong LAN truy cập")
    parser.add_argument("--web-port", type=int, default=8080)
    parser.add_argument("--token", help="bắt buộc khi mở ra LAN")
    args = parser.parse_args()
    if not 1 <= args.esp_port <= 65535 or not 1 <= args.web_port <= 65535:
        raise SystemExit("Cổng phải trong khoảng 1-65535")
    if args.listen not in {"127.0.0.1", "localhost", "::1"} and not args.token:
        raise SystemExit("Cần --token khi mở điều khiển bay ra mạng LAN")
    token = args.token or secrets.token_urlsafe(18)
    bridge = UavBridge(args.esp_host, args.esp_port)
    bridge.start()
    server = ThreadingHTTPServer((args.listen, args.web_port), Handler)
    server.controller = Controller(bridge, token)  # type: ignore[attr-defined]
    # Keep startup output ASCII-safe for legacy Windows console encodings.
    print("Open: http://<PC-LAN-IP>:%d/?token=%s" % (args.web_port, token))
    print("Open the page, then press the Connect button in the top-right corner.")
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        bridge.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
