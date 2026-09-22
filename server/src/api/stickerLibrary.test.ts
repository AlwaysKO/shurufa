import { readFileSync } from 'node:fs';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { newDb, DataType } from 'pg-mem';
import type pg from 'pg';
import request from 'supertest';
import { beforeEach, afterEach, expect, it, vi } from 'vitest';
import { createApp } from '../app.js';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';
const A = '00000000-0000-4000-8000-00000000000a';
const B = '00000000-0000-4000-8000-00000000000b';
let pool: pg.Pool;
let root: string;
let agent: Awaited<ReturnType<typeof authenticatedRequest>>;
let app: ReturnType<typeof createApp>;
beforeEach(async () => {
  const db = newDb();
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
  await pool.query(readFileSync(new URL('../../migrations/024_sticker_group_settings.sql', import.meta.url), 'utf8'));
  root = await mkdtemp(join(tmpdir(), 'sticker-library-'));
  await mkdir(join(root, 'server/.runtime/expression-assets/prebuilt'), { recursive: true });
  await mkdir(join(root, 'assets/expression/query'), { recursive: true });
  await writeFile(join(root, 'server/.runtime/expression-assets/catalog.json'), JSON.stringify({ templates: [
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
it('完整列出运行库和规划空词，个人图片按关键词拆分且不泄露其他用户', async () => {
  await pool.query(`INSERT INTO sticker(user_id,keywords,file_name,format) VALUES
    ($1,'你好，问候,你好','mine.gif','gif'),($2,'隐私','other.gif','gif')`, [A, B]);
  const res = await agent.get(`/api/v1/dashboard/sticker-library?user_id=${A}`);
  expect(res.status).toBe(200);
  expect(res.body.groups.map((g: any) => g.keyword).sort()).toEqual(['你好', '您好', '晚安', '问候'].sort());
  expect(res.body.groups.find((g: any) => g.keyword === '你好').assets.map((s: any) => s.source)).toEqual(['personal', 'system']);
  expect(res.body.groups.find((g: any) => g.keyword === '晚安')).toMatchObject({ planned: true, assets: [] });
});
it('新增关键词无需图片，重复请求幂等，用户隔离', async () => {
  for (let i = 0; i < 2; i++) {
    const res = await agent.post(`/api/v1/dashboard/sticker-keywords?user_id=${A}`).send({ keyword: ' 自定义 ' });
    expect(res.status).toBe(201);
  }
  const res = await agent.get(`/api/v1/dashboard/sticker-library?user_id=${A}`);
  expect(res.body.groups.filter((g: any) => g.keyword === '自定义')).toHaveLength(1);
  expect(res.body.groups.find((g: any) => g.keyword === '自定义').assets).toEqual([]);
  const other = await agent.get(`/api/v1/dashboard/sticker-library?user_id=${B}`);
  expect(other.body.groups.some((g: any) => g.keyword === '自定义')).toBe(false);
});
it.each(['', ' ', '两个,词', '两个，词', 'a'.repeat(101)])('拒绝不合法的独立关键词 %s', async keyword => {
  const res = await agent.post(`/api/v1/dashboard/sticker-keywords?user_id=${A}`).send({ keyword });
  expect(res.status).toBe(400);
});
it('已有个人图的关键词删除最后图片后仍保留', async () => {
  await pool.query(`INSERT INTO sticker(user_id,keywords,file_name,format) VALUES($1,'保留我','old.gif','gif')`, [A]);
  const before = await agent.get(`/api/v1/dashboard/sticker-library?user_id=${A}`);
  expect(before.status).toBe(200);
  const id = before.body.groups.find((g: any) => g.keyword === '保留我').assets[0].id;
  expect((await agent.delete(`/api/v1/dashboard/stickers/${id}?user_id=${A}`)).status).toBe(200);
  const after = await agent.get(`/api/v1/dashboard/sticker-library?user_id=${A}`);
  expect(after.body.groups.find((g: any) => g.keyword === '保留我').assets).toEqual([]);
});
it('运行库缺失仍可管理个人词，明确报告缺失，不静默伪装空库', async () => {
  await rm(join(root, 'server/.runtime/expression-assets/catalog.json'));
  const res = await agent.get(`/api/v1/dashboard/sticker-library?user_id=${A}`);
  expect(res.status).toBe(200); expect(res.body.warnings.length).toBeGreaterThan(0);
});
it('公共表情网页预览需要登录，登录后可使用用户查询参数，移动头协议不变', async () => {
  const path = `/uploads/expression/prebuilt/hello.gif?user_id=${A}`;
  expect((await request(app).get(path)).status).toBe(401);
  expect((await agent.get(path)).status).toBe(200);
  expect((await request(app).get('/uploads/expression/prebuilt/hello.gif').set('X-Device-Id', A)).status).toBe(200);
});
it('修改图片关键词后旧词保留为空组，新词也独立持久化', async () => {
  const row = await pool.query(`INSERT INTO sticker(user_id,keywords,file_name,format) VALUES($1,'旧词','move.gif','gif') RETURNING id`, [A]);
  const id = row.rows[0].id;
  for (const keywords of ['新词', '最终词']) {
    expect((await agent.patch(`/api/v1/dashboard/stickers/${id}?user_id=${A}`).send({ keywords })).status).toBe(200);
  }
  const res = await agent.get(`/api/v1/dashboard/sticker-library?user_id=${A}`);
  expect(res.body.groups.find((g: any) => g.keyword === '旧词')).toMatchObject({ assets: [] });
  expect(res.body.groups.find((g: any) => g.keyword === '新词')).toMatchObject({ assets: [] });
  expect(res.body.groups.find((g: any) => g.keyword === '最终词').assets).toHaveLength(1);
});
it('同义词与用户确认的完整说法归同组，多标签图片只出现一次', async () => {
  const path = join(root, 'server/.runtime/expression-assets/catalog.json');
  await writeFile(path, JSON.stringify({ templates: [
    { id: 'play', keywords: ['打你', '揍你'], fileName: 'prebuilt/play.gif', format: 'gif', width: 240, height: 240 },
    { id: 'happy', keywords: ['开心'], fileName: 'prebuilt/happy.gif', format: 'gif', width: 240, height: 240 },
    { id: 'sad', keywords: ['不开心'], fileName: 'prebuilt/sad.gif', format: 'gif', width: 240, height: 240 },
  ] }));
  await pool.query(`INSERT INTO sticker(user_id,keywords,file_name,format) VALUES
    ($1,'扁你,我来打你了','mine-play.gif','gif'),($2,'揍你','private.gif','gif')`, [A, B]);
  const res = await agent.get(`/api/v1/dashboard/sticker-library?user_id=${A}`);
  expect(res.status).toBe(200);
  const play = res.body.groups.find((g: any) => g.keyword === '打闹');
  expect(play).toBeDefined();
  expect(play.aliases).toEqual(expect.arrayContaining(['打你', '揍你', '扁你', '我来打你了', '过来打我啊']));
  expect(play.confirmedAliases).toEqual(expect.arrayContaining(['扁你', '我来打你了', '过来打我啊']));
  expect(play.assets).toHaveLength(2);
  expect(play.assets.map((a: any) => a.source)).toEqual(['personal', 'system']);
  expect(res.body.groups.some((g: any) => ['打你','揍你','扁你','我来打你了'].includes(g.keyword))).toBe(false);
  expect(res.body.groups.find((g: any) => g.keyword === '开心').assets.map((a: any) => a.id)).toEqual(['happy']);
  expect(res.body.groups.find((g: any) => g.keyword === '难过').assets.map((a: any) => a.id)).toEqual(['sad']);
  expect(res.body.systemCount).toBe(3); expect(res.body.personalCount).toBe(1);
});
it('未定义同义关系的关键词保持独立，不靠字面子串猜测', async () => {
  await agent.post(`/api/v1/dashboard/sticker-keywords?user_id=${A}`).send({ keyword: '我打你电话' });
  await agent.post(`/api/v1/dashboard/sticker-keywords?user_id=${A}`).send({ keyword: '打你' });
  const res = await agent.get(`/api/v1/dashboard/sticker-library?user_id=${A}`);
  expect(res.body.groups.find((g: any) => g.keyword === '打闹')?.aliases).toContain('打你');
  expect(res.body.groups.find((g: any) => g.keyword === '我打你电话')?.aliases).toEqual(['我打你电话']);
});

it('默认个人上传优先，拖拽顺序保存后优先于来源，另一个用户不受影响', async () => {
  const row = await pool.query(`INSERT INTO sticker(user_id,keywords,file_name,format) VALUES($1,'你好','mine.gif','gif') RETURNING id`, [A]);
  const id = row.rows[0].id;
  const url = `/api/v1/dashboard/sticker-library?user_id=${A}`;
  let library = (await agent.get(url)).body;
  expect(library.groups.find((g: any) => g.keyword === '你好').assets.map((a: any) => a.source)).toEqual(['personal', 'system']);
  const saved = await agent.patch(`/api/v1/dashboard/sticker-groups/${encodeURIComponent('你好')}?user_id=${A}`)
    .send({ assetOrder: ['system:hello', `personal:${id}`] });
  expect(saved.status).toBe(200);
  library = (await agent.get(url)).body;
  expect(library.groups.find((g: any) => g.keyword === '你好').assets.map((a: any) => a.source)).toEqual(['system', 'personal']);
  const other = (await agent.get(`/api/v1/dashboard/sticker-library?user_id=${B}`)).body;
  expect(other.groups.find((g: any) => g.keyword === '你好').assets).toHaveLength(1);
});
it('同组说法可替换和清空，删除旧说法不丢图、不被原标签补回', async () => {
  const url = `/api/v1/dashboard/sticker-groups/${encodeURIComponent('你好')}?user_id=${A}`;
  expect((await agent.patch(url).send({ aliases: ['  嗨你好！ ', '见到你真好'] })).status).toBe(200);
  let library = (await agent.get(`/api/v1/dashboard/sticker-library?user_id=${A}`)).body;
  expect(library.groups.find((g: any) => g.keyword === '你好')).toMatchObject({ aliases: ['嗨你好', '见到你真好'], assets: [expect.objectContaining({ id: 'hello' })] });
  expect((await agent.patch(url).send({ aliases: [] })).status).toBe(200);
  library = (await agent.get(`/api/v1/dashboard/sticker-library?user_id=${A}`)).body;
  expect(library.groups.find((g: any) => g.keyword === '你好').aliases).toEqual([]);
  const other = (await agent.get(`/api/v1/dashboard/sticker-library?user_id=${B}`)).body;
  expect(other.groups.find((g: any) => g.keyword === '你好').aliases).toEqual(['你好']);
});
it('拒绝重复、越权或非本组排序、冲突别名，失败不覆盖已保存设置', async () => {
  const url = `/api/v1/dashboard/sticker-groups/${encodeURIComponent('你好')}?user_id=${A}`;
  for (const payload of [{assetOrder:['personal:999']}, {assetOrder:['system:hello','system:hello']}, {assetOrder:[]}, {aliases:['晚安']}, {aliases:['  ']}, {aliases:['你好','你好！']}, {aliases:'abc'}, {}]) {
    expect([400, 409]).toContain((await agent.patch(url).send(payload)).status);
  }
  expect((await agent.patch(`/api/v1/dashboard/sticker-groups/不存在?user_id=${A}`).send({aliases:['新']})).status).toBe(404);
  expect((await agent.get(`/api/v1/dashboard/sticker-library?user_id=${A}`)).body.groups.find((g: any) => g.keyword === '你好').aliases).toEqual(['你好']);
});

it('完整目录同步别名与顺序，版本随设置变化，自动推荐无子串回退而手动搜索保留', async () => {
  await writeFile(join(root, 'server/.runtime/expression-assets/catalog.json'), JSON.stringify({version:'v1', emojiBases:[], emojiCombinations:[], templates:[
    {id:'praise',type:'prebuilt',keywords:['赞'],embeddedText:'赞',fileName:'prebuilt/praise.gif',format:'gif',width:240,height:240,sha256:'a'.repeat(64),heat:99},
  ]}));
  const personal = await pool.query(`INSERT INTO sticker(user_id,keywords,file_name,format,sha256) VALUES($1,'点赞','mine.gif','gif',$2) RETURNING id`, [A, 'b'.repeat(64)]);
  const id = personal.rows[0].id;
  const mobile = (path: string) => request(app).get(`/api/v1/mobile/expressions/${path}`).set('X-Device-Id', A);
  const before = await mobile('catalog');
  expect(before.status).toBe(200);
  expect(before.body.recommendationGroups.find((g: any) => g.keyword === '赞')).toMatchObject({aliases:['赞','点赞','给你点赞','太棒了'],assetIds:[`sticker-${id}`,'praise']});
  const recommend = async (q: string, mode='automatic') => (await mobile(`recommend?q=${encodeURIComponent(q)}&mode=${mode}`)).body.results.map((a:any)=>a.id);
  expect(await recommend(' 给你点赞！ ')).toEqual([`sticker-${id}`, 'praise']);
  expect(await recommend('这是中华人民赞扬的美德')).toEqual([]);
  expect(await recommend('这么长一段话最后才说给你点赞')).toEqual([]);
  expect(await recommend('给你，点赞')).toEqual([]);
  const patch = `/api/v1/dashboard/sticker-groups/${encodeURIComponent('赞')}?user_id=${A}`;
  expect((await agent.patch(patch).send({aliases:['夸夸你'],assetOrder:['system:praise',`personal:${id}`]})).status).toBe(200);
  const after = await mobile('catalog');
  expect(after.body.version).not.toBe(before.body.version);
  expect(after.body.recommendationGroups.find((g:any)=>g.keyword==='赞')).toEqual({keyword:'赞',aliases:['夸夸你'],assetIds:['praise',`sticker-${id}`]});
  expect(await recommend('给你点赞')).toEqual([]);
  expect(await recommend('赞')).toEqual([]); // 删除后不能由 embeddedText 或默认组恢复
  expect(await recommend('夸夸你')).toEqual(['praise',`sticker-${id}`]);
  expect(await recommend('夸夸你','manual')).toEqual(['praise',`sticker-${id}`]);
  expect(await recommend('点赞','manual')).toContain(`sticker-${id}`);
  const other = await request(app).get('/api/v1/mobile/expressions/catalog').set('X-Device-Id', B);
  expect(other.body.recommendationGroups.find((g:any)=>g.keyword==='赞').aliases).toContain('给你点赞');
});
it('组上传使用稳定组标识，新增/清空别名后仍归原组而不制造别名空组', async () => {
  const patch = `/api/v1/dashboard/sticker-groups/${encodeURIComponent('你好')}?user_id=${A}`;
  await agent.patch(patch).send({aliases:['新招呼']});
  const upload = await agent.post(`/api/v1/dashboard/stickers?user_id=${A}`).send({
    group_keyword:'你好', keywords:'新招呼', filename:'tiny.gif',
    file_base64:'R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7',
  });
  expect(upload.status).toBe(201);
  const library = (await agent.get(`/api/v1/dashboard/sticker-library?user_id=${A}`)).body;
  expect(library.groups.find((g:any)=>g.keyword==='你好').assets).toHaveLength(2);
  expect(library.groups.some((g:any)=>g.keyword==='新招呼')).toBe(false);
});
it('新建关键词按同一归一化规则检查已有组别名，不制造重复匹配组',async()=>{
 await agent.patch(`/api/v1/dashboard/sticker-groups/${encodeURIComponent('你好')}?user_id=${A}`).send({aliases:['测试说法']});
 const created=await agent.post(`/api/v1/dashboard/sticker-keywords?user_id=${A}`).send({keyword:'测试说法！'});
 expect(created.status).toBe(201);expect(created.body.keyword).toBe('你好');
 const groups=(await agent.get(`/api/v1/dashboard/sticker-library?user_id=${A}`)).body.groups;
 expect(groups.some((g:any)=>g.keyword==='测试说法！')).toBe(false);
});
it('移动个人表情搜索也使用编辑后的组说法和个人图片顺序',async()=>{
 const rows=await pool.query(`INSERT INTO sticker(user_id,keywords,file_name,format) VALUES($1,'你好','a.gif','gif'),($1,'你好','b.gif','gif') RETURNING id`,[A]);
 const [a,b]=rows.rows.map(row=>row.id);
 await agent.patch(`/api/v1/dashboard/sticker-groups/${encodeURIComponent('你好')}?user_id=${A}`).send({aliases:['新问候'],assetOrder:[`personal:${a}`,'system:hello',`personal:${b}`]});
 const found=await request(app).get('/api/v1/mobile/stickers?q='+encodeURIComponent('新问候')).set('X-Device-Id',A);
 expect(found.status).toBe(200);expect(found.body.stickers.map((item:any)=>Number(item.id))).toEqual([a,b]);
});
it('现有太棒了成品归入赞组，给你点赞正例实际有可用原图而非空规划词',async()=>{
 await writeFile(join(root,'server/.runtime/expression-assets/catalog.json'),JSON.stringify({templates:[{id:'great',keywords:['太棒了'],fileName:'prebuilt/great.gif',format:'gif',width:240,height:240}]}));
 const groups=(await agent.get(`/api/v1/dashboard/sticker-library?user_id=${A}`)).body.groups;
 expect(groups.find((g:any)=>g.keyword==='赞')).toMatchObject({aliases:expect.arrayContaining(['给你点赞','太棒了']),assets:[expect.objectContaining({id:'great'})]});
});
it('手动精确命中空图组时保持空结果，不回退匹配其他组',async()=>{
 await writeFile(join(root,'server/.runtime/expression-assets/catalog.json'),JSON.stringify({version:'empty-test',emojiBases:[],emojiCombinations:[],templates:[{id:'hello',type:'prebuilt',keywords:['你好'],embeddedText:'你好',fileName:'prebuilt/hello.gif',format:'gif',width:240,height:240,sha256:'a'.repeat(64)}]}));
 expect((await agent.patch(`/api/v1/dashboard/sticker-groups/${encodeURIComponent('晚安')}?user_id=${A}`).send({aliases:['今天你好']})).status).toBe(200);
 const res=await request(app).get('/api/v1/mobile/expressions/recommend?q='+encodeURIComponent('今天你好')+'&mode=manual').set('X-Device-Id',A);
 expect(res.status).toBe(200);expect(res.body.results).toEqual([]);
});
