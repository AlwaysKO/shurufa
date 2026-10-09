import { createHash, randomUUID } from 'node:crypto';
import { existsSync, readFileSync, readdirSync, realpathSync } from 'node:fs';
import { join } from 'node:path';
import pg from 'pg';
import request from 'supertest';
import sharp from 'sharp';
import { beforeAll, beforeEach, afterAll, expect, it } from 'vitest';
import { createApp } from '../app.js';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';

const cluster = process.env.VIDEO_VISITS_TEST_CLUSTER;
if (cluster && (!cluster.startsWith('/tmp/shurufa-video-visits.') ||
  readFileSync(join(cluster, 'test-instance-only'), 'utf8') !== 'video-visits-only')) throw Error('独立实例验证失败');
const test = cluster ? it : it.skip;
const A = randomUUID(), B = randomUUID();
const hash = (value: Buffer) => createHash('sha256').update(value).digest('hex');
let pool: pg.Pool, app: ReturnType<typeof createApp>, agent: Awaited<ReturnType<typeof authenticatedRequest>>, bytes: Buffer;
let verified = false;
const record = (extra = {}) => ({ id: randomUUID(), platform: 'wechat', observation_kind: 'confirmed_video', entered_at: 1780000000000,
  ended_at: 1780000010000, duration_ms: 10000, exit_reason: 'background', complete: true,
  first_image_id: null, last_image_id: null, ...extra });
const post = (body: unknown, user = A) => request(app).post('/api/v1/mobile/video-visits').set('X-Device-Id', user).send(body as object);
const list = (user = A, query = '') => agent.get(`/api/v1/dashboard/video-visits?user_id=${user}${query}`);
beforeAll(async () => {
  if (!cluster) return;
  pool = new pg.Pool({ host: join(cluster, 'socket'), port: 5432, user: 'video_visit_test', database: 'video_visits_test' });
  const identity = (await pool.query("SELECT current_setting('data_directory') AS dir,current_database() AS db")).rows[0];
  expect(realpathSync(identity.dir)).toBe(realpathSync(join(cluster, 'data')));
  expect(identity.db).toBe('video_visits_test'); verified = true;
  bytes = await sharp({ create: { width: 16, height: 20, channels: 3, background: '#abc' } }).webp().toBuffer();
});
beforeEach(async () => {
  if (!cluster) return;
  if (!verified) throw Error('禁止业务库测试');
  await pool.end();
  const schema = 'test_' + randomUUID().replaceAll('-', '');
  pool = new pg.Pool({ host: join(cluster, 'socket'), port: 5432, user: 'video_visit_test', database: 'video_visits_test', options: `-c search_path=${schema}` });
  await pool.query(`CREATE SCHEMA ${schema}; CREATE TABLE device(id UUID PRIMARY KEY,last_seen_at TIMESTAMPTZ DEFAULT now());
    CREATE TABLE runtime_setting(key TEXT PRIMARY KEY,value TEXT)`);
  await pool.query(readFileSync(new URL('../../migrations/043_page_captures.sql', import.meta.url), 'utf8'));
  const migration = new URL('../../migrations/044_video_visits.sql', import.meta.url);
  // 初次RED允许迁移文件尚不存在；真正失败应来自缺失接口，不伪造实现。
  if (existsSync(migration)) { const sql = readFileSync(migration, 'utf8'); await pool.query(sql); await pool.query(sql); }
  const observationMigration = new URL('../../migrations/045_video_visit_observation_kind.sql', import.meta.url);
  if (existsSync(observationMigration)) { const sql = readFileSync(observationMigration, 'utf8'); await pool.query(sql); await pool.query(sql); }
  await pool.query(readFileSync(new URL('../../migrations/046_capture_management.sql', import.meta.url), 'utf8'));
  app = createApp(pool); agent = await authenticatedRequest(app);
});
afterAll(async () => { await pool?.end(); });

