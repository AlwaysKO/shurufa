#!/usr/bin/env python3
"""生成两端一致的标题词典；固定上游文件，无网络依赖。"""
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'third_party/opencc'
entries = {}
for name in ('TSCharacters.txt', 'TSPhrases.txt'):
    for line in (SOURCE / name).read_text().splitlines():
        if not line or line.startswith('#'):
            continue
        key, values = line.split('\t')
        entries[key] = values.split(' ')[0]
entries = dict(sorted(entries.items()))
widths = {}
for key in entries:
    if len(key) > 1:
        widths[key[0]] = max(widths.get(key[0], 1), len(key))
body = ''.join(f'{key}\t{value}\n' for key, value in entries.items())
sha = hashlib.sha256(body.encode()).hexdigest()
header = f'Generated from OpenCC ver.1.1.9; TSV SHA256 {sha}; do not edit by hand.'
license_text = (SOURCE / 'LICENSE').read_text()
data = json.dumps(entries, ensure_ascii=False, separators=(',', ':'))
limits = json.dumps(widths, ensure_ascii=False, separators=(',', ':'))
sql = f'''-- {header}
-- Pure display/grouping conversion. Never updates conversation/message rows.
/* OpenCC license follows:
{license_text}
*/
CREATE OR REPLACE FUNCTION chat_title_to_simplified(raw text)
RETURNS text LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE AS $function$
DECLARE
    dictionary CONSTANT jsonb := $dictionary${data}$dictionary$::jsonb;
    phrase_widths CONSTANT jsonb := $widths${limits}$widths$::jsonb;
    pos integer := 1;
    total integer := char_length(raw);
    width integer;
    matched_width integer;
    replacement text;
    result text := '';
BEGIN
    WHILE pos <= total LOOP
        replacement := NULL;
        matched_width := 1;
        FOR width IN REVERSE LEAST(COALESCE((phrase_widths ->> substr(raw,pos,1))::integer,1), total-pos+1)..1 LOOP
            replacement := dictionary ->> substr(raw,pos,width);
            IF replacement IS NOT NULL THEN
                matched_width := width;
                EXIT;
            END IF;
        END LOOP;
        result := result || COALESCE(replacement,substr(raw,pos,1));
        pos := pos + matched_width;
    END LOOP;
    RETURN result;
END;
$function$;
'''
outputs = {
    ROOT / 'android/YuyanIme/yuyansdk/src/main/resources/chat-title-t2s.tsv': '# '+header+'\n'+body,
    ROOT / 'android/YuyanIme/yuyansdk/src/main/resources/opencc-title-LICENSE.txt': license_text,
    ROOT / 'server/migrations/042_chat_title_simplified.sql': sql,
}
for path, value in outputs.items():
    if '--check' in sys.argv:
        if not path.exists() or path.read_text() != value:
            raise SystemExit(f'词典生成物不一致：{path.relative_to(ROOT)}')
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)
print(f'词典一致：{len(entries)}条，SHA256={sha}')
