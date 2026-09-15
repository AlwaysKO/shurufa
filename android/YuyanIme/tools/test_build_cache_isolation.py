"""离线验证 POSIX wrapper 实際启动参数及 Windows IDE 默认缓存目录。"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class BuildCacheIsolationTest(unittest.TestCase):
    def test_windows_ide_cache_is_separate_from_legacy_shared_cache(self):
        properties = (ROOT / "gradle.properties").read_text()
        self.assertIn("org.gradle.projectcachedir=.gradle/windows", properties.splitlines())

    def test_posix_wrapper_passes_unix_cache_before_user_arguments(self):
        with tempfile.TemporaryDirectory(prefix="fake jdk ") as directory:
            java = Path(directory) / "bin/java"
            java.parent.mkdir()
            java.write_text('#!/bin/sh\nprintf "%s\\n" "$@"\n')
            java.chmod(0o755)
            env = dict(os.environ, JAVA_HOME=directory, JAVA_OPTS="", GRADLE_OPTS="")
            result = subprocess.run(["sh", str(ROOT / "gradlew"), "help", "--offline"],
                                    env=env, text=True, capture_output=True, check=True)
            arguments = result.stdout.splitlines()
            self.assertIn("--project-cache-dir", arguments)
            index = arguments.index("--project-cache-dir")
            self.assertEqual(ROOT / ".gradle/unix", Path(arguments[index + 1]))
            self.assertLess(index, arguments.index("help"))
            self.assertIn("--offline", arguments)


if __name__ == "__main__":
    unittest.main()
