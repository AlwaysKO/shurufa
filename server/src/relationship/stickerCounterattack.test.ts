import { createHash, randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { newDb } from 'pg-mem';
import type pg from 'pg';
import { beforeEach, describe, expect, it } from 'vitest';
import {
  getRecentIncomingStickerAssets,
  getStickerCounterattackCandidates,
} from './stickerCounterattack.js';

const migrations = [
  new URL('../../migrations/007_chat_capture.sql', import.meta.url),
  new URL('../../migrations/008_relationship_profile.sql', import.meta.url),
].map((url) => readFileSync(fileURLToPath(url), 'utf8'));

let pool: pg.Pool;
let userId: string;
let deviceId: string;
let sequence: number;

beforeEach(async () => {
  const database = newDb();
  const adapter = database.adapters.createPg();
  pool = new adapter.Pool();
  for (const migration of migrations) await pool.query(migration);
  userId = randomUUID();
  deviceId = randomUUID();
  sequence = 0;
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

async function asset(
  sha256: string,
  ownerId = userId,
): Promise<number> {
  const result = await pool.query<{ id: number }>(`INSERT INTO media_asset
    (user_id, sha256, mime_type, storage_path, byte_size, width, height)
    VALUES ($1, $2, 'image/webp', $3, 128, 120, 100)
    ON CONFLICT (user_id, sha256) DO UPDATE SET sha256 = EXCLUDED.sha256
    RETURNING id`, [ownerId, sha256, `${ownerId}/${sha256}.webp`]);
  return Number(result.rows[0].id);
}

async function stickerMessage(options: {
  conversationId: number;
  direction: 'incoming' | 'outgoing' | 'system';
  sha256: string;
  messageType?: 'emoji' | 'sticker' | 'image';
  role?: string;
  position?: number;
  ownerId?: string;
}): Promise<void> {
  sequence += 1;
  const ownerId = options.ownerId ?? userId;
  const capturedAt = new Date(Date.UTC(2026, 7, 24, 0, 0, sequence));
  const fingerprint = createHash('sha256')
    .update(`${ownerId}:${options.conversationId}:${sequence}`)
    .digest('hex');
  const messageId = randomUUID();
  const assetId = await asset(options.sha256, ownerId);
  await pool.query(`INSERT INTO chat_message
    (id, user_id, device_id, conversation_id, platform, fingerprint,
     content_fingerprint, sender_key, direction, message_type, captured_at)
    VALUES ($1, $2, $3, $4, 'wechat', $5, $5, $6, $7, $8, $9)`, [
    messageId,
    ownerId,
    deviceId,
    options.conversationId,
    fingerprint,
    options.direction === 'outgoing' ? 'self' : 'peer',
    options.direction,
    options.messageType ?? 'sticker',
    capturedAt,
  ]);
  await pool.query(`INSERT INTO chat_message_asset
    (message_id, asset_id, role, position)
    VALUES ($1, $2, $3, $4)`, [
    messageId,
    assetId,
    options.role ?? 'content',
    options.position ?? 0,
  ]);
}

const identity = (externalKey: string) => ({
  platform: 'wechat' as const,
  account_key: 'account',
  external_key: externalKey,
});

describe('getStickerCounterattackCandidates', () => {
  it('按反击、当前会话、关系类型和用户通用顺序回退并跨层去重', async () => {
    const current = await conversation('current', 'friend');
    const friend = await conversation('another-friend', 'friend');
    const family = await conversation('family', 'family');
    const incoming = 'a'.repeat(64);
    const exact = 'b'.repeat(64);
    const currentFrequent = 'c'.repeat(64);
    const relationFrequent = 'd'.repeat(64);
    const globalFrequent = 'e'.repeat(64);

    await stickerMessage({ conversationId: current, direction: 'incoming', sha256: incoming });
    await stickerMessage({ conversationId: current, direction: 'outgoing', sha256: exact });
    await stickerMessage({ conversationId: current, direction: 'outgoing', sha256: currentFrequent });
    await stickerMessage({ conversationId: current, direction: 'outgoing', sha256: currentFrequent });
    await stickerMessage({ conversationId: current, direction: 'outgoing', sha256: exact });
    await stickerMessage({ conversationId: friend, direction: 'outgoing', sha256: relationFrequent });
    await stickerMessage({ conversationId: friend, direction: 'outgoing', sha256: relationFrequent });
    await stickerMessage({ conversationId: friend, direction: 'outgoing', sha256: currentFrequent });
    await stickerMessage({ conversationId: family, direction: 'outgoing', sha256: globalFrequent });
    await stickerMessage({ conversationId: family, direction: 'outgoing', sha256: globalFrequent });
    await stickerMessage({ conversationId: family, direction: 'outgoing', sha256: globalFrequent });

    const result = await getStickerCounterattackCandidates(
      pool,
      userId,
      identity('current'),
      incoming,
      10,
    );

    expect(result.reason).toBeUndefined();
    expect(result.relationship_type).toBe('friend');
    expect(result.incoming_asset_sha256).toBe(incoming);
    expect(result.candidates.slice(0, 4).map((item) => item.sha256))
      .toEqual([exact, currentFrequent, relationFrequent, globalFrequent]);
    expect(result.candidates.map((item) => item.source)).toEqual([
      'sticker_counterattack',
      'sticker_conversation_frequency',
      'sticker_relationship_type_frequency',
      'sticker_global_frequency',
    ]);
    expect(result.candidates.filter((item) => item.sha256 === exact)).toHaveLength(1);
    expect(result.candidates[0].url).toContain(`/uploads/${userId}/${exact}.webp`);
  });

  it('只使用合格的发出表情且未知关系跳过类型回退', async () => {
    const current = await conversation('unknown');
    const other = await conversation('other');
    const otherUserId = randomUUID();
    const privateConversation = await conversation('private', 'friend', otherUserId);
    const good = '1'.repeat(64);
    const global = '2'.repeat(64);
    const excluded = ['3', '4', '5', '6', '7'].map((char) => char.repeat(64));

    await stickerMessage({ conversationId: current, direction: 'outgoing', sha256: good });
    await stickerMessage({ conversationId: current, direction: 'incoming', sha256: excluded[0] });
    await stickerMessage({ conversationId: current, direction: 'outgoing', sha256: excluded[1], messageType: 'image' });
    await stickerMessage({ conversationId: current, direction: 'outgoing', sha256: excluded[2], role: 'thumbnail' });
    await stickerMessage({ conversationId: current, direction: 'outgoing', sha256: excluded[3], position: 1 });
    await stickerMessage({ conversationId: privateConversation, direction: 'outgoing', sha256: excluded[4], ownerId: otherUserId });
    await stickerMessage({ conversationId: other, direction: 'outgoing', sha256: global, messageType: 'emoji' });

    const result = await getStickerCounterattackCandidates(
      pool,
      userId,
      identity('unknown'),
      null,
      20,
    );

    expect(result.relationship_type).toBe('unknown');
    expect(result.candidates.map((item) => item.sha256)).toEqual([good, global]);
    expect(result.candidates.some((item) => item.source === 'sticker_relationship_type_frequency'))
      .toBe(false);
    expect(result.candidates.map((item) => item.sha256))
      .not.toEqual(expect.arrayContaining(excluded));
  });

  it('未知会话安全返回空结果且最近收到表情按时间去重', async () => {
    const missing = await getStickerCounterattackCandidates(
      pool,
      userId,
      identity('missing'),
      'a'.repeat(64),
      6,
    );
    expect(missing).toEqual({
      conversation_id: null,
      relationship_type: 'unknown',
      incoming_asset_sha256: 'a'.repeat(64),
      candidates: [],
      reason: 'conversation_not_found',
    });

    const current = await conversation('current');
    const first = '8'.repeat(64);
    const second = '9'.repeat(64);
    await stickerMessage({ conversationId: current, direction: 'incoming', sha256: first });
    await stickerMessage({ conversationId: current, direction: 'incoming', sha256: second, messageType: 'emoji' });
    await stickerMessage({ conversationId: current, direction: 'incoming', sha256: first });
    await stickerMessage({ conversationId: current, direction: 'outgoing', sha256: 'a'.repeat(64) });
    await stickerMessage({ conversationId: current, direction: 'incoming', sha256: 'b'.repeat(64), messageType: 'image' });

    const recent = await getRecentIncomingStickerAssets(pool, userId, current, 6);

    expect(recent.map((item) => item.sha256)).toEqual([first, second]);
    expect(recent[0].url).toContain(`/uploads/${userId}/${first}.webp`);
  });
});
