import { isDeepStrictEqual } from 'node:util';
import { createHash, randomUUID } from 'node:crypto';
import { existsSync } from 'node:fs';
import { mkdir, readFile, writeFile, rename, realpath } from 'node:fs/promises';
import { join, dirname, sep } from 'node:path';
import type pg from 'pg';
import { loadStickerLibrary, splitStickerKeywords } from '../api/stickerLibrary.js';
import { normalizeRecommendationPhrase } from '../expression/recommendationGroups.js';
import { SHARED_STICKER_OWNER as OWNER } from './shared.js';

type Sticker = { fileName: string; keywords: string; format: string; width: number | null; height: number | null; sha256: string };
type Setting = { keyword: string; aliases: string[] | null; assetOrder: string[] | null };
export type StickerBundle = { version: 1; keywords: string[]; stickers: Sticker[]; settings: Setting[]; removals: { sha256: string; assetId: string }[] };
const manifestPath = (root: string) => join(root, 'data/sticker-library.json');
const same = (a: unknown, b: unknown) => isDeepStrictEqual(a,b);
const hash = (bytes: Buffer) => createHash('sha256').update(bytes).digest('hex');
const filename = /^[a-zA-Z0-9_-]+\.(gif|png|jpe?g|webp)$/;
const words = (value: unknown): value is string[] => Array.isArray(value) && value.every(s => typeof s === 'string' && s.length > 0 && s.length <= 100);

export async function validateStickerBundle(value: unknown, root: string): Promise<StickerBundle> {
  const data = value as StickerBundle;
  if (!data || data.version !== 1 || !words(data.keywords) || !Array.isArray(data.stickers) || !Array.isArray(data.settings) || !Array.isArray(data.removals)) throw new Error('图库清单格式错误');
  const files = new Set<string>();
  for (const s of data.stickers) {
    if (!s || typeof s.fileName !== 'string' || !filename.test(s.fileName) || files.has(s.fileName)) throw new Error('图库文件名非法或重复');
    files.add(s.fileName);
    if (!/^[a-f0-9]{64}$/.test(s.sha256) || typeof s.keywords !== 'string' || !s.keywords.trim() || !['gif','png','jpg','webp'].includes(s.format)
      || s.fileName.replace(/^.*\./,'').replace('jpeg','jpg') !== s.format
      || [s.width,s.height].some(n => n !== null && (!Number.isInteger(n) || n <= 0))) throw new Error('图库图片元数据非法');
    const directory = await realpath(join(root, 'uploads/stickers'));
    const path = await realpath(join(directory, s.fileName));
    if (!path.startsWith(directory + sep)) throw new Error('图库文件名越界');
    if (hash(await readFile(path)) !== s.sha256) throw new Error(`图库图片 SHA256 不匹配：${s.fileName}`);
  }
  const groups = new Set<string>();
  for (const s of data.settings) {
    if (!s || !words([s.keyword]) || groups.has(s.keyword) || (s.aliases !== null && !words(s.aliases))
      || (s.assetOrder !== null && (!Array.isArray(s.assetOrder) || s.assetOrder.some(key => typeof key !== 'string' || !(key.startsWith('system:') || (key.startsWith('file:') && files.has(key.slice(5)))))))) throw new Error('图库分组设置非法');
    groups.add(s.keyword);
  }
  if (data.removals.some(s => !s || !/^[a-f0-9]{64}$/.test(s.sha256) || typeof s.assetId !== 'string')) throw new Error('图库隐藏记录非法');
  return data;
}

