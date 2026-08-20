import { Router } from 'express';
import type pg from 'pg';

const DEFAULT_USER_ID = process.env.DEFAULT_USER_ID ?? '00000000-0000-0000-0000-000000000001';

export function createMobilePhraseRouter(pool: pg.Pool): Router {
  const router = Router();

  /** 常用语全量列表（输入法端同步用，按 sort_order 降序） */
  router.get('/phrases', async (req, res, next) => {
    try {
      const result = await pool.query(
        `SELECT id, content, sort_order
         FROM user_phrase
         WHERE user_id = $1
         ORDER BY sort_order DESC, id DESC`,
        [DEFAULT_USER_ID],
      );
      const phrases = (result.rows as Array<Record<string, unknown>>).map((r) => ({
        id: r.id,
        content: r.content,
      }));
      res.json({ total: phrases.length, phrases });
    } catch (err) {
      next(err);
    }
  });

  /** 输入法端本地新增常用语上报（按 content 幂等 upsert，返回服务端 id） */
  router.post('/phrases', async (req, res, next) => {
    try {
      const content = String((req.body as { content?: string })?.content ?? '').trim();
      if (!content || content.length > 500) {
        return res.status(400).json({ error: 'content required (<= 500 chars)' });
      }
      const result = await pool.query(
        `INSERT INTO user_phrase (user_id, content)
         VALUES ($1, $2)
         ON CONFLICT (content) DO UPDATE SET updated_at = NOW()
         RETURNING id, content, sort_order`,
        [DEFAULT_USER_ID, content],
      );
      const row = result.rows[0] as Record<string, unknown>;
      res.status(201).json({ id: row.id, content: row.content });
    } catch (err) {
      next(err);
    }
  });

  /** 常用语被使用（累加使用次数） */
  router.post('/phrases/use', async (req, res, next) => {
    try {
      const content = String((req.body as { content?: string })?.content ?? '').trim();
      if (!content) return res.status(400).json({ error: 'content required' });
      await pool.query(
        `UPDATE user_phrase SET use_count = use_count + 1 WHERE user_id = $1 AND content = $2`,
        [DEFAULT_USER_ID, content],
      );
      res.json({ ok: true });
    } catch (err) {
      next(err);
    }
  });

  return router;
}

export function createDashboardPhraseRouter(pool: pg.Pool): Router {
  const router = Router();

  /** 常用语管理列表（含使用次数/创建时间） */
  router.get('/user-phrases', async (req, res, next) => {
    try {
      const q = String(req.query.q ?? '').trim();
      const result = await pool.query(
        `SELECT id, content, sort_order, use_count, created_at
         FROM user_phrase
         WHERE user_id = $1 AND ($2 = '' OR content ILIKE '%' || $2 || '%')
         ORDER BY sort_order DESC, use_count DESC, id DESC`,
        [DEFAULT_USER_ID, q],
      );
      const phrases = (result.rows as Array<Record<string, unknown>>).map((r) => ({
        id: r.id,
        content: r.content,
        sortOrder: r.sort_order,
        useCount: r.use_count,
        createdAt: r.created_at,
      }));
      res.json({ total: phrases.length, phrases });
    } catch (err) {
      next(err);
    }
  });

  /** 新增常用语 */
  router.post('/user-phrases', async (req, res, next) => {
    try {
      const content = String((req.body as { content?: string })?.content ?? '').trim();
      if (!content || content.length > 500) {
        return res.status(400).json({ error: 'content required (<= 500 chars)' });
      }
      const result = await pool.query(
        `INSERT INTO user_phrase (user_id, content)
         VALUES ($1, $2)
         ON CONFLICT (content) DO UPDATE SET updated_at = NOW()
         RETURNING id, content, sort_order, use_count, created_at`,
        [DEFAULT_USER_ID, content],
      );
      const row = result.rows[0] as Record<string, unknown>;
      res.status(201).json({
        id: row.id,
        content: row.content,
        sortOrder: row.sort_order,
        useCount: row.use_count,
        createdAt: row.created_at,
      });
    } catch (err) {
      next(err);
    }
  });

  /** 修改常用语内容 */
  router.patch('/user-phrases/:id', async (req, res, next) => {
    try {
      const id = Number(req.params.id);
      const content = String((req.body as { content?: string })?.content ?? '').trim();
      if (!Number.isInteger(id) || id <= 0) return res.status(400).json({ error: 'invalid id' });
      if (!content || content.length > 500) {
        return res.status(400).json({ error: 'content required (<= 500 chars)' });
      }
      await pool.query(
        `UPDATE user_phrase SET content = $1, updated_at = NOW() WHERE id = $2 AND user_id = $3`,
        [content, id, DEFAULT_USER_ID],
      );
      res.json({ ok: true });
    } catch (err) {
      next(err);
    }
  });

  /** 删除常用语 */
  router.delete('/user-phrases/:id', async (req, res, next) => {
    try {
      const id = Number(req.params.id);
      if (!Number.isInteger(id) || id <= 0) return res.status(400).json({ error: 'invalid id' });
      const result = await pool.query(
        `DELETE FROM user_phrase WHERE id = $1 AND user_id = $2`,
        [id, DEFAULT_USER_ID],
      );
      if (result.rowCount === 0) return res.status(404).json({ error: 'not found' });
      res.json({ ok: true });
    } catch (err) {
      next(err);
    }
  });

  return router;
}
