"""比较两份真实候选观察；只对两边均已测的样本评分。无设备操作。"""
import argparse
import json
from pathlib import Path


def compare(cases, left, right):
    ids = {case["id"] for case in cases}
    if len(ids) != len(cases):
        raise ValueError("重复的测试用例 ID")

    def index(rows):
        result = {}
        for row in rows:
            key = row["id"]
            candidates = row.get("candidates")
            if key not in ids or key in result:
                raise ValueError(f"未知或重复的观测 ID: {key}")
            if not isinstance(candidates, list) or any(
                not isinstance(item, str) for item in candidates
            ):
                raise ValueError(f"候选必须是按显示顺序排列的字符串数组: {key}")
            result[key] = candidates
        return result

    left, right = index(left), index(right)
    rows = []
    for case in cases:
        key, target = case["id"], case["target"]
        if key not in left or key not in right:
            continue
        rows.append({
            "id": key, "target": target, "group": case["group"],
            "split": case["split"],
            "left_rank": left[key].index(target) + 1 if target in left[key] else None,
            "right_rank": right[key].index(target) + 1 if target in right[key] else None,
        })

    def metrics(items):
        result = {"paired_count": len(items)}
        for side in ("left", "right"):
            result[side] = {
                f"top{k}": sum(
                    row[f"{side}_rank"] is not None and row[f"{side}_rank"] <= k
                    for row in items
                ) / len(items) if items else None
                for k in (1, 3, 5)
            }
        return result

    return {
        **metrics(rows), "total_cases": len(cases),
        "missing_left": sorted(ids - left.keys()),
        "missing_right": sorted(ids - right.keys()),
        "by_group": {group: metrics([r for r in rows if r["group"] == group])
                     for group in sorted({c["group"] for c in cases})},
        "by_split": {split: metrics([r for r in rows if r["split"] == split])
                     for split in sorted({c["split"] for c in cases})},
        "rows": rows,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("left", type=Path, help="左侧输入法观测 JSON")
    parser.add_argument("right", type=Path, help="右侧输入法观测 JSON")
    parser.add_argument("--cases", type=Path, default=Path(__file__).parent / "data/ime-quality-cases.json")
    args = parser.parse_args()
    cases = json.loads(args.cases.read_text())["cases"]
    observations = [json.loads(path.read_text()) for path in (args.left, args.right)]
    for item in observations:
        metadata = item.get("metadata", {})
        required = ("ime", "version", "device", "network", "learning_state")
        if any(not metadata.get(key) for key in required):
            parser.error("观测必须注明 ime/version/device/network/learning_state")
        if any(not row.get("evidence") for row in item["observations"]):
            parser.error("每个观测必须关联原始截图或候选导出 evidence")
    result = compare(cases, *(item["observations"] for item in observations))
    result["metadata"] = {side: item["metadata"] for side, item in zip(("left", "right"), observations)}
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
