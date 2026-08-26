import { Router } from 'express';
import type express from 'express';
import type pg from 'pg';
import { EVENT_TYPES, type DeviceInfo, type MobileEvent, type SessionInfo } from '../types/events.js';
import { locationKey } from '../lib/geocoder.js';


/** 批量插入事件（幂等：冲突跳过），返回实际插入数 */
async function insertEvents(pool: pg.Pool, userId: string, events: MobileEvent[], clientIp?: string): Promise<number> {
  if (events.length === 0) return 0;
  const values: unknown[] = [];
  const params: string[] = [];
  events.forEach((e, i) => {
    const n = i * 17;
    params.push(
      `($${n + 1},$${n + 2},$${n + 3},$${n + 4},$${n + 5},$${n + 6},$${n + 7},$${n + 8},$${n + 9},$${n + 10},$${n + 11},$${n + 12},$${n + 13},$${n + 14},$${n + 15},$${n + 16},$${n + 17})`,
    );
    values.push(
      e.id,
      userId,
      e.device_id,
      e.session_id ?? null,
      e.sequence_no ?? null,
      e.occurred_at,
      e.package_name ?? null,
      e.editor_id ?? null,
      e.event_type,
      e.source ?? null,
      e.source_confidence ?? null,
      e.text ?? null,
      e.input_code ?? null,
      e.clipboard_id ?? null,
      JSON.stringify(e.metadata ?? {}),
      clientIp ?? null,
      e.network_type ?? null,
    );
  });
  const sql = `
    INSERT INTO input_event
      (id, user_id, device_id, session_id, sequence_no, occurred_at,
       package_name, editor_id, event_type, source, source_confidence,
       text, input_code, clipboard_id, metadata, client_ip, network_type)
    VALUES ${params.join(',')}
    ON CONFLICT (id) DO NOTHING`;
  const result = await pool.query(sql, values);
  return result.rowCount ?? 0;
}

/** 取请求方 IP：优先 X-Forwarded-For（nginx 反代场景），其次 socket 地址 */
function requestIp(req: express.Request): string {
  const xff = req.headers['x-forwarded-for'];
  if (typeof xff === 'string' && xff.trim()) return xff.split(',')[0].trim();
  return req.ip ?? '';
}

