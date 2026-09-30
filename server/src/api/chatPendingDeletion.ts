import { Router } from 'express';
import type pg from 'pg';
import { chatPlatforms, pendingConversation } from './chatPending.js';
import { visibleChatMessage } from '../chat/chatMessageVisibility.js';
import { cleanDeviceFiles, queueUploadFileCleanup } from '../lib/deleteDeviceData.js';

export function createChatPendingDeletionRouter(pool: pg.Pool): Router {
  const router = Router();
  router.post('/pending/messages/delete-batch', async (req, res, next) => {
    const body = req.body, user = res.locals.userId;
    if (body?.confirm !== 'DELETE' || !chatPlatforms.includes(body?.platform) ||
        !Array.isArray(body?.messages) || !body.messages.length || body.messages.length > 1000 ||
        !body.messages.every((m: any) => typeof m?.message_id === 'string' &&
          /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(m.message_id) &&
          Number.isSafeInteger(m.conversation_id) && m.conversation_id > 0)) {
      res.status(400).json({ error: '请选择明确的待确认非图片记录（每批最多1000条）' }); return;
    }
    const targets = body.messages.map((m: { message_id: string; conversation_id: number }) => ({
      message_id: m.message_id.toLowerCase(), conversation_id: m.conversation_id,
    }));
    const ids: string[] = targets.map((m: { message_id: string }) => m.message_id);
    if (new Set(ids).size !== ids.length) {
      res.status(400).json({ error: '重复的记录选择，请重新选择' }); return;
    }
    try {
      const db = await pool.connect();
      let cleanupKey: string | null = null;
      try {
        await db.query('BEGIN');
        await db.query("SET LOCAL lock_timeout = '5s'");
        await db.query('LOCK TABLE chat_conversation, chat_message, chat_message_asset, media_asset IN SHARE ROW EXCLUSIVE MODE');
        const matched = await db.query(`SELECT m.id FROM chat_message m
          JOIN chat_conversation c ON c.id=m.conversation_id AND c.user_id=m.user_id
          JOIN jsonb_to_recordset($3::jsonb) AS chosen(message_id uuid,conversation_id bigint)
            ON chosen.message_id=m.id AND chosen.conversation_id=m.conversation_id
          WHERE m.user_id=$1 AND c.platform=$2 AND m.platform=$2 AND (${pendingConversation()})
            AND ${visibleChatMessage()} AND m.message_type<>'image'
            AND NOT EXISTS (SELECT 1 FROM chat_message_asset ma JOIN media_asset a ON a.id=ma.asset_id
              WHERE ma.message_id=m.id AND a.mime_type LIKE 'image/%')`, [user, body.platform, JSON.stringify(targets)]);
        if (matched.rowCount !== ids.length) {
          await db.query('ROLLBACK');
          res.status(409).json({ error: '部分记录已变化、已确认或包含图片，整批未删除，请刷新后重新选择' }); return;
        }
        const assets = await db.query(`SELECT DISTINCT a.id FROM media_asset a
          JOIN chat_message_asset ma ON ma.asset_id=a.id
          WHERE ma.message_id=ANY($2::uuid[]) AND a.user_id=$1`, [user, ids]);
        const deleted = await db.query('DELETE FROM chat_message WHERE user_id=$1 AND id=ANY($2::uuid[]) RETURNING id', [user, ids]);
        if (deleted.rowCount !== ids.length) throw Error('非图片记录删除数量不一致');
        const removed = await db.query<{ storage_path: string }>(`DELETE FROM media_asset a
          WHERE a.user_id=$1 AND a.id=ANY($2::bigint[])
            AND NOT EXISTS(SELECT 1 FROM chat_message_asset ma WHERE ma.asset_id=a.id) RETURNING storage_path`,
          [user, assets.rows.map(a => a.id)]);
        cleanupKey = await queueUploadFileCleanup(db, removed.rows.map(a => a.storage_path));
        await db.query('COMMIT');
      } catch (error) { await db.query('ROLLBACK').catch(() => {}); throw error; }
      finally { db.release(); }
      const filesPending = cleanupKey ? !await cleanDeviceFiles(pool, cleanupKey).catch(() => false) : false;
      res.json({ deleted_messages: ids.length, files_pending: filesPending });
    } catch (error) { next(error); }
  });
  return router;
}
