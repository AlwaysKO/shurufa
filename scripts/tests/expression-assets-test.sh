#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export ROOT_DIR

python3 -B - <<'PY'
import hashlib
import json
import os
import sys
from pathlib import Path
from PIL import Image

root = Path(os.environ["ROOT_DIR"])
sys.path.insert(0, str(root / "scripts/tests"))
from expression_image_compare import same_visible_rgba
runtime = root / "server/.runtime/expression-assets"
android = root / "android/YuyanIme/yuyansdk/src/main/assets/expression"
source_manifest_path = root / "assets/expression/manifest.source.json"

catalog_path = runtime / "catalog.json"
if not catalog_path.is_file():
    raise SystemExit("缺少服务端素材目录，请先运行 npm run expression:generate -- --verify")

catalog = json.loads(catalog_path.read_text())
android_catalog = json.loads((android / "catalog.json").read_text())
source_manifest = json.loads(source_manifest_path.read_text())

for template in source_manifest["templates"]:
    crop = template.get("sourceCrop")
    assert crop is not None, f"模板缺少源裁剪框：{template['id']}"
    assert crop["x"] >= 0 and crop["y"] >= 96, f"模板顶部裁剪不足：{template['id']}"
    assert crop["width"] > 0 and crop["height"] > 0, f"模板裁剪尺寸非法：{template['id']}"
    safe = template["textSafeArea"]
    assert safe["x"] >= 0 and safe["y"] >= 0, f"文字区起点非法：{template['id']}"
    assert safe["x"] + safe["width"] <= 512, f"文字区横向越界：{template['id']}"
    assert safe["y"] + safe["height"] <= 512, f"文字区纵向越界：{template['id']}"

templates = catalog["templates"]
prebuilt_phrases = source_manifest.get("prebuiltPhrases", [])
phrase_words = {phrase["text"] for phrase in prebuilt_phrases} | {item["embeddedText"] for item in source_manifest.get("prebuiltAssets", [])}
assert len(phrase_words) >= 20, len(phrase_words)
for phrase in prebuilt_phrases:
    matches = [
        item for item in templates
        if item.get("type") == "prebuilt" and item.get("embeddedText") == phrase["text"]
    ]
    assert len(matches) >= 4, f"预制图不足：{phrase['text']} / {len(matches)}"

bases = catalog["emojiBases"]
combinations = catalog["emojiCombinations"]
animated = [item for item in templates if item["format"] == "gif"]
prebuilt = [item for item in templates if item.get("type") == "prebuilt"]
synthesis = [item for item in templates if item.get("type") == "synthesis-template"]
static = [item for item in synthesis if item["format"] != "gif"]

expected = source_manifest["expectedCounts"]
independent = source_manifest.get("prebuiltAssets", [])
assert len(prebuilt) == sum(len(p["templateIds"]) for p in prebuilt_phrases) + len(independent), len(prebuilt)
assert len(synthesis) == expected["templates"], len(synthesis)
assert len(animated) == expected["animatedTemplates"] + len(independent), len(animated)
assert len(static) == expected["templates"] - expected["animatedTemplates"], len(static)
assert len(bases) == expected["emojiBases"], len(bases)
assert len(combinations) == expected["emojiBases"] ** 2, len(combinations)
assert catalog["version"] == source_manifest["version"], "catalog 版本不一致"
assert len({item["id"] for item in templates}) == len(templates), "存在重复素材 ID"
assert android_catalog == catalog, "Android 与服务端 catalog 不一致"

base_ids = [item["id"] for item in bases]
expected_keys = {f"{first}__{second}" for first in base_ids for second in base_ids}
actual_keys = [item["key"] for item in combinations]
assert len(actual_keys) == len(set(actual_keys)), "存在重复 Emoji 组合键"
assert set(actual_keys) == expected_keys, "Emoji 有序组合不完整"
combination_hashes = {item["key"]: item["sha256"] for item in combinations}
for first in base_ids:
    for second in base_ids:
        if first != second:
            assert combination_hashes[f"{first}__{second}"] != combination_hashes[f"{second}__{first}"], (
                f"Emoji 交换顺序后图片相同：{first} / {second}"
            )

def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

for item in [*templates, *bases, *combinations]:
    path = runtime / item["fileName"]
    assert path.is_file(), f"缺少素材：{path}"
    assert digest(path) == item["sha256"], f"素材哈希不一致：{path}"

