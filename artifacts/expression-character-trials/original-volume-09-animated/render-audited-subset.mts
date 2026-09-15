/** 本批隔离输出：逐项调用原审计，失败项仅记录，不输出 GIF；不替代全批 CLI。 */
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {join} from 'node:path';
import {ORIGINAL_VOLUME09_ITEMS,renderReferenceCharacterGif,resolveReferenceMaster} from '../../../server/src/expression/referenceCharacterRenderer.js';
import {publishDirectoryAtomically} from '../../../server/src/expression/prototypePublication.js';
const root=fileURLToPath(new URL('./',import.meta.url));
const rendered:Awaited<ReturnType<typeof renderReferenceCharacterGif>>[]=[];
const failed:{id:string;caption:string;error:string}[]=[];
for(const item of ORIGINAL_VOLUME09_ITEMS){
 try {rendered.push(await renderReferenceCharacterGif(await readFile(await resolveReferenceMaster(root,item.masterFile)),item));}
 catch(error){
  if(item.id!=='duck-lost'||!(error instanceof Error)||!error.message.startsWith('GIF审计失败：'))throw error;
  const issues=JSON.parse(error.message.slice('GIF审计失败：'.length));
  if(!Array.isArray(issues)||issues.length!==1||issues[0].id!=='duck-lost'||issues[0].field!=='loopClosure')throw error;
  failed.push({id:item.id,caption:item.caption,error:String(error)});
 }
}
if(rendered.length!==11||failed.length!==1||failed[0].id!=='duck-lost')throw new Error('审计结果变化，请重新审核分流，不自动扩大输出');
await publishDirectoryAtomically(join(root,'output'),async output=>{
 for(const dir of ['gifs','thumbnails','poses'])await mkdir(join(output,dir));
 for(const r of rendered){
  await writeFile(join(output,'gifs',r.item.id+'.gif'),r.gif);
  await writeFile(join(output,'thumbnails',r.item.id+'.webp'),r.thumbnail);
  await mkdir(join(output,'poses',r.item.id));
  await Promise.all(r.poses.map((pose,i)=>writeFile(join(output,'poses',r.item.id,`${i+1}.png`),pose)));
 }
 await writeFile(join(output,'report.json'),JSON.stringify({status:'trial-only',sourceType:'ai-original',licenseStatus:'pending-review',publicationAllowed:false,staticCharacterReview:'pending',humanAnimationReview:'pending',total:12,pass:rendered.length,fail:failed.length,failed,items:rendered.map(r=>({...r.item,...r.audit,timeline:r.timeline}))},null,2)+'\n');
});
console.log('12 目标：11 机器通过并输出，1 失败隔离；形象及动态仍待审');
