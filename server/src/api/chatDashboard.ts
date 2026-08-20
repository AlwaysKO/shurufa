import { Router } from 'express';
import type pg from 'pg';

const DEFAULT_USER_ID = process.env.DEFAULT_USER_ID ?? '00000000-0000-0000-0000-000000000001';

function pagination(query: Record<string, unknown>): { page: number; pageSize: number; offset: number } {
  const page = Math.max(1, Math.trunc(Number(query.page) || 1));
  const pageSize = Math.min(100, Math.max(1, Math.trunc(Number(query.page_size) || 20)));
  return { page, pageSize, offset: (page - 1) * pageSize };
}

function iso(value: unknown): unknown {
  return value instanceof Date ? value.toISOString() : value;
}

export function createChatDashboardRouter(pool: pg.Pool): Router {
  const router = Router();

  router.get('/overview', async (_req, res, next) => {
    try {
      const [conversations, messages, media] = await Promise.all([
        pool.query<{ count: string }>(
          'SELECT COUNT(*) AS count FROM chat_conversation WHERE user_id = $1',
          [DEFAULT_USER_ID],
        ),
        pool.query<{ count: string }>(
          'SELECT COUNT(*) AS count FROM chat_message WHERE user_id = $1',
          [DEFAULT_USER_ID],
        ),
        pool.query<{ count: string }>(
          'SELECT COUNT(*) AS count FROM media_asset WHERE user_id = $1',
          [DEFAULT_USER_ID],
        ),
      ]);
      res.json({
        conversation_count: Number(conversations.rows[0]?.count ?? 0),
        message_count: Number(messages.rows[0]?.count ?? 0),
        media_count: Number(media.rows[0]?.count ?? 0),
      });
    } catch (error) {
      next(error);
    }
  });

  router.get('/conversations', async (req, res, next) => {
    try {
      const { page, pageSize, offset } = pagination(req.query as Record<string, unknown>);
      const [totalResult, rowsResult] = await Promise.all([
        pool.query<{ count: string }>(
          'SELECT COUNT(*) AS count FROM chat_conversation WHERE user_id = $1',
          [DEFAULT_USER_ID],
        ),
        pool.query(
          `SELECT
             c.id, c.platform, c.account_key, c.external_key, c.display_name,
             c.conversation_type, c.identity_confidence, c.first_seen_at,
             c.last_seen_at, COUNT(m.id) AS message_count,
             MAX(m.captured_at) AS last_message_at
           FROM chat_conversation c
           LEFT JOIN chat_message m ON m.conversation_id = c.id
           WHERE c.user_id = $1
           GROUP BY c.id, c.platform, c.account_key, c.external_key, c.display_name,
                    c.conversation_type, c.identity_confidence, c.first_seen_at,
                    c.last_seen_at
           ORDER BY c.last_seen_at DESC, c.id DESC
           LIMIT $2 OFFSET $3`,
          [DEFAULT_USER_ID, pageSize, offset],
        ),
      ]);
      res.json({
        total: Number(totalResult.rows[0]?.count ?? 0),
        page,
        page_size: pageSize,
        conversations: rowsResult.rows.map((row) => ({
          ...row,
          id: Number(row.id),
          identity_confidence: Number(row.identity_confidence),
          message_count: Number(row.message_count),
          first_seen_at: iso(row.first_seen_at),
          last_seen_at: iso(row.last_seen_at),
          last_message_at: iso(row.last_message_at),
        })),
      });
    } catch (error) {
      next(error);
    }
  });

  router.get('/messages', async (req, res, next) => {
    try {
      const conversationId = Number(req.query.conversation_id);
      if (!Number.isSafeInteger(conversationId) || conversationId <= 0) {
        return res.status(400).json({ error: 'conversation_id is invalid' });
      }
      const { page, pageSize, offset } = pagination(req.query as Record<string, unknown>);
      const [totalResult, rowsResult] = await Promise.all([
        pool.query<{ count: string }>(
          `SELECT COUNT(*) AS count FROM chat_message
           WHERE user_id = $1 AND conversation_id = $2`,
          [DEFAULT_USER_ID, conversationId],
        ),
        pool.query(
          `SELECT id, platform, direction, message_type, sender_key, sender_name,
                  text, displayed_time, occurred_at, captured_at, sequence_hint,
                  metadata
           FROM chat_message
           WHERE user_id = $1 AND conversation_id = $2
           ORDER BY captured_at DESC, id DESC
           LIMIT $3 OFFSET $4`,
          [DEFAULT_USER_ID, conversationId, pageSize, offset],
        ),
      ]);

      const messageIds = rowsResult.rows.map((row) => String(row.id));
      const assetsByMessage = new Map<string, unknown[]>();
      if (messageIds.length > 0) {
        const placeholders = messageIds.map((_, index) => `$${index + 1}`).join(', ');
        const assets = await pool.query(
          `SELECT ma.message_id, ma.role, ma.position, a.id, a.sha256,
                  a.mime_type, a.storage_path, a.width, a.height
           FROM chat_message_asset ma
           JOIN media_asset a ON a.id = ma.asset_id
           WHERE ma.message_id IN (${placeholders})
           ORDER BY ma.position ASC`,
          messageIds,
        );
        for (const asset of assets.rows) {
          const messageId = String(asset.message_id);
          const items = assetsByMessage.get(messageId) ?? [];
          items.push({
            id: Number(asset.id),
            sha256: asset.sha256,
            mime_type: asset.mime_type,
            width: asset.width,
            height: asset.height,
            role: asset.role,
            position: asset.position,
            url: `/uploads/${asset.storage_path}`,
          });
          assetsByMessage.set(messageId, items);
        }
      }

      res.json({
        total: Number(totalResult.rows[0]?.count ?? 0),
        page,
        page_size: pageSize,
        messages: rowsResult.rows.map((row) => ({
          ...row,
          occurred_at: iso(row.occurred_at),
          captured_at: iso(row.captured_at),
          sequence_hint: row.sequence_hint === null ? null : Number(row.sequence_hint),
          assets: assetsByMessage.get(String(row.id)) ?? [],
        })),
      });
    } catch (error) {
      next(error);
    }
  });

  return router;
}