test('完整载荷回执、并发幂等与冲突不覆盖', async () => {
  const r = record();
  expect((await post(r)).body).toEqual({ ok: true, record: r });
  expect((await Promise.all([post(r), post(r)])).map(x => x.status)).toEqual([200, 200]);
  expect((await post({ ...r, duration_ms: 9000 })).status).toBe(409);
  const result = await list(); expect(result.status).toBe(200);
  expect(result.body.total).toBe(1); expect(result.body.records[0]).toMatchObject(r);
  expect(result.headers['cache-control']).toBe('no-store');
});
test('严格字段、时间、完成状态、身份和枚举校验', async () => {
  for (const extra of [{ user_id: B }, { platform: 'qq' }, { platform: ['wechat'] }, { id: 'bad' }, { entered_at: 0 },
    { entered_at: Date.now() + 3600000 }, { ended_at: null }, { ended_at: 0 }, { duration_ms: -1 },
    { duration_ms: 1.1 }, { duration_ms: Number.MAX_SAFE_INTEGER + 1 }, { duration_ms: null },
    { duration_ms: '10000' }, { complete: 'true' }, { exit_reason: 'unknown' },
    { exit_reason: 'interrupted' }, { complete: false }, { first_image_id: '' }, { last_image_id: 'bad' }])
    expect((await post(record(extra))).status, JSON.stringify(extra)).toBe(400);
  for (const body of [[], {}, { id: randomUUID() }]) expect((await post(body)).status).toBe(400);
  expect((await list()).body.total).toBe(0);
});
test('异常结束不伪造时间，时钟回拨保留未知时长，可信单调时长不强等墙钟差', async () => {
  for (const extra of [{ exit_reason: 'interrupted', complete: false, ended_at: null, duration_ms: null },
    { complete: false, ended_at: 1779999999000, duration_ms: null }, { duration_ms: 9000 }]) {
    const r = record(extra); expect((await post(r)).body).toEqual({ ok: true, record: r });
  }
});
test('图片依赖仅同设备同平台，缺失明确409且补图后可重试', async () => {
  const pic = randomUUID(), r = record({ first_image_id: pic });
  const missing = await post(r); expect(missing.status).toBe(409); expect(missing.body.error).toBe('missing_page_captures');
  expect(missing.body.missing_image_ids).toEqual([pic]);
  const page = { id: pic, package_name: 'com.tencent.mm', kind: 'media_feed', captured_at: r.entered_at,
    width: 16, height: 20, sha256: hash(bytes), mime_type: 'image/webp', file_base64: bytes.toString('base64') };
  const imagePost = (body: object, user = A) => request(app).post('/api/v1/mobile/page-captures').set('X-Device-Id', user).send(body);
  await imagePost(page, B); expect((await post(r)).status).toBe(409);
  await imagePost({ ...page, package_name: 'com.ss.android.ugc.aweme' }); expect((await post(r)).status).toBe(409);
  await pool.query('DELETE FROM page_capture WHERE user_id=$1 AND id=$2', [A, pic]);
  await imagePost(page); expect((await post(r)).body).toEqual({ ok: true, record: r });
  await pool.query('DELETE FROM device WHERE id=$1', [A]);
  expect((await list()).body.total).toBe(0);
  expect(Number((await pool.query('SELECT count(*) AS n FROM page_capture WHERE user_id=$1', [B])).rows[0].n)).toBe(1);
});
test('登录、手机隔离、分页筛选及设备级联', async () => {
  for (let i = 0; i < 21; i++) expect((await post(record({ entered_at: 1780000000000 + i }))).status).toBe(200);
  const b = record({ platform: 'douyin' }); await post(b, B);
  expect((await list()).body.records).toHaveLength(20);
  expect((await list(A, '&page=2')).body.records).toHaveLength(1);
  expect((await list(A, '&platform=douyin')).body.total).toBe(0);
  expect((await list(B)).body.records[0]).toMatchObject(b);
  for (const q of ['&page=0', '&page=1000000', '&platform[]=wechat', '&platform=qq']) expect((await list(A, q)).status).toBe(400);
  expect((await request(app).get(`/api/v1/dashboard/video-visits?user_id=${A}`)).status).toBe(401);
  expect((await request(app).post('/api/v1/mobile/video-visits').send(record())).status).toBe(400);
  await pool.query('DELETE FROM device WHERE id=$1', [A]);
  expect((await list()).body.total).toBe(0); expect((await list(B)).body.total).toBe(1);
});
test('关闭保存仍严格校验且完整绑定discarded回执，不写业务行', async () => {
  await pool.query('INSERT INTO runtime_setting VALUES($1,$2)', [`device_save_uploads:${A}`, 'false']);
  const r = record({ first_image_id: randomUUID() });
  expect((await post(r)).body).toEqual({ ok: true, record: r, discarded: true });
  expect((await post(record({ platform: 'qq' }))).status).toBe(400);
  expect((await list()).body.total).toBe(0);
});
test('COMMIT失败不能确认，成功/去重更新联系时间，失败/GET不推进', async () => {
  await pool.query(`CREATE FUNCTION fail_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated commit failure'; END $$;
    CREATE CONSTRAINT TRIGGER fail_commit AFTER INSERT ON video_visit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fail_commit()`);
  const r = record(); expect((await post(r)).status).toBe(500);
  expect((await list()).body.total).toBe(0);
  expect((await pool.query('SELECT id FROM device WHERE id=$1', [A])).rowCount).toBe(0);
  await pool.query('DROP TRIGGER fail_commit ON video_visit');
  expect((await post(r)).status).toBe(200);
  for (const action of [() => post(r), () => list(), () => post(record({ platform: 'qq' }))]) {
    await pool.query("UPDATE device SET last_seen_at='2000-01-01T00:00:00Z' WHERE id=$1", [A]);
    const response = await action();
    await new Promise(resolve => setTimeout(resolve, 30));
    const time = (await pool.query('SELECT last_seen_at FROM device WHERE id=$1', [A])).rows[0].last_seen_at.getTime();
    expect(time > 946684800000).toBe(response.status === 200 && response.body.ok === true);
  }
});
test('完整迁移可重复执行且新访问不污染聊天', async () => {
  await pool.query('DROP TABLE video_visit,page_capture,device,runtime_setting CASCADE');
  const dir = new URL('../../migrations/', import.meta.url);
  for (const name of readdirSync(dir).filter(x => x.endsWith('.sql')).sort()) await pool.query(readFileSync(new URL(name, dir), 'utf8'));
  expect((await post(record())).status).toBe(200);
  for (const table of ['chat_conversation', 'chat_message']) expect(Number((await pool.query(`SELECT COUNT(*) AS n FROM ${table}`)).rows[0].n)).toBe(0);
});

