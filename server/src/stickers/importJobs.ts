import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { unlink } from 'node:fs/promises';
import type pg from 'pg';
import { withGroupLock } from '../api/stickerLibrary.js';
import { SHARED_STICKER_OWNER } from './shared.js';
import { importMaterial, matchMaterials, validateMaterialSha } from './materials.js';

type Db = Pick<pg.Pool, 'query'>;
export const IMPORT_LIMITS = {
  pairingSeconds: 600,
  leaseSeconds: 90,
  jobSeconds: 3600,
  pairings: 10,
  agents: 20,
  jobs: 1000,
  entries: 10000,
  batch: 500,
} as const;
export const SOURCE_ERROR_CODES = [
  'key_not_found',
  'snapshot_unstable',
  'wechat_not_running',
  'download_failed',
  'invalid_image',
  'collector_failed',
] as const;
export class ImportError extends Error {
  constructor(
    public status: number,
    public code: string,
  ) {
    super(code);
  }
}
const digest = (text: string) => createHash('sha256').update(text).digest('hex');
function fail(status: number, code: string): never {
  throw new ImportError(status, code);
}
function uuid(value: string) {
  if (!/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(value)) fail(400, 'invalid_id');
  return value;
}
export function exactBody(value: unknown, keys: string[]): Record<string, unknown> {
  if (
    !value ||
    typeof value !== 'object' ||
    Array.isArray(value) ||
    Object.keys(value).some((k) => !keys.includes(k))
  )
    fail(400, 'invalid_body');
  return value as Record<string, unknown>;
}
export class ImportJobs {
  constructor(
    private pool: pg.Pool,
    private root = process.cwd(),
  ) {}
  private transaction<T>(fn: (db: Db) => Promise<T>) {
    return withGroupLock(this.pool, SHARED_STICKER_OWNER, fn);
  }
  private async expire(db: Db) {
    await db.query(
      "UPDATE sticker_import_job SET status='expired',lease_hash=NULL,lease_until=NULL WHERE status IN ('queued','running') AND expires_at<=clock_timestamp()",
    );
  }
  private async authenticate(db: Db, token: string) {
    if (typeof token !== 'string' || !/^sfi_[a-f0-9]{64}$/.test(token)) fail(401, 'unauthorized');
    const agent = (
      await db.query(
        'SELECT id FROM sticker_import_agent WHERE token_hash=$1 AND revoked_at IS NULL FOR UPDATE',
        [digest(token)],
      )
    ).rows[0];
    if (!agent) fail(401, 'unauthorized');
    return agent.id as string;
  }
  private async lease(db: Db, token: string, id: string, lease: string) {
    const agent = await this.authenticate(db, token);
    uuid(id);
    if (typeof lease !== 'string' || !/^[a-f0-9]{64}$/.test(lease)) fail(409, 'inactive_lease');
    const row = (
      await db.query(
        "SELECT id FROM sticker_import_job WHERE id=$1 AND agent_id=$2 AND status='running' AND expires_at>clock_timestamp() AND lease_until>clock_timestamp() AND lease_hash=$3 FOR UPDATE",
        [id, agent, digest(lease)],
      )
    ).rows[0];
    if (!row) fail(409, 'inactive_lease');
  }
  private async job(db: Db, id: string) {
    uuid(id);
    const row = (
      await db.query(
        'SELECT id,agent_id,kind,status,created_at,expires_at,lease_until,discovered,validated,validation_failed,source_errors,job_error_code FROM sticker_import_job WHERE id=$1',
        [id],
      )
    ).rows[0];
    if (!row) fail(404, 'job_not_found');
    const counts = { existing: 0, missing: 0, imported: 0, failed: 0 };
    for (const r of (
      await db.query(
        'SELECT status,count(*)::int AS count FROM sticker_import_entry WHERE job_id=$1 GROUP BY status',
        [id],
      )
    ).rows)
      counts[r.status as keyof typeof counts] = r.count;
    const errors = (
      await db.query(
        "SELECT error_code AS code,count(*)::int AS count FROM sticker_import_entry WHERE job_id=$1 AND status='failed' GROUP BY error_code",
        [id],
      )
    ).rows;
    return {
      sourceErrors: row.source_errors as Record<string, number>,
      jobErrorCode: row.job_error_code as string | null,
      errors,
      id: row.id as string,
      agentId: row.agent_id as string,
      kind: 'wechat_favorites' as const,
      status: row.status as string,
      createdAt: row.created_at,
      expiresAt: row.expires_at,
      leaseUntil: row.lease_until,
      discovered: row.discovered as number,
      validated: row.validated as number,
      validationFailed: row.validation_failed as number,
      counts,
    };
  }
  async createPairing() {
    return this.transaction(async (db) => {
      await db.query('DELETE FROM sticker_import_pairing WHERE expires_at<=clock_timestamp()');
      if (
        Number((await db.query('SELECT count(*) FROM sticker_import_pairing')).rows[0].count) >=
        IMPORT_LIMITS.pairings
      )
        fail(429, 'pairing_limit');
      const code = randomBytes(8).toString('hex');
      const row = (
        await db.query(
          "INSERT INTO sticker_import_pairing(code_hash,expires_at) VALUES($1,clock_timestamp()+interval '10 minutes') RETURNING expires_at",
          [digest(code)],
        )
      ).rows[0];
      return { code, expiresAt: row.expires_at };
    });
  }
  async pair(code: unknown, name: unknown) {
    return this.transaction(async (db) => {
      if (typeof code !== 'string' || !/^[a-f0-9]{16}$/.test(code)) fail(401, 'invalid_pairing');
      if (
        typeof name !== 'string' ||
        !name.trim() ||
        name.length > 64 ||
        /[\x00-\x1f\x7f<>/\\:]/.test(name)
      )
        fail(400, 'invalid_name');
      // Keep even revoked agent history bounded; administrators cannot create unlimited records.
      if (
        Number((await db.query('SELECT count(*) FROM sticker_import_agent')).rows[0].count) >=
        IMPORT_LIMITS.agents
      )
        fail(429, 'agent_limit');
      const consumed = await db.query(
        'DELETE FROM sticker_import_pairing WHERE code_hash=$1 AND expires_at>clock_timestamp() RETURNING code_hash',
        [digest(code)],
      );
      if (!consumed.rowCount) fail(401, 'invalid_pairing');
      const token = 'sfi_' + randomBytes(32).toString('hex'),
        id = randomUUID();
      await db.query('INSERT INTO sticker_import_agent(id,name,token_hash) VALUES($1,$2,$3)', [
        id,
        name.trim(),
        digest(token),
      ]);
      return { token, agent: { id, name: name.trim() } };
    });
  }
  async agents() {
    return (
      await this.pool.query(
        'SELECT id,name,created_at AS "createdAt",last_seen_at AS "lastSeenAt",revoked_at AS "revokedAt" FROM sticker_import_agent ORDER BY created_at DESC LIMIT 20',
      )
    ).rows;
  }
  async revoke(id: string) {
    return this.transaction(async (db) => {
      uuid(id);
      await db.query('UPDATE sticker_import_agent SET revoked_at=clock_timestamp() WHERE id=$1', [
        id,
      ]);
      await db.query(
        "UPDATE sticker_import_job SET status='cancelled',lease_hash=NULL,lease_until=NULL WHERE agent_id=$1 AND status IN ('queued','running')",
        [id],
      );
    });
  }
  async heartbeat(token: string) {
    return this.transaction(async (db) => {
      const id = await this.authenticate(db, token);
      await db.query('UPDATE sticker_import_agent SET last_seen_at=clock_timestamp() WHERE id=$1', [
        id,
      ]);
      return { ok: true };
    });
  }
  async createJob(agentId: string) {
    return this.transaction(async (db) => {
      uuid(agentId);
      await this.expire(db);
      if (
        !(
          await db.query('SELECT id FROM sticker_import_agent WHERE id=$1 AND revoked_at IS NULL', [
            agentId,
          ])
        ).rowCount
      )
        fail(404, 'agent_not_found');
      const active = (
        await db.query(
          "SELECT id FROM sticker_import_job WHERE agent_id=$1 AND status IN ('queued','running')",
          [agentId],
        )
      ).rows[0];
      if (active) return this.job(db, active.id);
      await db.query(
        "DELETE FROM sticker_import_job WHERE status NOT IN ('queued','running') AND created_at<clock_timestamp()-interval '7 days'",
      );
      if (
        Number((await db.query('SELECT count(*) FROM sticker_import_job')).rows[0].count) >=
        IMPORT_LIMITS.jobs
      )
        fail(429, 'job_limit');
      const id = randomUUID();
      await db.query(
        "INSERT INTO sticker_import_job(id,agent_id,expires_at) VALUES($1,$2,clock_timestamp()+interval '1 hour')",
        [id, agentId],
      );
      return this.job(db, id);
    });
  }
  async getJob(id: string) {
    return this.transaction(async (db) => {
      await this.expire(db);
      return this.job(db, id);
    });
  }
  async jobs() {
    return this.transaction(async (db) => {
      await this.expire(db);
      const rows = (
        await db.query('SELECT id FROM sticker_import_job ORDER BY created_at DESC LIMIT 100')
      ).rows;
      return Promise.all(rows.map((r) => this.job(db, r.id)));
    });
  }
  async cancel(id: string) {
    return this.transaction(async (db) => {
      uuid(id);
      await db.query(
        "UPDATE sticker_import_job SET status='cancelled',lease_hash=NULL,lease_until=NULL WHERE id=$1 AND status IN ('queued','running')",
        [id],
      );
      return this.job(db, id);
    });
  }
  async claim(token: string) {
    return this.transaction(async (db) => {
      const agent = await this.authenticate(db, token);
      await this.expire(db);
      const row = (
        await db.query(
          "SELECT id FROM sticker_import_job WHERE agent_id=$1 AND (status='queued' OR (status='running' AND lease_until<=clock_timestamp())) ORDER BY created_at LIMIT 1 FOR UPDATE",
          [agent],
        )
      ).rows[0];
      if (!row) return null;
      const leaseToken = randomBytes(32).toString('hex');
      await db.query(
        "UPDATE sticker_import_job SET status='running',lease_hash=$2,lease_until=LEAST(expires_at,clock_timestamp()+interval '90 seconds') WHERE id=$1",
        [row.id, digest(leaseToken)],
      );
      return { ...(await this.job(db, row.id)), leaseToken };
    });
  }
  async renew(token: string, id: string, lease: string) {
    return this.transaction(async (db) => {
      await this.lease(db, token, id, lease);
      await db.query(
        "UPDATE sticker_import_job SET lease_until=LEAST(expires_at,clock_timestamp()+interval '90 seconds') WHERE id=$1",
        [id],
      );
      return this.job(db, id);
    });
  }
  private async entry(
    db: Db,
    id: string,
    sha: string,
    status: string,
    error: string | null = null,
  ) {
    const old = (
      await db.query('SELECT status FROM sticker_import_entry WHERE job_id=$1 AND sha256=$2', [
        id,
        sha,
      ])
    ).rows[0];
    if (
      !old &&
      Number(
        (await db.query('SELECT count(*) FROM sticker_import_entry WHERE job_id=$1', [id])).rows[0]
          .count,
      ) >= IMPORT_LIMITS.entries
    )
      fail(429, 'entry_limit');
    // A replay/match/failure must never downgrade a server-confirmed success.
    if (old && (old.status === 'imported' || old.status === 'existing') && status !== 'imported')
      return;
    await db.query(
      'INSERT INTO sticker_import_entry(job_id,sha256,status,error_code) VALUES($1,$2,$3,$4) ON CONFLICT(job_id,sha256) DO UPDATE SET status=EXCLUDED.status,error_code=EXCLUDED.error_code',
      [id, sha, status, error],
    );
  }
  async match(token: string, id: string, lease: string, sha256s: unknown) {
    return this.transaction(async (db) => {
      await this.lease(db, token, id, lease);
      const result = await matchMaterials(db, sha256s, this.root);
      for (const item of result.items)
        await this.entry(
          db,
          id,
          item.sha256,
          item.status === 'unavailable' ? 'failed' : item.status,
          item.status === 'unavailable' ? 'unavailable' : null,
        );
      await this.lease(db, token, id, lease);
      return result;
    });
  }
  async upload(
    token: string,
    id: string,
    lease: string,
    input: { buffer: Buffer; filename: unknown; sha256?: unknown },
  ) {
    const files: string[] = [];
    try {
      return await this.transaction(async (db) => {
        await this.lease(db, token, id, lease);
        const sha = validateMaterialSha(input.sha256);
        if (
          !(
            await db.query(
              'SELECT sha256 FROM sticker_import_entry WHERE job_id=$1 AND sha256=$2',
              [id, sha],
            )
          ).rowCount
        )
          fail(409, 'match_required');
        const result = await importMaterial(db, input, files, this.root);
        await this.lease(db, token, id, lease);
        await this.entry(db, id, result.material.sha256, result.status);
        return result;
      });
    } catch (error) {
      await Promise.all(files.map((path) => unlink(path).catch(() => undefined)));
      throw error;
    }
  }
  async report(token: string, id: string, lease: string, input: unknown) {
    const body = exactBody(input, [
      'discovered',
      'validated',
      'validationFailed',
      'sourceErrors',
      'failures',
    ]);
    for (const key of ['discovered', 'validated', 'validationFailed'])
      if (
        !Number.isInteger(body[key]) ||
        Number(body[key]) < 0 ||
        Number(body[key]) > IMPORT_LIMITS.entries
      )
        fail(400, 'invalid_statistics');
    if (Number(body.validated) + Number(body.validationFailed) > Number(body.discovered))
      fail(400, 'invalid_statistics');
    const sourceErrors = exactBody(body.sourceErrors, [...SOURCE_ERROR_CODES]);
    if (
      Object.values(sourceErrors).some((n) => !Number.isInteger(n) || Number(n) < 0) ||
      Object.values(sourceErrors).reduce<number>((sum, n) => sum + Number(n), 0) !==
        body.validationFailed
    )
      fail(400, 'invalid_source_errors');
    if (!Array.isArray(body.failures) || body.failures.length > IMPORT_LIMITS.batch)
      fail(400, 'invalid_failures');
    const failures = body.failures.map((value) => {
      const f = exactBody(value, ['sha256', 'code']);
      const sha256 = validateMaterialSha(f.sha256);
      if (
        !['download_failed', 'invalid_image', 'hash_mismatch', 'upload_failed'].includes(
          String(f.code),
        )
      )
        fail(400, 'invalid_failure_code');
      return { sha256, code: String(f.code) };
    });
    return this.transaction(async (db) => {
      await this.lease(db, token, id, lease);
      const prior = await this.job(db, id);
      if (
        Number(body.discovered) < prior.discovered ||
        Number(body.validated) < prior.validated ||
        Number(body.validationFailed) < prior.validationFailed ||
        Object.entries(prior.sourceErrors).some(([key, n]) => Number(sourceErrors[key] ?? 0) < n)
      )
        fail(409, 'stale_statistics');
      await db.query(
        'UPDATE sticker_import_job SET discovered=$2,validated=$3,validation_failed=$4,source_errors=$5 WHERE id=$1',
        [id, body.discovered, body.validated, body.validationFailed, JSON.stringify(sourceErrors)],
      );
      for (const f of failures) {
        // Only match creates an entry. Otherwise a forged failure could authorize an upload.
        if (
          !(
            await db.query(
              'SELECT sha256 FROM sticker_import_entry WHERE job_id=$1 AND sha256=$2',
              [id, f.sha256],
            )
          ).rowCount
        )
          fail(409, 'match_required');
        await this.entry(db, id, f.sha256, 'failed', f.code);
      }
      await this.lease(db, token, id, lease);
      return this.job(db, id);
    });
  }
  async complete(
    token: string,
    id: string,
    lease: string,
    status: unknown,
    jobErrorCode: unknown = null,
  ) {
    if (status !== 'completed' && status !== 'failed') fail(400, 'invalid_status');
    if (
      jobErrorCode !== null &&
      !SOURCE_ERROR_CODES.includes(jobErrorCode as (typeof SOURCE_ERROR_CODES)[number])
    )
      fail(400, 'invalid_failure_code');
    if (status === 'completed' && jobErrorCode !== null) fail(400, 'invalid_failure_code');
    return this.transaction(async (db) => {
      await this.lease(db, token, id, lease);
      if (
        status === 'completed' &&
        (
          await db.query(
            "SELECT 1 FROM sticker_import_entry WHERE job_id=$1 AND status='missing' LIMIT 1",
            [id],
          )
        ).rowCount
      )
        fail(409, 'unfinished_entries');
      await db.query(
        'UPDATE sticker_import_job SET status=$2,job_error_code=$3,lease_hash=NULL,lease_until=NULL WHERE id=$1',
        [id, status, jobErrorCode],
      );
      return this.job(db, id);
    });
  }
}
