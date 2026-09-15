import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import { Router, type Request, type RequestHandler } from 'express';

const COOKIE = 'dashboard_session';
const LIFETIME = 8 * 60 * 60 * 1000;
const WINDOW = 15 * 60 * 1000;
const digest = (value: string) => createHash('sha256').update(value).digest();

/** 单进程管理后台会话；服务重启后需要重新登录。 */
export function createDashboardAuth() {
  const production = process.env.NODE_ENV === 'production';
  const username = process.env.DASHBOARD_USERNAME || (production ? '' : 'admin');
  const password = process.env.DASHBOARD_PASSWORD || (production ? '' : 'adminhaha');
  if (!username || !password || (production && (password.length < 16 || password === 'adminhaha'))) {
    throw new Error('DASHBOARD_USERNAME and a unique DASHBOARD_PASSWORD (at least 16 characters) are required in production');
  }
  const sessions = new Map<string, number>();
  // 单管理员统一限流，不能通过伪造 X-Forwarded-For 绕过。
  let failures = 0;
  let failureUntil = 0;
  const cookieOptions = { httpOnly: true, sameSite: 'strict' as const, secure: production, path: '/' };
  const tokenOf = (req: Request) => (req.headers.cookie ?? '').split(';').map(s => s.trim()).find(s => s.startsWith(`${COOKIE}=`))?.slice(COOKIE.length + 1) ?? '';
  const prune = () => { for (const [token, expires] of sessions) if (expires <= Date.now()) sessions.delete(token); };
  const requireSession: RequestHandler = (req, res, next) => {
    res.set('Cache-Control', 'no-store');
    prune();
    if (!sessions.has(tokenOf(req))) { res.status(401).json({ error: 'authentication_required' }); return; }
    next();
  };
  const protectWrite: RequestHandler = (req, res, next) => {
    if (['GET', 'HEAD', 'OPTIONS'].includes(req.method)) { next(); return; }
    // 不启用后台 CORS；自定义头禁止简单跨站表单，Origin 再限制同源。
    const origin = req.get('Origin');
    const expected = process.env.DASHBOARD_ORIGIN || `${req.protocol}://${req.get('host')}`;
    if (req.get('X-Dashboard-Request') !== '1' || (origin && origin !== expected) || req.get('Sec-Fetch-Site') === 'cross-site') {
      res.status(403).json({ error: 'cross_site_request_rejected' }); return;
    }
    next();
  };
  const router = Router();
  router.use((_req, res, next) => { res.set('Cache-Control', 'no-store'); next(); });
  router.use(protectWrite);
  router.post('/login', (req, res) => {
    if (Date.now() >= failureUntil) { failures = 0; failureUntil = Date.now() + WINDOW; }
    if (failures >= 5) { res.set('Retry-After', String(Math.ceil((failureUntil - Date.now()) / 1000))); res.status(429).json({ error: 'too_many_attempts' }); return; }
    const validName = typeof req.body?.username === 'string' && timingSafeEqual(digest(req.body.username), digest(username));
    const validPassword = typeof req.body?.password === 'string' && timingSafeEqual(digest(req.body.password), digest(password));
    if (!validName || !validPassword) { failures++; res.status(401).json({ error: 'invalid_credentials' }); return; }
    failures = 0;
    prune(); sessions.delete(tokenOf(req));
    if (sessions.size >= 100) sessions.delete(sessions.keys().next().value!);
    const token = randomBytes(32).toString('hex');
    sessions.set(token, Date.now() + LIFETIME);
    res.cookie(COOKIE, token, { ...cookieOptions, maxAge: LIFETIME });
    res.json({ username });
  });
  router.get('/session', requireSession, (_req, res) => { res.json({ username }); });
  router.post('/logout', (req, res) => { sessions.delete(tokenOf(req)); res.clearCookie(COOKIE, cookieOptions); res.sendStatus(204); });
  return { router, requireSession, protectWrite };
}
