import { readFileSync } from 'node:fs';
import { newDb } from 'pg-mem';
import type pg from 'pg';
import request from 'supertest';
import { beforeEach, describe, expect, it } from 'vitest';
import { createApp } from '../app.js';

let pool: pg.Pool;
let conversationId: number;
const userId = '00000000-0000-0000-0000-000000000001';

beforeEach(async () => {
  const database = newDb();
  const adapter = database.adapters.createPg();
  pool = new adapter.Pool();
  await pool.query(readFileSync(
    new URL('../../migrations/007_chat_capture.sql', import.meta.url),
    'utf8',
  ));
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
  const firstMessageId = crypto.randomUUID();
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
});

describe('chat dashboard API', () => {
  it('概览返回稳定的会话、消息和媒体计数', async () => {
    const response = await request(createApp(pool)).get('/api/v1/dashboard/chat/overview');

    expect(response.status).toBe(200);
    expect(response.body).toEqual({
      conversation_count: 1,
      message_count: 2,
      media_count: 1,
    });
  });

  it('会话列表返回稳定字段并支持分页', async () => {
    const response = await request(createApp(pool))
      .get('/api/v1/dashboard/chat/conversations?page=1&page_size=1');

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
    const response = await request(createApp(pool))
      .get(`/api/v1/dashboard/chat/messages?conversation_id=${conversationId}&page=2&page_size=1`);

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
});
