import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('stage_gifs', Path(__file__).with_name('stage-keyword-gifs.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class StageKeywordGifsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name) / 'repo'
        self.repo.mkdir()
        self.release = Path(self.tmp.name) / 'release'
        self.catalog = self.release / 'assets/expression/approved-keyword-gifs.json'
        self.catalog.parent.mkdir(parents=True)
        self.source = 'artifacts/batch/output/gifs/then.gif'
        source = self.repo / self.source
        source.parent.mkdir(parents=True)
        source.write_bytes(b'GIF89a-original')
        (source.parent / 'unapproved.gif').write_bytes(b'not approved')
        self.git('init', '-q')
        self.git('add', '.')
        self.git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'fixture')
        self.item = {'sourceGif': self.source, 'sha256': hashlib.sha256(source.read_bytes()).hexdigest()}

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repo), *args])

    def write_catalog(self, items):
        self.catalog.write_text(json.dumps({'items': items}))

    def test_exact_revision_and_only_approved_files(self):
        self.write_catalog([self.item])
        (self.repo / self.source).write_bytes(b'local unfinished edit')
        self.assertEqual(module.stage(self.repo, 'HEAD', self.release), 1)
        self.assertEqual((self.release / self.source).read_bytes(), b'GIF89a-original')
        self.assertFalse((self.release / self.source).with_name('unapproved.gif').exists())

    def test_missing_file_or_wrong_checksum_blocks_staging(self):
        for item in [dict(self.item, sourceGif='artifacts/missing.gif'), dict(self.item, sha256='0' * 64)]:
            with self.subTest(item=item):
                self.write_catalog([item])
                with self.assertRaises((ValueError, subprocess.CalledProcessError)):
                    module.stage(self.repo, 'HEAD', self.release)
                self.assertFalse((self.release / self.source).exists())

    def test_traversal_rejected(self):
        self.write_catalog([dict(self.item, sourceGif='artifacts/../../outside.gif')])
        with self.assertRaises(ValueError):
            module.stage(self.repo, 'HEAD', self.release)


if __name__ == '__main__':
    unittest.main()
