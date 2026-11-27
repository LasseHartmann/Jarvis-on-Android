#!/usr/bin/env python3
"""
Jarvis PC Bridge — Zero-dependency LAN companion server for Jarvis on Android.

Allows Jarvis on your Android phone to safely send links, copy text to your
Windows clipboard, control PC media playback/volume, lock your workstation, or
show desktop notifications over your local Wi-Fi network.

Security:
  - Protected by a persistent 256-bit Bearer token stored locally at
    ~/.jarvis_pc_bridge_token (never committed to git).
  - Constant-time token comparison via hmac.compare_digest.
  - Strict allowlist of safe actions (no arbitrary shell execution).

Usage:
  python jarvis_pc_bridge.py [--port 8765] [--reset-token]
"""

from __future__ import annotations

import argparse
import ctypes
import hmac
import json
import os
import platform
import secrets
import socket
import subprocess
import sys
import time
import urllib.parse
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

TOKEN_FILE = Path.home() / ".jarvis_pc_bridge_token"
MAX_BODY_BYTES = 64 * 1024
START_TIME = time.time()

# Windows virtual-key codes for media and volume control
VK_VOLUME_MUTE = 0xAD
VK_VOLUME_DOWN = 0xAE
VK_VOLUME_UP = 0xAF
VK_MEDIA_NEXT_TRACK = 0xB0
VK_MEDIA_PREV_TRACK = 0xB1
VK_MEDIA_PLAY_PAUSE = 0xB3
KEYEVENTF_KEYUP = 0x0002


def load_or_create_token(reset: bool = False) -> str:
    if not reset and TOKEN_FILE.exists():
        existing = TOKEN_FILE.read_text(encoding="utf-8").strip()
        if len(existing) >= 24:
            return existing
    token = secrets.token_urlsafe(32)
    TOKEN_FILE.write_text(token + "\n", encoding="utf-8")
    try:
        os.chmod(TOKEN_FILE, 0o600)
    except OSError:
        pass
    return token


def detect_lan_ip() -> str:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        ip = sock.getsockname()[0]
        if ip and not ip.startswith("127."):
            return ip
    except OSError:
        pass
    finally:
        sock.close()
    try:
        return socket.gethostbyname(socket.gethostname())
    except OSError:
        return "127.0.0.1"


def send_windows_vk(vk_code: int, repeat: int = 1) -> None:
    if sys.platform != "win32":
        raise RuntimeError("Media/volume keys are currently supported on Windows.")
    user32 = ctypes.windll.user32
    count = max(1, min(15, int(repeat)))
    for _ in range(count):
        user32.keybd_event(vk_code, 0, 0, 0)
        user32.keybd_event(vk_code, 0, KEYEVENTF_KEYUP, 0)
        if count > 1:
            time.sleep(0.03)


def set_clipboard_text(text: str) -> None:
    if sys.platform == "win32":
        # Use UTF-16LE Win32 clipboard API via ctypes so Unicode/umlauts work cleanly.
        cf_unicodetext = 13
        gmem_moveable = 0x0002
        user32 = ctypes.windll.user32
        kernel32 = ctypes.windll.kernel32
        kernel32.GlobalAlloc.argtypes = [ctypes.c_uint, ctypes.c_size_t]
        kernel32.GlobalAlloc.restype = ctypes.c_void_p
        kernel32.GlobalLock.argtypes = [ctypes.c_void_p]
        kernel32.GlobalLock.restype = ctypes.c_void_p
        kernel32.GlobalUnlock.argtypes = [ctypes.c_void_p]
        user32.SetClipboardData.argtypes = [ctypes.c_uint, ctypes.c_void_p]
        user32.SetClipboardData.restype = ctypes.c_void_p

        encoded = (text + "\x00").encode("utf-16-le")
        if not user32.OpenClipboard(None):
            raise RuntimeError("Could not open Windows clipboard.")
        try:
            user32.EmptyClipboard()
            h_mem = kernel32.GlobalAlloc(gmem_moveable, len(encoded))
            if not h_mem:
                raise RuntimeError("GlobalAlloc failed for clipboard buffer.")
            ptr = kernel32.GlobalLock(h_mem)
            if not ptr:
                raise RuntimeError("GlobalLock failed for clipboard buffer.")
            try:
                ctypes.memmove(ptr, encoded, len(encoded))
            finally:
                kernel32.GlobalUnlock(h_mem)
            if not user32.SetClipboardData(cf_unicodetext, h_mem):
                raise RuntimeError("SetClipboardData failed.")
        finally:
            user32.CloseClipboard()
        return

    if sys.platform == "darwin":
        subprocess.run(["pbcopy"], input=text.encode("utf-8"), check=True)
        return

    subprocess.run(["xclip", "-selection", "clipboard"], input=text.encode("utf-8"), check=True)


