import { readFileSync } from 'node:fs';
import { newDb } from 'pg-mem';
import type pg from 'pg';
import request from 'supertest';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';
import { beforeEach, describe, expect, it } from 'vitest';
import { createApp } from '../app.js';

let pool: pg.Pool;
let conversationId: number;
let firstMessageId: string;
let assetId: number;
const userId = '00000000-0000-0000-0000-000000000001';

beforeEach(async () => {
  const database = newDb();
  database.public.interceptQueries(sql => sql.startsWith('LOCK TABLE ') ? [] : null);
  const adapter = database.adapters.createPg();
  pool = new adapter.Pool();
  await pool.query(readFileSync(
    new URL('../../migrations/007_chat_capture.sql', import.meta.url),
    'utf8',
  ));
  await pool.query(readFileSync(new URL('../../migrations/022_chat_conversation_merge.sql', import.meta.url), 'utf8'));
  const conversation = await pool.query<{ id: number }>(`INSERT INTO chat_conversation
    (user_id, platform, account_key, external_key, display_name,
     conversation_type, identity_confidence)
    VALUES ($1, 'wechat', 'account', 'peer', '对方', 'direct', 0.95)
    RETURNING id`, [userId]);
  conversationId = conversation.rows[0].id;

  const asset = await pool.query<{ id: number }>(`INSERT INTO media_asset
    (user_id, sha256, mime_type, storage_path, byte_size, width, height)
    VALUES ($1, $2, 'image/webp', 'chat/aa/asset.webp', 10, 100, 80)
    RETURNING id`, [userId, 'a'.repeat(64)]);
  assetId = asset.rows[0].id;
  firstMessageId = crypto.randomUUID();
  await pool.query(`INSERT INTO chat_message
    (id, user_id, device_id, conversation_id, platform, fingerprint,
     content_fingerprint, sender_key, sender_name, direction, message_type,
     text, captured_at)
    VALUES ($1, $2, $3, $4, 'wechat', $5, $6, 'peer', '对方',
            'incoming', 'image', '图片', $7)`, [
    firstMessageId,
    userId,
    crypto.randomUUID(),
    conversationId,
    'b'.repeat(64),
    'c'.repeat(64),
    new Date('2026-08-20T10:00:00.000Z'),
  ]);
  await pool.query(`INSERT INTO chat_message_asset (message_id, asset_id)
    VALUES ($1, $2)`, [firstMessageId, asset.rows[0].id]);
  await pool.query(`INSERT INTO chat_message
    (id, user_id, device_id, conversation_id, platform, fingerprint,
     content_fingerprint, sender_key, direction, message_type, text, captured_at)
    VALUES ($1, $2, $3, $4, 'wechat', $5, $6, 'self', 'outgoing', 'text', '你好', $7)`, [
    crypto.randomUUID(),
    userId,
    crypto.randomUUID(),
    conversationId,
    'd'.repeat(64),
    'e'.repeat(64),
    new Date('2026-08-20T11:00:00.000Z'),
  ]);
  await pool.query(`INSERT INTO chat_message
    (id, user_id, device_id, conversation_id, platform, fingerprint,
     content_fingerprint, sender_key, direction, message_type, text, captured_at)
    VALUES ($1, $2, $2, $3, 'wechat', $4, $5, 'other', 'incoming', 'text', '其他手机', $6)`, [
    crypto.randomUUID(),
    '00000000-0000-4000-8000-000000000099',
    conversationId,
    'f'.repeat(64),
    '0'.repeat(64),
    new Date('2026-08-20T12:00:00.000Z'),
  ]);
});

