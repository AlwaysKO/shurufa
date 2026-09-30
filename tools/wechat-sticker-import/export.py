"""Standalone original-image export. No dashboard connection or pairing token."""
import argparse
import json
from pathlib import Path
from collector import collect, CollectorError, CollectionCancelled

MESSAGES = {
    'key_not_found': '未能解锁收藏，请登录电脑微信并打开收藏表情面板后重试。',
    'snapshot_unstable': '微信数据正在变化，请稍后重试。',
    'wechat_not_running': '未找到对应微信进程，请确认微信已登录及程序路径正确。',
    'invalid_image': '原图不完整或格式不支持',
    'download_failed': '原图下载失败',
    'collector_failed': '提取失败，请检查微信版本、目录及输出空间。',
}

def main(argv=None, *, collector=collect):
    parser = argparse.ArgumentParser(description='提取本人微信收藏原图到本机；不连接后台、不需要配对。')
    parser.add_argument('--config', help='本地导出配置文件')
    parser.add_argument('--account-dir', help='微信账号目录（包含 db_storage）')
    parser.add_argument('--weixin-exe', help='Weixin.exe 完整路径')
    parser.add_argument('--output', help='原图输出文件夹，不能在微信目录中')
    args = parser.parse_args(argv)
    try:
        if args.config:
            if args.account_dir or args.weixin_exe or args.output:
                raise ValueError('mixed configuration')
            config = json.loads(Path(args.config).read_text(encoding='utf-8-sig'))
            if set(config) != {'account_dir','weixin_exe','output_dir'}:
                raise ValueError('config')
        else:
            config = {'account_dir': args.account_dir, 'weixin_exe': args.weixin_exe, 'output_dir': args.output}
        if not all(isinstance(v,str) and v.strip() for v in config.values()):
            raise ValueError('missing path')
        account = Path(config['account_dir']).resolve(strict=True)
        exe = Path(config['weixin_exe']).resolve(strict=True)
        output = Path(config['output_dir']).resolve()
        if not account.is_dir() or not exe.is_file():
            raise ValueError('path type')
        if output == account or account in output.parents or output in account.parents:
            raise ValueError('output overlaps wechat')
        output.mkdir(mode=0o700,parents=True,exist_ok=True)
    except Exception:
        print('导出配置无效：请核对账号目录、微信程序及独立输出目录，不要选择微信数据目录或其上级。')
        return 1
    last = -1
    def progress(state):
        nonlocal last
        processed = state['processed']
        if processed != last and (processed % 10 == 0 or processed == state['total']):
            last = processed
            print(f"已处理 {processed}/{state['total']}，有效原图 {state['collected']}，失败 {state['failed']}", flush=True)
    print('开始只读提取收藏原图；不会上传或修改微信。按 Ctrl+C 停止。',flush=True)
    try:
        result = collector(str(account),str(output),str(exe),progress=progress)
        failed = sum(item['count'] for item in result['sourceErrors'])
        print(f"提取结束：发现 {result['total']}，有效原图 {len(result['items'])}，失败 {failed}。")
        for item in result['sourceErrors']:
            print(f"{MESSAGES.get(item['code'], '其他采集错误')}：{item['count']}")
        print(f'图片目录：{output}')
        print('相同原图按 SHA 文件名复用；目录内历史图片不删除。只把图片上传到后台素材库。')
        return 2 if failed else 0
    except (KeyboardInterrupt, CollectionCancelled):
        print('已停止提取，已导出的原图保留。')
        return 130
    except CollectorError as error:
        print(MESSAGES.get(error.code,MESSAGES['collector_failed']))
        return 1
    except Exception:
        print(MESSAGES['collector_failed'])
        return 1

if __name__ == '__main__':
    raise SystemExit(main())
