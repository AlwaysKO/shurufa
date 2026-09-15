import { createHash, randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { newDb } from 'pg-mem';
import type pg from 'pg';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RelationshipAiProvider } from '../ai/deepSeekProvider.js';
import { getLatestAiProfile, trainRelationshipAiProfile } from './aiProfile.js';

const USER = '00000000-0000-0000-0000-000000000001';
let pool: pg.Pool;
let conversationId: number;

beforeEach(async () => {
  const db = newDb();
  const adapter = db.adapters.createPg();
  pool = new adapter.Pool();
  for (const file of ['007_chat_capture.sql', '008_relationship_profile.sql', '014_relationship_ai.sql']) {
    await pool.query(readFileSync(fileURLToPath(new URL(`../../migrations/${file}`, import.meta.url)), 'utf8'));
  }
  const result = await pool.query<{ id: number }>(`INSERT INTO chat_conversation
    (user_id, platform, account_key, external_key, display_name, conversation_type, identity_confidence)
    VALUES ($1, 'wechat', 'self', 'team', '项目群', 'group', 0.95) RETURNING id`, [USER]);
  conversationId = Number(result.rows[0].id);
});

async function message(direction: 'incoming' | 'outgoing', text: string, sender: string, deliveryStatus?: string): Promise<void> {
  const id = randomUUID();
  const hash = createHash('sha256').update(id).digest('hex');
  await pool.query(`INSERT INTO chat_message
    (id,user_id,device_id,conversation_id,platform,fingerprint,content_fingerprint,
     sender_key,sender_name,direction,message_type,text,metadata,captured_at)
    VALUES ($1,$2,$3,$4,'wechat',$5,$5,$6,$6,$7,'text',$8,$9,NOW())`,
  [id, USER, randomUUID(), conversationId, hash, sender, direction, text,
    deliveryStatus ? { delivery_status: deliveryStatus } : {}]);
}

describe('relationship AI profile', () => {
  it('trains from both sides and saves immutable versions', async () => {
    await message('incoming', '今晚能上线吗', '小周');
    await message('outgoing', '可以，八点左右', 'self');
    await message('outgoing', '未发送语音草稿', 'self', 'transcribed_not_send_confirmed');
    const completeJson = vi.fn(async (request: { user: string }) => {
      const payload = JSON.parse(request.user) as { messages: Array<{ id: string }> };
      return { json: {
        summary: '合作群聊', user_style: ['简短'], peer_style: ['直接'],
        relationship_signals: ['协作'], preferred_tone: ['自然'], avoid: [],
        evidence_message_ids: [payload.messages[0].id, 'invented-id'],
      }, usage: { prompt_tokens: 20, completion_tokens: 10 } };
    });
    const provider: RelationshipAiProvider = { available: true, model: 'fake', completeJson };

    const first = await trainRelationshipAiProfile(pool, USER, conversationId, provider, { minMessages: 2 });
    const second = await trainRelationshipAiProfile(pool, USER, conversationId, provider, { minMessages: 2 });

    expect(first.version).toBe(1);
    expect(first.profile.evidence_message_ids).toHaveLength(1);
    expect(second.version).toBe(2);
    const prompt = (completeJson.mock.calls as unknown as Array<[{ user: string }]>)[0]?.[0].user ?? '';
    expect(prompt).toContain('小周');
    expect(prompt).toContain('今晚能上线吗');
    expect(prompt).toContain('可以，八点左右');
    expect(prompt).not.toContain('未发送语音草稿');
    expect((await getLatestAiProfile(pool, USER, conversationId))?.version).toBe(2);
  });

  it('does not train another users conversation or too little history', async () => {
    const provider: RelationshipAiProvider = { available: true, model: 'fake', completeJson: vi.fn() };
    await expect(trainRelationshipAiProfile(pool, randomUUID(), conversationId, provider, { minMessages: 1 }))
      .rejects.toThrow('conversation not found');
    await expect(trainRelationshipAiProfile(pool, USER, conversationId, provider, { minMessages: 2 }))
      .rejects.toThrow('not enough');
  });
});
