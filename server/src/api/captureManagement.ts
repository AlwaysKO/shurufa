import { randomUUID } from 'node:crypto';
import { Router } from 'express';
import type pg from 'pg';

export const captureUuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
type CaptureType = 'page' | 'video';
const table = (type: CaptureType) => type === 'page' ? 'page_capture' : 'video_visit';
const timeColumn = (type: CaptureType) => type === 'page' ? 'captured_at' : 'entered_at';
const MAX_TARGETS = 50_000, BATCH_SIZE = 200, TTL = 15 * 60_000;
export class InvalidCaptureFilter extends Error {}
export function capturePage(value: unknown): number {
  if (value === undefined) return 1;
  if (typeof value !== 'string' || !/^[1-9]\d{0,5}$/.test(value)) throw new InvalidCaptureFilter('invalid page');
  return Number(value);
}
function date(value: unknown): number | undefined {
  if (value === undefined) return undefined;
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) throw new InvalidCaptureFilter('invalid date');
  const utc = Date.parse(value + 'T00:00:00.000Z');
  if (!Number.isFinite(utc) || new Date(utc).toISOString().slice(0, 10) !== value || value < '2000-01-01')
    throw new InvalidCaptureFilter('invalid date');
  return utc - 8 * 3600000;
}
/** 参数化筛选，北京日含首尾；正向视频页面只在视频列表展示。 */
export function captureWhere(type: CaptureType, user: string, filters: Record<string, unknown>) {
  const params: unknown[] = [user];
  const clauses = ['c.user_id=$1'];
  const add = (sql: string, value: unknown) => { params.push(value); clauses.push(sql.replace('?', '$' + params.length)); };
  const enums: Record<string, string[]> = { platform: ['wechat', 'douyin'] };
  if (type === 'page') enums.kind = ['conversation_list', 'payment', 'media_feed', 'image_post', 'mini_app'];
  else {
    enums.observation_kind = ['confirmed_video', 'unconfirmed_feed'];
    enums.exit_reason = ['page_changed', 'switched', 'exit', 'background', 'locked', 'interrupted'];
    enums.complete = ['true', 'false'];
  }
  for (const [name, allowed] of Object.entries(enums)) {
    const value = filters[name];
    if (value !== undefined) {
      if (typeof value !== 'string' || !allowed.includes(value)) throw new InvalidCaptureFilter('invalid ' + name);
      add(`c.${name}=?`, name === 'complete' ? value === 'true' : value);
    }
  }
  const from = date(filters.from), to = date(filters.to);
  if (from !== undefined && to !== undefined && from > to) throw new InvalidCaptureFilter('invalid date range');
  const time = `c.${timeColumn(type)}`;
  if (from !== undefined) add(`${time}>=?`, type === 'page' ? new Date(from) : from);
  if (to !== undefined) add(`${time}<?`, type === 'page' ? new Date(to + 86400000) : to + 86400000);
  if (filters.q !== undefined) {
    if (typeof filters.q !== 'string' || filters.q.length > 200 || filters.q.includes('\0')) throw new InvalidCaptureFilter('invalid query');
    // strpos 对 %/_ 不作通配，后台关键词按字面匹配名称或备注。
    add('strpos(lower(c.title || chr(10) || c.note),lower(?))>0', filters.q.trim());
  }
  if (type === 'page') clauses.push(`c.kind<>'media_feed' AND NOT EXISTS(SELECT 1 FROM video_visit v
    WHERE v.user_id=c.user_id AND (v.first_image_id=c.id OR v.last_image_id=c.id))`);
  return { where: clauses.join(' AND '), params };
}

