import { createHash, randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { newDb } from 'pg-mem';
import type pg from 'pg';
import request from 'supertest';
import { beforeEach, describe, expect, it } from 'vitest';
import { createApp } from '../app.js';

const USER_ID = process.env.DEFAULT_USER_ID ?? '00000000-0000-0000-0000-000000000001';
const migrations = [
  new URL('../../migrations/007_chat_capture.sql', import.meta.url),
  new URL('../../migrations/008_relationship_profile.sql', import.meta.url),
].map((url) => readFileSync(fileURLToPath(url), 'utf8'));

let pool: pg.Pool;
let conversationId: number;
let sequence: number;

beforeEach(async () => {
  const database = newDb();
  const adapter = database.adapters.createPg();
  pool = new adapter.Pool();
  for (const migration of migrations) await pool.query(migration);
  const conversation = await pool.query<{ id: number }>(`INSERT INTO chat_conversation
    (user_id, platform, account_key, external_key, display_name,
     conversation_type, identity_confidence)
    VALUES ($1, 'wechat', 'account', 'peer', '老朋友', 'direct', 0.95)
    RETURNING id`, [USER_ID]);
  conversationId = Number(conversation.rows[0].id);
  sequence = 0;
});

async function addMessage(direction: 'incoming' | 'outgoing', text: string): Promise<void> {
  sequence += 1;
  const digest = createHash('sha256').update(`api:${sequence}`).digest('hex');
  await pool.query(`INSERT INTO chat_message
    (id, user_id, device_id, conversation_id, platform, fingerprint,
     content_fingerprint, sender_key, direction, message_type, text, captured_at)
    VALUES ($1, $2, $3, $4, 'wechat', $5, $5, $6, $7, 'text', $8, $9)`, [
    randomUUID(), USER_ID, randomUUID(), conversationId, digest,
    direction === 'outgoing' ? 'self' : 'peer', direction, text,
    new Date(Date.UTC(2026, 7, 24, 1, 0, sequence)),
  ]);
}

async function addSticker(
  direction: 'incoming' | 'outgoing',
  sha256: string,
  targetConversationId = conversationId,
  ownerId = USER_ID,
): Promise<void> {
  sequence += 1;
  const messageId = randomUUID();
  const digest = createHash('sha256').update(`sticker-api:${ownerId}:${sequence}`).digest('hex');
  const asset = await pool.query<{ id: number }>(`INSERT INTO media_asset
    (user_id, sha256, mime_type, storage_path, byte_size, width, height)
    VALUES ($1, $2, 'image/webp', $3, 128, 120, 100)
    ON CONFLICT (user_id, sha256) DO UPDATE SET sha256 = EXCLUDED.sha256
    RETURNING id`, [ownerId, sha256, `${ownerId}/${sha256}.webp`]);
  await pool.query(`INSERT INTO chat_message
    (id, user_id, device_id, conversation_id, platform, fingerprint,
     content_fingerprint, sender_key, direction, message_type, captured_at)
    VALUES ($1, $2, $3, $4, 'wechat', $5, $5, $6, $7, 'sticker', $8)`, [
    messageId,
    ownerId,
    randomUUID(),
    targetConversationId,
    digest,
    direction === 'outgoing' ? 'self' : 'peer',
    direction,
    new Date(Date.UTC(2026, 7, 24, 2, 0, sequence)),
  ]);
  await pool.query(`INSERT INTO chat_message_asset
    (message_id, asset_id, role, position)
    VALUES ($1, $2, 'content', 0)`, [messageId, asset.rows[0].id]);
}

describe('relationship APIs', () => {
  it('移动端使用完整会话身份查询零 Token 候选', async () => {
    await addMessage('incoming', '在吗');
    await addMessage('outgoing', '在的');

    const response = await request(createApp(pool))
      .post('/api/v1/mobile/relationships/candidates')
      .send({
        platform: 'wechat',
        account_key: 'account',
        external_key: 'peer',
        context_text: '在吗',
      });

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({
      conversation_id: conversationId,
      relationship_type: 'unknown',
      candidates: [{ text: '在的', source: 'context_match' }],
    });
  });

  it('移动端未知会话安全返回空候选且非法请求返回 400', async () => {
    const missing = await request(createApp(pool))
      .post('/api/v1/mobile/relationships/candidates')
      .send({ platform: 'qq', account_key: 'account', external_key: 'missing' });
    expect(missing.status).toBe(200);
    expect(missing.body).toMatchObject({
      conversation_id: null,
      candidates: [],
      reason: 'conversation_not_found',
    });

    const invalid = await request(createApp(pool))
      .post('/api/v1/mobile/relationships/candidates')
      .send({ platform: 'wechat', account_key: 'account' });
    expect(invalid.status).toBe(400);
    expect(invalid.body.error).toContain('external_key');
  });

  it('移动端按完整会话身份查询表情反击候选', async () => {
    const incoming = 'a'.repeat(64);
    const reply = 'b'.repeat(64);
    await addSticker('incoming', incoming);
    await addSticker('outgoing', reply);

    const response = await request(createApp(pool))
      .post('/api/v1/mobile/relationships/sticker-candidates')
      .send({
        platform: 'wechat',
        account_key: 'account',
        external_key: 'peer',
        incoming_asset_sha256: incoming,
      });

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({
      conversation_id: conversationId,
      incoming_asset_sha256: incoming,
      candidates: [{ sha256: reply, source: 'sticker_counterattack' }],
    });
  });

  it('移动端表情候选对未知会话安全返回空结果并拒绝非法哈希', async () => {
    const missing = await request(createApp(pool))
      .post('/api/v1/mobile/relationships/sticker-candidates')
      .send({
        platform: 'qq',
        account_key: 'account',
        external_key: 'missing',
      });
    expect(missing.status).toBe(200);
    expect(missing.body).toMatchObject({
      conversation_id: null,
      incoming_asset_sha256: null,
      candidates: [],
      reason: 'conversation_not_found',
    });

    const invalid = await request(createApp(pool))
      .post('/api/v1/mobile/relationships/sticker-candidates')
      .send({
        platform: 'wechat',
        account_key: 'account',
        external_key: 'peer',
        incoming_asset_sha256: 'not-a-sha256',
      });
    expect(invalid.status).toBe(400);
    expect(invalid.body.error).toContain('incoming_asset_sha256');
  });

  it('后台分页列出未建档会话的安全默认值', async () => {
    await addMessage('outgoing', '好的');

    const response = await request(createApp(pool))
      .get('/api/v1/dashboard/relationships?page=1&page_size=10');

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({ total: 1, page: 1, page_size: 10 });
    expect(response.body.relationships[0]).toMatchObject({
      conversation_id: conversationId,
      display_name: '老朋友',
      relationship_type: 'unknown',
      alias: null,
      intimacy_level: 50,
      humor_level: 50,
      message_count: 1,
    });
  });

  it('后台更新关系档案支持 UPSERT 并严格校验输入', async () => {
    const app = createApp(pool);
    const payload = {
      relationship_type: 'friend',
      alias: '阿明',
      intimacy_level: 85,
      humor_level: 75,
      notes: '可以开玩笑',
    };
    const created = await request(app)
      .put(`/api/v1/dashboard/relationships/${conversationId}`)
      .send(payload);
    expect(created.status).toBe(200);
    expect(created.body.profile).toMatchObject(payload);

    const updated = await request(app)
      .put(`/api/v1/dashboard/relationships/${conversationId}`)
      .send({ ...payload, alias: '老明', humor_level: 80 });
    expect(updated.status).toBe(200);
    expect(updated.body.profile).toMatchObject({ alias: '老明', humor_level: 80 });
    expect((await pool.query('SELECT id FROM relationship_profile')).rowCount).toBe(1);

    const invalid = await request(app)
      .put(`/api/v1/dashboard/relationships/${conversationId}`)
      .send({ ...payload, relationship_type: 'stranger' });
    expect(invalid.status).toBe(400);
    expect(invalid.body.error).toContain('relationship_type');
  });

  it('后台拒绝其他用户会话并能预览上下文候选', async () => {
    const otherUserConversation = await pool.query<{ id: number }>(`INSERT INTO chat_conversation
      (user_id, platform, account_key, external_key, conversation_type, identity_confidence)
      VALUES ($1, 'wechat', 'account', 'other', 'direct', 0.95)
      RETURNING id`, [randomUUID()]);
    const forbidden = await request(createApp(pool))
      .put(`/api/v1/dashboard/relationships/${otherUserConversation.rows[0].id}`)
      .send({
        relationship_type: 'friend',
        intimacy_level: 50,
        humor_level: 50,
      });
    expect(forbidden.status).toBe(404);

    await addMessage('incoming', '吃饭了吗');
    await addMessage('outgoing', '刚吃完');
    const preview = await request(createApp(pool))
      .get(`/api/v1/dashboard/relationships/${conversationId}/candidates`)
      .query({ context_text: '吃饭了吗', limit: 3 });
    expect(preview.status).toBe(200);
    expect(preview.body.candidates[0]).toMatchObject({
      text: '刚吃完',
      source: 'context_match',
    });
  });

  it('后台列出最近收到表情并预览反击候选', async () => {
    const incoming = 'c'.repeat(64);
    const reply = 'd'.repeat(64);
    await addSticker('incoming', incoming);
    await addSticker('outgoing', reply);
    const app = createApp(pool);

    const recent = await request(app)
      .get(`/api/v1/dashboard/relationships/${conversationId}/incoming-sticker-assets`);
    expect(recent.status).toBe(200);
    expect(recent.body.assets[0]).toMatchObject({ sha256: incoming });

    const preview = await request(app)
      .get(`/api/v1/dashboard/relationships/${conversationId}/sticker-candidates`)
      .query({ incoming_asset_sha256: incoming, limit: 3 });
    expect(preview.status).toBe(200);
    expect(preview.body.candidates[0]).toMatchObject({
      sha256: reply,
      source: 'sticker_counterattack',
    });
  });

  it('后台表情接口拒绝其他用户会话', async () => {
    const otherUserConversation = await pool.query<{ id: number }>(`INSERT INTO chat_conversation
      (user_id, platform, account_key, external_key, conversation_type, identity_confidence)
      VALUES ($1, 'wechat', 'account', 'private-stickers', 'direct', 0.95)
      RETURNING id`, [randomUUID()]);
    const id = otherUserConversation.rows[0].id;

    const recent = await request(createApp(pool))
      .get(`/api/v1/dashboard/relationships/${id}/incoming-sticker-assets`);
    const preview = await request(createApp(pool))
      .get(`/api/v1/dashboard/relationships/${id}/sticker-candidates`);

    expect(recent.status).toBe(404);
    expect(preview.status).toBe(404);
  });
});
