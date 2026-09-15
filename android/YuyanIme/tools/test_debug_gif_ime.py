"""已验证的 GIF 入口提升为正常服务后的结构回归。"""
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[1]
A = '{http://schemas.android.com/apk/res/android}'
NAME = 'com.yuyan.imemodule.compat.com.sohu.inputmethod.sogou.DebugGifImeService'

class NormalGifImeTest(unittest.TestCase):
    def test_only_normal_service_registered_in_main(self):
        services = ET.parse(ROOT/'app/src/main/AndroidManifest.xml').findall('.//service')
        self.assertEqual([NAME], [s.get(A+'name') for s in services])
        service = services[0]
        self.assertEqual('@string/app_name', service.get(A+'label'))
        self.assertEqual('android.permission.BIND_INPUT_METHOD', service.get(A+'permission'))
        self.assertEqual('@xml/method', service.find('meta-data').get(A+'resource'))
        self.assertEqual([], ET.parse(ROOT/'app/src/debug/AndroidManifest.xml').findall('.//service'))

    def test_shared_class_and_setup_identify_same_component(self):
        relative = Path(NAME.replace('.', '/')+'.kt')
        source = ROOT/'yuyansdk/src/main/java'/relative
        self.assertIn(': ImeService()', source.read_text())
        self.assertFalse((ROOT/'app/src/debug/java'/relative).exists())
        util = (ROOT/'yuyansdk/src/main/java/com/yuyan/imemodule/utils/InputMethodUtil.kt').read_text()
        self.assertIn('import '+NAME, util)
        self.assertIn('DebugGifImeService::class.java.name', util)
        self.assertIn('ComponentName(Launcher.instance.context, DebugGifImeService::class.java)', util)

    def test_library_shrinker_preserves_required_component_name(self):
        rules = (ROOT/'yuyansdk/proguard.cfg').read_text()
        self.assertIn('-keep class '+NAME+' {', rules)

    def test_application_identity_and_no_experimental_label(self):
        self.assertIn('applicationId "com.yuyan.pinyin"', (ROOT/'app/build.gradle').read_text())
        for path in (ROOT/'app/src').rglob('*.xml'):
            self.assertNotIn('debug_gif_ime_label', path.read_text())

if __name__ == '__main__': unittest.main()
