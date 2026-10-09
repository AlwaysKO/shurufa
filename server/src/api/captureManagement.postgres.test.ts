import { createHash, randomUUID } from 'node:crypto';
import { readFileSync, readdirSync, realpathSync } from 'node:fs';
import { join } from 'node:path';
import pg from 'pg';
import request from 'supertest';
import sharp from 'sharp';
import { beforeAll, beforeEach, afterAll, expect, it, vi } from 'vitest';
import { createApp } from '../app.js';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';

const cluster = process.env.CAPTURE_MANAGEMENT_TEST_CLUSTER;
if (cluster && (!cluster.startsWith('/tmp/shurufa-capture-management.') ||
  readFileSync(join(cluster, 'test-instance-only'), 'utf8') !== 'capture-management-only')) throw Error('非独立测试实例');
const test = cluster ? it : it.skip;
const A = randomUUID(), B = randomUUID();
let pool: pg.Pool, app: ReturnType<typeof createApp>, agent: Awaited<ReturnType<typeof authenticatedRequest>>, bytes: Buffer;
let verified = false;
const connection = () => ({ host: join(cluster!, 'socket'), port: 5432, user: 'capture_management_test', database: 'capture_management_test' });
beforeAll(async () => {
  if (!cluster) return;
  pool = new pg.Pool(connection());
  const identity = (await pool.query("SELECT current_setting('data_directory') AS dir,current_database() AS db")).rows[0];
  expect(realpathSync(identity.dir)).toBe(realpathSync(join(cluster, 'data')));
  expect(identity.db).toBe('capture_management_test'); verified = true;
  bytes = await sharp({ create: { width: 16, height: 20, channels: 3, background: '#abc' } }).webp().toBuffer();
});
beforeEach(async () => {
  if (!cluster) return;
  if (!verified) throw Error('禁止业务库测试');
  await pool.end();
  const schema = 'test_' + randomUUID().replaceAll('-', '');
  pool = new pg.Pool({ ...connection(), options: `-c search_path=${schema}` });
  await pool.query(`CREATE SCHEMA ${schema}; CREATE TABLE device(id UUID PRIMARY KEY,last_seen_at TIMESTAMPTZ DEFAULT now());
    CREATE TABLE runtime_setting(key TEXT PRIMARY KEY,value TEXT)`);
  const dir = new URL('../../migrations/', import.meta.url);
  for (const file of readdirSync(dir).filter(x => /^(043|044|045|046)_/.test(x)).sort()) {
    const sql = readFileSync(new URL(file, dir), 'utf8'); await pool.query(sql); await pool.query(sql);
  }
  app = createApp(pool); agent = await authenticatedRequest(app);
});
afterAll(async () => { await pool?.end(); });
const page = (extra = {}) => ({ id: randomUUID(), package_name: 'com.tencent.mm', kind: 'conversation_list',
  captured_at: Date.parse('2026-01-01T00:00:00+08:00'), width: 16, height: 20,
  sha256: createHash('sha256').update(bytes).digest('hex'), mime_type: 'image/webp', file_base64: bytes.toString('base64'), ...extra });
const visit = (extra = {}) => ({ id: randomUUID(), platform: 'wechat', observation_kind: 'unconfirmed_feed',
  entered_at: Date.parse('2026-01-01T00:00:00+08:00'), ended_at: Date.parse('2026-01-01T00:00:10+08:00'),
  duration_ms: 10000, exit_reason: 'page_changed', complete: true, first_image_id: null, last_image_id: null, ...extra });
const mobile = (type: 'page-captures' | 'video-visits', data: object, user = A) =>
  request(app).post(`/api/v1/mobile/${type}`).set('X-Device-Id', user).send(data);
const base = (type: 'page-captures' | 'video-visits', user = A) => `/api/v1/dashboard/${type}?user_id=${user}`;
const list = (type: 'page-captures' | 'video-visits', filters = '', user = A) => agent.get(base(type, user) + filters);
const remove = (type: 'page-captures' | 'video-visits', ids: string[], user = A) =>
  agent.post(`/api/v1/dashboard/${type}/delete?user_id=${user}`).send({ ids, confirm: 'DELETE' });
const preview = (type: 'page-captures' | 'video-visits', extra = {}, user = A) =>
  agent.post(`/api/v1/dashboard/${type}/cleanup/preview?user_id=${user}`).send({ days: 7, ...extra });
const batch = (type: 'page-captures' | 'video-visits', token: string, offset = 0, user = A) =>
  agent.post(`/api/v1/dashboard/${type}/cleanup/batch?user_id=${user}`).send({ token, offset, confirm: 'DELETE' });

