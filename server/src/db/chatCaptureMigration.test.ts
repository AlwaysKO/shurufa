import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { newDb } from 'pg-mem';
import type pg from 'pg';
import { beforeEach, describe, expect, it } from 'vitest';

const migrationPath = fileURLToPath(
  new URL('../../migrations/007_chat_capture.sql', import.meta.url),
);

let pool: pg.Pool;

beforeEach(async () => {
  const database = newDb();
  const adapter = database.adapters.createPg();
  pool = new adapter.Pool();
  await pool.query(readFileSync(migrationPath, 'utf8'));
});

describe('007_chat_capture.sql', () => {
  it('同一用户、平台、账号和外部键只保留一个会话', async () => {
    const values = [crypto.randomUUID(), 'wechat', 'account', 'peer', 'direct', 0.9];
    const insert = `INSERT INTO chat_conversation
      (user_id, platform, account_key, external_key, conversation_type, identity_confidence)
      VALUES ($1, $2, $3, $4, $5, $6)`;

    await pool.query(insert, values);
    await expect(pool.query(insert, values)).rejects.toThrow();
  });

  it('同一用户、平台和消息指纹只保留一条消息', async () => {
    const userId = crypto.randomUUID();
    const deviceId = crypto.randomUUID();
    const conversation = await pool.query<{ id: number }>(`INSERT INTO chat_conversation
      (user_id, platform, account_key, external_key, conversation_type, identity_confidence)
      VALUES ($1, 'wechat', 'account', 'peer', 'direct', 0.9) RETURNING id`, [userId]);
    const values = [
      crypto.randomUUID(), userId, deviceId, conversation.rows[0].id, 'wechat',
      'a'.repeat(64), 'b'.repeat(64), 'peer', 'incoming', 'text', new Date(),
    ];
    const insert = `INSERT INTO chat_message
      (id, user_id, device_id, conversation_id, platform, fingerprint,
       content_fingerprint, sender_key, direction, message_type, captured_at)
      VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)`;

    await pool.query(insert, values);
    values[0] = crypto.randomUUID();
    await expect(pool.query(insert, values)).rejects.toThrow();
  });

  it('同一用户和 SHA-256 只保留一个媒体资源', async () => {
    const values = [crypto.randomUUID(), 'a'.repeat(64), 'image/webp', 'chat/aa/a.webp', 10];
    const insert = `INSERT INTO media_asset
      (user_id, sha256, mime_type, storage_path, byte_size)
      VALUES ($1, $2, $3, $4, $5)`;

    await pool.query(insert, values);
    await expect(pool.query(insert, values)).rejects.toThrow();
  });

  it('同一消息和资源的同一角色只关联一次', async () => {
    const userId = crypto.randomUUID();
    const conversation = await pool.query<{ id: number }>(`INSERT INTO chat_conversation
      (user_id, platform, account_key, external_key, conversation_type, identity_confidence)
      VALUES ($1, 'wechat', 'account', 'peer', 'direct', 0.9) RETURNING id`, [userId]);
    const messageId = crypto.randomUUID();
    await pool.query(`INSERT INTO chat_message
      (id, user_id, device_id, conversation_id, platform, fingerprint,
       content_fingerprint, sender_key, direction, message_type, captured_at)
      VALUES ($1, $2, $3, $4, 'wechat', $5, $6, 'peer', 'incoming', 'image', $7)`, [
      messageId, userId, crypto.randomUUID(), conversation.rows[0].id,
      'a'.repeat(64), 'b'.repeat(64), new Date(),
    ]);
    const asset = await pool.query<{ id: number }>(`INSERT INTO media_asset
      (user_id, sha256, mime_type, storage_path, byte_size)
      VALUES ($1, $2, 'image/webp', 'chat/cc/c.webp', 10) RETURNING id`, [
      userId, 'c'.repeat(64),
    ]);
    const values = [messageId, asset.rows[0].id, 'content'];
    const insert = `INSERT INTO chat_message_asset (message_id, asset_id, role)
      VALUES ($1, $2, $3)`;

    await pool.query(insert, values);
    await expect(pool.query(insert, values)).rejects.toThrow();
  });
});
