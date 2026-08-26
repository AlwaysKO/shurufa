#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export ROOT_DIR

python3 - <<'PY'
import hashlib
import json
import os
from pathlib import Path

root = Path(os.environ["ROOT_DIR"])
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
assert len(prebuilt_phrases) >= 20, len(prebuilt_phrases)
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

assert len(prebuilt) >= 80, len(prebuilt)
assert len(synthesis) == 60, len(synthesis)
assert len(animated) == 20, len(animated)
assert len(static) == 40, len(static)
assert len(bases) == 48, len(bases)
assert len(combinations) == 2304, len(combinations)
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
    *(item["fileName"] for item in prebuilt),
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
