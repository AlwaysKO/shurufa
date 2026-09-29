"""Bound response headers/body without background network workers.

HTTP parsing and TLS verification remain in the standard library. OS DNS lookup
is not a hard-deadline operation; connect/TLS retain the caller's socket timeout.
Cancellation during an idle read can take up to that timeout (15 seconds here).
"""

import http.client
import time
import urllib.request


class DeadlineReader:
    def __init__(self, stream, sock, deadline, check):
        self.stream = stream
        self.sock = sock
        self.deadline = deadline
        self.check = check

    def before_read(self):
        self.check()
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("response_deadline")
        self.sock.settimeout(min(15, remaining))

    def after_read(self):
        self.check()
        if time.monotonic() >= self.deadline:
            raise TimeoutError("response_deadline")

    def read1(self, size=-1):
        self.before_read()
        result = self.stream.read1(size)
        self.after_read()
        return result

    def readline(self, limit=-1):
        # BufferedReader.readline can wait forever on a continuously dripping
        # header. Read individual header bytes, preserving the stdlib line limit.
        line = bytearray()
        while limit < 0 or len(line) < limit:
            value = self.read1(1)
            if not value:
                break
            line.extend(value)
            if value == b"\n":
                break
        return bytes(line)

    def read(self, size=-1):
        chunks = []
        length = 0
        while size < 0 or length < size:
            chunk = self.read1(65536 if size < 0 else min(65536, size - length))
            if not chunk:
                break
            chunks.append(chunk)
            length += len(chunk)
        return b"".join(chunks)

    def readinto(self, buffer):
        data = self.read(len(buffer))
        buffer[: len(data)] = data
        return len(data)

    def close(self):
        self.stream.close()

    def __getattr__(self, name):
        return getattr(self.stream, name)


def set_deadline(request, deadline, check):
    request.response_deadline = deadline
    request.response_check = check
    return request


def copy_deadline(source, target):
    if target is not None and hasattr(source, "response_deadline"):
        set_deadline(target, source.response_deadline, source.response_check)
    return target


def connection_factory(connection_type, request):
    def make_connection(host, **kwargs):
        connection = connection_type(host, **kwargs)

        def response(sock, *args, **options):
            result = http.client.HTTPResponse(sock, *args, **options)
            result.fp = DeadlineReader(
                result.fp, sock, request.response_deadline, request.response_check
            )
            return result

        connection.response_class = response
        return connection

    return make_connection


class DeadlineHTTPHandler(urllib.request.HTTPHandler):
    def http_open(self, req):
        return self.do_open(connection_factory(http.client.HTTPConnection, req), req)


class DeadlineHTTPSHandler(urllib.request.HTTPSHandler):
    def https_open(self, req):
        return self.do_open(
            connection_factory(http.client.HTTPSConnection, req),
            req,
            context=self._context,
        )
