import { Router } from 'express';
import type pg from 'pg';
import { createHash } from 'node:crypto';

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const text = (value: unknown, max = 500): value is string =>
  typeof value === 'string' && value.trim().length > 0 && value.length <= max;
const finite = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value);

function validPayload(kind: unknown, p: Record<string, unknown>): boolean {
  switch (kind) {
    case 'location':
      return finite(p.latitude) && Math.abs(p.latitude) <= 90 &&
        finite(p.longitude) && Math.abs(p.longitude) <= 180 &&
        text(p.occurred_at, 64) && Number.isFinite(Date.parse(p.occurred_at)) &&
        (p.accuracy == null || (finite(p.accuracy) && p.accuracy >= 0)) &&
        (p.speed == null || finite(p.speed)) && (p.provider == null || text(p.provider, 64));
    case 'completion_feedback':
      return text(p.completion) && text(p.prefix) && typeof p.accepted === 'boolean';
    case 'phrase_upsert':
    case 'phrase_use':
      return text(p.content);
    case 'personal_choice':
      return text(p.code, 30) && /^(?:[a-z]{2,30}|[2-9]{1,30})$/.test(p.code) &&
        text(p.text, 30) && /^[\u4e00-\u9fff]+$/.test(p.text) &&
        finite(p.count) && Number.isSafeInteger(p.count) && p.count > 0 &&
        finite(p.weight) && p.weight > 0 && finite(p.last_used) &&
        Number.isSafeInteger(p.last_used) && p.last_used > 0;
    case 'sticker_use':
      return text(p.file_name, 255);
    default:
      return false;
  }
}

/** Stable receipts and their effects commit together, including concurrent retries. */
export function createMobileReportRouter(pool: pg.Pool): Router {
  const router = Router();
  router.post('/reports', async (req, res, next) => {
    const { id, kind, payload: p } = req.body ?? {};
    if (typeof id !== 'string' || !uuidPattern.test(id) || !p || typeof p !== 'object' || Array.isArray(p)) {
      return res.status(400).json({ error: 'invalid report' });
    }
    if (!validPayload(kind, p)) return res.status(400).json({ error: 'invalid report payload' });
    if (p.device_id && p.device_id !== res.locals.userId) return res.status(400).json({ error: 'device_id mismatch' });

    const hash = createHash('sha256').update(JSON.stringify({ kind, payload: p })).digest('hex');
    const user = res.locals.userId;
    let client: pg.PoolClient | undefined;
    try {
      client = await pool.connect();
      await client.query('BEGIN');
      const receipt = await client.query(
        `INSERT INTO mobile_report_receipt(user_id,report_id,payload_hash) VALUES($1,$2,$3)
         ON CONFLICT DO NOTHING RETURNING report_id`, [user, id, hash],
      );
      if (!receipt.rowCount) {
        const previous = await client.query(
          'SELECT payload_hash FROM mobile_report_receipt WHERE user_id=$1 AND report_id=$2', [user, id],
        );
        await client.query('ROLLBACK');
        if (previous.rows[0]?.payload_hash !== hash) return res.status(409).json({ error: 'report id conflict' });
        return res.json({ ok: true, id });
      }

      switch (kind) {
        case 'location':
          await client.query(
            `INSERT INTO location_track(user_id,device_id,latitude,longitude,accuracy,provider,speed,occurred_at)
             VALUES($1,$1,$2,$3,$4,$5,$6,$7)`,
            [user, p.latitude, p.longitude, p.accuracy ?? null, p.provider ?? null, p.speed ?? null, p.occurred_at],
          );
          break;
        case 'completion_feedback': {
          // Persist even when this destination has not generated the cloud candidate yet.
          await client.query(
            `INSERT INTO completion_feedback_usage(user_id,prefix,completion,accept_count,show_count) VALUES($1,$2,$3,$4,$5)
             ON CONFLICT(user_id,prefix,completion) DO UPDATE SET
               accept_count=completion_feedback_usage.accept_count+EXCLUDED.accept_count,
               show_count=completion_feedback_usage.show_count+EXCLUDED.show_count`,
            [user, p.prefix, p.completion, p.accepted ? 1 : 0, p.accepted ? 0 : 1],
          );
          const column = p.accepted ? 'accept_count' : 'show_count';
          await client.query(
            `UPDATE completion_candidate SET ${column}=${column}+1,last_used_at=NOW()
             WHERE user_id=$1 AND prefix=$2 AND completion=$3`, [user, p.prefix, p.completion],
          );
          break;
        }
        case 'phrase_upsert':
        case 'phrase_use':
          await client.query(
            `INSERT INTO user_phrase(user_id,content,use_count) VALUES($1,$2,$3)
             ON CONFLICT(user_id,content) DO UPDATE SET
               use_count=user_phrase.use_count+EXCLUDED.use_count,updated_at=NOW()`,
            [user, p.content.trim(), kind === 'phrase_use' ? 1 : 0],
          );
          break;
        case 'personal_choice':
          await client.query(
            `INSERT INTO personal_candidate_usage(user_id,code,text,count,weight,last_used) VALUES($1,$2,$3,$4,$5,$6)
             ON CONFLICT(user_id,code,text) DO UPDATE SET
               count=EXCLUDED.count,weight=EXCLUDED.weight,last_used=EXCLUDED.last_used
             WHERE EXCLUDED.count > personal_candidate_usage.count
               OR (EXCLUDED.count = personal_candidate_usage.count AND EXCLUDED.last_used >= personal_candidate_usage.last_used)`,
            [user, p.code, p.text, p.count, p.weight, p.last_used],
          );
          break;
        case 'sticker_use':
          await client.query(
            `INSERT INTO sticker_file_usage(user_id,file_name,use_count) VALUES($1,$2,1)
             ON CONFLICT(user_id,file_name) DO UPDATE SET use_count=sticker_file_usage.use_count+1`, [user, p.file_name],
          );
          await client.query('UPDATE sticker SET use_count=use_count+1 WHERE user_id=$1 AND file_name=$2', [user, p.file_name]);
          break;
      }
      await client.query('COMMIT');
      res.json({ ok: true, id });
    } catch (error) {
      if (client) await client.query('ROLLBACK').catch(() => {});
      next(error);
    } finally {
      client?.release();
    }
  });
  return router;
}