export function createMobileRouter(pool: pg.Pool): Router {
  const router = Router();

  /** 注册/更新设备 */
  router.post('/device', async (req, res, next) => {
    try {
      const info = req.body as DeviceInfo;
      if (!info?.id) return res.status(400).json({ error: 'device_id required' });
      await pool.query(
        `INSERT INTO device
           (id, name, platform, model, os_version, app_version,
            brand, sdk_int, screen_resolution, locale, region, hardware, rom_version, ram_mb)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14)
         ON CONFLICT (id) DO UPDATE SET
           name = COALESCE(EXCLUDED.name, device.name),
           platform = COALESCE(EXCLUDED.platform, device.platform),
           model = COALESCE(EXCLUDED.model, device.model),
           os_version = COALESCE(EXCLUDED.os_version, device.os_version),
           app_version = COALESCE(EXCLUDED.app_version, device.app_version),
           brand = COALESCE(EXCLUDED.brand, device.brand),
           sdk_int = COALESCE(EXCLUDED.sdk_int, device.sdk_int),
           screen_resolution = COALESCE(EXCLUDED.screen_resolution, device.screen_resolution),
           locale = COALESCE(EXCLUDED.locale, device.locale),
           region = COALESCE(EXCLUDED.region, device.region),
           hardware = COALESCE(EXCLUDED.hardware, device.hardware),
           rom_version = COALESCE(EXCLUDED.rom_version, device.rom_version),
           ram_mb = COALESCE(EXCLUDED.ram_mb, device.ram_mb),
           last_seen_at = NOW()`,
        [
          info.id,
          info.name ?? null,
          info.platform ?? 'android',
          info.model ?? null,
          info.os_version ?? null,
          info.app_version ?? null,
          info.brand ?? null,
          info.sdk_int ?? null,
          info.screen_resolution ?? null,
          info.locale ?? null,
          info.region ?? null,
          info.hardware ?? null,
          info.rom_version ?? null,
          info.ram_mb ?? null,
        ],
      );
      res.json({ ok: true });
    } catch (err) {
      next(err);
    }
  });

  /** 结束会话（更新结束时间和事件数） */
  router.post('/session', async (req, res, next) => {
    try {
      const info = req.body as SessionInfo;
      if (!info?.id || !info?.device_id) return res.status(400).json({ error: 'session_id and device_id required' });
      const existing = await pool.query<{ device_id: string }>(
        'SELECT device_id FROM input_session WHERE id = $1',
        [info.id],
      );
      if (existing.rows[0] && existing.rows[0].device_id !== info.device_id) {
        return res.status(409).json({ error: 'session belongs to another device' });
      }
      const startedAt = info.started_at ?? new Date().toISOString();
      const result = await pool.query(
        `INSERT INTO input_session (id, device_id, started_at, ended_at, package_name, editor_id, event_count)
         VALUES ($1,$2,$3,$4,$5,$6,$7)
         ON CONFLICT (id) DO UPDATE SET
           ended_at = EXCLUDED.ended_at,
           event_count = GREATEST(input_session.event_count, EXCLUDED.event_count)
         WHERE input_session.device_id = EXCLUDED.device_id
         RETURNING id`,
        [info.id, info.device_id, startedAt, info.ended_at ?? null, info.package_name ?? null, info.editor_id ?? null, info.event_count ?? 0],
      );
      if (result.rowCount === 0) return res.status(409).json({ error: 'session belongs to another device' });
      res.json({ ok: true });
    } catch (err) {
      next(err);
    }
  });

  /** 批量上传输入事件（核心接口，幂等） */
  router.post('/events/batch', async (req, res, next) => {
    try {
      const body = req.body as { device_id?: string; events?: MobileEvent[] };
      const events = Array.isArray(body?.events) ? body.events : [];
      if (events.length === 0) return res.json({ ok: true, inserted: 0 });
      if (events.length > 500) return res.status(400).json({ error: 'batch too large (max 500)' });

      // 只接受已知事件类型，防止脏数据
      const valid = events.filter((e) => e?.id && EVENT_TYPES.includes(e.event_type));
      if (valid.length === 0) return res.json({ ok: true, inserted: 0 });
      if (valid.some((event) => event.device_id !== res.locals.userId)) {
        return res.status(400).json({ error: 'device_id mismatch' });
      }

      const inserted = await insertEvents(pool, res.locals.userId, valid, requestIp(req));

      // 顺手更新设备最近活跃时间（不阻塞主流程）
      await pool.query('UPDATE device SET last_seen_at = NOW() WHERE id = $1', [body.device_id ?? valid[0].device_id]).catch(() => {});

      res.json({ ok: true, inserted, received: valid.length });
    } catch (err) {
      next(err);
    }
  });

  /**
   * 位置上报（Android 端每分钟调用一次）：
   * 坐标去重（round 4 位 ≈ 11 米）——同位置仅更新 last_seen_at，有变化才新增记录。
   */
  router.post('/location', async (req, res, next) => {
    try {
      const body = req.body as {
        device_id?: string;
        latitude?: number;
        longitude?: number;
        accuracy?: number;
        provider?: string;
        speed?: number;
        occurred_at?: string;
      };
      if (!body?.device_id || body.latitude == null || body.longitude == null) {
        return res.status(400).json({ error: 'device_id, latitude, longitude required' });
      }
      const lat = Number(body.latitude);
      const lng = Number(body.longitude);
      if (Number.isNaN(lat) || Number.isNaN(lng) || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
        return res.status(400).json({ error: 'invalid coordinates' });
      }
      const occurredAt = body.occurred_at ?? new Date().toISOString();

      // 查该设备最新一条位置记录，同坐标（4 位精度）只更新时间不新增
      const last = await pool.query(
        `SELECT latitude, longitude FROM location_track
         WHERE user_id = $1 AND device_id = $2
         ORDER BY occurred_at DESC LIMIT 1`,
        [res.locals.userId, body.device_id],
      );
      const prev = last.rows[0] as { latitude: string; longitude: string } | undefined;
      if (prev && locationKey(Number(prev.latitude), Number(prev.longitude)) === locationKey(lat, lng)) {
        await pool.query(
          `UPDATE location_track SET last_seen_at = NOW()
           WHERE user_id = $1 AND device_id = $2 AND latitude = $3 AND longitude = $4`,
          [res.locals.userId, body.device_id, prev.latitude, prev.longitude],
        );
        return res.json({ ok: true, recorded: 'same' });
      }

      await pool.query(
        `INSERT INTO location_track
           (user_id, device_id, latitude, longitude, accuracy, provider, speed, occurred_at)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8)`,
        [
          res.locals.userId,
          body.device_id,
          lat,
          lng,
          body.accuracy ?? null,
          body.provider ?? null,
          body.speed ?? null,
          occurredAt,
        ],
      );
      res.json({ ok: true, recorded: 'new' });
    } catch (err) {
      next(err);
    }
  });

  /** 补全模型增量同步：?since=<version> 返回 version 之后的新增/变更/删除 */
  router.get('/completions', async (req, res, next) => {
    try {
      const since = Number(req.query.since ?? 0) || 0;
      const limit = Math.min(Number(req.query.limit ?? 2000) || 2000, 10000);

      const result = await pool.query(
        `SELECT id, prefix, prefix_pinyin, prefix_initials, completion, package_name,
                use_count, score, accept_count, version, last_used_at
         FROM completion_candidate
         WHERE user_id = $1 AND version > $2
         ORDER BY version ASC
         LIMIT $3`,
        [res.locals.userId, since, limit],
      );
      const rows = result.rows as Array<{
        id: number;
        version: number;
      }>;

      // 最新版本号（供客户端下次增量）
      const latest = await pool.query(
        `SELECT COALESCE(MAX(version), 0) AS version FROM completion_candidate WHERE user_id = $1`,
        [res.locals.userId],
      );

      res.json({
        version: Number(latest.rows[0]?.version ?? 0),
        has_more: rows.length >= limit,
        candidates: rows,
      });
    } catch (err) {
      next(err);
    }
  });

  /** 汇报补全候选被展示/被接受（用于统计接受率） */
  router.post('/completions/feedback', async (req, res, next) => {
    try {
      const body = req.body as { completion?: string; prefix?: string; package_name?: string; accepted: boolean };
      if (!body?.completion) return res.status(400).json({ error: 'completion required' });
      const col = body.accepted ? 'accept_count' : 'show_count';
      await pool.query(
        `UPDATE completion_candidate
         SET ${col} = ${col} + 1, last_used_at = NOW()
         WHERE user_id = $1 AND completion = $2 AND ($3::text IS NULL OR package_name = $3)`,
        [res.locals.userId, body.completion, body.package_name ?? null],
      );
      res.json({ ok: true });
    } catch (err) {
      next(err);
    }
  });

  return router;
}
