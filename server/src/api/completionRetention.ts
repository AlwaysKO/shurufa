import { randomUUID } from 'node:crypto';
import { Router } from 'express';
import type pg from 'pg';

const TTL = 15 * 60_000;
const MAX_RECORDS = 50_000;
type Member = { id: string; kind: 'candidate' | 'phrase'; key: string; signature: string; at: Date };
type Job = {
  user: string; groups: Member[][]; total: number; index: number; processed: number;
  deleted: number; skipped: number; expires: number; busy: boolean;
};
// Keep all prefixes and their learning source together so a surviving source cannot
// immediately regenerate the candidates removed by this operation.
const members = `WITH members AS (
  SELECT id::text,'candidate'::text AS kind,jsonb_build_array(completion,package_name)::text AS key,
    md5(row_to_json(c)::text) AS signature,COALESCE(last_used_at,created_at) AS at
  FROM completion_candidate c WHERE user_id=$1
  UNION ALL
  SELECT id::text,'phrase'::text AS kind,jsonb_build_array(phrase,package_name)::text AS key,
    md5(row_to_json(p)::text) AS signature,last_used_at AS at
  FROM phrase_stat p WHERE user_id=$1
)`;
function groupMembers(rows: Member[]) {
  const groups = new Map<string, Member[]>();
  for (const row of rows) {
    const group = groups.get(row.key) ?? [];
    group.push(row); groups.set(row.key, group);
  }
  return [...groups.values()];
}
const fingerprint = (rows: Member[]) => JSON.stringify(rows.map(row => [row.kind, row.id, row.signature]).sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b))));
const progress = (job: Job) => ({ processed: job.processed, total: job.total,
  deleted_records: job.deleted, skipped_records: job.skipped, done: job.index === job.groups.length, files_pending: false });

export function createCompletionRetentionRouter(pool: pg.Pool): Router {
  const router = Router(), snapshots = new Map<string, Job>();
  const expire = () => { for (const [token, job] of snapshots) if (!job.busy && job.expires <= Date.now()) snapshots.delete(token); };
  router.post('/preview', async (req, res, next) => {
    const body = req.body;
    if (![1, 7, 30].includes(body?.days) || !body?.filters || typeof body.filters !== 'object' || Array.isArray(body.filters) || Object.keys(body.filters).length) {
      res.status(400).json({ error: '保留天数或筛选条件无效' }); return;
    }
    expire();
    if (snapshots.size >= 20) { res.status(429).json({ error: '清理预览较多，请稍后重试' }); return; }
    const cutoff = new Date(Date.now() - body.days * 86_400_000);
    try {
      const result = await pool.query<Member>(`${members}, eligible AS (
        SELECT key FROM members GROUP BY key HAVING bool_and(at IS NOT NULL AND at<$2)
      ) SELECT members.* FROM members JOIN eligible USING(key) ORDER BY at,kind,id LIMIT ${MAX_RECORDS + 1}`,
      [res.locals.userId, cutoff]);
      if (result.rows.length > MAX_RECORDS) { res.status(400).json({ error: '待清理记录超过5万条，请选择更长的保留天数' }); return; }
      const groups = groupMembers(result.rows), token = randomUUID();
      for (const [key, job] of snapshots) if (job.user === res.locals.userId && !job.busy && job.processed === 0) snapshots.delete(key);
      if (groups.length) snapshots.set(token, { user: res.locals.userId, groups, total: result.rows.length,
        index: 0, processed: 0, deleted: 0, skipped: 0, expires: Date.now() + TTL, busy: false });
      res.json({ token, cutoff: cutoff.toISOString(), total_records: result.rows.length, total_files: 0,
        first_at: result.rows[0]?.at ?? null, last_at: result.rows.at(-1)?.at ?? null });
    } catch (error) { next(error); }
  });
  router.post('/batch', async (req, res, next) => {
    const body = req.body;
    if (body?.confirm !== 'DELETE' || typeof body?.token !== 'string' || !Number.isSafeInteger(body?.offset) || body.offset < 0) {
      res.status(400).json({ error: '清理确认参数无效' }); return;
    }
    expire(); const job = snapshots.get(body.token);
    if (!job || job.user !== res.locals.userId) { res.status(410).json({ error: '清理预览已失效，请重新预览' }); return; }
    if (job.busy || body.offset > job.processed) { res.status(409).json({ error: '清理批次正在处理或进度不一致' }); return; }
    if (body.offset < job.processed || job.index === job.groups.length) { res.json(progress(job)); return; }
    job.busy = true;
    const groups: Member[][] = []; let count = 0;
    for (let i = job.index; i < job.groups.length; i++) {
      const group = job.groups[i];
      if (count && count + group.length > 200) break;
      groups.push(group); count += group.length;
      if (count >= 200) break;
    }
    try {
      const db = await pool.connect(); let deleted = 0;
      try {
        await db.query('BEGIN');
        await db.query("SET LOCAL lock_timeout='5s'"); await db.query("SET LOCAL statement_timeout='20s'");
        await db.query("SELECT pg_advisory_xact_lock(hashtextextended('completion-retention:' || $1,0))", [job.user.toLowerCase()]);
        await db.query('LOCK TABLE completion_candidate,phrase_stat IN SHARE ROW EXCLUSIVE MODE');
        const result = await db.query<Member>(`${members} SELECT * FROM members WHERE key=ANY($2::text[])`, [job.user, groups.map(group => group[0].key)]);
        const current = new Map(groupMembers(result.rows).map(group => [group[0].key, group]));
        const matching = groups.filter(group => fingerprint(group) === fingerprint(current.get(group[0].key) ?? [])).flat();
        const candidateIds = matching.filter(row => row.kind === 'candidate').map(row => row.id);
        const phraseIds = matching.filter(row => row.kind === 'phrase').map(row => row.id);
        const candidates = await db.query('DELETE FROM completion_candidate WHERE user_id=$1 AND id=ANY($2::bigint[])', [job.user, candidateIds]);
        const phrases = await db.query('DELETE FROM phrase_stat WHERE user_id=$1 AND id=ANY($2::bigint[])', [job.user, phraseIds]);
        deleted = (candidates.rowCount ?? 0) + (phrases.rowCount ?? 0);
        await db.query('COMMIT');
      } catch (error) { await db.query('ROLLBACK').catch(() => {}); throw error; }
      finally { db.release(); }
      job.index += groups.length; job.processed += count; job.deleted += deleted; job.skipped += count - deleted; job.expires = Date.now() + TTL;
      res.json(progress(job));
    } catch (error) { next(error); }
    finally { job.busy = false; }
  });
  return router;
}
