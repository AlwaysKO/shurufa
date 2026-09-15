import type pg from 'pg';
import type { RelationshipAiProvider } from '../ai/deepSeekProvider.js';
import { validateAiProfileDocument } from '../domain/relationshipAiValidation.js';
import type { RelationshipAiProfileDocument } from '../types/relationshipAi.js';

interface ProfileRow {
  version: number | string;
  profile: RelationshipAiProfileDocument | string;
  source_message_count: number | string;
  source_last_message_at: Date | string | null;
  model: string;
  created_at: Date | string;
}

export interface StoredAiProfile {
  version: number;
  profile: RelationshipAiProfileDocument;
  source_message_count: number;
  source_last_message_at: string | null;
  model: string;
  created_at: string;
}

function stored(row: ProfileRow): StoredAiProfile {
  return {
    version: Number(row.version),
    profile: validateAiProfileDocument(typeof row.profile === 'string' ? JSON.parse(row.profile) : row.profile),
    source_message_count: Number(row.source_message_count),
    source_last_message_at: row.source_last_message_at ? new Date(row.source_last_message_at).toISOString() : null,
    model: row.model,
    created_at: new Date(row.created_at).toISOString(),
  };
}

export async function getLatestAiProfile(
  pool: pg.Pool, userId: string, conversationId: number,
): Promise<StoredAiProfile | null> {
  const result = await pool.query<ProfileRow>(`SELECT version, profile, source_message_count,
      source_last_message_at, model, created_at
    FROM relationship_ai_profile
    WHERE user_id=$1 AND conversation_id=$2
    ORDER BY version DESC LIMIT 1`, [userId, conversationId]);
  return result.rows[0] ? stored(result.rows[0]) : null;
}

interface TrainOptions { minMessages?: number; maxMessages?: number }

export async function trainRelationshipAiProfile(
  pool: pg.Pool,
  userId: string,
  conversationId: number,
  provider: RelationshipAiProvider,
  options: TrainOptions = {},
): Promise<StoredAiProfile> {
  const conversation = await pool.query<{
    display_name: string | null; conversation_type: string; relationship_type: string | null;
    alias: string | null; intimacy_level: number | null; humor_level: number | null; notes: string | null;
  }>(`SELECT c.display_name,c.conversation_type,r.relationship_type,r.alias,
      r.intimacy_level,r.humor_level,r.notes
    FROM chat_conversation c
    LEFT JOIN relationship_profile r ON r.user_id=c.user_id AND r.conversation_id=c.id
    WHERE c.id=$1 AND c.user_id=$2`, [conversationId, userId]);
  if (!conversation.rows[0]) throw new Error('conversation not found');
  if (!provider.available) throw new Error('AI provider unavailable');

  const maxMessages = options.maxMessages ?? Number(process.env.RELATIONSHIP_AI_MAX_MESSAGES ?? 200);
  const messages = await pool.query<{
    id: string; sender_name: string | null; direction: string; text: string;
    captured_at: Date | string;
  }>(`SELECT id,sender_name,direction,text,captured_at FROM chat_message
    WHERE user_id=$1 AND conversation_id=$2 AND message_type='text'
      AND direction IN ('incoming','outgoing') AND text IS NOT NULL AND text <> ''
      AND COALESCE(metadata->>'delivery_status','') <> 'transcribed_not_send_confirmed'
    ORDER BY captured_at DESC,id DESC LIMIT $3`, [userId, conversationId, maxMessages]);
  const usableMessages = messages.rows.filter((message) => message.text.trim().length > 0);
  const minMessages = options.minMessages ?? Number(process.env.RELATIONSHIP_AI_MIN_MESSAGES ?? 20);
  if (usableMessages.length < minMessages) throw new Error(`not enough messages (need ${minMessages})`);
  const chronological = [...usableMessages].reverse();
  const meta = conversation.rows[0];
  const sample = chronological.map((message) => ({
    id: message.id,
    role: message.direction === 'outgoing' ? '用户' : (message.sender_name || '对方'),
    text: message.text.slice(0, 2_000),
  }));
  const started = Date.now();
  try {
    const completion = await provider.completeJson({
      system: '你是私人输入法的关系画像分析器。仅根据证据归纳，不猜测敏感属性。输出 JSON，字段为 summary、user_style、peer_style、relationship_signals、preferred_tone、avoid、evidence_message_ids。',
      user: JSON.stringify({ conversation: meta, messages: sample }),
    });
    const profile = validateAiProfileDocument(completion.json);
    const sampledIds = new Set(sample.map((message) => message.id));
    profile.evidence_message_ids = profile.evidence_message_ids.filter((id) => sampledIds.has(id));
    const lastMessageAt = chronological.at(-1)?.captured_at ?? null;
    const inserted = await insertNextProfileVersion(
      pool, userId, conversationId, profile, chronological.length, lastMessageAt, provider.model,
    );
    await safeLogCall(pool, userId, conversationId, 'profile', provider.model, 'success', Date.now() - started, completion.usage);
    return stored(inserted);
  } catch (error) {
    await safeLogCall(pool, userId, conversationId, 'profile', provider.model, 'failed', Date.now() - started, {});
    throw error;
  }
}

export async function logCall(
  pool: pg.Pool, userId: string, conversationId: number, purpose: 'profile' | 'reply',
  model: string, status: 'success' | 'failed', durationMs: number,
  usage: { prompt_tokens?: number; completion_tokens?: number },
): Promise<void> {
  await pool.query(`INSERT INTO relationship_ai_call
    (user_id,conversation_id,purpose,model,status,prompt_tokens,completion_tokens,duration_ms)
    VALUES($1,$2,$3,$4,$5,$6,$7,$8)`, [userId, conversationId, purpose, model, status,
    usage.prompt_tokens ?? null, usage.completion_tokens ?? null, durationMs]);
}

export async function safeLogCall(
  pool: pg.Pool, userId: string, conversationId: number, purpose: 'profile' | 'reply',
  model: string, status: 'success' | 'failed', durationMs: number,
  usage: { prompt_tokens?: number; completion_tokens?: number },
): Promise<void> {
  try { await logCall(pool, userId, conversationId, purpose, model, status, durationMs, usage); }
  catch { /* 审计写入不得阻断画像保存或零 Token 降级。 */ }
}

async function insertNextProfileVersion(
  pool: pg.Pool, userId: string, conversationId: number, profile: RelationshipAiProfileDocument,
  messageCount: number, lastMessageAt: Date | string | null, model: string,
): Promise<ProfileRow> {
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const current = await pool.query<{ version: number | string }>(
      `SELECT version FROM relationship_ai_profile
       WHERE user_id=$1 AND conversation_id=$2 ORDER BY version DESC LIMIT 1`, [userId, conversationId],
    );
    try {
      const result = await pool.query<ProfileRow>(`INSERT INTO relationship_ai_profile
        (user_id,conversation_id,version,profile,source_message_count,source_last_message_at,model)
        VALUES($1,$2,$3,$4,$5,$6,$7)
        RETURNING version,profile,source_message_count,source_last_message_at,model,created_at`, [
        userId, conversationId, Number(current.rows[0]?.version ?? 0) + 1, JSON.stringify(profile),
        messageCount, lastMessageAt, model,
      ]);
      return result.rows[0];
    } catch (error) {
      if ((error as { code?: string }).code !== '23505' || attempt === 2) throw error;
    }
  }
  throw new Error('profile version allocation failed');
}
