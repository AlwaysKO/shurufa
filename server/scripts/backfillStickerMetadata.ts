/** Run once after migration 018. Idempotent; only historical rows lacking a SHA read bytes. */
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { basename, join } from 'node:path';
import sharp from 'sharp';
import { createPool } from '../src/db/migrate.js';
const pool = createPool();
try {
    const rows = await pool.query('SELECT id,file_name FROM sticker WHERE sha256 IS NULL ORDER BY id');
    let failed = 0;
    for (const row of rows.rows) {
        try {
            if (basename(row.file_name) !== row.file_name)
                throw Error('invalid stored filename');
            const bytes = await readFile(join(process.cwd(), 'uploads/stickers', row.file_name));
            const meta = await sharp(bytes, { animated: true }).metadata();
            if (!['gif', 'png', 'jpeg', 'webp'].includes(meta.format ?? ''))
                throw Error('unsupported image');
            await pool.query('UPDATE sticker SET sha256=$1,width=$2,height=$3,format=$4 WHERE id=$5 AND sha256 IS NULL', [createHash('sha256').update(bytes).digest('hex'), meta.width, meta.pageHeight ?? meta.height, meta.format === 'jpeg' ? 'jpg' : meta.format, row.id]);
        }
        catch (e) {
            failed++;
            console.error(`sticker ${row.id}: ${(e as Error).message}`);
        }
    }
    console.log(`backfill: ${rows.rows.length - failed} updated, ${failed} failed`);
    if (failed)
        process.exitCode = 1;
}
finally {
    await pool.end();
}
