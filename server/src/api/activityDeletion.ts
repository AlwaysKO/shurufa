import { Router } from 'express';
import type pg from 'pg';
import { GROUP_KEY } from './groupedEdits.js';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const validId = (value: unknown): value is string => typeof value === 'string' && UUID.test(value);

/** 仅删除用户明确确认的列表行；不复用按筛选条件批量清理的接口。 */
export function createActivityDeletionRouter(pool: pg.Pool): Router {
  const router = Router();
  router.post('/events/:id/delete', async (req, res, next) => {
    const body = req.body;
    const userId = res.locals.userId;
    if (!validId(userId) || !validId(req.params.id) || body?.confirm !== 'DELETE'
      || !['single', 'group'].includes(body?.mode) || !Array.isArray(body?.event_ids)
      || body.event_ids.length < 1 || body.event_ids.length > 10000 || !body.event_ids.every(validId)) {
      res.status(400).json({ error: '删除参数无效，必须明确确认记录范围' });
      return;
    }
    const id = req.params.id.toLowerCase();
    const ids = (body.event_ids as string[]).map(value => value.toLowerCase());
    const expected = new Set(ids);
    if (!expected.has(id) || expected.size !== ids.length || (body.mode === 'single' && ids.length !== 1)) {
      res.status(400).json({ error: '删除范围不符合所选记录，请刷新列表' });
      return;
    }
    try {
      const client = await pool.connect();
      try {
        await client.query('BEGIN');
        // 使用与整段列表完全相同的分组键，并在事务中锁住将被删除的原始行。
        const condition = body.mode === 'group'
          ? `${GROUP_KEY} = (SELECT ${GROUP_KEY} FROM input_event WHERE user_id = $1 AND id = $2)`
          : 'id = $2';
        const selected = await client.query<{ id: string }>(
          `SELECT id FROM input_event WHERE user_id = $1 AND ${condition} ORDER BY id FOR UPDATE`, [userId, id],
        );
        if (!selected.rows.some(row => row.id === id)) {
          await client.query('ROLLBACK');
          res.status(404).json({ error: '记录不存在或已删除，请刷新列表' });
          return;
        }
        // 旧列表、漏项、混入其他组/用户都拒绝；不得自动扩大用户确认的范围。
        if (selected.rows.length !== ids.length || selected.rows.some(row => !expected.has(row.id))) {
          await client.query('ROLLBACK');
          res.status(409).json({ error: '记录已变化或删除范围不匹配，请刷新后重新确认' });
          return;
        }
        const deleted = await client.query(
          'DELETE FROM input_event WHERE user_id = $1 AND id = ANY($2::uuid[]) RETURNING id', [userId, ids],
        );
        if (deleted.rowCount !== ids.length) throw new Error('Activity deletion count mismatch');
        await client.query('COMMIT');
        res.json({ deleted: deleted.rowCount });
      } catch (error) {
        await client.query('ROLLBACK');
        throw error;
      } finally { client.release(); }
    } catch (error) { next(error); }
  });
  return router;
}
