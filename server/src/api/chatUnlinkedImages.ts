import { Router } from 'express';
import type pg from 'pg';

function dateBoundary(value: unknown, end: boolean): string | undefined {
  if (value === undefined || value === '') return undefined;
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) throw Error('接收日期格式无效');
  const time = Date.parse(`${value}T00:00:00+08:00`);
  if (!Number.isFinite(time) || new Date(time + 28_800_000).toISOString().slice(0, 10) !== value) throw Error('接收日期无效');
  return new Date(time + (end ? 86_400_000 : 0)).toISOString();
}

export function createChatUnlinkedImagesRouter(pool: pg.Pool): Router {
  const router = Router();
  router.get('/unlinked-images', async (req, res, next) => {
    const page = Number(req.query.page ?? 1), pageSize = Number(req.query.page_size ?? 24);
    if (!Number.isSafeInteger(page) || page < 1 || page > 100_000 || !Number.isSafeInteger(pageSize) || pageSize < 1 || pageSize > 60) {
      res.status(400).json({ error: '分页参数无效' }); return;
    }
    let from: string | undefined, to: string | undefined;
    try {
      from = dateBoundary(req.query.from, false); to = dateBoundary(req.query.to, true);
      if (from && to && from >= to) throw Error('开始日期不能晚于结束日期');
    } catch (error) { res.status(400).json({ error: (error as Error).message }); return; }
    const params: unknown[] = [res.locals.userId];
    const conditions = ["a.user_id=$1", "a.mime_type IN ('image/png','image/webp')",
      'NOT EXISTS (SELECT 1 FROM chat_message_asset ma WHERE ma.asset_id=a.id)'];
    if (from) { params.push(from); conditions.push(`a.created_at >= $${params.length}::timestamptz`); }
    if (to) { params.push(to); conditions.push(`a.created_at < $${params.length}::timestamptz`); }
    try {
      const where = conditions.join(' AND ');
      const [count, rows] = await Promise.all([
        pool.query(`SELECT COUNT(*) AS total FROM media_asset a WHERE ${where}`, params),
        pool.query(`SELECT a.id,a.storage_path,a.byte_size,a.width,a.height,a.created_at
          FROM media_asset a WHERE ${where} ORDER BY a.created_at DESC,a.id DESC
          LIMIT $${params.length + 1} OFFSET $${params.length + 2}`, [...params, pageSize, (page - 1) * pageSize]),
      ]);
      res.set('Cache-Control', 'no-store').json({ total: Number(count.rows[0].total), page, page_size: pageSize,
        images: rows.rows.map(row => ({ id: String(row.id), url: `/uploads/${row.storage_path}`,
          byte_size: Number(row.byte_size), width: row.width, height: row.height,
          received_at: row.created_at instanceof Date ? row.created_at.toISOString() : row.created_at })) });
    } catch (error) { next(error); }
  });
  return router;
}
