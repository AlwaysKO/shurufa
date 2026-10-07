import { createHash, randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import pg from 'pg';
import request from 'supertest';
import sharp from 'sharp';
import { beforeEach, afterEach, expect, it } from 'vitest';
import { createApp } from '../app.js';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';

let pool: pg.Pool, app: ReturnType<typeof createApp>, agent: Awaited<ReturnType<typeof authenticatedRequest>>;
let bytes: Buffer;
let schema: string;
const enabled = process.env.NAVIGATION_TEST_LOCAL === '1';
const test = enabled ? it : it.skip;
const A = randomUUID(), B = randomUUID();
const hash = (data: Buffer) => createHash('sha256').update(data).digest('hex');
const record = (extra = {}) => ({ id: randomUUID(), platform: 'amap', origin: '我的位置', destination: '杭州东站',
  started_at: 1780000001000, overview_at: 1780000000000, sha256: hash(bytes), mime_type: 'image/png', file_base64: bytes.toString('base64'), ...extra });
const post = (body: object, user = A) => request(app).post('/api/v1/mobile/navigation-records').set('X-Device-Id', user).send(body);
const list = (user = A, query = '') => agent.get(`/api/v1/dashboard/navigation-records?user_id=${user}${query}`);
beforeEach(async () => {
  if (!enabled) return;
  if (!['localhost', '127.0.0.1', '::1'].includes(process.env.PGHOST ?? 'localhost')) throw Error('只允许本机独立测试 schema');
  schema = 'navigation_test_' + randomUUID().replaceAll('-', '');
  pool = new pg.Pool({ options: '-c search_path=' + schema });
  await pool.query('CREATE SCHEMA ' + schema);
  await pool.query('CREATE TABLE device(id UUID PRIMARY KEY); CREATE TABLE runtime_setting(key TEXT PRIMARY KEY,value TEXT)');
  await pool.query(readFileSync(new URL('../../migrations/038_navigation_records.sql', import.meta.url), 'utf8'));
  await pool.query(readFileSync(new URL('../../migrations/041_statistics_retention_tombstones.sql', import.meta.url), 'utf8'));
  bytes = await sharp({ create: { width: 16, height: 16, channels: 3, background: '#abc' } }).png().toBuffer();
  app = createApp(pool); agent = await authenticatedRequest(app);
});
afterEach(async () => { if (!enabled) return; await pool.query('DROP SCHEMA ' + schema + ' CASCADE'); await pool.end(); });

test('原子保存一图、重传幂等、同ID不同内容返回冲突', async () => {
  const r = record();
  expect((await post(r)).body).toMatchObject({ ok: true, id: r.id, sha256: r.sha256 });
  expect((await post(r)).status).toBe(200);
  expect((await list()).body.total).toBe(1);
  expect((await post({ ...r, destination: '其他站' })).status).toBe(409);
});
test('列表与原图按设备隔离，未登录不能读取，原图禁止缓存', async () => {
  const r = record(); await post(r);
  expect((await list(B)).body.records).toEqual([]);
  const url = `/api/v1/dashboard/navigation-records/${r.id}/image?user_id=${A}`;
  expect((await request(app).get(url)).status).toBe(401);
  expect((await agent.get(url.replace(A, B))).status).toBe(404);
  const image = await agent.get(url);
  expect(image.status).toBe(200); expect(image.body).toEqual(bytes);
  expect(image.headers['cache-control']).toBe('no-store');
  expect((await list()).body.records[0]).toMatchObject({ origin: '我的位置', destination: '杭州东站', platform: 'amap' });
  expect((await list()).body.records[0].screenshot).toBeUndefined();
});
test('拒绝无效平台、时间、起终点、超时总览、伪图片及错误哈希', async () => {
  for (const extra of [{ platform: 'other' }, { platform: ['amap'] }, { origin: '' }, { destination: 'x'.repeat(301) }, { started_at: 0 },
    { overview_at: 1780000002000 }, { overview_at: 1779999000000 }, { sha256: 'f'.repeat(64) },
    { started_at: Date.now() + 3600000 }, { mime_type: 'text/html' },
    { file_base64: Buffer.from('fake').toString('base64'), sha256: hash(Buffer.from('fake')) }]) {
    expect((await post(record(extra))).status, JSON.stringify(extra)).toBe(400);
  }
  expect((await list()).body.total).toBe(0);
});
test('保存关闭返回绑定原记录的discarded回执，仍校验参数', async () => {
  await pool.query('INSERT INTO runtime_setting VALUES($1,$2)', [`device_save_uploads:${A}`, 'false']);
  const r = record();
  expect((await post(r)).body).toMatchObject({ ok: true, discarded: true, id: r.id, sha256: r.sha256 });
  expect((await post(record({ origin: '' }))).status).toBe(400);
  expect((await list()).body.total).toBe(0);
});
test('筛选分页与删除设备不串数据', async () => {
  await post(record()); await post(record({ platform: 'baidu' })); await post(record(), B);
  expect((await list(A, '&platform=baidu')).body.total).toBe(1);
  expect((await list(A, '&page=0')).status).toBe(400);
  expect((await list(A, '&platform=wrong')).status).toBe(400);
  expect((await list(A, '&platform[]=amap')).status).toBe(400);
  await pool.query('DELETE FROM device WHERE id=$1', [A]);
  expect((await list()).body.total).toBe(0);
  expect((await list(B)).body.total).toBe(1);
});

test('第二页无重复，按开始时间降序，筛选后的统计独立于分页', async () => {
  for (let i = 0; i < 21; i++) await post(record({ started_at: 1780000001000 + i * 1000 }));
  await post(record({ platform: 'baidu' }));
  const first = (await list(A, '&platform=amap')).body;
  const second = (await list(A, '&platform=amap&page=2')).body;
  expect(first.total).toBe(21); expect(second.total).toBe(21);
  expect(first.records).toHaveLength(20); expect(second.records).toHaveLength(1);
  expect(new Set([...first.records, ...second.records].map(r => r.id)).size).toBe(21);
  expect(Date.parse(first.records[0].started_at)).toBe(1780000021000);
});