/** 上传、管理删除与依赖链接按设备串行；跨设备互不阻塞，防删除/重传竞态。 */
export async function lockCaptureDevice(db: pg.PoolClient, user: string) {
  await db.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 14437))', [user.toLowerCase()]);
}
export async function captureTombstone(db: pg.PoolClient, type: CaptureType, user: string, id: string) {
  return (await db.query('SELECT platform,payload_sha256,sha256 FROM capture_tombstone WHERE user_id=$1 AND record_type=$2 AND id=$3',
    [user, type, id])).rows[0] as { platform: string; payload_sha256: string; sha256: string | null } | undefined;
}
async function deleteRows(db: pg.PoolClient, type: CaptureType, user: string, ids: string[]): Promise<number> {
  if (!ids.length) return 0;
  const rows = await db.query(`SELECT c.id${type === 'video' ? ',c.first_image_id,c.last_image_id' : ''} FROM ${table(type)} c WHERE ${captureWhere(type, user, {}).where}
    AND c.id=ANY($2::uuid[]) FOR UPDATE OF c`, [user, ids]);
  if (!rows.rows.length) return 0;
  const selected = rows.rows.map(r => r.id);
  await db.query(`INSERT INTO capture_tombstone(user_id,record_type,id,platform,payload_sha256,sha256)
    SELECT user_id,$3,id,platform,payload_sha256,${type === 'page' ? 'sha256' : 'NULL'} FROM ${table(type)}
    WHERE user_id=$1 AND id=ANY($2::uuid[]) ON CONFLICT(user_id,record_type,id) DO NOTHING`, [user, selected, type]);
  await db.query(`DELETE FROM ${table(type)} WHERE user_id=$1 AND id=ANY($2::uuid[])`, [user, selected]);
  if (type === 'video') {
    const imageIds = [...new Set(rows.rows.flatMap(r => [r.first_image_id, r.last_image_id]).filter(Boolean))];
    // 仅清理这些已删访问的帧；共享给其他访问的资源仍保留。
    const orphan = await db.query(`SELECT p.id FROM page_capture p WHERE p.user_id=$1 AND p.id=ANY($2::uuid[])
      AND NOT EXISTS(SELECT 1 FROM video_visit v WHERE v.user_id=p.user_id AND (v.first_image_id=p.id OR v.last_image_id=p.id)) FOR UPDATE OF p`,
      [user, imageIds]);
    const removed = orphan.rows.map(r => r.id);
    await db.query(`INSERT INTO capture_tombstone(user_id,record_type,id,platform,payload_sha256,sha256)
      SELECT user_id,'page',id,platform,payload_sha256,sha256 FROM page_capture WHERE user_id=$1 AND id=ANY($2::uuid[])
      ON CONFLICT(user_id,record_type,id) DO NOTHING`, [user, removed]);
    await db.query('DELETE FROM page_capture WHERE user_id=$1 AND id=ANY($2::uuid[])', [user, removed]);
  }
  return selected.length;
}
type Target = { id: string; signature: string };
type Snapshot = { user: string; where: string; params: unknown[]; targets: Target[]; expires: number;
  processed: number; deleted: number; skipped: number; busy: boolean };
const signature = (type: CaptureType) => type === 'page'
  ? 'md5(jsonb_build_array(c.id,c.platform,c.kind,c.captured_at,c.width,c.height,c.sha256,c.payload_sha256,c.mime_type,c.received_at,c.title,c.note)::text)'
  : 'md5(to_jsonb(c)::text)';
const progress = (s: Snapshot) => ({ processed: s.processed, total: s.targets.length, deleted: s.deleted,
  skipped: s.skipped, done: s.processed === s.targets.length });

