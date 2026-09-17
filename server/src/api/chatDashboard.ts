import { Router } from 'express';
import { unlink } from 'node:fs/promises';
import { resolve, sep } from 'node:path';
import type pg from 'pg';


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

  // 可选参数保留旧客户端行为；传入时必须严格选择一个平台。
  router.use(['/overview', '/conversations'], (req, res, next) => {
    const platform = req.query.platform;
    if (platform !== undefined && (typeof platform !== 'string' || !['wechat', 'qq', 'douyin'].includes(platform))) {
      res.status(400).json({ error: 'platform is invalid' });
      return;
    }
    next();
  });

  router.get('/overview', async (req, res, next) => {
    try {
      const platform = req.query.platform as string | undefined;
      const filter = platform === undefined ? '' : ' AND platform = $2';
      const params = platform === undefined ? [res.locals.userId] : [res.locals.userId, platform];
      const [conversations, messages, media] = await Promise.all([
        pool.query<{ count: string }>(
          `SELECT COUNT(*) AS count FROM chat_conversation WHERE user_id = $1${filter}`,
          params,
        ),
        pool.query<{ count: string }>(
          `SELECT COUNT(*) AS count FROM chat_message WHERE user_id = $1${filter}`,
          params,
        ),
        pool.query<{ count: string }>(
          platform === undefined
            ? 'SELECT COUNT(*) AS count FROM media_asset WHERE user_id = $1'
            : `SELECT COUNT(DISTINCT a.id) AS count FROM media_asset a
               JOIN chat_message_asset ma ON ma.asset_id = a.id
               JOIN chat_message m ON m.id = ma.message_id
               WHERE a.user_id = $1 AND m.user_id = $1 AND m.platform = $2`,
          params,
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
      const platform = req.query.platform as string | undefined;
      const filter = platform === undefined ? '' : ' AND platform = $2';
      const params = platform === undefined ? [res.locals.userId] : [res.locals.userId, platform];
      const [totalResult, rowsResult] = await Promise.all([
        pool.query<{ count: string }>(
          `SELECT COUNT(*) AS count FROM chat_conversation WHERE user_id = $1${filter}`,
          params,
        ),
        pool.query(
          `SELECT
             c.id, c.platform, c.account_key, c.external_key, c.display_name,
             c.conversation_type, c.identity_confidence, c.first_seen_at,
             c.last_seen_at, COUNT(m.id) AS message_count,
             MAX(m.captured_at) AS last_message_at
           FROM chat_conversation c
           LEFT JOIN chat_message m ON m.conversation_id = c.id AND m.user_id = c.user_id
           WHERE c.user_id = $1${platform === undefined ? '' : ' AND c.platform = $4'}
           GROUP BY c.id, c.platform, c.account_key, c.external_key, c.display_name,
                    c.conversation_type, c.identity_confidence, c.first_seen_at,
                    c.last_seen_at
           ORDER BY c.last_seen_at DESC, c.id DESC
           LIMIT $2 OFFSET $3`,
          platform === undefined ? [res.locals.userId, pageSize, offset] : [res.locals.userId, pageSize, offset, platform],
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
          [res.locals.userId, conversationId],
        ),
        pool.query(
          `SELECT id, platform, direction, message_type, sender_key, sender_name,
                  text, displayed_time, occurred_at, captured_at, sequence_hint,
                  metadata
           FROM chat_message
           WHERE user_id = $1 AND conversation_id = $2
           ORDER BY captured_at DESC, id DESC
           LIMIT $3 OFFSET $4`,
          [res.locals.userId, conversationId, pageSize, offset],
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

  router.delete('/conversations/:id', async (req, res, next) => {
    const conversationId = Number(req.params.id);
    if (!Number.isSafeInteger(conversationId) || conversationId <= 0) {
      return res.status(400).json({ error: 'conversation_id is invalid' });
    }
    const client = await pool.connect();
    const deletedPaths: string[] = [];
    try {
      await client.query('BEGIN');
      const conversation = await client.query(
        'SELECT id FROM chat_conversation WHERE id=$1 AND user_id=$2 FOR UPDATE',
        [conversationId, res.locals.userId],
      );
      if (conversation.rowCount === 0) {
        await client.query('ROLLBACK');
        return res.status(404).json({ error: 'conversation not found' });
      }
      const messageCount = await client.query<{ count: string }>(
        'SELECT COUNT(*) AS count FROM chat_message WHERE conversation_id=$1 AND user_id=$2',
        [conversationId, res.locals.userId],
      );
      const candidateAssets = await client.query<{ id: string | number }>(
        `SELECT DISTINCT a.id FROM media_asset a
         JOIN chat_message_asset ma ON ma.asset_id=a.id
         JOIN chat_message m ON m.id=ma.message_id
         WHERE m.conversation_id=$1 AND m.user_id=$2 AND a.user_id=$2`,
        [conversationId, res.locals.userId],
      );
      await client.query('DELETE FROM chat_conversation WHERE id=$1 AND user_id=$2', [conversationId, res.locals.userId]);
      for (const asset of candidateAssets.rows) {
        const removed = await client.query<{ storage_path: string }>(
          `DELETE FROM media_asset
           WHERE id=$1 AND user_id=$2
             AND NOT EXISTS (SELECT 1 FROM chat_message_asset WHERE asset_id=$1)
           RETURNING storage_path`,
          [asset.id, res.locals.userId],
        );
        if (removed.rows[0]) deletedPaths.push(removed.rows[0].storage_path);
      }
      await client.query('COMMIT');
      const uploadsRoot = resolve(process.cwd(), 'uploads');
      await Promise.all(deletedPaths.map(async (storagePath) => {
        const filePath = resolve(uploadsRoot, storagePath);
        if (!filePath.startsWith(`${uploadsRoot}${sep}`)) return;
        await unlink(filePath).catch(() => {});
      }));
      res.json({
        ok: true,
        deleted_messages: Number(messageCount.rows[0]?.count ?? 0),
        deleted_assets: deletedPaths.length,
      });
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {});
      next(error);
    } finally {
      client.release();
    }
  });

  router.delete('/messages/:messageId/assets/:assetId', async (req, res, next) => {
    const messageId = req.params.messageId;
    const assetId = Number(req.params.assetId);
    if (!messageId || !Number.isSafeInteger(assetId) || assetId <= 0) {
      return res.status(400).json({ error: 'message_id or asset_id is invalid' });
    }
    const client = await pool.connect();
    let deletedPath: string | null = null;
    try {
      await client.query('BEGIN');
      const association = await client.query(
        `SELECT ma.message_id FROM chat_message_asset ma
         JOIN chat_message m ON m.id=ma.message_id
         JOIN media_asset a ON a.id=ma.asset_id
         WHERE ma.message_id=$1 AND ma.asset_id=$2 AND m.user_id=$3 AND a.user_id=$3
         FOR UPDATE`,
        [messageId, assetId, res.locals.userId],
      );
      if (association.rowCount === 0) {
        await client.query('ROLLBACK');
        return res.status(404).json({ error: 'message image not found' });
      }
      await client.query(
        'DELETE FROM chat_message_asset WHERE message_id=$1 AND asset_id=$2',
        [messageId, assetId],
      );
      const remainingMessageAssets = await client.query(
        'SELECT 1 FROM chat_message_asset WHERE message_id=$1 LIMIT 1',
        [messageId],
      );
      const deletedMessage = remainingMessageAssets.rowCount === 0
        ? await client.query(
          `DELETE FROM chat_message
           WHERE id=$1 AND user_id=$2 AND message_type='image'
             AND (text IS NULL OR text='' OR text IN ('图片','截图'))
           RETURNING id`,
          [messageId, res.locals.userId],
        )
        : { rowCount: 0 };
      const remainingAssetReferences = await client.query(
        'SELECT 1 FROM chat_message_asset WHERE asset_id=$1 LIMIT 1',
        [assetId],
      );
      const deletedAsset = remainingAssetReferences.rowCount === 0
        ? await client.query<{ storage_path: string }>(
          `DELETE FROM media_asset
           WHERE id=$1 AND user_id=$2
           RETURNING storage_path`,
          [assetId, res.locals.userId],
        )
        : { rowCount: 0, rows: [] as Array<{ storage_path: string }> };
      deletedPath = deletedAsset.rows[0]?.storage_path ?? null;
      await client.query('COMMIT');
      if (deletedPath) {
        const uploadsRoot = resolve(process.cwd(), 'uploads');
        const filePath = resolve(uploadsRoot, deletedPath);
        if (filePath.startsWith(`${uploadsRoot}${sep}`)) await unlink(filePath).catch(() => {});
      }
      res.json({
        ok: true,
        deleted_asset: deletedAsset.rowCount === 1,
        deleted_message: deletedMessage.rowCount === 1,
      });
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {});
      next(error);
    } finally {
      client.release();
    }
  });

  return router;
}
