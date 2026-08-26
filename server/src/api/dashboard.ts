import { Router } from 'express';
import type pg from 'pg';
import { resolveMissingIps } from '../lib/ipgeo.js';
import { resolveMissingAddresses } from '../lib/geocoder.js';


/** 事件内容类型：语音 / 图片 / 文字（用于列表展示与筛选） */
const CONTENT_TYPE_SQL = `CASE
  WHEN event_type = 'voice' THEN 'voice'
  WHEN (metadata->>'media_type') IN ('image','picture')
       OR metadata ? 'image_uri' OR metadata ? 'image_url'
       OR (event_type IN ('paste','paste_inferred','clipboard_change') AND (text IS NULL OR text = '')) THEN 'image'
  ELSE 'text'
END`;

/** 行为事件（列表默认展示：输入/粘贴/复制/语音等有内容的行为，不含 key/compose 等底层事件） */
const BEHAVIOR_TYPES = "('commit','candidate_commit','paste','paste_inferred','external_insert','clipboard_change','voice')";

function daysAgo(days: number): Date {
  return new Date(Date.now() - days * 86_400_000);
}

export function createDashboardRouter(pool: pg.Pool): Router {
  const router = Router();

  /** 输入总览：今日/近7天/累计统计，输入方式占比，删除修改次数 */
  router.get('/overview', async (req, res, next) => {
    try {
      const days = Math.min(Number(req.query.days ?? 7) || 7, 365);

      const today = await pool.query(
        `SELECT
           COUNT(*) FILTER (WHERE event_type IN ('commit','candidate_commit','paste','paste_inferred','external_insert','voice')) AS input_events,
           COALESCE(SUM(length(text)) FILTER (WHERE event_type IN ('commit','candidate_commit','paste','paste_inferred','external_insert','voice')), 0)::bigint AS input_chars,
           COUNT(*) FILTER (WHERE event_type = 'clipboard_change') AS clipboard_count,
           COUNT(*) FILTER (WHERE event_type = 'delete') AS delete_count
         FROM input_event
         WHERE user_id = $1 AND occurred_at >= date_trunc('day', NOW())`,
        [res.locals.userId],
      );

      const period = await pool.query(
        `SELECT
           COUNT(*) AS event_count,
           COALESCE(SUM(length(text)) FILTER (WHERE event_type IN ('commit','candidate_commit','paste','paste_inferred','external_insert','voice')), 0)::bigint AS input_chars,
           COUNT(DISTINCT date(occurred_at)) AS active_days
         FROM input_event
         WHERE user_id = $1 AND occurred_at >= $2`,
        [res.locals.userId, daysAgo(days)],
      );

      // 输入方式占比（近 N 天）
      const sources = await pool.query(
        `SELECT
           COALESCE(SUM(length(text)) FILTER (WHERE event_type IN ('commit','candidate_commit')), 0)::bigint AS typed,
           COALESCE(SUM(length(text)) FILTER (WHERE event_type IN ('paste','paste_inferred')), 0)::bigint AS pasted,
           COALESCE(SUM(length(text)) FILTER (WHERE event_type = 'external_insert'), 0)::bigint AS external_inserted,
           COALESCE(SUM(length(text)) FILTER (WHERE event_type = 'voice'), 0)::bigint AS voiced
         FROM input_event
         WHERE user_id = $1 AND occurred_at >= $2`,
        [res.locals.userId, daysAgo(days)],
      );

      const total = await pool.query(
        `SELECT COALESCE(SUM(length(text)) FILTER (WHERE event_type IN ('commit','candidate_commit','paste','paste_inferred','external_insert','voice')), 0)::bigint AS total_chars
         FROM input_event WHERE user_id = $1`,
        [res.locals.userId],
      );

      const s = sources.rows[0];
      const totalChars = Number(s.typed) + Number(s.pasted) + Number(s.external_inserted) + Number(s.voiced);

      res.json({
        days,
        today: today.rows[0],
        period: period.rows[0],
        total_chars: total.rows[0].total_chars,
        source_distribution: {
          typed: Number(s.typed),
          pasted: Number(s.pasted),
          external: Number(s.external_inserted),
          voice: Number(s.voiced),
          total: totalChars,
        },
      });
    } catch (err) {
      next(err);
    }
  });

  /** 时间线：按天输入量（字符数/事件数） */
  router.get('/timeline', async (req, res, next) => {
    try {
      const days = Math.min(Number(req.query.days ?? 30) || 30, 365);
      const result = await pool.query(
        `SELECT
           date(occurred_at) AS day,
           COUNT(*) AS event_count,
           COALESCE(SUM(length(text)) FILTER (WHERE event_type IN ('commit','candidate_commit','paste','paste_inferred','external_insert','voice')), 0)::bigint AS input_chars
         FROM input_event
         WHERE user_id = $1 AND occurred_at >= $2
         GROUP BY date(occurred_at)
         ORDER BY day ASC`,
        [res.locals.userId, daysAgo(days)],
      );
      res.json({ days, timeline: result.rows });
    } catch (err) {
      next(err);
    }
  });

  /** 小时分布（近 N 天） */
  router.get('/hours', async (req, res, next) => {
    try {
      const days = Math.min(Number(req.query.days ?? 30) || 30, 365);
      const result = await pool.query(
        `SELECT
           EXTRACT(HOUR FROM occurred_at)::int AS hour,
           COUNT(*) AS event_count,
           COALESCE(SUM(length(text)) FILTER (WHERE event_type IN ('commit','candidate_commit','paste','paste_inferred','external_insert','voice')), 0)::bigint AS input_chars
         FROM input_event
         WHERE user_id = $1 AND occurred_at >= $2
         GROUP BY hour
         ORDER BY hour ASC`,
        [res.locals.userId, daysAgo(days)],
      );
      res.json({ days, hours: result.rows });
    } catch (err) {
      next(err);
    }
  });

  /** 活跃热力图：星期 × 小时输入量（近 N 天），dow 为 ISODOW（1=周一 … 7=周日） */
  router.get('/heatmap', async (req, res, next) => {
    try {
      const days = Math.min(Number(req.query.days ?? 30) || 30, 365);
      const result = await pool.query(
        `SELECT
           EXTRACT(ISODOW FROM occurred_at)::int AS dow,
           EXTRACT(HOUR FROM occurred_at)::int AS hour,
           COALESCE(SUM(length(text)) FILTER (WHERE event_type IN ('commit','candidate_commit','paste','paste_inferred','external_insert','voice')), 0)::bigint AS chars
         FROM input_event
         WHERE user_id = $1 AND occurred_at >= $2
         GROUP BY dow, hour
         ORDER BY dow ASC, hour ASC`,
        [res.locals.userId, daysAgo(days)],
      );
      res.json({ days, cells: result.rows });
    } catch (err) {
      next(err);
    }
  });

  /** APP 分布：各 App 输入字符数/事件数占比 */
  router.get('/apps', async (req, res, next) => {
    try {
      const days = Math.min(Number(req.query.days ?? 30) || 30, 365);
      const result = await pool.query(
        `SELECT
           package_name,
           COUNT(*) AS event_count,
           COALESCE(SUM(length(text)) FILTER (WHERE event_type IN ('commit','candidate_commit','paste','paste_inferred','external_insert','voice')), 0)::bigint AS input_chars
         FROM input_event
         WHERE user_id = $1 AND occurred_at >= $2 AND package_name IS NOT NULL
         GROUP BY package_name
         ORDER BY input_chars DESC
         LIMIT 20`,
        [res.locals.userId, daysAgo(days)],
      );
      res.json({ days, apps: result.rows });
    } catch (err) {
      next(err);
    }
  });

  /** 高频词/短语：?kind=word|phrase&days=7|30|all */
  router.get('/phrases', async (req, res, next) => {
    try {
      const kind = req.query.kind === 'word' ? 'word' : 'phrase';
      const daysRaw = req.query.days;
      const limit = Math.min(Number(req.query.limit ?? 50) || 50, 200);

      // 直接对原始事件按文本聚合（比 phrase_stat 更灵活，可带时间范围）
      const whereDays = daysRaw && daysRaw !== 'all' ? `AND occurred_at >= $2` : '';
      const params: unknown[] = [res.locals.userId];
      if (whereDays) params.push(daysAgo(Number(daysRaw) || 7));

      const baseType = `event_type IN ('commit','candidate_commit','paste','paste_inferred','external_insert','voice')`;
      const extra = kind === 'word' ? '' : 'AND length(text) >= 2 AND length(text) <= 60';

      const result = await pool.query(
        `SELECT text AS phrase,
                COUNT(*) AS use_count,
                COUNT(DISTINCT date(occurred_at)) AS use_days,
                MAX(occurred_at) AS last_used_at
         FROM input_event
         WHERE user_id = $1 AND ${baseType} AND text IS NOT NULL AND length(text) <= 200 ${extra} ${whereDays}
         GROUP BY text
         ORDER BY use_count DESC, last_used_at DESC
         LIMIT $${params.length + 1}`,
        [...params, limit],
      );
      res.json({ kind, phrases: result.rows });
    } catch (err) {
      next(err);
    }
  });

  /** 高频前缀分析：每个前缀后面的续写分布（"麻烦" → 帮我确认一下 63%） */
  router.get('/prefixes', async (req, res, next) => {
    try {
      const limit = Math.min(Number(req.query.limit ?? 10) || 10, 50);
      // 取高频短语作为候选前缀
      const result = await pool.query(
        `SELECT phrase, use_count, last_used_at
         FROM phrase_stat
         WHERE user_id = $1 AND use_count >= 3 AND length(phrase) >= 2
         ORDER BY use_count DESC
         LIMIT $2`,
        [res.locals.userId, limit],
      );

      const phrases = result.rows as Array<{ phrase: string; use_count: number }>;
      // 一次取出所有候选短语，内存中算前缀续写分布
      const all = await pool.query(
        `SELECT phrase, use_count
         FROM phrase_stat
         WHERE user_id = $1 AND use_count >= 2 AND length(phrase) <= 100`,
        [res.locals.userId],
      );
      const allRows = all.rows as Array<{ phrase: string; use_count: number }>;

      const prefixes: Array<{
        prefix: string;
        count: number;
        continuations: Array<{ text: string; count: number; pct: number }>;
      }> = [];
      for (const row of phrases) {
        const p = row.phrase;
        if ([...p].length < 2) continue;
        // 同前缀的其它短语（排除自身）作为续写
        const contMap = new Map<string, number>();
        for (const other of allRows) {
          if (other.phrase !== p && other.phrase.startsWith(p)) {
            contMap.set(other.phrase, (contMap.get(other.phrase) ?? 0) + other.use_count);
          }
        }
        const total = [...contMap.values()].reduce((a, b) => a + b, 0);
        const continuations = [...contMap.entries()]
          .sort((a, b) => b[1] - a[1])
          .slice(0, 5)
          .map(([text, count]) => ({
            text,
            count,
            pct: total > 0 ? Math.round((count / total) * 1000) / 10 : 0,
          }));
        prefixes.push({ prefix: p, count: row.use_count, continuations });
      }
      res.json({ prefixes });
    } catch (err) {
      next(err);
    }
  });

  /** 补全效果：展示/接受/节省字符/接受率 */
  router.get('/completions', async (req, res, next) => {
    try {
      const stats = await pool.query(
        `SELECT
           COALESCE(SUM(show_count), 0)::bigint AS show_count,
           COALESCE(SUM(accept_count), 0)::bigint AS accept_count,
           COALESCE(SUM(use_count), 0)::bigint AS use_count,
           COUNT(*) AS candidate_count
         FROM completion_candidate WHERE user_id = $1`,
        [res.locals.userId],
      );
      const top = await pool.query(
        `SELECT prefix, completion, package_name, use_count, show_count, accept_count, score, last_used_at
         FROM completion_candidate
         WHERE user_id = $1
         ORDER BY use_count DESC, score DESC
         LIMIT 50`,
        [res.locals.userId],
      );
      const s = stats.rows[0];
      const show = Number(s.show_count);
      const accept = Number(s.accept_count);
      res.json({
        show_count: show,
        accept_count: accept,
        accept_rate: show > 0 ? Math.round((accept / show) * 1000) / 10 : 0,
        saved_chars: accept * 9, // 按平均每次节省 9 字估算（对话示例口径）
        candidate_count: Number(s.candidate_count),
        top: top.rows,
      });
    } catch (err) {
      next(err);
    }
  });

  /** 剪贴板分析：复制次数、类型分布、复制→粘贴时间间隔 */
  router.get('/clipboard', async (req, res, next) => {
    try {
      const days = Math.min(Number(req.query.days ?? 30) || 30, 365);
      const result = await pool.query(
        `SELECT
           COUNT(*) FILTER (WHERE event_type = 'clipboard_change')::bigint AS copy_count,
           COUNT(*) FILTER (WHERE event_type IN ('paste','paste_inferred'))::bigint AS paste_count
         FROM input_event
         WHERE user_id = $1 AND occurred_at >= $2`,
        [res.locals.userId, daysAgo(days)],
      );

      // 复制内容类型分布：URL / 邮箱 / 电话 / 纯文字
      const types = await pool.query(
        `SELECT
           COUNT(*) FILTER (WHERE text ~* '^https?://')::bigint AS url,
           COUNT(*) FILTER (WHERE text ~* '[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}')::bigint AS email,
           COUNT(*) FILTER (WHERE text ~ '^[0-9+\\- ]{6,}$')::bigint AS phone,
           COUNT(*) FILTER (WHERE text !~* '^https?://' AND text !~* '[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}' AND text !~ '^[0-9+\\- ]{6,}$')::bigint AS plain
         FROM input_event
         WHERE user_id = $1 AND event_type = 'clipboard_change' AND text IS NOT NULL AND occurred_at >= $2`,
        [res.locals.userId, daysAgo(days)],
      );

      // 复制→粘贴间隔：用 clipboard_change 与最近 paste 的时间差聚合
      const interval = await pool.query(
        `WITH pastes AS (
           SELECT id, occurred_at, text FROM input_event
           WHERE user_id = $1 AND event_type IN ('paste','paste_inferred') AND occurred_at >= $2
         ),
         copies AS (
           SELECT occurred_at, text FROM input_event
           WHERE user_id = $1 AND event_type = 'clipboard_change' AND text IS NOT NULL AND occurred_at >= $2
         )
         SELECT
           COUNT(*) FILTER (WHERE diff <= '10 seconds')::bigint AS within_10s,
           COUNT(*) FILTER (WHERE diff <= '1 minute')::bigint AS within_1m,
           COUNT(*) FILTER (WHERE diff <= '10 minutes')::bigint AS within_10m
         FROM (
           SELECT p.occurred_at - c.occurred_at AS diff
           FROM pastes p
           JOIN LATERAL (
             SELECT occurred_at FROM copies c
             WHERE c.text = p.text AND c.occurred_at < p.occurred_at
             ORDER BY c.occurred_at DESC LIMIT 1
           ) c ON true
         ) t`,
        [res.locals.userId, daysAgo(days)],
      );

      // 高频复制内容
      const top = await pool.query(
        `SELECT text, COUNT(*) AS count, MAX(occurred_at) AS last_used_at
         FROM input_event
         WHERE user_id = $1 AND event_type = 'clipboard_change' AND text IS NOT NULL
           AND length(text) BETWEEN 1 AND 200 AND occurred_at >= $2
         GROUP BY text ORDER BY count DESC LIMIT 30`,
        [res.locals.userId, daysAgo(days)],
      );

      const t = types.rows[0];
      res.json({
        days,
        counts: result.rows[0],
        type_distribution: {
          url: Number(t.url),
          email: Number(t.email),
          phone: Number(t.phone),
          plain: Number(t.plain),
        },
        paste_intervals: interval.rows[0],
        top: top.rows,
      });
    } catch (err) {
      next(err);
    }
  });

  /** 用户目录：以设备作为用户空间，支持服务端搜索和分页。 */
  router.get('/users', async (req, res, next) => {
    try {
      const page = Math.min(1_000_000, Math.max(1, Math.floor(Number(req.query.page ?? 1) || 1)));
      const pageSize = Math.min(100, Math.max(1, Math.floor(Number(req.query.page_size ?? 20) || 20)));
      const q = String(req.query.q ?? '').trim().slice(0, 100);
      const id = String(req.query.id ?? '').trim().slice(0, 100);
      const where = `($1 = '' OR id::text ILIKE '%' || $1 || '%'
          OR COALESCE(dashboard_name, '') ILIKE '%' || $1 || '%'
          OR COALESCE(tags, '') ILIKE '%' || $1 || '%'
          OR COALESCE(name, '') ILIKE '%' || $1 || '%'
          OR COALESCE(brand, '') ILIKE '%' || $1 || '%'
          OR COALESCE(model, '') ILIKE '%' || $1 || '%')
        AND ($2 = '' OR id::text = $2)`;
      const [count, result] = await Promise.all([
        pool.query(`SELECT COUNT(*)::int AS total FROM device WHERE ${where}`, [q, id]),
        pool.query(
          `SELECT id, dashboard_name, tags, name, platform, model, os_version, app_version,
                  brand, sdk_int, screen_resolution, locale, region, hardware, rom_version, ram_mb,
                  last_seen_at
           FROM device WHERE ${where}
           ORDER BY last_seen_at DESC, id
           LIMIT $3 OFFSET $4`,
          [q, id, pageSize, (page - 1) * pageSize],
        ),
      ]);
      res.json({
        total: Number(count.rows[0]?.total ?? 0),
        page,
        page_size: pageSize,
        users: result.rows,
      });
    } catch (err) {
      next(err);
    }
  });

  /** 为大量同型号手机设置后台易识别名称和标签。 */
  router.patch('/users/:id', async (req, res, next) => {
    try {
      const dashboardName = typeof req.body?.dashboard_name === 'string'
        ? req.body.dashboard_name.trim().slice(0, 100) || null
        : null;
      const tags = typeof req.body?.tags === 'string'
        ? req.body.tags.trim().slice(0, 500) || null
        : null;
      const result = await pool.query(
        `UPDATE device SET dashboard_name = $2, tags = $3
         WHERE id::text = $1
         RETURNING id, dashboard_name, tags, name, platform, model, os_version, app_version,
                   brand, sdk_int, screen_resolution, locale, region, hardware, rom_version, ram_mb,
                   last_seen_at`,
        [req.params.id, dashboardName, tags],
      );
      if (!result.rows[0]) {
        res.status(404).json({ error: 'user not found' });
        return;
      }
      res.json({ user: result.rows[0] });
    } catch (err) {
      next(err);
    }
  });

  /** 当前用户的设备列表（当前模型中一台设备即一个用户空间）。 */
  router.get('/devices', async (_req, res, next) => {
    try {
      const result = await pool.query(
        `SELECT id, dashboard_name, tags, name, platform, model, os_version, app_version,
                brand, sdk_int, screen_resolution, locale, region, hardware, rom_version, ram_mb,
                last_seen_at
         FROM device WHERE id = $1 ORDER BY last_seen_at DESC`,
        [res.locals.userId],
      );
      res.json({ devices: result.rows });
    } catch (err) {
      next(err);
    }
  });

  /**
   * 输入报告（日报/周报）：一天或一周的输入总结。
   * 参数：type=daily|weekly（默认 daily），date=YYYY-MM-DD（默认今天）。
   * 内容：字数/事件/复制/删除汇总、活跃时段 Top、输入方式占比、Top 应用、Top 词句、常用位置。
   */
  router.get('/report', async (req, res, next) => {
    try {
      const type = req.query.type === 'weekly' ? 'weekly' : 'daily';
      const dateRaw = (req.query.date as string) ?? new Date().toISOString().slice(0, 10);
      if (!/^\d{4}-\d{2}-\d{2}$/.test(dateRaw)) {
        return res.status(400).json({ error: 'invalid date (expected YYYY-MM-DD)' });
      }

      // 时间范围：日报 = 当天 0 点 ~ 次日 0 点；周报 = ISO 周（周一）起 7 天
      const rangeSql = type === 'weekly'
        ? `occurred_at >= date_trunc('week', $2::date) AND occurred_at < date_trunc('week', $2::date) + interval '7 days'`
        : `occurred_at >= $2::date AND occurred_at < $2::date + interval '1 day'`;
      const cond = `user_id = $1 AND ${rangeSql}`;
      const params: unknown[] = [res.locals.userId, dateRaw];

      const summary = await pool.query(
        `SELECT
           COUNT(*) FILTER (WHERE event_type IN ('commit','candidate_commit','paste','paste_inferred','external_insert','voice')) AS input_events,
           COALESCE(SUM(length(text)) FILTER (WHERE event_type IN ('commit','candidate_commit','paste','paste_inferred','external_insert','voice')), 0)::bigint AS input_chars,
           COUNT(*) FILTER (WHERE event_type = 'clipboard_change') AS copy_count,
           COUNT(*) FILTER (WHERE event_type = 'delete') AS delete_count,
           COUNT(*) FILTER (WHERE event_type = 'voice') AS voice_count,
           COUNT(DISTINCT device_id) AS device_count,
           COUNT(DISTINCT date(occurred_at)) AS active_days
         FROM input_event WHERE ${cond}`,
        params,
      );

      // 活跃时段 Top3（按字符数）
      const peakHours = await pool.query(
        `SELECT EXTRACT(HOUR FROM occurred_at)::int AS hour,
                COALESCE(SUM(length(text)) FILTER (WHERE event_type IN ('commit','candidate_commit','paste','paste_inferred','external_insert','voice')), 0)::bigint AS chars
         FROM input_event WHERE ${cond}
         GROUP BY hour ORDER BY chars DESC LIMIT 3`,
        params,
      );

      // 输入方式占比
      const sources = await pool.query(
        `SELECT
           COALESCE(SUM(length(text)) FILTER (WHERE event_type IN ('commit','candidate_commit')), 0)::bigint AS typed,
           COALESCE(SUM(length(text)) FILTER (WHERE event_type IN ('paste','paste_inferred')), 0)::bigint AS pasted,
           COALESCE(SUM(length(text)) FILTER (WHERE event_type = 'external_insert'), 0)::bigint AS external_inserted,
           COALESCE(SUM(length(text)) FILTER (WHERE event_type = 'voice'), 0)::bigint AS voiced
         FROM input_event WHERE ${cond}`,
        params,
      );

      // Top 应用（按输入字符数）
      const topApps = await pool.query(
        `SELECT package_name,
                COUNT(*) AS event_count,
                COALESCE(SUM(length(text)) FILTER (WHERE event_type IN ('commit','candidate_commit','paste','paste_inferred','external_insert','voice')), 0)::bigint AS input_chars
         FROM input_event WHERE ${cond} AND package_name IS NOT NULL
         GROUP BY package_name ORDER BY input_chars DESC LIMIT 5`,
        params,
      );

      // Top 词句（2~60 字短语，按次数）
      const topPhrases = await pool.query(
        `SELECT text AS phrase, COUNT(*) AS use_count
         FROM input_event WHERE ${cond}
           AND event_type IN ('commit','candidate_commit','paste','paste_inferred','external_insert','voice')
           AND text IS NOT NULL AND length(text) BETWEEN 2 AND 60
         GROUP BY text ORDER BY use_count DESC LIMIT 10`,
        params,
      );

      // 常用位置（按坐标聚合，未解析地址的显示坐标）
      const locCond = `user_id = $1 AND ${rangeSql.replaceAll('occurred_at', 'location_track.occurred_at')}`;
      const topLocations = await pool.query(
        `SELECT latitude, longitude, address,
                COUNT(*) AS count, MAX(last_seen_at) AS last_seen_at
         FROM location_track WHERE ${locCond}
         GROUP BY latitude, longitude, address
         ORDER BY count DESC LIMIT 5`,
        params,
      );

      const s = sources.rows[0];
      const sourceTotal = Number(s.typed) + Number(s.pasted) + Number(s.external_inserted) + Number(s.voiced);

      res.json({
        type,
        date: dateRaw,
        summary: summary.rows[0],
        peak_hours: peakHours.rows,
        source_distribution: {
          typed: Number(s.typed),
          pasted: Number(s.pasted),
          external: Number(s.external_inserted),
          voice: Number(s.voiced),
          total: sourceTotal,
        },
        top_apps: topApps.rows,
        top_phrases: topPhrases.rows,
        top_locations: topLocations.rows,
      });
    } catch (err) {
      next(err);
    }
  });

  /**
   * 数据导出：全量采集数据打包为 JSON（个人数据迁移/备份）。
   * 返回各表全量数据 + 行数统计。
   */
  router.get('/export', async (req, res, next) => {
    try {
      const [devices, sessions, events, phrases, completions, locations] = await Promise.all([
        pool.query(`SELECT * FROM device WHERE id = $1`, [res.locals.userId]),
        pool.query(`SELECT * FROM input_session WHERE device_id = $1 ORDER BY started_at DESC`, [res.locals.userId]),
        pool.query(`SELECT * FROM input_event WHERE user_id = $1 ORDER BY occurred_at ASC`, [res.locals.userId]),
        pool.query(`SELECT * FROM phrase_stat WHERE user_id = $1`, [res.locals.userId]),
        pool.query(`SELECT * FROM completion_candidate WHERE user_id = $1`, [res.locals.userId]),
        pool.query(`SELECT * FROM location_track WHERE user_id = $1 ORDER BY occurred_at ASC`, [res.locals.userId]),
      ]);
      res.json({
        exported_at: new Date().toISOString(),
        counts: {
          devices: devices.rowCount,
          sessions: sessions.rowCount,
          events: events.rowCount,
          phrases: phrases.rowCount,
          completions: completions.rowCount,
          locations: locations.rowCount,
        },
        devices: devices.rows,
        sessions: sessions.rows,
        events: events.rows,
        phrases: phrases.rows,
        completions: completions.rows,
        locations: locations.rows,
      });
    } catch (err) {
      next(err);
    }
  });

  /**
   * 数据清理：删除事件日志（可带时间/应用范围）或全部采集数据。
   * body: { confirm: 'DELETE', scope: 'events'|'all', from?, to?, package_name? }
   * 必须显式传 confirm='DELETE' 防误删；全部清理会同时重置分析游标（统计从零重建）。
   */
  router.post('/cleanup', async (req, res, next) => {
    try {
      const body = req.body as {
        confirm?: string;
        scope?: string;
        from?: string;
        to?: string;
        package_name?: string;
      };
      if (body.confirm !== 'DELETE') return res.status(400).json({ error: "must pass confirm='DELETE'" });
      const scope = body.scope === 'all' ? 'all' : 'events';
      if (body.from && !/^\d{4}-\d{2}-\d{2}$/.test(body.from)) return res.status(400).json({ error: 'invalid from' });
      if (body.to && !/^\d{4}-\d{2}-\d{2}$/.test(body.to)) return res.status(400).json({ error: 'invalid to' });

      // 事件表条件（时间/应用均可选）
      const conds = ['user_id = $1'];
      const params: unknown[] = [res.locals.userId];
      const add = (cond: string, v: unknown) => {
        params.push(v);
        conds.push(cond.replace('?', `$${params.length}`));
      };
      if (body.from) add('occurred_at >= ?::date', body.from);
      if (body.to) add('occurred_at < ?::date + interval \'1 day\'', body.to);
      if (body.package_name) add('package_name = ?', body.package_name);
      const where = conds.join(' AND ');

      const client = await pool.connect();
      try {
        await client.query('BEGIN');
        const deleted: Record<string, number> = {};

        const eventsRes = await client.query(`DELETE FROM input_event WHERE ${where}`, params);
        deleted.events = eventsRes.rowCount ?? 0;

        if (scope === 'all') {
          // 会话（无 user_id 列，单用户环境按设备全删；带时间则按开始时间过滤）
          const sConds: string[] = ['device_id = $1'];
          const sParams: unknown[] = [res.locals.userId];
          if (body.from) {
            sParams.push(body.from);
            sConds.push(`started_at >= $${sParams.length}::date`);
          }
          if (body.to) {
            sParams.push(body.to);
            sConds.push(`started_at < $${sParams.length}::date + interval '1 day'`);
          }
          const sessionsRes = await client.query(
            `DELETE FROM input_session ${sConds.length ? `WHERE ${sConds.join(' AND ')}` : ''}`,
            sParams,
          );
          deleted.sessions = sessionsRes.rowCount ?? 0;

          // 位置轨迹（带时间则过滤）
          const lConds = ['user_id = $1'];
          const lParams: unknown[] = [res.locals.userId];
          if (body.from) {
            lParams.push(body.from);
            lConds.push(`occurred_at >= $${lParams.length}::date`);
          }
          if (body.to) {
            lParams.push(body.to);
            lConds.push(`occurred_at < $${lParams.length}::date + interval '1 day'`);
          }
          const locRes = await client.query(`DELETE FROM location_track WHERE ${lConds.join(' AND ')}`, lParams);
          deleted.locations = locRes.rowCount ?? 0;

          // 分析结果与补全模型全量重建（无条件）
          const phraseRes = await client.query(`DELETE FROM phrase_stat WHERE user_id = $1`, [res.locals.userId]);
          deleted.phrases = phraseRes.rowCount ?? 0;
          const compRes = await client.query(`DELETE FROM completion_candidate WHERE user_id = $1`, [res.locals.userId]);
          deleted.completions = compRes.rowCount ?? 0;

          // 重置分析游标，下次分析从零统计
          await client.query(
            `INSERT INTO analysis_state (key, value) VALUES ($1, 0)
             ON CONFLICT (key) DO UPDATE SET value = 0, updated_at = NOW()`,
            [`last_analyzed_epoch_ms:${res.locals.userId}`],
          );
        }

        await client.query('COMMIT');
        res.json({ scope, deleted });
      } catch (err) {
        await client.query('ROLLBACK');
        throw err;
      } finally {
        client.release();
      }
    } catch (err) {
      next(err);
    }
  });

  /**
   * 行为明细列表：每一次输入/粘贴/复制/语音/图片行为。
   * 支持筛选：device_id（用户）、from/to（时间范围）、q（关键词）、type（all|text|paste|voice|image）、all=1（含底层事件）
   * 分页：page / page_size（默认 20，最大 100）
   */
  router.get('/events', async (req, res, next) => {
    try {
      const userId = (req.query.user_id as string) ?? res.locals.userId;
      const deviceId = (req.query.device_id as string) ?? null;
      const packageName = (req.query.package_name as string) ?? null;
      const q = ((req.query.q as string) ?? '').trim();
      const type = (req.query.type as string) ?? 'all';
      const from = (req.query.from as string) ?? null;
      const to = (req.query.to as string) ?? null;
      const days = req.query.days ? Math.min(Math.max(Number(req.query.days) || 0, 1), 3650) : null;
      const showAll = req.query.all === '1';
      const page = Math.max(1, Number(req.query.page ?? 1) || 1);
      const pageSize = Math.min(Math.max(1, Number(req.query.page_size ?? 20) || 20), 100);

      const conds: string[] = ['user_id = $1'];
      const params: unknown[] = [userId];
      let n = 1;
      const add = (cond: string, ...vs: unknown[]) => {
        let sql = cond;
        for (const v of vs) {
          n++;
          sql = sql.replace('?', `$${n}`);
          params.push(v);
        }
        conds.push(sql);
      };

      if (deviceId) add('device_id = ?', deviceId);
      if (packageName) add('package_name = ?', packageName);
      if (from) add('occurred_at >= ?', new Date(from));
      if (to) add('occurred_at <= ?', new Date(to));
      if (days) add('occurred_at >= ?', daysAgo(days));
      if (q) add('(text ILIKE ? OR input_code ILIKE ? OR client_ip = ?)', `%${q}%`, `%${q}%`, q);
      if (!showAll) conds.push(`event_type IN ${BEHAVIOR_TYPES}`);

      // 类型筛选（对应列表“类型”列）
      const typeConds: Record<string, string> = {
        text: `event_type IN ('commit','candidate_commit','external_insert')`,
        paste: `event_type IN ('paste','paste_inferred','clipboard_change')`,
        voice: `event_type = 'voice'`,
        image: `${CONTENT_TYPE_SQL} = 'image'`,
      };
      if (typeConds[type]) conds.push(typeConds[type]);

      const where = conds.join(' AND ');

      const total = await pool.query(`SELECT COUNT(*)::int AS total FROM input_event WHERE ${where}`, params);

      const items = await pool.query(
        `SELECT id, occurred_at, event_type, ${CONTENT_TYPE_SQL} AS content_type,
                text, input_code, package_name, device_id, session_id,
                client_ip, ip_location, network_type
         FROM input_event
         WHERE ${where}
         ORDER BY occurred_at DESC
         LIMIT $${n + 1} OFFSET $${n + 2}`,
        [...params, pageSize, (page - 1) * pageSize],
      );

      // 懒解析未解析过的 IP（不阻塞响应，下次查询即有地址）
      const missingIps = (items.rows as Array<{ client_ip: string | null }>)
        .map((r) => r.client_ip)
        .filter((ip): ip is string => !!ip);
      resolveMissingIps(pool, missingIps).catch(() => {});

      res.json({ total: total.rows[0].total, page, page_size: pageSize, items: items.rows });
    } catch (err) {
      next(err);
    }
  });

  /**
   * 位置轨迹：设备移动历史（同位置自动合并，last_seen_at 为最后出现时间）。
   * 支持 device_id、days、limit 参数；未解析地址的坐标异步反地理编码。
   */
  router.get('/locations', async (req, res, next) => {
    try {
      const userId = (req.query.user_id as string) ?? res.locals.userId;
      const deviceId = (req.query.device_id as string) ?? null;
      const days = req.query.days ? Math.min(Math.max(Number(req.query.days) || 0, 1), 3650) : 7;
      const limit = Math.min(Math.max(1, Number(req.query.limit ?? 200) || 200), 1000);

      const conds = ['user_id = $1', 'occurred_at >= $2'];
      const params: unknown[] = [userId, daysAgo(days)];
      if (deviceId) {
        params.push(deviceId);
        conds.push(`device_id = $${params.length}`);
      }

      const result = await pool.query(
        `SELECT id, device_id, latitude, longitude, accuracy, provider, speed, address,
                occurred_at, first_seen_at, last_seen_at
         FROM location_track
         WHERE ${conds.join(' AND ')}
         ORDER BY occurred_at DESC
         LIMIT $${params.length + 1}`,
        [...params, limit],
      );
      const rows = result.rows as Array<{
        id: number;
        latitude: string;
        longitude: string;
        address: string | null;
      }>;

      // 懒解析未解析过的坐标（不阻塞响应，下次查询即有地址）
      resolveMissingAddresses(
        pool,
        rows.filter((r) => !r.address).map((r) => ({ id: r.id, lat: Number(r.latitude), lng: Number(r.longitude) })),
      ).catch(() => {});

      res.json({ days, total: rows.length, locations: rows });
    } catch (err) {
      next(err);
    }
  });

  return router;
}