describe('chat dashboard API', () => {
  it('概览返回稳定的会话、消息和媒体计数', async () => {
    const response = await (await authenticatedRequest(createApp(pool))).get(`/api/v1/dashboard/chat/overview?user_id=${userId}`);

    expect(response.status).toBe(200);
    expect(response.body).toEqual({
      conversation_count: 1,
      message_count: 2,
      media_count: 1,
    });
  });

  it('会话列表返回稳定字段并支持分页', async () => {
    const response = await (await authenticatedRequest(createApp(pool)))
      .get(`/api/v1/dashboard/chat/conversations?page=1&page_size=1&user_id=${userId}`);

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({ page: 1, page_size: 1, total: 1 });
    expect(response.body.conversations).toEqual([
      expect.objectContaining({
        id: conversationId,
        platform: 'wechat',
        display_name: '对方',
        conversation_type: 'direct',
        message_count: 2,
      }),
    ]);
  });

  it('消息列表返回资源 URL、方向、类型、发送者、文本、时间和平台', async () => {
    const response = await (await authenticatedRequest(createApp(pool)))
      .get(`/api/v1/dashboard/chat/messages?conversation_id=${conversationId}&page=2&page_size=1&user_id=${userId}`);

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({ page: 2, page_size: 1, total: 2 });
    expect(response.body.messages).toEqual([
      expect.objectContaining({
        platform: 'wechat',
        direction: 'incoming',
        message_type: 'image',
        sender_key: 'peer',
        sender_name: '对方',
        text: '图片',
        captured_at: expect.any(String),
        assets: [expect.objectContaining({ url: '/uploads/chat/aa/asset.webp' })],
      }),
    ]);
  });

  it('删除当前用户的会话、消息和仅由该会话引用的媒体', async () => {
    const response = await (await authenticatedRequest(createApp(pool)))
      .delete(`/api/v1/dashboard/chat/conversations/${conversationId}?user_id=${userId}`);

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({ ok: true, deleted_messages: 2, deleted_assets: 1 });
    expect((await pool.query('SELECT id FROM chat_conversation WHERE user_id=$1', [userId])).rowCount).toBe(0);
    expect((await pool.query('SELECT id FROM chat_message WHERE user_id=$1', [userId])).rowCount).toBe(0);
    expect((await pool.query('SELECT id FROM media_asset WHERE user_id=$1', [userId])).rowCount).toBe(0);
  });

  it('不能删除其他用户的会话', async () => {
    const response = await (await authenticatedRequest(createApp(pool)))
      .delete(`/api/v1/dashboard/chat/conversations/${conversationId}?user_id=00000000-0000-4000-8000-000000000099`);

    expect(response.status).toBe(404);
    expect((await pool.query('SELECT id FROM chat_conversation WHERE id=$1', [conversationId])).rowCount).toBe(1);
  });

  it('可删除单张截图并清理失去最后资源的空截图消息', async () => {
    const response = await (await authenticatedRequest(createApp(pool)))
      .delete(`/api/v1/dashboard/chat/messages/${firstMessageId}/assets/${assetId}?user_id=${userId}`);

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({ ok: true, deleted_asset: true, deleted_message: true });
    expect((await pool.query('SELECT 1 FROM chat_message_asset WHERE message_id=$1', [firstMessageId])).rowCount).toBe(0);
    expect((await pool.query('SELECT 1 FROM chat_message WHERE id=$1', [firstMessageId])).rowCount).toBe(0);
    expect((await pool.query('SELECT 1 FROM media_asset WHERE id=$1', [assetId])).rowCount).toBe(0);
    expect((await pool.query('SELECT 1 FROM chat_conversation WHERE id=$1', [conversationId])).rowCount).toBe(1);
  });

  it('单图删除不删除其他消息仍引用的共享资源', async () => {
    const sharedMessage = crypto.randomUUID();
    await pool.query(`INSERT INTO chat_message
      (id,user_id,device_id,conversation_id,platform,fingerprint,content_fingerprint,
       sender_key,direction,message_type,captured_at)
      VALUES($1,$2,$3,$4,'wechat',$5,$6,'peer','incoming','image',NOW())`,
    [sharedMessage, userId, crypto.randomUUID(), conversationId, '1'.repeat(64), '2'.repeat(64)]);
    await pool.query('INSERT INTO chat_message_asset(message_id,asset_id) VALUES($1,$2)', [sharedMessage, assetId]);

    const response = await (await authenticatedRequest(createApp(pool)))
      .delete(`/api/v1/dashboard/chat/messages/${firstMessageId}/assets/${assetId}?user_id=${userId}`);

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({ deleted_asset: false, deleted_message: true });
    expect((await pool.query('SELECT 1 FROM media_asset WHERE id=$1', [assetId])).rowCount).toBe(1);
    expect((await pool.query('SELECT 1 FROM chat_message_asset WHERE message_id=$1 AND asset_id=$2', [sharedMessage, assetId])).rowCount).toBe(1);
  });

  it('不能删除其他用户消息中的图片', async () => {
    const response = await (await authenticatedRequest(createApp(pool)))
      .delete(`/api/v1/dashboard/chat/messages/${firstMessageId}/assets/${assetId}?user_id=00000000-0000-4000-8000-000000000099`);
    expect(response.status).toBe(404);
    expect((await pool.query('SELECT 1 FROM chat_message_asset WHERE message_id=$1 AND asset_id=$2', [firstMessageId, assetId])).rowCount).toBe(1);
  });
});