assert len(independent) == 52, "独立预制素材应保留旧12张并新增40张"
for word in ["谢谢", "无语", "笑死"]:
    items = [item for item in independent if item["embeddedText"] == word]
    assert len(items) == 4 and all(item.get("distribution", "bundled") == "bundled" for item in items), word
batch_root = root / "assets/expression/batches/daily-01"
batch = json.loads((batch_root / "manifest.json").read_text())
report = json.loads((root / "artifacts/expression-batches/daily-01/report.json").read_text())
approved_hashes = {item["id"]: item["metadata"]["sha256"] for item in report["items"]}
for word in batch["keywords"]:
    items = [item for item in independent if item["embeddedText"] == word]
    approved = [item for item in batch["items"] if item["keyword"] == word]
    assert not any(phrase["text"] == word for phrase in prebuilt_phrases), word
    assert len([item for item in prebuilt if item["embeddedText"] == word]) == 8, word
    assert [item["id"] for item in items] == [item["id"] for item in approved], word
    assert [item["distribution"] for item in items] == ["bundled"] * 4 + ["remote"] * 4, word
    for item, original in zip(items, approved):
        assert item["distribution"] == original["distribution"], item["id"]
        assert item["sha256"] == approved_hashes[item["id"]], item["id"]
        assert item["style"] == original["style"] and item["sourceType"] == original["sourceType"], item["id"]

for source in independent:
    item = next(item for item in templates if item["id"] == source["id"])
    assert item.get("distribution", "bundled") == source.get("distribution", "bundled"), source["id"]
    assert item["type"] == "prebuilt" and item["format"] == "gif", source["id"]
    assert item["embeddedText"] == source["embeddedText"], source["id"]
    assert item["layout"] is None and item["textSafeArea"] is None, source["id"]
    assert item["width"] == item["height"] == 240, source["id"]
    path = runtime / item["fileName"]
    assert digest(path) == source["sha256"] == digest(root / "assets/expression" / source["source"]), source["id"]
    assert path.stat().st_size < 250 * 1024, source["id"]
    with Image.open(path) as gif:
        assert gif.format == "GIF" and gif.size == (240, 240), source["id"]
        assert 10 <= gif.n_frames <= 20 and gif.info.get("loop") == 0, source["id"]
        first = gif.convert("RGBA")
        duration = 0
        for frame in range(gif.n_frames):
            gif.seek(frame)
            duration += gif.info.get("duration", 0)
        assert 800 <= duration <= 2000, source["id"]
        with Image.open(runtime / item["thumbnailFileName"]) as thumbnail:
            assert thumbnail.format == "WEBP" and thumbnail.size == first.size, source["id"]
            assert same_visible_rgba(first, thumbnail), source["id"]

for item in templates:
    thumbnail = runtime / item["thumbnailFileName"]
    assert thumbnail.is_file(), f"缺少缩略图：{thumbnail}"
    assert digest(thumbnail), f"缩略图无效：{thumbnail}"

built_in_templates = set(source_manifest.get("builtInTemplateIds", []))
high_frequency = set(source_manifest.get("highFrequencyCombinations", []))
expected_android_files = {
    "catalog.json",
    *(item["fileName"] for item in bases),
    *(item["thumbnailFileName"] for item in templates),
    *(item["fileName"] for item in prebuilt if item.get("distribution", "bundled") == "bundled"),
    *(item["fileName"] for item in templates if item["id"] in built_in_templates),
    *(item["fileName"] for item in combinations if item["key"] in high_frequency),
}
actual_android_files = {
    str(path.relative_to(android)) for path in android.rglob("*") if path.is_file()
}
assert actual_android_files == expected_android_files, "Android 内置素材集合不符合清单"

for relative in expected_android_files - {"catalog.json"}:
    assert digest(android / relative) == digest(runtime / relative), f"Android 素材不一致：{relative}"

print(
    "素材审计通过："
    f"{len(prebuilt)} prebuilt / {len(synthesis)} synthesis / "
    f"{len(animated)} GIF / {len(static)} static / "
    f"{len(bases)} bases / {len(combinations)} ordered WebP，"
    f"Android 内置 {len(expected_android_files) - 1} 个素材文件"
)
PY
