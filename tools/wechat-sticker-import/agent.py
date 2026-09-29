"""Foreground, outbound-only WeChat import agent. No cloud-controlled local paths."""

from __future__ import annotations

import argparse
import ctypes
from ctypes import wintypes
import getpass
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

from deadline_http import DeadlineHTTPHandler, DeadlineHTTPSHandler, set_deadline

from collector import (
    collect,
    CollectorError,
    CollectionCancelled,
    ERROR_CODES,
    MAX_IMAGE,
)

PREFIX = "/api/v1/sticker-import-agent"
TOKEN_RE = re.compile(r"sfi_[a-f0-9]{64}\Z")
SHA_RE = re.compile(r"[a-f0-9]{64}\Z")
JOB_KEYS = {
    "id",
    "agentId",
    "kind",
    "status",
    "createdAt",
    "expiresAt",
    "leaseUntil",
    "discovered",
    "validated",
    "validationFailed",
    "counts",
    "sourceErrors",
    "errors",
    "jobErrorCode",
    "leaseToken",
}
MESSAGES = {
    "network": "网络暂不可用，请稍后重试。",
    "unauthorized": "助手授权已失效，请重新配对。",
    "conflict": "任务已取消或租约失效，停止本次处理。",
    "invalid_config": "本机配置无效，请检查安装参数。",
    "invalid_response": "服务器返回无法识别的数据，已停止处理。",
    "http_error": "服务器拒绝请求，已停止本次处理。",
    "rate_limited": "服务器繁忙，请稍后重试。",
    "storage": "无法读取或保存当前用户的安全配置。",
    "unsupported": "此助手需要 Windows Python 3.12。",
}


class AgentError(Exception):
    def __init__(self, code="invalid_response"):
        self.code = code if code in MESSAGES else "invalid_response"
        super().__init__(MESSAGES[self.code])


class HttpError(AgentError):
    def __init__(self, status):
        self.status = status
        super().__init__(
            "unauthorized"
            if status == 401
            else "conflict" if status == 409 else "http_error"
        )


