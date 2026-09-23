"""Build bounded, mmap-friendly initial/prefix lookup from locked public sources."""
import argparse
import hashlib
import json
import re
import struct
import subprocess
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
        if (re.fullmatch(r'[\u4e00-\u9fff]{3,20}', text) and
                re.fullmatch(r'[a-z]+(?: [a-z]+)+', reading) and
                len(reading.split()) == len(text) and frequency.isdigit()):
            rows.append((text, reading, int(frequency)))
    return rows


def completion_rows(entries, phrases):
    unique = {}
    for text, reading, frequency in entries:
        syllables = reading.split()
        if len(text) != len(syllables) or not re.fullmatch(r'[a-z]+(?: [a-z]+)+', reading):
            continue
        keys = []
        if len(text) == 3:
            keys.append('a' + digits(''.join(s[0] for s in syllables)))
        if 5 <= len(text) <= 20 and text in phrases:
            full = ''.join(syllables)
            keys += ['p' + full, 'n' + digits(full)]
        for key in keys:
            item = (key, text, reading)
            unique[item] = max(frequency, unique.get(item, 0))
    return sorted(((*key, freq) for key, freq in unique.items()), key=lambda r: (r[0], -r[3], r[1], r[2]))


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
                'long_phrases': sum(row[0].startswith('p') for row in rows),
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
