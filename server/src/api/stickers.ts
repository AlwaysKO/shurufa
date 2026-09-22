import { normalizeRecommendationPhrase } from '../expression/recommendationGroups.js';
import { systemExpressionCatalog } from './expressionSnapshot.js';
import sharp from 'sharp';
import { Router } from 'express';
import type express from 'express';
import type pg from 'pg';
import { mkdirSync, writeFileSync, unlinkSync, existsSync } from 'node:fs';
import { join, extname } from 'node:path';
import { createHash, randomUUID } from 'node:crypto';
import { loadStickerLibrary, rememberStickerKeywords, updateStickerGroup, createStickerKeyword, StickerGroupError } from './stickerLibrary.js';


/** 表情包文件存储目录（server/uploads/stickers），由 app.ts 挂载为 /uploads 静态路径 */
const stickerDirectory = () => join(process.cwd(), 'uploads', 'stickers');

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
      const requestedLimit = Number(req.query.limit ?? 60);
      const limit = Number.isInteger(requestedLimit) && requestedLimit > 0 ? Math.min(requestedLimit, 200) : 60;
      if (q) {
        const normalized = normalizeRecommendationPhrase(q);
        const group = (await loadStickerLibrary(pool, res.locals.userId)).groups.find(group =>
          group.aliases.some(alias => normalizeRecommendationPhrase(alias) === normalized));
        if (group) {
          const stickers = group.assets.filter(asset => asset.source === 'personal').slice(0, limit)
            .map(({ id, url, format, width, height }) => ({ id, url, format, width, height }));
          return res.json({ total: stickers.length, stickers });
        }
      }
      const result = await pool.query(
        `SELECT id, file_name, format, width, height, use_count
         FROM sticker
         WHERE user_id = $1 AND ($2 = '' OR keywords ILIKE '%' || $2 || '%')
         ORDER BY use_count DESC, id DESC
         LIMIT $3`,
        [res.locals.userId, q, limit],
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
      await pool.query(
        `UPDATE sticker SET use_count = use_count + 1 WHERE id = $1 AND user_id = $2`,
        [id, res.locals.userId],
      );
      res.json({ ok: true });
    } catch (err) {
      next(err);
    }
  });

  return router;
}

