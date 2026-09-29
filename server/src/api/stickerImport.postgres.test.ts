import { readFileSync, realpathSync } from 'node:fs';
import { mkdir, readdir, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { createHash, randomUUID } from 'node:crypto';
import pg from 'pg';
import { expect, it } from 'vitest';
import { ImportJobs } from '../stickers/importJobs.js';
const cluster = process.env.STICKER_SYNC_TEST_CLUSTER;
if (
  cluster &&
  (!cluster.startsWith('/tmp/shurufa-sticker-test.') ||
    readFileSync(join(cluster, 'test-instance-only'), 'utf8') !== 'sticker-sync-only')
)
  throw Error('非独立测试实例');
const test = cluster ? it : it.skip;
test('持久配对、任务租约、跨助手隔离、重放与撤销', async () => {
  const schema = 'imports_' + randomUUID().replaceAll('-', '');
  const pool = new pg.Pool({
    host: join(cluster!, 'socket'),
    port: 5433,
    user: 'sticker_test',
    database: 'sticker_sync_test',
    options: `-c search_path=${schema}`,
  });
  const root = join(cluster!, schema);
  const service = new ImportJobs(pool, root);
  try {
    expect(
      realpathSync(
        (await pool.query("SELECT current_setting('data_directory') AS dir")).rows[0].dir,
      ),
    ).toBe(realpathSync(join(cluster!, 'data')));
    await pool.query(`CREATE SCHEMA ${schema}`);
    await pool.query(
      readFileSync(new URL('../../migrations/005_sticker.sql', import.meta.url), 'utf8'),
    );
    await pool.query('ALTER TABLE sticker ADD COLUMN sha256 TEXT');
    const migration = readFileSync(
      new URL('../../migrations/036_sticker_import.sql', import.meta.url),
      'utf8',
    );
    await pool.query(migration);
    await pool.query(migration);
    await mkdir(root, { recursive: true });
    const pairing = await service.createPairing();
    const a = await service.pair(pairing.code, '电脑 A');
    await expect(service.pair(pairing.code, '电脑')).rejects.toMatchObject({ status: 401 });
    expect(
      JSON.stringify((await pool.query('SELECT * FROM sticker_import_agent')).rows),
    ).not.toContain(a.token);
    const expired = await service.createPairing();
    await pool.query("UPDATE sticker_import_pairing SET expires_at=now()-interval '1 second'");
    await expect(service.pair(expired.code, '电脑')).rejects.toMatchObject({ status: 401 });
    const b = await service.pair((await service.createPairing()).code, '电脑 B');
    const [job, repeated] = await Promise.all([
      service.createJob(a.agent.id),
      service.createJob(a.agent.id),
    ]);
    expect(job.id).toBe(repeated.id);
    const claim = await service.claim(a.token);
    expect(claim!.id).toBe(job.id);
    await expect(
      service.match(b.token, job.id, claim!.leaseToken, ['a'.repeat(64)]),
    ).rejects.toMatchObject({ status: 409 });
    const gif = Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7', 'base64');
    const sha256 = createHash('sha256').update(gif).digest('hex');
    const input = { buffer: gif, filename: 'original.gif', sha256 };
    await expect(
      service.report(a.token, job.id, claim!.leaseToken, {
        discovered: 1,
        validated: 1,
        validationFailed: 0,
        sourceErrors: {},
        failures: [{ sha256, code: 'upload_failed' }],
      }),
    ).rejects.toMatchObject({ status: 409, code: 'match_required' });
    expect((await service.getJob(job.id)).discovered).toBe(0);
    expect(
      (await pool.query('SELECT * FROM sticker_import_entry WHERE job_id=$1', [job.id])).rows,
    ).toHaveLength(0);

    await expect(service.upload(a.token, job.id, claim!.leaseToken, input)).rejects.toMatchObject({
      status: 409,
    });
    await service.match(a.token, job.id, claim!.leaseToken, [sha256]);
    await expect(
      service.complete(a.token, job.id, claim!.leaseToken, 'completed'),
    ).rejects.toMatchObject({ status: 409 });
    await service.report(a.token, job.id, claim!.leaseToken, {
      discovered: 1,
      validated: 1,
      validationFailed: 0,
      sourceErrors: {},
      failures: [{ sha256, code: 'upload_failed' }],
    });
    expect((await service.getJob(job.id)).counts.failed).toBe(1);
    const results = await Promise.all([
      service.upload(a.token, job.id, claim!.leaseToken, input),
      service.upload(a.token, job.id, claim!.leaseToken, input),
    ]);
    expect(results.map((r) => r.status).sort()).toEqual(['existing', 'imported']);
    expect((await pool.query('SELECT * FROM sticker')).rows).toHaveLength(1);
    await service.match(a.token, job.id, claim!.leaseToken, [sha256]);
    expect((await service.getJob(job.id)).counts.imported).toBe(1);
    await service.report(a.token, job.id, claim!.leaseToken, {
      discovered: 2,
      validated: 1,
      validationFailed: 1,
      sourceErrors: { invalid_image: 1 },
      failures: [],
    });
    await service.report(a.token, job.id, claim!.leaseToken, {
      discovered: 2,
      validated: 1,
      validationFailed: 1,
      sourceErrors: { invalid_image: 1 },
      failures: [],
    });
    expect((await service.getJob(job.id)).validationFailed).toBe(1);
    expect((await service.getJob(job.id)).sourceErrors).toEqual({ invalid_image: 1 });
    await pool.query(
      "UPDATE sticker_import_job SET lease_until=now()-interval '1 second' WHERE id=$1",
      [job.id],
    );
    await expect(service.upload(a.token, job.id, claim!.leaseToken, input)).rejects.toMatchObject({
      status: 409,
    });
    const recovered = await new ImportJobs(pool, root).claim(a.token);
    expect(recovered!.id).toBe(job.id);
    expect(recovered!.leaseToken).not.toBe(claim!.leaseToken);
    await expect(service.renew(a.token, job.id, claim!.leaseToken)).rejects.toMatchObject({
      status: 409,
    });
    await service.cancel(job.id);
    await expect(service.renew(a.token, job.id, recovered!.leaseToken)).rejects.toMatchObject({
      status: 409,
    });
    await expect(
      service.upload(a.token, job.id, recovered!.leaseToken, input),
    ).rejects.toMatchObject({ status: 409 });
    expect(await readdir(join(root, 'uploads/stickers'))).toHaveLength(1);
    const next = await service.createJob(a.agent.id);
    const claimed = await service.claim(a.token);
    // A statement failure after the raw file was written must roll back both DB and new file.
    const changed = Buffer.from(gif);
    changed[13] = 127;
    const otherSha = createHash('sha256').update(changed).digest('hex');
    await service.match(a.token, next.id, claimed!.leaseToken, [otherSha]);
    await pool.query(
      "CREATE FUNCTION reject_sticker() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic rollback'; END $$; CREATE TRIGGER reject_sticker BEFORE INSERT ON sticker FOR EACH ROW EXECUTE FUNCTION reject_sticker()",
    );
    await expect(
      service.upload(a.token, next.id, claimed!.leaseToken, {
        buffer: changed,
        filename: 'other.gif',
        sha256: otherSha,
      }),
    ).rejects.toThrow('synthetic rollback');
    expect((await pool.query('SELECT * FROM sticker')).rows).toHaveLength(1);
    expect(await readdir(join(root, 'uploads/stickers'))).toHaveLength(1);
    await pool.query('DROP TRIGGER reject_sticker ON sticker; DROP FUNCTION reject_sticker()');
    // A lease which expires while image processing/SQL is in flight cannot commit its new file.
    await pool.query(
      'CREATE FUNCTION slow_sticker() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(1.1); RETURN NEW; END $$; CREATE TRIGGER slow_sticker BEFORE INSERT ON sticker FOR EACH ROW EXECUTE FUNCTION slow_sticker()',
    );
    await pool.query(
      "UPDATE sticker_import_job SET lease_until=clock_timestamp()+interval '1 second' WHERE id=$1",
      [next.id],
    );
    await expect(
      service.upload(a.token, next.id, claimed!.leaseToken, {
        buffer: changed,
        filename: 'other.gif',
        sha256: otherSha,
      }),
    ).rejects.toMatchObject({ status: 409 });
    expect(await readdir(join(root, 'uploads/stickers'))).toHaveLength(1);
    expect((await pool.query('SELECT * FROM sticker')).rows).toHaveLength(1);
    await pool.query('DROP TRIGGER slow_sticker ON sticker; DROP FUNCTION slow_sticker()');
    const retried = await service.claim(a.token);
    claimed!.leaseToken = retried!.leaseToken;
    await service.report(a.token, next.id, claimed!.leaseToken, {
      discovered: 1,
      validated: 1,
      validationFailed: 0,
      sourceErrors: {},
      failures: [{ sha256: otherSha, code: 'upload_failed' }],
    });
    expect((await service.getJob(next.id)).errors).toEqual([{ code: 'upload_failed', count: 1 }]);

    await service.complete(a.token, next.id, claimed!.leaseToken, 'completed');
    await expect(
      service.complete(a.token, next.id, claimed!.leaseToken, 'completed'),
    ).rejects.toMatchObject({ status: 409 });
    const third = await service.createJob(a.agent.id);
    await pool.query(
      "UPDATE sticker_import_job SET expires_at=now()-interval '1 second' WHERE id=$1",
      [third.id],
    );
    expect(await service.claim(a.token)).toBeNull();
    expect((await service.getJob(third.id)).status).toBe('expired');
    await service.revoke(a.agent.id);
    await expect(service.heartbeat(a.token)).rejects.toMatchObject({ status: 401 });
    await pool.query('DELETE FROM sticker_import_pairing');
    for (let i = 0; i < 10; i++) await service.createPairing();
    await expect(service.createPairing()).rejects.toMatchObject({ status: 429 });
    const fourth = await service.createJob(b.agent.id);
    const fourthClaim = await service.claim(b.token);
    await service.complete(b.token, fourth.id, fourthClaim!.leaseToken, 'failed', 'key_not_found');
    expect((await service.getJob(fourth.id)).jobErrorCode).toBe('key_not_found');
    await expect(
      service.report(b.token, fourth.id, fourthClaim!.leaseToken, {
        discovered: 1,
        validated: 0,
        validationFailed: 1,
        sourceErrors: { 'https://private': 1 },
        failures: [],
      }),
    ).rejects.toMatchObject({ status: 400 });
  } finally {
    await pool.query(`DROP SCHEMA IF EXISTS ${schema} CASCADE`);
    await pool.end();
    await rm(root, { recursive: true, force: true });
  }
}, 30000);
