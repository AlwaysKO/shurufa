import { it, expect, vi, afterEach } from 'vitest';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { readKeywordGifCatalog, resolveKeywordGifFile, mergeKeywordGifCatalog } from './keywordGifLibrary.js';
let root='';
afterEach(async()=>{vi.restoreAllMocks();if(root)await rm(root,{recursive:true,force:true});});
async function fixture(){root=await mkdtemp(join(tmpdir(),'keyword-gifs-'));await mkdir(join(root,'server'));await mkdir(join(root,'assets/expression'),{recursive:true});await mkdir(join(root,'artifacts/gifs'),{recursive:true});vi.spyOn(process,'cwd').mockReturnValue(join(root,'server'));}
const item={id:'generated-test',type:'prebuilt',format:'gif',sourceType:'ai-original',sha256:'a'.repeat(64),version:'a'.repeat(64),fileName:'generated/generated-test.gif',thumbnailFileName:null,width:240,height:240,keywords:['晚上好'],emotions:[],embeddedText:'晚上好',textSafeArea:null,layout:null,heat:0,sourceGif:'artifacts/gifs/test.gif'};
it('缺少补录清单保持空，不改变已有系统目录',async()=>{await fixture();expect(await readKeywordGifCatalog()).toEqual([]);});
it('只解析清单内安全路径并按SHA去重，不信任请求路径',async()=>{await fixture();await writeFile(join(root,'assets/expression/approved-keyword-gifs.json'),JSON.stringify({items:[item]}));await writeFile(join(root,item.sourceGif),'GIF89a');expect(await resolveKeywordGifFile(item.id,'gif')).toBe(join(root,item.sourceGif));expect(await resolveKeywordGifFile('../secret','gif')).toBeNull();expect(mergeKeywordGifCatalog([item] as any,[item] as any)).toHaveLength(1);});
it('拒绝越界的清单路径',async()=>{await fixture();await writeFile(join(root,'assets/expression/approved-keyword-gifs.json'),JSON.stringify({items:[{...item,sourceGif:'../secret'}]}));await expect(resolveKeywordGifFile(item.id,'gif')).rejects.toThrow();});
it('明确失败/废稿或无授权参考不能默认补录，待人工状态可按新授权入库', async()=>{
 const { keywordGifExclusion }=await import('./keywordGifLibrary.js');
 expect(keywordGifExclusion({id:'a',sourceType:'ai-original',issues:[]},{needsRework:['a']},{})).toBeTruthy();
 expect(keywordGifExclusion({id:'a',sourceType:'ai-original',issues:[]},{},{items:[{id:'a',visualTriage:'needs-rework'}]})).toBeTruthy();
 expect(keywordGifExclusion({id:'a',sourceType:'user-provided-reference',issues:[]},{},{})).toBeTruthy();
 expect(keywordGifExclusion({id:'a',sourceType:'ai-original',issues:[],publicationAllowed:false},{},{})).toBeNull();
});

it('拒绝允许目录内部的..逃逸，即使目标还在仓库内',async()=>{await fixture();await writeFile(join(root,'server/private.gif'),'private');await writeFile(join(root,'assets/expression/approved-keyword-gifs.json'),JSON.stringify({items:[{...item,sourceGif:'artifacts/../server/private.gif'}]}));await expect(resolveKeywordGifFile(item.id,'gif')).rejects.toThrow();});
