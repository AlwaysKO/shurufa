import { randomUUID } from 'node:crypto';
import pg from 'pg';
import { beforeAll, afterAll, beforeEach, expect, it } from 'vitest';
import { createApp } from '../app.js';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';

// Explicit opt-in, local PostgreSQL only. All writes stay in a disposable schema.
const url = process.env.GROUPED_EDITS_TEST_DATABASE_URL;
const local = url && ['localhost', '127.0.0.1', '[::1]'].includes(new URL(url).hostname);
if (url && !local) throw new Error('Grouped edit integration tests require localhost');
const test = local ? it : it.skip;
let pool: pg.Pool;
let agent: Awaited<ReturnType<typeof authenticatedRequest>>;
const schema = `edit_test_${randomUUID().replaceAll('-', '')}`;
const A = randomUUID(), B = randomUUID(), D = randomUUID(), session = randomUUID();
beforeAll(async () => {
  if (!local) return;
  pool = new pg.Pool({ connectionString: url, max: 1 });
  await pool.query(`CREATE SCHEMA ${schema}`);
  await pool.query(`SET search_path TO ${schema}`);
  await pool.query(`CREATE TABLE input_event (
    id uuid PRIMARY KEY, user_id uuid, device_id uuid, session_id uuid, sequence_no bigint,
    occurred_at timestamptz, package_name text, editor_id text, event_type text,
    text text, text_before text, text_after text, input_code text, metadata jsonb,
    client_ip text, ip_location text, network_type text)`);
  agent = await authenticatedRequest(createApp(pool));
});
afterAll(async () => {
  if (!pool) return;
  try { await pool.query(`DROP SCHEMA ${schema} CASCADE`); } finally { await pool.end(); }
});
beforeEach(async () => { if (pool) await pool.query('DELETE FROM input_event'); });
async function insert(seq: number, extra: Record<string, unknown> = {}) {
  const row = { id: randomUUID(), user_id: A, device_id: D, session_id: session, sequence_no: seq,
    occurred_at: `2026-09-16T02:00:${String(seq).padStart(2, '0')}Z`, package_name: 'chat', editor_id: 'editor',
    event_type: seq === 2 ? 'delete' : 'commit', text: seq === 1 ? '八' : seq === 2 ? '八' : '九',
    text_before: seq === 1 ? '' : seq === 2 ? '八' : '', text_after: seq === 1 ? '八' : seq === 2 ? '' : '九',
    metadata: { edit_protocol: 1, snapshot_complete: true }, ...extra };
  await pool.query(`INSERT INTO input_event (${Object.keys(row).join(',')}) VALUES (${Object.keys(row).map((_, i) => `$${i + 1}`).join(',')})`, Object.values(row));
}
const get = (suffix = '') => agent.get(`/api/v1/dashboard/events?user_id=${A}&grouped=1${suffix}`);
test('搜索已删除文字、日期和类型只匹配成员，返回完整操作组', async () => {
  await insert(3); await insert(1); await insert(2);
  const res = await get('&q=八&type=delete&from=2026-09-16T02:00:02Z&to=2026-09-16T02:00:02Z');
  expect(res.status).toBe(200); expect(res.body.total).toBe(1);
  expect(res.body.items[0]).toMatchObject({ edit_count: 3, edit_complete: true, text_after: '九' });
  expect(res.body.items[0].edit_events.map((x: any) => Number(x.sequence_no))).toEqual([1, 2, 3]);
});
test('按组分页；旧事件各自保留，跨用户/设备/编辑器/应用不能串组', async () => {
  await insert(1); await insert(2); await insert(3);
  await insert(4, { metadata: {} }); await insert(5, { metadata: {} });
  await insert(1, { user_id: B });
  await insert(6, { device_id: randomUUID() });
  await insert(7, { editor_id: 'other' });
  await insert(8, { package_name: 'other' });
  const pages = [];
  for (let page = 1; page <= 6; page++) {
    const res = await get(`&page_size=1&page=${page}`);
    expect(res.status).toBe(200); expect(res.body.total).toBe(6); expect(res.body.items).toHaveLength(1);
    pages.push(res.body.items[0]);
  }
  expect(pages.map(x => x.edit_count).sort()).toEqual([1, 1, 1, 1, 1, 3]);
  expect(new Set(pages.map(x => x.id)).size).toBe(6);
  expect((await get('&page=99&page_size=1')).body).toMatchObject({ total: 6, items: [] });
  const raw = await agent.get(`/api/v1/dashboard/events?user_id=${A}`);
  expect(raw.body.total).toBe(8);
});
test('旧会话、字符串伪协议和非编辑事件不合并，不完整快照保留原文', async () => {
  await insert(1, { metadata: { edit_protocol: '1', snapshot_complete: true } });
  await insert(2, { metadata: { edit_protocol: '1', snapshot_complete: true } });
  await insert(3, { event_type: 'compose' });
  await insert(1, { session_id: randomUUID(), text_before: null, metadata: { edit_protocol: 1, snapshot_complete: false } });
  const res = await get('&all=1');
  expect(res.status).toBe(200); expect(res.body.total).toBe(4);
  expect(res.body.items.every((x: any) => x.edit_count === 1 && x.edit_complete === false)).toBe(true);
  expect(res.body.items.every((x: any) => x.edit_events[0].text)).toBe(true);
});
test('离线乱序到达及时间回拨仍按序号选择编辑结果', async () => {
  await insert(3, { occurred_at: '2026-09-16T01:00:00Z' }); await insert(1);
  expect((await get()).body.items[0]).toMatchObject({ edit_complete: false, text_after: '九' });
  await insert(2);
  expect((await get()).body.items[0]).toMatchObject({ edit_complete: true, text_after: '九', edit_count: 3 });
});
test('整段模式首次响应解析代表行及全部原始操作IP，不遗漏早期不同IP', async () => {
  await insert(1, { client_ip: '127.0.0.1' });
  await insert(2, { client_ip: '10.1.2.3' });
  await insert(3, { client_ip: '127.0.0.1' });
  const res = await get();
  expect(res.status).toBe(200);
  expect(res.body.items[0].ip_location).toBe('本机/内网');
  expect(res.body.items[0].edit_events.map((x: any) => x.ip_location)).toEqual(['本机/内网', '本机/内网', '本机/内网']);
  expect((await pool.query('SELECT DISTINCT ip_location FROM input_event')).rows).toEqual([{ ip_location: '本机/内网' }]);
});