it('聊天消息跨页按采集时间倒序，同时间以ID倒序稳定排序且隔离用户', async () => {
  const ids = Array.from({ length: 30 }, (_, i) => `10000000-0000-4000-8000-${String(i + 1).padStart(12, '0')}`);
  for (const [i, id] of ids.entries()) {
    await pool.query(`INSERT INTO chat_message
      (id, user_id, device_id, conversation_id, platform, fingerprint, content_fingerprint,
       sender_key, direction, message_type, text, captured_at)
      VALUES ($1, $2, $3, $4, 'wechat', $5, $5, 'peer', 'incoming', 'image', '截图', $6)`,
      [id, userId, crypto.randomUUID(), conversationId, String(i + 1).padStart(64, '0'),
       new Date(i < 15 ? '2026-09-16T10:00:00Z' : '2026-09-17T10:00:00Z')]);
  }
  const agent = await authenticatedRequest(createApp(pool));
  const first = await agent.get(`/api/v1/dashboard/chat/messages?user_id=${userId}&conversation_id=${conversationId}&page=1&page_size=24`);
  const second = await agent.get(`/api/v1/dashboard/chat/messages?user_id=${userId}&conversation_id=${conversationId}&page=2&page_size=24`);
  expect(first.status).toBe(200);
  expect(second.status).toBe(200);
  expect(first.body.total).toBe(32);
  expect(first.body.messages).toHaveLength(24);
  expect(second.body.messages).toHaveLength(8);
  const messages = [...first.body.messages, ...second.body.messages];
  expect(messages.slice(0, 30).map(m => m.id)).toEqual([...ids].reverse());
  expect(new Set(messages.map(m => m.id)).size).toBe(32);
  const timestamps = messages.map(m => m.captured_at);
  expect(timestamps).toEqual([...timestamps].sort().reverse());
  expect(messages.some(m => m.text === '其他手机')).toBe(false);
});

it('平台筛选在分页前执行，概览只统计当前平台关联的去重媒体', async () => {
  const { rows } = await pool.query(`INSERT INTO chat_conversation
    (user_id, platform, account_key, external_key, display_name, conversation_type, identity_confidence)
    VALUES ($1, 'qq', 'account', 'qq-peer', 'QQ测试', 'direct', 0.95) RETURNING id`, [userId]);
  for (let i = 0; i < 2; i++) {
    const id = crypto.randomUUID();
    await pool.query(`INSERT INTO chat_message
      (id,user_id,device_id,conversation_id,platform,fingerprint,content_fingerprint,sender_key,direction,message_type,captured_at)
      VALUES ($1,$2,$3,$4,'qq',$5,$5,'peer','incoming','image',NOW())`,
    [id,userId,crypto.randomUUID(),rows[0].id,String(i + 1).repeat(64)]);
    await pool.query('INSERT INTO chat_message_asset (message_id,asset_id) VALUES ($1,$2)', [id,assetId]);
  }
  const agent = await authenticatedRequest(createApp(pool));
  for (const platform of ['wechat', 'qq', 'douyin']) {
    const empty = platform === 'douyin';
    const overview = await agent.get(`/api/v1/dashboard/chat/overview?user_id=${userId}&platform=${platform}`);
    expect(overview.status).toBe(200);
    expect(overview.body).toEqual({conversation_count: empty ? 0 : 1, message_count: empty ? 0 : 2, media_count: empty ? 0 : 1});
    const list = await agent.get(`/api/v1/dashboard/chat/conversations?user_id=${userId}&platform=${platform}&page_size=1`);
    expect(list.status).toBe(200);
    expect(list.body.total).toBe(empty ? 0 : 1);
    expect(list.body.conversations.map((c: {platform: string}) => c.platform)).toEqual(empty ? [] : [platform]);
    const page2 = await agent.get(`/api/v1/dashboard/chat/conversations?user_id=${userId}&platform=${platform}&page_size=1&page=2`);
    expect(page2.body.conversations).toEqual([]);
  }
});

it('拒绝未知或重复的平台参数而不是悄悄返回全部聊天', async () => {
  const agent = await authenticatedRequest(createApp(pool));
  for (const route of ['overview', 'conversations']) {
    for (const query of ['platform=unknown', 'platform=qq&platform=wechat', 'platform=']) {
      expect((await agent.get(`/api/v1/dashboard/chat/${route}?user_id=${userId}&${query}`)).status).toBe(400);
    }
  }
});

it('平台媒体统计排除未关联图片与其他用户消息关联图片', async () => {
  for (const [index, linked] of [false, true].entries()) {
    const { rows } = await pool.query(`INSERT INTO media_asset
      (user_id,sha256,mime_type,storage_path,byte_size)
      VALUES ($1,$2,'image/png','chat/test.png',1) RETURNING id`, [userId, String(index + 7).repeat(64)]);
    if (linked) {
      const foreign = await pool.query('SELECT id FROM chat_message WHERE user_id <> $1', [userId]);
      await pool.query('INSERT INTO chat_message_asset(message_id,asset_id) VALUES ($1,$2)', [foreign.rows[0].id, rows[0].id]);
    }
  }
  const agent = await authenticatedRequest(createApp(pool));
  expect((await agent.get(`/api/v1/dashboard/chat/overview?user_id=${userId}&platform=wechat`)).body.media_count).toBe(1);
  expect((await agent.get(`/api/v1/dashboard/chat/overview?user_id=${userId}&platform=qq`)).body.media_count).toBe(0);
});
