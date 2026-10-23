import { existsSync, readFileSync, realpathSync, mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { randomUUID } from 'node:crypto';
import pg from 'pg';
import request from 'supertest';
import { beforeAll, beforeEach, afterAll, expect, it, vi } from 'vitest';
import { retryDeviceFileCleanup } from '../lib/deleteDeviceData.js';
import { createApp } from '../app.js';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';

const cluster = process.env.CHAT_UNLINKED_TEST_CLUSTER;
if (cluster && (!cluster.startsWith('/tmp/shurufa-unlinked.') || readFileSync(join(cluster, 'test-instance-only'), 'utf8') !== 'chat-unlinked-only')) throw Error('独立测试实例验证失败');
const test = cluster ? it : it.skip;
const a = 'aaaaaaaa-0000-4000-8000-000000000001', b = 'bbbbbbbb-0000-4000-8000-000000000002';
let pool: pg.Pool, root: string, app: ReturnType<typeof createApp>, agent: Awaited<ReturnType<typeof authenticatedRequest>>;
const config = () => ({host: join(cluster!, 'socket'), port:5432, user:process.env.USER || 'ubuntu', database:'chat_unlinked_test'});
const path = `/api/v1/dashboard/chat/unlinked-images?user_id=${a}`;
async function asset(user = a, time = '2026-10-22T00:00:00Z', mime = 'image/png') {
  const hash = randomUUID().replaceAll('-', '').padEnd(64, '0');
  return (await pool.query(`INSERT INTO media_asset(user_id,sha256,mime_type,storage_path,byte_size,created_at)
    VALUES($1,$2,$3,$4,68,$5) RETURNING *`,[user,hash,mime,`chat/${hash.slice(0,2)}/${hash}.png`,time])).rows[0];
}
async function link(assetId: string) {
  const c=(await pool.query(`INSERT INTO chat_conversation(user_id,platform,account_key,external_key,conversation_type,identity_confidence)
    VALUES($1,'wechat','fixture',$2,'direct',1) RETURNING id`,[a,randomUUID()])).rows[0];
  const id=randomUUID();
  await pool.query(`INSERT INTO chat_message(id,user_id,device_id,conversation_id,platform,fingerprint,content_fingerprint,sender_key,direction,message_type,captured_at)
    VALUES($1,$2,$2,$3,'wechat',$4,$4,'fixture','system','image',NOW())`,[id,a,c.id,randomUUID().replaceAll('-','').padEnd(64,'0')]);
  await pool.query('INSERT INTO chat_message_asset(message_id,asset_id) VALUES($1,$2)',[id,assetId]);
}
beforeAll(async()=>{
  if(!cluster)return;
  pool=new pg.Pool(config());
  const identity=(await pool.query("SELECT current_setting('data_directory') dir,current_database() db")).rows[0];
  expect(realpathSync(identity.dir)).toBe(realpathSync(join(cluster,'data')));expect(identity.db).toBe('chat_unlinked_test');
  root=mkdtempSync('/tmp/shurufa-unlinked-files-');vi.spyOn(process,'cwd').mockReturnValue(root);
});
beforeEach(async()=>{
  if(!cluster)return;
  await pool.end(); const schema='test_'+randomUUID().replaceAll('-','');
  pool=new pg.Pool({...config(),options:`-c search_path=${schema}`});await pool.query(`CREATE SCHEMA ${schema}`);
  await pool.query(readFileSync(new URL('../../migrations/007_chat_capture.sql',import.meta.url),'utf8'));
  await pool.query(`CREATE TABLE runtime_setting(key TEXT PRIMARY KEY,value TEXT NOT NULL,updated_at TIMESTAMPTZ DEFAULT now()); CREATE TABLE sticker(file_name TEXT); CREATE TABLE synthesis_asset(file_name TEXT)`);
  app=createApp(pool);agent=await authenticatedRequest(app,pool);
});
afterAll(async()=>{await pool?.end();vi.restoreAllMocks();if(root)rmSync(root,{recursive:true,force:true});});

test('只列当前手机未关联图片，排序分页稳定，不伪造平台或采集时间',async()=>{
  const old=await asset(a,'2026-10-21T00:00:00Z'),newer=await asset();await asset(b);await asset(a,undefined,'audio/mp3');
  const linked=await asset();await link(linked.id);
  const one=await agent.get(path+'&page_size=1');expect(one.status).toBe(200);expect(one.body.total).toBe(2);
  expect(one.body.images[0]).toMatchObject({id:newer.id,received_at:'2026-10-22T00:00:00.000Z'});
  expect(one.body.images[0]).not.toHaveProperty('captured_at');expect(one.body.images[0]).not.toHaveProperty('platform');
  const two=await agent.get(path+'&page_size=1&page=2');expect(two.body.images[0].id).toBe(old.id);
  expect(one.headers['cache-control']).toBe('no-store');
});
test('北京时间接收日期覆盖零点与最后微秒，排除相邻日期',async()=>{
  await asset(a,'2026-10-21T15:59:59.999Z');const first=await asset(a,'2026-10-21T16:00:00Z');
  const last=await asset(a,'2026-10-22T15:59:59.999999Z');await asset(a,'2026-10-22T16:00:00Z');
  const res=await agent.get(path+'&from=2026-10-22&to=2026-10-22');expect(res.status).toBe(200);
  expect(res.body.images.map((x:any)=>x.id)).toEqual([last.id,first.id]);expect(res.body.total).toBe(2);
});
test('消息关联补齐后自动移出，不复制或更改图片',async()=>{
  const row=await asset();expect((await agent.get(path)).body.total).toBe(1);await link(row.id);
  expect((await agent.get(path)).body).toMatchObject({total:0,images:[]});
  expect((await pool.query('SELECT * FROM media_asset WHERE id=$1',[row.id])).rows[0]).toEqual(row);
});
test('未登录拒绝，手机范围必填，原图仍按所属手机授权',async()=>{
  expect((await request(app).get(path)).status).toBe(401);
  expect((await agent.get('/api/v1/dashboard/chat/unlinked-images')).status).toBe(400);
  const row=await asset();mkdirSync(dirname(join(root,'uploads',row.storage_path)),{recursive:true});writeFileSync(join(root,'uploads',row.storage_path),'synthetic');
  expect((await agent.get(`/uploads/${row.storage_path}?user_id=${a}`)).status).toBe(200);
  expect((await agent.get(`/uploads/${row.storage_path}?user_id=${b}`)).status).toBe(404);
  expect((await request(app).get(`/uploads/${row.storage_path}?user_id=${a}`)).status).toBe(401);
});
for(const query of ['page=0','page=1.5','page=100001','page_size=61','page_size=0','from=2026-02-30','to=bad','from=2026-10-23&to=2026-10-22']){
  test(`拒绝无效筛选 ${query}`,async()=>{expect((await agent.get(path+'&'+query)).status).toBe(400);});
}

const mutation = (suffix: string, user = a) => `/api/v1/dashboard/chat/unlinked-images/${suffix}?user_id=${user}`;
function file(row: { storage_path: string }) {
  const path = join(root, 'uploads', row.storage_path); mkdirSync(dirname(path), { recursive: true }); writeFileSync(path, 'synthetic'); return path;
}
async function preview(days = 1, filters = {}) {
  const result = await agent.post(mutation('cleanup/preview')).send({ days, ...filters }); expect(result.status).toBe(200); return result.body;
}
async function batch(token: string, offset = 0, user = a) {
  return agent.post(mutation('cleanup/batch', user)).send({ token, offset, confirm: 'DELETE' });
}
test('单张和批量删除选定图片及文件，共享文件仍保留', async () => {
  const one = await asset(), two = await asset(), other = await asset(b); const firstFile = file(one), sharedFile = file(two);
  await pool.query('UPDATE media_asset SET storage_path=$1 WHERE id=$2', [two.storage_path, other.id]);
  const result = await agent.post(mutation('delete')).send({ ids: [one.id, two.id, one.id], confirm: 'DELETE' });
  expect(result.status).toBe(200); expect(result.body).toEqual({ deleted: 2, files_pending: false });
  expect(existsSync(firstFile)).toBe(false); expect(existsSync(sharedFile)).toBe(true);
  expect((await pool.query('SELECT id FROM media_asset')).rows).toEqual([{ id: other.id }]);
  const single = await asset(); expect((await agent.post(mutation('delete')).send({ ids: [single.id], confirm: 'DELETE' })).body.deleted).toBe(1);
});
test('混入其他手机、已关联或不存在图片时整批拒绝', async () => {
  const own = await asset(), foreign = await asset(b), linked = await asset(); await link(linked.id); const saved = file(own);
  for (const id of [foreign.id, linked.id, '9223372036854775807']) {
    expect((await agent.post(mutation('delete')).send({ ids: [own.id, id], confirm: 'DELETE' })).status).toBe(409);
    expect((await pool.query('SELECT id FROM media_asset WHERE id=$1', [own.id])).rowCount).toBe(1); expect(existsSync(saved)).toBe(true);
  }
});
test('路径校验失败回滚整批，文件不先于事务删除', async () => {
  const good = await asset(), bad = await asset(); const saved = file(good);
  await pool.query("UPDATE media_asset SET storage_path='../unsafe.png' WHERE id=$1", [bad.id]);
  expect((await agent.post(mutation('delete')).send({ ids: [good.id, bad.id], confirm: 'DELETE' })).status).toBe(409);
  expect((await pool.query('SELECT id FROM media_asset')).rowCount).toBe(2); expect(existsSync(saved)).toBe(true);
});
test('删除要求登录、请求标识及明确确认，拒绝无效ID', async () => {
  const row = await asset(), body = { ids: [row.id], confirm: 'DELETE' };
  expect((await request(app).post(mutation('delete')).set('X-Dashboard-Request', '1').send(body)).status).toBe(401);
  const plain = request.agent(app); await plain.post('/api/v1/auth/login').set('X-Dashboard-Request', '1').send({ username: 'admin', password: 'adminhaha' });
  expect((await plain.post(mutation('delete')).send(body)).status).toBe(403);
  for (const value of [{ ids: [row.id] }, { ids: [], confirm: 'DELETE' }, ...['0', '-1', '1.2', '9223372036854775808'].map(id => ({ ids: [id], confirm: 'DELETE' }))]) {
    expect((await agent.post(mutation('delete')).send(value)).status).toBe(400);
  }
  expect((await pool.query('SELECT id FROM media_asset')).rowCount).toBe(1);
});
for (const days of [1, 7, 30]) test(`保留${days}天按精确接收时间，恰好截止时间保留`, async () => {
  const now = Date.parse('2026-10-23T04:00:00Z'), cutoff = now - days * 86_400_000;
  const clock = vi.spyOn(Date, 'now').mockReturnValue(now);
  try {
    await asset(a, new Date(cutoff - 1).toISOString()); const boundary = await asset(a, new Date(cutoff).toISOString());
    const p = await preview(days); expect(p.total).toBe(1); expect(p.cutoff).toBe(new Date(cutoff).toISOString());
    expect((await batch(p.token)).body).toMatchObject({ processed: 1, total: 1, deleted: 1, skipped: 0, done: true });
    expect((await pool.query('SELECT id FROM media_asset')).rows).toEqual([{ id: boundary.id }]);
  } finally { clock.mockRestore(); }
});
test('日期筛选冻结预览，后到图片和新关联/已变更图片保留', async () => {
  await asset(a, '2020-10-22T00:00:00Z'); const linked = await asset(a, '2020-10-22T00:00:00Z'), changed = await asset(a, '2020-10-22T00:00:00Z');
  const outside = await asset(a, '2020-10-21T00:00:00Z'), foreign = await asset(b, '2020-10-22T00:00:00Z');
  const p = await preview(1, { from: '2020-10-22', to: '2020-10-22' }); expect(p.total).toBe(3);
  await link(linked.id); await pool.query('UPDATE media_asset SET byte_size=99 WHERE id=$1', [changed.id]);
  const late = await asset(a, '2020-10-22T00:00:00Z');
  expect((await batch(p.token)).body).toMatchObject({ total: 3, processed: 3, deleted: 1, skipped: 2, done: true });
  expect((await pool.query('SELECT id FROM media_asset')).rows.map(row => row.id).sort()).toEqual([linked.id, changed.id, outside.id, foreign.id, late.id].sort());
});
test('清理分批200张，重试旧offset幂等', async () => {
  for (let i = 0; i < 201; i++) await asset(a, '2020-01-01T00:00:00Z');
  const p = await preview(); const first = await batch(p.token); expect(first.body).toMatchObject({ total: 201, processed: 200, deleted: 200, done: false });
  expect((await batch(p.token)).body).toEqual(first.body);
  expect((await batch(p.token, 200)).body).toMatchObject({ processed: 201, deleted: 201, done: true });
  expect((await pool.query('SELECT id FROM media_asset')).rowCount).toBe(0);
});
test('清理令牌绑定手机、有效期、明确确认和进度', async () => {
  await asset(a, '2020-01-01T00:00:00Z'); const p = await preview();
  expect((await batch(p.token, 0, b)).status).toBe(410); expect((await batch(p.token, 1)).status).toBe(409);
  expect((await agent.post(mutation('cleanup/batch')).send({ token: p.token, offset: 0 })).status).toBe(400);
  const clock = vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 16 * 60_000);
  try { expect((await batch(p.token)).status).toBe(410); } finally { clock.mockRestore(); }
  expect((await pool.query('SELECT id FROM media_asset')).rowCount).toBe(1);
  for (const value of [{ days: 2 }, { days: 1, from: 'bad' }, { days: 7, from: '2026-10-23', to: '2026-10-22' }]) expect((await agent.post(mutation('cleanup/preview')).send(value)).status).toBe(400);
});

test('提交后文件清理失败保留清单，后台重试回收文件', async () => {
  const row = await asset(), saved = file(row);
  // A missing synthetic dependency fails only the post-commit cleanup transaction.
  await pool.query('DROP TABLE sticker');
  const result = await agent.post(mutation('delete')).send({ ids: [row.id], confirm: 'DELETE' });
  expect(result.status).toBe(200); expect(result.body).toEqual({ deleted: 1, files_pending: true });
  expect((await pool.query('SELECT id FROM media_asset')).rowCount).toBe(0); expect(existsSync(saved)).toBe(true);
  expect((await pool.query('SELECT key FROM runtime_setting')).rowCount).toBe(1);
  await pool.query('CREATE TABLE sticker(file_name TEXT)'); await retryDeviceFileCleanup(pool);
  expect(existsSync(saved)).toBe(false); expect((await pool.query('SELECT key FROM runtime_setting')).rowCount).toBe(0);
});
