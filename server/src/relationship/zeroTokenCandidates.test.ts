import { createHash, randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { newDb } from 'pg-mem';
import type pg from 'pg';
import { beforeEach, describe, expect, it } from 'vitest';
import { getZeroTokenCandidates } from './zeroTokenCandidates.js';

const migrations = [
  new URL('../../migrations/007_chat_capture.sql', import.meta.url),
  new URL('../../migrations/008_relationship_profile.sql', import.meta.url),
].map((url) => readFileSync(fileURLToPath(url), 'utf8'));

let pool: pg.Pool;
let userId: string;
let deviceId: string;
let messageSequence: number;

beforeEach(async () => {
  const database = newDb();
  const adapter = database.adapters.createPg();
  pool = new adapter.Pool();
  for (const migration of migrations) await pool.query(migration);
  userId = randomUUID();
  deviceId = randomUUID();
  messageSequence = 0;
});

async function conversation(
  externalKey: string,
  relationshipType?: 'unknown' | 'friend' | 'family',
  ownerId = userId,
): Promise<number> {
  const result = await pool.query<{ id: number }>(`INSERT INTO chat_conversation
    (user_id, platform, account_key, external_key, display_name,
     conversation_type, identity_confidence)
    VALUES ($1, 'wechat', 'account', $2, $2, 'direct', 0.95)
    RETURNING id`, [ownerId, externalKey]);
  const id = Number(result.rows[0].id);
  if (relationshipType) {
    await pool.query(`INSERT INTO relationship_profile
      (user_id, conversation_id, relationship_type)
      VALUES ($1, $2, $3)`, [ownerId, id, relationshipType]);
  }
  return id;
}

async function message(
  conversationId: number,
  direction: 'incoming' | 'outgoing' | 'system',
  text: string | null,
  messageType = 'text',
  ownerId = userId,
): Promise<void> {
  messageSequence += 1;
  const capturedAt = new Date(Date.UTC(2026, 7, 24, 0, 0, messageSequence));
  const digest = createHash('sha256')
    .update(`${ownerId}:${conversationId}:${messageSequence}`)
    .digest('hex');
  await pool.query(`INSERT INTO chat_message
    (id, user_id, device_id, conversation_id, platform, fingerprint,
     content_fingerprint, sender_key, direction, message_type, text, captured_at)
    VALUES ($1, $2, $3, $4, 'wechat', $5, $5, $6, $7, $8, $9, $10)`, [
    randomUUID(), ownerId, deviceId, conversationId, digest,
    direction === 'outgoing' ? 'self' : 'peer', direction, messageType, text, capturedAt,
  ]);
}

const identity = (externalKey: string) => ({
  platform: 'wechat' as const,
  account_key: 'account',
  external_key: externalKey,
});

describe('getZeroTokenCandidates', () => {
  it('按相同上下文、当前会话、关系类型和用户通用顺序回退', async () => {
    const current = await conversation('current', 'friend');
    const friend = await conversation('another-friend', 'friend');
    const family = await conversation('family', 'family');

    await message(current, 'incoming', '在吗');
    await message(current, 'outgoing', '在的');
    await message(current, 'incoming', ' 在吗 ');
    await message(current, 'outgoing', '马上来');
    await message(current, 'outgoing', '好的');
    await message(current, 'outgoing', '好的');
    await message(current, 'outgoing', '好的');
    await message(friend, 'outgoing', '收到');
    await message(friend, 'outgoing', '收到');
    await message(friend, 'outgoing', '好的');
    await message(family, 'outgoing', '没问题');
    await message(family, 'outgoing', '没问题');

    const result = await getZeroTokenCandidates(
      pool,
      userId,
      identity('current'),
      '  在吗  ',
      10,
    );
    expect(result.reason).toBeUndefined();
    expect(result.relationship_type).toBe('friend');
    expect(result.candidates.slice(0, 2).map((item) => item.text))
      .toEqual(['马上来', '在的']);
    expect(result.candidates.find((item) => item.text === '好的')?.source)
      .toBe('conversation_frequency');
    expect(result.candidates.find((item) => item.text === '收到')?.source)
      .toBe('relationship_type_frequency');
    expect(result.candidates.find((item) => item.text === '没问题')?.source)
      .toBe('global_frequency');
    expect(result.candidates.filter((item) => item.text === '好的')).toHaveLength(1);
  });

  it('只使用合格的真实发出文本且未知关系跳过类型回退', async () => {
    const current = await conversation('unknown');
    const other = await conversation('other');
    await message(current, 'incoming', '不该候选');
    await message(current, 'system', '系统提示');
    await message(current, 'outgoing', '图片说明', 'image');
    await message(current, 'outgoing', '   ');
    await message(current, 'outgoing', '长'.repeat(501));
    await message(current, 'outgoing', '可以回复');
    await message(other, 'outgoing', '通用回复');

    const result = await getZeroTokenCandidates(pool, userId, identity('unknown'), '', 20);

    expect(result.relationship_type).toBe('unknown');
    expect(result.candidates.map((item) => item.text)).toContain('可以回复');
    expect(result.candidates.map((item) => item.text)).toContain('通用回复');
    expect(result.candidates.some((item) => item.source === 'relationship_type_frequency'))
      .toBe(false);
    expect(result.candidates.map((item) => item.text)).not.toEqual(expect.arrayContaining([
      '不该候选', '系统提示', '图片说明', '   ', '长'.repeat(501),
    ]));
  });

  it('不跨用户匹配会话并遵守返回数量', async () => {
    const otherUserId = randomUUID();
    const otherConversation = await conversation('private', 'friend', otherUserId);
    await message(otherConversation, 'outgoing', '别人的回复', 'text', otherUserId);

    const missing = await getZeroTokenCandidates(
      pool,
      userId,
      identity('private'),
      '在吗',
      6,
    );
    expect(missing).toEqual({
      conversation_id: null,
      relationship_type: 'unknown',
      candidates: [],
      reason: 'conversation_not_found',
    });

    const ownConversation = await conversation('own');
    await message(ownConversation, 'outgoing', '一');
    await message(ownConversation, 'outgoing', '二');
    const limited = await getZeroTokenCandidates(pool, userId, identity('own'), '', 1);
    expect(limited.candidates).toHaveLength(1);
  });
});
