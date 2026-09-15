import { createHash, randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { newDb } from 'pg-mem';
import type pg from 'pg';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RelationshipAiProvider } from '../ai/deepSeekProvider.js';
import { generateRelationshipReplies } from './aiReply.js';

const USER = '00000000-0000-0000-0000-000000000001';
const identity = { platform: 'wechat' as const, account_key: 'self', external_key: 'peer' };
let pool: pg.Pool;
let conversationId: number;

beforeEach(async () => {
  const db = newDb(); const adapter = db.adapters.createPg(); pool = new adapter.Pool();
  for (const file of ['007_chat_capture.sql', '008_relationship_profile.sql', '014_relationship_ai.sql']) {
    await pool.query(readFileSync(fileURLToPath(new URL(`../../migrations/${file}`, import.meta.url)), 'utf8'));
  }
  const c = await pool.query<{ id: number }>(`INSERT INTO chat_conversation
    (user_id,platform,account_key,external_key,conversation_type,identity_confidence)
    VALUES ($1,'wechat','self','peer','direct',0.95) RETURNING id`, [USER]);
  conversationId = Number(c.rows[0].id);
  let n = 0;
  for (const [direction, text] of [['incoming', '在吗'], ['outgoing', '在的']] as const) {
    const id = randomUUID(); const hash = createHash('sha256').update(String(++n)).digest('hex');
    await pool.query(`INSERT INTO chat_message
      (id,user_id,device_id,conversation_id,platform,fingerprint,content_fingerprint,sender_key,direction,message_type,text,captured_at)
      VALUES($1,$2,$3,$4,'wechat',$5,$5,$6,$7,'text',$8,NOW())`,
    [id, USER, randomUUID(), conversationId, hash, direction === 'outgoing' ? 'self' : 'peer', direction, text]);
  }
});

describe('relationship AI replies', () => {
  it('uses zero-token candidates before second refresh', async () => {
    const provider: RelationshipAiProvider = { available: true, model: 'fake', completeJson: vi.fn() };
    const result = await generateRelationshipReplies(pool, USER, { identity, contextText: '在吗', refreshCount: 1, limit: 3, sessionId: null }, provider);
    expect(result).toMatchObject({ ai_used: false, fallback_reason: 'refresh_threshold' });
    expect(result.candidates[0]).toEqual({ text: '在的', source: 'zero_token' });
    expect(provider.completeJson).not.toHaveBeenCalled();
  });

  it('uses latest profile on second refresh and falls back on provider failure', async () => {
    const draftId = randomUUID(); const draftHash = createHash('sha256').update('draft').digest('hex');
    await pool.query(`INSERT INTO chat_message
      (id,user_id,device_id,conversation_id,platform,fingerprint,content_fingerprint,sender_key,direction,message_type,text,metadata,captured_at)
      VALUES($1,$2,$3,$4,'wechat',$5,$5,'self','outgoing','text','未发送语音草稿',$6,NOW())`,
    [draftId, USER, randomUUID(), conversationId, draftHash, { delivery_status: 'transcribed_not_send_confirmed' }]);
    await pool.query(`INSERT INTO relationship_ai_profile
      (user_id,conversation_id,version,profile,source_message_count,model)
      VALUES($1,$2,1,$3,2,'fake')`, [USER, conversationId, JSON.stringify({
      summary: '老朋友', user_style: ['简短'], peer_style: [], relationship_signals: [],
      preferred_tone: ['自然'], avoid: [], evidence_message_ids: [],
    })]);
    const ok: RelationshipAiProvider = { available: true, model: 'fake', completeJson: vi.fn(async () => ({
      json: { replies: ['有事吗', '咋啦'] }, usage: {},
    })) };
    const generated = await generateRelationshipReplies(pool, USER, { identity, contextText: '在吗', refreshCount: 2, limit: 3, sessionId: null }, ok);
    expect(generated).toMatchObject({ ai_used: true, profile_version: 1 });
    expect(generated.candidates).toContainEqual({ text: '有事吗', source: 'ai' });
    const prompt = (ok.completeJson as ReturnType<typeof vi.fn>).mock.calls[0]?.[0].user as string;
    expect(prompt).not.toContain('未发送语音草稿');

    const broken: RelationshipAiProvider = { available: true, model: 'fake', completeJson: vi.fn(async () => { throw new Error('boom'); }) };
    const fallback = await generateRelationshipReplies(pool, USER, { identity, contextText: '在吗', refreshCount: 2, limit: 3, sessionId: null }, broken);
    expect(fallback).toMatchObject({ ai_used: false, fallback_reason: 'ai_unavailable' });
    expect(fallback.candidates[0].source).toBe('zero_token');
  });

  it('enforces the daily reply budget before calling the provider', async () => {
    await pool.query(`INSERT INTO relationship_ai_profile
      (user_id,conversation_id,version,profile,source_message_count,model)
      VALUES($1,$2,1,$3,2,'fake')`, [USER, conversationId, JSON.stringify({
      summary: '朋友', user_style: [], peer_style: [], relationship_signals: [],
      preferred_tone: [], avoid: [], evidence_message_ids: [],
    })]);
    const previous = process.env.RELATIONSHIP_AI_DAILY_REPLY_LIMIT;
    process.env.RELATIONSHIP_AI_DAILY_REPLY_LIMIT = '0';
    const provider: RelationshipAiProvider = { available: true, model: 'fake', completeJson: vi.fn() };
    try {
      const result = await generateRelationshipReplies(pool, USER, { identity, contextText: '在吗', refreshCount: 2, limit: 3, sessionId: null }, provider);
      expect(result.fallback_reason).toBe('budget_exceeded');
      expect(provider.completeJson).not.toHaveBeenCalled();
    } finally {
      if (previous === undefined) delete process.env.RELATIONSHIP_AI_DAILY_REPLY_LIMIT;
      else process.env.RELATIONSHIP_AI_DAILY_REPLY_LIMIT = previous;
    }
  });
});
