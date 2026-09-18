import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { framePoseGrid } from '../src/expression/gridFraming.js';
import { renderReferenceCharacterGif, ORIGINAL_WEB08_ITEMS } from '../src/expression/referenceCharacterRenderer.js';
import { publishDirectoryAtomically } from '../src/expression/prototypePublication.js';

if(process.argv.length>2)throw new Error('仅处理web08固定新稿，不接受参数');
const root=fileURLToPath(new URL('../../artifacts/expression-character-trials/web-original-08-animated/',import.meta.url));
const specs: {id:string;sha256:string;scale:number;baselines:number[]}[]=JSON.parse(await readFile(join(root,'framing.json'),'utf8'));
if(!Array.isArray(specs)||!specs.length||new Set(specs.map(s=>s.id)).size!==specs.length)throw new Error('构图清单为空或重复');
const results: Array<{
 spec:(typeof specs)[number];
 provenance:Record<string,unknown>;
 prompt:string;
 framing:Awaited<ReturnType<typeof framePoseGrid>>;
 rendered:Awaited<ReturnType<typeof renderReferenceCharacterGif>>;
}>=[];
for(const spec of specs){
 const item=ORIGINAL_WEB08_ITEMS.find(x=>x.id===spec.id);
 if(!item)throw new Error('非web08固定素材');
 const source=join(root,'sources',item.id,'source');
 const bytes=await readFile(join(source,'master.png'));
 const provenance=JSON.parse(await readFile(join(source,'provenance.json'),'utf8'));
 const prompt=await readFile(join(source,'prompt.txt'),'utf8');
 const sha=createHash('sha256').update(bytes).digest('hex');
 const downloadedAt=Date.parse(provenance.downloadedAt??'');
 const deletedAt=Date.parse(provenance.deletedAt??'');
 if(sha!==spec.sha256||sha!==provenance.sha256||provenance.sourceType!=='ai-original'
  ||provenance.status!=='downloaded-decodable'||!Number.isFinite(Date.parse(provenance.requestedDate??''))
  ||!Number.isFinite(downloadedAt)||!Number.isFinite(deletedAt)||deletedAt<downloadedAt
  ||provenance.deletionEvidence?.returnedTo!=='https://chatgpt.com/'
  ||provenance.deletionEvidence?.conversationLinkAbsent!==true||provenance.deletionEvidence?.confirmationDialogAbsent!==true
  ||provenance.deletion!=='confirmed-in-web-ui'||!prompt.trim()
  ||!/^https:\/\/chatgpt\.com\/c\/[a-f0-9-]+$/.test(provenance.sourceConversation??''))throw new Error(`来源、SHA或对话清理门禁失败：${item.id}`);
 const framing=await framePoseGrid(bytes,{scale:spec.scale,baselines:spec.baselines,targetBaselineRatio:0.69});
 const rendered=await renderReferenceCharacterGif(framing.png,item);
 results.push({spec,provenance,prompt,framing,rendered});
}
await publishDirectoryAtomically(join(root,'output'),async out=>{
 for(const d of ['gifs','thumbnails','poses','framed-masters'])await mkdir(join(out,d));
 for(const r of results){
  const id=r.spec.id;
  await writeFile(join(out,'gifs',id+'.gif'),r.rendered.gif);
  await writeFile(join(out,'thumbnails',id+'.webp'),r.rendered.thumbnail);
  await writeFile(join(out,'framed-masters',id+'.png'),r.framing.png);
  await mkdir(join(out,'poses',id));
  for(const [i,pose] of r.rendered.poses.entries())await writeFile(join(out,'poses',id,String(i+1).padStart(2,'0')+'.png'),pose);
 }
 await writeFile(join(out,'report.json'),JSON.stringify({sourceType:'ai-original',approvalBasis:'用户2026-09-16默认批准关键词成品入库；不是逐张观看证明',items:results.map(r=>({...r.rendered.item,...r.rendered.audit,timeline:r.rendered.timeline}))},null,2)+'\n');
 await writeFile(join(out,'source-manifest.json'),JSON.stringify({items:results.map(r=>({...r.spec,prompt:r.prompt,provenance:r.provenance,transforms:r.framing.transforms}))},null,2)+'\n');
});
console.log(JSON.stringify(results.map(r=>({id:r.spec.id,bytes:r.rendered.gif.length,sha256:r.rendered.audit.metadata.sha256,issues:r.rendered.audit.issues}))));
