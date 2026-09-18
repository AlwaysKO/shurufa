import { pendingConversation, pendingScope, pendingMessageScope } from './chatPending.js';
import { Router } from 'express';
import type pg from 'pg';
import { cleanDeviceFiles, DeviceDeletionError, queueUploadFileCleanup } from '../lib/deleteDeviceData.js';

const validUuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
const positiveId = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value > 0;

export function createChatImagesRouter(pool: pg.Pool): Router {
  const router = Router();
  // 以明确的消息/附件关联为锚点，不受列表分页影响，不一次传输整个会话的图片。
  router.get('/images/adjacent', async (req, res, next) => {
    const conversationId = Number(req.query.conversation_id), assetId = Number(req.query.asset_id);
    const messageId = req.query.message_id, direction = req.query.direction;
    const grouped = pendingScope(conversationId, req.query.platform);
    if ((!positiveId(conversationId) && !grouped) || !positiveId(assetId) || !validUuid(messageId)
      || (direction !== 'next' && direction !== 'previous')) {
      res.status(400).json({ error: '图片浏览参数无效' }); return;
    }
    try {
      const result = await pool.query(`WITH images AS (
        SELECT m.id AS message_id, m.captured_at, m.text, a.id AS asset_id, a.storage_path, MIN(ma.position) AS position
        FROM chat_message m
        JOIN chat_conversation c ON c.id=m.conversation_id AND c.user_id=m.user_id
        JOIN chat_message_asset ma ON ma.message_id=m.id
        JOIN media_asset a ON a.id=ma.asset_id AND a.user_id=m.user_id
        WHERE m.user_id=$1 AND ${grouped ? pendingMessageScope('$1', '$2') : 'm.conversation_id=$2'} AND a.mime_type LIKE 'image/%'
        GROUP BY m.id, a.id
      ), ordered AS (
        SELECT *, ROW_NUMBER() OVER (ORDER BY captured_at DESC, message_id DESC, position ASC, asset_id ASC) AS ordinal,
          COUNT(*) OVER () AS total FROM images
      ), anchor AS (
        SELECT ordinal FROM ordered WHERE message_id=$3 AND asset_id=$4
      ) SELECT EXISTS(SELECT 1 FROM anchor) AS found,
        (SELECT row_to_json(candidate) FROM ordered candidate WHERE ordinal=(SELECT ordinal FROM anchor)+$5) AS image`,
      [res.locals.userId, grouped ? req.query.platform : conversationId, messageId, assetId, direction === 'next' ? 1 : -1]);
      const row = result.rows[0];
      if (!row.found) { res.status(404).json({ error: '当前图片不存在或已删除，请刷新列表' }); return; }
      const image = row.image;
      res.json({ image: image ? {
        message_id: image.message_id, asset_id: Number(image.asset_id), url: `/uploads/${image.storage_path}`,
        alt: image.text || '聊天图片', captured_at: image.captured_at,
        ordinal: Number(image.ordinal), total: Number(image.total),
      } : null });
    } catch (error) { next(error); }
  });

  router.post('/images/delete-batch', async (req, res, next) => {
    const body = req.body, userId = res.locals.userId;
    const grouped = pendingScope(body?.conversation_id, body?.platform);
    if (body?.confirm !== 'DELETE' || !validUuid(userId) || (!positiveId(body?.conversation_id) && !grouped)
      || !Array.isArray(body?.images) || !body.images.length || body.images.length > 1000
      || !body.images.every((image: any) => validUuid(image?.message_id) && positiveId(image?.asset_id))) {
      res.status(400).json({ error: '批量图片删除参数无效（每批最多1000张）' }); return;
    }
    const images: Array<{ message_id: string; asset_id: number }> = body.images.map((image: { message_id: string; asset_id: number }) => ({
      message_id: image.message_id.toLowerCase(), asset_id: image.asset_id,
    }));
    const expected = new Set(images.map(image => `${image.message_id}:${image.asset_id}`));
    if (expected.size !== images.length) { res.status(400).json({ error: '重复的图片选择，请重新选择' }); return; }
    try {
      const db = await pool.connect();
      let cleanupKey: string | null = null, deletedMessages = 0;
      try {
        await db.query('BEGIN');
        await db.query("SET LOCAL lock_timeout = '5s'");
        // 与上传的 media_asset 写锁及设备删除保持同一加锁顺序。
        // 否则同内容上传可能在删除提交前读到即将失效的旧附件ID。
        await db.query('LOCK TABLE chat_conversation, chat_message, chat_message_asset, media_asset IN SHARE ROW EXCLUSIVE MODE');
        const conversation = await db.query(`SELECT c.id FROM chat_conversation c WHERE c.user_id=$2 AND ${grouped ? `c.platform=$1 AND ${pendingConversation()}` : 'c.id=$1'} ORDER BY c.id FOR UPDATE`, [grouped ? body.platform : body.conversation_id, userId]);
        const conversationIds = conversation.rows.map(row => Number(row.id));
        if (!conversation.rowCount) {
          await db.query('ROLLBACK'); res.status(404).json({ error: '会话不存在，未删除任何图片' }); return;
        }
        const messageIds = [...new Set(images.map(image => image.message_id))];
        await db.query(`SELECT id FROM chat_message WHERE conversation_id=ANY($1::bigint[]) AND user_id=$2 AND id=ANY($3::uuid[]) ORDER BY id FOR UPDATE`, [conversationIds, userId, messageIds]);
        const links = await db.query<{ message_id: string; asset_id: string }>(`SELECT ma.message_id, ma.asset_id
          FROM chat_message_asset ma JOIN chat_message m ON m.id=ma.message_id
          JOIN media_asset a ON a.id=ma.asset_id
          JOIN jsonb_to_recordset($3::jsonb) AS selected(message_id uuid, asset_id bigint)
            ON selected.message_id=ma.message_id AND selected.asset_id=ma.asset_id
          WHERE m.conversation_id=ANY($1::bigint[]) AND m.user_id=$2 AND a.user_id=$2 AND a.mime_type LIKE 'image/%'
          ORDER BY ma.asset_id, ma.message_id, ma.role FOR UPDATE OF ma, a`, [conversationIds, userId, JSON.stringify(images)]);
        const actual = new Set(links.rows.map(row => `${row.message_id}:${row.asset_id}`));
        if (actual.size !== expected.size || [...expected].some(key => !actual.has(key))) {
          await db.query('ROLLBACK'); res.status(409).json({ error: '部分图片已变化或不属于当前会话，整批未删除，请刷新后重新选择' }); return;
        }
        const removed = await db.query(`DELETE FROM chat_message_asset ma USING jsonb_to_recordset($1::jsonb) AS selected(message_id uuid, asset_id bigint)
          WHERE ma.message_id=selected.message_id AND ma.asset_id=selected.asset_id`, [JSON.stringify(images)]);
        if (removed.rowCount !== links.rowCount) throw Error('Chat image deletion count mismatch');
        // 仅清理删空的纯图片占位消息；真实文字及任何未选择的附件都保留。
        const messages = await db.query(`DELETE FROM chat_message m WHERE m.user_id=$1 AND m.conversation_id=ANY($2::bigint[]) AND m.id=ANY($3::uuid[])
          AND m.message_type='image' AND COALESCE(m.text,'') IN ('','图片','截图')
          AND NOT EXISTS (SELECT 1 FROM chat_message_asset ma WHERE ma.message_id=m.id)`, [userId, conversationIds, messageIds]);
        deletedMessages = messages.rowCount ?? 0;
        const assets = await db.query<{ storage_path: string }>(`DELETE FROM media_asset a WHERE a.user_id=$1 AND a.id=ANY($2::bigint[])
          AND NOT EXISTS (SELECT 1 FROM chat_message_asset ma WHERE ma.asset_id=a.id) RETURNING storage_path`, [userId, [...new Set(images.map(image => image.asset_id))]]);
        cleanupKey = await queueUploadFileCleanup(db, assets.rows.map(row => row.storage_path));
        await db.query('COMMIT');
      } catch (error) { await db.query('ROLLBACK').catch(() => {}); throw error; }
      finally { db.release(); }
      // 文件只在提交后清理，并再次核对所有用户的共享路径；失败由现有后台任务重试。
      const filesPending = cleanupKey ? !await cleanDeviceFiles(pool, cleanupKey).catch(() => false) : false;
      res.json({ deleted_images: images.length, deleted_messages: deletedMessages, files_pending: filesPending });
    } catch (error) {
      if (error instanceof DeviceDeletionError) { res.status(error.status).json({ error: error.message }); return; }
      next(error);
    }
  });
  return router;
}
