import { createHash, randomUUID } from 'node:crypto';
import { existsSync, readFileSync, readdirSync, realpathSync } from 'node:fs';
import { join } from 'node:path';
import pg from 'pg';
import request from 'supertest';
import sharp from 'sharp';
import { beforeAll, beforeEach, afterAll, expect, it } from 'vitest';
import { createApp } from '../app.js';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';

const cluster = process.env.PAGE_CAPTURES_TEST_CLUSTER;
if (cluster && (!cluster.startsWith('/tmp/shurufa-page-captures.') ||
  readFileSync(join(cluster, 'test-instance-only'), 'utf8') !== 'page-captures-only')) throw Error('独立实例验证失败');
const test = cluster ? it : it.skip;
const A = randomUUID(), B = randomUUID();
const hash = (value: Buffer) => createHash('sha256').update(value).digest('hex');
let pool: pg.Pool, app: ReturnType<typeof createApp>, agent: Awaited<ReturnType<typeof authenticatedRequest>>, bytes: Buffer;
let verified = false;
const record = (extra = {}) => ({ id: randomUUID(), package_name: 'com.tencent.mm', kind: 'conversation_list',
  captured_at: 1780000000000, width: 16, height: 20, sha256: hash(bytes), mime_type: 'image/webp', file_base64: bytes.toString('base64'), ...extra });
const post = (body: unknown, user = A) => request(app).post('/api/v1/mobile/page-captures').set('X-Device-Id', user).send(body as object);
const list = (user = A, query = '') => agent.get(`/api/v1/dashboard/page-captures?user_id=${user}${query}`);
const image = (id: string, user = A) => agent.get(`/api/v1/dashboard/page-captures/${id}/image?user_id=${user}`);
beforeAll(async () => {
  if (!cluster) return;
  pool = new pg.Pool({ host: join(cluster, 'socket'), port: 5432, user: 'page_capture_test', database: 'page_captures_test' });
  const identity = (await pool.query("SELECT current_setting('data_directory') AS dir,current_database() AS db")).rows[0];
  expect(realpathSync(identity.dir)).toBe(realpathSync(join(cluster, 'data')));
  expect(identity.db).toBe('page_captures_test'); verified = true;
  bytes = await sharp({ create: { width: 16, height: 20, channels: 3, background: '#abc' } }).webp().toBuffer();
});
beforeEach(async () => {
  if (!cluster) return;
  if (!verified) throw Error('禁止业务库测试');
  await pool.end();
  const schema = 'test_' + randomUUID().replaceAll('-', '');
  pool = new pg.Pool({ host: join(cluster, 'socket'), port: 5432, user: 'page_capture_test', database: 'page_captures_test', options: `-c search_path=${schema}` });
  await pool.query(`CREATE SCHEMA ${schema}; CREATE TABLE device(id UUID PRIMARY KEY,last_seen_at TIMESTAMPTZ DEFAULT now());
    CREATE TABLE runtime_setting(key TEXT PRIMARY KEY,value TEXT)`);
  const migration = new URL('../../migrations/043_page_captures.sql', import.meta.url);
  // 初次RED允许迁移文件尚不存在；真正失败应来自缺失接口，不伪造实现。
  if (existsSync(migration)) { const sql = readFileSync(migration, 'utf8'); await pool.query(sql); await pool.query(sql); }
  // 页面管理与视频共用资源，最小fixture也加载其依赖和管理迁移。
  for (const name of ['044_video_visits.sql', '045_video_visit_observation_kind.sql', '046_capture_management.sql'])
    await pool.query(readFileSync(new URL('../../migrations/' + name, import.meta.url), 'utf8'));
  app = createApp(pool); agent = await authenticatedRequest(app);
});
afterAll(async () => { await pool?.end(); });

