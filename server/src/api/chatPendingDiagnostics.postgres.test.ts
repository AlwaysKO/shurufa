import { randomUUID, createHash } from 'node:crypto';
import { readFileSync, realpathSync } from 'node:fs';
import { join } from 'node:path';
import pg from 'pg';
import { beforeAll, beforeEach, afterAll, expect, it } from 'vitest';
import { createApp } from '../app.js';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';
import { ingestCapturedMessages } from '../chat/chatRepository.js';

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
  for (const file of ['007_chat_capture.sql', '020_runtime_settings.sql', '022_chat_conversation_merge.sql']) {
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

async function duplicateFamily() {
  const conversationId = await conversation('已确认联系人', { confidence: .95 });
  const main = await message(conversationId, { capture_source: 'wechat_empty_tree_screenshot', conversation_identity_source: 'on_device_title_ocr', conversation_identity_status: 'confirmed' });
  const duplicate = await message(conversationId, { screenshot_duplicate_of: main });
  const nested = await message(conversationId, { screenshot_duplicate_of: duplicate });
  const sha = createHash('sha256').update(randomUUID()).digest('hex');
  const assetId = Number((await pool.query(`INSERT INTO media_asset(user_id,sha256,mime_type,storage_path,byte_size)
    VALUES($1,$2,'image/png',$3,1) RETURNING id`, [user, sha, `chat/${sha.slice(0, 2)}/${sha}.png`])).rows[0].id);
  for (const id of [main, duplicate, nested]) await pool.query('INSERT INTO chat_message_asset(message_id,asset_id) VALUES($1,$2)', [id, assetId]);
  return { conversationId, main, duplicate, nested, assetId, sha };
}

test('精确截图副本在列表、画廊、概览和预览仅展示一次，原fingerprint仍保留', async () => {
  const family = await duplicateFamily();
  for (const gallery of [false, true]) {
    const page = await agent.get('/api/v1/dashboard/chat/messages').query({ user_id: user, conversation_id: family.conversationId, gallery });
    expect(page.status).toBe(200); expect(page.body.total).toBe(1);
    expect(page.body.messages.map((m: any) => m.id)).toEqual([family.main]);
  }
  const list = await agent.get('/api/v1/dashboard/chat/conversations').query({ user_id: user, platform: 'wechat', group_names: true });
  expect(list.body.conversations[0].message_count).toBe(1);
  const resolved = await agent.get(`/api/v1/dashboard/chat/conversations/${family.conversationId}/resolve`).query({ user_id: user });
  expect(resolved.body.conversation.message_count).toBe(1);
  const overview = await agent.get('/api/v1/dashboard/chat/overview').query({ user_id: user, platform: 'wechat' });
  expect(overview.body.message_count).toBe(1);
  const next = await agent.get('/api/v1/dashboard/chat/images/adjacent').query({ user_id: user, conversation_id: family.conversationId, message_id: family.main, asset_id: family.assetId, direction: 'next' });
  expect(next.status).toBe(200); expect(next.body.image).toBeNull();
  const hidden = await agent.get('/api/v1/dashboard/chat/images/adjacent').query({ user_id: user, conversation_id: family.conversationId, message_id: family.duplicate, asset_id: family.assetId, direction: 'next' });
  expect(hidden.status).toBe(404);
  expect(Number((await pool.query('SELECT COUNT(*) AS n FROM chat_message')).rows[0].n)).toBe(3);
});

test.each(['single', 'single_uppercase', 'batch'])('删除可见截图连带清除嵌套副本附件并保留不可见幂等收据（%s）', async mode => {
  const family = await duplicateFamily();
  const original = (await pool.query('SELECT m.*,c.external_key,c.account_key,c.display_name,c.identity_confidence FROM chat_message m JOIN chat_conversation c ON c.id=m.conversation_id WHERE m.id=$1', [family.main])).rows[0];
  const response = mode.startsWith('single')
    ? await agent.delete(`/api/v1/dashboard/chat/messages/${mode === 'single_uppercase' ? family.main.toUpperCase() : family.main}/assets/${family.assetId}`).query({ user_id: user })
    : await agent.post('/api/v1/dashboard/chat/images/delete-batch').query({ user_id: user }).send({ confirm: 'DELETE', conversation_id: family.conversationId, images: [{ message_id: family.main, asset_id: family.assetId }] });
  expect(response.status, JSON.stringify(response.body)).toBe(200);
  expect(Number((await pool.query('SELECT COUNT(*) AS n FROM chat_message_asset')).rows[0].n)).toBe(0);
  expect(Number((await pool.query('SELECT COUNT(*) AS n FROM media_asset')).rows[0].n)).toBe(0);
  const receipts = (await pool.query('SELECT id,metadata FROM chat_message')).rows;
  expect(receipts).toHaveLength(3);
  expect(receipts.every(row => row.metadata.screenshot_deleted === true)).toBe(true);
  const page = await agent.get('/api/v1/dashboard/chat/messages').query({ user_id: user, conversation_id: family.conversationId, gallery: true });
  expect(page.body.total).toBe(0);
  const overview = await agent.get('/api/v1/dashboard/chat/overview').query({ user_id: user, platform: 'wechat' });
  expect(overview.body).toMatchObject({ message_count: 0, media_count: 0 });
  const replay = await ingestCapturedMessages(pool, user, user, {
    platform: 'wechat', external_key: original.external_key, account_key: original.account_key,
    display_name: original.display_name, identity_confidence: Number(original.identity_confidence), conversation_type: 'unknown',
  }, [{ id: family.main, fingerprint: original.fingerprint, content_fingerprint: original.content_fingerprint,
    direction: 'system', sender_key: 'peer', message_type: 'image', captured_at: original.captured_at.toISOString(),
    asset_sha256: [family.sha], metadata: original.metadata }]);
  expect(replay).toMatchObject({ inserted: 0, duplicated: 1, missingAssets: [] });
  expect(Number((await pool.query('SELECT COUNT(*) AS n FROM chat_message_asset')).rows[0].n)).toBe(0);
  expect(Number((await pool.query('SELECT COUNT(*) AS n FROM media_asset')).rows[0].n)).toBe(0);
});

test('删除多附件截图中的一张同步副本但保留另一张与其他会话共享图', async () => {
  const family = await duplicateFamily();
  const original = (await pool.query('SELECT m.*,c.external_key,c.account_key,c.display_name,c.identity_confidence FROM chat_message m JOIN chat_conversation c ON c.id=m.conversation_id WHERE m.id=$1', [family.main])).rows[0];
  const secondSha = createHash('sha256').update(randomUUID()).digest('hex');
  const secondAsset = Number((await pool.query(`INSERT INTO media_asset(user_id,sha256,mime_type,storage_path,byte_size)
    VALUES($1,$2,'image/png',$3,1) RETURNING id`, [user, secondSha, `chat/${secondSha.slice(0, 2)}/${secondSha}.png`])).rows[0].id);
  for (const id of [family.main, family.duplicate, family.nested]) await pool.query('INSERT INTO chat_message_asset(message_id,asset_id,position) VALUES($1,$2,1)', [id, secondAsset]);
  const outside = await message(await conversation('其他联系人', { confidence: .95 }), {});
  await pool.query('INSERT INTO chat_message_asset(message_id,asset_id) VALUES($1,$2)', [outside, family.assetId]);
  const response = await agent.post('/api/v1/dashboard/chat/images/delete-batch').query({ user_id: user }).send({
    confirm: 'DELETE', conversation_id: family.conversationId, images: [{ message_id: family.main, asset_id: family.assetId }],
  });
  expect(response.status, JSON.stringify(response.body)).toBe(200);
  const page = await agent.get('/api/v1/dashboard/chat/messages').query({ user_id: user, conversation_id: family.conversationId, gallery: true });
  expect(page.body.total).toBe(1);
  expect(page.body.messages[0].assets.map((a: any) => a.id)).toEqual([secondAsset]);
  expect(Number((await pool.query('SELECT COUNT(*) AS n FROM chat_message_asset WHERE asset_id=$1', [secondAsset])).rows[0].n)).toBe(3);
  expect((await pool.query('SELECT message_id FROM chat_message_asset WHERE asset_id=$1', [family.assetId])).rows.map(row => row.message_id)).toEqual([outside]);
  expect(Number((await pool.query('SELECT COUNT(*) AS n FROM media_asset')).rows[0].n)).toBe(2);
  // 其他会话仍持有第一张图；再删掉该独立引用，验证旧批次不会要求重传已删除附件。
  await agent.delete(`/api/v1/dashboard/chat/messages/${outside}/assets/${family.assetId}`).query({ user_id: user });
  const replay = await ingestCapturedMessages(pool, user, user, {
    platform: 'wechat', external_key: original.external_key, account_key: original.account_key,
    display_name: original.display_name, identity_confidence: Number(original.identity_confidence), conversation_type: 'unknown',
  }, [{ id: family.main, fingerprint: original.fingerprint, content_fingerprint: original.content_fingerprint,
    direction: 'system', sender_key: 'peer', message_type: 'image', captured_at: original.captured_at.toISOString(),
    asset_sha256: [family.sha, secondSha], metadata: original.metadata }]);
  expect(replay).toMatchObject({ inserted: 0, duplicated: 1, missingAssets: [] });
  const remaining = await agent.get('/api/v1/dashboard/chat/messages').query({ user_id: user, conversation_id: family.conversationId, gallery: true });
  expect(remaining.body.total).toBe(1);
  expect(remaining.body.messages[0].assets.map((a: any) => a.id)).toEqual([secondAsset]);
  expect(Number((await pool.query('SELECT COUNT(*) AS n FROM media_asset')).rows[0].n)).toBe(1);
});

test('历史页面标题仅提示疑似非聊天，同名已知联系人建议优先', async () => {
  const id = await message(await conversation(), { conversation_identity_observed_title: '付款' });
  const first = await pending();
  expect(first.body.messages.find((m: any) => m.id === id).pending_diagnostic).toMatchObject({ reason: 'non_chat_page', non_chat_evidence: 'title_only' });
  const known = await conversation('付款', { confidence: .95 });
  const next = await pending();
  expect(next.body.messages.find((m: any) => m.id === id).pending_diagnostic).toMatchObject({
    reason: 'possible_existing_conversation', suggested_conversations: [{ id: known, display_name: '付款' }],
  });
  expect(next.body.messages.find((m: any) => m.id === id).pending_diagnostic.non_chat_evidence).toBeUndefined();
});