test('应用列表排除信息流及视频引用图，视频仍能访问共享原图且不会合并不同访问', async () => {
  const normal = page(), feed = page({ kind: 'media_feed' }), linked = page({ kind: 'image_post' });
  for (const r of [normal, feed, linked]) expect((await mobile('page-captures', r)).status).toBe(200);
  for (let i = 0; i < 2; i++) expect((await mobile('video-visits', visit({ first_image_id: linked.id }))).status).toBe(200);
  const pages = await list('page-captures'); expect(pages.status).toBe(200);
  expect(pages.body.records.map((r: { id: string }) => r.id)).toEqual([normal.id]);
  expect((await list('page-captures', '&kind=media_feed')).body.total).toBe(0);
  expect((await list('video-visits')).body.total).toBe(2);
  const picture = await agent.get(`/api/v1/dashboard/page-captures/${linked.id}/image?user_id=${A}`);
  expect(picture.status).toBe(200); expect(picture.body).toEqual(bytes);
});

test('北京日期含首尾日、名称备注关键词与视频状态筛选，元信息编辑不改原始数据和上传ACK', async () => {
  const before = page({ captured_at: Date.parse('2025-12-31T23:59:59.999+08:00') });
  const first = page(), last = page({ captured_at: Date.parse('2026-01-01T23:59:59.999+08:00') });
  const after = page({ captured_at: Date.parse('2026-01-02T00:00:00+08:00') });
  for (const r of [before, first, last, after]) await mobile('page-captures', r);
  const edited = await agent.patch(`/api/v1/dashboard/page-captures/${first.id}?user_id=${A}`).send({ title: '原图名称', note: '备注100%_匹配' });
  expect(edited.status).toBe(200); expect(edited.body).toEqual({ ok: true });
  expect((await list('page-captures', '&from=2026-01-01&to=2026-01-01')).body.total).toBe(2);
  const searched = await list('page-captures', '&q=' + encodeURIComponent('100%_'));
  expect(searched.body.records).toHaveLength(1); expect(searched.body.records[0]).toMatchObject({ id: first.id, title: '原图名称', note: '备注100%_匹配' });
  expect((await mobile('page-captures', first)).body).toEqual({ ok: true, id: first.id, sha256: first.sha256 });
  const v = visit(); await mobile('video-visits', v);
  await mobile('video-visits', visit({ observation_kind: 'confirmed_video', complete: false, exit_reason: 'interrupted', ended_at: null, duration_ms: null }));
  expect((await agent.patch(`/api/v1/dashboard/video-visits/${v.id}?user_id=${A}`).send({ title: '视频名', note: '片段' })).status).toBe(200);
  const videos = await list('video-visits', '&observation_kind=unconfirmed_feed&complete=true&exit_reason=page_changed&q=' + encodeURIComponent('片段'));
  expect(videos.body.total).toBe(1); expect(videos.body.records[0]).toMatchObject({ ...v, title: '视频名', note: '片段' });
  expect((await mobile('video-visits', v)).body).toEqual({ ok: true, record: v });
});

test('删除有设备隔离、严格确认及原始载荷墓碑，重试不复活、变内容冲突', async () => {
  const p = page(), v = visit();
  for (const u of [A, B]) { await mobile('page-captures', p, u); await mobile('video-visits', v, u); }
  for (const [type, r] of [['page-captures', p], ['video-visits', v]] as const) {
    expect((await agent.post(`/api/v1/dashboard/${type}/delete?user_id=${A}`).send({ ids: [r.id] })).status).toBe(400);
    expect((await remove(type, [r.id])).body).toEqual({ deleted: 1 });
    expect((await remove(type, [r.id])).body).toEqual({ deleted: 0 });
    expect((await list(type)).body.total).toBe(0); expect((await list(type, '', B)).body.total).toBe(1);
    const retry = await mobile(type, r); expect(retry.status).toBe(200); expect(retry.body.discarded).toBe(true);
    expect((await list(type)).body.total).toBe(0);
    const changed = type === 'page-captures' ? { ...r, kind: 'payment' } : { ...r, duration_ms: 9000 };
    expect((await mobile(type, changed)).status).toBe(409);
  }
  expect((await agent.get(`/api/v1/dashboard/page-captures/${p.id}/image?user_id=${A}`)).status).toBe(404);
  expect((await agent.patch(`/api/v1/dashboard/video-visits/${v.id}?user_id=${B}`).send({ duration_ms: 5 })).status).toBe(400);
  expect((await request(app).post(`/api/v1/dashboard/video-visits/delete?user_id=${B}`).send({ ids: [v.id], confirm: 'DELETE' })).status).toBe(401);
});

