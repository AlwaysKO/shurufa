"""只汇总实际完整记录；不存在的目标名次 -1 指本次窗口未见，不代表所有后页不存在。"""
import collections
import gzip
import json
import pathlib
import sys


def summarize(expected, rows):
    seen = set()
    result = collections.Counter()
    removed_examples = []
    missed_examples = []
    for row in rows:
        code = row['code']
        if code in seen or code not in expected:
            raise ValueError('重复或未知编码: ' + code)
        if set(row['ranks']) != expected[code]:
            raise ValueError('目标集合不符: ' + code)
        seen.add(code)
        result['codes'] += 1
        result['nativeFirstRemoved'] += row.get('nativeFirstRemoved', False)
        for text, (before, after) in row['ranks'].items():
            if before < -1 or after < -1:
                raise ValueError('非法名次')
            result['targets'] += 1
            result['nativeFound100'] += before >= 0
            result['afterFoundFirstPage'] += after >= 0
            result['nativeFirst'] += before == 0
            result['afterFirst'] += after == 0
            result['nativeTop5'] += 0 <= before < 5
            result['afterTop5'] += 0 <= after < 5
            result['removedFromNative100'] += before >= 0 and after < 0
            result['addedToNative100'] += before < 0 and after >= 0
            if before >= 0 and after < 0 and len(removed_examples) < 100:
                removed_examples.append(dict(code=code, text=text, nativeRank=before, afterTop5=row['afterTop5']))
            if after < 0 and len(missed_examples) < 100:
                missed_examples.append(dict(code=code, text=text, nativeRank=before, nativeTop5=row['nativeTop5'], afterTop5=row['afterTop5']))
    if seen != set(expected):
        raise ValueError(f'记录不完整: {len(seen)}/{len(expected)}')
    return dict(result, removedExamples=removed_examples, missedExamples=missed_examples)


def read_cases(path):
    expected = {}
    for line in pathlib.Path(path).read_text().splitlines():
        code, targets = line.split('\t')
        if code in expected:
            raise ValueError('输入编码重复: ' + code)
        expected[code] = set(targets.split('|'))
    return expected


if __name__ == '__main__':
    cases, records, output = sys.argv[1:]
    reader = gzip.open if records.endswith('.gz') else open
    with reader(records, 'rt', encoding='utf-8') as stream:
        summary = summarize(read_cases(cases), (json.loads(line) for line in stream))
    pathlib.Path(output).write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({k:v for k,v in summary.items() if not isinstance(v,list)},ensure_ascii=False))