test('图片与元信息原子入库，重传和并发重传幂等，同ID变内容409', async () => {
  const r = record();
  const first = await post(r);
  expect(first.status).toBe(200); expect(first.body).toEqual({ ok: true, id: r.id, sha256: r.sha256 });
  const retries = await Promise.all(Array.from({ length: 4 }, () => post(r)));
  expect(retries.every(x => x.status === 200 && x.body.id === r.id)).toBe(true);
  expect((await list()).body.total).toBe(1);
  for (const extra of [{ kind: 'payment' }, { captured_at: r.captured_at + 1 }, { package_name: 'com.ss.android.ugc.aweme' }])
    expect((await post({ ...r, ...extra })).status).toBe(409);
  expect((await image(r.id)).body).toEqual(bytes);
});
test('两平台分页独立，列表无原图，登录与设备隔离，图片禁止缓存', async () => {
  const r = record(); await post(r); await post(record({ kind: 'payment' }));
  await post(record({ package_name: 'com.ss.android.ugc.aweme', kind: 'image_post' })); await post(r, B);
  expect((await list(A, '&platform=wechat')).body.total).toBe(2);
  expect((await list(A, '&platform=douyin&kind=image_post')).body.total).toBe(1);
  const row = (await list()).body.records.find((x: { id: string }) => x.id === r.id);
  expect(row).toMatchObject({ id: r.id, platform: 'wechat', kind: 'conversation_list', width: 16, height: 20 });
  expect(row.screenshot).toBeUndefined(); expect(row.file_base64).toBeUndefined();
  const pic = await image(r.id); expect(pic.body).toEqual(bytes); expect(pic.headers['cache-control']).toBe('no-store');
  expect(pic.headers['x-content-type-options']).toBe('nosniff');
  expect((await image(randomUUID(), B)).status).toBe(404);
  const privateRecord = record(); await post(privateRecord);
  expect((await image(privateRecord.id, B)).status).toBe(404);
  expect((await request(app).get(`/api/v1/dashboard/page-captures?user_id=${A}`)).status).toBe(401);
  expect((await request(app).get(`/api/v1/dashboard/page-captures/${r.id}/image?user_id=${A}`)).status).toBe(401);
  expect((await request(app).post('/api/v1/mobile/page-captures').send(r)).status).toBe(400);
});
test('严格拒绝越界平台/聊天类型/身份字段/时间/尺寸/哈希/伪图与非规范Base64', async () => {
  const fake = Buffer.from('not an image');
  for (const extra of [{ package_name: 'com.taobao.taobao' }, { kind: 'chat' }, { kind: 'video' }, { user_id: B }, { device_id: B },
    { id: 'bad' }, { captured_at: 0 }, { captured_at: Date.now() + 3600000 }, { captured_at: '1780000000000' },
    { width: 17 }, { width: 0 }, { height: 9000 }, { sha256: 'f'.repeat(64) }, { mime_type: 'text/html' },
    { mime_type: 'image/png' }, { file_base64: record().file_base64 + '\n' },
    { file_base64: fake.toString('base64'), sha256: hash(fake) }]) expect((await post(record(extra))).status, JSON.stringify(extra)).toBe(400);
  expect((await list()).body.total).toBe(0);
});
test('参数校验和分页有界，设备删除只级联自己的图片', async () => {
  for (let i = 0; i < 21; i++) await post(record({ captured_at: 1780000000000 + i }));
  const b = record(); await post(b, B);
  const first = (await list()).body, second = (await list(A, '&page=2')).body;
  expect(first.total).toBe(21); expect(first.records).toHaveLength(20); expect(second.records).toHaveLength(1);
  expect(new Set([...first.records, ...second.records].map(x => x.id)).size).toBe(21);
  for (const q of ['&page=0', '&page=1.5', '&platform[]=wechat', '&platform=qq', '&kind=chat']) expect((await list(A, q)).status).toBe(400);
  expect((await image('bad')).status).toBe(400);
  await pool.query('DELETE FROM device WHERE id=$1', [A]);
  expect((await list()).body.total).toBe(0); expect((await list(B)).body.total).toBe(1); expect((await image(b.id, B)).body).toEqual(bytes);
});
test('关闭保存有明确绑定回执但不落图，坏请求仍拒绝', async () => {
  await pool.query('INSERT INTO runtime_setting VALUES($1,$2)', [`device_save_uploads:${A}`, 'false']);
  const r = record();
  expect((await post(r)).body).toEqual({ ok: true, id: r.id, sha256: r.sha256, discarded: true });
  expect((await post(record({ kind: 'chat' }))).status).toBe(400);
  expect((await list()).body.total).toBe(0);
});
test('数据库故障不返回成功，事务回滚后同ID可安全重试', async () => {
  await pool.query(`CREATE FUNCTION fail_capture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated failure'; END $$;
    CREATE TRIGGER fail_capture BEFORE INSERT ON page_capture FOR EACH ROW EXECUTE FUNCTION fail_capture()`);
  const r = record(); expect((await post(r)).status).toBe(500);
  expect((await list()).body.total).toBe(0);
  expect((await pool.query('SELECT id FROM device WHERE id=$1', [A])).rowCount).toBe(0);
  await pool.query('DROP TRIGGER fail_capture ON page_capture');
  expect((await post(r)).status).toBe(200); expect((await image(r.id)).body).toEqual(bytes);
});

