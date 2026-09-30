import hashlib
import importlib.util
import io
import os
import stat
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('standalone_export', Path(__file__).with_name('export.py'))
e = importlib.util.module_from_spec(spec)
spec.loader.exec_module(e)

class ExportTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.account = self.root / 'wechat'; self.account.mkdir()
        self.exe = self.root / 'Weixin.exe'; self.exe.touch()
        self.output = self.root / 'pictures'
        self.args = ['--account-dir', str(self.account), '--weixin-exe', str(self.exe), '--output', str(self.output)]
    def run_export(self, collector, args=None):
        out = io.StringIO()
        with redirect_stdout(out): code = e.main(self.args if args is None else args, collector=collector)
        return code, out.getvalue()
    def test_exports_without_origin_or_pairing_and_reports_counts(self):
        def collect(account, output, exe, **kwargs):
            self.assertEqual(Path(account), self.account)
            self.assertEqual(Path(exe), self.exe)
            Path(output).mkdir(exist_ok=True)
            (Path(output) / 'original.gif').write_bytes(b'original')
            kwargs['progress']({'total':3,'processed':3,'collected':2,'failed':1})
            return {'total':3,'items':[{},{}], 'sourceErrors':[{'code':'invalid_image','count':1}]}
        code, out = self.run_export(collect)
        self.assertEqual(code, 2)
        self.assertIn('有效原图 2', out); self.assertIn('失败 1', out)
        self.assertEqual((self.output/'original.gif').read_bytes(), b'original')
        self.assertFalse((self.output/'token.dpapi').exists())
    def test_success_and_repeat_never_clears_output(self):
        self.output.mkdir(); old = self.output/'old.gif'; old.write_bytes(b'old')
        fake = Mock(return_value={'total':0,'items':[], 'sourceErrors':[]})
        self.assertEqual(self.run_export(fake)[0],0)
        self.assertEqual(old.read_bytes(),b'old')
    def test_output_in_wechat_is_rejected_before_collect(self):
        fake = Mock()
        args = self.args[:-1] + [str(self.account/'out')]
        self.assertEqual(self.run_export(fake,args)[0],1); fake.assert_not_called()
    def test_output_is_wechat_ancestor_rejected(self):
        fake = Mock()
        self.assertEqual(self.run_export(fake,self.args[:-1]+[str(self.root)])[0],1)
        fake.assert_not_called()
    def test_private_config_without_server_values(self):
        config = self.root/'export-config.json'
        config.write_text(json.dumps({'account_dir':str(self.account),'weixin_exe':str(self.exe),'output_dir':str(self.output)}),encoding='utf-8-sig')
        fake = Mock(return_value={'total':0,'items':[], 'sourceErrors':[]})
        self.assertEqual(self.run_export(fake,['--config',str(config)])[0],0)
    def test_exception_does_not_log_secret(self):
        code,out=self.run_export(Mock(side_effect=RuntimeError('secret=https://private/key')))
        self.assertEqual(code,1); self.assertNotIn('private',out); self.assertNotIn('secret',out)
    def test_cancel_returns_130_and_keeps_files(self):
        self.output.mkdir(); (self.output/'keep.gif').write_bytes(b'keep')
        self.assertEqual(self.run_export(Mock(side_effect=KeyboardInterrupt()))[0],130)
        self.assertTrue((self.output/'keep.gif').exists())
    @unittest.skipIf(os.name == 'nt', 'Windows ACL 由安装器设置')
    def test_new_output_directory_private(self):
        fake=Mock(return_value={'total':0,'items':[], 'sourceErrors':[]})
        self.assertEqual(self.run_export(fake)[0],0)
        self.assertEqual(stat.S_IMODE(self.output.stat().st_mode),0o700)
    def test_missing_account_rejected(self):
        self.account.rmdir(); fake=Mock()
        self.assertEqual(self.run_export(fake)[0],1); fake.assert_not_called()
    def test_cancel_during_original_write_removes_only_owned_partial(self):
        import collector as c
        data=b'original bytes'
        metadata={'sha256':hashlib.sha256(data).hexdigest(),'format':'gif'}
        original_open=Path.open
        class InterruptedStream:
            def __init__(self, stream): self.stream=stream
            def __enter__(self): return self
            def __exit__(self,*args): self.stream.close()
            def write(self,value):
                self.stream.write(value[:3]); raise KeyboardInterrupt()
        def open_interrupted(path,*args,**kwargs):
            stream=original_open(path,*args,**kwargs)
            return InterruptedStream(stream) if args and args[0]=='xb' else stream
        with patch.object(Path,'open',open_interrupted):
            with self.assertRaises(KeyboardInterrupt): c.cache_original(self.output,data,metadata)
        self.assertFalse((self.output/(metadata['sha256']+'.gif')).exists())
        saved=c.cache_original(self.output,data,metadata)
        self.assertEqual(Path(saved).read_bytes(),data)
    def test_collector_known_error_has_chinese_message(self):
        from collector import CollectorError
        code,out=self.run_export(Mock(side_effect=CollectorError('key_not_found')))
        self.assertEqual(code,1); self.assertIn('表情面板',out)

if __name__ == '__main__': unittest.main()
