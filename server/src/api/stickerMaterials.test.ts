import { SHARED_STICKER_OWNER as OWNER } from '../stickers/shared.js';
import * as bundle from '../stickers/bundle.js';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { mkdtemp, mkdir, writeFile, rm, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { newDb, DataType } from 'pg-mem';
import type pg from 'pg';
import request from 'supertest';
import { beforeEach, afterEach, expect, it, vi } from 'vitest';
import { createApp } from '../app.js';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';
const A = '00000000-0000-4000-8000-00000000000a';
let pool: pg.Pool;
let root: string;
let agent: Awaited<ReturnType<typeof authenticatedRequest>>;
let app: ReturnType<typeof createApp>;
beforeEach(async () => {
  const db = newDb();
  // pg-mem 不模拟表锁；真实事务及附件清理在 PostgreSQL 回归中验证。
  db.public.interceptQueries(sql => /^LOCK TABLE /i.test(sql) ? [] : null);
  db.public.registerFunction({ name: 'trim', args: [DataType.text], returns: DataType.text, implementation: (s: string) => s.trim() });
  db.public.registerFunction({ name: 'length', args: [DataType.text], returns: DataType.integer, implementation: (s: string) => s.length });
  db.public.registerFunction({name:'hashtext',args:[DataType.text],returns:DataType.integer,implementation:()=>1});
  db.public.registerFunction({name:'pg_advisory_xact_lock',args:[DataType.integer],returns:DataType.integer,implementation:()=>1});
  pool = new (db.adapters.createPg().Pool)();
  await pool.query(readFileSync(new URL('../../migrations/005_sticker.sql', import.meta.url), 'utf8'));
  // pg-mem 不支持 regexp_split_to_table；迁移回填与幂等另在真实 PostgreSQL 事务中验证。
  const migration = new URL('../../migrations/015_sticker_keywords.sql', import.meta.url);
  await pool.query(readFileSync(migration, 'utf8').split('-- 兼容历史')[0]);
  await pool.query(readFileSync(new URL('../../migrations/019_keyword_gif_removal.sql', import.meta.url), 'utf8'));
  await pool.query(readFileSync(new URL('../../migrations/018_synthesis_library.sql', import.meta.url), 'utf8'));
  await pool.query(readFileSync(new URL('../../migrations/030_synthesis_order.sql', import.meta.url), 'utf8'));
  await pool.query(readFileSync(new URL('../../migrations/024_sticker_group_settings.sql', import.meta.url), 'utf8'));
  await pool.query(readFileSync(new URL('../../migrations/028_sticker_group_deletion.sql', import.meta.url), 'utf8'));
  await pool.query(readFileSync(new URL('../../migrations/020_runtime_settings.sql', import.meta.url), 'utf8'));
  await pool.query('CREATE TABLE sticker_bundle_import(singleton BOOLEAN PRIMARY KEY, manifest JSONB NOT NULL)');
  root = await mkdtemp(join(tmpdir(), 'sticker-materials-'));
  await mkdir(join(root, 'server/.runtime/expression-assets/prebuilt'), { recursive: true });
  await mkdir(join(root, 'assets/expression/query'), { recursive: true });
  await writeFile(join(root, 'server/.runtime/expression-assets/catalog.json'), JSON.stringify({ emojiBases: [], emojiCombinations: [], templates: [
    { id: 'hello', keywords: ['你好', '您好'], fileName: 'prebuilt/hello.gif', format: 'gif', width: 240, height: 240 },
  ] }));
  await writeFile(join(root, 'server/.runtime/expression-assets/prebuilt/hello.gif'), Buffer.from('GIF89a'));
  await writeFile(join(root, 'assets/expression/query/keyword-coverage.draft.json'), JSON.stringify({ keywords: [
    { keyword: '你好', category: '问候' }, { keyword: '晚安', category: '问候' },
  ] }));
  vi.spyOn(process, 'cwd').mockReturnValue(join(root, 'server'));
  app = createApp(pool); agent = await authenticatedRequest(app);
});
afterEach(async () => { vi.restoreAllMocks(); await pool.end(); if (root) await rm(root, { recursive: true, force: true }); });
async function seedMaterials() {
  return (await pool.query(`INSERT INTO sticker(user_id,keywords,file_name,format,sha256) VALUES
    ($1,'','unassigned.gif','gif',$2), ($1,'你好','assigned.gif','gif',$3) RETURNING id`,
    [OWNER, 'a'.repeat(64), 'b'.repeat(64)])).rows.map(row => row.id);
}
it('未分配素材后台可读，但手机空词、关键词组及模糊搜索均不返回', async () => {
  const [unassigned, assigned] = await seedMaterials();
  const dashboard = await agent.get('/api/v1/dashboard/stickers');
  expect(dashboard.status).toBe(200);
  expect(dashboard.body.stickers.find((s: any) => s.id === unassigned).keywords).toBe('');
  for (const q of ['', '你好', '好', '%']) {
    const mobile = await request(app).get('/api/v1/mobile/stickers').query({ q }).set('X-Device-Id', A);
    expect(mobile.status).toBe(200);
    expect(mobile.body.stickers.map((s: any) => s.id)).toEqual([assigned]);
  }
});
it('未分配素材不产生空关键词组，也不进入手机推荐快照', async () => {
  const [unassigned, assigned] = await seedMaterials();
  const library = await agent.get('/api/v1/dashboard/sticker-library');
  expect(library.status).toBe(200);
  expect(library.body.groups.some((g: any) => !g.keyword.trim())).toBe(false);
  const snapshot = await request(app).get('/api/v1/mobile/expressions/catalog').set('X-Device-Id', A);
  expect(snapshot.status).toBe(200);
  expect(snapshot.body.templates.map((s: any) => s.id)).not.toContain(`sticker-${unassigned}`);
  expect(snapshot.body.templates.map((s: any) => s.id)).toContain(`sticker-${assigned}`);
  expect(snapshot.body.recommendationGroups.flatMap((g: any) => g.assetIds)).not.toContain(`sticker-${unassigned}`);
});
it('原手动上传入口仍拒绝空关键词', async () => {
  const res = await agent.post('/api/v1/dashboard/stickers').send({
    keywords: '', filename: 'tiny.gif', file_base64: 'R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7',
  });
  expect(res.status).toBe(400);
  expect((await pool.query('SELECT * FROM sticker')).rows).toEqual([]);
});
const gif = Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7','base64');
async function upload(name='tiny.gif', hash?:string) { return agent.post('/api/v1/dashboard/sticker-materials').query({filename:name,...(hash?{sha256:hash}:{})}).set('Content-Type','application/octet-stream').send(gif); }
it('素材导入不依赖手机，保留原图并按内容重复识别，支持筛选分页',async()=>{
 const first=await upload(); expect(first.status).toBe(201); expect(first.body.material).toMatchObject({keywords:[],assigned:false,format:'gif',width:1,height:1});
 const duplicate=await upload('renamed.gif'); expect(duplicate.status).toBe(200); expect(duplicate.body.status).toBe('existing'); expect(duplicate.body.material.ids).toEqual(first.body.material.ids);
 const list=await agent.get('/api/v1/dashboard/sticker-materials').query({state:'unassigned',page_size:1}); expect(list.status).toBe(200); expect(list.body.total).toBe(1); expect(list.body.pageSize).toBe(1);
 const match=await agent.post('/api/v1/dashboard/sticker-materials/match').send({sha256s:[first.body.material.sha256,'a'.repeat(64)]}); expect(match.body.items.map((i:any)=>i.status)).toEqual(['existing','missing']);
 expect((await agent.get('/api/v1/dashboard/sticker-materials').query({state:'assigned'})).body.total).toBe(0);
});
it('多行旧记录差量修改保留ID、次数与排序，只移除指定组，最后一词可清空',async()=>{
 const first=await upload(); const sha=first.body.material.sha256,id=first.body.material.ids[0];
 await pool.query("UPDATE sticker SET keywords='A,B', use_count=19 WHERE id=$1",[id]);
 await writeFile(join(root,'server/uploads/stickers/copy.gif'),gif);
 await pool.query("INSERT INTO sticker(user_id,keywords,file_name,format,sha256,use_count) VALUES($1,'B,C','copy.gif','gif',$2,27)",[OWNER,sha]);
 await pool.query('INSERT INTO sticker_group_settings(user_id,keyword,asset_order) VALUES($1,$2,$3)',[OWNER,'B',JSON.stringify([`personal:${id}`])]);
 const edit=await agent.patch(`/api/v1/dashboard/sticker-materials/${sha}/keywords`).send({add:['D'],remove:['A']}); expect(edit.status).toBe(200); expect(edit.body.material.keywords).toEqual(['B','D','C']);
 expect((await pool.query('SELECT id,use_count FROM sticker ORDER BY id')).rows).toEqual([{id,use_count:19},{id:id+1,use_count:27}]);
 expect((await pool.query('SELECT asset_order FROM sticker_group_settings')).rows[0].asset_order).toEqual([`personal:${id}`]);
 const cleared=await agent.patch(`/api/v1/dashboard/sticker-materials/${sha}/keywords`).send({add:[],remove:['B','C','D']}); expect(cleared.body.material.keywords).toEqual([]);
 expect((await request(app).get('/api/v1/mobile/stickers').set('X-Device-Id',A)).body.stickers).toEqual([]);
 expect((await upload()).body.material.ids).toHaveLength(2);
});
it('导入拒绝伪造哈希、路径、类型及无效图，鉴权和跨站保护保持',async()=>{
 expect((await upload('x.gif','a'.repeat(64))).status).toBe(400);
 expect((await upload('../x.gif')).status).toBe(400); expect((await upload('x.png')).status).toBe(400);
 expect((await agent.post('/api/v1/dashboard/sticker-materials').query({filename:'x.gif'}).set('Content-Type','application/octet-stream').send(Buffer.from('bad'))).status).toBe(400);
 expect((await request(app).get('/api/v1/dashboard/sticker-materials')).status).toBe(401);
 expect((await agent.post('/api/v1/dashboard/sticker-materials/match').set('Origin','https://evil.test').send({sha256s:[]})).status).toBe(403);
 expect((await pool.query('SELECT * FROM sticker')).rows).toEqual([]);
});
it('拒绝metadata可读但缺少GIF结束符的截断图',async()=>{
 const res=await agent.post('/api/v1/dashboard/sticker-materials').query({filename:'cut.gif'}).set('Content-Type','application/octet-stream').send(gif.subarray(0,-1));
 expect(res.status).toBe(400); expect((await pool.query('SELECT * FROM sticker')).rows).toEqual([]);
});
it('同组别名只增加规范组；移除规范组同时移除旧别名，已删组不得增加',async()=>{
 const first=await upload(),sha=first.body.material.sha256,id=first.body.material.ids[0];
 await pool.query("UPDATE sticker SET keywords='扁你,B' WHERE id=$1",[id]);
 const edited=await agent.patch(`/api/v1/dashboard/sticker-materials/${sha}/keywords`).send({add:['过来打我啊'],remove:[]}); expect(edited.status).toBe(200); expect(edited.body.material.keywords).toEqual(['扁你','B']);
 const removed=await agent.patch(`/api/v1/dashboard/sticker-materials/${sha}/keywords`).send({add:[],remove:['打闹']}); expect(removed.body.material.keywords).toEqual(['B']);
 await pool.query("INSERT INTO sticker_group_deletion(keyword,revision) VALUES('打闹',$1)",[A]);
 expect((await agent.patch(`/api/v1/dashboard/sticker-materials/${sha}/keywords`).send({add:['扁你'],remove:[]})).status).toBe(409);
});
it('归档失败不删除已提交原图，重试按已有素材返回',async()=>{
 vi.spyOn(bundle,'publishStickerBundle').mockRejectedValueOnce(Error('synthetic archive failure'));
 const res=await upload(); expect(res.status).toBe(500);
 const row=(await pool.query('SELECT * FROM sticker')).rows[0]; expect(row).toBeDefined();
 expect(await readFile(join(root,'server/uploads/stickers',row.file_name))).toEqual(gif);
 const retry=await upload(); expect(retry.status).toBe(200); expect(retry.body.status).toBe('existing');
});
it('全部旧副本缺失的导入返回明确冲突，不新建记录或吞掉旧词',async()=>{
 const hash=createHash('sha256').update(gif).digest('hex');
 await pool.query("INSERT INTO sticker(user_id,keywords,file_name,format,sha256) VALUES($1,'旧词','missing.gif','gif',$2)",[OWNER,hash]);
 const match=await agent.post('/api/v1/dashboard/sticker-materials/match').send({sha256s:[hash]}); expect(match.body.items[0].status).toBe('unavailable');
 expect((await upload()).status).toBe(409); expect((await pool.query('SELECT keywords FROM sticker')).rows).toEqual([{keywords:'旧词'}]);
});
it('非法分页、非法SHA、超量匹配、超限文件和空词修改均拒绝',async()=>{
 for(const query of [{page:0},{page_size:101},{state:'bad'}]) expect((await agent.get('/api/v1/dashboard/sticker-materials').query(query)).status).toBe(400);
 for(const sha256s of [[' '],Array(501).fill('a'.repeat(64))]) expect((await agent.post('/api/v1/dashboard/sticker-materials/match').send({sha256s})).status).toBe(400);
 expect((await agent.post('/api/v1/dashboard/sticker-materials').query({filename:'large.gif'}).set('Content-Type','application/octet-stream').send(Buffer.alloc(10*1024*1024+1))).status).toBe(413);
 const first=await upload();
 expect((await agent.patch(`/api/v1/dashboard/sticker-materials/${first.body.material.sha256}/keywords`).send({add:[' '],remove:[]})).status).toBe(400);
});
it('自定义说法解析回既有组，不创建额外组；按关键词查询分页',async()=>{
 const first=await upload(),sha=first.body.material.sha256;
 await pool.query('INSERT INTO sticker_group_settings(user_id,keyword,aliases) VALUES($1,$2,$3)',[OWNER,'自定义组',JSON.stringify(['自定义说法'])]);
 const res=await agent.patch(`/api/v1/dashboard/sticker-materials/${sha}/keywords`).send({add:['自定义说法'],remove:[]}); expect(res.status).toBe(200); expect(res.body.material.keywords).toEqual(['自定义组']);
 const filtered=await agent.get('/api/v1/dashboard/sticker-materials').query({state:'assigned',q:'自定义',page:2,page_size:1}); expect(filtered.body.total).toBe(1); expect(filtered.body.items).toEqual([]);
});
it('最低ID副本缺图时新增关键词只写入原图已验证的确定性记录',async()=>{
 const hash=createHash('sha256').update(gif).digest('hex');
 const missing=(await pool.query("INSERT INTO sticker(user_id,keywords,file_name,format,sha256) VALUES($1,'旧关联','missing.gif','gif',$2) RETURNING id",[OWNER,hash])).rows[0].id;
 await mkdir(join(root,'server/uploads/stickers'),{recursive:true});
 await writeFile(join(root,'server/uploads/stickers/healthy.gif'),gif);
 const healthy=(await pool.query("INSERT INTO sticker(user_id,keywords,file_name,format,sha256) VALUES($1,'保留词','healthy.gif','gif',$2) RETURNING id",[OWNER,hash])).rows[0].id;
 // 本例有意保留缺图旧行；归档会独立拒绝缺图，不影响验证已提交关联的目标行。
 vi.spyOn(bundle,'publishStickerBundle').mockResolvedValueOnce(undefined);
 const response=await agent.patch(`/api/v1/dashboard/sticker-materials/${hash}/keywords`).send({add:['新词'],remove:[]});
 expect(response.status).toBe(200);
 expect((await pool.query('SELECT id,keywords FROM sticker ORDER BY id')).rows).toEqual([{id:missing,keywords:'旧关联'},{id:healthy,keywords:'保留词,新词'}]);
 const mobile=await request(app).get('/api/v1/mobile/stickers').query({q:'新词'}).set('X-Device-Id',A);
 expect(mobile.body.stickers.map((item:any)=>item.id)).toEqual([healthy]);
 expect(mobile.body.stickers[0].url).toBe('/uploads/stickers/healthy.gif');
});

it('素材组选取模式拒绝已不存在的候选，不静默新建关键词',async()=>{
 const first=await upload(),sha=first.body.material.sha256;
 const response=await agent.patch(`/api/v1/dashboard/sticker-materials/${sha}/keywords`).send({add:['不存在的候选组0929'],remove:[],requireExistingGroups:true});
 expect(response.status).toBe(409);
 expect((await pool.query('SELECT keywords FROM sticker')).rows).toEqual([{keywords:''}]);
 expect((await agent.get('/api/v1/dashboard/sticker-library')).body.groups.some((g:any)=>g.keyword==='不存在的候选组0929')).toBe(false);
});
it('选择已有主关键词一次即可复用整组说法且不改配置，别名同样解析到主组',async()=>{
 const first=await upload(),sha=first.body.material.sha256,id=first.body.material.ids[0];
 await pool.query('INSERT INTO sticker_group_settings(user_id,keyword,aliases) VALUES($1,$2,$3)',[OWNER,'晨间验收组',JSON.stringify(['晨间验收说法甲','晨间验收说法乙'])]);
 for(const word of ['晨间验收组','晨间验收说法乙']) {
  const response=await agent.patch(`/api/v1/dashboard/sticker-materials/${sha}/keywords`).send({add:[word],remove:[],requireExistingGroups:true});
  expect(response.status).toBe(200);expect(response.body.material.keywords).toEqual(['晨间验收组']);
 }
 for(const q of ['晨间验收说法甲','晨间验收说法乙']) {
  const response=await request(app).get('/api/v1/mobile/stickers').query({q}).set('X-Device-Id',A);
  expect(response.body.stickers.map((s:any)=>s.id)).toContain(id);
 }
 expect((await pool.query('SELECT aliases FROM sticker_group_settings WHERE keyword=$1',['晨间验收组'])).rows[0].aliases).toEqual(['晨间验收说法甲','晨间验收说法乙']);
});
it('组选择模式校验布尔标记并允许仅移除已有组',async()=>{
 const first=await upload(),sha=first.body.material.sha256;
 const url=`/api/v1/dashboard/sticker-materials/${sha}/keywords`;
 expect((await agent.patch(url).send({add:['你好'],remove:[],requireExistingGroups:'true'})).status).toBe(400);
 expect((await agent.patch(url).send({add:['你好'],remove:[],requireExistingGroups:true})).status).toBe(200);
 const removed=await agent.patch(url).send({add:[],remove:['你好'],requireExistingGroups:true});
 expect(removed.status).toBe(200);expect(removed.body.material.keywords).toEqual([]);
});

it('批量删除同图全部历史记录，保留其他素材与原文件，并阻止重新导入', async () => {
 const first=await upload(), sha=first.body.material.sha256;
 await pool.query("UPDATE sticker SET keywords='你好' WHERE sha256=$1",[sha]);
 await writeFile(join(root,'server/uploads/stickers/copy.gif'),gif);
 await pool.query("INSERT INTO sticker(user_id,keywords,file_name,format,sha256) VALUES($1,'晚安','copy.gif','gif',$2)",[A,sha]);
 const other=Buffer.from(gif); other[13]=127;
 const second=await agent.post('/api/v1/dashboard/sticker-materials').query({filename:'other.gif'}).set('Content-Type','application/octet-stream').send(other);
 const deleted=await agent.post('/api/v1/dashboard/sticker-materials/delete').send({confirm:'DELETE',sha256s:[sha]});
 expect(deleted.status).toBe(200); expect(deleted.body.deleted).toBe(1);
 expect((await pool.query('SELECT sha256 FROM sticker')).rows).toEqual([{sha256:second.body.material.sha256}]);
 expect((await pool.query('SELECT sha256,asset_id FROM keyword_gif_removal')).rows).toEqual([{sha256:sha,asset_id:`material:${sha}`}]);
 expect(await readFile(join(root,'server',first.body.material.url))).toEqual(gif);
 expect((await upload()).status).toBe(409);
 expect((await agent.post('/api/v1/dashboard/stickers').send({keywords:'你好',filename:'tiny.gif',file_base64:gif.toString('base64')})).status).toBe(409);
 const listing=await agent.get('/api/v1/dashboard/sticker-materials'); expect(listing.body.total).toBe(1);
 const mobile=await request(app).get('/api/v1/mobile/stickers').query({q:'你好'}).set('X-Device-Id',A);
 expect(mobile.body.stickers).toEqual([]);
});
it('素材批量删除拒绝空列表、超量、坏哈希、缺确认和旧选择，且要求登录与同源',async()=>{
 const first=await upload(),sha=first.body.material.sha256,url='/api/v1/dashboard/sticker-materials/delete';
 for(const body of [{sha256s:[sha]}, {confirm:'DELETE',sha256s:[]}, {confirm:'DELETE',sha256s:Array(31).fill(sha)}, {confirm:'DELETE',sha256s:['bad']}]) expect((await agent.post(url).send(body)).status).toBe(400);
 expect((await agent.post(url).send({confirm:'DELETE',sha256s:[sha,'a'.repeat(64)]})).status).toBe(409);
 expect((await pool.query('SELECT * FROM sticker')).rows).toHaveLength(1);
 expect((await request(app).post(url).send({confirm:'DELETE',sha256s:[sha]})).status).toBe(401);
 expect((await agent.post(url).set('Origin','https://evil.test').send({confirm:'DELETE',sha256s:[sha]})).status).toBe(403);
});
