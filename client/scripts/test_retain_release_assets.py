import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('retain', Path(__file__).with_name('retain-release-assets.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class RetainAssetsTest(unittest.TestCase):
    def test_retains_four_releases_without_recursively_retaining_copies(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for number in range(7):
                release = root / str(number)
                assets = release / 'client/dist/assets'
                assets.mkdir(parents=True)
                (release / 'REVISION').write_text(str(number))
                (assets / f'page-{number}.js').write_text(f'export default {number}')
                module.retain_assets(root, release)
            current = root / '6/client/dist'
            self.assertEqual(sorted(p.name for p in (current / 'assets').iterdir()),
                [f'page-{n}.js' for n in range(2, 7)])
            self.assertEqual(json.loads((current / module.MANIFEST).read_text()), ['page-6.js'])
            self.assertEqual(module.retain_assets(root, root / '6'), 0)

    def test_existing_releases_without_inventory_and_identical_shared_assets(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ['old', 'new']:
                assets = root / name / 'client/dist/assets'
                assets.mkdir(parents=True)
                (root / name / 'REVISION').write_text(name)
                (assets / 'shared-hash.js').write_text('same bytes')
                (assets / f'{name}-hash.js').write_text(name)
            self.assertEqual(module.retain_assets(root, root / 'new'), 1)
            self.assertEqual((root / 'new/client/dist/assets/old-hash.js').read_text(), 'old')
            self.assertNotIn('old-hash.js', json.loads((root / 'new/client/dist' / module.MANIFEST).read_text()))

    def test_conflicting_same_name_stops_deployment(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ['old', 'new']:
                assets = root / name / 'client/dist/assets'
                assets.mkdir(parents=True)
                (root / name / 'REVISION').write_text(name)
                (assets / 'page-hash.js').write_text(name)
            with self.assertRaisesRegex(ValueError, 'Conflicting'):
                module.retain_assets(root, root / 'new')
            self.assertEqual((root / 'new/client/dist/assets/page-hash.js').read_text(), 'new')


if __name__ == '__main__':
    unittest.main()
