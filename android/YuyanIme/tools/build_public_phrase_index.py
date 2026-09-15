"""Build an exact membership index from checksum-locked public original rows."""
import argparse,hashlib,json,struct
from pathlib import Path
MAGIC=b'T9WORD1\0'
TABLES=('8105','base','ext','tencent','others')

def extract_words(source):
    words=set();body=False
    for line in source.splitlines():
        if line.strip()=='...':body=True;continue
        if not body or line.startswith('#'):continue
        cells=line.split('\t');text=cells[0]
        if len(cells)>=2 and 2<=len(text)<=30 and all(0x3400<=ord(c)<=0x9fff or 0x20000<=ord(c)<=0x323af for c in text):words.add(text)
    return words

def encode_index(words):
    rows=sorted(set(word.encode('utf-8') for word in words));offsets=[0]
    for row in rows:offsets.append(offsets[-1]+len(row))
    return MAGIC+struct.pack('<I',len(rows))+struct.pack('<'+str(len(offsets))+'I',*offsets)+b''.join(rows)

def build(source,lock,output):
    source=Path(source).resolve();manifest=json.loads(Path(lock).read_text());files={r['path']:r for r in manifest['files']}
    words=set()
    for name in TABLES:
        key=f'cn_dicts/{name}.dict.yaml';entry=files[key];data=(source/key).read_bytes()
        blob=hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
        if len(data)!=entry['size'] or hashlib.sha256(data).hexdigest()!=entry['sha256'] or blob!=entry['git_blob_sha1']:raise ValueError('source checksum mismatch: '+key)
        words.update(extract_words(data.decode('utf-8')))
    encoded=encode_index(words);Path(output).write_bytes(encoded)
    result={'repository':manifest['repository'],'revision':manifest['revision'],'entries':len(words),'size':len(encoded),'sha256':hashlib.sha256(encoded).hexdigest(),'format':'T9WORD1; uint32 LE count, count+1 relative offsets, sorted UTF-8 entries'}
    Path(str(output)+'.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');return result

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',required=True);p.add_argument('--lock',required=True);p.add_argument('--output',required=True);a=p.parse_args();print(json.dumps(build(a.source,a.lock,a.output),ensure_ascii=False))
