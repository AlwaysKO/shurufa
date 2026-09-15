"""夜间模式隔离的资源及初始化连接回归；偏好行为由 Robolectric 测试覆盖。"""
import re
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1] / 'yuyansdk/src/main'
JAVA = ROOT / 'java/com/yuyan/imemodule'


class KeyboardNightModeTest(unittest.TestCase):
    def test_migration_runs_before_preferences_are_loaded(self):
        source = (JAVA / 'application/Launcher.kt').read_text()
        self.assertIn('KeyboardNightModeMigration.migrate(preferences)', source)
        self.assertLess(source.index('KeyboardNightModeMigration.migrate(preferences)'),
                        source.index('AppPrefs.init(preferences)'))

    def test_default_does_not_follow_system(self):
        source = (JAVA / 'data/theme/ThemePrefs.kt').read_text()
        self.assertRegex(source, r'"follow_system_dark_mode",\s*false,')

    def test_ime_theme_disables_force_dark(self):
        styles = ET.parse(ROOT / 'res/values/themes.xml')
        theme = styles.find(".//style[@name='Theme.ImeTheme']")
        self.assertIsNotNone(theme)
        self.assertEqual('@android:style/Theme.DeviceDefault.InputMethod', theme.get('parent'))
        self.assertEqual('false', theme.find("item[@name='android:forceDarkAllowed']").text)

    def test_ime_applies_theme_before_framework_window_creation(self):
        source = (JAVA / 'service/ImeService.kt').read_text()
        self.assertRegex(source, r'override fun onCreate\(\)\s*\{\s*setTheme\(R.style.Theme_ImeTheme\)\s*super.onCreate\(\)')
        self.assertRegex(source, r'if\s*\(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q\)\s*\{\s*window\?\.window\?\.decorView\?\.isForceDarkAllowed = false')


if __name__ == '__main__':
    unittest.main()
