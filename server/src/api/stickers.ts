import { Router } from 'express';
import type express from 'express';
import type pg from 'pg';
import { mkdirSync, writeFileSync, unlinkSync, existsSync } from 'node:fs';
import { join, extname } from 'node:path';
import { randomUUID } from 'node:crypto';

const DEFAULT_USER_ID = process.env.DEFAULT_USER_ID ?? '00000000-0000-0000-0000-000000000001';

/** 表情包文件存储目录（server/uploads/stickers），由 app.ts 挂载为 /uploads 静态路径 */
const STICKER_DIR = join(process.cwd(), 'uploads', 'stickers');

const ALLOWED_FORMAT: Record<string, string> = {
  '.gif': 'gif',
  '.png': 'png',
  '.jpg': 'jpg',
  '.jpeg': 'jpg',
  '.webp': 'webp',
};

/** 完整可访问的图片 URL（相对路径，输入法端拼接 baseUrl） */
function stickerUrl(fileName: string): string {
  return `/uploads/stickers/${fileName}`;
}

export function createMobileStickerRouter(pool: pg.Pool): Router {
  const router = Router();

  /** 关键词搜索表情包（关键词字段 ILIKE 匹配，按使用次数排序） */
  router.get('/stickers', async (req, res, next) => {
    try {
      const q = String(req.query.q ?? '').trim();
      const limit = Math.min(Number(req.query.limit ?? 60), 200);
      const result = await pool.query(
        `SELECT id, file_name, format, width, height, use_count
         FROM sticker
         WHERE user_id = $1 AND ($2 = '' OR keywords ILIKE '%' || $2 || '%')
         ORDER BY use_count DESC, id DESC
         LIMIT $3`,
        [DEFAULT_USER_ID, q, limit],
      );
      const stickers = (result.rows as Array<Record<string, unknown>>).map((r) => ({
        id: r.id,
        url: stickerUrl(r.file_name as string),
        format: r.format,
        width: r.width,
        height: r.height,
      }));
      res.json({ total: stickers.length, stickers });
    } catch (err) {
      next(err);
    }
  });

  /** 表情包被选择发送（累加使用次数，用于排序） */
  router.post('/stickers/:id/use', async (req, res, next) => {
    try {
      const id = Number(req.params.id);
      if (!Number.isInteger(id) || id <= 0) return res.status(400).json({ error: 'invalid id' });
      await pool.query(`UPDATE sticker SET use_count = use_count + 1 WHERE id = $1`, [id]);
      res.json({ ok: true });
    } catch (err) {
      next(err);
    }
  });

  return router;
}

export function createDashboardStickerRouter(pool: pg.Pool): Router {
  const router = Router();

  /** 表情包管理列表（含关键词/使用次数/上传时间） */
  router.get('/stickers', async (req, res, next) => {
    try {
      const q = String(req.query.q ?? '').trim();
      const result = await pool.query(
        `SELECT id, keywords, file_name, format, width, height, use_count, created_at
         FROM sticker
         WHERE user_id = $1 AND ($2 = '' OR keywords ILIKE '%' || $2 || '%')
         ORDER BY id DESC`,
        [DEFAULT_USER_ID, q],
      );
      const stickers = (result.rows as Array<Record<string, unknown>>).map((r) => ({
        id: r.id,
        keywords: r.keywords,
        url: stickerUrl(r.file_name as string),
        format: r.format,
        width: r.width,
        height: r.height,
        useCount: r.use_count,
        createdAt: r.created_at,
      }));
      res.json({ total: stickers.length, stickers });
    } catch (err) {
      next(err);
    }
  });

  /** 上传表情包（JSON base64，避免引入 multipart 依赖；图片建议 < 5MB） */
  router.post('/stickers', async (req, res, next) => {
    try {
      const body = req.body as { file_base64?: string; filename?: string; keywords?: string; width?: number; height?: number };
      if (!body?.file_base64 || !body?.filename) {
        return res.status(400).json({ error: 'file_base64 and filename required' });
      }
      const ext = extname(body.filename).toLowerCase();
      const format = ALLOWED_FORMAT[ext];
      if (!format) return res.status(400).json({ error: `unsupported format: ${ext}` });
      const buffer = Buffer.from(body.file_base64, 'base64');
      if (buffer.length === 0 || buffer.length > 10 * 1024 * 1024) {
        return res.status(400).json({ error: 'file size must be 0 ~ 10MB' });
      }
      const keywords = String(body.keywords ?? '').trim();
      if (!keywords) return res.status(400).json({ error: 'keywords required' });

      mkdirSync(STICKER_DIR, { recursive: true });
      const fileName = `${randomUUID()}${ext}`;
      writeFileSync(join(STICKER_DIR, fileName), buffer);

      const result = await pool.query(
        `INSERT INTO sticker (user_id, keywords, file_name, format, width, height)
         VALUES ($1, $2, $3, $4, $5, $6)
         RETURNING id, keywords, file_name, format, width, height, use_count, created_at`,
        [DEFAULT_USER_ID, keywords, fileName, format, body.width ?? null, body.height ?? null],
      );
      const row = result.rows[0] as Record<string, unknown>;
      res.status(201).json({
        id: row.id,
        keywords: row.keywords,
        url: stickerUrl(row.file_name as string),
        format: row.format,
        width: row.width,
        height: row.height,
        useCount: row.use_count,
        createdAt: row.created_at,
      });
    } catch (err) {
      next(err);
    }
  });

  /** 修改关键词 */
  router.patch('/stickers/:id', async (req, res, next) => {
    try {
      const id = Number(req.params.id);
      const keywords = String((req.body as { keywords?: string })?.keywords ?? '').trim();
      if (!Number.isInteger(id) || id <= 0) return res.status(400).json({ error: 'invalid id' });
      if (!keywords) return res.status(400).json({ error: 'keywords required' });
      await pool.query(`UPDATE sticker SET keywords = $1 WHERE id = $2 AND user_id = $3`, [
        keywords,
        id,
        DEFAULT_USER_ID,
      ]);
      res.json({ ok: true });
    } catch (err) {
      next(err);
    }
  });

  /** 删除表情包（数据库 + 文件） */
  router.delete('/stickers/:id', async (req, res, next) => {
    try {
      const id = Number(req.params.id);
      if (!Number.isInteger(id) || id <= 0) return res.status(400).json({ error: 'invalid id' });
      const result = await pool.query(
        `DELETE FROM sticker WHERE id = $1 AND user_id = $2 RETURNING file_name`,
        [id, DEFAULT_USER_ID],
      );
      if (result.rowCount === 0) return res.status(404).json({ error: 'not found' });
      const fileName = (result.rows[0] as { file_name: string }).file_name;
      const filePath = join(STICKER_DIR, fileName);
      if (existsSync(filePath)) unlinkSync(filePath); // 文件已缺失时忽略，不影响删除
      res.json({ ok: true });
    } catch (err) {
      next(err);
    }
  });

  return router;
}
