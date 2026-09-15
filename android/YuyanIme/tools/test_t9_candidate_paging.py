"""Rime native 库无法在宿主 JVM 运行，校验首屏/翻页的过滤入口连接。"""
from pathlib import Path
import unittest

SOURCE = (Path(__file__).resolve().parents[1] / 'yuyansdk/src/main/java/com/yuyan/inputmethod/RimeEngine.kt').read_text()

class T9CandidatePagingTest(unittest.TestCase):
    def test_empty_first_page_loads_next_visible_page(self):
        self.assertRegex(SOURCE, r'\}\s*\.ifEmpty\s*\{\s*getNextPageCandidates\(\)\.asList\(\)\s*\}')

    def test_first_page_keeps_native_readings(self):
        self.assertIn('OfflineT9Candidates.select(code, candidates.map { it.text }, candidates.map { it.comment })', SOURCE)
        self.assertIn('T9Spelling.preedit(code, reading) ?: code', SOURCE)

    def test_next_page_uses_selection_index_mapping(self):
        self.assertIn('personalCandidates?.appendNativePage(candidates.map { it.text }, learningCode(), candidates.map { it.comment })', SOURCE)
        self.assertIn('if (visible.isNotEmpty()) return visible', SOURCE)

if __name__ == '__main__':
    unittest.main()
