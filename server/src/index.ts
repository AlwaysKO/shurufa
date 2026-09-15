import 'dotenv/config';
import { createPool } from './db/migrate.js';
import { createApp } from './app.js';
import { startAnalysisJob } from './jobs/analyze.js';

const pool = createPool();
const port = Number(process.env.PORT ?? 3000);

const app = createApp(pool);

app.listen(port, () => {
  console.log(`[server] listening on http://localhost:${port}`);
  startAnalysisJob(pool);
});

// 优雅退出
process.on('SIGINT', async () => {
  console.log('[server] shutting down');
  await pool.end();
  process.exit(0);
});
