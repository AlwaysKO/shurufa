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
  /** 当前页批量删除：统一模式、每行完整快照，一次事务全部成功或全部回滚。 */
  router.post('/events/delete-batch', async (req, res, next) => {
    const body = req.body;
    const userId = res.locals.userId;
    if (!validId(userId) || body?.confirm !== 'DELETE' || !['single', 'group'].includes(body?.mode)
      || !Array.isArray(body?.records) || body.records.length < 1 || body.records.length > 20) {
      res.status(400).json({ error: '批量删除参数无效，必须确认1至20行明确记录' });
      return;
    }
    const records: Array<{ id: string; ids: string[] }> = [];
    const expected = new Set<string>();
    for (const row of body.records) {
      if (!validId(row?.id) || !Array.isArray(row?.event_ids) || !row.event_ids.length
        || row.event_ids.length > 10000 || !row.event_ids.every(validId)
        || (body.mode === 'single' && row.event_ids.length !== 1)) {
        res.status(400).json({ error: '批量删除记录范围无效' });
        return;
      }
      const id = row.id.toLowerCase();
      const ids = (row.event_ids as string[]).map(value => value.toLowerCase());
      if (!ids.includes(id) || ids.some(value => expected.has(value)) || new Set(ids).size !== ids.length
        || expected.size + ids.length > 10000) {
        res.status(400).json({ error: '删除范围重复、不匹配或过大，请重新选择' });
        return;
      }
      ids.forEach(value => expected.add(value));
      records.push({ id, ids });
    }
    try {
      const client = await pool.connect();
      try {
        await client.query('BEGIN');
        // 一次按ID排序锁定所有实际成员，避免逐组加锁导致反向顺序死锁。
        // 分组键与列表/单行删除一致，查全组后才与用户确认的快照逐一比较。
        const selected = await client.query<{ id: string; selected_id: string }>(body.mode === 'group'
          ? `WITH targets AS (
               SELECT id AS selected_id, ${GROUP_KEY} AS edit_key
               FROM input_event WHERE user_id = $1 AND id = ANY($2::uuid[])
             )
             SELECT input_event.id, targets.selected_id FROM input_event
             JOIN targets ON ${GROUP_KEY} = targets.edit_key
             WHERE user_id = $1 ORDER BY input_event.id FOR UPDATE OF input_event`
          : `SELECT id, id AS selected_id FROM input_event
             WHERE user_id = $1 AND id = ANY($2::uuid[]) ORDER BY id FOR UPDATE`,
        [userId, records.map(row => row.id)]);
        const actual = new Map<string, Set<string>>();
        for (const row of selected.rows) {
          if (!actual.has(row.selected_id)) actual.set(row.selected_id, new Set());
          actual.get(row.selected_id)!.add(row.id);
        }
        if (records.some(row => !actual.get(row.id)?.has(row.id))) {
          await client.query('ROLLBACK');
          res.status(404).json({ error: '部分记录不存在或已删除，整批未删除，请刷新列表' });
          return;
        }
        if (records.some(row => actual.get(row.id)!.size !== row.ids.length
          || row.ids.some(id => !actual.get(row.id)!.has(id)))) {
          await client.query('ROLLBACK');
          res.status(409).json({ error: '记录已变化或范围不匹配，整批未删除，请刷新后重新确认' });
          return;
        }
        const deleted = await client.query(
          'DELETE FROM input_event WHERE user_id = $1 AND id = ANY($2::uuid[]) RETURNING id', [userId, [...expected]],
        );
        if (deleted.rowCount !== expected.size) throw new Error('Activity batch deletion count mismatch');
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