export async function exportStickerBundle(pool: pg.Pool, root = process.cwd()): Promise<void> {
  const db = await pool.connect();
  let data: StickerBundle;
  try {
    await db.query('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY');
    const stickers = (await db.query('SELECT id,keywords,file_name,format,width,height,sha256 FROM sticker ORDER BY file_name')).rows;
    const ids = new Map(stickers.map(s => [String(s.id), s.file_name]));
    const keywords = (await db.query('SELECT keyword FROM sticker_keyword WHERE user_id=$1 ORDER BY keyword', [OWNER])).rows;
    const settings = (await db.query('SELECT keyword,aliases,asset_order FROM sticker_group_settings WHERE user_id=$1 ORDER BY keyword', [OWNER])).rows;
    const removals = (await db.query('SELECT sha256,asset_id FROM keyword_gif_removal WHERE user_id=$1 ORDER BY sha256', [OWNER])).rows;
    data = { version: 1, keywords: [...new Set<string>([...keywords.map(s => s.keyword), ...stickers.flatMap(s => String(s.keywords).split(/[,，]/).map(v => v.trim()).filter(Boolean))])].sort(),
      stickers: stickers.map(s => ({fileName:s.file_name,keywords:s.keywords,format:s.format,width:s.width,height:s.height,sha256:s.sha256})),
      settings: settings.map(s => ({keyword:s.keyword,aliases:s.aliases,assetOrder:s.asset_order === null ? null : s.asset_order.flatMap((key:string) => key.startsWith('personal:') ? (ids.has(key.slice(9)) ? [`file:${ids.get(key.slice(9))}`] : []) : [key])})),
      removals: removals.map(s => ({sha256:s.sha256,assetId:s.asset_id})) };
    await db.query('COMMIT');
  } catch (error) { await db.query('ROLLBACK'); throw error; }
  finally { db.release(); }
  // 历史图片可能没有SHA；只从现有原图补算，不重编码或更改数据库内容。
  for (const s of data.stickers) if (!s.sha256) s.sha256 = hash(await readFile(join(root,'uploads/stickers',s.fileName)));
  await validateStickerBundle(data,root);
  const path = manifestPath(root), bytes = JSON.stringify(data,null,2)+'\n';
  let existing: StickerBundle | undefined;
  try { existing = JSON.parse(await readFile(path,'utf8')); } catch(error) { if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error; }
  const baseline = (await pool.query('SELECT manifest FROM sticker_bundle_import WHERE singleton=TRUE')).rows[0]?.manifest;
  if (existing && !same(existing,baseline) && !same(existing,data)) throw new Error('Git图库清单尚未同步到本机数据库，请先执行 npm run stickers:import 导入，再保存或导出');
  await mkdir(dirname(path),{recursive:true});
  const temp = `${path}.${randomUUID()}.tmp`;
  await writeFile(temp,bytes); await rename(temp,path);
  await pool.query('INSERT INTO sticker_bundle_import(singleton,manifest) VALUES(TRUE,$1) ON CONFLICT(singleton) DO UPDATE SET manifest=EXCLUDED.manifest',[JSON.stringify(data)]);
}

