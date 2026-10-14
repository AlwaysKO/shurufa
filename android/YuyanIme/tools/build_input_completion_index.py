"""Build bounded, mmap-friendly initial/prefix lookup from locked public sources."""
import argparse
import hashlib
import json
import re
import struct
import subprocess
from collections import Counter
from pathlib import Path

from generate_offline_associations import Simplifier, PUNCTUATION_PATTERN

KEYS = {c: str(i) for i, letters in enumerate(('abc', 'def', 'ghi', 'jkl', 'mno', 'pqrs', 'tuv', 'wxyz'), 2) for c in letters}


def digits(pinyin):
    return ''.join(KEYS[c] for c in pinyin)


def parse_readings(source):
    rows = []
    body = False
    for line in source.splitlines():
        if line.strip() == '...':
            body = True
            continue
        fields = line.split('\t')
        if not body or len(fields) < 3:
            continue
        text, reading, frequency = fields[:3]
        if (re.fullmatch(r'[\u4e00-\u9fff]{2,20}', text) and
                re.fullmatch(r'[a-z]+(?: [a-z]+)+', reading) and
                len(reading.split()) == len(text) and frequency.isdigit()):
            rows.append((text, reading, int(frequency)))
    return rows


def completion_rows(entries, phrases):
    unique = {}
    for text, reading, frequency in entries:
        syllables = reading.split()
        if (not re.fullmatch(r'[\u4e00-\u9fff]{2,20}', text) or
                len(text) != len(syllables) or not re.fullmatch(r'[a-z]+(?: [a-z]+)+', reading)):
            continue
        keys = []
        if len(text) in (2, 3):
            initials = ''.join(s[0] for s in syllables)
            keys += ['a' + digits(initials), 'i' + initials]
            mixed = syllables[0][0] + syllables[1] if len(text) == 2 else syllables[0] + syllables[1][0] + syllables[2]
            keys += ['m' + mixed, 'm' + digits(mixed)]
        if len(text) in (2, 3) or (5 <= len(text) <= 20 and text in phrases):
            full = ''.join(syllables)
            keys += ['p' + full, 'n' + digits(full)]
        for key in keys:
            item = (key, text, reading)
            unique[item] = max(frequency, unique.get(item, 0))
    ordered = sorted(((*key, freq) for key, freq in unique.items()), key=lambda r: (r[0], -r[3], r[1], r[2]))
    rows = []
    counts = {}
    for row in ordered:
        key = row[0]
        limit = 32 if key.startswith('i') else (64 if key.startswith('a') and len(key) == 3 else None)
        count = counts.get(key, 0)
        if limit is None or count < limit:
            rows.append(row)
        counts[key] = count + 1
    return sorted(rows + wide_prefix_rows(rows), key=lambda r: (r[0], -r[3], r[1], r[2]))


def wide_prefix_rows(rows):
    searchable = [row for row in rows if row[0][0] in 'pnm']
    # Only descend into wide parents: a narrow prefix can never have a wide child.
    depth = 4  # channel byte plus at least three input characters
    counts = Counter(row[0][:depth] for row in searchable if len(row[0]) >= depth)
    wide = set()
    while counts:
        parents = {key for key, count in counts.items() if count > 512}
        if not parents:
            break
        wide.update(parents)
        counts = Counter(row[0][:depth + 1] for row in searchable
                         if len(row[0]) > depth and row[0][:depth] in parents)
        depth += 1
    fallback = {}
    for key, text, reading, frequency in searchable:
        syllables = reading.split()
        if key[0] == 'm':
            head = syllables[0][0] if len(syllables) == 2 else syllables[0] + syllables[1][0]
        else:
            head = ''.join(syllables[:-1])
        if key[1:].isdigit():
            head = digits(head)
        for length in range(max(3, len(head) + 1), len(key)):
            if key[0] == "m" and len(syllables) == 2 and length + 1 != len(key):
                continue
            if key[:length + 1] in wide:
                item = ('b' + key[1:length + 1], text, reading)
                fallback[item] = max(frequency, fallback.get(item, 0))
    ordered = sorted(((*item, freq) for item, freq in fallback.items()),
                     key=lambda r: (r[0], -r[3], r[1], r[2]))
    counts = Counter()
    result = []
    seen_words = set()
    for row in ordered:
        word = (row[0], row[1])
        if counts[row[0]] < 64 and word not in seen_words:
            result.append(row)
            seen_words.add(word)
            counts[row[0]] += 1
    return result


