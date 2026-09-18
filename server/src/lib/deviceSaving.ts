import type pg from 'pg';
import type { Request, Response, NextFunction } from 'express';

export const deviceSavingKey = (id: string) => `device_save_uploads:${id.toLowerCase()}`;
/** 直接按连接的search_path读取；权限/连接/内容异常不能降级为允许保存。 */
export async function savingFlags(pool: pg.Pool, ids: string[]): Promise<Map<string, boolean>> {
  const flags = new Map<string, boolean>();
  if (!ids.length) return flags;
  let rows: pg.QueryResult<{ key: string; value: string }>;
  try {
    rows = await pool.query('SELECT key,value FROM runtime_setting WHERE key = ANY($1::text[])', [ids.map(deviceSavingKey)]);
  } catch (error) {
    const failure = error as { code?: string; data?: { error?: string } };
    // 仅兼容真正尚未安装020表的旧库；内存SQL驱动的缺表错误使用data.error。
    if (failure.code === '42P01' || (!failure.code && failure.data?.error === 'relation "runtime_setting" does not exist')) return flags;
    throw error;
  }
  for (const row of rows.rows) {
    if (row.value !== 'true' && row.value !== 'false') throw new Error('invalid device saving state');
    flags.set(row.key.slice('device_save_uploads:'.length), row.value === 'true');
  }
  return flags;
}
/** 只拦截已知上报协议，不把查询、未知接口或身份错误伪装成成功。 */
export function discardDisabledUploads(pool: pg.Pool) {
  return async (req: Request, res: Response, next: NextFunction): Promise<void> => {
    const path = req.path.replace(/\/$/, '');
    const report = req.method === 'POST' && (
      ['/device', '/session', '/events/batch', '/location', '/reports', '/completions/feedback',
        '/phrases', '/phrases/use', '/dictionary/report', '/dictionary/ack', '/chat/assets', '/chat/messages/batch'].includes(path)
      || /^\/(stickers|expressions)\/[^/]+\/use$/.test(path));
    if (!report) { next(); return; }
    try {
      const userId = String(res.locals.userId).toLowerCase();
      const flags = await savingFlags(pool, [userId]);
      // 删除过的手机可能仍缓存“已注册”；新上报恢复最小目录行，不要求重启手机。
      if (flags.has(userId)) await pool.query('INSERT INTO device(id) VALUES($1) ON CONFLICT(id) DO NOTHING', [userId]);
      if (flags.get(userId) !== false) { next(); return; }
      const body = req.body ?? {};
      const result: Record<string, unknown> = { ok: true, discarded: true };
      // /device 已恢复ID；关闭期间不保存上传的名称、型号等详情。
      if (path === '/events/batch') {
        if (!Array.isArray(body.events) || body.events.length > 500) { res.status(400).json({ error: 'invalid events batch' }); return; }
        if (body.events.some((e: { device_id?: string } | null) => !e || e.device_id !== userId)) { res.status(400).json({ error: 'device_id mismatch' }); return; }
        Object.assign(result, { inserted: 0, received: body.events.length });
      } else if (path === '/reports') {
        if (typeof body.id !== 'string' || !/^[a-f0-9-]{36}$/i.test(body.id)) { res.status(400).json({ error: 'invalid report id' }); return; }
        if (body.payload?.device_id && body.payload.device_id !== userId) { res.status(400).json({ error: 'device_id mismatch' }); return; }
        result.id = body.id; // 持久化队列核对原回执ID后才能移除待传项。
      } else if (path === '/chat/assets') {
        Object.assign(result, { id: null, sha256: body.sha256, duplicated: false });
      } else if (path === '/chat/messages/batch') {
        if (!Array.isArray(body.messages) || body.messages.length > 200) { res.status(400).json({ error: 'invalid messages batch' }); return; }
        Object.assign(result, { conversationId: null, inserted: 0, duplicated: 0, missingAssets: [] });
      } else if (path === '/phrases') {
        Object.assign(result, { id: null, content: body.content });
      } else if (path === '/location') result.recorded = 'discarded';
      else if (path.startsWith('/expressions/')) result.use_count = 0;
      res.status(path === '/phrases' ? 201 : 200).json(result);
    } catch (error) { next(error); }
  };
}
