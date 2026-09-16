import {readFile,mkdir,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {join} from 'node:path';
import {framePoseGrid} from '../src/expression/gridFraming.js';
import {ORIGINAL_WEB02_ITEMS,renderReferenceCharacterGif} from '../src/expression/referenceCharacterRenderer.js';
import {publishDirectoryAtomically} from '../src/expression/prototypePublication.js';

async function main(){
 if(process.argv.length>2)throw new Error('仅处理web02固定新稿，不接受其他路径或批次');
 const trials=fileURLToPath(new URL('../../artifacts/expression-character-trials/',import.meta.url));
 const root=join(trials,'web-original-02-animated');
 const specs=[
  {
    "id": "siamese-evening",
    "source": "web-original-02-animated/sources/siamese-evening-v2/source/master.png",
    "sha256": "24c839d5ae8c88670772033c29939d877e0b1af9f2bcfbb4986cb1d6b4ad4f4d",
    "baselines": [
      278,
      278,
      278,
      278,
      277,
      277,
      277,
      277,
      277,
      277,
      277,
      277
    ],
    "scale": 0.8
  },
  {
    "id": "hedgehog-evening",
    "source": "web-original-02-animated/sources/hedgehog-evening/source/master.png",
    "sha256": "2939b22b031c71ef8f86c836e35f2b784018e78c95ed6f0ce818408dfa3e93e3",
    "baselines": [
      276,
      276,
      276,
      276,
      275,
      275,
      275,
      275,
      273,
      273,
      273,
      273
    ],
    "scale": 0.8
  },
  {
    "id": "beaver-evening",
    "source": "web-original-02-animated/sources/beaver-evening/source/master.png",
    "sha256": "8f2b8cfa60023cfda60c8951d43e90fecbf0c8fd8d47792e2e7802160b63a6df",
    "baselines": [
      264,
      263,
      264,
      264,
      254,
      254,
      254,
      254,
      238,
      238,
      238,
      237
    ],
    "scale": 0.8
  },
  {
    "id": "man-evening",
    "source": "web-original-02-animated/sources/man-evening-v2/source/master.png",
    "sha256": "cb1ad6dead0bcc24f664e228a8c61b4e128ff0c55c6e87efd964821f09fbee02",
    "baselines": [
      331,
      331,
      331,
      331,
      331,
      331,
      331,
      331,
      331,
      331,
      331,
      331
    ],
    "scale": 0.74
  },
  {
    "id": "puppy-noon",
    "source": "web-original-02-animated/sources/puppy-noon-v2/source/master.png",
    "sha256": "cad2c13d7f3e325570a4d6332adbf1ba663a7a015b7af22818b03a87b6b11a51",
    "baselines": [
      318,
      319,
      319,
      319,
      316,
      316,
      316,
      316,
      315,
      315,
      315,
      315
    ],
    "scale": 0.78
  },
  {
    "id": "sparrow-noon",
    "source": "web-original-02-animated/sources/sparrow-noon-v2/source/master.png",
    "sha256": "75e90b877debe0aabaf102e8413cda5ca9444de837b048d539342b57413772b8",
    "baselines": [
      310,
      310,
      310,
      310,
      299,
      299,
      299,
      299,
      287,
      287,
      287,
      287
    ],
    "scale": 0.8
  },
  {
    "id": "fawn-noon",
    "source": "web-original-02-animated/sources/fawn-noon/source/master.png",
    "sha256": "dce80b3836efc103f515d6fb06808bc4d41739f0bed936542e363aeed05378c1",
    "baselines": [
      285,
      285,
      285,
      285,
      285,
      285,
      285,
      285,
      284,
      284,
      284,
      284
    ],
    "scale": 0.8
  },
  {
    "id": "man-noon",
    "source": "web-original-02-animated/sources/man-noon/source/master.png",
    "sha256": "9e6b22553c6f183eeb85ddbc4d6f40bbeca6c1f003f4aaa07dba007a13798fbb",
    "baselines": [
      319,
      319,
      320,
      322,
      317,
      317,
      318,
      317,
      316,
      316,
      315,
      314
    ],
    "scale": 0.76
  }
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
  const framing=await framePoseGrid(original,{scale:spec.scale,targetBaselineRatio:0.69,baselines:spec.baselines});
  const item=ORIGINAL_WEB02_ITEMS.find(x=>x.id===spec.id)!;
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
  await writeFile(join(out,'source-manifest.json'),JSON.stringify({authorization:'2026-09-16 用户连续制作新稿，沿用已允许的统一缩放、对齐脚底和补白；不修改原图或补画',items:results.map(({spec,framing,provenance,prompt})=>({...spec,provenance,prompt,framedSha256:sha(framing.png),transforms:framing.transforms,scaledSize:framing.scaledSize,cellSize:framing.cellSize,targetBaselineRatio:framing.targetBaselineRatio}))},null,2)+'\n');
  await writeFile(join(out,'preview.html'),'<!doctype html><meta charset="utf-8"><title>晚上好与中午好·待审</title><h1>晚上好与中午好：新候选</h1><p>均待用户动态审核，未发布；原图与旧GIF不变。</p>'+results.map(({spec})=>`<h2>${spec.id}</h2><img width="240" height="240" src="gifs/${spec.id}.gif">`).join(''));
 });
 console.log(`${results.length}/${specs.length} 构图整理及机器审计通过：${root}/output`);
}
main().catch(e=>{console.error(e);process.exitCode=1;});
