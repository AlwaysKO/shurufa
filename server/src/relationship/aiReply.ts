import type pg from 'pg';
import type { RelationshipAiProvider } from '../ai/deepSeekProvider.js';
import { validateAiReplyResult } from '../domain/relationshipAiValidation.js';
import type { AiReplyQuery, RelationshipReplyResult } from '../types/relationshipAi.js';
import { getZeroTokenCandidates } from './zeroTokenCandidates.js';
import { getLatestAiProfile, safeLogCall } from './aiProfile.js';

export async function generateRelationshipReplies(
  pool: pg.Pool, userId: string, query: AiReplyQuery, provider: RelationshipAiProvider,
): Promise<RelationshipReplyResult> {
  const zero = await getZeroTokenCandidates(pool, userId, query.identity, query.contextText, query.limit);
  const fallback = (reason: RelationshipReplyResult['fallback_reason'], version: number | null = null): RelationshipReplyResult => ({
    conversation_id: zero.conversation_id,
    candidates: zero.candidates.map(({ text }) => ({ text, source: 'zero_token' as const })),
    zero_token_candidates: zero.candidates,
    ai_used: false,
    profile_version: version,
    fallback_reason: reason,
  });
  if (zero.conversation_id === null) return fallback('conversation_not_found');
  if (query.refreshCount < 2) return fallback('refresh_threshold');
  const profile = await getLatestAiProfile(pool, userId, zero.conversation_id);
  if (!profile) return fallback('profile_missing');
  if (!provider.available) return fallback('ai_unavailable', profile.version);
  if (!await withinAiBudget(pool, userId)) return fallback('budget_exceeded', profile.version);

  const messages = await pool.query<{ sender_name: string | null; direction: string; text: string }>(
    `SELECT sender_name,direction,text FROM chat_message
     WHERE user_id=$1 AND conversation_id=$2 AND message_type='text'
       AND direction IN ('incoming','outgoing') AND text IS NOT NULL
       AND COALESCE(metadata->>'delivery_status','') <> 'transcribed_not_send_confirmed'
     ORDER BY captured_at DESC,id DESC LIMIT 30`, [userId, zero.conversation_id],
  );
  const started = Date.now();
  try {
    const completion = await provider.completeJson({
      system: '你替用户拟写聊天回复。模仿用户在当前关系中的真实表达，但不要虚构事实、承诺或敏感信息。仅输出 JSON：{"replies":["候选1"]}。',
      user: JSON.stringify({
        profile: profile.profile,
        current_message: query.contextText,
        recent_messages: [...messages.rows].reverse().map((m) => ({
          role: m.direction === 'outgoing' ? '用户' : (m.sender_name || '对方'), text: m.text.slice(0, 2_000),
        })),
        historical_candidates: zero.candidates.map((candidate) => candidate.text),
        reply_count: query.limit,
      }),
    });
    const replies = validateAiReplyResult(completion.json, query.limit);
    await safeLogCall(pool, userId, zero.conversation_id, 'reply', provider.model, 'success', Date.now() - started, completion.usage);
    return {
      conversation_id: zero.conversation_id,
      candidates: replies.map((text) => ({ text, source: 'ai' })),
      ai_used: true,
      profile_version: profile.version,
    };
  } catch {
    await safeLogCall(pool, userId, zero.conversation_id, 'reply', provider.model, 'failed', Date.now() - started, {});
    return fallback('ai_unavailable', profile.version);
  }
}

async function withinAiBudget(pool: pg.Pool, userId: string): Promise<boolean> {
  const dailyLimit = Number(process.env.RELATIONSHIP_AI_DAILY_REPLY_LIMIT ?? 50);
  const monthlyTokenLimit = Number(process.env.RELATIONSHIP_AI_MONTHLY_TOKEN_LIMIT ?? 1_000_000);
  const now = new Date();
  const dayStart = new Date(now); dayStart.setHours(0, 0, 0, 0);
  const monthStart = new Date(now.getFullYear(), now.getMonth(), 1);
  const [daily, monthly] = await Promise.all([
    pool.query<{ count: string | number }>(
      `SELECT COUNT(*) AS count FROM relationship_ai_call
       WHERE user_id=$1 AND purpose='reply' AND created_at >= $2`, [userId, dayStart],
    ),
    pool.query<{ tokens: string | number }>(
      `SELECT COALESCE(SUM(COALESCE(prompt_tokens,0)+COALESCE(completion_tokens,0)),0) AS tokens
       FROM relationship_ai_call WHERE user_id=$1 AND created_at >= $2`, [userId, monthStart],
    ),
  ]);
  return Number(daily.rows[0]?.count ?? 0) < dailyLimit &&
    Number(monthly.rows[0]?.tokens ?? 0) < monthlyTokenLimit;
}
