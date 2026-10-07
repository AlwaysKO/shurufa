import { createHash } from 'node:crypto';
import { Router } from 'express';
import type pg from 'pg';
import sharp from 'sharp';
import { savingFlags } from '../lib/deviceSaving.js';
import { withStatisticsRetentionLock } from '../lib/statisticsRetentionLock.js';

const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const MAX_BYTES = 3 * 1024 * 1024;
const hash = (value: Buffer | string) => createHash('sha256').update(value).digest('hex');
class InvalidRecord extends Error {}

async function parseRecord(body: unknown) {
  const r = body as Record<string, unknown> | null;
  if (!r || typeof r.id !== 'string' || !UUID.test(r.id) || typeof r.platform !== 'string' || !['amap', 'baidu'].includes(r.platform)) throw new InvalidRecord('invalid id or platform');
  for (const field of ['origin', 'destination']) {
    const value = r[field];
    if (typeof value !== 'string' || !value.trim() || value.length > 300 || /[\u0000-\u001f]/.test(value)) throw new InvalidRecord(`invalid ${field}`);
  }
  const started = Number(r.started_at), overview = Number(r.overview_at);
  if (typeof r.started_at !== 'number' || typeof r.overview_at !== 'number' || !Number.isSafeInteger(started) || !Number.isSafeInteger(overview) ||
    overview < 946684800000 || started < overview || started - overview > 5 * 60_000 || started > Date.now() + 5 * 60_000) throw new InvalidRecord('invalid navigation time');
  if (!['image/png', 'image/webp'].includes(String(r.mime_type)) || typeof r.file_base64 !== 'string' || r.file_base64.length > MAX_BYTES * 4 / 3 ||
    !/^[A-Za-z0-9+/]+={0,2}$/.test(r.file_base64)) throw new InvalidRecord('invalid screenshot');
  const bytes = Buffer.from(r.file_base64, 'base64');
  if (!bytes.length || bytes.length > MAX_BYTES || bytes.toString('base64') !== r.file_base64 || hash(bytes) !== r.sha256) throw new InvalidRecord('invalid screenshot hash or size');
  try {
    const image = sharp(bytes, { limitInputPixels: 16_000_000 });
    const metadata = await image.metadata();
    if (`image/${metadata.format}` !== r.mime_type || (metadata.pages ?? 1) !== 1) throw new Error('invalid format');
    await image.stats(); // 实际解码，不能只凭文件头接受截断图片。
  } catch { throw new InvalidRecord('screenshot cannot be decoded'); }
  const metadata = { id: r.id.toLowerCase(), platform: r.platform as string, origin: (r.origin as string).trim(), destination: (r.destination as string).trim(),
    started_at: started, overview_at: overview, sha256: r.sha256 as string, mime_type: r.mime_type as string };
  return { ...metadata, bytes, payloadHash: hash(JSON.stringify(metadata)) };
}

export function createMobileNavigationRouter(pool: pg.Pool): Router {
  const router = Router();
  router.post('/', async (req, res, next) => {
    try {
      const r = await parseRecord(req.body);
      const user = String(res.locals.userId).toLowerCase();
      const receipt = { ok: true, id: r.id, sha256: r.sha256 };
      if ((await savingFlags(pool, [user])).get(user) === false) { res.json({ ...receipt, discarded: true }); return; }
      const result = await withStatisticsRetentionLock(pool, 'navigation', user, async db => {
        const deleted = await db.query('SELECT source_version FROM retention_deleted_record WHERE user_id=$1 AND dataset=\'navigation\' AND record_key=$2', [user, r.id]);
        if (deleted.rows.length) return deleted.rows.every(row => row.source_version === r.payloadHash) ? 'deleted' : 'conflict';
        await db.query('INSERT INTO device(id) VALUES($1) ON CONFLICT(id) DO NOTHING', [user]);
        await db.query(`INSERT INTO navigation_record(user_id,id,platform,origin,destination,started_at,overview_at,sha256,payload_sha256,mime_type,screenshot)
          VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11) ON CONFLICT(user_id,id) DO NOTHING`,
        [user, r.id, r.platform, r.origin, r.destination, new Date(r.started_at), new Date(r.overview_at), r.sha256, r.payloadHash, r.mime_type, r.bytes]);
        const stored = await db.query('SELECT payload_sha256 FROM navigation_record WHERE user_id=$1 AND id=$2', [user, r.id]);
        if (!stored.rows[0]) throw new Error('navigation record was not stored');
        return stored.rows[0].payload_sha256 === r.payloadHash ? 'stored' : 'conflict';
      });
      if (result === 'conflict') { res.status(409).json({ error: 'record id already has different content' }); return; }
      res.json(result === 'deleted' ? { ...receipt, deleted: true } : receipt);
    } catch (error) { if (error instanceof InvalidRecord) res.status(400).json({ error: error.message }); else next(error); }
  });
  return router;
}

export function createDashboardNavigationRouter(pool: pg.Pool): Router {
  const router = Router();
  router.get('/', async (req, res, next) => {
    try {
      const pageText = req.query.page ?? '1', platform = req.query.platform;
      if (typeof pageText !== 'string' || !/^[1-9]\d{0,5}$/.test(pageText) || (platform !== undefined && (typeof platform !== 'string' || !['amap', 'baidu'].includes(platform)))) {
        res.status(400).json({ error: 'invalid page or platform' }); return;
      }
      const page = Number(pageText), params: unknown[] = [res.locals.userId];
      let where = 'user_id=$1';
      if (platform !== undefined) { params.push(platform); where += ' AND platform=$2'; }
      const total = await pool.query(`SELECT COUNT(*) AS n FROM navigation_record WHERE ${where}`, params);
      const result = await pool.query(`SELECT id,platform,origin,destination,started_at,overview_at,received_at
        FROM navigation_record WHERE ${where} ORDER BY started_at DESC,id DESC LIMIT 20 OFFSET $${params.length + 1}`, [...params, (page - 1) * 20]);
      res.set('Cache-Control', 'no-store').json({ total: Number(total.rows[0].n), page, page_size: 20, records: result.rows });
    } catch (error) { next(error); }
  });
  router.get('/:id/image', async (req, res, next) => {
    try {
      if (!UUID.test(req.params.id)) { res.sendStatus(400); return; }
      const result = await pool.query('SELECT screenshot,mime_type FROM navigation_record WHERE user_id=$1 AND id=$2', [res.locals.userId, req.params.id]);
      const record = result.rows[0];
      if (!record) { res.sendStatus(404); return; }
      res.set('Cache-Control', 'no-store').set('X-Content-Type-Options', 'nosniff').type(record.mime_type).send(record.screenshot);
    } catch (error) { next(error); }
  });
  return router;
}
