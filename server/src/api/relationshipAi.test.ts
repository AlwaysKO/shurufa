import { createHash, randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { newDb } from 'pg-mem';
import type pg from 'pg';
import request from 'supertest';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RelationshipAiProvider } from '../ai/deepSeekProvider.js';
import { createApp } from '../app.js';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';

const USER = '00000000-0000-0000-0000-000000000001';
let pool: pg.Pool;
let conversationId: number;
let provider: RelationshipAiProvider;

beforeEach(async () => {
  const db = newDb(); const adapter = db.adapters.createPg(); pool = new adapter.Pool();
  for (const file of ['007_chat_capture.sql', '008_relationship_profile.sql', '014_relationship_ai.sql']) {
    await pool.query(readFileSync(fileURLToPath(new URL(`../../migrations/${file}`, import.meta.url)), 'utf8'));
  }
  const c = await pool.query<{ id: number }>(`INSERT INTO chat_conversation
    (user_id,platform,account_key,external_key,display_name,conversation_type,identity_confidence)
    VALUES($1,'wechat','self','peer','朋友','direct',0.95) RETURNING id`, [USER]);
  conversationId = Number(c.rows[0].id);
  let sequence = 0;
  for (const [direction, text] of [['incoming', '周末有空吗'], ['outgoing', '有呀']] as const) {
    const id = randomUUID(); const hash = createHash('sha256').update(String(++sequence)).digest('hex');
    await pool.query(`INSERT INTO chat_message
      (id,user_id,device_id,conversation_id,platform,fingerprint,content_fingerprint,sender_key,direction,message_type,text,captured_at)
      VALUES($1,$2,$3,$4,'wechat',$5,$5,$6,$7,'text',$8,NOW())`,
    [id, USER, randomUUID(), conversationId, hash, direction === 'outgoing' ? 'self' : 'peer', direction, text]);
  }
  provider = { available: true, model: 'fake', completeJson: vi.fn(async ({ system }) => ({
    json: system.includes('画像') ? {
      summary: '好友', user_style: ['简短'], peer_style: ['直接'], relationship_signals: ['熟悉'],
      preferred_tone: ['自然'], avoid: [], evidence_message_ids: [],
    } : { replies: ['有空，怎么啦'] }, usage: {},
  })) };
});

describe('relationship AI APIs', () => {
  it('lets dashboard train/read/preview and mobile request replies', async () => {
    const app = createApp(pool, { relationshipAiProvider: provider, relationshipAiMinMessages: 2 });
    const auth = await authenticatedRequest(app);
    const trained = await auth.post(`/api/v1/dashboard/relationships/${conversationId}/ai-profile/train?user_id=${USER}`).send({});
    expect(trained.status).toBe(200);
    expect(trained.body.profile).toMatchObject({ version: 1, profile: { summary: '好友' } });

    const read = await auth.get(`/api/v1/dashboard/relationships/${conversationId}/ai-profile?user_id=${USER}`);
    expect(read.status).toBe(200);
    expect(read.body.profile.version).toBe(1);

    const payload = { ...{
        platform: 'wechat', account_key: 'self', external_key: 'peer',
      }, context_text: '周末有空吗', refresh_count: 20, reply_session_id: randomUUID(), limit: 3 };
    const initial = await request(app).post('/api/v1/mobile/relationships/ai-replies').set('X-Device-Id', USER).send(payload);
    const firstRefresh = await request(app).post('/api/v1/mobile/relationships/ai-replies').set('X-Device-Id', USER).send(payload);
    expect(initial.body).toMatchObject({ ai_used: false, fallback_reason: 'refresh_threshold' });
    expect(firstRefresh.body).toMatchObject({ ai_used: false, fallback_reason: 'refresh_threshold' });
    const mobile = await request(app).post('/api/v1/mobile/relationships/ai-replies')
      .set('X-Device-Id', USER).send(payload);
    expect(mobile.status).toBe(200);
    expect(mobile.body).toMatchObject({ ai_used: true, candidates: [{ text: '有空，怎么啦', source: 'ai' }] });
  });

  it('validates requests and hides provider failures behind fallback', async () => {
    const app = createApp(pool, { relationshipAiProvider: provider, relationshipAiMinMessages: 3 });
    const auth = await authenticatedRequest(app);
    const train = await auth.post(`/api/v1/dashboard/relationships/${conversationId}/ai-profile/train?user_id=${USER}`).send({});
    expect(train.status).toBe(409);
    expect(train.body.error).toBe('not_enough_messages');

    const invalid = await request(app).post('/api/v1/mobile/relationships/ai-replies')
      .set('X-Device-Id', USER).send({ platform: 'wechat' });
    expect(invalid.status).toBe(400);
  });
});
