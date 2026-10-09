import { createHash } from 'node:crypto';
import { Router } from 'express';
import type pg from 'pg';
import { savingFlags } from '../lib/deviceSaving.js';

const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const FIELDS = ['id', 'platform', 'observation_kind', 'entered_at', 'ended_at', 'duration_ms', 'exit_reason', 'complete', 'first_image_id', 'last_image_id'];
const EXITS = ['page_changed', 'switched', 'exit', 'background', 'locked', 'interrupted'];
class InvalidVisit extends Error {}
type Visit = { id: string; platform: string; observation_kind: string; entered_at: number; ended_at: number | null; duration_ms: number | null;
  exit_reason: string; complete: boolean; first_image_id: string | null; last_image_id: string | null };
function parseVisit(body: unknown): Visit {
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw new InvalidVisit('invalid visit');
  const r = body as Record<string, unknown>;
  if (Object.keys(r).length !== FIELDS.length || Object.keys(r).some(k => !FIELDS.includes(k))) throw new InvalidVisit('invalid visit fields');
  const uuid = (v: unknown) => typeof v === 'string' && UUID.test(v);
  const time = (v: unknown) => typeof v === 'number' && Number.isSafeInteger(v) && v >= 946684800000 && v <= Date.now() + 300000;
  if (!uuid(r.id) || typeof r.platform !== 'string' || !['wechat', 'douyin'].includes(r.platform) || !time(r.entered_at) ||
    typeof r.observation_kind !== 'string' || !['confirmed_video', 'unconfirmed_feed'].includes(r.observation_kind) ||
    (r.ended_at !== null && !time(r.ended_at)) || typeof r.complete !== 'boolean' ||
    typeof r.exit_reason !== 'string' || !EXITS.includes(r.exit_reason) ||
    (r.first_image_id !== null && !uuid(r.first_image_id)) || (r.last_image_id !== null && !uuid(r.last_image_id)) ||
    (r.duration_ms !== null && (typeof r.duration_ms !== 'number' || !Number.isSafeInteger(r.duration_ms) || r.duration_ms < 0)))
    throw new InvalidVisit('invalid visit values');
  if (r.exit_reason === 'interrupted' ? r.complete || r.ended_at !== null || r.duration_ms !== null
    : r.ended_at === null || (r.complete ? r.duration_ms === null : r.duration_ms !== null)) throw new InvalidVisit('inconsistent visit completion');
  // 固定字段顺序生成载荷摘要；ACK保留客户端请求UUID大小写，避免非绑定回执。
  return Object.fromEntries(FIELDS.map(k => [k, r[k]])) as Visit;
}

export function createMobileVideoVisitsRouter(pool: pg.Pool): Router {
  const router = Router();
  router.post('/', async (req, res, next) => {
    try {
      const r = parseVisit(req.body), user = String(res.locals.userId).toLowerCase();
      const receipt = { ok: true, record: r };
      if ((await savingFlags(pool, [user])).get(user) === false) { res.json({ ...receipt, discarded: true }); return; }
      const digest = createHash('sha256').update(JSON.stringify(r)).digest('hex');
      const db = await pool.connect();
      let conflict = false, missing: string[] = [];
      try {
        await db.query('BEGIN');
        await db.query('INSERT INTO device(id) VALUES($1) ON CONFLICT(id) DO NOTHING', [user]);
        const imageIds = [...new Set([r.first_image_id, r.last_image_id].filter((x): x is string => x !== null))];
        if (imageIds.length) {
          const images = await db.query('SELECT id FROM page_capture WHERE user_id=$1 AND platform=$2 AND id=ANY($3::uuid[]) FOR KEY SHARE', [user, r.platform, imageIds]);
          const found = new Set(images.rows.map(x => x.id));
          missing = imageIds.filter(id => !found.has(id.toLowerCase()));
        }
        if (missing.length) await db.query('ROLLBACK');
        else {
          await db.query(`INSERT INTO video_visit(user_id,id,platform,entered_at,ended_at,duration_ms,exit_reason,complete,first_image_id,last_image_id,payload_sha256,observation_kind)
            VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12) ON CONFLICT(user_id,id) DO NOTHING`,
          [user,r.id,r.platform,r.entered_at,r.ended_at,r.duration_ms,r.exit_reason,r.complete,r.first_image_id,r.last_image_id,digest,r.observation_kind]);
          const saved = await db.query('SELECT payload_sha256 FROM video_visit WHERE user_id=$1 AND id=$2', [user,r.id]);
          if (!saved.rows[0]) throw Error('video visit was not stored');
          conflict = saved.rows[0].payload_sha256 !== digest;
          await db.query('COMMIT');
        }
      } catch (error) { await db.query('ROLLBACK').catch(() => {}); throw error; }
      finally { db.release(); }
      if (missing.length) { res.status(409).json({ error: 'missing_page_captures', missing_image_ids: missing }); return; }
      if (conflict) { res.status(409).json({ error: 'record id already has different content' }); return; }
      res.json(receipt);
    } catch (error) { if (error instanceof InvalidVisit) res.status(400).json({ error: error.message }); else next(error); }
  });
  return router;
}

export function createDashboardVideoVisitsRouter(pool: pg.Pool): Router {
  const router = Router();
  router.get('/', async (req, res, next) => {
    try {
      const pageText = req.query.page ?? '1', platform = req.query.platform;
      if (typeof pageText !== 'string' || !/^[1-9]\d{0,5}$/.test(pageText) ||
        (platform !== undefined && (typeof platform !== 'string' || !['wechat','douyin'].includes(platform)))) {
        res.status(400).json({ error: 'invalid visit filter' }); return;
      }
      const page = Number(pageText), params: unknown[] = [res.locals.userId];
      let where = 'user_id=$1';
      if (platform !== undefined) { params.push(platform); where += ' AND platform=$2'; }
      const total = await pool.query(`SELECT COUNT(*) AS n FROM video_visit WHERE ${where}`, params);
      const rows = await pool.query(`SELECT id,platform,observation_kind,entered_at,ended_at,duration_ms,exit_reason,complete,first_image_id,last_image_id,received_at
        FROM video_visit WHERE ${where} ORDER BY entered_at DESC,id DESC LIMIT 20 OFFSET $${params.length+1}`, [...params,(page-1)*20]);
      const records = rows.rows.map(r => ({ ...r, entered_at: Number(r.entered_at),
        ended_at: r.ended_at === null ? null : Number(r.ended_at), duration_ms: r.duration_ms === null ? null : Number(r.duration_ms) }));
      res.set('Cache-Control','no-store').json({ total: Number(total.rows[0].n),page,page_size:20,records });
    } catch (error) { next(error); }
  });
  return router;
}