test('删除视频只回收无人使用帧，页面删除不能破坏观看时长，晚到新访问保留时间不恢复已删图片', async () => {
  const shared = page({ kind: 'media_feed' }), last = page({ kind: 'media_feed' });
  await mobile('page-captures', shared); await mobile('page-captures', last);
  const one = visit({ first_image_id: shared.id, last_image_id: last.id }), two = visit({ first_image_id: shared.id });
  await mobile('video-visits', one); await mobile('video-visits', two);
  expect((await remove('page-captures', [shared.id])).body).toEqual({ deleted: 0 });
  expect((await remove('video-visits', [one.id])).body).toEqual({ deleted: 1 });
  expect((await pool.query('SELECT id FROM page_capture WHERE user_id=$1', [A])).rows.map(r => r.id)).toEqual([shared.id]);
  expect((await list('video-visits')).body.records[0]).toMatchObject(two);
  expect((await mobile('video-visits', one)).status).toBe(200); // 墓碑ACK先于已经回收的依赖检查。
  await remove('video-visits', [two.id]);
  expect((await pool.query('SELECT id FROM page_capture WHERE user_id=$1', [A])).rows).toHaveLength(0);
  const late = visit({ first_image_id: shared.id });
  expect((await mobile('video-visits', late)).status).toBe(200);
  expect((await list('video-visits')).body.records[0]).toMatchObject({ id: late.id, duration_ms: 10000, first_image_id: null });
  expect((await mobile('page-captures', shared)).body.discarded).toBe(true);
});

test('清理冻结范围及内容签名，排除新增/修改行、跨手机/跨列表token，重复批次幂等', async () => {
  const keep = page(), changed = page(), old = page();
  for (const r of [keep, changed, old]) await mobile('page-captures', r);
  const p = await preview('page-captures'); expect(p.status).toBe(200); expect(p.body.total).toBe(3);
  expect(Date.parse(p.body.cutoff)).toBeLessThanOrEqual(Date.now() - 7 * 86400000);
  const late = page(); await mobile('page-captures', late);
  await agent.patch(`/api/v1/dashboard/page-captures/${changed.id}?user_id=${A}`).send({ title: '已修改', note: '' });
  expect((await batch('page-captures', p.body.token, 0, B)).status).toBe(410);
  expect((await batch('video-visits', p.body.token)).status).toBe(410);
  expect((await batch('page-captures', p.body.token, 1)).status).toBe(409);
  const r = await batch('page-captures', p.body.token); expect(r.status).toBe(200);
  expect(r.body).toEqual({ processed: 3, total: 3, deleted: 2, skipped: 1, done: true });
  expect((await batch('page-captures', p.body.token)).body).toEqual(r.body);
  expect((await list('page-captures')).body.records.map((x: { id: string }) => x.id).sort()).toEqual([changed.id, late.id].sort());
  const clock = vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 16 * 60000);
  try { expect((await batch('page-captures', p.body.token)).status).toBe(410); } finally { clock.mockRestore(); }
});

test('清理视频分页200条、视频筛选与业务删除原子回滚', async () => {
  for (let i = 0; i < 202; i++) {
    const uploaded = await mobile('video-visits', visit());
    expect(uploaded.status, JSON.stringify(uploaded.body)).toBe(200);
  }
  await mobile('video-visits', visit({ platform: 'douyin' }));
  const p = await preview('video-visits', { platform: 'wechat', complete: 'true', observation_kind: 'unconfirmed_feed' });
  expect(p.status).toBe(200); expect(p.body.total).toBe(202);
  const first = await batch('video-visits', p.body.token); expect(first.body).toMatchObject({ processed: 200, deleted: 200, done: false });
  expect((await batch('video-visits', p.body.token, 200)).body).toEqual({ processed: 202, total: 202, deleted: 202, skipped: 0, done: true });
  const left = (await list('video-visits')).body.records[0];
  await pool.query(`CREATE FUNCTION fail_management_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated failure'; END $$;
    CREATE TRIGGER fail_management_delete BEFORE DELETE ON video_visit FOR EACH ROW EXECUTE FUNCTION fail_management_delete()`);
  expect((await remove('video-visits', [left.id])).status).toBe(500);
  expect((await list('video-visits')).body.total).toBe(1);
  expect((await pool.query('SELECT * FROM capture_tombstone WHERE user_id=$1 AND id=$2 AND record_type=$3', [A, left.id, 'video'])).rowCount).toBe(0);
});