def validate_origin(value, allow_local_http=False):
    try:
        if (
            not isinstance(value, str)
            or any(ord(c) <= 32 for c in value)
            or "\\" in value
        ):
            raise ValueError()
        u = urllib.parse.urlsplit(value)
        if (
            not u.hostname
            or u.username is not None
            or u.password is not None
            or u.path not in ("", "/")
            or u.query
            or u.fragment
            or "?" in value
            or "#" in value
        ):
            raise ValueError()
        if u.port is not None and not 1 <= u.port <= 65535:
            raise ValueError()
        if u.scheme != "https":
            if (
                u.scheme != "http"
                or not allow_local_http
                or not ipaddress.ip_address(u.hostname).is_loopback
            ):
                raise ValueError()
        return value.rstrip("/")
    except (ValueError, TypeError):
        raise AgentError("invalid_config") from None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Transport:
    """Three requests/second aggregate; retries wait independently of renew thread."""

    def __init__(
        self, origin, token=None, allow_local_http=False, stop=None, response_timeout=20
    ):
        self.origin = validate_origin(origin, allow_local_http)
        if token is not None and not TOKEN_RE.fullmatch(token):
            raise AgentError("storage")
        self.token = token
        self.response_timeout = response_timeout
        self.stop = stop or threading.Event()
        self.lock = threading.Lock()
        self.next_request = 0.0
        self.retry_after_until = 0.0
        self.opener = urllib.request.build_opener(
            NoRedirect(), DeadlineHTTPHandler(), DeadlineHTTPSHandler()
        )

    def wait(self, seconds, cancel=None):
        deadline = time.monotonic() + seconds
        while True:
            if self.stop.is_set() or (cancel and cancel()):
                raise CollectionCancelled()
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return
            self.stop.wait(min(0.1, remaining))

    def request(
        self, path, body=None, *, lease=None, raw=None, cancel=None, retry=True
    ):
        if not re.fullmatch(
            r"/(pair|heartbeat|claim|jobs/[a-f0-9-]{36}/(renew|match|report|complete|materials)(\?filename=[^&]+&sha256=[a-f0-9]{64})?)",
            path,
        ):
            raise AgentError("invalid_response")
        headers = {
            "Content-Type": (
                "application/octet-stream" if raw is not None else "application/json"
            )
        }
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        if lease:
            if not SHA_RE.fullmatch(lease):
                raise AgentError("invalid_response")
            headers["X-Import-Lease"] = lease
        data = raw if raw is not None else json.dumps(body or {}).encode()
        for attempt in range(3 if retry else 1):
            if cancel and cancel():
                raise CollectionCancelled()
            with self.lock:
                if not retry and self.retry_after_until > time.monotonic():
                    raise AgentError("rate_limited")
                delay = max(
                    0,
                    self.next_request - time.monotonic(),
                    self.retry_after_until - time.monotonic(),
                )
                self.next_request = max(time.monotonic(), self.next_request) + 0.35
            self.wait(delay, cancel)
            if cancel and cancel():
                raise CollectionCancelled()
            req = urllib.request.Request(
                self.origin + PREFIX + path, data=data, headers=headers, method="POST"
            )
            deadline = time.monotonic() + self.response_timeout

            def check_response():
                if self.stop.is_set() or (cancel and cancel()):
                    raise CollectionCancelled()

            set_deadline(req, deadline, check_response)
            try:
                with self.opener.open(req, timeout=15) as response:
                    chunks, size = [], 0
                    while True:
                        if self.stop.is_set() or (cancel and cancel()):
                            raise CollectionCancelled()
                        if time.monotonic() >= deadline:
                            raise AgentError("network")
                        chunk = response.read1(65536)
                        if time.monotonic() >= deadline:
                            raise AgentError("network")
                        if self.stop.is_set() or (cancel and cancel()):
                            raise CollectionCancelled()
                        if not chunk:
                            break
                        size += len(chunk)
                        if size > 2 * 1024 * 1024:
                            raise AgentError("invalid_response")
                        chunks.append(chunk)
                    payload = b"".join(chunks)
                    result = json.loads(payload)
                    if not isinstance(result, dict):
                        raise AgentError("invalid_response")
                    return result
            except urllib.error.HTTPError as error:
                status = error.code
                retry_after = error.headers.get("Retry-After", "60")
                error.close()
                if status == 429:
                    delay = (
                        min(300, max(1, int(retry_after)))
                        if retry_after.isdigit()
                        else 60
                    )
                    with self.lock:
                        self.retry_after_until = max(
                            self.retry_after_until, time.monotonic() + delay
                        )
                    if attempt == 2 or not retry:
                        raise AgentError("rate_limited") from None
                elif status >= 500:
                    if attempt == 2 or not retry:
                        raise AgentError("network") from None
                    delay = 2**attempt
                else:
                    raise HttpError(status) from None
            except (urllib.error.URLError, TimeoutError, OSError):
                if attempt == 2 or not retry:
                    raise AgentError("network") from None
                delay = 2**attempt
            except (ValueError, UnicodeError):
                raise AgentError("invalid_response") from None
            self.wait(delay, cancel)
        raise AgentError("network")


class DATA_BLOB(ctypes.Structure):
    _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_ubyte))]


def _dpapi(data, decrypt):
    if os.name != "nt":
        raise AgentError("unsupported")
    buf = ctypes.create_string_buffer(data)
    source = DATA_BLOB(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_ubyte)))
    target = DATA_BLOB()
    crypt = ctypes.WinDLL("crypt32", use_last_error=True)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.LocalFree.argtypes = [ctypes.c_void_p]
    kernel.LocalFree.restype = ctypes.c_void_p
    fn = crypt.CryptUnprotectData if decrypt else crypt.CryptProtectData
    fn.argtypes = [
        ctypes.POINTER(DATA_BLOB),
        ctypes.c_void_p,
        ctypes.c_void_p,
        ctypes.c_void_p,
        ctypes.c_void_p,
        wintypes.DWORD,
        ctypes.POINTER(DATA_BLOB),
    ]
    fn.restype = wintypes.BOOL
    if not fn(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target)):
        raise AgentError("storage")
    try:
        return ctypes.string_at(target.pbData, target.cbData)
    finally:
        kernel.LocalFree(target.pbData)


def protect(data):
    return _dpapi(data, False)


def unprotect(data):
    return _dpapi(data, True)


def private_root():
    if os.name != "nt" or not os.environ.get("LOCALAPPDATA"):
        raise AgentError("unsupported")
    root = Path(os.environ["LOCALAPPDATA"]) / "shurufa-wechat-import"
    if not root.is_dir() or root.is_symlink():
        raise AgentError("storage")
    return root


