import {readFile,mkdir,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {join} from 'node:path';
import {framePoseGrid} from '../src/expression/gridFraming.js';
import {ORIGINAL_WEB01_ITEMS,renderReferenceCharacterGif} from '../src/expression/referenceCharacterRenderer.js';
import {publishDirectoryAtomically} from '../src/expression/prototypePublication.js';

async function main(){
 if(process.argv.length>2)throw new Error('仅处理用户确认的两份固定新稿，不接受其他路径或批次');
 const trials=fileURLToPath(new URL('../../artifacts/expression-character-trials/',import.meta.url));
 const root=join(trials,'web-original-01-framed');
 const specs=[
  {id:'schnauzer-afternoon',source:'web-k005-a-clean-20260916/source/master.png',sha256:'5ee1f2e46bc50790339434694a8bf330c3db2c9ad37c067d08fb74896144c2b3',baselines:Array(12).fill(303) as number[]},
  {id:'man-afternoon',source:'web-k005-d-seated-20260916/source/master.png',sha256:'6362c8f7d36edaaf170ea1151fd7c217c7c036263a8a2f20ff6edda7863c3abb',baselines:[308,308,308,308,309,309,309,309,308,308,308,308]},
 ];
 const sha=(b:Buffer)=>createHash('sha256').update(b).digest('hex');
 const results: Array<{
  spec: (typeof specs)[number];
  framing: Awaited<ReturnType<typeof framePoseGrid>>;
  rendered: Awaited<ReturnType<typeof renderReferenceCharacterGif>>;
  provenance: unknown;
  prompt: string;
 }>=[];
 for(const spec of specs){
  const original=await readFile(join(trials,spec.source));
  if(sha(original)!==spec.sha256)throw new Error(`原图SHA不符：${spec.id}`);
  const framing=await framePoseGrid(original,{scale:0.8,targetBaselineRatio:0.69,baselines:spec.baselines});
  const item=ORIGINAL_WEB01_ITEMS.find(x=>x.id===spec.id)!;
  const rendered=await renderReferenceCharacterGif(framing.png,item);
  const provenance=JSON.parse(await readFile(join(trials,spec.source.replace('master.png','provenance.json')),'utf8'));
  if(provenance.deletion!=='confirmed-in-web-ui')throw new Error('来源对话清理未确认');
  results.push({spec,framing,rendered,provenance,prompt:await readFile(join(trials,spec.source.replace('master.png','prompt.txt')),'utf8')});
 }
 await mkdir(root,{recursive:true});
 await publishDirectoryAtomically(join(root,'output'),async out=>{
  for(const dir of ['gifs','thumbnails','framed-masters'])await mkdir(join(out,dir));
  for(const {spec,framing,rendered} of results){
   await writeFile(join(out,'framed-masters',spec.id+'.png'),framing.png);
   await writeFile(join(out,'gifs',spec.id+'.gif'),rendered.gif);
   await writeFile(join(out,'thumbnails',spec.id+'.webp'),rendered.thumbnail);
  }
  await writeFile(join(out,'report.json'),JSON.stringify({sourceType:'ai-original',status:'trial-only',staticCharacterReview:'pending',humanAnimationReview:'pending',publicationAllowed:false,items:results.map(({rendered:r})=>({...r.item,...r.audit,timeline:r.timeline}))},null,2)+'\n');
  await writeFile(join(out,'source-manifest.json'),JSON.stringify({authorization:'2026-09-16 用户允许两份新稿统一缩放、对齐脚底和补白；不修改原图或补画',items:results.map(({spec,framing,provenance,prompt})=>({...spec,provenance,prompt,framedSha256:sha(framing.png),transforms:framing.transforms,scaledSize:framing.scaledSize,cellSize:framing.cellSize,targetBaselineRatio:framing.targetBaselineRatio}))},null,2)+'\n');
  await writeFile(join(out,'preview.html'),'<!doctype html><meta charset="utf-8"><title>下午好构图整理·待审</title><h1>新增两张构图整理候选</h1><p>均待用户动态审核，未发布；原图与旧GIF不变。</p>'+results.map(({spec})=>`<h2>${spec.id}</h2><img width="240" height="240" src="gifs/${spec.id}.gif">`).join(''));
 });
 console.log(`2/2 构图整理及机器审计通过：${root}/output`);
}
main().catch(e=>{console.error(e);process.exitCode=1;});
