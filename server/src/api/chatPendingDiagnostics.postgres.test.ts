import { randomUUID, createHash } from 'node:crypto';
import { readFileSync, realpathSync } from 'node:fs';
import { join } from 'node:path';
import pg from 'pg';
import { beforeAll, beforeEach, afterAll, expect, it } from 'vitest';
import { createApp } from '../app.js';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';

const cluster = process.env.CHAT_CONTINUITY_TEST_CLUSTER;
if (cluster && (!cluster.startsWith('/tmp/shurufa-chat-continuity.') || readFileSync(join(cluster, 'test-instance-only'), 'utf8') !== 'chat-continuity-only')) throw Error('独立测试实例验证失败');
const test = cluster ? it : it.skip;
const user = randomUUID(), otherUser = randomUUID();
let pool: pg.Pool;
let agent: Awaited<ReturnType<typeof authenticatedRequest>>;
const config = () => ({ host: join(cluster!, 'socket'), port: 5432, user: 'ko', database: 'chat_continuity_test' });
beforeAll(async () => {
  if (!cluster) return;
  pool = new pg.Pool(config());
  const identity = (await pool.query("SELECT current_setting('data_directory') AS dir,current_database() AS db")).rows[0];
  expect(realpathSync(identity.dir)).toBe(realpathSync(join(cluster, 'data')));
  expect(identity.db).toBe('chat_continuity_test');
});
beforeEach(async () => {
  if (!cluster) return;
  await pool.end();
  const schema = `diagnostic_${randomUUID().replaceAll('-', '')}`;
  pool = new pg.Pool({ ...config(), options: `-c search_path=${schema}` });
  await pool.query(`CREATE SCHEMA ${schema}`);
  for (const file of ['007_chat_capture.sql', '022_chat_conversation_merge.sql']) {
    await pool.query(readFileSync(new URL(`../../migrations/${file}`, import.meta.url), 'utf8'));
  }
  agent = await authenticatedRequest(createApp(pool));
});
afterAll(async () => { await pool?.end(); });
async function conversation(name = '待确认会话', options: { user?: string; platform?: string; account?: string; confidence?: number; key?: string } = {}) {
  return Number((await pool.query(`INSERT INTO chat_conversation(user_id,platform,account_key,external_key,display_name,conversation_type,identity_confidence)
    VALUES($1,$2,$3,$4,$5,'unknown',$6) RETURNING id`, [options.user ?? user, options.platform ?? 'wechat', options.account ?? 'wechat-empty-tree', options.key ?? `screenshot-v2:pending:${randomUUID()}`, name, options.confidence ?? .55])).rows[0].id);
}
async function message(id: number, metadata: Record<string, unknown>) {
  const uuid = randomUUID(), fingerprint = createHash('sha256').update(uuid).digest('hex');
  await pool.query(`INSERT INTO chat_message(id,user_id,device_id,conversation_id,platform,fingerprint,content_fingerprint,sender_key,direction,message_type,captured_at,metadata)
    SELECT $1,user_id,user_id,id,platform,$2,$2,'peer','system','image',NOW(),$3 FROM chat_conversation WHERE id=$4`, [uuid, fingerprint, metadata, id]);
  return uuid;
}
const pending = (gallery = false) => agent.get('/api/v1/dashboard/chat/messages').query({ user_id: user, platform: 'wechat', conversation_id: -1, gallery });

test('逐图解释标题未读到、读到未确认和非聊天页面，兼容旧观测字段', async () => {
  const a = await message(await conversation(), {});
  const b = await message(await conversation(), { conversation_title_observed: '新联系人', conversation_identity_status: 'pending', conversation_identity_source: 'on_device_title_ocr' });
  const c = await message(await conversation(), { conversation_identity_observed_title: '发现', conversation_identity_source: 'wechat_page_title', conversation_identity_status: 'pending' });
  const d = await message(await conversation(), { conversation_identity_observed_title: '应用页面', conversation_identity_page_type: 'non_chat' });
  for (const gallery of [false, true]) {
    const response = await pending(gallery);
    expect(response.status).toBe(200);
    const rows = new Map(response.body.messages.map((m: any) => [m.id, m.pending_diagnostic]));
    expect(rows.get(a)).toMatchObject({ reason: 'title_unreadable', observed_title: null, suggested_conversations: [] });
    expect(rows.get(b)).toMatchObject({ reason: 'title_unconfirmed', observed_title: '新联系人', identity_status: 'pending' });
    expect(rows.get(c)).toMatchObject({ reason: 'non_chat_page', observed_title: '发现', suggested_conversations: [] });
    expect(rows.get(d)).toMatchObject({ reason: 'non_chat_page', observed_title: '应用页面', suggested_conversations: [] });
  }
});

test('仅建议同手机App账号的完整同名已确认来源，多候选不自动合并', async () => {
  const source = await conversation();
  const mid = await message(source, { conversation_identity_observed_title: ' 王小明 ', conversation_identity_status: 'pending' });
  const first = await conversation('王小明', { confidence: .95 });
  const second = await conversation('王小明', { confidence: .85 });
  for (const options of [{ user: otherUser }, { platform: 'qq' }, { account: 'another-account' }, { key: `screenshot-v2:truncated:${randomUUID()}` }]) await conversation('王小明', { confidence: .95, ...options });
  await conversation('王小朋', { confidence: .95 });
  await conversation('王小明');
  const response = await pending();
  expect(response.status).toBe(200);
  expect(response.body.messages.find((m: any) => m.id === mid).pending_diagnostic).toMatchObject({
    reason: 'possible_existing_conversation', observed_title: '王小明',
    suggested_conversations: [{ id: first, display_name: '王小明' }, { id: second, display_name: '王小明' }],
  });
  expect((await pool.query('SELECT merged_into_id FROM chat_conversation WHERE id=$1', [source])).rows[0].merged_into_id).toBeNull();
  expect((await pool.query('SELECT conversation_id FROM chat_message WHERE id=$1', [mid])).rows[0].conversation_id).toBe(String(source));
});

test('普通联系人和无明确页面证据的同名联系人不会误标为非聊天页', async () => {
  const known = await conversation('已确认', { confidence: .95 });
  await message(known, { conversation_identity_observed_title: '历史读法' });
  const response = await agent.get('/api/v1/dashboard/chat/messages').query({ user_id: user, conversation_id: known });
  expect(response.status).toBe(200);
  expect(response.body.messages[0].pending_diagnostic).toBeUndefined();
  await message(await conversation(), { conversation_identity_observed_title: '发现', conversation_identity_source: 'on_device_title_ocr' });
  expect((await pending()).body.messages[0].pending_diagnostic).toMatchObject({ reason: 'title_unconfirmed' });
});
