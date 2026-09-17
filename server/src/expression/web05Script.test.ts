import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { expect, it } from 'vitest';
import { mkdtemp, mkdir, writeFile, readFile, copyFile, symlink, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';

it('web05 script refuses arbitrary paths before reading or writing any batch',()=>{
 const result=spawnSync(process.execPath,['--import','tsx','scripts/render-web05-framed.ts','../other-batch'],{
  cwd:fileURLToPath(new URL('../../',import.meta.url)),encoding:'utf8',timeout:10000,
 });
 expect(result.status).not.toBe(0);
 expect(result.stderr).toContain('仅处理web05固定新稿，不接受参数');
});

it('rejects missing or contradictory cleanup evidence and failed sources without replacing output',async()=>{
 const server=fileURLToPath(new URL('../../',import.meta.url));
 const tmp=await mkdtemp(join(tmpdir(),'web05-gate-'));
 const batch=join(tmp,'artifacts/expression-character-trials/web-original-05-animated');
 const source=join(batch,'sources/fox-ofcourse/source');
 const bytes=Buffer.from('invalid image must never reach the decoder');
 const sha=createHash('sha256').update(bytes).digest('hex');
 try{
  await mkdir(join(tmp,'server/scripts'),{recursive:true});await mkdir(source,{recursive:true});await mkdir(join(batch,'output'));
  await writeFile(join(tmp,'package.json'),'{}');
  await writeFile(join(tmp,'server/package.json'),'{"type":"module"}');
  await symlink(join(server,'src'),join(tmp,'server/src'),'dir');
  await copyFile(join(server,'scripts/render-web05-framed.ts'),join(tmp,'server/scripts/render-web05-framed.ts'));
  await writeFile(join(source,'master.png'),bytes);await writeFile(join(source,'prompt.txt'),'original generation prompt');
  await writeFile(join(batch,'framing.json'),JSON.stringify([{id:'fox-ofcourse',sha256:sha,scale:0.78,baselines:Array(12).fill(300)}]));
  await writeFile(join(batch,'output/sentinel'),'preserve existing output');
  const valid={sourceType:'ai-original',sha256:sha,sourceConversation:'https://chatgpt.com/c/12345678-1234-1234-1234-123456789abc',status:'downloaded-decodable',requestedDate:'2026-09-17',downloadedAt:'2026-09-17T01:00:00Z',deletedAt:'2026-09-17T01:01:00Z',deletion:'confirmed-in-web-ui',deletionEvidence:{returnedTo:'https://chatgpt.com/',conversationLinkAbsent:true,confirmationDialogAbsent:true}};
  for(const patch of [{status:'rejected'},{status:'failed'},{deletionEvidence:null},{deletionEvidence:{...valid.deletionEvidence,conversationLinkAbsent:false}},{requestedDate:''},{downloadedAt:''},{deletedAt:'2026-09-17T00:59:00Z'},{sha256:'0'.repeat(64)},{sourceType:'unknown'}]){
   await writeFile(join(source,'provenance.json'),JSON.stringify({...valid,...patch}));
   const result=spawnSync(process.execPath,['--import','tsx',join(tmp,'server/scripts/render-web05-framed.ts')],{cwd:server,encoding:'utf8',timeout:10000});
   expect(result.status).not.toBe(0);
   expect(result.stderr).toContain('来源、SHA或对话清理门禁失败');
   expect(await readFile(join(batch,'output/sentinel'),'utf8')).toBe('preserve existing output');
  }
 }finally{await rm(tmp,{recursive:true,force:true});}
},30000);
