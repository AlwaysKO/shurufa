#!/usr/bin/env python3
"""Derive bounded fallback words/readings solely from the existing public T9 lexicon."""
import argparse
import collections
import gzip
from pathlib import Path


def build(source: Path, output: Path):
    words = {}
    singles = collections.Counter()
    with gzip.open(source, 'rt', encoding='utf-8') as stream:
        for line in stream:
            fields = line.rstrip('\n').split('\t')
            if len(fields) != 3:
                continue
            text, reading, frequency = fields
            parts = reading.split(' ')
            if not 2 <= len(text) <= 4 or len(parts) != len(text):
                continue
            if not all('\u3400' <= char <= '\u9fff' for char in text):
                continue
            if not all(part.isascii() and part.isalpha() and part.islower() for part in parts):
                continue
            frequency = max(0, int(frequency))
            words[text, reading] = max(words.get((text, reading), 0), frequency)
            for char, syllable in zip(text, parts):
                singles[char, syllable] += frequency
    selected = sorted(words.items(), key=lambda item: (-item[1], item[0]))[:20000]
    # Keep homophones as distinct text/reading pairs. Runtime selects four per code.
    rows = [(text, reading, frequency) for (text, reading), frequency in selected]
    rows += [(text, reading, frequency) for (text, reading), frequency in sorted(singles.items())]
    raw = ''.join(f'{text}\t{reading}\t{frequency}\n' for text, reading, frequency in rows).encode()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(gzip.compress(raw, compresslevel=9, mtime=0))
    if output.stat().st_size >= 1_000_000:
        raise ValueError('Fallback asset exceeds 1 MB budget')
    print(f'words={len(selected)} single_readings={len(singles)} bytes={output.stat().st_size}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    root = Path(__file__).resolve().parents[1]
    parser.add_argument('--source', type=Path, default=root / 'yuyansdk/src/main/assets/completion/t9_lexicon.tsv.gzip')
    parser.add_argument('--output', type=Path, default=root / 'yuyansdk/src/main/assets/completion/sentence_fallback.tsv.gzip')
    args = parser.parse_args()
    build(args.source, args.output)