/** 增量导入，只更新相对上次发布实际变化的记录，不清空线上独有数据。 */
export async function importStickerBundle(pool: pg.Pool, root = process.cwd()): Promise<void> {
  let text: string;
  try { text = await readFile(manifestPath(root),'utf8'); }
  catch (error) { if ((error as NodeJS.ErrnoException).code === 'ENOENT') return; throw error; }
  const data = await validateStickerBundle(JSON.parse(text),root);
  const db = await pool.connect();
  try {
    await db.query('BEGIN');
    // 与分组编辑共用事务锁，多个部署进程不会交错导入。
    await db.query('SELECT pg_advisory_xact_lock(hashtext($1))',[`sticker-groups:${OWNER}`]);
    const previous = (await db.query('SELECT manifest FROM sticker_bundle_import WHERE singleton=TRUE')).rows[0]?.manifest as StickerBundle | undefined;
    if (same(previous,data)) { await db.query('COMMIT'); return; }
    const ids = new Map<string,string>();
    for (const s of data.stickers) {
      const old = previous?.stickers.find(item => item.fileName === s.fileName);
      let row = (await db.query('SELECT id,sha256,keywords,format,width,height FROM sticker WHERE file_name=$1 FOR UPDATE',[s.fileName])).rows[0];
      if (row && row.sha256 && row.sha256 !== s.sha256) throw new Error(`同名图片内容冲突：${s.fileName}`);
      if (!same(old,s)) {
        const keywords = !old && row ? [...new Set([...splitStickerKeywords(row.keywords),...splitStickerKeywords(s.keywords)])].join(',') : s.keywords;
        const change = (key: 'keywords'|'format'|'width'|'height') => old ? !same(old[key],s[key]) : key === 'keywords' || row?.[key] == null;
        row = (await db.query(`INSERT INTO sticker(user_id,keywords,file_name,format,width,height,sha256) VALUES($1,$2,$3,$4,$5,$6,$7)
          ON CONFLICT(file_name) DO UPDATE SET keywords=CASE WHEN $8 THEN EXCLUDED.keywords ELSE sticker.keywords END,
          format=CASE WHEN $9 THEN EXCLUDED.format ELSE sticker.format END,
          width=CASE WHEN $10 THEN EXCLUDED.width ELSE sticker.width END,
          height=CASE WHEN $11 THEN EXCLUDED.height ELSE sticker.height END,sha256=EXCLUDED.sha256 RETURNING id`,
          [OWNER,keywords,s.fileName,s.format,s.width,s.height,s.sha256,change('keywords'),change('format'),change('width'),change('height')])).rows[0];
      }
      if (row) ids.set(s.fileName,String(row.id));
    }
    for (const keyword of data.keywords) await db.query('INSERT INTO sticker_keyword(user_id,keyword) VALUES($1,$2) ON CONFLICT DO NOTHING',[OWNER,keyword]);
    for (const s of data.settings) {
      const old = previous?.settings.find(item => item.keyword === s.keyword);
      if (same(old,s)) continue;
      let order = s.assetOrder?.flatMap(key => key.startsWith('file:') ? (ids.has(key.slice(5)) ? [`personal:${ids.get(key.slice(5))}`] : []) : [key]) ?? null;
      let aliases = s.aliases;
      if (!old) {
        const current = (await db.query('SELECT aliases,asset_order FROM sticker_group_settings WHERE user_id=$1 AND keyword=$2',[OWNER,s.keyword])).rows[0];
        if (current) {
          aliases = current.aliases === null && aliases === null ? null : [...new Set<string>([...(current.aliases ?? []),...(aliases ?? [])])];
          order = current.asset_order === null && order === null ? null : [...new Set<string>([...(current.asset_order ?? []),...(order ?? [])])];
        }
      }
      await db.query(`INSERT INTO sticker_group_settings(user_id,keyword,aliases,asset_order) VALUES($1,$2,$3,$4)
        ON CONFLICT(user_id,keyword) DO UPDATE SET aliases=CASE WHEN $5 THEN EXCLUDED.aliases ELSE sticker_group_settings.aliases END,
        asset_order=CASE WHEN $6 THEN EXCLUDED.asset_order ELSE sticker_group_settings.asset_order END`,
        [OWNER,s.keyword,aliases === null ? null : JSON.stringify(aliases),order === null ? null : JSON.stringify(order),!old || !same(old.aliases,s.aliases),!old || !same(old.assetOrder,s.assetOrder)]);
    }
    const library = await loadStickerLibrary(db,OWNER,root);
    for (const setting of data.settings) {
      const group = library.groups.find(g => g.keyword === setting.keyword);
      if (!group) continue;
      const occupied = new Set(library.groups.filter(g => g.keyword !== group.keyword).flatMap(g => g.aliases).map(normalizeRecommendationPhrase));
      if (group.aliases.some(alias => occupied.has(normalizeRecommendationPhrase(alias)))) throw new Error(`图库说法归属冲突：${group.keyword}，请先调整另一组的同组说法`);
    }
    for (const s of data.removals) await db.query('INSERT INTO keyword_gif_removal(user_id,sha256,asset_id) VALUES($1,$2,$3) ON CONFLICT DO NOTHING',[OWNER,s.sha256,s.assetId]);
    await db.query('INSERT INTO sticker_bundle_import(singleton,manifest) VALUES(TRUE,$1) ON CONFLICT(singleton) DO UPDATE SET manifest=EXCLUDED.manifest',[JSON.stringify(data)]);
    await db.query('COMMIT');
  } catch (error) { await db.query('ROLLBACK'); throw error; }
  finally { db.release(); }
}

let exportQueue: Promise<void> = Promise.resolve();
/** 源码工作目录自动归档；生产release没有.git，不反向改写已发布清单。 */
export async function publishStickerBundle(pool: pg.Pool): Promise<void> {
  if (!existsSync(join(process.cwd(),'../.git'))) return;
  const next = exportQueue.catch(() => {}).then(() => exportStickerBundle(pool));
  exportQueue = next;
  await next;
}