export function createCaptureManagementRouter(pool: pg.Pool, type: CaptureType): Router {
  const router = Router();
  const snapshots = new Map<string, Snapshot>();
  const expire = () => { for (const [token, s] of snapshots) if (!s.busy && s.expires < Date.now()) snapshots.delete(token); };
  router.patch('/:id', async (req, res, next) => {
    const b = req.body;
    if (!captureUuid.test(req.params.id) || !b || Array.isArray(b) || Object.keys(b).length !== 2 ||
      Object.keys(b).some(k => !['title', 'note'].includes(k)) || typeof b.title !== 'string' || typeof b.note !== 'string' ||
      b.title.length > 200 || b.note.length > 2000 || b.title.includes('\0') || b.note.includes('\0')) {
      res.status(400).json({ error: 'invalid metadata' }); return;
    }
    try {
      const scoped = captureWhere(type, res.locals.userId, {});
      const result = await pool.query(`UPDATE ${table(type)} c SET title=$2,note=$3 WHERE ${scoped.where} AND c.id=$4`,
        [res.locals.userId, b.title.trim(), b.note.trim(), req.params.id]);
      if (!result.rowCount) { res.sendStatus(404); return; }
      res.set('Cache-Control', 'no-store').json({ ok: true });
    } catch (error) { next(error); }
  });
  router.post('/delete', async (req, res, next) => {
    const b = req.body;
    if (b?.confirm !== 'DELETE' || !Array.isArray(b.ids) || !b.ids.length || b.ids.length > 200 ||
      b.ids.some((id: unknown) => typeof id !== 'string' || !captureUuid.test(id))) {
      res.status(400).json({ error: 'invalid delete confirmation' }); return;
    }
    let db: pg.PoolClient | undefined;
    try {
      db = await pool.connect(); await db.query('BEGIN'); await lockCaptureDevice(db, res.locals.userId);
      const deleted = await deleteRows(db, type, res.locals.userId, [...new Set<string>(b.ids.map((id: string) => id.toLowerCase()))]);
      await db.query('COMMIT'); res.set('Cache-Control', 'no-store').json({ deleted });
    } catch (error) { await db?.query('ROLLBACK').catch(() => {}); next(error); }
    finally { db?.release(); }
  });
  router.post('/cleanup/preview', async (req, res, next) => {
    try {
      const b = req.body;
      const allowed = ['days', 'platform', 'from', 'to', 'q', ...(type === 'page' ? ['kind'] : ['observation_kind', 'complete', 'exit_reason'])];
      if (!b || Array.isArray(b) || ![1, 7, 30].includes(b.days) || Object.keys(b).some(k => !allowed.includes(k)))
        throw new InvalidCaptureFilter('invalid cleanup filter');
      const scoped = captureWhere(type, res.locals.userId, b);
      const cutoff = new Date(Date.now() - b.days * 86400000);
      scoped.params.push(type === 'page' ? cutoff : cutoff.getTime());
      scoped.where += ` AND c.${timeColumn(type)}<$${scoped.params.length}`;
      expire();
      // 同一设备可替换尚未开始的预览；处理过的token保留供安全重试。
      for (const [token, s] of snapshots) if (s.user === res.locals.userId && !s.busy && s.processed === 0) snapshots.delete(token);
      if (snapshots.size >= 20) { res.status(429).json({ error: 'too many cleanup previews' }); return; }
      const rows = await pool.query<Target>(`SELECT c.id,${signature(type)} AS signature FROM ${table(type)} c
        WHERE ${scoped.where} ORDER BY c.${timeColumn(type)},c.id LIMIT ${MAX_TARGETS + 1}`, scoped.params);
      if (rows.rows.length > MAX_TARGETS) { res.status(400).json({ error: 'cleanup exceeds 50000 records' }); return; }
      const token = randomUUID();
      snapshots.set(token, { user: res.locals.userId, ...scoped, targets: rows.rows, expires: Date.now() + TTL,
        processed: 0, deleted: 0, skipped: 0, busy: false });
      res.set('Cache-Control', 'no-store').json({ token, total: rows.rows.length, cutoff: cutoff.toISOString() });
    } catch (error) { if (error instanceof InvalidCaptureFilter) res.status(400).json({ error: error.message }); else next(error); }
  });
  router.post('/cleanup/batch', async (req, res, next) => {
    const b = req.body;
    if (b?.confirm !== 'DELETE' || typeof b.token !== 'string' || !Number.isSafeInteger(b.offset) || b.offset < 0) {
      res.status(400).json({ error: 'invalid cleanup confirmation' }); return;
    }
    expire();
    const s = snapshots.get(b.token);
    if (!s || s.user !== res.locals.userId) { res.status(410).json({ error: 'cleanup preview expired' }); return; }
    if (s.busy || b.offset > s.processed) { res.status(409).json({ error: 'cleanup offset mismatch' }); return; }
    if (b.offset < s.processed || s.processed === s.targets.length) { res.json(progress(s)); return; }
    s.busy = true;
    let db: pg.PoolClient | undefined;
    try {
      db = await pool.connect(); await db.query('BEGIN'); await lockCaptureDevice(db, s.user);
      const targets = s.targets.slice(s.processed, s.processed + BATCH_SIZE);
      const rows = await db.query<Target>(`SELECT c.id,${signature(type)} AS signature FROM ${table(type)} c
        WHERE ${s.where} AND c.id=ANY($${s.params.length + 1}::uuid[]) FOR UPDATE OF c`, [...s.params, targets.map(t => t.id)]);
      const expected = new Map(targets.map(t => [t.id, t.signature]));
      const matched = rows.rows.filter(r => expected.get(r.id) === r.signature).map(r => r.id);
      const deleted = await deleteRows(db, type, s.user, matched);
      await db.query('COMMIT');
      s.processed += targets.length; s.deleted += deleted; s.skipped += targets.length - deleted; s.expires = Date.now() + TTL;
      res.set('Cache-Control', 'no-store').json(progress(s));
    } catch (error) { await db?.query('ROLLBACK').catch(() => {}); next(error); }
    finally { db?.release(); s.busy = false; }
  });
  return router;
}
