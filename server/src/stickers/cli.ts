import 'dotenv/config';
import { createPool } from '../db/migrate.js';
import { exportStickerBundle, importStickerBundle } from './bundle.js';
const action = process.argv[2];
if (action !== 'export' && action !== 'import') throw new Error('用法：stickers/cli.js export|import');
const pool = createPool();
try {
  if (action === 'export') await exportStickerBundle(pool);
  else await importStickerBundle(pool);
  console.log(`[stickers] ${action} complete`);
} finally { await pool.end(); }
