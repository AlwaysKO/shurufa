import hashlib
import os
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import agent

SHA = "a" * 64
TOKEN = "sfi_" + "b" * 64
JOB = {
    "id": "00000000-0000-4000-8000-000000000001",
    "kind": "wechat_favorites",
    "leaseToken": "c" * 64,
    "discovered": 0,
    "validated": 0,
    "validationFailed": 0,
    "sourceErrors": {},
}


class Fake:
    def __init__(self, existing=False):
        self.calls = []
        self.existing = existing

    def request(self, path, body=None, **kw):
        self.calls.append((path, body, kw))
        if path.endswith("/match"):
            return {
                "items": [
                    {"sha256": s, "status": "existing" if self.existing else "missing"}
                    for s in body["sha256s"]
                ]
            }
        return {"ok": True}


class AgentTests(unittest.TestCase):
    def test_origin_boundaries(self):
        self.assertEqual(
            agent.validate_origin("https://example.com/"), "https://example.com"
        )
        for url in [
            "http://example.com",
            "https://user:pw@example.com",
            "https://example.com/a",
            "https://example.com/?a=1",
            "https://example.com/#x",
            "https://example.com\\@evil",
            "http://127.0.0.1.evil",
        ]:
            with self.assertRaises(agent.AgentError):
                agent.validate_origin(url, True)
        self.assertEqual(
            agent.validate_origin("http://127.0.0.1:3310", True),
            "http://127.0.0.1:3310",
        )
        with self.assertRaises(agent.AgentError):
            agent.validate_origin("http://127.0.0.1")

    def run_job(self, transport, count=1, collector=None, job=None, interval=20):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            items = []
            for n in range(count):
                content = str(n).encode()
                p = root / (str(n) + ".gif")
                p.write_bytes(content)
                items.append(
                    {"path": str(p), "sha256": hashlib.sha256(content).hexdigest()}
                )
            collect = collector or (
                lambda *a, **k: {"total": count, "items": items, "sourceErrors": []}
            )
            runner = agent.Runner(
                transport,
                {
                    "account_dir": "local-account",
                    "weixin_exe": "local-exe",
                    "cache_dir": str(root),
                },
                collect,
                renew_interval=interval,
                log=lambda s: None,
            )
            runner.process(dict(job or JOB))
        return transport.calls

    def test_hundred_existing_zero_upload(self):
        calls = self.run_job(Fake(True), 100)
        self.assertFalse(any("/materials" in c[0] for c in calls))
        self.assertEqual(
            next(c[1] for c in calls if c[0].endswith("/complete")),
            {"status": "completed"},
        )

    def test_missing_only_uploaded(self):
        calls = self.run_job(Fake(), 2)
        self.assertEqual(sum("/materials?" in c[0] for c in calls), 2)

    def test_slow_collector_renews(self):
        def slow(*a, **k):
            time.sleep(0.08)
            return {"total": 0, "items": [], "sourceErrors": []}

        calls = self.run_job(Fake(), collector=slow, interval=0.015)
        self.assertGreaterEqual(sum(c[0].endswith("/renew") for c in calls), 2)

    def test_unknown_kind_never_collects(self):
        with self.assertRaises(agent.AgentError):
            self.run_job(
                Fake(),
                collector=lambda *a, **k: self.fail("collector called"),
                job={**JOB, "kind": "shell", "path": "C:/secret"},
            )

    def test_remote_extra_path_rejected(self):
        with self.assertRaises(agent.AgentError):
            self.run_job(Fake(), job={**JOB, "account_dir": "remote"})

    def test_revoked_renew_cancels_collector(self):
        class Revoked(Fake):
            def request(self, path, *a, **k):
                if path.endswith("/renew"):
                    raise agent.HttpError(401)
                return super().request(path, *a, **k)

        def slow(*a, **k):
            for _ in range(100):
                if k["cancel"]():
                    raise agent.CollectionCancelled()
                time.sleep(0.005)
            self.fail("not cancelled")

        with self.assertRaises(agent.HttpError):
            self.run_job(Revoked(), collector=slow, interval=0.01)

    def test_expired_renew_cancels_without_complete(self):
        class Expired(Fake):
            def request(self, path, *a, **k):
                if path.endswith("/renew"):
                    raise agent.HttpError(409)
                return super().request(path, *a, **k)

        fake = Expired()

        def slow(*a, **k):
            while not k["cancel"]():
                time.sleep(0.005)
            raise agent.CollectionCancelled()

        with self.assertRaises(agent.HttpError) as caught:
            self.run_job(fake, collector=slow, interval=0.01)
        self.assertEqual(caught.exception.status, 409)
        self.assertFalse(any(c[0].endswith("/complete") for c in fake.calls))

    def test_upload_failure_does_not_block_next(self):
        class Rejected(Fake):
            def request(self, path, *a, **k):
                if "/materials?" in path:
                    self.calls.append((path, None, k))
                    if sum("/materials?" in c[0] for c in self.calls) == 1:
                        raise agent.HttpError(400)
                    return {}
                return super().request(path, *a, **k)

        calls = self.run_job(Rejected(), 2)
        self.assertEqual(sum("/materials?" in c[0] for c in calls), 2)
        self.assertTrue(
            any(c[1].get("failures") for c in calls if c[0].endswith("/report"))
        )
        self.assertEqual(calls[-1][1]["status"], "completed")

    def test_success_cancels_background_inflight_wait(self):
        exited = threading.Event()
        started = threading.Event()

        class SlowHeartbeat(Fake):
            def request(self, path, *args, **kwargs):
                if path == "/heartbeat":
                    started.set()
                    while not kwargs["cancel"]():
                        time.sleep(0.002)
                    exited.set()
                    raise agent.CollectionCancelled()
                return super().request(path, *args, **kwargs)

        def collecting(*args, **kwargs):
            self.assertTrue(started.wait(0.5))
            return {"total": 0, "items": [], "sourceErrors": []}

        calls = self.run_job(SlowHeartbeat(), collector=collecting, interval=0.005)
        self.assertTrue(exited.is_set())
        self.assertEqual(calls[-1][1]["status"], "completed")

    def test_duplicate_initial_match_rejected(self):
        class Duplicate(Fake):
            def request(self, path, *args, **kwargs):
                result = super().request(path, *args, **kwargs)
                if path.endswith("/match"):
                    result["items"] *= 2
                return result

        fake = Duplicate(True)
        with self.assertRaises(agent.AgentError):
            self.run_job(fake)
        self.assertFalse(any(c[0].endswith("/complete") for c in fake.calls))

    def test_response_loss_requires_exact_valid_match(self):
        for reply in [
            [],
            [{"sha256": SHA, "status": "existing"}],
            [{"sha256": SHA, "status": "unexpected"}],
            [{"sha256": hashlib.sha256(b"0").hexdigest(), "status": "unexpected"}],
        ]:
            with self.subTest(reply=reply):

                class Malformed(Fake):
                    lost = False

                    def request(self, path, *args, **kwargs):
                        if "/materials?" in path:
                            self.lost = True
                            raise agent.AgentError("network")
                        if self.lost and path.endswith("/match"):
                            return {"items": reply}
                        return super().request(path, *args, **kwargs)

                fake = Malformed()
                with self.assertRaises(agent.AgentError):
                    self.run_job(fake)
                self.assertFalse(any(c[0].endswith("/complete") for c in fake.calls))

    def test_control_plane_permanent_error_stops_outer_loop(self):
        for endpoint in ["/heartbeat", "/claim"]:
            with self.subTest(endpoint=endpoint):

                class Rejected(Fake):
                    def request(self, path, *args, **kwargs):
                        self.calls.append((path, None, kwargs))
                        if path == endpoint:
                            raise agent.HttpError(400)
                        return {}

                fake = Rejected()
                stop = unittest.mock.Mock()
                stop.is_set.return_value = False
                stop.wait.return_value = True
                runner = agent.Runner(fake, {}, stop=stop, log=lambda s: None)
                runner.run()
                stop.wait.assert_not_called()

    def test_snapshot_error_not_empty_success(self):
        def fail(*a, **k):
            raise agent.CollectorError("key_not_found")

        calls = self.run_job(Fake(), collector=fail)
        self.assertEqual(
            calls[-1][1], {"status": "failed", "jobErrorCode": "key_not_found"}
        )

    def test_lower_reclaimed_stats_fail_without_report(self):
        calls = self.run_job(Fake(), job={**JOB, "discovered": 10})
        self.assertFalse(any(c[0].endswith("/report") for c in calls))
        self.assertEqual(calls[-1][1]["status"], "failed")

    def test_upload_response_lost_rematches(self):
        class Lost(Fake):
            def request(self, path, *a, **k):
                if "/materials?" in path:
                    self.existing = True
                    self.calls.append((path, None, k))
                    raise agent.AgentError("network")
                return super().request(path, *a, **k)

        calls = self.run_job(Lost())
        self.assertEqual(sum(c[0].endswith("/match") for c in calls), 2)
        self.assertEqual(calls[-1][1]["status"], "completed")

    @unittest.skipUnless(os.name == "nt", "Windows DPAPI")
    def test_dpapi_roundtrip(self):
        encrypted = agent.protect(TOKEN.encode())
        self.assertNotIn(TOKEN.encode(), encrypted)
        self.assertEqual(agent.unprotect(encrypted), TOKEN.encode())


