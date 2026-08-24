import { Router } from 'express';
import type pg from 'pg';
import {
  validateRelationshipCandidateQuery,
  validateRelationshipProfileInput,
} from '../domain/relationshipValidation.js';
import { getZeroTokenCandidates } from '../relationship/zeroTokenCandidates.js';
import type { RelationshipConversationIdentity } from '../types/relationship.js';

const DEFAULT_USER_ID = process.env.DEFAULT_USER_ID ?? '00000000-0000-0000-0000-000000000001';

function validationMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'invalid request';
}

function pagination(query: Record<string, unknown>): { page: number; pageSize: number; offset: number } {
  const page = Math.max(1, Math.trunc(Number(query.page) || 1));
  const pageSize = Math.min(100, Math.max(1, Math.trunc(Number(query.page_size) || 20)));
  return { page, pageSize, offset: (page - 1) * pageSize };
}

function conversationId(value: string): number | null {
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

function iso(value: unknown): unknown {
  return value instanceof Date ? value.toISOString() : value;
}

export function createRelationshipDashboardRouter(pool: pg.Pool): Router {
  const router = Router();

  router.get('/', async (req, res, next) => {
    try {
      const { page, pageSize, offset } = pagination(req.query as Record<string, unknown>);
      const [totalResult, rowsResult] = await Promise.all([
        pool.query<{ count: string }>(
          'SELECT COUNT(*) AS count FROM chat_conversation WHERE user_id = $1',
          [DEFAULT_USER_ID],
        ),
        pool.query(
          `SELECT c.id AS conversation_id, c.platform, c.account_key, c.external_key,
                  c.display_name, c.conversation_type, c.last_seen_at,
                  COALESCE(r.relationship_type, 'unknown') AS relationship_type,
                  r.alias, COALESCE(r.intimacy_level, 50) AS intimacy_level,
                  COALESCE(r.humor_level, 50) AS humor_level, r.notes,
                  r.updated_at, COUNT(m.id) AS message_count,
                  MAX(m.captured_at) AS last_message_at
           FROM chat_conversation c
           LEFT JOIN relationship_profile r
             ON r.user_id = c.user_id AND r.conversation_id = c.id
           LEFT JOIN chat_message m
             ON m.user_id = c.user_id AND m.conversation_id = c.id
           WHERE c.user_id = $1
           GROUP BY c.id, c.platform, c.account_key, c.external_key,
                    c.display_name, c.conversation_type, c.last_seen_at,
                    r.relationship_type, r.alias, r.intimacy_level,
                    r.humor_level, r.notes, r.updated_at
           ORDER BY c.last_seen_at DESC, c.id DESC
           LIMIT $2 OFFSET $3`,
          [DEFAULT_USER_ID, pageSize, offset],
        ),
      ]);
      res.json({
        total: Number(totalResult.rows[0]?.count ?? 0),
        page,
        page_size: pageSize,
        relationships: rowsResult.rows.map((row) => ({
          ...row,
          conversation_id: Number(row.conversation_id),
          intimacy_level: Number(row.intimacy_level),
          humor_level: Number(row.humor_level),
          message_count: Number(row.message_count),
          last_seen_at: iso(row.last_seen_at),
          last_message_at: iso(row.last_message_at),
          updated_at: iso(row.updated_at),
        })),
      });
    } catch (error) {
      next(error);
    }
  });

  router.put('/:conversation_id', async (req, res, next) => {
    try {
      const id = conversationId(req.params.conversation_id);
      if (id === null) return res.status(400).json({ error: 'conversation_id is invalid' });
      let input;
      try {
        input = validateRelationshipProfileInput(req.body);
      } catch (error) {
        return res.status(400).json({ error: validationMessage(error) });
      }
      const owned = await pool.query(
        'SELECT id FROM chat_conversation WHERE id = $1 AND user_id = $2',
        [id, DEFAULT_USER_ID],
      );
      if (owned.rowCount === 0) return res.status(404).json({ error: 'conversation not found' });

      const result = await pool.query(
        `INSERT INTO relationship_profile
          (user_id, conversation_id, relationship_type, alias,
           intimacy_level, humor_level, notes)
         VALUES ($1, $2, $3, $4, $5, $6, $7)
         ON CONFLICT (user_id, conversation_id) DO UPDATE SET
           relationship_type = EXCLUDED.relationship_type,
           alias = EXCLUDED.alias,
           intimacy_level = EXCLUDED.intimacy_level,
           humor_level = EXCLUDED.humor_level,
           notes = EXCLUDED.notes,
           updated_at = NOW()
         RETURNING conversation_id, relationship_type, alias,
                   intimacy_level, humor_level, notes, created_at, updated_at`,
        [
          DEFAULT_USER_ID,
          id,
          input.relationship_type,
          input.alias ?? null,
          input.intimacy_level,
          input.humor_level,
          input.notes ?? null,
        ],
      );
      const profile = result.rows[0];
      res.json({
        ok: true,
        profile: {
          ...profile,
          conversation_id: Number(profile.conversation_id),
          intimacy_level: Number(profile.intimacy_level),
          humor_level: Number(profile.humor_level),
          created_at: iso(profile.created_at),
          updated_at: iso(profile.updated_at),
        },
      });
    } catch (error) {
      next(error);
    }
  });

  router.get('/:conversation_id/candidates', async (req, res, next) => {
    try {
      const id = conversationId(req.params.conversation_id);
      if (id === null) return res.status(400).json({ error: 'conversation_id is invalid' });
      const conversation = await pool.query<RelationshipConversationIdentity>(
        `SELECT platform, account_key, external_key
         FROM chat_conversation
         WHERE id = $1 AND user_id = $2`,
        [id, DEFAULT_USER_ID],
      );
      if (conversation.rowCount === 0) {
        return res.status(404).json({ error: 'conversation not found' });
      }
      let query;
      try {
        query = validateRelationshipCandidateQuery({
          ...conversation.rows[0],
          context_text: String(req.query.context_text ?? ''),
          limit: req.query.limit === undefined ? undefined : Number(req.query.limit),
        });
      } catch (error) {
        return res.status(400).json({ error: validationMessage(error) });
      }
      const result = await getZeroTokenCandidates(
        pool,
        DEFAULT_USER_ID,
        query.identity,
        query.contextText,
        query.limit,
      );
      res.json(result);
    } catch (error) {
      next(error);
    }
  });

  return router;
}
