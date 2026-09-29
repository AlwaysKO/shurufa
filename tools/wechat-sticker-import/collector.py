"""Read-only Windows WeChat favorite originals collector.

No database, URL, process dump, or key is written to disk. Public errors are fixed
codes, never exception text. The caller owns pairing/transport, not this module.
"""
from __future__ import annotations

import ctypes
import hashlib
import hmac
import io
import os
from pathlib import Path
import re
import sqlite3
import struct
import time
import urllib.request
import warnings

from deadline_http import (DeadlineHTTPHandler, DeadlineHTTPSHandler,
                           set_deadline, copy_deadline)

from Crypto.Cipher import AES
from PIL import Image

MASK = bytes.fromhex('d2c7442458020000004889442450488b450048844c2448488944254048584c24')
PAGE_SIZE = 4096
MAX_DATABASE = 128 * 1024 * 1024
MAX_IMAGE = 10 * 1024 * 1024
MAX_CACHE = 512 * 1024 * 1024
MAX_PIXELS = 100_000_000
MAX_FRAME_PIXELS = 200_000_000
MAX_FRAMES = 1000
MAX_FAVORITES = 10000
CDN_HOSTS = frozenset(('wxapp.tc.qq.com', 'vweixinf.tc.qq.com'))
ERROR_CODES = frozenset(('download_failed', 'invalid_image', 'key_not_found',
                        'snapshot_unstable', 'wechat_not_running', 'collector_failed'))


class CollectorError(Exception):
    def __init__(self, code):
        self.code = code if code in ERROR_CODES else 'collector_failed'
        super().__init__(self.code)


class CollectionCancelled(Exception):
    """Local cancellation; the caller must not report this as a source error."""


def check_cancel(cancel):
    if cancel and cancel():
        raise CollectionCancelled()


def verify_page(page, key, salt, number):
    if len(page) != PAGE_SIZE or len(key) != 32 or len(salt) != 16:
        return False
    mac_key = hashlib.pbkdf2_hmac('sha512', key, bytes(x ^ 0x3a for x in salt), 2, 32)
    start = 16 if number == 1 else 0
    expected = hmac.new(mac_key, page[start:4032] + struct.pack('<I', number), 'sha512').digest()
    return hmac.compare_digest(expected, page[4032:])


def decrypt_page(page, key, salt, number):
    if not verify_page(page, key, salt, number):
        raise CollectorError('snapshot_unstable')
    start = 16 if number == 1 else 0
    plain = AES.new(key, AES.MODE_CBC, page[4016:4032]).decrypt(page[start:4016])
    return (b'SQLite format 3\0' if number == 1 else b'') + plain + bytes(80)


def matching_keys(data, first_page):
    salt = first_page[:16]
    for salt_text in (salt.hex().encode(), salt.hex().upper().encode()):
        needle = bytes(value ^ MASK[(i + 66) % 32] for i, value in enumerate(salt_text))
        position = 0
        while True:
            position = data.find(needle, position)
            if position < 0:
                break
            start = position - 66
            position += 1
            if start < 0 or start + 99 > len(data):
                continue
            clear = bytes(value ^ MASK[i % 32] for i, value in enumerate(data[start:start + 99]))
            if not re.fullmatch(rb"[xX]'[0-9a-fA-F]{96}'", clear):
                continue
            key = bytes.fromhex(clear[2:66].decode('ascii'))
            if verify_page(first_page, key, salt, 1):
                yield key


def find_key_in_chunks(chunks, first_page, cancel=None):
    tail = b''
    previous_end = None
    for address, data in chunks:
        check_cancel(cancel)
        if address != previous_end:
            tail = b''
        for key in matching_keys(tail + data, first_page):
            return key
        tail = (tail + data)[-98:]
        previous_end = address + len(data)
    return None


def wal_checksum(data, state=(0, 0), endian='<'):
    first, second = state
    for left, right in struct.iter_unpack(endian + 'II', data):
        first = (first + left + second) & 0xffffffff
        second = (second + right + first) & 0xffffffff
    return first, second


