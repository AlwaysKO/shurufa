import type { NextFunction, Request, Response } from 'express';

// 兼容历史 DEFAULT_USER_ID（版本位为 0）以及 Android 新生成的标准 v4 UUID。
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function validUuid(value: unknown): value is string {
  return typeof value === 'string' && UUID_PATTERN.test(value);
}

export function isLoopbackAddress(address: string | undefined): boolean {
  return address === '127.0.0.1' || address === '::1' || address === '::ffff:127.0.0.1';
}

function bodyDeviceId(req: Request): unknown {
  if (!req.body || typeof req.body !== 'object') return undefined;
  const body = req.body as Record<string, unknown>;
  return body.device_id ?? (req.path === '/device' ? body.id : undefined);
}

export function requireMobileIdentity(req: Request, res: Response, next: NextFunction): void {
  const headerId = req.get('X-Device-Id');
  const bodyId = bodyDeviceId(req);

  if (headerId != null && !validUuid(headerId)) {
    res.status(400).json({ error: 'device_id is invalid' });
    return;
  }
  if (bodyId != null && !validUuid(bodyId)) {
    res.status(400).json({ error: 'device_id is invalid' });
    return;
  }
  if (headerId && bodyId && headerId !== bodyId) {
    res.status(400).json({ error: 'device_id mismatch' });
    return;
  }

  const deviceId = headerId ?? bodyId;
  if (!validUuid(deviceId)) {
    res.status(400).json({ error: 'device_id required' });
    return;
  }
  res.locals.userId = deviceId;
  next();
}

export function requireDashboardIdentity(req: Request, res: Response, next: NextFunction): void {
  // 用户目录属于本机后台管理能力，不向局域网匿名暴露设备指纹和编辑入口。
  const isDirectory = req.path === '/users' || req.path.startsWith('/users/');
  if (isDirectory && !isLoopbackAddress(req.socket.remoteAddress)) {
    res.status(403).json({ error: 'dashboard directory is local only' });
    return;
  }
  // 分页用户目录用于进入后台后选择用户，GET 本身不能依赖已选中的用户。
  if (req.method === 'GET' && req.path === '/users') {
    next();
    return;
  }
  const userId = req.query.user_id;
  if (!validUuid(userId)) {
    res.status(400).json({ error: 'valid user_id required' });
    return;
  }
  res.locals.userId = userId;
  next();
}
