import { randomUUID } from 'node:crypto';
import type { RequestHandler } from 'express';

/** 不记录URL查询、文件名、图片内容或身份；代理收完请求体前的等待由浏览器计时补齐。 */
export const uploadTiming: RequestHandler = (req, res, next) => {
  if (!['POST', 'PATCH'].includes(req.method) || !/^\/api\/v1\/dashboard\/(stickers|synthesis-library)(\/[^/]+)?$/.test(req.path)) return next();
  const incoming = req.get('X-Upload-Id') ?? '';
  const id = /^[a-f0-9-]{36}$/i.test(incoming) ? incoming : randomUUID();
  const start = performance.now();
  let previous = start, logged = false;
  const stages: Record<string, number> = {};
  res.locals.markUpload = (stage: string) => {
    const now = performance.now(); stages[stage] = Math.round(now - previous); previous = now;
  };
  res.set('X-Upload-Id', id);
  const log = (aborted: boolean) => {
    if (logged) return; logged = true;
    console.info('[upload-server]', JSON.stringify({ id, kind: req.path.includes('synthesis-library') ? 'synthesis' : 'sticker', status: res.statusCode, aborted, requestBytes: Number(req.get('content-length')) || null, totalMs: Math.round(performance.now() - start), stages }));
  };
  res.on('finish', () => log(false));
  res.on('close', () => { if (!res.writableFinished) log(true); });
  next();
};

export const uploadClientTiming: RequestHandler = (req, res) => {
  const b = req.body ?? {};
  if (!/^[a-f0-9-]{36}$/i.test(b.id ?? '') || !['sticker', 'synthesis'].includes(b.kind) ||
      !['load', 'error', 'timeout', 'abort'].includes(b.outcome) ||
      !['totalMs', 'transferMs', 'bytes', 'status'].every(k => b[k] === null || (typeof b[k] === 'number' && Number.isFinite(b[k]) && b[k] >= 0 && b[k] <= 100_000_000))) {
    res.status(400).json({ error: 'invalid upload timing' }); return;
  }
  console.info('[upload-client]', JSON.stringify({ id: b.id, kind: b.kind, outcome: b.outcome, totalMs: b.totalMs, transferMs: b.transferMs, bytes: b.bytes, status: b.status }));
  res.json({ ok: true });
};