def committed_wal_pages(wal):
    """Apply SQLite generation/checksum rules; uncertain corruption fails closed.

    Salt-mismatched old generations and partial tails cannot commit. Same-salt
    full frames with invalid checksums are not silently treated as old data.
    """
    if not wal:
        return None, {}
    if len(wal) < 32:
        raise CollectorError('snapshot_unstable')
    magic, version, size, _, _, _, one, two = struct.unpack('>8I', wal[:32])
    if magic not in (0x377f0682, 0x377f0683) or version != 3007000 or size != PAGE_SIZE:
        raise CollectorError('snapshot_unstable')
    endian = '<' if magic == 0x377f0682 else '>'
    state = wal_checksum(wal[:24], endian=endian)
    if state != (one, two):
        raise CollectorError('snapshot_unstable')
    pending, committed, database_size = {}, {}, None
    for offset in range(32, len(wal), 24 + PAGE_SIZE):
        frame = wal[offset:offset + 24 + PAGE_SIZE]
        if len(frame) < 24 + PAGE_SIZE:
            break
        if frame[8:16] != wal[16:24]:
            break
        number, commit_size = struct.unpack('>II', frame[:8])
        if not number or number > MAX_DATABASE // PAGE_SIZE or commit_size > MAX_DATABASE // PAGE_SIZE:
            raise CollectorError('snapshot_unstable')
        state = wal_checksum(frame[:8] + frame[24:], state, endian)
        if state != struct.unpack('>II', frame[16:24]):
            raise CollectorError('snapshot_unstable')
        pending[number] = frame[24:]
        if commit_size:
            committed.update(pending)
            pending.clear()
            database_size = commit_size
            committed = {n: p for n, p in committed.items() if n <= commit_size}
    return database_size, committed


def decrypt_database(database, wal, key):
    if not database or len(database) % PAGE_SIZE or len(database) > MAX_DATABASE:
        raise CollectorError('snapshot_unstable')
    count, replacements = committed_wal_pages(wal)
    count = count if count is not None else len(database) // PAGE_SIZE
    salt = database[:16]
    pages = []
    for number in range(1, count + 1):
        encrypted = replacements.get(number, database[(number - 1) * PAGE_SIZE:number * PAGE_SIZE])
        pages.append(decrypt_page(encrypted, key, salt, number))
    plain = bytearray(b''.join(pages))
    # The WAL is already folded in; prevent SQLite trying to open sibling WAL.
    plain[18:20] = b'\x01\x01'
    plain[28:32] = struct.pack('>I', count)
    plain[92:96] = plain[24:28]
    return bytes(plain)


def _bounded_read(path, optional=False):
    try:
        with path.open('rb') as stream:
            data = stream.read(MAX_DATABASE + 1)
    except FileNotFoundError:
        if optional:
            return b''
        raise CollectorError('snapshot_unstable') from None
    if len(data) > MAX_DATABASE:
        raise CollectorError('snapshot_unstable')
    return data


def _read_pair(path):
    return _bounded_read(path), _bounded_read(Path(str(path) + '-wal'), optional=True)


def _metadata(path):
    result = []
    for item in (path, Path(str(path) + '-wal')):
        try:
            stat = item.stat()
            result.append((stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns))
        except FileNotFoundError:
            result.append(None)
    return result


def stable_snapshot(path, cancel=None):
    for _ in range(3):
        check_cancel(cancel)
        try:
            before = _metadata(path)
            first = _read_pair(path)
            second = _read_pair(path)
            if first == second and before == _metadata(path):
                if not first[0] or len(first[0]) % PAGE_SIZE:
                    raise CollectorError('snapshot_unstable')
                return first
        except OSError:
            pass
        time.sleep(0.05)
    raise CollectorError('snapshot_unstable')


def favorite_rows(plain):
    # Python 3.12 Windows includes deserialize. No disk-based fallback: even an
    # abnormal termination must never leave a decrypted favorite database.
    if not hasattr(sqlite3.Connection, 'deserialize'):
        raise CollectorError('collector_failed')
    connection = sqlite3.connect(':memory:')
    try:
        connection.deserialize(plain)
        connection.execute('PRAGMA temp_store=MEMORY')
        connection.execute('PRAGMA query_only=ON')
        connection.execute('PRAGMA trusted_schema=OFF')
        rows = connection.execute(
            "SELECT f.rowid,f.md5,coalesce(n.cdn_url,'') "
            'FROM kFavEmoticonOrderTable f LEFT JOIN kNonStoreEmoticonTable n '
            'ON n.md5=f.md5 ORDER BY f.rowid LIMIT ?', (MAX_FAVORITES + 1,)).fetchall()
        if len(rows) > MAX_FAVORITES:
            raise CollectorError('collector_failed')
        return rows
    except sqlite3.Error:
        raise CollectorError('snapshot_unstable') from None
    finally:
        connection.close()


