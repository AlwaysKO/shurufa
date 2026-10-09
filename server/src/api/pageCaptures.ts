import { createHash } from 'node:crypto';
import { Router } from 'express';
import type pg from 'pg';
import sharp from 'sharp';
import { savingFlags } from '../lib/deviceSaving.js';
import { capturePage, captureTombstone, captureWhere, createCaptureManagementRouter, InvalidCaptureFilter, lockCaptureDevice } from './captureManagement.js';

const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const MAX_BYTES = 3 * 1024 * 1024;
const KINDS = ['conversation_list', 'payment', 'media_feed', 'image_post', 'mini_app'];
const FIELDS = new Set(['id', 'package_name', 'kind', 'captured_at', 'width', 'height', 'sha256', 'mime_type', 'file_base64']);
const hash = (data: Buffer | string) => createHash('sha256').update(data).digest('hex');
class InvalidPage extends Error {}

/** 严格版本1协议，不把页面图片伪装为聊天消息或已确认视频访问。 */
async function parsePage(body: unknown) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw new InvalidPage('invalid page');
  const r = body as Record<string, unknown>;
  if (Object.keys(r).some(key => !FIELDS.has(key)) || typeof r.id !== 'string' || !UUID.test(r.id)) throw new InvalidPage('invalid page fields');
  const platform = r.package_name === 'com.tencent.mm' ? 'wechat' : r.package_name === 'com.ss.android.ugc.aweme' ? 'douyin' : null;
  if (!platform || typeof r.kind !== 'string' || !KINDS.includes(r.kind)) throw new InvalidPage('unsupported page');
  if (typeof r.captured_at !== 'number' || !Number.isSafeInteger(r.captured_at) || r.captured_at < 946684800000 ||
    r.captured_at > Date.now() + 300000) throw new InvalidPage('invalid capture time');
  if (typeof r.width !== 'number' || typeof r.height !== 'number' || !Number.isInteger(r.width) || !Number.isInteger(r.height) ||
    r.width < 1 || r.height < 1 || r.width > 8192 || r.height > 8192 || r.width * r.height > 16_000_000) throw new InvalidPage('invalid image dimensions');
  if (typeof r.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(r.sha256) ||
    !['image/png', 'image/webp'].includes(String(r.mime_type)) || typeof r.file_base64 !== 'string' ||
    !r.file_base64.length || r.file_base64.length > MAX_BYTES * 4 / 3 || !/^[A-Za-z0-9+/]+={0,2}$/.test(r.file_base64)) throw new InvalidPage('invalid screenshot');
  const bytes = Buffer.from(r.file_base64, 'base64');
  if (!bytes.length || bytes.length > MAX_BYTES || bytes.toString('base64') !== r.file_base64 || hash(bytes) !== r.sha256) throw new InvalidPage('invalid screenshot hash or size');
  try {
    const image = sharp(bytes, { limitInputPixels: 16_000_000 });
    const meta = await image.metadata();
    if (`image/${meta.format}` !== r.mime_type || meta.width !== r.width || meta.height !== r.height || (meta.pages ?? 1) !== 1) throw Error('mismatch');
    await image.stats(); // 真正解码，不能把截断文件头当作有效图片。
  } catch { throw new InvalidPage('screenshot cannot be decoded'); }
  const metadata = { id: r.id.toLowerCase(), platform, kind: r.kind, captured_at: r.captured_at,
    width: r.width, height: r.height, sha256: r.sha256, mime_type: r.mime_type as string };
  return { ...metadata, bytes, payloadHash: hash(JSON.stringify(metadata)) };
}

export function createMobilePageCapturesRouter(pool: pg.Pool): Router {
  const router = Router();
  router.post('/', async (req, res, next) => {
    try {
      const r = await parsePage(req.body);
      const user = String(res.locals.userId).toLowerCase();
      const receipt = { ok: true, id: r.id, sha256: r.sha256 };
      // 与现有明确关闭保存协议一致，不伪称已入库；坏载荷仍先拒绝。
      if ((await savingFlags(pool, [user])).get(user) === false) { res.json({ ...receipt, discarded: true }); return; }
      const db = await pool.connect();
      let matches = false;
      let discarded = false;
      try {
        await db.query('BEGIN');
        await lockCaptureDevice(db, user);
        await db.query('INSERT INTO device(id) VALUES($1) ON CONFLICT(id) DO NOTHING', [user]);
        const deleted = await captureTombstone(db, 'page', user, r.id);
        if (deleted) {
          matches = deleted.payload_sha256 === r.payloadHash && deleted.sha256 === r.sha256;
          discarded = matches;
        } else {
          await db.query(`INSERT INTO page_capture(user_id,id,platform,kind,captured_at,width,height,sha256,payload_sha256,mime_type,screenshot)
            VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11) ON CONFLICT(user_id,id) DO NOTHING`,
          [user, r.id, r.platform, r.kind, new Date(r.captured_at), r.width, r.height, r.sha256, r.payloadHash, r.mime_type, r.bytes]);
          const stored = await db.query('SELECT payload_sha256 FROM page_capture WHERE user_id=$1 AND id=$2', [user, r.id]);
          if (!stored.rows[0]) throw Error('page capture was not stored');
          matches = stored.rows[0].payload_sha256 === r.payloadHash;
        }
        await db.query('COMMIT');
      } catch (error) { await db.query('ROLLBACK').catch(() => {}); throw error; }
      finally { db.release(); }
      if (!matches) { res.status(409).json({ error: 'record id already has different content' }); return; }
      // 只有原图和元信息已提交才确认；手机必须校验ID/hash后再清理本地待办。
      res.json(discarded ? { ...receipt, discarded: true } : receipt);
    } catch (error) { if (error instanceof InvalidPage) res.status(400).json({ error: error.message }); else next(error); }
  });
  return router;
}

export function createDashboardPageCapturesRouter(pool: pg.Pool): Router {
  const router = Router();
  router.use(createCaptureManagementRouter(pool, 'page'));
  router.get('/', async (req, res, next) => {
    try {
      const page = capturePage(req.query.page), { where, params } = captureWhere('page', res.locals.userId, req.query);
      const total = await pool.query(`SELECT COUNT(*) AS n FROM page_capture c WHERE ${where}`, params);
      const records = await pool.query(`SELECT c.id,c.platform,c.kind,c.captured_at,c.width,c.height,c.sha256,c.mime_type,c.received_at,c.title,c.note
        FROM page_capture c WHERE ${where} ORDER BY c.captured_at DESC,c.id DESC LIMIT 20 OFFSET $${params.length + 1}`, [...params, (page - 1) * 20]);
      res.set('Cache-Control', 'no-store').json({ total: Number(total.rows[0].n), page, page_size: 20, records: records.rows });
    } catch (error) { if (error instanceof InvalidCaptureFilter) res.status(400).json({ error: error.message }); else next(error); }
  });
  router.get('/:id/image', async (req, res, next) => {
    try {
      if (!UUID.test(req.params.id)) { res.sendStatus(400); return; }
      const result = await pool.query('SELECT screenshot,mime_type FROM page_capture WHERE user_id=$1 AND id=$2', [res.locals.userId, req.params.id]);
      if (!result.rows[0]) { res.sendStatus(404); return; }
      res.set('Cache-Control', 'no-store').set('X-Content-Type-Options', 'nosniff').type(result.rows[0].mime_type).send(result.rows[0].screenshot);
    } catch (error) { next(error); }
  });
  return router;
}
