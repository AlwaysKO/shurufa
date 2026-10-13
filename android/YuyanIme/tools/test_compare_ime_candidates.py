import unittest
import json
from pathlib import Path

from compare_ime_candidates import compare


class CandidateComparisonTest(unittest.TestCase):
    cases = [
        {"id": "a", "target": "南山", "group": "locked", "split": "development"},
        {"id": "b", "target": "你好", "group": "full", "split": "holdout"},
    ]

    def test_missing_observation_is_not_a_failed_candidate(self):
        result = compare(self.cases, [{"id": "a", "candidates": ["南山"]}], [])
        self.assertEqual(result["paired_count"], 0)
        self.assertEqual(result["missing_right"], ["a", "b"])
        self.assertIsNone(result["left"]["top5"])

    def test_same_cases_are_scored_and_rank_changes_are_reported(self):
        left = [{"id": "a", "candidates": ["男生", "南山"]},
                {"id": "b", "candidates": []}]
        right = [{"id": "a", "candidates": ["南山", "男生"]},
                 {"id": "b", "candidates": ["你好"]}]
        result = compare(self.cases, left, right)
        self.assertEqual(result["paired_count"], 2)
        self.assertEqual(result["left"]["top1"], 0)
        self.assertEqual(result["right"]["top1"], 1)
        self.assertEqual(result["left"]["top5"], 0.5)
        self.assertEqual(result["rows"][0]["left_rank"], 2)
        self.assertEqual(result["by_split"]["holdout"]["paired_count"], 1)

    def test_duplicate_or_unknown_observation_is_rejected(self):
        for rows in ([{"id": "x", "candidates": []}],
                     [{"id": "a", "candidates": []}] * 2,
                     [{"id": "a", "candidates": "南山"}]):
            with self.assertRaises(ValueError):
                compare(self.cases, rows, [])

    def test_every_input_group_has_holdout_without_same_word_leakage(self):
        cases = json.loads((Path(__file__).parent / "data/ime-quality-cases.json").read_text())["cases"]
        self.assertEqual(len(cases), len({case["id"] for case in cases}))
        for group in {case["group"] for case in cases}:
            self.assertEqual({case["split"] for case in cases if case["group"] == group},
                             {"development", "holdout"})
        for word in {case["target"] for case in cases}:
            self.assertEqual(len({case["split"] for case in cases if case["target"] == word}), 1)


if __name__ == "__main__":
    unittest.main()
