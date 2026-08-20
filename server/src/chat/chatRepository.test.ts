import { readFileSync } from 'node:fs';
import { newDb } from 'pg-mem';
import type pg from 'pg';
import { beforeEach, describe, expect, it } from 'vitest';
import type {
  CapturedConversationInput,
  CapturedMessageInput,
} from '../types/chat.js';
import { ingestCapturedMessages } from './chatRepository.js';

let pool: pg.Pool;
let userId: string;
let deviceId: string;

const conversation: CapturedConversationInput = {
  platform: 'wechat',
  account_key: 'local-account',
  external_key: 'direct:peer',
  display_name: '对方',
  conversation_type: 'direct',
  identity_confidence: 0.95,
};

function message(overrides: Partial<CapturedMessageInput> = {}): CapturedMessageInput {
  return {
    id: crypto.randomUUID(),
    fingerprint: 'a'.repeat(64),
    content_fingerprint: 'b'.repeat(64),
    sender_key: 'peer',
    sender_name: '对方',
    direction: 'incoming',
    message_type: 'text',
    text: '相同文本',
    displayed_time: '18:30',
    captured_at: new Date().toISOString(),
    ...overrides,
  };
}

beforeEach(async () => {
  const database = newDb();
  const adapter = database.adapters.createPg();
  pool = new adapter.Pool();
  const sql = readFileSync(
    new URL('../../migrations/007_chat_capture.sql', import.meta.url),
    'utf8',
  );
  await pool.query(sql);
  userId = crypto.randomUUID();
  deviceId = crypto.randomUUID();
});

describe('ingestCapturedMessages', () => {
  it('重复写入同一批消息时保持会话和消息幂等', async () => {
    const batch = [message()];

    const first = await ingestCapturedMessages(pool, userId, deviceId, conversation, batch);
    const second = await ingestCapturedMessages(pool, userId, deviceId, conversation, batch);

    expect(first).toMatchObject({ inserted: 1, duplicated: 0, missingAssets: [] });
    expect(second).toMatchObject({ inserted: 0, duplicated: 1, missingAssets: [] });
    expect(second.conversationId).toBe(first.conversationId);
    expect((await pool.query('SELECT id FROM chat_conversation')).rowCount).toBe(1);
    expect((await pool.query('SELECT id FROM chat_message')).rowCount).toBe(1);
  });

  it('相同文本但不同消息指纹会保留两条', async () => {
    const first = message();
    const second = message({ fingerprint: 'c'.repeat(64) });

    const result = await ingestCapturedMessages(
      pool,
      userId,
      deviceId,
      conversation,
      [first, second],
    );

    expect(result).toMatchObject({ inserted: 2, duplicated: 0 });
    expect((await pool.query('SELECT id FROM chat_message')).rowCount).toBe(2);
  });

  it('资源缺失时返回哈希且不提前写入相关消息', async () => {
    const missingSha256 = 'd'.repeat(64);

    const result = await ingestCapturedMessages(pool, userId, deviceId, conversation, [
      message({ message_type: 'image', asset_sha256: [missingSha256] }),
    ]);

    expect(result).toMatchObject({
      inserted: 0,
      duplicated: 0,
      missingAssets: [missingSha256],
    });
    expect((await pool.query('SELECT id FROM chat_message')).rowCount).toBe(0);
  });

  it('拒绝超过 200 条的批次', async () => {
    const batch = Array.from({ length: 201 }, (_, index) => message({
      id: crypto.randomUUID(),
      fingerprint: index.toString(16).padStart(64, '0'),
    }));

    await expect(ingestCapturedMessages(
      pool,
      userId,
      deviceId,
      conversation,
      batch,
    )).rejects.toThrow('200');
  });
});
