import { randomUUID } from 'node:crypto';
import { cleanDeviceFiles, queueUploadFileCleanup, DeviceDeletionError } from '../lib/deleteDeviceData.js';
import { Router } from 'express';
import type pg from 'pg';

function dateBoundary(value: unknown, end: boolean): string | undefined {
  if (value === undefined || value === '') return undefined;
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) throw Error('接收日期格式无效');
  const time = Date.parse(`${value}T00:00:00+08:00`);
  if (!Number.isFinite(time) || new Date(time + 28_800_000).toISOString().slice(0, 10) !== value) throw Error('接收日期无效');
  return new Date(time + (end ? 86_400_000 : 0)).toISOString();
}

const eligible = "a.user_id=$1 AND a.mime_type IN ('image/png','image/webp') AND NOT EXISTS (SELECT 1 FROM chat_message_asset ma WHERE ma.asset_id=a.id)";
function scope(user: string, input: { from?: unknown; to?: unknown }) {
  const from = dateBoundary(input.from, false), to = dateBoundary(input.to, true);
  if (from && to && from >= to) throw Error('开始日期不能晚于结束日期');
  const params: unknown[] = [user], conditions = [eligible];
  if (from) { params.push(from); conditions.push(`a.created_at >= $${params.length}::timestamptz`); }
  if (to) { params.push(to); conditions.push(`a.created_at < $${params.length}::timestamptz`); }
  return { params, where: conditions.join(' AND ') };
}
type Target = { id: string; signature: string };
type Snapshot = { user: string; targets: Target[]; expires: number; busy: boolean; processed: number; deleted: number; skipped: number; filesPending: boolean };
const ttl = 15 * 60_000;
const progress = (job: Snapshot) => ({ processed: job.processed, total: job.targets.length, deleted: job.deleted,
  skipped: job.skipped, done: job.processed === job.targets.length, files_pending: job.filesPending });

/** Recheck ownership and associations under the same locks as chat deletion; files are removed only after commit. */
async function removeImages(pool: pg.Pool, user: string, ids: string[], targets?: Target[]) {
  const db = await pool.connect();
  let cleanupKey: string | null = null, deleted = 0;
  try {
    await db.query('BEGIN');
    await db.query("SET LOCAL lock_timeout = '5s'");
    await db.query("SET LOCAL statement_timeout = '20s'");
    await db.query('LOCK TABLE chat_conversation, chat_message, chat_message_asset, media_asset IN SHARE ROW EXCLUSIVE MODE');
    const current = await db.query<Target>(`SELECT a.id,md5(row_to_json(a)::text) AS signature FROM media_asset a
      WHERE ${eligible} AND a.id=ANY($2::bigint[])`, [user, ids]);
    if (!targets && current.rows.length !== ids.length) throw new DeviceDeletionError(409, '部分图片已关联、已删除或不属于当前手机，请刷新后重新选择');
    const expected = targets && new Map(targets.map(row => [row.id, row.signature]));
    const matched = current.rows.filter(row => !expected || expected.get(row.id) === row.signature).map(row => row.id);
    const removed = await db.query<{ storage_path: string }>('DELETE FROM media_asset WHERE user_id=$1 AND id=ANY($2::bigint[]) RETURNING storage_path', [user, matched]);
    cleanupKey = await queueUploadFileCleanup(db, removed.rows.map(row => row.storage_path));
    await db.query('COMMIT');
    deleted = removed.rows.length;
  } catch (error) { await db.query('ROLLBACK').catch(() => {}); throw error; }
  finally { db.release(); }
  return { deleted, cleanupKey };
}

