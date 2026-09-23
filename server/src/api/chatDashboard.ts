import { createChatGroupDeletionRouter } from './chatGroupDeletion.js';
import { createChatPendingRouter, chatConversationScope } from './chatPending.js';
import { createChatConversationsRouter } from './chatConversations.js';
import { createChatImagesRouter } from './chatImages.js';
import { Router } from 'express';
import { unlink } from 'node:fs/promises';
import { resolve, sep } from 'node:path';
import type pg from 'pg';
import { visibleChatMessage } from '../chat/chatMessageVisibility.js';
import { expandScreenshotDeletion, tombstoneDeletedScreenshots } from '../chat/screenshotDeletion.js';
import { pendingMessageDiagnostics } from './chatPendingDiagnostics.js';


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
  router.use(createChatPendingRouter(pool));
  router.use(createChatGroupDeletionRouter(pool));
  router.use(createChatImagesRouter(pool));
  router.use(createChatConversationsRouter(pool));

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
          `SELECT COUNT(*) AS count FROM chat_conversation WHERE user_id = $1 AND merged_into_id IS NULL${filter}`,
          params,
        ),
        pool.query<{ count: string }>(
          `SELECT COUNT(*) AS count FROM chat_message m WHERE user_id = $1${filter} AND ${visibleChatMessage()}`,
          params,
        ),
        pool.query<{ count: string }>(
          platform === undefined
            ? 'SELECT COUNT(*) AS count FROM media_asset WHERE user_id = $1'
            : `SELECT COUNT(DISTINCT a.id) AS count FROM media_asset a
               JOIN chat_message_asset ma ON ma.asset_id = a.id
               JOIN chat_message m ON m.id = ma.message_id
               WHERE a.user_id = $1 AND m.user_id = $1 AND m.platform = $2 AND ${visibleChatMessage()}`,
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
      const search = typeof req.query.q === 'string' ? req.query.q.trim().slice(0,100) : '';
      const searchParam = `%${search.replace(/[\\%_]/g, '\\$&')}%`;
      if (search) params.push(searchParam);
      const searchFilter = search ? ` AND COALESCE(display_name,external_key) ILIKE $${params.length}` : '';
      const [totalResult, rowsResult] = await Promise.all([
        pool.query<{ count: string }>(
          `SELECT COUNT(*) AS count FROM chat_conversation WHERE user_id = $1 AND merged_into_id IS NULL${filter}${searchFilter}`,
          params,
        ),
        pool.query(
          `SELECT
             c.id, c.platform, c.account_key, c.external_key, c.display_name,
             c.conversation_type, c.identity_confidence, c.first_seen_at,
             c.last_seen_at, COUNT(m.id) AS message_count,
             MAX(m.captured_at) AS last_message_at
           FROM chat_conversation c
           LEFT JOIN chat_message m ON m.conversation_id = c.id AND m.user_id = c.user_id AND ${visibleChatMessage()}
           WHERE c.user_id = $1 AND c.merged_into_id IS NULL${platform === undefined ? '' : ' AND c.platform = $4'}${search ? ` AND COALESCE(c.display_name,c.external_key) ILIKE $${platform === undefined ? 4 : 5}` : ''}
           GROUP BY c.id, c.platform, c.account_key, c.external_key, c.display_name,
                    c.conversation_type, c.identity_confidence, c.first_seen_at,
                    c.last_seen_at
           ORDER BY c.last_seen_at DESC, c.id DESC
           LIMIT $2 OFFSET $3`,
          [res.locals.userId, pageSize, offset, ...(platform === undefined ? [] : [platform]), ...(search ? [searchParam] : [])],
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
      const scope = chatConversationScope(res.locals.userId, conversationId, req.query.platform, req.query.group_name);
      if (!scope) return res.status(400).json({ error: '会话范围无效' });
      const scopeFilter = `conversation_id IN (SELECT c.id FROM chat_conversation c WHERE ${scope.sql})`;
      const { page, pageSize, offset } = pagination(req.query as Record<string, unknown>);
      const gallery = req.query.gallery === 'true';
      // 一条消息可能有多张图；先展开图片关联再分页。纯文字/非图片消息保留一项，不丢内容。
      const prefix = gallery ? `WITH gallery_rows AS (
        SELECT m.*, pictures.asset_id AS gallery_asset_id, pictures.position AS gallery_position
        FROM chat_message m JOIN chat_conversation c ON c.id=m.conversation_id AND c.user_id=m.user_id
        LEFT JOIN LATERAL (
          SELECT a.id AS asset_id, MIN(ma.position) AS position FROM chat_message_asset ma
          JOIN media_asset a ON a.id=ma.asset_id AND a.user_id=m.user_id
          WHERE ma.message_id=m.id AND a.mime_type LIKE 'image/%' GROUP BY a.id
          UNION ALL SELECT NULL::bigint, NULL::integer WHERE EXISTS (
            SELECT 1 FROM chat_message_asset ma JOIN media_asset a ON a.id=ma.asset_id AND a.user_id=m.user_id
            WHERE ma.message_id=m.id AND a.mime_type NOT LIKE 'image/%'
          )
        ) pictures ON true WHERE ${scope.sql} AND ${visibleChatMessage()}
      ) ` : '';
      const from = gallery ? 'gallery_rows' : 'chat_message';
      const visible = visibleChatMessage(from);
      const [totalResult, rowsResult] = await Promise.all([
        pool.query<{ count: string }>(`${prefix}SELECT COUNT(*) AS count FROM ${from}
          WHERE user_id=$1 AND ${scopeFilter} AND ${visible}`, scope.params),
        pool.query(`${prefix}SELECT id, conversation_id, platform, direction, message_type, sender_key, sender_name,
          text, displayed_time, occurred_at, captured_at, sequence_hint, metadata${gallery ? ',gallery_asset_id' : ''}
          FROM ${from} WHERE user_id=$1 AND ${scopeFilter} AND ${visible} ORDER BY captured_at DESC,id DESC
          ${gallery ? ',gallery_position ASC,gallery_asset_id ASC' : ''}
          LIMIT $${scope.params.length+1} OFFSET $${scope.params.length+2}`, [...scope.params,pageSize,offset]),
      ]);

      const messageIds = [...new Set(rowsResult.rows.map((row) => String(row.id)))];
      const assetsByMessage = new Map<string, Array<{ id: number; mime_type: string; [key: string]: unknown }>>();
      if (messageIds.length > 0) {
        const placeholders = messageIds.map((_, index) => `$${index + 1}`).join(', ');
        const assets = await pool.query(
          `SELECT ma.message_id, ma.role, ma.position, a.id, a.sha256,
                  a.mime_type, a.storage_path, a.width, a.height
           FROM chat_message_asset ma
           JOIN media_asset a ON a.id = ma.asset_id
           WHERE ma.message_id IN (${placeholders}) AND a.user_id=$${messageIds.length+1}
           ORDER BY ma.position ASC`,
          [...messageIds, res.locals.userId],
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

      const diagnostics = scope.mode === 'pending'
        ? await pendingMessageDiagnostics(pool, res.locals.userId, rowsResult.rows) : new Map();
      res.json({
        total: Number(totalResult.rows[0]?.count ?? 0),
        page,
        page_size: pageSize,
        messages: rowsResult.rows.map((row) => ({
          ...row,
          ...(diagnostics.has(String(row.id)) ? { pending_diagnostic: diagnostics.get(String(row.id)) } : {}),
          conversation_id: Number(row.conversation_id),
          occurred_at: iso(row.occurred_at),
          captured_at: iso(row.captured_at),
          sequence_hint: row.sequence_hint === null ? null : Number(row.sequence_hint),
          assets: (assetsByMessage.get(String(row.id)) ?? []).filter((asset, index, all) => !gallery || (
            (row.gallery_asset_id == null ? !asset.mime_type.startsWith('image/') : asset.id === Number(row.gallery_asset_id)) &&
            all.findIndex(item => item.id === asset.id) === index
          )),
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
      await client.query('LOCK TABLE chat_conversation IN SHARE ROW EXCLUSIVE MODE');
      const conversation = await client.query(
        'SELECT id, merged_into_id FROM chat_conversation WHERE id=$1 AND user_id=$2 FOR UPDATE',
        [conversationId, res.locals.userId],
      );
      if (conversation.rowCount === 0) {
        await client.query('ROLLBACK');
        return res.status(404).json({ error: 'conversation not found' });
      }
      if (conversation.rows[0].merged_into_id) {
        await client.query('ROLLBACK');
        return res.status(409).json({ error: '该会话已合并，请刷新后操作目标会话' });
      }
      const messageCount = await client.query<{ count: string }>(
        `SELECT COUNT(*) AS count FROM chat_message m WHERE conversation_id=$1 AND user_id=$2 AND ${visibleChatMessage()}`,
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
    const messageId = req.params.messageId.toLowerCase();
    const assetId = Number(req.params.assetId);
    if (!messageId || !Number.isSafeInteger(assetId) || assetId <= 0) {
      return res.status(400).json({ error: 'message_id or asset_id is invalid' });
    }
    const client = await pool.connect();
    let deletedPath: string | null = null;
    try {
      await client.query('BEGIN');
      await client.query('LOCK TABLE chat_conversation, chat_message, chat_message_asset, media_asset IN SHARE ROW EXCLUSIVE MODE');
      const association = await client.query(
        `SELECT ma.message_id FROM chat_message_asset ma
         JOIN chat_message m ON m.id=ma.message_id
         JOIN media_asset a ON a.id=ma.asset_id
         WHERE ma.message_id=$1 AND ma.asset_id=$2 AND m.user_id=$3 AND a.user_id=$3 AND ${visibleChatMessage()}
         FOR UPDATE`,
        [messageId, assetId, res.locals.userId],
      );
      if (association.rowCount === 0) {
        await client.query('ROLLBACK');
        return res.status(404).json({ error: 'message image not found' });
      }
      const expanded = await expandScreenshotDeletion(client, res.locals.userId, [{ message_id: messageId, asset_id: assetId }]);
      const deletionIds = [...new Set(expanded.images.map(image => image.message_id))];
      await client.query(`DELETE FROM chat_message_asset WHERE message_id IN (${deletionIds.map((_, i) => `$${i + 1}`).join(',')}) AND asset_id=$${deletionIds.length + 1}`, [...deletionIds, assetId]);
      await tombstoneDeletedScreenshots(client, res.locals.userId, expanded.receiptIds);
      const remainingMessageAssets = await client.query(
        'SELECT 1 FROM chat_message_asset WHERE message_id=$1 LIMIT 1',
        [messageId],
      );
      const deletedMessage = remainingMessageAssets.rowCount === 0 && !expanded.receiptIds.includes(messageId)
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
