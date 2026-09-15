import unittest
from analyze import summarize

class AnalyzeTest(unittest.TestCase):
    def test_same_code_targets_are_not_all_required_first(self):
        result = summarize({'64426': {'你好', '拟好'}}, [dict(code='64426', ranks={'你好':[0,0], '拟好':[6,-1]}, nativeTop5=['你好'], afterTop5=['你好'])])
        self.assertEqual(result['targets'], 2)
        self.assertEqual(result['afterFirst'], 1)
        self.assertEqual(result['removedFromNative100'], 1)

    def test_missing_case_is_not_success(self):
        with self.assertRaises(ValueError): summarize({'64426': {'你好'}}, [])

    def test_duplicate_case_is_not_success(self):
        row = dict(code='64426', ranks={'你好':[0,0]}, nativeTop5=['你好'], afterTop5=['你好'])
        with self.assertRaises(ValueError): summarize({'64426': {'你好'}}, [row,row])

    def test_wrong_target_set_is_not_success(self):
        with self.assertRaises(ValueError): summarize({'64426': {'你好'}}, [dict(code='64426',ranks={'拟好':[0,0]})])

if __name__ == '__main__': unittest.main()
