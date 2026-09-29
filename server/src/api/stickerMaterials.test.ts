import { SHARED_STICKER_OWNER as OWNER } from '../stickers/shared.js';
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
