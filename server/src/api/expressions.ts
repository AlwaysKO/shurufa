import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { Router } from 'express';
import type { NextFunction, Request, Response } from 'express';
import type pg from 'pg';
import type { GeneratedExpressionCatalog } from '../expression/assetGenerator.js';
import { emojiCombinationKey, rankExpressionAssets } from '../expression/catalog.js';
import type { ExpressionAsset } from '../types/expression.js';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ID_PATTERN = /^[a-z0-9][a-z0-9_-]*$/;

export function expressionAssetRoot(): string {
  return join(process.cwd(), '.runtime', 'expression-assets');
}

export function requireExpressionAssetIdentity(
  req: Request,
  res: Response,
  next: NextFunction,
): void {
  const userId = req.get('X-Device-Id');
  if (!userId || !UUID_PATTERN.test(userId)) {
    res.status(400).json({ error: 'valid X-Device-Id required' });
    return;
  }
  next();
}

async function loadCatalog(): Promise<GeneratedExpressionCatalog> {
  return JSON.parse(
    await readFile(join(expressionAssetRoot(), 'catalog.json'), 'utf8'),
  ) as GeneratedExpressionCatalog;
}

function assetUrl(fileName: string): string {
  return `/uploads/expression/${fileName}`;
}

function publicAsset(asset: ExpressionAsset): Record<string, unknown> {
  return {
    ...asset,
    url: assetUrl(asset.fileName),
    thumbnail_url: asset.thumbnailFileName ? assetUrl(asset.thumbnailFileName) : null,
  };
}

function publicCatalog(catalog: GeneratedExpressionCatalog): Record<string, unknown> {
  return {
    version: catalog.version,
    templates: catalog.templates.map(publicAsset),
    emojiBases: catalog.emojiBases.map((item) => ({
      ...item,
      url: assetUrl(item.fileName),
    })),
    emojiCombinations: catalog.emojiCombinations.map((item) => ({
      ...item,
      url: assetUrl(item.fileName),
    })),
  };
}

async function ensureAssetRow(pool: pg.Pool, asset: ExpressionAsset): Promise<void> {
  await pool.query(`INSERT INTO expression_asset
    (id, type, format, version, file_name, thumbnail_file_name, sha256,
     width, height, keywords, emotions, embedded_text, text_safe_area, layout, heat)
    VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15)
    ON CONFLICT (id) DO UPDATE SET
      type = EXCLUDED.type,
      format = EXCLUDED.format,
      version = EXCLUDED.version,
      file_name = EXCLUDED.file_name,
      thumbnail_file_name = EXCLUDED.thumbnail_file_name,
      sha256 = EXCLUDED.sha256,
      width = EXCLUDED.width,
      height = EXCLUDED.height,
      keywords = EXCLUDED.keywords,
      emotions = EXCLUDED.emotions,
      embedded_text = EXCLUDED.embedded_text,
      text_safe_area = EXCLUDED.text_safe_area,
      layout = EXCLUDED.layout,
      heat = EXCLUDED.heat,
      updated_at = NOW()`, [
    asset.id,
    asset.type,
    asset.format,
    asset.version,
    asset.fileName,
    asset.thumbnailFileName,
    asset.sha256,
    asset.width,
    asset.height,
    asset.keywords,
    asset.emotions,
    asset.embeddedText,
    asset.textSafeArea,
    asset.layout,
    asset.heat,
  ]);
}

export function createMobileExpressionRouter(pool: pg.Pool): Router {
  const router = Router();

  router.use((req, res, next) => {
    const userId = res.locals.userId ?? req.get('X-Device-Id');
    if (typeof userId !== 'string' || !UUID_PATTERN.test(userId)) {
      res.status(400).json({ error: 'valid X-Device-Id required' });
      return;
    }
    res.locals.userId = userId;
    next();
  });

  router.get('/catalog', async (req, res, next) => {
    try {
      const catalog = await loadCatalog();
      const version = req.query.version;
      if (typeof version === 'string' && version === catalog.version) {
        res.status(304).end();
        return;
      }
      res.json(publicCatalog(catalog));
    } catch (error) {
      next(error);
    }
  });

  router.get('/recommend', async (req, res, next) => {
    try {
      const query = typeof req.query.q === 'string' ? req.query.q.trim() : '';
      if (!query || query.length > 100) {
        res.status(400).json({ error: 'q required (<= 100 chars)' });
        return;
      }
      const catalog = await loadCatalog();
      const results = rankExpressionAssets(catalog.templates, query)
        .slice(0, 20)
        .map(publicAsset);
      res.json({ query, results });
    } catch (error) {
      next(error);
    }
  });

  router.get('/emoji/:first/:second', async (req, res, next) => {
    try {
      const { first, second } = req.params;
      if (!ID_PATTERN.test(first) || !ID_PATTERN.test(second)) {
        res.status(400).json({ error: 'invalid emoji id' });
        return;
      }
      const catalog = await loadCatalog();
      const key = emojiCombinationKey(first, second);
      const combination = catalog.emojiCombinations.find((item) => item.key === key);
      if (!combination) {
        res.status(404).json({ error: 'combination not found' });
        return;
      }
      res.json({ ...combination, url: assetUrl(combination.fileName) });
    } catch (error) {
      next(error);
    }
  });

  router.post('/:id/use', async (req, res, next) => {
    try {
      const { id } = req.params;
      if (!ID_PATTERN.test(id)) {
        res.status(400).json({ error: 'invalid expression id' });
        return;
      }
      const catalog = await loadCatalog();
      const asset = catalog.templates.find((item) => item.id === id);
      if (!asset) {
        res.status(404).json({ error: 'expression not found' });
        return;
      }
      await ensureAssetRow(pool, asset);
      const result = await pool.query<{ use_count: number }>(`INSERT INTO expression_asset_usage
        (user_id, asset_id, use_count, last_used_at)
        VALUES ($1, $2, 1, NOW())
        ON CONFLICT (user_id, asset_id) DO UPDATE SET
          use_count = expression_asset_usage.use_count + 1,
          last_used_at = NOW()
        RETURNING use_count`, [res.locals.userId, id]);
      res.json({ ok: true, use_count: Number(result.rows[0].use_count) });
    } catch (error) {
      next(error);
    }
  });

  return router;
}
