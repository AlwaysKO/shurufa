import { readFile, readdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
const { default: pg } = await import(`${process.cwd()}/node_modules/pg/lib/index.js`);
const client = new pg.Client();
await client.connect();
try {
  await client.query('BEGIN');
  await client.query('SELECT pg_advisory_xact_lock(7310310)');
  await client.query('CREATE TABLE IF NOT EXISTS deployment_migration (name text PRIMARY KEY, checksum text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now())');
  for (const name of (await readdir('migrations')).filter(n => n.endsWith('.sql')).sort()) {
    const sql = await readFile(`migrations/${name}`, 'utf8');
    const checksum = createHash('sha256').update(sql).digest('hex');
    const prior = await client.query('SELECT checksum FROM deployment_migration WHERE name=$1', [name]);
    if (prior.rowCount) {
      if (prior.rows[0].checksum !== checksum) throw new Error(`Applied migration changed: ${name}; add a new migration instead`);
      continue;
    }
    await client.query(sql);
    await client.query('INSERT INTO deployment_migration(name,checksum) VALUES($1,$2)', [name, checksum]);
    console.log(`Migrated ${name}`);
  }
  await client.query('COMMIT');
} catch (error) {
  await client.query('ROLLBACK');
  throw error;
} finally { await client.end(); }
