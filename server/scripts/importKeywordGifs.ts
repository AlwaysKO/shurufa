import { readFile, readdir, writeFile, rename, access } from 'node:fs/promises';
import { resolve, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { auditPrototypeGif } from '../src/expression/prototypeAudit.js';
import { keywordGifExclusion, type KeywordGifAsset } from '../src/expression/keywordGifLibrary.js';

// 仅补录最终输出报告，不递归抓取rejected/临时样片；不改变旧报告或原图。
async function main() {
 const root=fileURLToPath(new URL('../../',import.meta.url));
 const json=async(path:string,fallback:any=null):Promise<any>=>{try{return JSON.parse(await readFile(join(root,path),'utf8'));}catch(e){if((e as NodeJS.ErrnoException).code==='ENOENT')return fallback;throw e;}};
 const exists=async(path:string)=>{try{await access(join(root,path));return true;}catch{return false;}};
 const system=await json('server/.runtime/expression-assets/catalog.json');
 if(!system)throw new Error('先安装既有运行库，避免重复补录');
 const seen=new Set<string>(system.templates.map((x:any)=>x.sha256));
 const items:KeywordGifAsset[]=[]; const decisions:any[]=[];
 const reports:string[]=[];
 for(const d of await readdir(join(root,'artifacts/expression-character-trials')))if(/^(original-|reference-|web-original-)/.test(d))reports.push(`artifacts/expression-character-trials/${d}/output/report.json`);
 for(const d of await readdir(join(root,'artifacts/expression-batches')))reports.push(`artifacts/expression-batches/${d}/report.json`);
 // 最早的原型批次也核对，通常已经在184张系统库中。
 reports.push('artifacts/expression-prototypes/report.json');
 for(const reportPath of reports.sort()) {
  const report=await json(reportPath); if(!report)continue;
  const output=reportPath.slice(0,-'/report.json'.length);
  const batch=output.endsWith('/output')?output.slice(0,-7):output;
  const review=await json(batch+'/visual-review.json',{});
  const manifests=[batch+'/manifest.json',batch+'/source-manifest.json',batch+'/generation.json',`assets/expression/batches/${batch.split('/').pop()}/manifest.json`];
  let manifest:any={};for(const path of manifests){const found=await json(path);if(found){manifest=found;break;}}
  if(reportPath==='artifacts/expression-prototypes/report.json') manifest=await json('assets/expression/prototypes/manifest.json',{});
  for(const original of report.items??[]) {
   const fromManifest=(manifest.items??[]).find((x:any)=>x.id===original.id)??(manifest.id===original.id?manifest:{});
   const item={...fromManifest,...original};
   item.sourceType??=report.sourceType??manifest.sourceType;
   // 用户2026-09-16明确声明的许可仅覆盖reference-01两形象，不能推及reference-02。
   if(batch.endsWith('/reference-01-animated') && ['panda-really','mushroom-think'].includes(item.id) && await exists(batch+'/authorization-2026-09-16.md')) item.sourceType='licensed';
   const caption=item.caption??item.keyword;
   let sourceGif=output+`/gifs/${item.id}.gif`;
   if(item.id==='scene-rich-03-serious-v2')sourceGif=output+'/serious-v2.gif';
   const record:any={id:item.id,sourceReport:reportPath,sourceGif};
   let reason=keywordGifExclusion(item,review,manifest);
   if(item.id==='scene-rich-11-watch-line')reason='用户明确负面反馈，保留旧稿但不复活';
   if(!caption)reason??='缺少可确认的关键词';
   if(!(await exists(sourceGif)))reason??='无最终GIF输出';
   if(reason){decisions.push({...record,status:'excluded',reason});continue;}
   const bytes=await readFile(join(root,sourceGif));const sha=createHash('sha256').update(bytes).digest('hex');
   if(seen.has(sha)){decisions.push({...record,status:'already-present',sha256:sha});continue;}
   const metadata=item.metadata;
   if(!metadata || metadata.sha256!==sha){decisions.push({...record,status:'excluded',reason:'实际GIF与报告SHA不匹配或报告无SHA'});continue;}
   const audit=await auditPrototypeGif(bytes,{id:item.id,frameCount:metadata.pages,durationMs:metadata.durationMs});
   if(audit.issues.length){decisions.push({...record,status:'excluded',reason:'重新机器审计失败',issues:audit.issues});continue;}
   const id=`generated-${sha.slice(0,24)}`;
   items.push({id,type:'prebuilt',format:'gif',sourceType:item.sourceType,distribution:'remote',version:sha,sha256:sha,fileName:`generated/${id}.gif`,thumbnailFileName:null,width:240,height:240,keywords:[caption],emotions:[],embeddedText:caption,textSafeArea:null,layout:null,heat:0,sourceGif,sourceReport:reportPath,approval:{date:'2026-09-16',basis:'用户明确此前所有合格关键词成品入库，并授权后续默认通过；不是逐张观看证明'+(item.sourceType==='licensed'?'；形象许可依据 artifacts/expression-character-trials/reference-01-animated/authorization-2026-09-16.md 中用户声明，非独立法律核验':'')}});
   seen.add(sha);decisions.push({...record,status:'imported',catalogId:id,keyword:caption,sha256:sha,bytes:bytes.length});
  }
 }
 const target=join(root,'assets/expression/approved-keyword-gifs.json');
 const result={schemaVersion:1,approval:'2026-09-16 用户明确全量补录及后续默认入库',items};
 if(process.argv.includes('--apply')){await writeFile(target+'.tmp',JSON.stringify(result,null,2)+'\n');await rename(target+'.tmp',target);}
 const reportTarget=join(root,process.argv.includes('--apply') ? 'assets/expression/approvals/2026-09-16-keyword-gif-import.json' : 'server/.runtime/keyword-gif-import-dry-run.json');
 await writeFile(reportTarget,JSON.stringify({applied:process.argv.includes('--apply'),items:decisions},null,2)+'\n');
 console.log(JSON.stringify({applied:process.argv.includes('--apply'),imported:items.length,alreadyPresent:decisions.filter(x=>x.status==='already-present').length,excluded:decisions.filter(x=>x.status==='excluded').length,report:relative(root,reportTarget)}));
}
main().catch(error=>{console.error(error);process.exitCode=1;});
