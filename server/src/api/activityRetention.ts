import { randomUUID } from 'node:crypto';
import { Router } from 'express';
import type pg from 'pg';
import { activityConditions } from './activityQuery.js';
import { GROUP_KEY } from './groupedEdits.js';

const TTL = 15 * 60_000;
const MAX_EVENTS = 50_000;
type Target = { id: string; key: string; signature: string; occurred_at: Date };
type Job = {
  user: string; grouped: boolean; groups: Target[][]; total: number; index: number;
  processed: number; deleted: number; skipped: number; expires: number; busy: boolean;
};
function validFilters(value: unknown): value is Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  return Object.entries(value).every(([key, val]) => {
    if (['all', 'grouped'].includes(key)) return typeof val === 'boolean';
    if (key === 'days') return typeof val === 'number' && Number.isFinite(val) && val >= 1 && val <= 3650;
    if (key === 'type') return ['all','text','delete','paste','voice','image'].includes(val as string);
    if (typeof val !== 'string' || !val.length || val.length > 2000) return false;
    if (key === 'from' || key === 'to') return Number.isFinite(Date.parse(val));
    if (key === 'device_id') return /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(val);
    return key === 'package_name' || key === 'q';
  });
}
const progress = (job: Job) => ({ processed: job.processed, total: job.total,
  deleted_events: job.deleted, skipped_events: job.skipped, done: job.index === job.groups.length });
function groupTargets(rows: Target[]) {
  const groups = new Map<string, Target[]>();
  for (const row of rows) { const group = groups.get(row.key) ?? []; group.push(row); groups.set(row.key, group); }
  return [...groups.values()];
}
const fingerprint = (rows: Target[]) => JSON.stringify(rows.map(row => [row.id,row.signature]).sort((a,b)=>a[0].localeCompare(b[0])));

export function createActivityRetentionRouter(pool: pg.Pool): Router {
  const router = Router(), snapshots = new Map<string, Job>();
  const expire = () => { for (const [token,job] of snapshots) if (!job.busy && job.expires < Date.now()) snapshots.delete(token); };
  router.post('/events/cleanup/preview', async (req,res,next) => {
    const body = req.body;
    if (![1,7,30].includes(body?.days) || !validFilters(body?.filters)) {
      res.status(400).json({ error: '保留天数或筛选条件无效' }); return;
    }
    expire();
    if (snapshots.size >= 20) { res.status(429).json({ error: '清理预览较多，请稍后重试' }); return; }
    const cutoff = new Date(Date.now() - body.days * 86_400_000);
    const filters = body.filters, grouped = filters.grouped === true && filters.all !== true;
    const { where, params } = activityConditions(res.locals.userId, { ...filters, all: filters.all ? '1' : '0' });
    const cutoffParam = `$${params.length + 1}`;
    try {
      const sql = grouped ? `WITH scoped AS NOT MATERIALIZED (
        SELECT input_event.*,${GROUP_KEY} AS edit_key,md5(row_to_json(input_event)::text) AS signature
        FROM input_event WHERE user_id=$1
      ), matched AS (SELECT DISTINCT edit_key FROM scoped WHERE ${where}), eligible AS (
        SELECT edit_key FROM scoped JOIN matched USING(edit_key) GROUP BY edit_key HAVING MAX(occurred_at)<${cutoffParam}
      ) SELECT id,edit_key::text AS key,signature,occurred_at FROM scoped JOIN eligible USING(edit_key)
        ORDER BY occurred_at,id LIMIT ${MAX_EVENTS + 1}`
        : `SELECT id,id::text AS key,md5(row_to_json(input_event)::text) AS signature,occurred_at
          FROM input_event WHERE ${where} AND occurred_at<${cutoffParam} ORDER BY occurred_at,id LIMIT ${MAX_EVENTS + 1}`;
      const result = await pool.query<Target>(sql, [...params,cutoff]);
      if (result.rows.length > MAX_EVENTS) { res.status(400).json({ error: '待清理记录超过5万条，请缩小筛选范围' }); return; }
      const groups = groupTargets(result.rows), token = randomUUID();
      for (const [key,job] of snapshots) if (job.user === res.locals.userId && !job.busy && job.processed === 0) snapshots.delete(key);
      if (groups.length) snapshots.set(token, { user: res.locals.userId, grouped, groups, total: result.rows.length,
        index: 0, processed: 0, deleted: 0, skipped: 0, expires: Date.now()+TTL, busy: false });
      res.json({ token, cutoff: cutoff.toISOString(), total_events: result.rows.length, total_groups: groups.length,
        first_occurred_at: result.rows[0]?.occurred_at ?? null, last_occurred_at: result.rows.at(-1)?.occurred_at ?? null });
    } catch(error) { next(error); }
  });
  router.post('/events/cleanup/batch', async (req,res,next) => {
    const body = req.body;
    if (body?.confirm !== 'DELETE' || typeof body?.token !== 'string' || !Number.isSafeInteger(body?.offset) || body.offset < 0) {
      res.status(400).json({ error: '清理确认参数无效' }); return;
    }
    expire(); const job = snapshots.get(body.token);
    if (!job || job.user !== res.locals.userId) { res.status(410).json({ error: '清理预览已失效，请重新预览' }); return; }
    if (job.busy || body.offset > job.processed) { res.status(409).json({ error: '清理批次正在处理或进度不一致' }); return; }
    if (body.offset < job.processed || job.index === job.groups.length) { res.json(progress(job)); return; }
    job.busy = true;
    const groups: Target[][] = []; let count = 0;
    for (let i=job.index; i<job.groups.length; i++) {
      const group=job.groups[i];
      if (count && count+group.length>200) break;
      groups.push(group); count+=group.length;
      if (count>=200) break;
    }
    try {
      const db = await pool.connect(); let deleted = 0;
      try {
        await db.query('BEGIN');
        await db.query("SET LOCAL lock_timeout='5s'"); await db.query("SET LOCAL statement_timeout='20s'");
        await db.query('LOCK TABLE input_event IN SHARE ROW EXCLUSIVE MODE');
        const key = job.grouped ? `(${GROUP_KEY})::text` : 'id::text';
        const match = job.grouped ? `${key}=ANY($2::text[])` : 'id=ANY($2::uuid[])';
        const result = await db.query<Target>(`SELECT id,${key} AS key,md5(row_to_json(input_event)::text) AS signature,occurred_at
          FROM input_event WHERE user_id=$1 AND ${match}`,[job.user,groups.map(group=>group[0].key)]);
        const current = new Map(groupTargets(result.rows).map(group=>[group[0].key,group]));
        const ids = groups.filter(group=>fingerprint(group)===fingerprint(current.get(group[0].key) ?? [])).flatMap(group=>group.map(row=>row.id));
        const removed = await db.query('DELETE FROM input_event WHERE user_id=$1 AND id=ANY($2::uuid[])',[job.user,ids]);
        deleted = removed.rowCount ?? 0;
        await db.query('COMMIT');
      } catch(error) { await db.query('ROLLBACK').catch(()=>{}); throw error; }
      finally { db.release(); }
      job.index += groups.length; job.processed += count; job.deleted += deleted; job.skipped += count-deleted; job.expires=Date.now()+TTL;
      res.json(progress(job));
    } catch(error) { next(error); }
    finally { job.busy=false; }
  });
  return router;
}