def validate_cdn_url(url):
    from urllib.parse import urlsplit
    try:
        parts = urlsplit(url)
        if (parts.scheme != 'https' or parts.hostname not in CDN_HOSTS or
                parts.username is not None or parts.password is not None or
                parts.port not in (None, 443) or parts.fragment or '\\' in url or
                any(ord(char) < 33 for char in url)):
            raise ValueError()
    except (ValueError, TypeError):
        raise CollectorError('download_failed') from None
    return url


class CdnRedirectHandler(urllib.request.HTTPRedirectHandler):
    max_redirections = 3
    max_repeats = 1

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        validate_cdn_url(newurl)
        return copy_deadline(req, super().redirect_request(req, fp, code, msg, headers, newurl))


def source_cdn_url(url):
    # Historical table rows use HTTP. Upgrade before validation/network, never
    # follow an HTTP redirect. Explicit :80 remains invalid rather than guessed.
    if isinstance(url, str) and url.startswith('http://'):
        url = 'https://' + url[7:]
    validate_cdn_url(url)
    from urllib.parse import urlsplit, urlunsplit
    parts = urlsplit(url)
    # Narrow compatibility observed against original MD5s; not an assertion
    # of an official universal alias. Never rewrite redirect destinations.
    if parts.hostname == 'vweixinf.tc.qq.com':
        url = urlunsplit(parts._replace(netloc='wxapp.tc.qq.com'))
    return validate_cdn_url(url)


def download_original(url, cancel=None):
    url = source_cdn_url(url)
    # Do not inherit arbitrary proxy settings or send cookies/credentials.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), CdnRedirectHandler(),
                                         DeadlineHTTPHandler(), DeadlineHTTPSHandler())
    for attempt in range(2):
        check_cancel(cancel)
        try:
            deadline = time.monotonic() + 45
            request = set_deadline(urllib.request.Request(url), deadline, lambda: check_cancel(cancel))
            with opener.open(request, timeout=15) as response:
                validate_cdn_url(response.url)
                length = response.headers.get('Content-Length')
                if length is not None and (not length.isdigit() or int(length) > MAX_IMAGE):
                    raise CollectorError('download_failed')
                result = bytearray()
                while True:
                    check_cancel(cancel)
                    if time.monotonic() > deadline:
                        raise CollectorError('download_failed')
                    # One underlying read, not a read-until-full loop: a slow
                    # trickle must not evade deadline/cancellation checks. The
                    # deadline can overshoot by the current socket timeout
                    # (at most 15 seconds), not a millisecond-hard cutoff.
                    chunk = response.read1(min(65536, MAX_IMAGE + 1 - len(result)))
                    check_cancel(cancel)
                    if time.monotonic() > deadline:
                        raise CollectorError('download_failed')
                    if not chunk:
                        return bytes(result)
                    result.extend(chunk)
                    if len(result) > MAX_IMAGE:
                        raise CollectorError('download_failed')
        except CollectionCancelled:
            raise
        except Exception:
            if attempt == 1:
                raise CollectorError('download_failed') from None
    raise CollectorError('download_failed')


def validate_gif_blocks(data):
    # Pillow may accept a missing trailer. Walk container boundaries, not LZW;
    # actual pixel/frame decoding remains Pillow's job. Preserve legal padding.
    if len(data) < 13 or data[:6] not in (b'GIF87a', b'GIF89a'):
        raise ValueError()
    offset = 13
    if data[10] & 0x80:
        offset += 3 * (2 ** ((data[10] & 7) + 1))
    while offset < len(data):
        marker = data[offset]
        offset += 1
        if marker == 0x3b:
            return
        if marker == 0x21:
            offset += 1  # extension label, followed by data sub-blocks
        elif marker == 0x2c:
            if offset + 9 > len(data):
                raise ValueError()
            packed = data[offset + 8]
            offset += 9
            if packed & 0x80:
                offset += 3 * (2 ** ((packed & 7) + 1))
            offset += 1  # LZW minimum code size
        else:
            raise ValueError()
        while True:
            if offset >= len(data):
                raise ValueError()
            length = data[offset]
            offset += 1
            if not length:
                break
            offset += length
            if offset > len(data):
                raise ValueError()
    raise ValueError()


