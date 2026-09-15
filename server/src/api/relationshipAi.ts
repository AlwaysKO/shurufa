import { Router } from 'express';
import type pg from 'pg';
import type { RelationshipAiProvider } from '../ai/deepSeekProvider.js';
import { validateAiReplyQuery } from '../domain/relationshipAiValidation.js';
import { generateRelationshipReplies } from '../relationship/aiReply.js';
import { getLatestAiProfile, trainRelationshipAiProfile } from '../relationship/aiProfile.js';
import type { RelationshipConversationIdentity } from '../types/relationship.js';
import { createHash } from 'node:crypto';

function id(value: string): number | null {
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
}

function message(error: unknown): string { return error instanceof Error ? error.message : 'invalid request'; }

export function createMobileRelationshipAiRouter(pool: pg.Pool, provider: RelationshipAiProvider): Router {
  const router = Router();
  router.post('/ai-replies', async (req, res, next) => {
    try {
      let query;
      try { query = validateAiReplyQuery(req.body); }
      catch (error) { return res.status(400).json({ error: message(error) }); }
      if (!query.sessionId) return res.status(400).json({ error: 'reply_session_id is required' });
      const owned = await pool.query<{ id: number | string }>(
        `SELECT id FROM chat_conversation WHERE user_id=$1 AND platform=$2 AND account_key=$3 AND external_key=$4`,
        [res.locals.userId, query.identity.platform, query.identity.account_key, query.identity.external_key],
      );
      if (owned.rows[0]) {
        query.refreshCount = await serverRefreshCount(
          pool, res.locals.userId, Number(owned.rows[0].id), query.sessionId, query.contextText,
        );
      }
      res.json(await generateRelationshipReplies(pool, res.locals.userId, query, provider));
    } catch (error) { next(error); }
  });
  return router;
}

async function serverRefreshCount(
  pool: pg.Pool, userId: string, conversationId: number, sessionId: string, contextText: string,
): Promise<number> {
  const contextHash = createHash('sha256').update(contextText.trim()).digest('hex');
  const result = await pool.query<{ refresh_count: number | string }>(`INSERT INTO relationship_ai_reply_session
    (user_id,session_id,conversation_id,context_hash,refresh_count)
    VALUES($1,$2,$3,$4,0)
    ON CONFLICT(user_id,session_id) DO UPDATE SET
      refresh_count=CASE
        WHEN relationship_ai_reply_session.conversation_id=EXCLUDED.conversation_id
         AND relationship_ai_reply_session.context_hash=EXCLUDED.context_hash
        THEN LEAST(relationship_ai_reply_session.refresh_count+1,20) ELSE 0 END,
      conversation_id=EXCLUDED.conversation_id,context_hash=EXCLUDED.context_hash,updated_at=NOW()
    RETURNING refresh_count`, [userId, sessionId, conversationId, contextHash]);
  return Number(result.rows[0]?.refresh_count ?? 0);
}

async function identityFor(
  pool: pg.Pool, userId: string, conversationId: number,
): Promise<RelationshipConversationIdentity | null> {
  const result = await pool.query<RelationshipConversationIdentity>(
    `SELECT platform,account_key,external_key FROM chat_conversation WHERE id=$1 AND user_id=$2`,
    [conversationId, userId],
  );
  return result.rows[0] ?? null;
}

export function createRelationshipAiDashboardRouter(
  pool: pg.Pool, provider: RelationshipAiProvider, minMessages?: number,
): Router {
  const router = Router();
  router.get('/:conversation_id/ai-profile', async (req, res, next) => {
    try {
      const conversationId = id(req.params.conversation_id);
      if (!conversationId) return res.status(400).json({ error: 'conversation_id is invalid' });
      if (!await identityFor(pool, res.locals.userId, conversationId)) return res.status(404).json({ error: 'conversation not found' });
      res.json({ profile: await getLatestAiProfile(pool, res.locals.userId, conversationId) });
    } catch (error) { next(error); }
  });
  router.post('/:conversation_id/ai-profile/train', async (req, res, next) => {
    try {
      const conversationId = id(req.params.conversation_id);
      if (!conversationId) return res.status(400).json({ error: 'conversation_id is invalid' });
      const profile = await trainRelationshipAiProfile(
        pool, res.locals.userId, conversationId, provider,
        minMessages === undefined ? {} : { minMessages },
      );
      res.json({ ok: true, profile });
    } catch (error) {
      const detail = message(error);
      if (detail === 'conversation not found') return res.status(404).json({ error: detail });
      if (detail.startsWith('not enough messages')) return res.status(409).json({ error: 'not_enough_messages' });
      if (detail === 'AI provider unavailable') return res.status(503).json({ error: 'ai_unavailable' });
      next(error);
    }
  });
  router.post('/:conversation_id/ai-replies', async (req, res, next) => {
    try {
      const conversationId = id(req.params.conversation_id);
      if (!conversationId) return res.status(400).json({ error: 'conversation_id is invalid' });
      const identity = await identityFor(pool, res.locals.userId, conversationId);
      if (!identity) return res.status(404).json({ error: 'conversation not found' });
      let query;
      try { query = validateAiReplyQuery({ ...identity, ...req.body }); }
      catch (error) { return res.status(400).json({ error: message(error) }); }
      res.json(await generateRelationshipReplies(pool, res.locals.userId, query, provider));
    } catch (error) { next(error); }
  });
  return router;
}
