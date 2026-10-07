import type pg from 'pg';

export type StatisticsRetentionDataset = 'call-logs' | 'navigation' | 'app-usage' | 'input';

export async function lockStatisticsRetention(db: pg.PoolClient, dataset: StatisticsRetentionDataset, user: string): Promise<void> {
  await db.query("SELECT pg_advisory_xact_lock(hashtextextended('statistics-retention:' || $1 || ':' || $2,0))", [dataset, user.toLowerCase()]);
}

export async function withStatisticsRetentionLock<T>(pool: pg.Pool, dataset: StatisticsRetentionDataset, user: string, run: (db: pg.PoolClient) => Promise<T>): Promise<T> {
  const db = await pool.connect();
  try {
    await db.query('BEGIN');
    await lockStatisticsRetention(db, dataset, user);
    const result = await run(db);
    await db.query('COMMIT');
    return result;
  } catch (error) { await db.query('ROLLBACK').catch(() => {}); throw error; }
  finally { db.release(); }
}
