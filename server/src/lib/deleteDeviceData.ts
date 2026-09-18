import { deviceSavingKey } from './deviceSaving.js';
import type pg from 'pg';
import { randomUUID } from 'node:crypto';
import { lstat, realpath, unlink } from 'node:fs/promises';
import { resolve, dirname, relative, sep } from 'node:path';

export class DeviceDeletionError extends Error {
  constructor(public status: number, message: string) { super(message); }
}
const CLEANUP_PREFIX = 'device_delete_files:';
// 显式业务表清单；禁止按数据库发现的任意表自动扩大删除范围。
const USER_TABLES = ['relationship_ai_reply_session', 'relationship_ai_call', 'relationship_ai_profile', 'relationship_profile',
  'chat_message', 'chat_conversation', 'media_asset', 'input_event', 'location_track', 'phrase_stat', 'completion_candidate',
  'user_phrase', 'sticker', 'sticker_keyword', 'synthesis_asset', 'mobile_report_receipt', 'personal_candidate_usage',
  'completion_feedback_usage', 'sticker_file_usage', 'expression_asset_usage', 'keyword_gif_removal'];
const LOCK_TABLES = [...USER_TABLES, 'device', 'input_session', 'chat_message_asset', 'dictionary_device', 'dictionary_entry', 'dictionary_policy', 'analysis_state'].sort();

function validUploadPath(path: unknown): path is string {
  return typeof path === 'string' && (/^chat\/[a-f0-9]{2}\/[a-f0-9]{64}\.(png|webp)$/.test(path)
    || /^(stickers|synthesis)\/[a-zA-Z0-9_-]+\.(gif|png|jpe?g|webp)$/.test(path));
}
async function safeFile(path: string): Promise<string> {
  if (!validUploadPath(path)) throw new DeviceDeletionError(409, '附件路径异常，未执行删除，请检查数据');
  const root = resolve(process.cwd(), 'uploads'), file = resolve(root, path);
  try {
    const actualRoot = await realpath(root), parent = await realpath(dirname(file));
    const rel = relative(actualRoot, parent);
    if (rel === '..' || rel.startsWith(`..${sep}`) || resolve(actualRoot, rel) !== parent) throw new DeviceDeletionError(409, '附件目录越界，未执行删除');
    const stat = await lstat(file);
    if (!stat.isFile() && !stat.isSymbolicLink()) throw new DeviceDeletionError(409, '附件不是文件，未执行删除');
  } catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error; }
  return file;
}

export async function deleteDeviceData(pool: pg.Pool, id: string) {
  const db = await pool.connect();
  let cleanupKey: string | null = null;
  const deleted: Record<string, number> = {};
  try {
    await db.query('BEGIN');
    await db.query("SET LOCAL lock_timeout = '5s'");
    // 词库绑定先获取同一个锁，避免组关系在判断是否独占时变化。
    await db.query('SELECT id FROM dictionary_lock WHERE id=1 FOR UPDATE');
    await db.query(`LOCK TABLE ${LOCK_TABLES.join(',')} IN SHARE ROW EXCLUSIVE MODE`);
    if (!(await db.query('SELECT id FROM device WHERE id=$1 FOR UPDATE', [id])).rowCount) throw new DeviceDeletionError(404, '该手机不存在或已删除，请刷新列表');
    // 历史异常跨用户引用必须拒绝，不能让级联删除误伤其他手机。
    for (const table of ['chat_message', 'relationship_profile', 'relationship_ai_profile', 'relationship_ai_call', 'relationship_ai_reply_session']) {
      const foreign = await db.query(`SELECT 1 FROM ${table} t JOIN chat_conversation c ON c.id=t.conversation_id WHERE c.user_id=$1 AND t.user_id<>$1 LIMIT 1`, [id]);
      if (foreign.rowCount) throw new DeviceDeletionError(409, '存在跨手机会话引用，未执行删除');
    }
    const foreignAsset = await db.query('SELECT 1 FROM chat_message_asset l JOIN media_asset a ON a.id=l.asset_id JOIN chat_message m ON m.id=l.message_id WHERE a.user_id=$1 AND m.user_id<>$1 LIMIT 1', [id]);
    if (foreignAsset.rowCount) throw new DeviceDeletionError(409, '存在跨手机附件引用，未执行删除');
    const paths = (await db.query<{ path: string }>(`SELECT storage_path AS path FROM media_asset WHERE user_id=$1
      UNION SELECT 'stickers/' || file_name FROM sticker WHERE user_id=$1
      UNION SELECT 'synthesis/' || file_name FROM synthesis_asset WHERE user_id=$1`, [id])).rows.map(r => r.path);
    for (const path of paths) await safeFile(path);
    const groups = (await db.query<{ group_id: string }>('SELECT group_id FROM dictionary_device WHERE device_id=$1', [id])).rows;
    for (const table of USER_TABLES) {
      deleted[table] = (await db.query(`DELETE FROM ${table} WHERE user_id=$1`, [id])).rowCount ?? 0;
    }
    deleted.input_session = (await db.query('DELETE FROM input_session WHERE device_id=$1', [id])).rowCount ?? 0;
    await db.query('DELETE FROM dictionary_device WHERE device_id=$1', [id]);
    for (const group of groups) await db.query('DELETE FROM dictionary_policy WHERE group_id=$1 AND NOT EXISTS (SELECT 1 FROM dictionary_device WHERE group_id=$1)', [group.group_id]);
    await db.query('DELETE FROM analysis_state WHERE key=$1', [`last_analyzed_epoch_ms:${id}`]);
    await db.query('DELETE FROM device WHERE id=$1', [id]);
    // 默认开关也显式保留，以便无需重新注册的后续上报恢复目录。
    await db.query('INSERT INTO runtime_setting(key,value) VALUES($1,$2) ON CONFLICT(key) DO NOTHING', [deviceSavingKey(id), 'true']);
    if (paths.length) {
      // 提交前不碰文件；提交后的磁盘失败可重试，不回滚成“数据库还在、文件已丢”。
      cleanupKey = `${CLEANUP_PREFIX}${randomUUID()}`;
      await db.query('INSERT INTO runtime_setting(key,value) VALUES($1,$2)', [cleanupKey, JSON.stringify(paths)]);
    }
    await db.query('COMMIT');
  } catch (error) { await db.query('ROLLBACK').catch(() => {}); throw error; }
  finally { db.release(); }
  const filesPending = cleanupKey ? !await cleanDeviceFiles(pool, cleanupKey).catch(() => false) : false;
  return { deleted_device_id: id, deleted, files_pending: filesPending };
}

