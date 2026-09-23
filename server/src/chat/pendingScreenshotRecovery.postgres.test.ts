import { randomUUID, createHash } from 'node:crypto';
import { readFileSync, realpathSync } from 'node:fs';
import { join } from 'node:path';
import { userInfo } from 'node:os';
import pg from 'pg';
import { beforeAll, beforeEach, afterAll, it, expect } from 'vitest';
import express from 'express';
import request from 'supertest';
import { createChatConversationsRouter } from '../api/chatConversations.js';
import { auditPendingScreenshots, recoverPendingScreenshots } from './pendingScreenshotRecovery.js';
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
  for (const name of ['007_chat_capture.sql', '022_chat_conversation_merge.sql', '029_screenshot_recovery_indexes.sql']) {
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
    await pool.query(`UPDATE chat_message SET metadata=metadata || '{"screenshot_capture_id":"11111111-1111-4111-8111-111111111111"}'::jsonb WHERE id=$1`, [pending.id]);
    confirmed.metadata = { ...confirmed.metadata, screenshot_capture_id: '22222222-2222-4222-8222-222222222222' };
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

test.each(['token', 'legacy'])('同次%s证据允许上传时间不同，但不依赖近似时间', async (mode) => {
  const token = randomUUID();
  const pending = shot('pending', false, { occurred_at: time });
  const known = shot('known', true, { captured_at: '2026-09-23T01:09:00Z', occurred_at: time });
  if (mode === 'token') {
    pending.metadata = { ...pending.metadata, screenshot_capture_id: token };
    known.metadata = { ...known.metadata, screenshot_capture_id: token };
    known.occurred_at = '2026-09-23T01:10:00Z';
  }
  await ingestCapturedMessages(pool, user, device, convo('pending:source'), [pending]);
  const c = await ingestCapturedMessages(pool, user, device, convo('known', true), [known]);
  expect(Number((await rows()).find(r => r.id === pending.id).conversation_id)).toBe(c.conversationId);
  expect((await rows()).find(r => r.id === pending.id).metadata.screenshot_identity_recovery.match)
    .toBe(mode === 'token' ? 'capture_id' : 'legacy_occurred_at');
});

test.each(['one_token', 'invalid_token', 'different_occurrence', 'other_source'])('拒绝%s退回宽松匹配', async (mode) => {
  const pending = shot('pending', false, { occurred_at: time });
  const known = shot('known', true, { captured_at: '2026-09-23T01:09:00Z', occurred_at: time });
  if (mode === 'one_token') known.metadata = { ...known.metadata, screenshot_capture_id: randomUUID() };
  if (mode === 'invalid_token') {
    pending.metadata = { ...pending.metadata, screenshot_capture_id: 'bad' };
    known.metadata = { ...known.metadata, screenshot_capture_id: 'bad' };
  }
  if (mode === 'different_occurrence') known.occurred_at = '2026-09-23T01:02:03.123457Z';
  if (mode === 'other_source') known.metadata = { ...known.metadata, capture_source: 'wechat_screenshot' };
  const p = await ingestCapturedMessages(pool, user, device, convo('pending:source'), [pending]);
  await ingestCapturedMessages(pool, user, device, convo('known', true), [known]);
  expect(Number((await rows()).find(r => r.id === pending.id).conversation_id)).toBe(p.conversationId);
});

test('历史审核是只读，执行限定消息回填后重跑幂等', async () => {
  const pending = shot('pending');
  await ingestCapturedMessages(pool, user, device, convo('pending:source'), [pending]);
  const known = shot('known', true, { captured_at: '2026-09-23T02:00:00Z' });
  await ingestCapturedMessages(pool, user, device, convo('known', true), [known]);
  await pool.query('UPDATE chat_message SET captured_at=$1 WHERE id=$2', [time, known.id]);
  const client = await pool.connect();
  try {
    const before = await rows();
    await client.query('BEGIN READ ONLY');
    const report = await auditPendingScreenshots(client, { userId: user, deviceId: device });
    await client.query('COMMIT');
    expect(report.proposals.map(p => p.messageId)).toEqual([pending.id]);
    expect(await rows()).toEqual(before);
    await client.query('BEGIN');
    await client.query('LOCK TABLE chat_conversation IN SHARE ROW EXCLUSIVE MODE');
    expect((await recoverPendingScreenshots(client, { userId: user, deviceId: device })).moved).toBe(1);
    expect((await recoverPendingScreenshots(client, { userId: user, deviceId: device })).moved).toBe(0);
    await client.query('COMMIT');
  } finally { client.release(); }
});

test('人工确认后同次附件可回填另一个未知来源，而不复用普通merge或低置信度目标', async () => {
  const first = await ingestCapturedMessages(pool, user, device, convo('pending:manual'), [shot('manual')]);
  const other = shot('other-pending');
  await ingestCapturedMessages(pool, user, device, convo('pending:other'), [other]);
  const target = Number((await pool.query(`INSERT INTO chat_conversation
    (user_id,platform,account_key,external_key,display_name,conversation_type,identity_confidence)
    VALUES($1,'wechat','wechat-empty-tree','screenshot-v2:known','人工确认目标','unknown',.85) RETURNING id`, [user])).rows[0].id);
  const app = express(); app.use(express.json());
  app.use((_req, res, next) => { res.locals.userId = user; next(); });
  app.use(createChatConversationsRouter(pool));
  const response = await request(app).post(`/conversations/${first.conversationId}/merge`).send({ confirm: 'MERGE', target_id: target });
  expect(response.status).toBe(200);
  expect(Number((await rows()).find(r => r.id === other.id).conversation_id)).toBe(target);
});

test('跨用户同附件不可回填，缺附件的确认也不提供证据', async () => {
  const pending = shot('pending');
  const p = await ingestCapturedMessages(pool, user, device, convo('pending:source'), [pending]);
  const otherUser = randomUUID();
  await pool.query(`INSERT INTO media_asset(user_id,sha256,mime_type,storage_path,byte_size)
    VALUES($1,$2,'image/webp','synthetic',1)`, [otherUser, hash('image')]);
  await ingestCapturedMessages(pool, otherUser, device, convo('known', true), [shot('known', true)]);
  const result = await ingestCapturedMessages(pool, user, device, convo('known', true),
    [shot('missing', true, { asset_sha256: [hash('image'), hash('missing')] })]);
  expect(result.missingAssets).toEqual([hash('missing')]);
  expect(Number((await rows()).find(r => r.id === pending.id).conversation_id)).toBe(p.conversationId);
});

test('相同fingerprint但不同完整附件的确认不产生凭证', async () => {
  const pending = shot('same');
  const p = await ingestCapturedMessages(pool, user, device, convo('pending:source'), [pending]);
  await ingestCapturedMessages(pool, user, device, convo('known', true),
    [shot('same', true, { asset_sha256: [hash('other')] })]);
  const row = (await rows())[0];
  expect(Number(row.conversation_id)).toBe(p.conversationId);
  expect(row.metadata.screenshot_confirmation_evidence).toBeUndefined();
});

test('客户端不能伪造内部人工或自动恢复凭证', async () => {
  const known = await ingestCapturedMessages(pool, user, device, convo('known', true),
    [shot('different-time', true, { captured_at: '2026-09-23T02:00:00Z' })]);
  const pending = shot('pending');
  pending.metadata = { ...pending.metadata,
    screenshot_confirmation_evidence: [{ conversation_id: known.conversationId, ...shot('x', true).metadata }],
    screenshot_manual_confirmation: { user_id: user, conversation_id: known.conversationId },
    screenshot_identity_recovery: { source_conversation_id: 99 }, screenshot_duplicate_of: randomUUID(), screenshot_deleted: true, screenshot_assets_deleted: true };
  const p = await ingestCapturedMessages(pool, user, device, convo('pending:source'), [pending]);
  const row = (await rows()).find(r => r.id === pending.id);
  expect(Number(row.conversation_id)).toBe(p.conversationId);
  expect(row.metadata.screenshot_confirmation_evidence).toBeUndefined();
  expect(row.metadata.screenshot_manual_confirmation).toBeUndefined();
  expect(row.metadata.screenshot_identity_recovery).toBeUndefined();
  expect(row.metadata.screenshot_duplicate_of).toBeUndefined();
  expect(row.metadata.screenshot_deleted).toBeUndefined();
  expect(row.metadata.screenshot_assets_deleted).toBeUndefined();
});

test.each(['low_confidence', 'known_source', 'different_account'])('人工普通merge的%s不扩大自动回填', async (mode) => {
  const source = await ingestCapturedMessages(pool, user, device, convo('pending:manual', mode === 'known_source'), [shot('manual')]);
  const other = shot('other');
  const p = await ingestCapturedMessages(pool, user, device, convo('pending:other'), [other]);
  const target = Number((await pool.query(`INSERT INTO chat_conversation
    (user_id,platform,account_key,external_key,display_name,conversation_type,identity_confidence)
    VALUES($1,'wechat',$2,'screenshot-v2:target',$3,'unknown',$4) RETURNING id`,
  [user, mode === 'different_account' ? 'other' : 'wechat-empty-tree', mode === 'low_confidence' ? '待确认' : '目标', mode === 'low_confidence' ? .55 : .85])).rows[0].id);
  const app = express(); app.use(express.json()); app.use((_req, res, next) => { res.locals.userId = user; next(); });
  app.use(createChatConversationsRouter(pool));
  expect((await request(app).post(`/conversations/${source.conversationId}/merge`).send({ confirm: 'MERGE', target_id: target })).status).toBe(200);
  expect(Number((await rows()).find(r => r.id === other.id).conversation_id)).toBe(p.conversationId);
});

test('无标题首帧只允许以已验证微信来源和精确occurred_at恢复', async () => {
  const pending = shot('pending', false, { occurred_at: time });
  pending.metadata = { ...pending.metadata, conversation_identity_source: 'unresolved_title' };
  await ingestCapturedMessages(pool, user, device, convo('pending:source'), [pending]);
  const c = await ingestCapturedMessages(pool, user, device, convo('known', true),
    [shot('known', true, { occurred_at: time, captured_at: '2026-09-23T02:00:00Z' })]);
  expect(Number((await rows()).find(r => r.id === pending.id).conversation_id)).toBe(c.conversationId);
});

test('不同fingerprint的同次确认保留重试收据并标记画廊重复', async () => {
  const pending = shot('pending'), known = shot('known', true);
  await ingestCapturedMessages(pool, user, device, convo('pending:source'), [pending]);
  await ingestCapturedMessages(pool, user, device, convo('known', true), [known]);
  const all = await rows();
  expect(all).toHaveLength(2);
  expect(all.find(r => r.id === pending.id).metadata.screenshot_duplicate_of).toBe(known.id);
  expect((await ingestCapturedMessages(pool, user, device, convo('pending:source'), [pending])).duplicated).toBe(1);
  expect(await rows()).toEqual(all);
});

test('后到矛盾确认不自动搬回，但历史审计必须报告冲突', async () => {
  const pending = shot('pending');
  await ingestCapturedMessages(pool, user, device, convo('pending:source'), [pending]);
  const a = await ingestCapturedMessages(pool, user, device, convo('a', true), [shot('a', true)]);
  const b = await ingestCapturedMessages(pool, user, device, convo('b', true), [shot('b', true)]);
  const client = await pool.connect();
  try {
    const report = await auditPendingScreenshots(client, { userId: user, deviceId: device });
    expect(report.blocked).toContainEqual({ messageId: pending.id, reason: 'conflicting_recovery', targetIds: [a.conversationId, b.conversationId] });
    expect(Number((await rows()).find(r => r.id === pending.id).conversation_id)).toBe(a.conversationId);
  } finally { client.release(); }
});

test('人工hook不顺带执行无关采集时刻的历史回填', async () => {
  const source = await ingestCapturedMessages(pool, user, device, convo('pending:manual'), [shot('manual')]);
  const history = shot('history', false, { captured_at: '2026-09-20T01:00:00Z', asset_sha256: [hash('other')] });
  const h = await ingestCapturedMessages(pool, user, device, convo('pending:history'), [history]);
  const oldKnown = shot('old-known', true, { captured_at: '2026-09-21T01:00:00Z', asset_sha256: [hash('other')] });
  const target = await ingestCapturedMessages(pool, user, device, convo('known', true), [oldKnown]);
  await pool.query('UPDATE chat_message SET captured_at=$1 WHERE id=$2', [history.captured_at, oldKnown.id]);
  const app = express(); app.use(express.json()); app.use((_req, res, next) => { res.locals.userId = user; next(); });
  app.use(createChatConversationsRouter(pool));
  expect((await request(app).post(`/conversations/${source.conversationId}/merge`).send({ confirm: 'MERGE', target_id: target.conversationId })).status).toBe(200);
  expect(Number((await rows()).find(r => r.id === history.id).conversation_id)).toBe(h.conversationId);
});

test('旧服务器纯SQL审核与helper一致且不写库，索引迁移可重复执行', async () => {
  const pending = shot('pending');
  await ingestCapturedMessages(pool, user, device, convo('pending:source'), [pending]);
  const known = shot('known', true, { captured_at: '2026-09-23T02:00:00Z' });
  await ingestCapturedMessages(pool, user, device, convo('known', true), [known]);
  await pool.query('UPDATE chat_message SET captured_at=$1 WHERE id=$2', [time, known.id]);
  const before = await rows();
  const sql = readFileSync(new URL('../../scripts/audit-pending-screenshots.sql', import.meta.url), 'utf8')
    .replaceAll(":'audit_user'", `'${user}'`).replaceAll(":'audit_device'", `'${device}'`)
    .replaceAll(":'audit_platform'", "'wechat'").replaceAll(":'audit_account'", "'wechat-empty-tree'");
  const client = await pool.connect();
  try {
    const results = await client.query(sql) as unknown as pg.QueryResult[];
    const report = results.find(r => r.fields.some(f => f.name === 'message_id'))!.rows;
    expect(report).toHaveLength(1);
    expect(report[0]).toMatchObject({ message_id: pending.id, result: 'proposed', evidence_types: ['captured_at'] });
    expect(await rows()).toEqual(before);
    await client.query(readFileSync(new URL('../../migrations/029_screenshot_recovery_indexes.sql', import.meta.url), 'utf8'));
    expect((await client.query("SELECT count(*) FROM pg_indexes WHERE indexname LIKE 'idx_chat_message_screenshot_%'")).rows[0].count).toBe('3');
  } finally { client.release(); }
});


test('相同fingerprint先恢复后，不同fingerprint确认仅补重复标记且保留来源追踪', async () => {
  const pending = shot('same');
  await ingestCapturedMessages(pool, user, device, convo('pending:source'), [pending]);
  await ingestCapturedMessages(pool, user, device, convo('known', true), [shot('same', true)]);
  const before = (await rows()).find(r => r.id === pending.id);
  expect(before.metadata.screenshot_duplicate_of).toBeUndefined();
  const known = shot('new-confirmed', true);
  await ingestCapturedMessages(pool, user, device, convo('known', true), [known]);
  const after = (await rows()).find(r => r.id === pending.id);
  expect(after.metadata.screenshot_duplicate_of).toBe(known.id);
  expect(after.metadata.screenshot_identity_recovery).toEqual(before.metadata.screenshot_identity_recovery);
  const all = await rows();
  await ingestCapturedMessages(pool, user, device, convo('known', true), [known]);
  expect(await rows()).toEqual(all);
});


test('已删除截图收据重试不要求重传附件，也不产生新的确认凭证', async () => {
  const pending = shot('deleted');
  const p = await ingestCapturedMessages(pool, user, device, convo('pending:source'), [pending]);
  await pool.query(`UPDATE chat_message SET metadata=metadata || '{"screenshot_deleted":true}'::jsonb WHERE id=$1`, [pending.id]);
  await pool.query('DELETE FROM chat_message_asset WHERE message_id=$1', [pending.id]);
  await pool.query('DELETE FROM media_asset WHERE user_id=$1', [user]);
  const before = await rows();
  const result = await ingestCapturedMessages(pool, user, device, convo('known', true), [shot('deleted', true)]);
  expect(result).toMatchObject({ inserted: 0, duplicated: 1, missingAssets: [] });
  expect(await rows()).toEqual(before);
  expect(Number((await rows())[0].conversation_id)).toBe(p.conversationId);
  expect((await pool.query('SELECT count(*) FROM media_asset')).rows[0].count).toBe('0');
  const client = await pool.connect();
  try {
    const report = await auditPendingScreenshots(client, { userId: user, deviceId: device });
    expect(report.blocked).toEqual([]);
    expect(report.proposals).toEqual([]);
  } finally { client.release(); }
});


test('部分删除附件收据重试不重传且残余附件不能再次用于恢复', async () => {
  const pending = shot('partial-deleted', false, { asset_sha256: [hash('image'), hash('other')] });
  const p = await ingestCapturedMessages(pool, user, device, convo('pending:source'), [pending]);
  await pool.query(`UPDATE chat_message SET metadata=metadata || '{"screenshot_assets_deleted":true}'::jsonb WHERE id=$1`, [pending.id]);
  await pool.query('DELETE FROM chat_message_asset WHERE message_id=$1 AND asset_id IN (SELECT id FROM media_asset WHERE sha256=$2)', [pending.id, hash('image')]);
  await pool.query('DELETE FROM media_asset WHERE user_id=$1 AND sha256=$2', [user, hash('image')]);
  const before = await rows();
  const result = await ingestCapturedMessages(pool, user, device, convo('known', true), [shot('partial-deleted', true, { asset_sha256: pending.asset_sha256 })]);
  expect(result).toMatchObject({ inserted: 0, duplicated: 1, missingAssets: [] });
  expect(await rows()).toEqual(before);
  await ingestCapturedMessages(pool, user, device, convo('known', true), [shot('other-confirmation', true, { asset_sha256: [hash('other')] })]);
  expect(Number((await rows()).find(r => r.id === pending.id).conversation_id)).toBe(p.conversationId);
  expect((await rows()).find(r => r.id === pending.id).metadata.screenshot_deleted).toBeUndefined();
});

test.each(['partial', 'different_time', 'ambiguous'])('候选SQL保留严格完整附件与时间/歧义校验：%s', async (kind) => {
  const pending = shot('audit-pending');
  await ingestCapturedMessages(pool, user, device, convo('pending:audit'), [pending]);
  const known = shot('audit-known', true, { captured_at: '2026-09-23T02:00:00Z',
    asset_sha256: kind === 'partial' ? [hash('image'), hash('other')] : [hash('image')] });
  await ingestCapturedMessages(pool, user, device, convo('known-audit', true), [known]);
  if (kind !== 'different_time') await pool.query('UPDATE chat_message SET captured_at=$1 WHERE id=$2', [time, known.id]);
  if (kind === 'ambiguous') {
    const other = shot('audit-other', true, { captured_at: '2026-09-23T03:00:00Z' });
    await ingestCapturedMessages(pool, user, device, convo('known-other', true), [other]);
    await pool.query('UPDATE chat_message SET captured_at=$1 WHERE id=$2', [time, other.id]);
  }
  await ingestCapturedMessages(pool, user, randomUUID(), convo('known-device', true), [shot('audit-device', true)]);
  const before = await rows();
  const sql = readFileSync(new URL('../../scripts/audit-pending-screenshots.sql', import.meta.url), 'utf8')
    .replaceAll(":'audit_user'", `'${user}'`).replaceAll(":'audit_device'", `'${device}'`)
    .replaceAll(":'audit_platform'", "'wechat'").replaceAll(":'audit_account'", "'wechat-empty-tree'");
  const client = await pool.connect();
  try {
    const results = await client.query(sql) as unknown as pg.QueryResult[];
    const report = results.find(r => r.fields.some(f => f.name === 'message_id'))!.rows;
    expect(report).toHaveLength(1);
    expect(report[0]).toMatchObject({ message_id: pending.id,
      result: kind === 'ambiguous' ? 'ambiguous_targets' : 'no_confirmed_match' });
    if (kind === 'ambiguous') expect(report[0].target_ids).toHaveLength(2);
    expect(await rows()).toEqual(before);
  } finally { client.release(); }
});