class TransportTests(unittest.TestCase):
    def setUp(self):
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

        self.received = []
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                owner.received.append(
                    (
                        self.path,
                        self.headers.get("Authorization"),
                        self.rfile.read(int(self.headers.get("Content-Length", "0"))),
                    )
                )
                if self.path.endswith("/pair"):
                    payload = b'{"token":"' + TOKEN.encode() + b'"}'
                    self.send_response(201)
                    self.end_headers()
                    self.wfile.write(payload)
                elif self.path.endswith("/heartbeat"):
                    self.send_response(302)
                    self.send_header("Location", "/stolen")
                    self.end_headers()
                else:
                    self.send_response(400)
                    self.end_headers()

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.transport = agent.Transport(
            "http://127.0.0.1:" + str(self.server.server_port), TOKEN, True
        )

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def test_pair_payload_and_response(self):
        self.assertEqual(
            self.transport.request("/pair", {"code": "one-use", "name": "test"})[
                "token"
            ],
            TOKEN,
        )
        self.assertIn(b"one-use", self.received[0][2])

    def test_redirect_not_followed(self):
        with self.assertRaises(agent.HttpError):
            self.transport.request("/heartbeat")
        self.assertEqual(len(self.received), 1)

    def test_400_not_retried(self):
        with self.assertRaises(agent.HttpError):
            self.transport.request("/claim")
        self.assertEqual(len(self.received), 1)

    def test_external_route_rejected_before_request(self):
        with self.assertRaises(agent.AgentError):
            self.transport.request("https://evil.test/")
        self.assertEqual(self.received, [])

    def test_network_bounded_retries(self):
        import urllib.error

        with patch.object(
            self.transport.opener,
            "open",
            side_effect=urllib.error.URLError("secret-token"),
        ), patch.object(self.transport, "wait"):
            with self.assertRaises(agent.AgentError) as caught:
                self.transport.request("/claim")
            self.assertNotIn("secret-token", str(caught.exception))
            self.assertEqual(self.transport.opener.open.call_count, 3)

    def test_renew_rate_limit_no_wait(self):
        import urllib.error
        from email.message import Message

        headers = Message()
        headers["Retry-After"] = "60"
        with patch.object(
            self.transport.opener,
            "open",
            side_effect=urllib.error.HTTPError("secret", 429, "secret", headers, None),
        ), patch.object(self.transport, "wait") as wait:
            with self.assertRaises(agent.AgentError):
                self.transport.request(
                    "/jobs/" + JOB["id"] + "/renew",
                    lease=JOB["leaseToken"],
                    retry=False,
                )
            self.assertEqual(self.transport.opener.open.call_count, 1)
            self.assertFalse(any(call.args[0] >= 60 for call in wait.call_args_list))

    def test_429_respected(self):
        import urllib.error
        from email.message import Message

        headers = Message()
        headers["Retry-After"] = "60"
        with patch.object(
            self.transport.opener,
            "open",
            side_effect=urllib.error.HTTPError("secret", 429, "secret", headers, None),
        ), patch.object(self.transport, "wait") as wait:
            with self.assertRaises(agent.AgentError):
                self.transport.request("/claim")
            self.assertGreaterEqual(
                sum(call.args[0] == 60 for call in wait.call_args_list), 2
            )


