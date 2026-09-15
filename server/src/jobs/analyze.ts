import 'dotenv/config';
import cron from 'node-cron';
import { fileURLToPath } from 'node:url';
import type pg from 'pg';
import { runAnalysis } from '../analysis/analyze.js';
import { createPool } from '../db/migrate.js';

export function startAnalysisJob(pool: pg.Pool): void {
  const cronExpr = process.env.ANALYZE_CRON ?? '*/10 * * * *';
  const job = cron.schedule(cronExpr, async () => {
    try {
      const t0 = Date.now();
      const result = await runAnalysis(pool);
      console.log(`[analyze] done in ${Date.now() - t0}ms`, result);
    } catch (err) {
      console.error('[analyze] failed', err);
    }
  });
  console.log(`[job] analysis scheduled with cron: ${cronExpr}`);
  job.start();
}

// 独立运行：npm run analyze:once
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const pool = createPool();
  try {
    const t0 = Date.now();
    const result = await runAnalysis(pool);
    console.log(`[analyze] once done in ${Date.now() - t0}ms`, result);
  } finally {
    await pool.end();
  }
}
