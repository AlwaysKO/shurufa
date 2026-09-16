import { randomUUID } from 'node:crypto';
import { readFileSync, realpathSync } from 'node:fs';
import { join, basename } from 'node:path';
import pg from 'pg';
import request from 'supertest';
import { beforeAll, afterAll, beforeEach, expect, it } from 'vitest';
import { createApp } from '../app.js';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';

// 只允许专用脚本 initdb 创建的独立实例；绝不使用业务 URL 或默认 TCP 连接。
const cluster = process.env.APP_NAMES_TEST_CLUSTER;
if (cluster && (!basename(cluster).startsWith('shurufa-app-names.') ||
  readFileSync(join(cluster, 'test-instance-only'), 'utf8') !== 'shurufa-app-names-only')) {
  throw Error('Dedicated test instance required');
}
const test = cluster ? it : it.skip;
const schema = `app_names_test_${randomUUID().replaceAll('-', '')}`;
const A = randomUUID(), B = randomUUID();
let pool: pg.Pool;
let isolated = false;
let app: ReturnType<typeof createApp>;
let agent: Awaited<ReturnType<typeof authenticatedRequest>>;
beforeAll(async () => {
  if (!cluster) return;
  pool = new pg.Pool({ host: join(cluster, 'socket'), port: 5432, user: process.env.USER || 'ko',
    database: 'app_names_test', max: 1, options: `-c search_path=${schema}` });
  const identity = (await pool.query("SELECT current_setting('data_directory') AS dir, current_database() AS db")).rows[0];
  expect(realpathSync(identity.dir)).toBe(realpathSync(join(cluster, 'data')));
  expect(identity.db).toBe('app_names_test');
  isolated = true;
  await pool.query(`CREATE SCHEMA ${schema}`);
  await pool.query(`SET search_path TO ${schema}`);
  const sql = readFileSync(new URL('../../migrations/001_init.sql', import.meta.url), 'utf8');
  await pool.query(sql.slice(sql.indexOf('CREATE TABLE IF NOT EXISTS input_event'), sql.indexOf('CREATE INDEX IF NOT EXISTS idx_event_device_time')));
  await pool.query('ALTER TABLE input_event ADD COLUMN client_ip TEXT; ALTER TABLE input_event ADD COLUMN ip_location TEXT; ALTER TABLE input_event ADD COLUMN network_type TEXT');
  await pool.query('CREATE TABLE location_track (user_id uuid, occurred_at timestamptz, latitude numeric, longitude numeric, address text, last_seen_at timestamptz)');
  app = createApp(pool); agent = await authenticatedRequest(app);
});
afterAll(async () => {
  if (!pool) return;
  try { if (isolated) await pool.query(`DROP SCHEMA IF EXISTS ${schema} CASCADE`); } finally { await pool.end(); }
});
beforeEach(async () => {
  if (!cluster) return;
  if (!isolated) throw Error("Isolation was not verified");
  // 失败即停止，不得在默认 public schema 上执行清理。
  expect((await pool.query('SELECT current_schema() AS name')).rows[0].name).toBe(schema);
  await pool.query(`DELETE FROM ${schema}.input_event`);
});
function event(extra: Record<string, unknown> = {}) {
  return { id: randomUUID(), device_id: A, event_type: 'commit', text: '你好', package_name: 'org.example.chat', occurred_at: new Date().toISOString(), ...extra };
}
const send = (events: object[], user = A) => request(app).post('/api/v1/mobile/events/batch').set('X-Device-Id', user).send({ device_id: user, events });
const apps = (user = A) => agent.get(`/api/v1/dashboard/apps?user_id=${user}&days=30`);

test('名称随真实事件保存，保留编辑元数据，重复上传不改变统计', async () => {
  const row = event({ app_name: '  测试聊天  ', metadata: { edit_protocol: 1, snapshot_complete: true } });
  expect((await send([row])).body.inserted).toBe(1);
  expect((await send([row])).body.inserted).toBe(0);
  const stored = (await pool.query('SELECT metadata FROM input_event')).rows[0].metadata;
  expect(stored).toEqual({ edit_protocol: 1, snapshot_complete: true, app_name: '测试聊天' });
  const res = await apps(); expect(res.status).toBe(200);
  expect(res.body.apps).toEqual([expect.objectContaining({ package_name: 'org.example.chat', app_name: '测试聊天', input_chars: '2', event_count: '1' })]);
});

test('旧版和无效名称正常接收，不让元数据对象或超长名称成为展示名', async () => {
  for (const name of [undefined, null, '', ' \n ', {}, 123, '甲'.repeat(121)]) {
    expect((await send([event({ app_name: name })])).status).toBe(200);
  }
  const res = await apps(); expect(res.status).toBe(200); expect(res.body.apps[0].app_name).toBeNull();
  expect(res.body.apps[0].event_count).toBe('7');
});

test('同包用最新有效名称，离线上传旧名字和后续空名字不能覆盖新名字', async () => {
  const now = Date.now();
  await send([event({ app_name: '新名称', occurred_at: new Date(now - 1000).toISOString() })]);
  await send([event({ app_name: '旧名称', occurred_at: new Date(now - 5000).toISOString() })]);
  await send([event({ app_name: '  ' })]);
  expect((await apps()).body.apps[0].app_name).toBe('新名称');
});

test('不同用户同包不串名，同名不同包不合并', async () => {
  await send([event({ app_name: '我的聊天' }), event({ package_name: 'org.example.work', app_name: '我的聊天' })]);
  await send([event({ device_id: B, app_name: '另一台名称' })], B);
  const res = await apps(); expect(res.body.apps).toHaveLength(2);
  expect(res.body.apps.every((row: { app_name: string }) => row.app_name === '我的聊天')).toBe(true);
  expect((await apps(B)).body.apps[0].app_name).toBe('另一台名称');
});

test('新采集的名字也能用于旧日报和周报，统计字数保持原日期范围', async () => {
  await send([event({ occurred_at: '2025-01-06T12:00:00Z' }), event({ app_name: '系统真实名称' })]);
  for (const type of ['daily', 'weekly']) {
    const res = await agent.get(`/api/v1/dashboard/report?user_id=${A}&type=${type}&date=2025-01-06`);
    expect(res.status).toBe(200);
    expect(res.body.top_apps[0]).toMatchObject({ package_name: 'org.example.chat', app_name: '系统真实名称', input_chars: '2' });
  }
});

test('旧 metadata 中的无效名称被移除，客户端名称中的控制字符不会进入展示', async () => {
  await send([event({ app_name: ' 测\u0000试\n应用 ', metadata: { app_name: { bad: true }, edit_protocol: 1 } })]);
  expect((await apps()).body.apps[0].app_name).toBe('测试应用');
});