/** 在调用者事务中保存精确附件清单；提交前只校验路径，绝不删除文件。 */
export async function queueUploadFileCleanup(db: pg.PoolClient, paths: string[]): Promise<string | null> {
  const unique = [...new Set(paths)];
  if (!unique.length) return null;
  for (const path of unique) await safeFile(path);
  const key = `${CLEANUP_PREFIX}${randomUUID()}`;
  await db.query('INSERT INTO runtime_setting(key,value) VALUES($1,$2)', [key, JSON.stringify(unique)]);
  return key;
}

/** 上传与清理共用media_asset表锁；再检查所有手机引用，避免同内容并发上传失去文件。 */
export async function cleanDeviceFiles(pool: pg.Pool, key: string): Promise<boolean> {
  const db = await pool.connect();
  try {
    await db.query('BEGIN');
    await db.query("SET LOCAL lock_timeout = '5s'");
    await db.query('LOCK TABLE media_asset, sticker, synthesis_asset IN SHARE ROW EXCLUSIVE MODE');
    const job = (await db.query<{ value: string }>('SELECT value FROM runtime_setting WHERE key=$1 FOR UPDATE', [key])).rows[0];
    if (!job) { await db.query('COMMIT'); return true; }
    const paths: unknown = JSON.parse(job.value);
    if (!Array.isArray(paths) || !paths.every(validUploadPath)) throw Error('invalid cleanup manifest');
    for (const path of paths as string[]) {
      const inUse = await db.query(`SELECT 1 FROM media_asset WHERE storage_path=$1
        UNION SELECT 1 FROM sticker WHERE 'stickers/' || file_name=$1
        UNION SELECT 1 FROM synthesis_asset WHERE 'synthesis/' || file_name=$1 LIMIT 1`, [path]);
      if (inUse.rowCount) continue;
      const file = await safeFile(path);
      await unlink(file).catch(error => { if (error.code !== 'ENOENT') throw error; });
    }
    await db.query('DELETE FROM runtime_setting WHERE key=$1', [key]);
    await db.query('COMMIT'); return true;
  } catch { await db.query('ROLLBACK').catch(() => {}); return false; }
  finally { db.release(); }
}
export async function retryDeviceFileCleanup(pool: pg.Pool): Promise<void> {
  const jobs = await pool.query<{ key: string }>('SELECT key FROM runtime_setting WHERE key LIKE $1 ORDER BY updated_at LIMIT 20', [`${CLEANUP_PREFIX}%`]);
  for (const job of jobs.rows) await cleanDeviceFiles(pool, job.key);
}
export function startDeviceFileCleanup(pool: pg.Pool): void {
  let running = false;
  const tick = async () => { if (running) return; running = true; try { await retryDeviceFileCleanup(pool); } catch { console.warn('[device-cleanup] retry postponed'); } finally { running = false; } };
  void tick(); setInterval(() => void tick(), 60_000).unref();
}
