"""Exercise real Nginx admission using headers only; no audio or production changes."""
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile
import time
import unittest

HERE = Path(__file__).resolve().parent


@unittest.skipUnless(shutil.which('nginx'), 'local nginx required')
class RecordingBodyLimitTest(unittest.TestCase):
    def test_recording_limit_is_64_mib_without_expanding_other_routes(self):
        for source in (HERE / 'nginx.conf', HERE.parent / 'nginx/shurufa.conf'):
            with self.subTest(config=source.name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                text = source.read_text()
                start = text.index('server {')
                depth, end = 0, start
                for end in range(start, len(text)):
                    depth += (text[end] == '{') - (text[end] == '}')
                    if depth == 0 and text[end] == '}':
                        break
                site = text[start:end + 1]
                with socket.socket() as listener:
                    listener.bind(('127.0.0.1', 0))
                    port = listener.getsockname()[1]
                site = re.sub(r'^\s*(listen|ssl_\w+|root|access_log|error_log)\s+[^;]+;', '', site, flags=re.M)
                site = re.sub(r'^\s*include /etc/letsencrypt/[^;]+;', '', site, flags=re.M)
                site = site.replace('/etc/nginx/shurufa-proxy.conf', str(HERE / 'proxy.conf'))
                site = site.replace('server {', f'server {{ listen 127.0.0.1:{port};', 1)
                config = root / 'nginx.conf'
                config.write_text(f'pid {root}/nginx.pid; error_log {root}/error.log;\n'
                    'events {} http { access_log off; upstream shurufa_backend { server 127.0.0.1:9; }\n'
                    + site + '\n}')
                subprocess.run(['nginx', '-t', '-p', directory, '-c', str(config)], check=True, capture_output=True)
                process = subprocess.Popen(['nginx', '-p', directory, '-c', str(config), '-g', 'daemon off; master_process off;'],
                                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                try:
                    for _ in range(100):
                        try:
                            with socket.create_connection(('127.0.0.1', port), .1):
                                break
                        except OSError:
                            time.sleep(.01)
                    def status(path, size):
                        with socket.create_connection(('127.0.0.1', port), 2) as client:
                            client.settimeout(2)
                            client.sendall((f'PUT {path} HTTP/1.1\r\nHost: my.dog8ball.com\r\n'
                                f'Content-Length: {size}\r\nContent-Type: application/octet-stream\r\n'
                                'Expect: 100-continue\r\nConnection: close\r\n\r\n').encode())
                            return int(client.recv(1024).split(b' ')[1])
                    recording = '/api/v1/mobile/call-recordings/test'
                    self.assertEqual(100, status(recording, 64 * 1024 * 1024))
                    self.assertEqual(413, status(recording, 64 * 1024 * 1024 + 1))
                    self.assertEqual(413, status('/api/v1/mobile/chat/assets', 37 * 1024 * 1024))
                finally:
                    process.terminate()
                    process.wait(timeout=5)


if __name__ == '__main__':
    unittest.main()
