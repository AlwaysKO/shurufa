import 'dotenv/config';
import { randomUUID } from 'node:crypto';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import pg from 'pg';
import { describe, expect, it } from 'vitest';

const LEGACY = '00000000-0000-0000-0000-000000000001';
const A = '00000000-0000-4000-8000-00000000000a';
const B = '00000000-0000-4000-8000-00000000000b';
const enabled = Boolean(process.env.PGDATABASE && process.env.PGUSER);

describe('009 device user isolation migration (PostgreSQL)', () => {
  it.skipIf(!enabled)('拆分多设备历史记录且可重复执行', async () => {
    const pool = new pg.Pool({
      host: process.env.PGHOST ?? 'localhost', port: Number(process.env.PGPORT ?? 5432),
      user: process.env.PGUSER, password: process.env.PGPASSWORD, database: process.env.PGDATABASE,
    });
    const client = await pool.connect();
    const schema = `migration_test_${randomUUID().replaceAll('-', '')}`;
    await client.query(`CREATE SCHEMA ${schema}`);
    await client.query(`SET search_path TO ${schema}`);
    const migrationsDir = join(process.cwd(), 'migrations');
    const files = readdirSync(migrationsDir).filter((name) => name.endsWith('.sql')).sort();
    for (const file of files.slice(0, -1)) {
      await client.query(readFileSync(join(migrationsDir, file), 'utf8'));
    }
    const sql = readFileSync(join(migrationsDir, '009_device_user_isolation.sql'), 'utf8');
    const c = await client.query(`INSERT INTO chat_conversation(user_id,platform,account_key,external_key,conversation_type,identity_confidence) VALUES($1,'wechat','account','peer','direct',1) RETURNING id`, [LEGACY]);
    const a = await client.query(`INSERT INTO media_asset(user_id,sha256,mime_type,storage_path,byte_size) VALUES($1,$2,'image/webp','chat/aa/a.webp',1) RETURNING id`, [LEGACY, 'a'.repeat(64)]);
    for (const [i, device] of [A, B].entries()) {
      const id = `00000000-0000-4000-8000-00000000001${i}`;
      await client.query(`INSERT INTO chat_message(id,user_id,device_id,conversation_id,platform,fingerprint,content_fingerprint,sender_key,direction,message_type,captured_at) VALUES($1,$2,$3,$4,'wechat',$5,$6,'peer','incoming','image',NOW())`, [id, LEGACY, device, c.rows[0].id, String(i).repeat(64), String(i + 2).repeat(64)]);
      await client.query('INSERT INTO chat_message_asset(message_id,asset_id) VALUES($1,$2)', [id, a.rows[0].id]);
    }
    await client.query("INSERT INTO user_phrase(user_id,content) VALUES($1,'旧常用语')", [LEGACY]);
    await client.query("INSERT INTO phrase_stat(user_id,phrase) VALUES($1,'测试短语')", [LEGACY]);
    await client.query("INSERT INTO completion_candidate(user_id,prefix,completion) VALUES($1,'测','测试短语')", [LEGACY]);
    await client.query("INSERT INTO analysis_state(key) VALUES('last_analyzed_epoch_ms')");

    await client.query(sql);
    await client.query(sql);

    expect((await client.query('SELECT id FROM device WHERE id IN ($1,$2)', [A, B])).rowCount).toBe(2);
    expect((await client.query('SELECT DISTINCT user_id,conversation_id FROM chat_message')).rowCount).toBe(2);
    expect((await client.query('SELECT 1 FROM chat_message m JOIN chat_conversation c ON c.id=m.conversation_id WHERE m.user_id<>m.device_id OR m.user_id<>c.user_id')).rowCount).toBe(0);
    expect((await client.query('SELECT 1 FROM chat_message_asset l JOIN chat_message m ON m.id=l.message_id JOIN media_asset a ON a.id=l.asset_id WHERE a.user_id<>m.user_id')).rowCount).toBe(0);
    expect((await client.query('SELECT * FROM user_phrase WHERE user_id=$1', [LEGACY])).rowCount).toBe(1);
    expect((await client.query('SELECT * FROM phrase_stat WHERE user_id=$1', [LEGACY])).rowCount).toBe(0);
    expect((await client.query('SELECT * FROM completion_candidate WHERE user_id=$1', [LEGACY])).rowCount).toBe(0);
    await client.query('RESET search_path');
    await client.query(`DROP SCHEMA ${schema} CASCADE`);
    client.release();
    await pool.end();
  });
});