def encode_index(rows):
    values = ['\t'.join(map(str, row)).encode('utf-8') for row in rows]
    offsets = [0]
    for row in values:
        offsets.append(offsets[-1] + len(row))
    return b'T9COMP1\0' + struct.pack('<I', len(values)) + struct.pack('<' + str(len(offsets)) + 'I', *offsets) + b''.join(values)


def strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for child in value.values():
            yield from strings(child)
    elif isinstance(value, list):
        for child in value:
            yield from strings(child)


def checked_read(path, expected):
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != expected:
        raise ValueError('source checksum mismatch: ' + str(path))
    return data.decode('utf-8')


def build(source, poetry, output):
    project = Path(__file__).resolve().parent.parent
    assets = project / 'yuyansdk/src/main/assets/completion'
    lock = json.loads((assets / 'public-phrase-sources/source-lock.json').read_text())
    files = {f['path']: f for f in lock['files']}
    base = checked_read(source / 'cn_dicts/base.dict.yaml', files['cn_dicts/base.dict.yaml']['sha256'])
    entries = parse_readings(base)
    # 高频基础表有原始读音；扩展表仅补三字行政地名，自动注音以最低先验进入。
    tencent = checked_read(source / 'cn_dicts/tencent.dict.yaml', files['cn_dicts/tencent.dict.yaml']['sha256'])
    places = sorted({line.split('\t')[0] for line in tencent.splitlines()
                     if re.fullmatch(r'[\u4e00-\u9fff]{2}[镇县市区]', line.split('\t')[0])})
    existing = {text for text, _, _ in entries}
    places = [text for text in places if text not in existing]
    generated = json.loads(subprocess.check_output(
        ['node', str(project / 'tools/input_completion_readings.mjs')], input=json.dumps(places).encode()))
    entries.extend((text, reading, 0) for text, reading in generated)
    poetry_lock = json.loads((assets / 'input-completion-sources.json').read_text())
    simplifier = Simplifier(project)
    phrases = set()
    for item in poetry_lock['files']:
        document = json.loads(checked_read(poetry / Path(item['path']).name, item['sha256']))
        if item.get('authors'):
            document = [poem for poem in document if poem.get('author') in item['authors']]
        for paragraph in strings(document):
            for match in PUNCTUATION_PATTERN.finditer(simplifier.convert(paragraph)):
                if re.fullmatch(r'[\u4e00-\u9fff]{5,20}', match[1]):
                    phrases.add(match[1])
    daily = project / 'tools/data/common_association_phrases.txt'
    phrases.update(line for line in daily.read_text().splitlines() if re.fullmatch(r'[\u4e00-\u9fff]{5,20}', line))
    rows = completion_rows(entries, phrases)
    data = encode_index(rows)
    output.write_bytes(data)
    manifest = {'rime_revision': lock['revision'], 'poetry_revision': poetry_lock['revision'],
                'daily_sha256': hashlib.sha256(daily.read_bytes()).hexdigest(),
                'rows': len(rows), 'short_words': sum(row[0].startswith('a') for row in rows),
                'letter_initial_rows': sum(row[0].startswith('i') for row in rows),
                'mixed_reading_rows': sum(row[0].startswith('m') for row in rows),
                'wide_prefix_rows': sum(row[0].startswith('b') for row in rows),
                'wide_prefix_buckets': len({row[0] for row in rows if row[0].startswith('b')}),
                'short_full_reading_rows': sum(row[0].startswith('p') and len(row[1]) <= 3 for row in rows),
                'long_phrases': sum(row[0].startswith('p') and len(row[1]) >= 5 for row in rows),
                'generated_place_readings': len(generated), 'size': len(data),
                'sha256': hashlib.sha256(data).hexdigest()}
    Path(str(output) + '.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--poetry', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(build(args.source, args.poetry, args.output), ensure_ascii=False))