def validate_image(data, source_md5):
    if (not data or len(data) > MAX_IMAGE or not isinstance(source_md5, str) or
            not re.fullmatch('[0-9a-fA-F]{32}', source_md5) or
            hashlib.md5(data).hexdigest() != source_md5.lower()):
        raise CollectorError('invalid_image')
    try:
        with warnings.catch_warnings():
            warnings.simplefilter('error', Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(data)) as image:
                fmt = image.format.lower()
                if fmt == 'gif':
                    validate_gif_blocks(data)
                if fmt not in ('gif', 'png', 'jpeg', 'webp'):
                    raise ValueError()
                width, height = image.size
                if width * height > MAX_PIXELS:
                    raise ValueError()
                frames = getattr(image, "n_frames", 1)
                if frames > MAX_FRAMES or frames * width * height > MAX_FRAME_PIXELS:
                    raise ValueError()
                cumulative_pixels = 0
                for index in range(frames):
                    image.seek(index)
                    frame_width, frame_height = image.size
                    frame_pixels = frame_width * frame_height
                    cumulative_pixels += frame_pixels
                    # A malformed GIF can enlarge its logical canvas on seek.
                    # Reject it before decoding, rather than repair the image.
                    if (image.size != (width, height) or frame_pixels > MAX_PIXELS or
                            cumulative_pixels > MAX_FRAME_PIXELS):
                        raise ValueError()
                    image.load()
        return {'sha256': hashlib.sha256(data).hexdigest(), 'sourceMd5': source_md5.lower(),
                'format': fmt, 'width': width, 'height': height, 'frames': frames, 'bytes': len(data)}
    except Exception:
        raise CollectorError('invalid_image') from None


def cache_original(directory, data, metadata):
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    if directory.is_symlink():
        raise CollectorError('collector_failed')
    sha = metadata['sha256']
    if not re.fullmatch('[0-9a-f]{64}', sha) or hashlib.sha256(data).hexdigest() != sha:
        raise CollectorError('invalid_image')
    path = directory / (sha + '.' + metadata['format'])
    if path.is_symlink():
        raise CollectorError('collector_failed')
    if path.exists():
        if path.stat().st_size <= MAX_IMAGE and path.read_bytes() == data:
            return str(path)
        raise CollectorError('collector_failed')
    size = sum(item.stat().st_size for item in directory.iterdir() if item.is_file())
    if size + len(data) > MAX_CACHE:
        raise CollectorError('collector_failed')
    owned = False
    try:
        with path.open('xb') as stream:
            owned = True
            stream.write(data)
    except Exception:
        if owned:
            path.unlink(missing_ok=True)
        raise CollectorError('collector_failed') from None
    return str(path)


