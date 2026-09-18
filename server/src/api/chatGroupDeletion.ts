import { Router } from 'express';
import type pg from 'pg';
import { chatConversationScope } from './chatPending.js';
import { cleanDeviceFiles, DeviceDeletionError, queueUploadFileCleanup } from '../lib/deleteDeviceData.js';

/** 只删除用户确认时看到的明确来源集合；不把代表ID当整个组，也不扩大到确认后的新来源。 */
export function createChatGroupDeletionRouter(pool: pg.Pool): Router {
  const router = Router();
  router.post('/conversation-groups/delete', async (req, res, next) => {
    const body = req.body, ids = body?.source_ids, userId = res.locals.userId;
    const scope = chatConversationScope(userId, 1, body?.platform, body?.group_name);
    if (body?.confirm !== 'DELETE' || scope?.mode !== 'name' || !Array.isArray(ids) || !ids.length || ids.length > 1000 ||
        !ids.every(id => Number.isSafeInteger(id) && id > 0) || new Set(ids).size !== ids.length) {
      res.status(400).json({ error: '请确认明确的同名会话来源（最多1000个）' }); return;
    }
    let cleanupKey: string | null = null, deletedMessages = 0, deletedAssets = 0;
    try {
      const db = await pool.connect();
      try {
        await db.query('BEGIN');
        await db.query("SET LOCAL lock_timeout = '5s'");
        await db.query('LOCK TABLE chat_conversation, chat_message, chat_message_asset, media_asset IN SHARE ROW EXCLUSIVE MODE');
        const sources = await db.query(`SELECT c.id FROM chat_conversation c WHERE ${scope.sql} ORDER BY c.id FOR UPDATE`, scope.params);
        const actual = new Set(sources.rows.map(row => Number(row.id)));
        if (actual.size !== ids.length || !ids.every(id => actual.has(id))) {
          await db.query('ROLLBACK'); res.status(409).json({ error: '会话来源已变化，整组未删除，请刷新后重新确认' }); return;
        }
        deletedMessages = Number((await db.query('SELECT COUNT(*) AS count FROM chat_message WHERE user_id=$1 AND conversation_id=ANY($2::bigint[])', [userId, ids])).rows[0].count);
        const assets = await db.query(`SELECT DISTINCT a.id FROM media_asset a JOIN chat_message_asset ma ON ma.asset_id=a.id
          JOIN chat_message m ON m.id=ma.message_id WHERE m.user_id=$1 AND a.user_id=$1 AND m.conversation_id=ANY($2::bigint[])`, [userId, ids]);
        const deleted = await db.query('DELETE FROM chat_conversation WHERE user_id=$1 AND id=ANY($2::bigint[]) RETURNING id', [userId, ids]);
        if (deleted.rowCount !== ids.length) throw Error('会话删除数量不一致');
        const removed = await db.query<{ storage_path: string }>(`DELETE FROM media_asset a WHERE a.user_id=$1 AND a.id=ANY($2::bigint[])
          AND NOT EXISTS(SELECT 1 FROM chat_message_asset ma WHERE ma.asset_id=a.id) RETURNING storage_path`, [userId, assets.rows.map(row => row.id)]);
        deletedAssets = removed.rowCount ?? 0;
        cleanupKey = await queueUploadFileCleanup(db, removed.rows.map(row => row.storage_path));
        await db.query('COMMIT');
      } catch (error) { await db.query('ROLLBACK').catch(() => {}); throw error; }
      finally { db.release(); }
      const filesPending = cleanupKey ? !await cleanDeviceFiles(pool, cleanupKey).catch(() => false) : false;
      res.json({ ok: true, deleted_sources: ids.length, deleted_messages: deletedMessages, deleted_assets: deletedAssets, files_pending: filesPending });
    } catch (error) {
      if (error instanceof DeviceDeletionError) { res.status(error.status).json({ error: error.message }); return; }
      next(error);
    }
  });
  return router;
}