export function createDashboardStickerRouter(pool: pg.Pool): Router {
  const router = Router();

  router.get('/sticker-library', async (_req, res, next) => {
    try { res.json(await loadStickerLibrary(pool, res.locals.userId)); }
    catch (error) { next(error); }
  });

  router.patch('/sticker-groups/:keyword', async (req, res, next) => {
    try { res.json({ group: await updateStickerGroup(pool, res.locals.userId, req.params.keyword, req.body) }); }
    catch (error) {
      if (error instanceof StickerGroupError) return res.status(error.status).json({ error: error.message });
      next(error);
    }
  });

  // 系统成品只删除当前用户的可见引用，保留原文件与来源证据。
  router.delete('/system-stickers/:id', async (req, res, next) => {
    try {
      const asset = (await systemExpressionCatalog()).templates.find(item => item.id === req.params.id && item.type !== 'synthesis-template');
      if (!asset || !/^[a-f0-9]{64}$/.test(asset.sha256)) return res.status(404).json({ error: 'not found' });
      await rememberStickerKeywords(pool, res.locals.userId, asset.keywords.join(','));
      await pool.query(`INSERT INTO keyword_gif_removal (user_id, sha256, asset_id) VALUES ($1,$2,$3)
        ON CONFLICT (user_id, sha256) DO NOTHING`, [res.locals.userId, asset.sha256, asset.id]);
      res.json({ ok: true });
    } catch (error) { next(error); }
  });

  router.post('/sticker-keywords', async (req, res, next) => {
    try {
      const keyword = typeof req.body?.keyword === 'string' ? req.body.keyword.trim() : '';
      if (!keyword || keyword.length > 100 || /[,，\r\n]/.test(keyword)) {
        return res.status(400).json({ error: '请填写单个关键词（1～100字，不含逗号或换行）' });
      }
      const saved = await createStickerKeyword(pool, res.locals.userId, keyword);
      res.status(201).json({ keyword: saved });
    } catch (error) {
      if (error instanceof StickerGroupError) return res.status(error.status).json({ error: error.message });
      next(error);
    }
  });

  /** 表情包管理列表（含关键词/使用次数/上传时间） */
  router.get('/stickers', async (req, res, next) => {
    try {
      const q = String(req.query.q ?? '').trim();
      const result = await pool.query(
        `SELECT id, keywords, file_name, format, width, height, use_count, created_at
         FROM sticker
         WHERE user_id = $1 AND ($2 = '' OR keywords ILIKE '%' || $2 || '%')
         ORDER BY id DESC`,
        [res.locals.userId, q],
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
      const body = req.body as { file_base64?: string; filename?: string; keywords?: string; group_keyword?: string; width?: number; height?: number };
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
      let dimensions;
      try {
        dimensions = await sharp(buffer, { animated: true, limitInputPixels: 100_000_000 }).metadata();
        const detected = dimensions.format === 'jpeg' ? 'jpg' : dimensions.format;
        if (detected !== format) return res.status(400).json({ error: 'image format does not match filename' });
      } catch { return res.status(400).json({ error: 'invalid image' }); }
      let keywords = String(body.keywords ?? '').trim();
      if (body.group_keyword !== undefined) {
        const group = (await loadStickerLibrary(pool, res.locals.userId)).groups.find(item => item.keyword === body.group_keyword);
        if (!group) return res.status(400).json({ error: '关键词组不存在，请刷新后重试' });
        // 归属与可编辑的匹配说法分离，删除/新增说法不会制造重复组或丢图。
        keywords = group.keyword;
      }
      if (!keywords) return res.status(400).json({ error: 'keywords required' });

      mkdirSync(stickerDirectory(), { recursive: true });
      const fileName = `${randomUUID()}${ext}`;
      writeFileSync(join(stickerDirectory(), fileName), buffer);

      const result = await pool.query(
        `INSERT INTO sticker (user_id, keywords, file_name, format, width, height, sha256)
         VALUES ($1, $2, $3, $4, $5, $6, $7)
         RETURNING id, keywords, file_name, format, width, height, use_count, created_at`,
        [res.locals.userId, keywords, fileName, format, dimensions.width ?? null, dimensions.pageHeight ?? dimensions.height ?? null, createHash('sha256').update(buffer).digest('hex')],
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
      const existing = await pool.query<{ keywords: string }>(
        'SELECT keywords FROM sticker WHERE id = $1 AND user_id = $2', [id, res.locals.userId]);
      if (!existing.rows.length) return res.status(404).json({ error: 'not found' });
      await rememberStickerKeywords(pool, res.locals.userId, `${existing.rows[0].keywords},${keywords}`);
      await pool.query(`UPDATE sticker SET keywords = $1 WHERE id = $2 AND user_id = $3`, [
        keywords,
        id,
        res.locals.userId,
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
      const existing = await pool.query<{ keywords: string }>(
        'SELECT keywords FROM sticker WHERE id = $1 AND user_id = $2', [id, res.locals.userId]);
      if (!existing.rows.length) return res.status(404).json({ error: 'not found' });
      await rememberStickerKeywords(pool, res.locals.userId, existing.rows[0].keywords);
      const result = await pool.query(
        `DELETE FROM sticker WHERE id = $1 AND user_id = $2 RETURNING file_name`,
        [id, res.locals.userId],
      );
      if (result.rowCount === 0) return res.status(404).json({ error: 'not found' });
      const fileName = (result.rows[0] as { file_name: string }).file_name;
      const filePath = join(stickerDirectory(), fileName);
      if (existsSync(filePath)) unlinkSync(filePath); // 文件已缺失时忽略，不影响删除
      res.json({ ok: true });
    } catch (err) {
      next(err);
    }
  });

  return router;
}
