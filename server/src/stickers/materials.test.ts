import { newDb } from 'pg-mem';
import * as files from 'node:fs/promises';
import { mkdtemp, mkdir, writeFile, rm, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { afterEach, expect, it, vi } from 'vitest';
import { importMaterial, listMaterials, matchMaterials } from './materials.js';
vi.mock('node:fs/promises', async importOriginal => ({...await importOriginal<typeof import('node:fs/promises')>()}));
const gif = Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7','base64');
const sha = createHash('sha256').update(gif).digest('hex');
const roots: string[]=[];
async function fixture() { const root=await mkdtemp(join(tmpdir(),'materials-unit-')); roots.push(root); await mkdir(join(root,'uploads/stickers'),{recursive:true}); return root; }
afterEach(async()=>{vi.restoreAllMocks();for(const root of roots.splice(0)) await rm(root,{recursive:true,force:true});});
function database(rows:any[]) { return { query: async(sql:string,values?:unknown[])=>{ if(sql.startsWith('UPDATE sticker')) { rows.find(r=>r.id===values![1]).sha256=values![0]; return {rows:[]}; } return {rows}; } } as any; }
it('按原图哈希聚合历史多行及多词，改名和缺失历史sha不影响',async()=>{
 const root=await fixture(); await writeFile(join(root,'uploads/stickers/a.gif'),gif); await writeFile(join(root,'uploads/stickers/renamed.gif'),gif);
 const rows=[{id:1,keywords:'A,B',file_name:'a.gif',format:'gif',sha256:sha},{id:2,keywords:'B,C',file_name:'renamed.gif',format:'gif',sha256:null}];
 const result=await listMaterials(database(rows),{},root);
 expect(result.items).toHaveLength(1); expect(result.items[0]).toMatchObject({sha256:sha,ids:[1,2],keywords:['A','B','C'],assigned:true}); expect(rows[1].sha256).toBe(sha);
});
it('缺图和越界symlink只能警告，不能返回existing',async()=>{
 const root=await fixture(); await writeFile(join(root,'outside.gif'),gif); await symlink(join(root,'outside.gif'),join(root,'uploads/stickers/link.gif'));
 const db=database([{id:1,keywords:'A',file_name:'missing.gif',sha256:sha},{id:2,keywords:'B',file_name:'link.gif',sha256:sha}]);
 const result=await matchMaterials(db,[sha],root); expect(result.items).toEqual([{sha256:sha,status:'unavailable'}]); expect(result.warnings).toHaveLength(2);
});
it('拒绝无效指纹及超量匹配',async()=>{ await expect(matchMaterials(database([]),[' '])).rejects.toThrow(); await expect(matchMaterials(database([]),Array(501).fill(sha))).rejects.toThrow(); });
it('缺失副本仍保留历史归属，全部副本缺失返回unavailable而非missing',async()=>{
 const root=await fixture(); await writeFile(join(root,'uploads/stickers/good.gif'),gif);
 const rows=[{id:1,keywords:'A',file_name:'missing.gif',sha256:sha},{id:2,keywords:'B',file_name:'good.gif',sha256:sha}];
 const result=await matchMaterials(database(rows),[sha],root); expect(result.items[0]).toMatchObject({status:'existing',material:{ids:[1,2],keywords:['A','B']}}); expect(result.warnings).toHaveLength(1);
 await rm(join(root,'uploads/stickers/good.gif'));
 expect((await matchMaterials(database(rows),[sha],root)).items[0].status).toBe('unavailable');
});
it('错SHA的旧行不能混入已校验同哈希的历史归属',async()=>{
 const root=await fixture();await writeFile(join(root,'uploads/stickers/good.gif'),gif);await writeFile(join(root,'uploads/stickers/wrong.gif'),Buffer.from('other'));
 const result=await matchMaterials(database([{id:1,keywords:'A',file_name:'good.gif',sha256:sha},{id:2,keywords:'B',file_name:'wrong.gif',sha256:sha}]),[sha],root);
 expect(result.items[0]).toMatchObject({status:'existing',material:{ids:[1],keywords:['A']}}); expect(result.warnings).toHaveLength(1);
});

it('写文件中途失败仍返回仅本请求拥有的清理路径',async()=>{
 const root=await fixture(),created:string[]=[];
 const fail=async()=>{throw Error('disk full');};
 vi.spyOn(files,'writeFile').mockImplementation(fail);
 vi.spyOn(files,'open').mockResolvedValue({writeFile:fail,close:async()=>{}} as any);
 await expect(importMaterial(database([]),{buffer:gif,filename:'x.gif'},created,root)).rejects.toThrow('disk full');
 expect(created).toHaveLength(1); expect(created[0].startsWith(join(root,'uploads/stickers')+'/')).toBe(true);
});

it('单SHA匹配只读取该SHA和缺SHA历史原图，不扫描其他已知SHA文件',async()=>{
 const root=await fixture(); const other=Buffer.from(gif);other[13]=127;
 const otherSha=createHash('sha256').update(other).digest('hex');
 for(const [name,bytes] of [['target.gif',gif],['other.gif',other],['legacy-same.gif',gif],['legacy-other.gif',other]] as const) await writeFile(join(root,'uploads/stickers',name),bytes);
 const db=newDb(),pool=new(db.adapters.createPg().Pool)();
 try {
  await pool.query('CREATE TABLE sticker(id INT, keywords TEXT, file_name TEXT, format TEXT, width INT, height INT, sha256 TEXT)');
  await pool.query("INSERT INTO sticker VALUES(1,'A','target.gif','gif',1,1,$1),(2,'B','other.gif','gif',1,1,$2),(3,'C','legacy-same.gif','gif',1,1,NULL),(4,'D','legacy-other.gif','gif',1,1,'')",[sha,otherSha]);
  const reads=vi.spyOn(files,'readFile');
  const result=await matchMaterials(pool as any,[sha],root);
  expect(reads.mock.calls.map(args=>args[0])).not.toContain(join(root,'uploads/stickers/other.gif'));
  expect(reads.mock.calls.map(args=>args[0])).toEqual(expect.arrayContaining([join(root,'uploads/stickers/target.gif'),join(root,'uploads/stickers/legacy-same.gif'),join(root,'uploads/stickers/legacy-other.gif')]));
  expect(result.items[0]).toMatchObject({status:'existing',material:{ids:[1,3],keywords:['A','C']}});
  expect((await pool.query('SELECT sha256 FROM sticker WHERE id=4')).rows[0].sha256).toBe(otherSha);
  reads.mockClear();await matchMaterials(pool as any,[sha],root);
  expect(reads.mock.calls.map(args=>args[0])).toEqual([join(root,'uploads/stickers/target.gif'),join(root,'uploads/stickers/legacy-same.gif')]);
 }finally{await pool.end();}
});
it('空匹配请求不访问数据库或扫描文件',async()=>{
 const query=vi.fn(async()=>{throw Error('must not query');});
 expect(await matchMaterials({query} as any,[])).toEqual({items:[],warnings:[]});expect(query).not.toHaveBeenCalled();
});
