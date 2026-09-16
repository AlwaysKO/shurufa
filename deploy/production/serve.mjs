const { createPool } = await import(`${process.cwd()}/dist/db/migrate.js`);
const { createApp } = await import(`${process.cwd()}/dist/app.js`);
const { startAnalysisJob } = await import(`${process.cwd()}/dist/jobs/analyze.js`);
const pool = createPool();
await pool.query('SELECT 1');
const server = createApp(pool).listen(Number(process.env.PORT), '127.0.0.1', () => {
  console.log(`Shurufa listening on 127.0.0.1:${process.env.PORT}`);
  startAnalysisJob(pool);
});
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => {
  server.close(async () => { await pool.end(); process.exit(0); });
  setTimeout(() => process.exit(1), 10000).unref();
});
