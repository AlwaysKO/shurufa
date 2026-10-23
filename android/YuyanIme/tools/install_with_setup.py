#!/usr/bin/env python3
"""保留数据安装原签名交付包，并核验该应用的电池优化豁免；不授予无障碍。"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys

PACKAGES = {'com.yuyan.pinyin.release', 'com.yuyan.pinyin.debug',
            'com.yuyan.pinyin.offline.release', 'com.yuyan.pinyin.offline.debug'}
SERVICE = 'com.yuyan.imemodule.service.capture.PassiveChatAccessibilityService'


def run_command(args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)


def has_exemption(output, package):
    return any(len(parts := line.strip().split(',')) == 3 and parts[1] == package
               for line in output.splitlines())


def has_accessibility(output, package):
    return f'{package}/{SERVICE}' in output.strip().split(':')


def install_and_configure(adb, apk, package, serial=None, run=run_command):
    if package not in PACKAGES:
        raise ValueError('仅支持本项目安装包')
    devices = [parts[0] for line in run([adb, 'devices']).splitlines()
               if len(parts := line.split()) == 2 and parts[1] == 'device']
    if serial is None:
        if len(devices) != 1:
            raise ValueError('需要一台已授权设备；多台设备时请指定 --serial')
        serial = devices[0]
    if serial not in devices:
        raise ValueError('指定设备未连接或尚未授权')
    prefix = [adb, '-s', serial]
    output = run(prefix + ['install', '-r', str(apk)])
    if not any(line.strip() == 'Success' for line in output.splitlines()):
        raise RuntimeError('安装未返回成功，未修改系统设置：' + output)
    report = {'serial': serial, 'package': package, 'installed': True,
              'battery_exempt': False, 'accessibility_enabled': None, 'warnings': []}
    try:
        if not has_exemption(run(prefix + ['shell', 'dumpsys', 'deviceidle', 'whitelist']), package):
            run(prefix + ['shell', 'dumpsys', 'deviceidle', 'whitelist', '+' + package])
        report['battery_exempt'] = has_exemption(
            run(prefix + ['shell', 'dumpsys', 'deviceidle', 'whitelist']), package)
        if not report['battery_exempt']:
            report['warnings'].append('电池优化豁免未通过回读，请在手机集中设置页确认')
    except subprocess.CalledProcessError:
        report['warnings'].append('安装已成功，但系统未允许电脑配置电池优化，请在手机设置')
    try:
        report['accessibility_enabled'] = has_accessibility(run(prefix + [
            'shell', 'settings', 'get', 'secure', 'enabled_accessibility_services']), package)
    except subprocess.CalledProcessError:
        report['warnings'].append('未能读取无障碍启用状态')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--build-tools', type=Path, required=True, help='SDK/build-tools/版本目录')
    parser.add_argument('--adb', default='adb', help='adb 或 adb.exe 路径')
    parser.add_argument('--serial', help='多设备连接时必须指定')
    args = parser.parse_args()
    if not args.apk.is_file():
        parser.error('APK 文件不存在')
    verifier_path = Path(__file__).with_name('verify-delivery-apk.py')
    spec = importlib.util.spec_from_file_location('delivery_verifier', verifier_path)
    verifier = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(verifier)
    # 原签名、非 testOnly 校验必须先于任何设备修改。
    verifier.verify(args.apk, args.build_tools)
    aapt = args.build_tools / ('aapt.exe' if os.name == 'nt' else 'aapt')
    badging = run_command([str(aapt), 'dump', 'badging', str(args.apk)])
    package = re.search(r"^package: name='([^']+)'", badging, re.MULTILINE)
    if not package:
        raise ValueError('无法核验 APK 包名')
    report = install_and_configure(args.adb, args.apk, package[1], args.serial)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print('请打开妙言输入法：首次显示后台运行设置，剩余项目由系统确认；已有选择不重置。')
    print('无障碍启用状态不代表服务已连接；本工具不强停、不清数据、不自动授予权限。')
    return 0 if report['battery_exempt'] else 2


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, RuntimeError, AssertionError, subprocess.CalledProcessError, OSError) as error:
        sys.exit('安装或配置未完成：' + str(error))
