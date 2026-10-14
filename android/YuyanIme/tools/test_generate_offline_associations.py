#!/usr/bin/env python3
"""检查日常来源及长前缀生成，不依赖外网下载。"""
import tempfile
import unittest
from collections import OrderedDict
from pathlib import Path

from generate_offline_associations import add_daily_phrases


class IdentitySimplifier:
    def convert(self, text):
        return text


class DailyAssociationGenerationTest(unittest.TestCase):
    def generate(self, text):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "daily.txt"
            source.write_text(text, encoding="utf-8")
            index = OrderedDict()
            add_daily_phrases(index, source, IdentitySimplifier())
            return index

    def test_daily_source_and_long_prefix_continuation(self):
        index = self.generate("我晚点回复你\n")
        self.assertEqual([("D", "点回复你")], index["我晚"])
        self.assertEqual([("D", "回复你")], index["我晚点"])
        self.assertEqual([("D", "你")], index["我晚点回复"])
        self.assertNotIn("我晚点回复你", index)

    def test_explicit_context_is_not_shortened(self):
        index = self.generate("麻烦确认\t一下\n")
        self.assertNotIn("麻烦", index)
        self.assertEqual([("D", "一下")], index["麻烦确认"])
        self.assertEqual([("D", "下")], index["麻烦确认一"])


if __name__ == "__main__":
    unittest.main()
