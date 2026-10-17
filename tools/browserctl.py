#!/usr/bin/env python3
"""Control the Android browser over its HTTP API. Requires only Python's standard library."""

import argparse
import json
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.request

PACKAGE = "com.vixmobi.browser"


class ApiError(Exception):
    def __init__(self, result):
        self.result = result
        super().__init__(result.get("message", result.get("error", "Request failed")))


class BrowserClient:
    def __init__(self, base_url, timeout=12):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(self, path, payload=None):
        data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers = {}
        if data is not None:
            headers["Content-Type"] = "application/json; charset=utf-8"
        request = urllib.request.Request(self.base_url + path, data=data, headers=headers)
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                result = json.load(response)
        except urllib.error.HTTPError as error:
            try:
                result = json.load(error)
            except (ValueError, OSError):
                result = {"ok": False, "error": "http_error", "message": str(error)}
            result["http_status"] = error.code
            raise ApiError(result) from error
        if not result.get("ok"):
            raise ApiError(result)
        return result

    def wait_for_page(self, timeout):
        deadline = time.monotonic() + timeout
        previous_url = None
        idle_count = 0
        while time.monotonic() < deadline:
            result = self.request("/api/state")
            state = result["state"]
            if state["page_error"]:
                raise ApiError({"ok": False, "error": "page_load_failed", "state": state})
            if state["url"] and not state["loading"]:
                idle_count = idle_count + 1 if state["url"] == previous_url else 1
                if idle_count >= 2:
                    return result
            else:
                idle_count = 0
            previous_url = state["url"]
            time.sleep(0.2)
        raise ApiError({"ok": False, "error": "page_load_timeout"})


def adb(args, *command):
    invocation = ["adb"]
    if args.serial:
        invocation += ["-s", args.serial]
    completed = subprocess.run(invocation + list(command), check=False, capture_output=True,
                               text=True, encoding="utf-8", errors="replace", timeout=30)
    if completed.returncode or "Error:" in completed.stdout:
        raise RuntimeError((completed.stderr or completed.stdout).strip())
    return completed.stdout.strip()


def connect(args):
    if args.base_url:
        return BrowserClient(args.base_url)
    adb(args, "shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    adb(args, "forward", "tcp:" + str(args.port), "tcp:8765")
    client = BrowserClient("http://127.0.0.1:" + str(args.port))
    deadline = time.monotonic() + 5
    while True:
        try:
            client.request("/api/state")
            return client
        except (urllib.error.URLError, ApiError) as error:
            if time.monotonic() >= deadline:
                raise error
            if isinstance(error, ApiError) and error.result.get("http_status") != 503:
                raise error
            time.sleep(0.2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="ADB device serial, e.g. emulator-5580")
    parser.add_argument("--port", type=int, default=8765, help="Host forwarding port (default: 8765)")
    parser.add_argument("--base-url", help="Use an existing HTTP connection, without invoking ADB")
    parser.add_argument("--timeout", type=float, default=30, help="Page loading timeout for --wait")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("setup", help="Start the app and prepare port forwarding")
    commands.add_parser("state", help="Read the current browser state")
    commands.add_parser("loading", help="Read page loading status and progress")
    navigate = commands.add_parser("navigate", help="Navigate to a URL")
    navigate.add_argument("url")
    navigate.add_argument("--referer", help="Set and save the Referer for this and future loads; empty clears it")
    navigate.add_argument("--ua", help="Set and save a User-Agent; empty restores the system default")
    navigate_history = navigate.add_mutually_exclusive_group()
    navigate_history.add_argument("--history-length", type=int, help="Override JS history.length")
    navigate_history.add_argument("--default-history", action="store_true", help="Restore real history.length")
    navigate.add_argument("--wait", action="store_true", help="Wait until page loading finishes")
    for name in ("back", "forward", "refresh"):
        command = commands.add_parser(name)
        command.add_argument("--wait", action="store_true")
    commands.add_parser("clear-cache", help="Clear WebView memory and disk resource caches")
    referer = commands.add_parser("referer", help="Read, set, or clear the saved Referer")
    referer_source = referer.add_mutually_exclusive_group()
    referer_source.add_argument("value", nargs="?", help="Complete HTTP(S) source URL")
    referer_source.add_argument("--clear", action="store_true", help="Restore WebView defaults")
    ua = commands.add_parser("ua", help="Read, set, or clear the User-Agent")
    ua_source = ua.add_mutually_exclusive_group()
    ua_source.add_argument("value", nargs="?", help="User-Agent string")
    ua_source.add_argument("--clear", action="store_true")
    history = commands.add_parser("history-length", help="Read, set, or clear JS history.length")
    history_source = history.add_mutually_exclusive_group()
    history_source.add_argument("value", nargs="?", type=int, help="Integer from 0 to 2147483647")
    history_source.add_argument("--clear", action="store_true")
    settings = commands.add_parser("settings", help="Read all settings or atomically update a JSON object")
    settings.add_argument("json", nargs="?", help='Example: \'{"ua":"vixBrowser/1.4","history_length":7}\'')
    javascript = commands.add_parser("js", help="Execute JavaScript and return its value")
    source = javascript.add_mutually_exclusive_group(required=True)
    source.add_argument("script", nargs="?", help="JavaScript source code")
    source.add_argument("--file", help="Read JavaScript from a UTF-8 file")
    args = parser.parse_args()
    try:
        client = connect(args)
        if args.command == "setup":
            result = {"ok": True, "base_url": client.base_url}
        elif args.command == "state":
            result = client.request("/api/state")
        elif args.command == "loading":
            result = client.request("/api/loading")
        elif args.command == "navigate":
            payload = {"url": args.url}
            if args.referer is not None:
                payload["referer"] = args.referer
            if args.ua is not None:
                payload["ua"] = args.ua
            if args.history_length is not None:
                payload["history_length"] = args.history_length
            elif args.default_history:
                payload["history_length"] = None
            result = client.request("/api/navigate", payload)
        elif args.command == "referer":
            if args.clear:
                result = client.request("/api/referer", {"referer": None})
            elif args.value is not None:
                result = client.request("/api/referer", {"referer": args.value})
            else:
                result = client.request("/api/referer")
        elif args.command in ("ua", "history-length"):
            key = "ua" if args.command == "ua" else "history_length"
            path = "/api/" + args.command
            if args.clear:
                result = client.request(path, {key: None})
            elif args.value is not None:
                result = client.request(path, {key: args.value})
            else:
                result = client.request(path)
        elif args.command == "settings":
            if args.json is None:
                result = client.request("/api/settings")
            else:
                payload = json.loads(args.json)
                if not isinstance(payload, dict):
                    raise ValueError("Settings must be a JSON object")
                result = client.request("/api/settings", payload)
        elif args.command == "js":
            script = Path(args.file).read_text(encoding="utf-8") if args.file else args.script
            result = client.request("/api/js/execute", {"script": script})
        elif args.command == "clear-cache":
            result = client.request("/api/cache/clear", {})
        else:
            result = client.request("/api/" + args.command, {})
        if getattr(args, "wait", False):
            result = client.wait_for_page(args.timeout)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except ApiError as error:
        print(json.dumps(error.result, ensure_ascii=False, indent=2))
        return 1
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(json.dumps({"ok": False, "error": "client_error", "message": str(error)},
                         ensure_ascii=False, indent=2))
        return 1


if __name__ == "__main__":
    sys.exit(main())
