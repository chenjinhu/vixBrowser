#!/usr/bin/env python3
"""Integration checks against an installed debug APK, using temporary local fixture pages."""

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import shlex
import threading
import time

from browserctl import ApiError, PACKAGE, adb, connect


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--port", type=int, default=18765)
    args = parser.parse_args()
    args.base_url = None
    cache_hits = []
    slow_started = threading.Event()
    release_slow = threading.Event()

    class Fixture(BaseHTTPRequestHandler):
        def log_message(self, *unused):
            pass

        def do_GET(self):
            if self.path == "/slow":
                slow_started.set()
                if not release_slow.wait(15):
                    self.send_error(504)
                    return
                body = b"<!doctype html><title>Slow Page</title><h1>Loaded</h1>"
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Cache-Control", "no-store")
            elif self.path == "/redirect":
                self.send_response(302)
                self.send_header("Location", "/one?redirected=1")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            elif self.path.startswith("/profile-resource.js"):
                ua = json.dumps(self.headers.get("User-Agent", ""), ensure_ascii=True)
                body = ("window.resourceUa=" + ua + ";").encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/javascript")
                self.send_header("Cache-Control", "no-store")
            elif self.path.startswith("/profile"):
                ua = json.dumps(self.headers.get("User-Agent", ""), ensure_ascii=True)
                body = ("<!doctype html><html><head><title>Browser Profile</title>"
                        "<script>window.profileAtStart={history:history.length,ua:navigator.userAgent,"
                        "receivedUa:" + ua + "};</script></head><body><h1>Browser Profile</h1>")
                if not self.path.startswith("/profile-frame"):
                    body += ("<script src='/profile-resource.js'></script>"
                             "<iframe id='profile-frame' src='/profile-frame'></iframe>")
                body = (body + "</body></html>").encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Cache-Control", "no-store")
            elif self.path.startswith("/referer"):
                received = json.dumps(self.headers.get("Referer", ""), ensure_ascii=True)
                body = ("<!doctype html><html><head><title>Referer Echo</title></head>"
                        "<body><h1>Referer Echo</h1><script>window.receivedReferer="
                        + received + ";</script></body></html>").encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Cache-Control", "no-store")
            elif self.path == "/cached.js":
                cache_hits.append(time.monotonic())
                body = b"window.fixtureCached = true;"
                self.send_response(200)
                self.send_header("Content-Type", "application/javascript")
                self.send_header("Cache-Control", "public, max-age=3600")
            elif self.path.startswith(("/one", "/two")):
                label = "One" if self.path.startswith("/one") else "Two"
                body = ("<!doctype html><html><head><meta name='viewport' "
                        "content='width=device-width,initial-scale=1'><title>Page " + label
                        + "</title><script src='/cached.js'></script></head><body>"
                        + "<h1 id='heading'>Page " + label + "</h1>"
                        + "<a id='next' href='/two'>Next page</a></body></html>").encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Cache-Control", "no-store")
            else:
                body = b"Not found"
                self.send_response(404)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    fixture = ThreadingHTTPServer(("127.0.0.1", 0), Fixture)
    fixture.daemon_threads = True
    threading.Thread(target=fixture.serve_forever, daemon=True).start()
    reverse = "tcp:18888"
    passed = []
    client = None
    original_settings = None

    def mark(name):
        passed.append(name)
        print("PASS " + name, flush=True)

    def expect_error(client, path, payload, code):
        try:
            client.request(path, payload)
        except ApiError as error:
            assert error.result["error"] == code, error.result
        else:
            raise AssertionError("Expected " + code)

    def assert_received_referer(expected):
        client.wait_for_page(30)
        actual = client.request("/api/js/execute", {"script": "window.receivedReferer"})["result"]
        assert actual == expected, {"expected": expected, "received": actual}

    def assert_profile(expected_ua, expected_history):
        client.wait_for_page(30)
        profile = client.request("/api/js/execute", {"script":
            "({head:window.profileAtStart,now:history.length,resourceUa:window.resourceUa,"
            "frame:document.querySelector('#profile-frame').contentWindow.profileAtStart})"})["result"]
        assert profile["head"]["history"] == expected_history, profile
        assert profile["now"] == expected_history, profile
        assert profile["frame"]["history"] == expected_history, profile
        for value in (profile["head"]["ua"], profile["head"]["receivedUa"],
                      profile["frame"]["ua"], profile["frame"]["receivedUa"], profile["resourceUa"]):
            assert value == expected_ua, profile

    try:
        adb(args, "reverse", reverse, "tcp:" + str(fixture.server_port))
        adb(args, "shell", "am", "force-stop", PACKAGE)
        client = connect(args)
        original_settings = client.request("/api/settings")["settings"]
        idle = client.request("/api/loading")
        assert idle["status"] == "idle" and not idle["loading"], idle
        assert idle["progress"] == 0 and idle["url"] is None and idle["page_error"] is None, idle
        expect_error(client, "/api/loading", {}, "method_not_allowed")
        mark("loading endpoint reports idle and rejects POST")
        client.request("/api/settings", {"referer": None, "ua": None, "history_length": None})
        root = "http://127.0.0.1:18888"
        client.request("/api/navigate", {"url": root + "/slow"})
        assert slow_started.wait(5), "Slow page was not requested"
        pending = client.request("/api/loading")
        assert pending["status"] == "loading" and pending["loading"], pending
        assert pending["url"] == root + "/slow" and pending["page_error"] is None, pending
        assert 0 <= pending["progress"] <= 100, pending
        release_slow.set()
        client.wait_for_page(30)
        complete = client.request("/api/loading")
        assert complete["status"] == "complete" and not complete["loading"], complete
        assert complete["progress"] == 100 and complete["title"] == "Slow Page", complete
        assert complete["page_error"] is None, complete
        state = client.request("/api/state")["state"]
        for key in ("loading", "progress", "url", "page_error"):
            assert state[key] == complete[key], (state, complete)
        assert state["load_status"] == complete["status"], (state, complete)
        mark("loading endpoint observes delayed navigation and completion with progress")

        client.request("/api/navigate", {"url": root + "/redirect"})
        client.wait_for_page(30)
        redirected = client.request("/api/loading")
        assert redirected["status"] == "complete", redirected
        assert redirected["url"] == root + "/one?redirected=1", redirected
        mark("loading endpoint follows redirects to the final URL")

        client.request("/api/navigate", {"url": root + "/one"})
        state = client.wait_for_page(30)["state"]
        assert state["title"] == "Page One", state
        mark("navigate and state")

        result = client.request("/api/js/execute", {"script":
            "({title:document.title, heading:document.querySelector('#heading').textContent, n:2+3})"})
        assert result["result"] == {"title": "Page One", "heading": "Page One", "n": 5}, result
        client.request("/api/js/execute", {"script": "window.smokeText = '引号\\\"与换行\\n';"})
        assert client.request("/api/js/execute", {"script": "window.smokeText"})["result"] == '引号"与换行\n'
        expect_error(client, "/api/js/execute", {"script": "throw new Error('smoke')"}, "js_error")
        expect_error(client, "/api/js/execute", {"script": "Promise.resolve(1)"}, "js_error")
        mark("JS values, Unicode, side effects and errors")

        client.request("/api/navigate", {"url": root + "/two"})
        assert client.wait_for_page(30)["state"]["title"] == "Page Two"
        client.request("/api/back", {})
        assert client.wait_for_page(30)["state"]["title"] == "Page One"
        client.request("/api/forward", {})
        assert client.wait_for_page(30)["state"]["title"] == "Page Two"
        mark("back and forward")

        client.request("/api/js/execute", {"script": "document.title = 'Changed'"})
        client.request("/api/refresh", {})
        assert client.wait_for_page(30)["state"]["title"] == "Page Two"
        mark("refresh")

        previous_hits = len(cache_hits)
        client.request("/api/cache/clear", {})
        client.request("/api/navigate", {"url": root + "/one?after-clear=1"})
        client.wait_for_page(30)
        assert len(cache_hits) > previous_hits, (previous_hits, cache_hits)
        mark("clear resource cache")

        for style in ("extra", "data"):
            url = root + "/two?from=" + style + "&value=%E4%B8%AD"
            command = ["am", "start", "-W", "-n", PACKAGE + "/.MainActivity"]
            command += ["--es", "url", url] if style == "extra" else ["-a", "android.intent.action.VIEW", "-d", url]
            adb(args, "shell", " ".join(shlex.quote(part) for part in command))
            state = client.wait_for_page(30)["state"]
            assert state["url"] == url, state
        mark("warm Intent url extra and ACTION_VIEW URI")

        expect_error(client, "/api/navigate", {"url": "file:///sdcard/test"}, "invalid_url")
        expect_error(client, "/api/js/execute", {"script": 123}, "invalid_script")
        assert client.request("/api/state")["ok"]
        assert client.request("/api/cache/clear", {})["ok"]
        mark("parameter validation and GET/POST without authentication")

        client.request("/api/navigate", {"url": root + "/missing"})
        try:
            client.wait_for_page(30)
        except ApiError as error:
            assert error.result["error"] == "page_load_failed", error.result
        else:
            raise AssertionError("Expected HTTP error in state")
        failed = client.request("/api/loading")
        assert failed["status"] == "error" and not failed["loading"] and failed["page_error"], failed
        assert failed["url"] == root + "/missing", failed
        client.request("/api/navigate", {"url": root + "/one"})
        client.wait_for_page(30)
        recovered = client.request("/api/loading")
        assert recovered["status"] == "complete" and recovered["page_error"] is None, recovered
        mark("loading endpoint distinguishes page failure from successful recovery")
        mark("HTTP page errors and recovery")

        referer = root + "/source?from=api&value=1"
        assert client.request("/api/referer", {"referer": referer})["referer"] == referer
        assert client.request("/api/referer")["referer"] == referer
        client.request("/api/navigate", {"url": root + "/referer?from=api"})
        assert_received_referer(referer)
        mark("custom Referer reaches the HTTP server")

        referer = root + "/changed?from=refresh"
        client.request("/api/referer", {"referer": referer})
        client.request("/api/refresh", {})
        assert_received_referer(referer)
        mark("refresh applies an updated Referer")

        referer = root + "/inline?value=1"
        client.request("/api/navigate", {"url": root + "/referer?from=inline",
                                         "referer": referer + "#removed-fragment"})
        assert_received_referer(referer)
        assert client.request("/api/state")["state"]["referer"] == referer
        expect_error(client, "/api/referer", {}, "invalid_referer")
        for invalid in (123, "https://source.example/\r\nX-Injected: true", "file:///sdcard/test"):
            expect_error(client, "/api/referer", {"referer": invalid}, "invalid_referer")
        expect_error(client, "/api/navigate", {"url": "file:///sdcard/test",
                     "referer": root + "/must-not-save"}, "invalid_url")
        assert client.request("/api/referer")["referer"] == referer
        mark("inline Referer, normalization and invalid-input atomicity")

        adb(args, "shell", "am", "force-stop", PACKAGE)
        client = connect(args)
        assert client.request("/api/referer")["referer"] == referer
        client.request("/api/navigate", {"url": root + "/referer?from=restart"})
        assert_received_referer(referer)
        mark("Referer survives process restart")

        referer = root + "/intent-source?value=1"
        command = ["am", "start", "-W", "-n", PACKAGE + "/.MainActivity",
                   "--es", "url", root + "/referer?from=intent", "--es", "referer", referer]
        adb(args, "shell", " ".join(shlex.quote(part) for part in command))
        assert_received_referer(referer)
        mark("Intent Referer reaches the HTTP server")

        client.request("/api/referer", {"referer": ""})
        client.request("/api/refresh", {})
        assert_received_referer("")
        assert client.request("/api/referer")["referer"] is None
        client.request("/api/navigate", {"url": root + "/referer?from=clear", "referer": None})
        assert_received_referer("")
        mark("clearing Referer restores default requests")

        default_ua = client.request("/api/ua")["effective_ua"]
        ua = "vixBrowser-Smoke/1.2 (Android)"
        settings = client.request("/api/settings", {"ua": ua, "history_length": 7})["settings"]
        assert settings["history_length_supported"] is True, settings
        assert settings["ua"] == ua and settings["history_length"] == 7, settings
        client.request("/api/navigate", {"url": root + "/profile?first"})
        assert_profile(ua, 7)
        mark("UA reaches HTTP, JS, subresources and frames; history is overridden before page scripts")

        client.request("/api/navigate", {"url": root + "/profile?second"})
        assert_profile(ua, 7)
        client.request("/api/back", {})
        assert_profile(ua, 7)
        assert client.request("/api/state")["state"]["url"] == root + "/profile?first"
        client.request("/api/forward", {})
        assert_profile(ua, 7)
        assert client.request("/api/state")["state"]["url"] == root + "/profile?second"
        mark("history.length override preserves real back and forward navigation")

        client.request("/api/history-length", {"history_length": 0})
        assert client.request("/api/js/execute", {"script": "history.length"})["result"] == 0
        client.request("/api/history-length", {"history_length": 123})
        assert client.request("/api/js/execute", {"script": "history.length"})["result"] == 123
        ua = "vixBrowser-Changed/1.2"
        client.request("/api/ua", {"ua": ua})
        assert client.request("/api/js/execute", {"script": "navigator.userAgent"})["result"] == ua
        client.request("/api/refresh", {})
        assert_profile(ua, 123)
        mark("UA and history.length update on the current page and survive refresh")

        before = client.request("/api/settings")["settings"]
        for value in (-1, 2.5, "7", True, 2147483648):
            expect_error(client, "/api/history-length", {"history_length": value}, "invalid_history_length")
        for value in (123, "bad\r\nInjected: true", "非ASCII"):
            expect_error(client, "/api/ua", {"ua": value}, "invalid_ua")
        expect_error(client, "/api/settings", {"ua": "must-not-save", "history_length": -1},
                     "invalid_history_length")
        expect_error(client, "/api/navigate", {"url": "file:///sdcard/test", "ua": "must-not-save"},
                     "invalid_url")
        assert client.request("/api/settings")["settings"] == before
        mark("UA/history validation and atomic multi-setting updates")

        ua = "vixBrowser-Persist/1.2"
        client.request("/api/settings", {"ua": ua, "history_length": 23})
        client.request("/api/navigate", {"url": root + "/profile?before-restart"})
        assert_profile(ua, 23)
        adb(args, "shell", "am", "force-stop", PACKAGE)
        client = connect(args)
        settings = client.request("/api/settings")["settings"]
        assert settings["ua"] == ua and settings["history_length"] == 23, settings
        client.request("/api/navigate", {"url": root + "/profile?after-restart"})
        assert_profile(ua, 23)
        mark("UA and history.length survive process restart")

        ua = "vixBrowser-Intent/1.2"
        command = ["am", "start", "-W", "-n", PACKAGE + "/.MainActivity",
                   "--es", "url", root + "/profile?intent", "--es", "ua", ua,
                   "--ei", "history_length", "31"]
        adb(args, "shell", " ".join(shlex.quote(part) for part in command))
        assert_profile(ua, 31)
        mark("Intent configures UA and history.length before loading")

        client.request("/api/settings", {"ua": None, "history_length": None})
        assert client.request("/api/ua")["effective_ua"] == default_ua
        client.request("/api/navigate", {"url": root + "/profile?defaults"})
        client.wait_for_page(30)
        baseline = client.request("/api/js/execute", {"script": "history.length"})["result"]
        changed = client.request("/api/js/execute", {"script":
            "history.pushState({},'', '#real-history'); history.length"})["result"]
        assert changed == baseline + 1, {"baseline": baseline, "after_push": changed}
        assert client.request("/api/js/execute", {"script": "navigator.userAgent"})["result"] == default_ua
        mark("clearing UA restores default headers and clearing history restores native history behavior")
        print(json.dumps({"ok": True, "checks": passed}, ensure_ascii=False, indent=2))
        return 0
    finally:
        release_slow.set()
        if original_settings is not None and client is not None:
            try:
                client.request("/api/settings", {key: original_settings[key]
                               for key in ("referer", "ua", "history_length")})
            except Exception as error:
                print("Could not restore the original browser settings:", error)
        fixture.shutdown()
        fixture.server_close()
        adb(args, "reverse", "--remove", reverse)
        adb(args, "forward", "--remove", "tcp:" + str(args.port))


if __name__ == "__main__":
    raise SystemExit(main())
