import { randomUUID } from 'node:crypto';
import type pg from 'pg';
import { loadStickerLibrary, splitStickerKeywords, stickerGroupKeyword, stickerAssetKey, StickerGroupError } from '../api/stickerLibrary.js';
import { cleanDeviceFiles, queueUploadFileCleanup } from '../lib/deleteDeviceData.js';
import { SHARED_STICKER_OWNER as OWNER } from './shared.js';

export interface StickerGroupDeletion { keyword: string; revision: string }
export interface StickerGroupDeleteInput { confirm: 'DELETE'; aliases: string[]; assetKeys: string[] }
const strings = (value: unknown): value is string[] => Array.isArray(value) && value.every(item => typeof item === 'string');
const sameItems = (a: string[], b: string[]) => JSON.stringify([...a].sort()) === JSON.stringify([...b].sort());

/** 调用者持有分组事务锁；导入可对尚不存在的规划词保存删除记录。 */
export async function deleteStickerGroupInTransaction(db: pg.PoolClient, deletion: StickerGroupDeletion, root = process.cwd(), input?: StickerGroupDeleteInput) {
  await db.query('LOCK TABLE sticker IN SHARE ROW EXCLUSIVE MODE');
  const library = await loadStickerLibrary(db, OWNER, root, input === undefined);
  const group = library.groups.find(item => item.keyword === deletion.keyword);
  if (input) {
    if (!group) throw new StickerGroupError(404, '关键词组不存在或已删除，请刷新');
    if (!sameItems(input.aliases, group.aliases) || !sameItems(input.assetKeys, group.assets.map(stickerAssetKey))) {
      throw new StickerGroupError(409, '组内说法或图片已变化，请刷新后重新确认删除');
    }
  }
  const paths: string[] = [];
  const stickers = (await db.query<{id: number; keywords: string; file_name: string}>('SELECT id,keywords,file_name FROM sticker FOR UPDATE')).rows;
  for (const sticker of stickers) {
    const keywords = splitStickerKeywords(sticker.keywords);
    const remaining = keywords.filter(word => stickerGroupKeyword(word) !== deletion.keyword);
    if (keywords.length === remaining.length) continue;
    if (remaining.length) await db.query('UPDATE sticker SET keywords=$1 WHERE id=$2', [remaining.join(','), sticker.id]);
    else {
      await db.query('DELETE FROM sticker WHERE id=$1', [sticker.id]);
      paths.push(`stickers/${sticker.file_name}`);
    }
  }
  const otherHashes = new Set(library.groups.filter(item => item.keyword !== deletion.keyword)
    .flatMap(item => item.assets.filter(asset => asset.source === 'system').map(asset => asset.sha256)));
  for (const asset of group?.assets ?? []) {
    if (asset.source !== 'system' || !asset.sha256 || otherHashes.has(asset.sha256)) continue;
    await db.query(`INSERT INTO keyword_gif_removal(user_id,sha256,asset_id) VALUES($1,$2,$3)
      ON CONFLICT(user_id,sha256) DO NOTHING`, [OWNER, asset.sha256, String(asset.id)]);
  }
  for (const table of ['sticker_keyword', 'sticker_group_settings']) {
    const rows = (await db.query<{keyword: string}>(`SELECT keyword FROM ${table} WHERE user_id=$1`, [OWNER])).rows;
    for (const row of rows) if (stickerGroupKeyword(row.keyword) === deletion.keyword) {
      await db.query(`DELETE FROM ${table} WHERE user_id=$1 AND keyword=$2`, [OWNER, row.keyword]);
    }
  }
  await db.query(`INSERT INTO sticker_group_deletion(keyword,revision) VALUES($1,$2)
    ON CONFLICT(keyword) DO UPDATE SET revision=EXCLUDED.revision`, [deletion.keyword, deletion.revision]);
  return queueUploadFileCleanup(db, paths);
}

export async function deleteStickerGroup(pool: pg.Pool, keyword: string, body: unknown) {
  const input = body as Partial<StickerGroupDeleteInput> | null;
  if (!input || input.confirm !== 'DELETE' || !strings(input.aliases) || !strings(input.assetKeys)) {
    throw new StickerGroupError(400, '请确认删除，并提供当前组的说法和图片列表');
  }
  const db = await pool.connect();
  let cleanup: string | null;
  try {
    await db.query('BEGIN');
    await db.query('SELECT pg_advisory_xact_lock(hashtext($1))', [`sticker-groups:${OWNER}`]);
    cleanup = await deleteStickerGroupInTransaction(db, {keyword, revision: randomUUID()}, process.cwd(), input as StickerGroupDeleteInput);
    await db.query('COMMIT');
  } catch (error) { await db.query('ROLLBACK'); throw error; }
  finally { db.release(); }
  const filesPending = cleanup ? !await cleanDeviceFiles(pool, cleanup).catch(() => false) : false;
  return { keyword, files_pending: filesPending };
}