def load_config(root):
    try:
        config = json.loads((root / "config.json").read_text(encoding="utf-8-sig"))
        if set(config) != {"origin", "account_dir", "weixin_exe", "allow_local_http"}:
            raise ValueError()
        validate_origin(config["origin"], config["allow_local_http"] is True)
        if not isinstance(config["allow_local_http"], bool):
            raise ValueError()
        if (
            not Path(config["account_dir"]).is_dir()
            or not Path(config["weixin_exe"]).is_file()
        ):
            raise ValueError()
        config["cache_dir"] = str(root / "cache")
        return config
    except (OSError, ValueError, TypeError, KeyError):
        raise AgentError("storage") from None


def save_token(root, token):
    if not isinstance(token, str) or not TOKEN_RE.fullmatch(token):
        raise AgentError("invalid_response")
    try:
        blob = protect(token.encode())
        temporary = root / "token.new"
        with temporary.open("wb") as output:
            output.write(blob)
        temporary.replace(root / "token.dpapi")
    except OSError:
        raise AgentError("storage") from None


def load_token(root):
    try:
        value = unprotect((root / "token.dpapi").read_bytes()).decode("ascii")
        if not TOKEN_RE.fullmatch(value):
            raise ValueError()
        return value
    except (OSError, ValueError, UnicodeError):
        raise AgentError("storage") from None


def validated_matches(response, hashes):
    entries = response.get("items") if isinstance(response, dict) else None
    if not isinstance(entries, list) or len(entries) != len(hashes):
        raise AgentError("invalid_response")
    seen = set()
    expected = set(hashes)
    for entry in entries:
        if not isinstance(entry, dict):
            raise AgentError("invalid_response")
        sha = entry.get("sha256")
        status = entry.get("status")
        if (
            not isinstance(sha, str)
            or sha not in expected
            or sha in seen
            or status not in ("existing", "missing", "unavailable")
        ):
            raise AgentError("invalid_response")
        seen.add(sha)
    if seen != expected:
        raise AgentError("invalid_response")
    return entries


