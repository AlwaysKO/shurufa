import { randomUUID, createHash } from 'node:crypto';
import { readFileSync, realpathSync } from 'node:fs';
import { join } from 'node:path';
import { userInfo } from 'node:os';
import pg from 'pg';
import { beforeAll, beforeEach, afterAll, it, expect } from 'vitest';
import { ingestCapturedMessages } from './chatRepository.js';
import type { CapturedConversationInput, CapturedMessageInput } from '../types/chat.js';

const cluster = process.env.CHAT_RECOVERY_TEST_CLUSTER;
if (cluster && (!cluster.startsWith('/tmp/shurufa-chat-recovery.') ||
  readFileSync(join(cluster, 'test-instance-only'), 'utf8') !== 'chat-recovery-only')) throw Error('独立测试实例验证失败');
const test = cluster ? it : it.skip;
let pool: pg.Pool;
const user = randomUUID(), device = randomUUID();
const time = '2026-09-23T01:02:03.123456Z';
const hash = (s: string) => createHash('sha256').update(s).digest('hex');
const convo = (key: string, confirmed = false): CapturedConversationInput => ({
  platform: 'wechat', account_key: 'wechat-empty-tree', external_key: `screenshot-v2:${key}`,
  display_name: confirmed ? '测试联系人' : '待确认会话', conversation_type: 'unknown', identity_confidence: confirmed ? .85 : .55,
});
function shot(tag: string, confirmed = false, overrides: Partial<CapturedMessageInput> = {}): CapturedMessageInput {
  return { id: randomUUID(), fingerprint: hash(tag), content_fingerprint: hash(tag), sender_key: 'viewport',
    direction: 'system', message_type: 'image', captured_at: time, asset_sha256: [hash('image')],
    metadata: { capture_source: 'wechat_empty_tree_screenshot', conversation_identity_source: 'on_device_title_ocr',
      conversation_identity_status: confirmed ? 'confirmed' : 'pending' }, ...overrides };
}
beforeAll(async () => {
  if (!cluster) return;
  pool = new pg.Pool({ host: join(cluster, 'socket'), port: 5432, user: userInfo().username, database: 'chat_recovery_test' });
  const row = (await pool.query("SELECT current_setting('data_directory') AS dir,current_database() AS db")).rows[0];
  expect(realpathSync(row.dir)).toBe(realpathSync(join(cluster, 'data')));
  expect(row.db).toBe('chat_recovery_test');
});
beforeEach(async () => {
  if (!cluster) return;
  await pool.query('DROP SCHEMA public CASCADE; CREATE SCHEMA public');
  for (const name of ['007_chat_capture.sql', '022_chat_conversation_merge.sql']) {
    await pool.query(readFileSync(new URL(`../../migrations/${name}`, import.meta.url), 'utf8'));
  }
  for (const name of ['image', 'other']) await pool.query(`INSERT INTO media_asset
    (user_id,sha256,mime_type,storage_path,byte_size) VALUES($1,$2,'image/webp','synthetic',1)`, [user, hash(name)]);
});
afterAll(async () => { await pool?.end(); });
const rows = async () => (await pool.query('SELECT id,conversation_id,metadata FROM chat_message ORDER BY created_at,id')).rows;

test.each([true, false])('仅同次完整附件回填，不移动同未知来源其他图片；pending先到=%s', async (pendingFirst) => {
  const pending = shot('pending'), other = shot('other', false, { asset_sha256: [hash('other')] });
  let p: Awaited<ReturnType<typeof ingestCapturedMessages>>, c: typeof p;
  if (pendingFirst) {
    p = await ingestCapturedMessages(pool, user, device, convo('pending:source'), [pending, other]);
    c = await ingestCapturedMessages(pool, user, device, convo('known', true), [shot('known', true)]);
  } else {
    c = await ingestCapturedMessages(pool, user, device, convo('known', true), [shot('known', true)]);
    p = await ingestCapturedMessages(pool, user, device, convo('pending:source'), [pending, other]);
  }
  const messages = await rows();
  expect(Number(messages.find(r => r.id === pending.id).conversation_id)).toBe(c.conversationId);
  expect(Number(messages.find(r => r.id === other.id).conversation_id)).toBe(p.conversationId);
  expect(messages.find(r => r.id === pending.id).metadata.screenshot_identity_recovery.source_conversation_id).toBe(p.conversationId);
  expect((await pool.query('SELECT merged_into_id FROM chat_conversation WHERE id=$1', [p.conversationId])).rows[0].merged_into_id).toBeNull();
});

test('重复fingerprint的确认重放仍触发回填且幂等', async () => {
  const pending = shot('same-fingerprint');
  await ingestCapturedMessages(pool, user, device, convo('pending:source'), [pending]);
  const confirmed = { ...pending, id: randomUUID(), metadata: shot('unused', true).metadata };
  const result = await ingestCapturedMessages(pool, user, device, convo('known', true), [confirmed]);
  expect(result.inserted).toBe(0);
  expect(Number((await rows())[0].conversation_id)).toBe(result.conversationId);
  const before = await rows();
  await ingestCapturedMessages(pool, user, device, convo('known', true), [confirmed]);
  expect(await rows()).toEqual(before);
});

test.each(['device', 'platform', 'account', 'time', 'partial', 'empty', 'source', 'token'])('不回填不同%s或不完整证据', async (kind) => {
  const pending = shot('pending', false, kind === 'empty' ? { asset_sha256: [] } : {});
  const p = await ingestCapturedMessages(pool, user, device, convo('pending:source'), [pending]);
  const known = convo('known', true);
  if (kind === 'platform') known.platform = 'qq';
  if (kind === 'account') known.account_key = 'other';
  const confirmed = shot('known', true);
  if (kind === 'time') confirmed.captured_at = '2026-09-23T01:02:03.123457Z';
  if (kind === 'partial') confirmed.asset_sha256 = [hash('image'), hash('other')];
  if (kind === 'source') confirmed.metadata = { conversation_identity_status: 'confirmed', capture_source: 'notification' };
  if (kind === 'token') {
    await pool.query(`UPDATE chat_message SET metadata=metadata || '{"capture_instance_id":"one"}'::jsonb WHERE id=$1`, [pending.id]);
    confirmed.metadata = { ...confirmed.metadata, capture_instance_id: 'two' };
  }
  await ingestCapturedMessages(pool, user, kind === 'device' ? randomUUID() : device, known, [confirmed]);
  expect(Number((await rows()).find(r => r.id === pending.id).conversation_id)).toBe(p.conversationId);
});

test('两个不同确认目标有相同精确附件时保留待确认', async () => {
  await ingestCapturedMessages(pool, user, device, convo('known-a', true), [shot('a', true)]);
  await ingestCapturedMessages(pool, user, device, convo('known-b', true), [shot('b', true)]);
  const pending = shot('pending');
  const p = await ingestCapturedMessages(pool, user, device, convo('pending:source'), [pending]);
  expect(Number((await rows()).find(r => r.id === pending.id).conversation_id)).toBe(p.conversationId);
});