test('支持的五类非聊天页面和PNG实际解码，改变图片的同ID重传不覆盖', async () => {
  for (const kind of ['conversation_list', 'payment', 'media_feed', 'image_post', 'mini_app'])
    expect((await post(record({ kind }))).status).toBe(200);
  const png = await sharp(bytes).png().toBuffer();
  const r = record({ mime_type: 'image/png', file_base64: png.toString('base64'), sha256: hash(png) });
  expect((await post(r)).status).toBe(200);
  const other = await sharp({ create: { width: 16, height: 20, channels: 3, background: '#123' } }).png().toBuffer();
  expect((await post({ ...r, file_base64: other.toString('base64'), sha256: hash(other) })).status).toBe(409);
  expect((await image(r.id)).body).toEqual(png);
});
test('图片字节上限、截断解码、非对象、缺字段和非标量筛选均拒绝', async () => {
  const huge = Buffer.alloc(3 * 1024 * 1024 + 1), truncated = bytes.subarray(0, 32);
  for (const data of [huge, truncated]) expect((await post(record({ file_base64: data.toString('base64'), sha256: hash(data) }))).status).toBe(400);
  for (const body of [[], {}, { id: randomUUID() }]) expect((await post(body)).status).toBe(400);
  for (const q of ['&kind[]=payment', '&page[]=1', '&page=1000000']) expect((await list(A, q)).status).toBe(400);
  expect((await list()).body.total).toBe(0);
});
test('并发相同ID不同载荷只有一个成功且原始记录可重传', async () => {
  const a = record(), b = { ...a, kind: 'payment' };
  const results = await Promise.all([post(a), post(b)]);
  expect(results.map(x => x.status).sort()).toEqual([200, 409]);
  const saved = results[0].status === 200 ? a : b;
  expect((await post(saved)).status).toBe(200);
  expect((await list()).body.records[0].kind).toBe(saved.kind);
  expect((await list()).body.total).toBe(1);
});
test('提交阶段失败不能给手机清理回执', async () => {
  await pool.query(`CREATE FUNCTION fail_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated commit failure'; END $$;
    CREATE CONSTRAINT TRIGGER fail_commit AFTER INSERT ON page_capture DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fail_commit()`);
  expect((await post(record())).status).toBe(500);
  expect((await list()).body.total).toBe(0);
  expect((await pool.query('SELECT id FROM device WHERE id=$1', [A])).rowCount).toBe(0);
});

test('动画图片不能伪装为单帧页面图', async () => {
  const pixels = Buffer.alloc(16 * 40 * 3);
  pixels.fill(255, 16 * 20 * 3); // 两帧不同，防止编码器把相同帧折叠成单帧。
  const animated = await sharp(pixels, { raw: { width: 16, height: 40, pageHeight: 20, channels: 3 } }).webp().toBuffer();
  expect((await sharp(animated, { animated: true }).metadata()).pages).toBe(2);
  expect((await post(record({ file_base64: animated.toString('base64'), sha256: hash(animated) }))).status).toBe(400);
});


test('完整迁移链兼容，新页面不写聊天会话或消息', async () => {
  // 仅操作当前隔离schema；先撤掉最小fixture表，按正式顺序建完整表。
  await pool.query('DROP TABLE page_capture,device,runtime_setting CASCADE');
  const dir = new URL('../../migrations/', import.meta.url);
  for (const name of readdirSync(dir).filter(x => x.endsWith('.sql')).sort()) await pool.query(readFileSync(new URL(name, dir), 'utf8'));
  const r = record(); expect((await post(r)).status).toBe(200);
  expect((await image(r.id)).body).toEqual(bytes);
  for (const table of ['chat_conversation', 'chat_message']) expect(Number((await pool.query(`SELECT COUNT(*) AS n FROM ${table}`)).rows[0].n)).toBe(0);
});
