import { isIP } from 'node:net';
import type { Request, RequestHandler } from 'express';
import type pg from 'pg';
import { isLoopbackAddress } from './requestIdentity.js';

export type LoginResult = 'success' | 'invalid_credentials' | 'rate_limited' | 'rejected';
const results: LoginResult[] = ['success', 'invalid_credentials', 'rate_limited', 'rejected'];
const clean = (value: unknown, max: number) => typeof value === 'string' ? value.replace(/[\u0000-\u001f\u007f]/g, '').slice(0, max) : '';
const normalizeIp = (value: string) => value.startsWith('::ffff:') && isIP(value.slice(7)) === 4 ? value.slice(7) : value;

export function loginAddress(req: Request) {
  const peer = req.socket.remoteAddress ?? '';
  const forwarded = req.get('X-Forwarded-For')?.trim() ?? '';
  // 部署Nginx覆盖此头；远程直连、地址链和非IP值均不采信。
  const trusted = isLoopbackAddress(peer) && isIP(forwarded) !== 0;
  return { ip: normalizeIp(trusted ? forwarded : peer), peer_ip: normalizeIp(peer), ip_source: trusted ? 'local_proxy' : 'connection' };
}

export async function recordLogin(pool: pg.Pool, req: Request, result: LoginResult, authenticatedUsername: string | null = null) {
  const address = loginAddress(req);
  await pool.query(`INSERT INTO dashboard_login_audit
    (username,authenticated_username,result,ip,peer_ip,ip_source,user_agent) VALUES($1,$2,$3,$4,$5,$6,$7)`,
  [clean(req.body?.username, 200), authenticatedUsername, result, address.ip, address.peer_ip, address.ip_source, clean(req.get('User-Agent'), 1000)]);
}

function timestamp(value: unknown): number | undefined {
  if (value === undefined) return undefined;
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,3})?(Z|[+-]\d{2}:\d{2})$/.test(value)) return NaN;
  const day = Date.parse(value.slice(0, 10) + 'T00:00:00Z');
  if (!Number.isFinite(day) || new Date(day).toISOString().slice(0, 10) !== value.slice(0, 10)) return NaN;
  return Date.parse(value);
}

export function listLoginRecords(pool: pg.Pool): RequestHandler {
  return async (req, res, next) => {
    const page = Number(req.query.page ?? 1), size = Number(req.query.page_size ?? 20);
    const { result, username, ip } = req.query;
    const from = timestamp(req.query.from), to = timestamp(req.query.to);
    if (!Number.isSafeInteger(page) || page < 1 || page > 1_000_000 || !Number.isInteger(size) || size < 1 || size > 100
      || (result !== undefined && !results.includes(result as LoginResult))
      || (username !== undefined && (typeof username !== 'string' || username.length > 200))
      || (ip !== undefined && (typeof ip !== 'string' || !isIP(ip)))
      || (from !== undefined && !Number.isFinite(from)) || (to !== undefined && !Number.isFinite(to))
      || (from !== undefined && to !== undefined && to <= from)) {
      res.status(400).json({ error: 'invalid_login_record_filters' }); return;
    }
    const params: unknown[] = [];
    const conditions: string[] = [];
    const add = (column: string, op: string, value: unknown) => { params.push(value); conditions.push(`${column} ${op} $${params.length}`); };
    if (result !== undefined) add('result', '=', result);
    if (username !== undefined) add('username', '=', username);
    if (ip !== undefined) add('ip', '=', normalizeIp(ip as string));
    if (from !== undefined) add('occurred_at', '>=', new Date(from));
    if (to !== undefined) add('occurred_at', '<', new Date(to));
    const where = conditions.length ? `WHERE ${conditions.join(' AND ')}` : '';
    try {
      const count = await pool.query(`SELECT COUNT(*) AS total FROM dashboard_login_audit ${where}`, params);
      const records = await pool.query(`SELECT id,occurred_at,username,authenticated_username,result,ip,peer_ip,ip_source,user_agent
        FROM dashboard_login_audit ${where} ORDER BY occurred_at DESC,id DESC LIMIT $${params.length + 1} OFFSET $${params.length + 2}`,
      [...params, size, (page - 1) * size]);
      res.json({ total: Number(count.rows[0].total), page, page_size: size, records: records.rows });
    } catch (error) { next(error); }
  };
}
