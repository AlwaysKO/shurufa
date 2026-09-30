import { pendingConversation, conversationGroupName, chatPlatforms } from './chatPending.js';
import { Router } from 'express';
import { visibleChatMessage } from '../chat/chatMessageVisibility.js';
import type pg from 'pg';
import { recordManualScreenshotConfirmation, recoverPendingScreenshots } from '../chat/pendingScreenshotRecovery.js';

const validId = (value: unknown): number | null => {
  if (typeof value !== 'number' && (typeof value !== 'string' || !/^\d+$/.test(value))) return null;
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
};

export function createChatConversationsRouter(pool: pg.Pool): Router {
  const router = Router();
  router.post('/conversations/:id/confirm', async (req, res, next) => {
    const id = validId(req.params.id), name = typeof req.body?.display_name === 'string' ? req.body.display_name.trim() : '';
    if (!id || req.body?.confirm !== 'CONFIRM' || !chatPlatforms.includes(req.body?.platform)
      || !name || name.length > 200 || name.startsWith('待确认') || /[\u0000-\u001f\u007f]/.test(name)) {
      return res.status(400).json({ error: '请填写有效的联系人或群聊名称并确认（最多200字）' });
    }
    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      await client.query('LOCK TABLE chat_conversation IN SHARE ROW EXCLUSIVE MODE');
      const source = (await client.query(`SELECT c.*, (${pendingConversation()}) AS is_pending_source
        FROM chat_conversation c WHERE c.id=$1 AND c.user_id=$2 FOR UPDATE`, [id, res.locals.userId])).rows[0];
      if (!source) { await client.query('ROLLBACK'); return res.status(404).json({ error: '来源不存在' }); }
      if (source.platform !== req.body.platform || !source.is_pending_source || source.merged_into_id) {
        await client.query('ROLLBACK'); return res.status(409).json({ error: '来源已变化或已确认，请刷新后处理' });
      }
      await client.query(`UPDATE chat_conversation SET display_name=$3,identity_confidence=1,
        metadata=metadata || '{"manual_display_name":true}'::jsonb WHERE id=$1 AND user_id=$2`, [id, res.locals.userId, name]);
      await client.query('COMMIT');
      res.json({ ok: true, id });
    } catch (error) { await client.query('ROLLBACK').catch(() => {}); next(error); }
    finally { client.release(); }
  });
  router.get('/conversations/:id/resolve', async (req, res, next) => {
    const id = validId(req.params.id);
    if (!id) return res.status(400).json({ error: '会话标识无效' });
    try {
      const result = await pool.query(`SELECT c.*, ${conversationGroupName()} AS display_name, (${pendingConversation()}) AS is_pending_source, COUNT(m.id) AS message_count, MAX(m.captured_at) AS last_message_at
        FROM chat_conversation source
        JOIN chat_conversation c ON c.id=COALESCE(source.merged_into_id,source.id) AND c.user_id=source.user_id
        LEFT JOIN chat_message m ON m.conversation_id=c.id AND m.user_id=c.user_id AND ${visibleChatMessage()}
        WHERE source.id=$1 AND source.user_id=$2 GROUP BY c.id`, [id, res.locals.userId]);
      const row = result.rows[0];
      if (!row) return res.status(404).json({ error: '会话不存在' });
      res.json({ conversation: { ...row, id: Number(row.id), message_count: Number(row.message_count), identity_confidence: Number(row.identity_confidence) } });
    } catch (error) { next(error); }
  });
  router.post('/conversations/:id/merge', async (req, res, next) => {
    const sourceId = validId(req.params.id), targetId = validId(req.body?.target_id);
    if (!sourceId || !targetId || sourceId === targetId || req.body?.confirm !== 'MERGE') {
      return res.status(400).json({ error: '请选择不同的目标会话并确认合并' });
    }
    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      // 与上报及图片批量删除同序加锁，防合并提交前后还有消息落到隐藏源会话。
      await client.query('LOCK TABLE chat_conversation IN SHARE ROW EXCLUSIVE MODE');
      const source = (await client.query('SELECT * FROM chat_conversation WHERE id=$1 AND user_id=$2', [sourceId, res.locals.userId])).rows[0];
      let target = (await client.query('SELECT * FROM chat_conversation WHERE id=$1 AND user_id=$2', [targetId, res.locals.userId])).rows[0];
      if (target?.merged_into_id) target = (await client.query('SELECT * FROM chat_conversation WHERE id=$1 AND user_id=$2', [target.merged_into_id, res.locals.userId])).rows[0];
      if (!source || !target) { await client.query('ROLLBACK'); return res.status(404).json({ error: '会话不存在' }); }
      if (source.merged_into_id) { await client.query('ROLLBACK'); return res.status(409).json({ error: '源会话已经合并，请刷新后操作' }); }
      if (source.platform !== target.platform || Number(target.id) === sourceId) {
        await client.query('ROLLBACK'); return res.status(400).json({ error: '不能跨App或合并到自身' });
      }
      const moved = await client.query<{ id: string }>(`UPDATE chat_message SET conversation_id=$1
        WHERE user_id=$2 AND conversation_id IN (SELECT id FROM chat_conversation WHERE user_id=$2 AND (id=$3 OR merged_into_id=$3)) RETURNING id`, [target.id, res.locals.userId, sourceId]);
      const recoveryScopes = await recordManualScreenshotConfirmation(client, res.locals.userId, sourceId, Number(target.id), moved.rows.map(m => m.id));
      await client.query(`UPDATE chat_conversation SET merged_into_id=$1 WHERE user_id=$2 AND (id=$3 OR merged_into_id=$3)`, [target.id, res.locals.userId, sourceId]);
      await client.query(`UPDATE chat_conversation SET first_seen_at=LEAST(first_seen_at,$2), last_seen_at=GREATEST(last_seen_at,$3) WHERE id=$1`, [target.id, source.first_seen_at, source.last_seen_at]);
      for (const scope of recoveryScopes) await recoverPendingScreenshots(client, scope);
      await client.query('COMMIT');
      res.json({ ok: true, target_id: Number(target.id), moved_messages: moved.rowCount });
    } catch (error) { await client.query('ROLLBACK').catch(() => {}); next(error); }
    finally { client.release(); }
  });
  return router;
}