test('过滤及元信息只接受严格标量，写操作鉴权与路径隔离', async () => {
  const p = page(); await mobile('page-captures', p);
  for (const type of ['page-captures', 'video-visits'] as const) {
    for (const q of ['&from=2026-02-30', '&to=bad', '&from=2026-01-02&to=2026-01-01', '&q[]=x'])
      expect((await list(type, q)).status, q).toBe(400);
    expect((await preview(type, { days: 2 })).status).toBe(400);
    expect((await remove(type, ['bad'])).status).toBe(400);
    expect((await batch(type, 'unknown')).status).toBe(410);
  }
  for (const q of ['&complete=1', '&complete[]=true', '&observation_kind=unknown', '&exit_reason=unknown'])
    expect((await list('video-visits', q)).status).toBe(400);
  for (const body of [{ title: 'x', note: '', duration_ms: 1 }, { title: 1, note: '' }, { title: 'x'.repeat(201), note: '' }])
    expect((await agent.patch(`/api/v1/dashboard/page-captures/${p.id}?user_id=${A}`).send(body)).status).toBe(400);
  expect((await agent.patch(`/api/v1/dashboard/page-captures/${p.id}?user_id=${B}`).send({ title: 'x', note: '' })).status).toBe(404);
  expect((await agent.post(`/api/v1/dashboard/page-captures?user_id=${A}`).send(p)).status).toBe(404);
});

test('并发删除和移动重试不会复活图片或视频，删除设备只级联自己的墓碑', async () => {
  const p = page({ kind: 'media_feed' }), v = visit({ first_image_id: p.id });
  await mobile('page-captures', p); await mobile('video-visits', v);
  const others = page(); await mobile('page-captures', others, B); await remove('page-captures', [others.id], B);
  const results = await Promise.all([
    remove('video-visits', [v.id]), ...Array.from({ length: 4 }, () => mobile('video-visits', v)),
    ...Array.from({ length: 4 }, () => mobile('page-captures', p)),
  ]);
  expect(results.map(r => r.status)).toEqual(Array(9).fill(200));
  expect((await list('video-visits')).body.total).toBe(0);
  expect((await pool.query('SELECT id FROM page_capture WHERE user_id=$1', [A])).rowCount).toBe(0);
  expect((await pool.query('SELECT id FROM capture_tombstone WHERE user_id=$1', [A])).rowCount).toBe(2);
  await pool.query('DELETE FROM device WHERE id=$1', [A]);
  expect((await pool.query('SELECT id FROM capture_tombstone WHERE user_id=$1', [A])).rowCount).toBe(0);
  expect((await pool.query('SELECT id FROM capture_tombstone WHERE user_id=$1', [B])).rowCount).toBe(1);
});

test('清理失败可用原offset重试，视频帧清理失败回滚观看和两类墓碑', async () => {
  const p = page({ kind: 'media_feed' }), v = visit({ first_image_id: p.id });
  await mobile('page-captures', p); await mobile('video-visits', v);
  const selection = await preview('video-visits'); expect(selection.body.total).toBe(1);
  await pool.query(`CREATE FUNCTION fail_frame_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated frame deletion failure'; END $$;
    CREATE TRIGGER fail_frame_delete BEFORE DELETE ON page_capture FOR EACH ROW EXECUTE FUNCTION fail_frame_delete()`);
  expect((await batch('video-visits', selection.body.token)).status).toBe(500);
  expect((await pool.query('SELECT id FROM capture_tombstone WHERE user_id=$1', [A])).rowCount).toBe(0);
  expect((await list('video-visits')).body.records[0]).toMatchObject(v);
  await pool.query('DROP TRIGGER fail_frame_delete ON page_capture');
  expect((await batch('video-visits', selection.body.token)).body).toEqual({ processed: 1, total: 1, deleted: 1, skipped: 0, done: true });
});

test('预览后被视频引用的页面跳过，跨平台已删图片不能满足新访问依赖', async () => {
  const p = page({ kind: 'image_post' }); await mobile('page-captures', p);
  const selection = await preview('page-captures');
  const v = visit({ first_image_id: p.id }); await mobile('video-visits', v);
  expect((await batch('page-captures', selection.body.token)).body).toEqual({ processed: 1, total: 1, deleted: 0, skipped: 1, done: true });
  await remove('video-visits', [v.id]);
  const wrongPlatform = await mobile('video-visits', visit({ platform: 'douyin', first_image_id: p.id }));
  expect(wrongPlatform.status).toBe(409); expect(wrongPlatform.body.error).toBe('missing_page_captures');
});