def show_desktop_notification(title: str, message: str) -> None:
    clean_title = (title or "J.A.R.V.I.S.").strip()[:80]
    clean_msg = (message or "").strip()[:300]
    print(f"[NOTIFY] {clean_title}: {clean_msg}", flush=True)
    if sys.platform != "win32":
        return
    # Escape single quotes for PowerShell single-quoted literals.
    ps_title = clean_title.replace("'", "''")
    ps_msg = clean_msg.replace("'", "''")
    ps_script = (
        "Add-Type -AssemblyName System.Windows.Forms; "
        "$n = New-Object System.Windows.Forms.NotifyIcon; "
        "$n.Icon = [System.Drawing.SystemIcons]::Information; "
        "$n.Visible = $true; "
        f"$n.ShowBalloonTip(4500, '{ps_title}', '{ps_msg}', [System.Windows.Forms.ToolTipIcon]::Info); "
        "Start-Sleep -Seconds 5; "
        "$n.Dispose()"
    )
    subprocess.Popen(
        ["powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", ps_script],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def lock_workstation() -> None:
    if sys.platform == "win32":
        if not ctypes.windll.user32.LockWorkStation():
            raise RuntimeError("LockWorkStation returned false.")
    elif sys.platform == "darwin":
        subprocess.run(["pmset", "displaysleepnow"], check=True)
    else:
        subprocess.run(["loginctl", "lock-session"], check=True)


def execute_action(payload: dict[str, Any]) -> dict[str, Any]:
    action = str(payload.get("action", "")).strip().lower()
    value = str(payload.get("value", "") or "").strip()
    title = str(payload.get("title", "J.A.R.V.I.S.") or "J.A.R.V.I.S.").strip()
    steps = payload.get("steps", 4)

    if action == "status":
        uptime_s = int(time.time() - START_TIME)
        return {
            "ok": True,
            "action": "status",
            "hostname": socket.gethostname(),
            "platform": f"{platform.system()} {platform.release()}",
            "bridge_uptime_seconds": uptime_s,
            "message": f"Connected to {socket.gethostname()} ({platform.system()} {platform.release()}).",
        }

    if action == "open_url":
        if not value:
            raise ValueError("Missing 'value' URL for open_url.")
        normalized = value if "://" in value else f"https://{value}"
        parsed = urllib.parse.urlparse(normalized)
        if parsed.scheme not in ("http", "https") or not parsed.netloc:
            raise ValueError("Only valid http:// or https:// URLs are allowed.")
        webbrowser.open(normalized, new=2)
        return {"ok": True, "action": action, "url": normalized, "message": f"Opened {normalized} on PC."}

    if action == "set_clipboard":
        if not value:
            raise ValueError("Missing 'value' text for set_clipboard.")
        set_clipboard_text(value)
        return {
            "ok": True,
            "action": action,
            "chars": len(value),
            "message": f"Copied {len(value)} characters to PC clipboard.",
        }

    if action == "lock_pc":
        lock_workstation()
        return {"ok": True, "action": action, "message": "Workstation locked."}

    if action == "media_play_pause":
        send_windows_vk(VK_MEDIA_PLAY_PAUSE, 1)
        return {"ok": True, "action": action, "message": "Toggled PC media playback."}

    if action == "media_next":
        send_windows_vk(VK_MEDIA_NEXT_TRACK, 1)
        return {"ok": True, "action": action, "message": "Skipped to next track on PC."}

    if action == "media_prev":
        send_windows_vk(VK_MEDIA_PREV_TRACK, 1)
        return {"ok": True, "action": action, "message": "Went to previous track on PC."}

    if action == "volume_mute":
        send_windows_vk(VK_VOLUME_MUTE, 1)
        return {"ok": True, "action": action, "message": "Toggled PC mute."}

    if action == "volume_up":
        send_windows_vk(VK_VOLUME_UP, int(steps) if str(steps).isdigit() else 4)
        return {"ok": True, "action": action, "message": "Turned PC volume up."}

    if action == "volume_down":
        send_windows_vk(VK_VOLUME_DOWN, int(steps) if str(steps).isdigit() else 4)
        return {"ok": True, "action": action, "message": "Turned PC volume down."}

    if action == "notify":
        if not value:
            raise ValueError("Missing 'value' message for notify.")
        show_desktop_notification(title, value)
        return {"ok": True, "action": action, "message": "Displayed desktop notification on PC."}

    raise ValueError(
        f"Unsupported action '{action}'. Supported: status, open_url, set_clipboard, "
        "lock_pc, media_play_pause, media_next, media_prev, volume_mute, volume_up, "
        "volume_down, notify."
    )


def make_handler(expected_token: str) -> type[BaseHTTPRequestHandler]:
    class JarvisBridgeHandler(BaseHTTPRequestHandler):
        def log_message(self, fmt: str, *args: Any) -> None:
            ts = time.strftime("%H:%M:%S")
            print(f"[{ts}] {self.address_string()} - {fmt % args}", flush=True)

        def _send_json(self, status_code: int, payload: dict[str, Any]) -> None:
            raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status_code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def _is_authorized(self) -> bool:
            auth = self.headers.get("Authorization", "")
            if not auth.startswith("Bearer "):
                return False
            supplied = auth[len("Bearer "):].strip()
            return hmac.compare_digest(supplied, expected_token)

        def do_GET(self) -> None:  # noqa: N802
            path = urllib.parse.urlparse(self.path).path
            if path == "/health":
                self._send_json(200, {"ok": True, "service": "jarvis-pc-bridge"})
                return
            if path == "/status":
                if not self._is_authorized():
                    self._send_json(401, {"ok": False, "error": "Unauthorized. Check PC Bridge token."})
                    return
                self._send_json(200, execute_action({"action": "status"}))
                return
            self._send_json(404, {"ok": False, "error": "Not found"})

        def do_POST(self) -> None:  # noqa: N802
            path = urllib.parse.urlparse(self.path).path
            if path != "/action":
                self._send_json(404, {"ok": False, "error": "Not found"})
                return
            if not self._is_authorized():
                self._send_json(401, {"ok": False, "error": "Unauthorized. Check PC Bridge token."})
                return
            try:
                content_length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                self._send_json(400, {"ok": False, "error": "Invalid Content-Length"})
                return
            if content_length <= 0 or content_length > MAX_BODY_BYTES:
                self._send_json(400, {"ok": False, "error": "Request body empty or too large"})
                return
            raw_body = self.rfile.read(content_length)
            try:
                payload = json.loads(raw_body.decode("utf-8"))
                if not isinstance(payload, dict):
                    raise ValueError("JSON body must be an object.")
                result = execute_action(payload)
                self._send_json(200, result)
            except ValueError as err:
                self._send_json(400, {"ok": False, "error": str(err)})
            except Exception as err:  # noqa: BLE001
                self._send_json(500, {"ok": False, "error": str(err)})

    return JarvisBridgeHandler


def main() -> None:
    parser = argparse.ArgumentParser(description="Jarvis PC Bridge LAN server")
    parser.add_argument("--host", default="0.0.0.0", help="Bind address (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8765, help="TCP port (default: 8765)")
    parser.add_argument(
        "--reset-token",
        action="store_true",
        help="Generate a brand-new auth token and overwrite ~/.jarvis_pc_bridge_token",
    )
    args = parser.parse_args()

    token = load_or_create_token(reset=args.reset_token)
    lan_ip = detect_lan_ip()

    print("=" * 64)
    print("  J.A.R.V.I.S. PC Bridge is ONLINE")
    print("=" * 64)
    print(f"  PC Bridge URL : http://{lan_ip}:{args.port}")
    print(f"  Auth Token    : {token}")
    print(f"  Token Stored  : {TOKEN_FILE}")
    print("-" * 64)
    print("  In Jarvis on Android -> Settings -> Ecosystem & integrations:")
    print("    1. Paste the PC Bridge URL and Auth Token above.")
    print("    2. Ensure Windows Firewall allows private LAN TCP port", args.port)
    print("  Press Ctrl+C to stop.")
    print("=" * 64, flush=True)

    server = ThreadingHTTPServer((args.host, args.port), make_handler(token))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down Jarvis PC Bridge.")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
