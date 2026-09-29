import { Router, json, raw, type RequestHandler, type ErrorRequestHandler } from 'express';
import type pg from 'pg';
import { exactBody, ImportError, ImportJobs } from '../stickers/importJobs.js';
import { StickerGroupError } from './stickerLibrary.js';
import { publishStickerBundle } from '../stickers/bundle.js';

/** One fixed-size global bucket, deliberately independent of proxy IP headers. */
export function createImportGate(
  limits = { requests: 240, pairings: 10, concurrent: 8 },
): RequestHandler {
  let windowStart = Date.now(),
    requests = 0,
    pairings = 0,
    active = 0;
  return (req, res, next) => {
    if (Date.now() - windowStart >= 60000) {
      windowStart = Date.now();
      requests = 0;
      pairings = 0;
    }
    const isPair = /^\/pair\/?$/i.test(req.path);
    if (
      active >= limits.concurrent ||
      requests >= limits.requests ||
      (isPair && pairings >= limits.pairings)
    ) {
      res.set('Retry-After', '60').status(429).json({ error: 'rate_limited' });
      return;
    }
    requests++;
    if (isPair) pairings++;
    active++;
    let released = false;
    const release = () => {
      if (!released) {
        released = true;
        active--;
      }
    };
    res.locals.releaseImportSlot = release;
    res.once('finish', release);
    // A disconnected client does not cancel a running database/file operation.
    res.once('close', () => {
      if (!res.locals.importOperationActive) release();
    });
    next();
  };
}
const agentGate = createImportGate();
const dashboardGate = createImportGate({ requests: 240, pairings: 10, concurrent: 8 });
const safeErrors: ErrorRequestHandler = (error, _req, res, _next) => {
  if (error instanceof ImportError) {
    res.status(error.status).json({ error: error.code });
    return;
  }
  if (error instanceof StickerGroupError) {
    res.status(error.status).json({ error: 'invalid_material' });
    return;
  }
  if (error?.type === 'entity.too.large') {
    res.status(413).json({ error: 'payload_too_large' });
    return;
  }
  if (error?.type === 'entity.parse.failed') {
    res.status(400).json({ error: 'invalid_json' });
    return;
  }
  // Do not pass SQL, request bodies, token values or remote source URLs to global logging.
  res.status(500).json({ error: 'import_internal_error' });
};
const wrap =
  (fn: RequestHandler): RequestHandler =>
  (req, res, next) => {
    res.locals.importOperationActive = true;
    Promise.resolve()
      .then(() => fn(req, res, next))
      .catch(next)
      .finally(() => {
        res.locals.importOperationActive = false;
        res.locals.releaseImportSlot?.();
      });
  };
function string(value: unknown) {
  if (typeof value !== 'string') throw new ImportError(400, 'invalid_field');
  return value;
}
export function createStickerImportRouter(pool: pg.Pool): Router {
  const router = Router(),
    jobs = new ImportJobs(pool);
  router.use('/sticker-import', dashboardGate, (_q, r, next) => {
    r.set('Cache-Control', 'no-store');
    next();
  });
  router.post(
    '/sticker-import/pairings',
    wrap(async (_q, r) => {
      r.status(201).json(await jobs.createPairing());
    }),
  );
  router.get(
    '/sticker-import/agents',
    wrap(async (_q, r) => {
      r.json({ agents: await jobs.agents() });
    }),
  );
  router.post(
    '/sticker-import/agents/:id/revoke',
    wrap(async (q, r) => {
      await jobs.revoke(string(q.params.id));
      r.json({ ok: true });
    }),
  );
  router.post(
    '/sticker-import/jobs',
    wrap(async (q, r) => {
      const b = exactBody(q.body, ['agentId']);
      r.status(201).json({ job: await jobs.createJob(string(b.agentId)) });
    }),
  );
  router.get(
    '/sticker-import/jobs',
    wrap(async (_q, r) => {
      r.json({ jobs: await jobs.jobs() });
    }),
  );
  router.get(
    '/sticker-import/jobs/:id',
    wrap(async (q, r) => {
      r.json({ job: await jobs.getJob(string(q.params.id)) });
    }),
  );
  router.post(
    '/sticker-import/jobs/:id/cancel',
    wrap(async (q, r) => {
      r.json({ job: await jobs.cancel(string(q.params.id)) });
    }),
  );
  router.use('/sticker-import', safeErrors);
  return router;
}
export function createStickerImportAgentRouter(
  pool: pg.Pool,
  gate: RequestHandler = agentGate,
): Router {
  const router = Router(),
    jobs = new ImportJobs(pool);
  router.use(gate, (_q, r, next) => {
    r.set('Cache-Control', 'no-store');
    next();
  });
  router.use(json({ limit: '64kb' }));
  router.post(
    '/pair',
    wrap(async (q, r) => {
      const b = exactBody(q.body, ['code', 'name']);
      r.status(201).json(await jobs.pair(b.code, b.name));
    }),
  );
  router.use((q, r, next) => {
    const header = q.get('Authorization');
    if (!header || !/^Bearer sfi_[a-f0-9]{64}$/.test(header)) {
      r.status(401).json({ error: 'unauthorized' });
      return;
    }
    r.locals.token = header.slice(7);
    next();
  });
  const args = (q: import('express').Request, r: import('express').Response) =>
    [r.locals.token as string, string(q.params.id), q.get('X-Import-Lease') ?? ''] as const;
  router.post(
    '/heartbeat',
    wrap(async (_q, r) => {
      r.json(await jobs.heartbeat(r.locals.token));
    }),
  );
  router.post(
    '/claim',
    wrap(async (_q, r) => {
      r.json({ job: await jobs.claim(r.locals.token) });
    }),
  );
  router.post(
    '/jobs/:id/renew',
    wrap(async (q, r) => {
      r.json({ job: await jobs.renew(...args(q, r)) });
    }),
  );
  router.post(
    '/jobs/:id/match',
    wrap(async (q, r) => {
      const b = exactBody(q.body, ['sha256s']);
      r.json(await jobs.match(...args(q, r), b.sha256s));
    }),
  );
  router.post(
    '/jobs/:id/materials',
    raw({ type: 'application/octet-stream', limit: '10mb' }),
    wrap(async (q, r) => {
      if (Object.keys(q.query).some((k) => !['filename', 'sha256'].includes(k)))
        throw new ImportError(400, 'invalid_query');
      const result = await jobs.upload(...args(q, r), {
        buffer: q.body,
        filename: q.query.filename,
        sha256: q.query.sha256,
      });
      // Commit is already durable. An archive failure must not erase its image or induce a recount.
      let archiveWarning = false;
      if (result.status === 'imported')
        try {
          await publishStickerBundle(pool);
        } catch {
          archiveWarning = true;
        }
      r.status(result.status === 'imported' ? 201 : 200).json({ ...result, archiveWarning });
    }),
  );
  router.post(
    '/jobs/:id/report',
    wrap(async (q, r) => {
      r.json({ job: await jobs.report(...args(q, r), q.body) });
    }),
  );
  router.post(
    '/jobs/:id/complete',
    wrap(async (q, r) => {
      const b = exactBody(q.body, ['status', 'jobErrorCode']);
      r.json({ job: await jobs.complete(...args(q, r), b.status, b.jobErrorCode ?? null) });
    }),
  );
  router.use(safeErrors);
  return router;
}
