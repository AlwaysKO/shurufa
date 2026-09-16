import { readFileSync } from 'node:fs';
import { newDb, DataType } from 'pg-mem';
import type pg from 'pg';
import request from 'supertest';
import { beforeEach, afterEach, expect, it } from 'vitest';
import { createApp } from '../app.js';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';
const A = '00000000-0000-4000-8000-00000000000a';
const B = '00000000-0000-4000-8000-00000000000b';
let pool: pg.Pool;
let app: ReturnType<typeof createApp>;
beforeEach(async () => {
  const db = newDb();
  db.public.registerOperator({ operator: '?', left: DataType.jsonb, right: DataType.text, returns: DataType.bool,
    implementation: (value: Record<string, unknown>, key: string) => Object.hasOwn(value, key) });
  pool = new (db.adapters.createPg().Pool)();
  const schema = readFileSync(new URL('../../migrations/001_init.sql', import.meta.url), 'utf8');
  await pool.query(schema.slice(schema.indexOf('CREATE TABLE IF NOT EXISTS input_event'), schema.indexOf('CREATE INDEX IF NOT EXISTS idx_event_device_time')));
  await pool.query('ALTER TABLE input_event ADD COLUMN client_ip TEXT; ALTER TABLE input_event ADD COLUMN ip_location TEXT; ALTER TABLE input_event ADD COLUMN network_type TEXT');
  app = createApp(pool);
});
afterEach(async () => { await pool.end(); });
function event(type: string, text: string, before?: string, after?: string, seq = 1) {
  return { id: crypto.randomUUID(), device_id: A, event_type: type, text,
    text_before: before, text_after: after, sequence_no: seq,
    session_id: '00000000-0000-4000-8000-000000000011', editor_id: 'chat-1',
    package_name: 'com.tencent.mm', occurred_at: `2026-09-16T02:00:${String(seq).padStart(2, '0')}Z` };
}
async function send(events: object[]) {
  return request(app).post('/api/v1/mobile/events/batch').set('X-Device-Id', A).send({ device_id: A, events });
}
it('完整保存已上屏及删除前后文本，空字符串不是未采集', async () => {
  const rows = [event('commit', '晚上八点见', '', '晚上八点见'), event('delete', '八', '晚上八点见', '晚上点见', 2), event('commit', '九', '晚上点见', '晚上九点见', 3), event('delete', '晚上九点见', '晚上九点见', '', 4)];
  expect((await send(rows)).body.inserted).toBe(4);
  const stored = (await pool.query('SELECT text, text_before, text_after FROM input_event ORDER BY sequence_no')).rows;
  expect(stored).toEqual(rows.map(row => ({ text: row.text, text_before: row.text_before, text_after: row.text_after })));
});
it('跨批次与重试不覆盖早期输入，旧客户端没有快照时保留NULL', async () => {
  const commit = event('commit', '已经打出'); const deletion = event('delete', '打出', '已经打出', '已经', 2);
  expect((await send([commit])).body.inserted).toBe(1);
  expect((await send([deletion])).body.inserted).toBe(1);
  // pg-mem 的冲突 rowCount 与 PostgreSQL 不同，验证实际持久化结果。
  expect((await send([commit, deletion])).status).toBe(200);
  const rows = (await pool.query('SELECT text, text_before, text_after FROM input_event ORDER BY sequence_no')).rows;
  expect(rows).toEqual([{ text: '已经打出', text_before: null, text_after: null }, { text: '打出', text_before: '已经打出', text_after: '已经' }]);
});
it('不能替其他设备保存编辑过程', async () => {
  expect((await send([{ ...event('commit', '别人的文字', '', '别人的文字'), device_id: B }])).status).toBe(400);
  expect((await pool.query('SELECT * FROM input_event')).rows).toHaveLength(0);
});
it('默认明细展示删除但不展示未上屏拼音，返回编辑证据并支持已删文字搜索', async () => {
  const rows = [event('commit', '八', '', '八'), event('delete', '八', '八', '', 2), event('compose', 'ba', undefined, undefined, 3), event('key', 'b', undefined, undefined, 4)];
  await send(rows);
  // 已有地址，避免本测试触发IP解析网络请求。
  await pool.query("UPDATE input_event SET client_ip = NULL");
  const agent = await authenticatedRequest(app);
  const response = await agent.get(`/api/v1/dashboard/events?user_id=${A}`);
  expect(response.status).toBe(200);
  expect(response.body.items.map((r: any) => r.event_type)).toEqual(['delete', 'commit']);
  expect(response.body.items[0]).toMatchObject({ text: '八', text_before: '八', text_after: '', editor_id: 'chat-1', sequence_no: 2 });
  const deletion = await agent.get(`/api/v1/dashboard/events?user_id=${A}&type=delete&q=${encodeURIComponent('八')}`);
  expect(deletion.status).toBe(200); expect(deletion.body.total).toBe(1);
  expect((await agent.get(`/api/v1/dashboard/events?user_id=${B}`)).body.total).toBe(0);
});
it('从编辑前后文本也能搜索，缺少删除text不等于忽略整条记录', async () => {
  await send([event('external_delete', '', '会被删掉的文字', '会被', 1)]);
  await pool.query('UPDATE input_event SET client_ip = NULL');
  const agent = await authenticatedRequest(app);
  const response = await agent.get(`/api/v1/dashboard/events?user_id=${A}&q=${encodeURIComponent('删掉')}`);
  expect(response.status).toBe(200); expect(response.body.total).toBe(1);
});