class Runner:
    def __init__(
        self,
        transport,
        config,
        collector=collect,
        *,
        renew_interval=20,
        log=print,
        stop=None,
    ):
        self.transport, self.config, self.collector = transport, config, collector
        self.renew_interval, self.log = renew_interval, log
        self.stop = stop or threading.Event()

    def process(self, job):
        if (
            not isinstance(job, dict)
            or set(job) - JOB_KEYS
            or job.get("kind") != "wechat_favorites"
        ):
            raise AgentError("invalid_response")
        if not re.fullmatch(
            r"[a-f0-9-]{36}", str(job.get("id", ""))
        ) or not SHA_RE.fullmatch(str(job.get("leaseToken", ""))):
            raise AgentError("invalid_response")
        prefix = "/jobs/" + job["id"]
        cancelled, done = threading.Event(), threading.Event()
        background_error = []

        def is_cancelled():
            return done.is_set() or cancelled.is_set() or self.stop.is_set()

        def request(endpoint, body=None, **kwargs):
            if is_cancelled():
                raise CollectionCancelled()
            return self.transport.request(
                prefix + endpoint,
                body,
                lease=job["leaseToken"],
                cancel=is_cancelled,
                **kwargs,
            )

        def renew():
            while not done.wait(self.renew_interval):
                try:
                    request("/renew", retry=False)
                    self.transport.request(
                        "/heartbeat", cancel=is_cancelled, retry=False
                    )
                except (AgentError, CollectionCancelled) as error:
                    background_error.append(error)
                    cancelled.set()
                    return

        thread = threading.Thread(target=renew, daemon=True)
        thread.start()
        try:
            try:
                result = self.collector(
                    self.config["account_dir"],
                    self.config["cache_dir"],
                    self.config["weixin_exe"],
                    cancel=is_cancelled,
                    progress=lambda p: None,
                )
            except CollectorError as error:
                request("/complete", {"status": "failed", "jobErrorCode": error.code})
                self.log("微信采集失败：" + error.code)
                return
            if is_cancelled():
                raise CollectionCancelled()
            errors = {entry["code"]: entry["count"] for entry in result["sourceErrors"]}
            if set(errors) - ERROR_CODES or any(
                type(n) is not int or n < 0 for n in errors.values()
            ):
                raise AgentError("invalid_response")
            items = result["items"]
            report = {
                "discovered": result["total"],
                "validated": len(items),
                "validationFailed": sum(errors.values()),
                "sourceErrors": errors,
                "failures": [],
            }
            if any(
                report[k] < job.get(k, 0)
                for k in ("discovered", "validated", "validationFailed")
            ) or any(
                errors.get(k, 0) < v for k, v in job.get("sourceErrors", {}).items()
            ):
                request(
                    "/complete",
                    {"status": "failed", "jobErrorCode": "collector_failed"},
                )
                self.log("收藏快照变化，已停止当前任务，请重新开始导入。")
                return
            by_sha = {}
            for item in items:
                if not SHA_RE.fullmatch(item["sha256"]):
                    raise AgentError("invalid_response")
                by_sha[item["sha256"]] = item
            request("/report", report)
            missing = []
            hashes = list(by_sha)
            for start in range(0, len(hashes), 500):
                matched = request("/match", {"sha256s": hashes[start : start + 500]})
                entries = validated_matches(matched, hashes[start : start + 500])
                for entry in entries:
                    if entry["status"] == "missing":
                        missing.append(entry["sha256"])
                    elif entry["status"] == "unavailable":
                        self.log("已有图片原文件不可用，已记录失败。")
                    elif entry["status"] != "existing":
                        raise AgentError("invalid_response")
            for sha in missing:
                item = by_sha[sha]
                failure = None
                try:
                    path = Path(item["path"]).resolve(strict=True)
                    path.relative_to(
                        Path(self.config["cache_dir"]).resolve(strict=True)
                    )
                    if not path.is_file() or path.stat().st_size > MAX_IMAGE:
                        raise ValueError()
                    with path.open("rb") as source:
                        content = source.read(MAX_IMAGE + 1)
                    if len(content) > MAX_IMAGE:
                        raise ValueError()
                    if hashlib.sha256(content).hexdigest() != sha:
                        failure = "hash_mismatch"
                    else:
                        query = urllib.parse.urlencode(
                            {"filename": path.name, "sha256": sha}
                        )
                        try:
                            response = request("/materials?" + query, raw=content)
                            if response.get("archiveWarning"):
                                self.log("图片已入库，但服务器归档失败，请检查后台。")
                        except HttpError as error:
                            if error.status in (401, 409):
                                raise
                            failure = "upload_failed"
                        except AgentError:
                            # Response loss is reconciled by hash, never by assumed success.
                            matched = request("/match", {"sha256s": [sha]})
                            status = validated_matches(matched, [sha])[0]["status"]
                            if status == "missing":
                                failure = "upload_failed"
                            elif status == "unavailable":
                                self.log("已有图片原文件不可用，服务器已记录失败。")
                except (OSError, ValueError):
                    failure = "invalid_image"
                if failure:
                    report["failures"] = [{"sha256": sha, "code": failure}]
                    request("/report", report)
            request("/complete", {"status": "completed"})
            self.log("导入任务已处理完成，请在后台查看成功、已有及失败数量。")
        except CollectionCancelled:
            if background_error:
                raise background_error[0]
            raise
        finally:
            done.set()
            thread.join(timeout=16)

    def run(self):
        last_heartbeat = 0.0
        while not self.stop.is_set():
            try:
                if time.monotonic() - last_heartbeat >= 30:
                    self.transport.request("/heartbeat")
                    last_heartbeat = time.monotonic()
                job = self.transport.request("/claim").get("job")
                if job is not None:
                    self.process(job)
            except HttpError as error:
                self.log(str(error))
                if error.status != 409:
                    return
            except AgentError as error:
                self.log(str(error))
            except CollectionCancelled:
                if self.stop.is_set():
                    return
            if self.stop.wait(10):
                return


def main():
    parser = argparse.ArgumentParser(description="微信收藏表情导入助手（主动出站连接）")
    parser.add_argument("command", choices=["pair", "run"])
    args = parser.parse_args()
    stop = threading.Event()
    try:
        root = private_root()
        config = load_config(root)
        transport = Transport(
            config["origin"], allow_local_http=config["allow_local_http"], stop=stop
        )
        if args.command == "pair":
            code = getpass.getpass("请输入后台一次性配对码（不显示）：").strip()
            name = input("助手名称（例如：我的电脑）：").strip()
            result = transport.request("/pair", {"code": code, "name": name})
            save_token(root, result.get("token"))
            print("配对成功。请启动助手，再到后台点击导入。")
        else:
            transport.token = load_token(root)
            print("助手已启动；按 Ctrl+C 停止。不自动开机启动。")
            Runner(transport, config, stop=stop).run()
    except KeyboardInterrupt:
        stop.set()
        print("助手已停止；原图缓存保留。")
    except (AgentError, CollectionCancelled) as error:
        print(str(error) if isinstance(error, AgentError) else "操作已停止。")
        return 1
    except Exception:
        print("助手遇到异常，已停止；请检查本机配置及后台任务状态。")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