test('并发相同ID不同载荷只有一个胜出且可绑定重传，接收时间不刷新', async () => {
  const a = record(), b = { ...a, exit_reason: 'locked' };
  const responses = await Promise.all([post(a), post(b)]);
  expect(responses.map(r => r.status).sort()).toEqual([200, 409]);
  const winner = responses[0].status === 200 ? a : b;
  await pool.query("UPDATE video_visit SET received_at='2000-01-01T00:00:00Z' WHERE user_id=$1 AND id=$2", [A,a.id]);
  expect((await post(winner)).body).toEqual({ ok: true, record: winner });
  expect((await list()).body.records[0]).toMatchObject({ ...winner, received_at: '2000-01-01T00:00:00.000Z' });
});

test('信息流停留独立语义入库和回执，不能省略或伪造观察类型', async () => {
  const r = record({ observation_kind: 'unconfirmed_feed', exit_reason: 'page_changed' });
  expect((await post(r)).body).toEqual({ ok: true, record: r });
  expect((await list()).body.records[0]).toMatchObject(r);
  expect((await post({ ...r, observation_kind: 'confirmed_video' })).status).toBe(409);
  for (const observation_kind of [null, '', 'video', ['unconfirmed_feed']]) {
    expect((await post(record({ observation_kind }))).status).toBe(400);
  }
  const { observation_kind, ...missing } = record();
  expect((await post(missing)).status).toBe(400);
});
test('045迁移兼容旧已确认记录且重复执行保留新信息流类型', async () => {
  await pool.query('ALTER TABLE video_visit DROP COLUMN observation_kind');
  await pool.query('INSERT INTO device(id) VALUES($1)', [A]);
  const legacyId = randomUUID();
  await pool.query(`INSERT INTO video_visit(user_id,id,platform,entered_at,ended_at,duration_ms,exit_reason,complete,payload_sha256)
    VALUES($1,$2,'wechat',1780000000000,1780000010000,10000,'background',true,$3)`, [A,legacyId,'a'.repeat(64)]);
  await pool.query(readFileSync(new URL('../../migrations/045_video_visit_observation_kind.sql', import.meta.url), 'utf8'));
  expect((await pool.query('SELECT observation_kind FROM video_visit WHERE id=$1',[legacyId])).rows[0].observation_kind).toBe('confirmed_video');
  const r = record({ observation_kind: 'unconfirmed_feed', exit_reason: 'page_changed' });
  expect((await post(r)).status).toBe(200);
  await pool.query(readFileSync(new URL('../../migrations/045_video_visit_observation_kind.sql', import.meta.url), 'utf8'));
  expect((await pool.query('SELECT observation_kind FROM video_visit WHERE id=$1',[r.id])).rows[0].observation_kind).toBe('unconfirmed_feed');
  await expect(pool.query("UPDATE video_visit SET observation_kind='unknown' WHERE id=$1",[r.id])).rejects.toMatchObject({code:'23514'});
});
