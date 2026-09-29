import http.client
import socketserver
import ssl
import threading
import time
import unittest
import urllib.request

from collector import CollectionCancelled, CdnRedirectHandler
from deadline_http import (
    DeadlineHTTPHandler,
    DeadlineHTTPSHandler,
    connection_factory,
    set_deadline,
)


class DeadlineHTTPTests(unittest.TestCase):
    def test_real_slow_header_cancellation_closes_connection(self):
        cancelled, disconnected = threading.Event(), threading.Event()

        class Handler(socketserver.BaseRequestHandler):
            def handle(self):
                self.request.recv(8192)
                try:
                    self.request.sendall(b"HTTP/1.1 200 OK\r\nX-Slow: ")
                    cancelled.set()
                    for _ in range(20):
                        self.request.sendall(b"x")
                        time.sleep(0.03)
                except OSError:
                    disconnected.set()

        server = socketserver.TCPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()

        def check():
            if cancelled.is_set():
                raise CollectionCancelled()

        request = set_deadline(
            urllib.request.Request("http://127.0.0.1:" + str(server.server_address[1])),
            time.monotonic() + 5,
            check,
        )
        opener = urllib.request.build_opener(DeadlineHTTPHandler())
        try:
            before = time.monotonic()
            with self.assertRaises(CollectionCancelled):
                opener.open(request, timeout=15)
            self.assertLess(time.monotonic() - before, 0.3)
            self.assertTrue(disconnected.wait(0.5))
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_head_and_chunked_body_keep_standard_parsing(self):
        class Handler(socketserver.BaseRequestHandler):
            def handle(self):
                line = self.request.recv(8192)
                if line.startswith(b"HEAD "):
                    self.request.sendall(
                        b"HTTP/1.1 200 OK\r\nContent-Length: 999\r\n\r\n"
                    )
                else:
                    self.request.sendall(
                        b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n2\r\nde\r\n0\r\nX-End: yes\r\n\r\n"
                    )

        server = socketserver.TCPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        opener = urllib.request.build_opener(DeadlineHTTPHandler())
        try:
            for method, expected in [("HEAD", b""), ("GET", b"abcde")]:
                request = set_deadline(
                    urllib.request.Request(
                        "http://127.0.0.1:" + str(server.server_address[1]),
                        method=method,
                    ),
                    time.monotonic() + 2,
                    lambda: None,
                )
                with opener.open(request, timeout=15) as response:
                    self.assertEqual(response.read(), expected)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_https_retains_verified_context(self):
        request = set_deadline(
            urllib.request.Request("https://example.test"),
            time.monotonic() + 2,
            lambda: None,
        )
        connection = connection_factory(http.client.HTTPSConnection, request)(
            "example.test", timeout=15
        )
        self.assertEqual(connection._context.verify_mode, ssl.CERT_REQUIRED)
        self.assertTrue(connection._context.check_hostname)
        connection.close()

    def test_cdn_redirect_carries_original_budget_and_cancel(self):
        check = lambda: None
        request = set_deadline(
            urllib.request.Request("https://wxapp.tc.qq.com/a"), 123, check
        )
        result = CdnRedirectHandler().redirect_request(
            request, None, 302, "Found", {}, "https://wxapp.tc.qq.com/b"
        )
        self.assertEqual(result.response_deadline, 123)
        self.assertIs(result.response_check, check)


if __name__ == "__main__":
    unittest.main()