def find_wechat_key(first_page, weixin_exe, cancel=None):
    """Only VM_READ + QUERY_INFORMATION; verify executable on the opened handle."""
    if os.name != 'nt':
        raise CollectorError('wechat_not_running')
    import psutil
    from ctypes import wintypes

    class MemoryInfo(ctypes.Structure):
        _fields_ = [('BaseAddress', ctypes.c_void_p), ('AllocationBase', ctypes.c_void_p),
                    ('AllocationProtect', wintypes.DWORD), ('RegionSize', ctypes.c_size_t),
                    ('State', wintypes.DWORD), ('Protect', wintypes.DWORD), ('Type', wintypes.DWORD)]

    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel.QueryFullProcessImageNameW.argtypes = [wintypes.HANDLE, wintypes.DWORD,
                                                wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)]
    kernel.VirtualQueryEx.argtypes = [wintypes.HANDLE, ctypes.c_void_p,
                                     ctypes.POINTER(MemoryInfo), ctypes.c_size_t]
    kernel.VirtualQueryEx.restype = ctypes.c_size_t
    kernel.ReadProcessMemory.argtypes = [wintypes.HANDLE, ctypes.c_void_p, ctypes.c_void_p,
                                        ctypes.c_size_t, ctypes.POINTER(ctypes.c_size_t)]
    expected = os.path.normcase(os.path.realpath(weixin_exe))
    if Path(expected).name.lower() != 'weixin.exe' or not Path(expected).is_file():
        raise CollectorError('wechat_not_running')
    candidates = []
    for process in psutil.process_iter(['pid', 'name', 'exe', 'memory_info']):
        try:
            if (process.info['name'] or '').lower() == 'weixin.exe' and os.path.normcase(
                    os.path.realpath(process.info['exe'] or '')) == expected:
                candidates.append((getattr(process.info['memory_info'], 'rss', 0), process.info['pid']))
        except (psutil.Error, OSError):
            continue
    if not candidates:
        raise CollectorError('wechat_not_running')
    deadline = time.monotonic() + 180

    def chunks(handle):
        address = 0
        info = MemoryInfo()
        while kernel.VirtualQueryEx(handle, address, ctypes.byref(info), ctypes.sizeof(info)):
            check_cancel(cancel)
            if time.monotonic() >= deadline:
                return
            base, size = int(info.BaseAddress or 0), int(info.RegionSize)
            # Committed, non-guarded readable pages (including adjacent regions).
            if info.State == 0x1000 and not info.Protect & 0x100 and info.Protect & 0xff in (2, 4, 8, 0x20, 0x40, 0x80):
                for offset in range(0, size, 1024 * 1024):
                    check_cancel(cancel)
                    if time.monotonic() >= deadline:
                        return
                    amount = min(1024 * 1024, size - offset)
                    buffer = ctypes.create_string_buffer(amount)
                    received = ctypes.c_size_t()
                    kernel.ReadProcessMemory(handle, base + offset, buffer, amount, ctypes.byref(received))
                    if received.value:
                        yield base + offset, buffer.raw[:received.value]
            if base + size <= address:
                return
            address = base + size

    for _, pid in sorted(candidates, reverse=True):
        check_cancel(cancel)
        if time.monotonic() >= deadline:
            break
        handle = kernel.OpenProcess(0x0400 | 0x0010, False, pid)
        if not handle:
            continue
        try:
            buffer = ctypes.create_unicode_buffer(32768)
            length = wintypes.DWORD(len(buffer))
            if not kernel.QueryFullProcessImageNameW(handle, 0, buffer, ctypes.byref(length)):
                continue
            if os.path.normcase(os.path.realpath(buffer.value)) != expected:
                continue
            key = find_key_in_chunks(chunks(handle), first_page, cancel)
            if key is not None:
                return key
        finally:
            kernel.CloseHandle(handle)
    raise CollectorError('key_not_found')


def collect(account_dir, cache_dir, weixin_exe, *, progress=None, cancel=None):
    """Return {total, items, sourceErrors}; no URL or database key in results.

    items contain path, sha256, sourceMd5, format, width, height, frames, bytes.
    progress receives {phase, total, processed, collected, failed}. Source-wide
    failures raise CollectorError(code); individual failures are counted and
    skipped. CollectionCancelled propagates. Cache is caller-owned and bounded
    to 512 MiB; caller should use a current-user private application directory.
    """
    check_cancel(cancel)
    try:
        account = Path(account_dir).resolve()
        cache = Path(cache_dir).resolve()
        if cache == account or account in cache.parents:
            raise CollectorError('collector_failed')
        database_path = account / 'db_storage' / 'emoticon' / 'emoticon.db'
        if progress:
            progress({'phase': 'collecting', 'total': 0, 'processed': 0, 'collected': 0, 'failed': 0})
        database, wal = stable_snapshot(database_path, cancel)
        key = find_wechat_key(database[:PAGE_SIZE], weixin_exe, cancel)
        try:
            plain = decrypt_database(database, wal, key)
            rows = favorite_rows(plain)
        finally:
            # Python cannot guarantee wiping immutable temporary key bytes. No
            # serialization/logging; release references immediately after use.
            key = None
            plain = None
            database = wal = None
        items, errors, seen = [], {}, set()
        for index, (_, source_md5, url) in enumerate(rows):
            check_cancel(cancel)
            try:
                data = download_original(url, cancel)
                metadata = validate_image(data, source_md5)
                if metadata['sha256'] not in seen:
                    path = cache_original(Path(cache_dir), data, metadata)
                    items.append(dict(metadata, path=path))
                    seen.add(metadata['sha256'])
            except CollectorError as error:
                errors[error.code] = errors.get(error.code, 0) + 1
            if progress:
                progress({'phase': 'collecting', 'total': len(rows), 'processed': index + 1,
                          'collected': len(items), 'failed': sum(errors.values())})
        return {'total': len(rows), 'items': items,
                'sourceErrors': [{'code': code, 'count': count} for code, count in sorted(errors.items())]}
    except (CollectorError, CollectionCancelled):
        raise
    except Exception:
        raise CollectorError('collector_failed') from None
