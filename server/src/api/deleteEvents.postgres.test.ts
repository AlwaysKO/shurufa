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
const schema = `activity_delete_test_${randomUUID().replaceAll('-', '')}`;
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
const session = randomUUID(), device = randomUUID();
async function insert(extra: Record<string, unknown> = {}) {
  const row = { id: randomUUID(), user_id: A, device_id: device, session_id: session,
    sequence_no: 1, occurred_at: '2026-09-17T02:00:00Z', package_name: 'org.example.chat', editor_id: 'chat-editor',
    event_type: 'commit', text: '测试原文', text_before: '', text_after: '测试原文',
    metadata: { edit_protocol: 1, snapshot_complete: true }, ...extra };
  await pool.query(`INSERT INTO input_event (${Object.keys(row).join(',')}) VALUES (${Object.keys(row).map((_, i) => `$${i + 1}`).join(',')})`, Object.values(row));
  return row.id;
}
const remove = (id: string, ids: string[], mode = 'group', user = A) => agent
  .post(`/api/v1/dashboard/events/${id}/delete?user_id=${user}`)
  .send({ confirm: 'DELETE', mode, event_ids: ids });
async function remaining() { return (await pool.query('SELECT id FROM input_event ORDER BY id')).rows.map(row => row.id); }
async function group() {
  return [await insert(), await insert({ sequence_no: 2, event_type: 'delete', text: '文', text_before: '测试原文', text_after: '测试原' }),
    await insert({ sequence_no: 3, text: '句', text_before: '测试原', text_after: '测试原句' })];
}

test('整段删除包含全部原始操作，不触及其他用户设备会话应用或编辑框', async () => {
  const ids = await group();
  const keep = [];
  for (const extra of [{ user_id: B }, { device_id: randomUUID() }, { session_id: randomUUID() },
    { package_name: 'other.app' }, { editor_id: 'other-editor' }, { event_type: 'compose' }, { event_type: 'clipboard_change' }]) keep.push(await insert(extra));
  const res = await remove(ids[2], ids);
  expect(res.status).toBe(200); expect(res.body).toEqual({ deleted: 3 });
  expect(await remaining()).toEqual(keep.sort());
});

test('原始模式只删除指定单条，即便它属于整段', async () => {
  const ids = await group(); const res = await remove(ids[1], [ids[1]], 'single');
  expect(res.status).toBe(200); expect(res.body.deleted).toBe(1);
  expect(await remaining()).toEqual([ids[0], ids[2]].sort());
});

test('搜索只命中一个操作时，确认的整段仍包含完整历史', async () => {
  const ids = await group();
  const listed = await agent.get(`/api/v1/dashboard/events?user_id=${A}&grouped=1&type=delete&q=文`);
  expect(listed.status).toBe(200); expect(listed.body.items).toHaveLength(1);
  const row = listed.body.items[0]; expect(row.edit_events).toHaveLength(3);
  expect((await remove(row.id, row.edit_events.map((e: { id: string }) => e.id))).body.deleted).toBe(3);
  expect(await remaining()).toEqual([]);
});

test('旧记录或无效编辑协议是独立记录，不可按同一会话扩大范围', async () => {
  for (const metadata of [{}, { edit_protocol: '1' }]) {
    const a = await insert({ metadata }), b = await insert({ metadata });
    expect((await remove(a, [a])).status).toBe(200);
    expect(await remaining()).toContain(b);
  }
});

test('跨用户 ID 或混入其他组 ID 时整批拒绝且不删除任何记录', async () => {
  const ids = await group(); const other = await insert({ user_id: B });
  const another = await insert({ session_id: randomUUID() });
  const before = await remaining();
  expect((await remove(other, [other], 'single')).status).toBe(404);
  expect((await remove(ids[2], [...ids, other])).status).toBe(409);
  expect((await remove(ids[2], [...ids, another])).status).toBe(409);
  expect(await remaining()).toEqual(before);
});

test('确认后组内有新增或减少时拒绝旧快照，不猜测要删除哪些记录', async () => {
  const ids = await group(); const late = await insert({ sequence_no: 4 });
  expect((await remove(ids[2], ids)).status).toBe(409);
  expect(await remaining()).toEqual([...ids, late].sort());
  expect((await remove(late, [late], 'single')).status).toBe(200);
  expect((await remove(ids[2], [...ids, late])).status).toBe(409);
  expect(await remaining()).toEqual(ids.sort());
});

test('缺少二次确认、非法ID、空列表、重复ID和单条模式多ID均不执行删除', async () => {
  const ids = await group(); const before = await remaining();
  for (const body of [undefined, {}, { confirm: 'DELETE' },
    { confirm: 'DELETE', mode: 'group', event_ids: [] },
    { confirm: 'DELETE', mode: 'group', event_ids: ['bad-id'] },
    { confirm: 'DELETE', mode: 'group', event_ids: [ids[0], ids[0]] },
    { confirm: 'DELETE', mode: 'single', event_ids: ids },
    { confirm: 'DELETE', mode: 'group', event_ids: [ids[0]] },
    { confirm: 'DELETE', mode: 'all', event_ids: ids }]) {
    const res = await agent.post(`/api/v1/dashboard/events/${ids[2]}/delete?user_id=${A}`).send(body);
    expect(res.status).toBe(400);
  }
  expect((await remove('bad-id', ids)).status).toBe(400);
  expect(await remaining()).toEqual(before);
});

test('不存在或已删除的记录返回404，重复点击不影响其他记录', async () => {
  const id = await insert(); const keep = await insert({ session_id: randomUUID() });
  expect((await remove(id, [id], 'single')).body.deleted).toBe(1);
  expect((await remove(id, [id], 'single')).status).toBe(404);
  expect((await remove(randomUUID(), [randomUUID()], 'single')).status).toBe(400);
  expect(await remaining()).toEqual([keep]);
});

test('没有登录、缺少用户范围和跨站写入均被拒绝', async () => {
  const id = await insert(); const body = { confirm: 'DELETE', mode: 'single', event_ids: [id] };
  const path = `/api/v1/dashboard/events/${id}/delete`;
  expect((await request(app).post(`${path}?user_id=${A}`).set('X-Dashboard-Request', '1').send(body)).status).toBe(401);
  expect((await agent.post(path).send(body)).status).toBe(400);
  expect((await agent.post(`${path}?user_id=${A}`).set('Origin', 'https://attacker.invalid').send(body)).status).toBe(403);
  expect(await remaining()).toEqual([id]);
});

test('删除中发生部分未执行时整个事务回滚，不留下半段记录', async () => {
  const ids = await group();
  // 仅对独立测试表的目标ID注入跳过行为，验证实际删除数量不一致时回滚。
  await pool.query(`CREATE FUNCTION skip_test_delete() RETURNS trigger LANGUAGE plpgsql AS $$
    BEGIN IF OLD.id = '${ids[1]}'::uuid THEN RETURN NULL; END IF; RETURN OLD; END $$`);
  await pool.query('CREATE TRIGGER skip_test_delete BEFORE DELETE ON input_event FOR EACH ROW EXECUTE FUNCTION skip_test_delete()');
  const res = await remove(ids[2], ids);
  expect(res.status).toBe(500); expect(await remaining()).toEqual(ids.sort());
});
