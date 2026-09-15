import csv, hashlib, json
from pathlib import Path
from collections import Counter
from PIL import Image, ImageSequence
root=Path('/home/ko/project/shurufa'); src=root/'assets/expression/batches/daily-02'; out=root/'artifacts/expression-batches/daily-02'
m=json.loads((src/'manifest.json').read_text());r=json.loads((out/'report.json').read_text())
assert (r['total'],r['pass'],r['fail'],r['complete'],r['humanReview'])==(40,40,0,True,'pending')
assert len(m['items'])==40
assert Counter((i['keyword'],i['distribution']) for i in m['items'])==Counter({(k,d):4 for k in m['keywords'] for d in ('bundled','remote')})
rows=[]
for i in m['items']:
 p=out/'gifs'/f"{i['id']}.gif"; b=p.read_bytes(); sha=hashlib.sha256(b).hexdigest()
 im=Image.open(p); n=im.n_frames; duration=sum(f.info.get('duration',0) for f in ImageSequence.Iterator(im))
 assert im.size==(240,240) and n==16 and duration==1600 and im.info.get('loop')==0 and len(b)<250*1024, i['id']
 audit=next(a for a in r['items'] if a['id']==i['id'])['metadata'];assert audit['sha256']==sha and audit['bytes']==len(b)
 thumb=Image.open(out/'thumbnails'/f"{i['id']}.webp");assert thumb.size==(240,240) and thumb.format=='WEBP'
 assert hashlib.sha256((src/i['masterFile']).read_bytes()).hexdigest()==i['generation']['masterSha256']
 assert i['sourceType']=='ai-original' and len(i['poseFiles'])==4
 for pose in i['poseFiles']: assert Image.open(src/pose).mode=='RGBA'
 rows.append([i['id'],i['keyword'],i['distribution'],240,240,n,duration,len(b),sha])
assert len(list((out/'gifs').glob('*.gif')))==40 and len(list((out/'thumbnails').glob('*.webp')))==40
assert len(list((src/'poses').glob('*/*.png')))==160
with (out/'inventory.tsv').open('w') as f:
 w=csv.writer(f,delimiter='\t',lineterminator='\n');w.writerow(['id','keyword','distribution','width','height','frames','duration_ms','bytes','sha256']);w.writerows(rows)
print(json.dumps({'total':len(rows),'pass':40,'fail':0,'maxBytes':max(x[7] for x in rows),'bundledBytes':sum(x[7] for x in rows if x[2]=='bundled'),'remoteBytes':sum(x[7] for x in rows if x[2]=='remote'),'thumbnails':40,'poses':160,'humanReview':'pending'},ensure_ascii=False,indent=2))
