import 'dotenv/config';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import pg from 'pg';

const __dirname = dirname(fileURLToPath(import.meta.url));

export function createPool(): pg.Pool {
  return new pg.Pool({
    host: process.env.PGHOST ?? 'localhost',
    port: Number(process.env.PGPORT ?? 5432),
    user: process.env.PGUSER ?? 'ime',
    password: process.env.PGPASSWORD,
    database: process.env.PGDATABASE ?? 'personal_ime',
    max: 10,
  });
}

/** 执行迁移目录下所有 .sql 文件（按文件名排序） */
export async function migrate(pool: pg.Pool): Promise<void> {
  const migrationsDir = join(__dirname, '../../migrations');
  const { readdirSync } = await import('node:fs');
  const files = readdirSync(migrationsDir).filter((f) => f.endsWith('.sql')).sort();
  for (const file of files) {
    const sql = readFileSync(join(migrationsDir, file), 'utf8');
    await pool.query(sql);
    console.log(`[migrate] applied ${file}`);
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const pool = createPool();
  try {
    await migrate(pool);
    console.log('[migrate] done');
  } finally {
    await pool.end();
  }
}
