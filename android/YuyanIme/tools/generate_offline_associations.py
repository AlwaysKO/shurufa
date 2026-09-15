#!/usr/bin/env python3
"""生成妙言输入法的离线诗文/日常用语联想索引。"""

from __future__ import annotations

import argparse
import base64
import gzip
import json
import re
import time
import urllib.parse
import urllib.request
from collections import OrderedDict
from pathlib import Path


UPSTREAM_REPOSITORY = "chinese-poetry/chinese-poetry"
UPSTREAM_COMMIT = "b8594f81a89752241442f2ce267d6f66f96704ee"
UPSTREAM_FILES = (
    "蒙学/guwenguanzhi.json",
    "蒙学/tangshisanbaishou.json",
    "宋词/宋词三百首.json",
    "诗经/shijing.json",
    "蒙学/qianjiashi.json",
)
PUNCTUATION_PATTERN = re.compile(r"([^，。！？；：、,.!?;:\s]+)([，。！？；：、,.!?;:]*)")
PARENTHETICAL_PATTERN = re.compile(r"[（(][^（）()]{0,100}[）)]")


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    script_dir = Path(__file__).resolve().parent
    project_dir = script_dir.parent
    parser.add_argument("--source-dir", type=Path, help="已下载的 chinese-poetry JSON 目录")
    parser.add_argument(
        "--cache-dir",
        type=Path,
        default=script_dir / ".cache" / "chinese-poetry",
        help="未传 --source-dir 时使用的下载缓存",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=project_dir / "yuyansdk/src/main/assets/completion/offline_associations.tsv.gzip",
    )
    parser.add_argument(
        "--common-phrases",
        type=Path,
        default=script_dir / "data/common_association_phrases.txt",
    )
    return parser.parse_args()


def download_file(relative_path: str, cache_dir: Path) -> Path:
    target = cache_dir / Path(relative_path).name
    if target.is_file():
        return target
    encoded_path = urllib.parse.quote(relative_path, safe="/")
    url = (
        f"https://api.github.com/repos/{UPSTREAM_REPOSITORY}/contents/{encoded_path}"
        f"?ref={UPSTREAM_COMMIT}"
    )
    request = urllib.request.Request(
        url,
        headers={"Accept": "application/vnd.github+json", "User-Agent": "miaoyan-ime-builder"},
    )
    cache_dir.mkdir(parents=True, exist_ok=True)
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                payload = json.load(response)
            target.write_bytes(base64.b64decode(payload["content"]))
            return target
        except Exception:
            if attempt == 3:
                raise
            time.sleep(attempt + 1)
    raise AssertionError("unreachable")


def source_file(relative_path: str, source_dir: Path | None, cache_dir: Path) -> Path:
    if source_dir is not None:
        direct = source_dir / relative_path
        flattened = source_dir / Path(relative_path).name
        return direct if direct.is_file() else flattened
    return download_file(relative_path, cache_dir)


class Simplifier:
    def __init__(self, project_dir: Path) -> None:
        opencc_dir = project_dir / "yuyansdk/src/main/assets/rime/opencc"
        self.characters: dict[str, str] = {}
        for line in (opencc_dir / "STCharacters.txt").read_text(encoding="utf-8").splitlines():
            fields = line.split("\t")
            if len(fields) < 2 or len(fields[0]) != 1:
                continue
            for traditional in fields[1].split():
                if len(traditional) == 1:
                    self.characters.setdefault(traditional, fields[0])

        self.phrase_trie: dict[str, dict] = {}
        for line in (opencc_dir / "STPhrases.txt").read_text(encoding="utf-8").splitlines():
            fields = line.split("\t")
            if len(fields) < 2:
                continue
            simplified = fields[0]
            for traditional in fields[1].split():
                if len(traditional) < 2:
                    continue
                node = self.phrase_trie
                for char in traditional:
                    node = node.setdefault(char, {})
                node.setdefault("", simplified)

    def convert(self, text: str) -> str:
        output: list[str] = []
        position = 0
        while position < len(text):
            node = self.phrase_trie
            cursor = position
            best: tuple[int, str] | None = None
            while cursor < len(text) and text[cursor] in node:
                node = node[text[cursor]]
                cursor += 1
                if "" in node:
                    best = cursor, node[""]
            if best is not None:
                position, replacement = best
                output.append(replacement)
            else:
                output.append(self.characters.get(text[position], text[position]))
                position += 1
        return "".join(output)


def paragraphs(value):
    if isinstance(value, dict):
        if isinstance(value.get("paragraphs"), list):
            yield from (item for item in value["paragraphs"] if isinstance(item, str))
        elif "content" in value:
            yield from paragraphs(value["content"])
    elif isinstance(value, list):
        for item in value:
            yield from paragraphs(item)


def add_candidate(index, key: str, kind: str, candidate: str) -> None:
    if len(key) < 2 or not candidate or key == candidate:
        return
    candidates = index.setdefault(key, [])
    item = kind, candidate
    if item not in candidates and sum(1 for existing in candidates if existing[0] == kind) < 3:
        candidates.append(item)


def add_daily_phrases(index, path: Path, simplifier: Simplifier) -> None:
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = simplifier.convert(raw_line.strip())
        if not line or line.startswith("#"):
            continue
        if "\t" in line:
            prefix, completion = line.split("\t", 1)
        else:
            prefix, completion = line[:2], line[2:]
        add_candidate(index, prefix, "P", completion)


def add_classics(index, path: Path, simplifier: Simplifier) -> int:
    document = json.loads(path.read_text(encoding="utf-8"))
    count = 0
    for paragraph in paragraphs(document):
        count += 1
        text = simplifier.convert(PARENTHETICAL_PATTERN.sub("", paragraph)).strip()
        clauses = [
            (match.group(1), match.group(2))
            for match in PUNCTUATION_PATTERN.finditer(text)
            if match.group(1)
        ]
        for position, (clause, _) in enumerate(clauses):
            if 3 <= len(clause) <= 40:
                add_candidate(index, clause[:2], "P", clause[2:])
            if position + 1 < len(clauses) and 2 <= len(clause) <= 40:
                next_clause = clauses[position + 1][0]
                if 1 <= len(next_clause) <= 40:
                    add_candidate(index, clause, "N", next_clause)
    return count


def main() -> None:
    args = arguments()
    project_dir = Path(__file__).resolve().parent.parent
    simplifier = Simplifier(project_dir)
    index: OrderedDict[str, list[tuple[str, str]]] = OrderedDict()

    # 日常用语优先于外部语料，保证常用候选排名稳定。
    add_daily_phrases(index, args.common_phrases, simplifier)
    paragraph_count = 0
    for relative_path in UPSTREAM_FILES:
        paragraph_count += add_classics(
            index,
            source_file(relative_path, args.source_dir, args.cache_dir),
            simplifier,
        )

    lines = []
    for key in sorted(index):
        for kind, candidate in index[key]:
            lines.append(f"{key}\t{kind}\t{candidate}\n")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0) as compressed:
            compressed.write("".join(lines).encode("utf-8"))
    print(
        f"generated {len(index)} keys / {len(lines)} candidates from "
        f"{paragraph_count} classical paragraphs: {args.output} ({args.output.stat().st_size} bytes)"
    )


if __name__ == "__main__":
    main()