export function createChatUnlinkedImagesRouter(pool: pg.Pool): Router {
  const router = Router();
  router.get('/unlinked-images', async (req, res, next) => {
    const page = Number(req.query.page ?? 1), pageSize = Number(req.query.page_size ?? 24);
    if (!Number.isSafeInteger(page) || page < 1 || page > 100_000 || !Number.isSafeInteger(pageSize) || pageSize < 1 || pageSize > 60) {
      res.status(400).json({ error: '分页参数无效' }); return;
    }
    let selected: ReturnType<typeof scope>;
    try { selected = scope(res.locals.userId, req.query); }
    catch (error) { res.status(400).json({ error: (error as Error).message }); return; }
    const { params, where } = selected;
    try {
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
  const snapshots = new Map<string, Snapshot>();
  function expire() { for (const [token, job] of snapshots) if (!job.busy && job.expires < Date.now()) snapshots.delete(token); }
  router.post('/unlinked-images/delete', async (req, res, next) => {
    const body = req.body;
    if (body?.confirm !== 'DELETE' || !Array.isArray(body.ids) || !body.ids.length || body.ids.length > 1000 ||
        !body.ids.every((id: unknown) => typeof id === 'string' && /^[1-9]\d{0,18}$/.test(id) && BigInt(id) <= 9223372036854775807n)) {
      res.status(400).json({ error: '请选择有效图片并确认删除' }); return;
    }
    try {
      const result = await removeImages(pool, res.locals.userId, [...new Set<string>(body.ids)]);
      const filesPending = result.cleanupKey ? !await cleanDeviceFiles(pool, result.cleanupKey).catch(() => false) : false;
      res.json({ deleted: result.deleted, files_pending: filesPending });
    } catch (error) {
      if (error instanceof DeviceDeletionError) res.status(error.status).json({ error: error.message }); else next(error);
    }
  });
  router.post('/unlinked-images/cleanup/preview', async (req, res, next) => {
    if (![1, 7, 30].includes(req.body?.days)) { res.status(400).json({ error: '请选择保留1天、7天或30天' }); return; }
    let selected: ReturnType<typeof scope>;
    try { selected = scope(res.locals.userId, req.body); }
    catch (error) { res.status(400).json({ error: (error as Error).message }); return; }
    expire();
    const cutoff = new Date(Date.now() - req.body.days * 86_400_000);
    try {
      const { where, params } = selected;
      const result = await pool.query<Target>(`SELECT a.id,md5(row_to_json(a)::text) AS signature FROM media_asset a
        WHERE ${where} AND a.created_at < $${params.length + 1}::timestamptz ORDER BY a.created_at,a.id LIMIT 50001`, [...params, cutoff]);
      if (result.rows.length > 50_000) { res.status(400).json({ error: '待清理图片超过5万张，请缩小日期范围' }); return; }
      for (const [key, job] of snapshots) if (job.user === res.locals.userId && !job.busy && job.processed === 0) snapshots.delete(key);
      if (snapshots.size >= 20) { res.status(429).json({ error: '清理预览较多，请稍后重试' }); return; }
      const token = randomUUID();
      if (result.rows.length) snapshots.set(token, { user: res.locals.userId, targets: result.rows, expires: Date.now() + ttl,
        busy: false, processed: 0, deleted: 0, skipped: 0, filesPending: false });
      res.json({ token, total: result.rows.length, cutoff: cutoff.toISOString() });
    } catch (error) { next(error); }
  });
  router.post('/unlinked-images/cleanup/batch', async (req, res, next) => {
    const body = req.body;
    if (body?.confirm !== 'DELETE' || typeof body?.token !== 'string' || !Number.isSafeInteger(body?.offset) || body.offset < 0) {
      res.status(400).json({ error: '清理确认参数无效' }); return;
    }
    expire();
    const job = snapshots.get(body.token);
    if (!job || job.user !== res.locals.userId) { res.status(410).json({ error: '清理预览已失效，请重新预览' }); return; }
    if (job.busy || body.offset > job.processed) { res.status(409).json({ error: '清理批次正在处理或进度不一致，请重试' }); return; }
    if (body.offset < job.processed || job.processed === job.targets.length) { res.json(progress(job)); return; }
    job.busy = true;
    try {
      const targets = job.targets.slice(job.processed, job.processed + 200);
      const result = await removeImages(pool, job.user, targets.map(row => row.id), targets);
      job.processed += targets.length; job.deleted += result.deleted; job.skipped += targets.length - result.deleted;
      job.expires = Date.now() + ttl;
      if (result.cleanupKey && !await cleanDeviceFiles(pool, result.cleanupKey).catch(() => false)) job.filesPending = true;
      res.json(progress(job));
    } catch (error) {
      if (error instanceof DeviceDeletionError) res.status(error.status).json({ error: error.message }); else next(error);
    } finally { job.busy = false; }
  });
  return router;
}