class BoundaryTests(unittest.TestCase):
    def test_real_slow_headers_deadline_closes_connection(self):
        import socketserver

        closed = threading.Event()

        class Handler(socketserver.BaseRequestHandler):
            def handle(self):
                self.request.recv(8192)
                try:
                    self.request.sendall(b"HTTP/1.1 200 OK\r\nX-Slow: ")
                    for _ in range(12):
                        self.request.sendall(b"x")
                        time.sleep(0.1)
                    self.request.sendall(b"\r\nContent-Length: 2\r\n\r\n{}")
                except OSError:
                    closed.set()

        server = socketserver.TCPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            transport = agent.Transport(
                "http://127.0.0.1:" + str(server.server_address[1]),
                allow_local_http=True,
                response_timeout=0.1,
            )
            before = time.monotonic()
            with self.assertRaises(agent.AgentError):
                transport.request("/claim", retry=False)
            self.assertLess(time.monotonic() - before, 0.6)
            self.assertTrue(closed.wait(0.6))
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_stream_total_deadline(self):
        class Slow:
            def __enter__(self):
                return self

            def __exit__(self, *args):
                pass

            def read1(self, n):
                time.sleep(0.012)
                return b" "

        transport = agent.Transport("https://example.test", response_timeout=0.02)
        with patch.object(transport.opener, "open", return_value=Slow()), patch.object(
            transport, "wait"
        ):
            before = time.monotonic()
            with self.assertRaises(agent.AgentError):
                transport.request("/claim", retry=False)
            self.assertLess(time.monotonic() - before, 0.2)

    def test_retry_wait_job_cancellation(self):
        transport = agent.Transport("https://example.test")
        cancelled = threading.Event()
        threading.Timer(0.02, cancelled.set).start()
        before = time.monotonic()
        with self.assertRaises(agent.CollectionCancelled):
            transport.wait(60, cancelled.is_set)
        self.assertLess(time.monotonic() - before, 0.5)


if __name__ == "__main__":
    unittest.main()
