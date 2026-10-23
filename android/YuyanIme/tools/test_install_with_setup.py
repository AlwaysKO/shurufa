import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

SOURCE = Path(__file__).with_name('install_with_setup.py')
spec = importlib.util.spec_from_file_location('install_with_setup', SOURCE) if SOURCE.exists() else None
module = importlib.util.module_from_spec(spec) if spec else None
if module:
    spec.loader.exec_module(module)

PACKAGE = 'com.yuyan.pinyin.offline.debug'


class InstallSetupTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(module, '缺少带配置核验的电脑安装入口')
        self.commands = []
        self.whitelist = ''
        self.devices = 'List of devices attached\nphone\tdevice\n'
        self.install_fails = False
        self.persist_whitelist = True

    def run_command(self, args):
        self.commands.append(args)
        command = args[3:] if args[:3] == ['adb', '-s', 'phone'] else args[1:]
        if command == ['devices']:
            return self.devices
        if command[:2] == ['install', '-r']:
            if self.install_fails:
                raise subprocess.CalledProcessError(1, args, output='INSTALL_FAILED_UPDATE_INCOMPATIBLE')
            return 'Success\n'
        if command == ['shell', 'dumpsys', 'deviceidle', 'whitelist']:
            return self.whitelist
        if command == ['shell', 'dumpsys', 'deviceidle', 'whitelist', '+' + PACKAGE]:
            if self.persist_whitelist:
                self.whitelist = 'user,' + PACKAGE + ',10375\n'
            return 'Added: ' + PACKAGE
        if command[:4] == ['shell', 'settings', 'get', 'secure']:
            return PACKAGE + '/com.yuyan.imemodule.service.capture.PassiveChatAccessibilityService\n'
        raise AssertionError('Unexpected command: ' + repr(args))

    def install(self, serial=None):
        return module.install_and_configure('adb', Path('app.apk'), PACKAGE, serial, self.run_command)

    def test_install_then_exempt_only_target_and_verify(self):
        report = self.install()
        self.assertTrue(report['installed'])
        self.assertTrue(report['battery_exempt'])
        self.assertTrue(report['accessibility_enabled'])
        self.assertIn(['adb', '-s', 'phone', 'install', '-r', 'app.apk'], self.commands)
        self.assertFalse(any(token in ('force-stop', 'uninstall', 'clear', 'put', 'grant')
                             for command in self.commands for token in command))

    def test_multiple_devices_require_explicit_selection_before_install(self):
        self.devices += 'other\tdevice\n'
        with self.assertRaisesRegex(ValueError, '设备'):
            self.install()
        self.assertEqual([['adb', 'devices']], self.commands)

    def test_explicit_serial_must_be_authorized_and_online(self):
        self.devices = 'List of devices attached\nphone\tunauthorized\n'
        with self.assertRaisesRegex(ValueError, '设备'):
            self.install('phone')
        self.assertEqual([['adb', 'devices']], self.commands)

    def test_failed_install_never_changes_settings(self):
        self.install_fails = True
        with self.assertRaises(subprocess.CalledProcessError):
            self.install()
        self.assertFalse(any('whitelist' in command for command in self.commands))

    def test_successful_command_without_readback_is_not_success(self):
        self.persist_whitelist = False
        report = self.install()
        self.assertTrue(report['installed'])
        self.assertFalse(report['battery_exempt'])

    def test_existing_exemption_does_not_need_rewriting(self):
        self.whitelist = 'user,' + PACKAGE + ',10375\n'
        self.install()
        self.assertFalse(any('+' + PACKAGE in command for command in self.commands))

    def test_readback_does_not_match_similar_package_or_service(self):
        self.assertFalse(module.has_exemption('user,' + PACKAGE + '.other,9', PACKAGE))
        self.assertFalse(module.has_accessibility(PACKAGE + '/other.Service', PACKAGE))

    def test_only_project_packages_can_be_configured(self):
        with self.assertRaises(ValueError):
            module.install_and_configure('adb', Path('app.apk'), 'com.other.app', None, self.run_command)
        self.assertEqual([], self.commands)

    def test_signature_validation_failure_never_touches_phone(self):
        class InvalidSignature:
            @staticmethod
            def verify(apk, tools):
                raise AssertionError('签名不符')
        # CLI 必须先调用交付签名校验，再执行任何 ADB 命令。
        with patch('sys.argv', ['install_with_setup.py', str(SOURCE), '--build-tools', '/sdk']), \
                patch.object(module.importlib.util, 'module_from_spec', return_value=InvalidSignature()), \
                patch.object(module.importlib.util, 'spec_from_file_location') as loader, \
                patch.object(module, 'run_command') as command:
            loader.return_value.loader.exec_module.return_value = None
            with self.assertRaisesRegex(AssertionError, '签名不符'):
                module.main()
            command.assert_not_called()

    def test_explicit_device_selection_with_multiple_devices(self):
        self.devices += 'other\tdevice\n'
        self.assertEqual('phone', self.install('phone')['serial'])


if __name__ == '__main__':
    unittest.main()
