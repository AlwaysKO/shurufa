import { pendingConversation, conversationGroupName } from './chatPending.js';
import { Router } from 'express';
import type pg from 'pg';

const validId = (value: unknown): number | null => {
  if (typeof value !== 'number' && (typeof value !== 'string' || !/^\d+$/.test(value))) return null;
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
};

export function createChatConversationsRouter(pool: pg.Pool): Router {
  const router = Router();
  router.get('/conversations/:id/resolve', async (req, res, next) => {
    const id = validId(req.params.id);
    if (!id) return res.status(400).json({ error: '会话标识无效' });
    try {
      const result = await pool.query(`SELECT c.*, ${conversationGroupName()} AS display_name, (${pendingConversation()}) AS is_pending_source, COUNT(m.id) AS message_count, MAX(m.captured_at) AS last_message_at
        FROM chat_conversation source
        JOIN chat_conversation c ON c.id=COALESCE(source.merged_into_id,source.id) AND c.user_id=source.user_id
        LEFT JOIN chat_message m ON m.conversation_id=c.id AND m.user_id=c.user_id
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
      const moved = await client.query(`UPDATE chat_message SET conversation_id=$1
        WHERE user_id=$2 AND conversation_id IN (SELECT id FROM chat_conversation WHERE user_id=$2 AND (id=$3 OR merged_into_id=$3))`, [target.id, res.locals.userId, sourceId]);
      await client.query(`UPDATE chat_conversation SET merged_into_id=$1 WHERE user_id=$2 AND (id=$3 OR merged_into_id=$3)`, [target.id, res.locals.userId, sourceId]);
      await client.query(`UPDATE chat_conversation SET first_seen_at=LEAST(first_seen_at,$2), last_seen_at=GREATEST(last_seen_at,$3) WHERE id=$1`, [target.id, source.first_seen_at, source.last_seen_at]);
      await client.query('COMMIT');
      res.json({ ok: true, target_id: Number(target.id), moved_messages: moved.rowCount });
    } catch (error) { await client.query('ROLLBACK').catch(() => {}); next(error); }
    finally { client.release(); }
  });
  return router;
}
