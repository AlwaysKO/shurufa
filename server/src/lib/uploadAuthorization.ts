import type { NextFunction, Request, Response } from 'express';
import type pg from 'pg';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function authorizeUpload(pool: pg.Pool) {
  return async (req: Request, res: Response, next: NextFunction): Promise<void> => {
    try {
      const userId = req.get('X-Device-Id') ?? req.query.user_id;
      if (typeof userId !== 'string' || !UUID_PATTERN.test(userId)) {
        res.status(400).json({ error: 'valid user_id or X-Device-Id required' });
        return;
      }

      const relativePath = req.path.replace(/^\//, '');
      const result = relativePath.startsWith('stickers/')
        ? await pool.query(
          'SELECT 1 FROM sticker WHERE user_id = $1 AND file_name = $2',
          [userId, relativePath.slice('stickers/'.length)],
        )
        : await pool.query(
          'SELECT 1 FROM media_asset WHERE user_id = $1 AND storage_path = $2',
          [userId, relativePath],
        );
      if (result.rowCount === 0) {
        res.status(404).json({ error: 'not found' });
        return;
      }
      next();
    } catch (error) {
      next(error);
    }
  };
}
