import { SHARED_STICKER_OWNER } from '../stickers/shared.js';
import { readKeywordGifCatalog, mergeKeywordGifCatalog, removedKeywordGifHashes } from '../expression/keywordGifLibrary.js';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import type pg from 'pg';
import type { ExpressionAsset } from '../types/expression.js';
import { normalizeRecommendationPhrase } from '../expression/recommendationGroups.js';
import { expressionSynonymGroups } from '../expression/queryMatching.js';

export function splitStickerKeywords(value: string): string[] {
  return [...new Set(value.split(/[,，]/).map(word => word.trim()).filter(Boolean))];
}

export async function rememberStickerKeywords(pool: Pick<pg.Pool, 'query'>, _userId: string, keywords: string): Promise<void> {
  for (const keyword of splitStickerKeywords(keywords)) {
    await pool.query(`INSERT INTO sticker_keyword(user_id, keyword) VALUES($1, $2)
      ON CONFLICT (user_id, keyword) DO NOTHING`, [SHARED_STICKER_OWNER, keyword]);
  }
}

export interface LibraryAsset {
  id: string | number;
  source: 'system' | 'personal';
  keywords: string[];
  url: string;
  format: string;
  width: number | null;
  height: number | null;
  useCount: number | null;
}
interface KeywordGroup {
  keyword: string;
  category: string;
  planned: boolean;
  custom: boolean;
  assets: LibraryAsset[];
}

export interface SemanticKeywordGroup extends KeywordGroup {
  aliases: string[];
  confirmedAliases: string[];
}

// 来源：2026-09-15 用户明确要求以下说法在后台共用“打闹”组。
// 2026-09-22：默认分组现作为可覆盖的推荐说法下发，不启用整份语义草案。
const confirmedPlayAliases = ['扁你', '我来打你了', '过来打我啊'];
function mergeSemanticGroups(rawGroups: KeywordGroup[]): SemanticKeywordGroup[] {
  const definitions = [...expressionSynonymGroups, ['赞', '点赞', '给你点赞', '太棒了']].map(words => ({
    keyword: words[0],
    aliases: [...words, ...(words[0] === '打闹' ? confirmedPlayAliases : [])],
    confirmedAliases: words[0] === '打闹' ? [...confirmedPlayAliases] : [],
  }));
  const byAlias = new Map(definitions.flatMap(def => def.aliases.map(alias => [alias, def] as const)));
  const merged = new Map<string, SemanticKeywordGroup>();
  const seenAssets = new Map<string, Set<string>>();
  for (const raw of rawGroups) {
    const definition = byAlias.get(raw.keyword) ?? { keyword: raw.keyword, aliases: [raw.keyword], confirmedAliases: [] };
    let group = merged.get(definition.keyword);
    if (!group) {
      group = { ...raw, ...definition, assets: [] };
      merged.set(group.keyword, group); seenAssets.set(group.keyword, new Set());
    }
    group.planned ||= raw.planned;
    group.custom ||= raw.custom;
    if (group.category === '其他' && raw.category !== '其他') group.category = raw.category;
    const seen = seenAssets.get(group.keyword)!;
    for (const asset of raw.assets) {
      const key = `${asset.source}:${asset.id}`;
      if (!seen.has(key)) { seen.add(key); group.assets.push(asset); }
    }
  }
  return [...merged.values()];
}

export async function loadStickerLibrary(pool: Pick<pg.Pool, 'query'>, _userId: string, serverRoot = process.cwd()) {
  const warnings: string[] = [];
  async function readOptional<T>(path: string, fallback: T, message: string): Promise<T> {
    try { return JSON.parse(await readFile(path, 'utf8')) as T; }
    catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
      warnings.push(message); return fallback;
    }
  }
  const [catalog, coverage, custom, personal, settings] = await Promise.all([
    readOptional<{ templates: ExpressionAsset[] }>(join(serverRoot, '.runtime/expression-assets/catalog.json'), { templates: [] }, '运行表情库未安装；当前只展示词表与公共上传。'),
    // 仅用草案展示规划词，不读取 existingAssets，不启用草案别名/匹配或发布素材。
    readOptional<{ keywords: { keyword: string; category: string }[] }>(join(serverRoot, '../assets/expression/query/keyword-coverage.draft.json'), { keywords: [] }, '规划词表未安装；当前只展示运行库与公共关键词。'),
    await pool.query<{ keyword: string }>('SELECT keyword FROM sticker_keyword WHERE user_id = $1 ORDER BY created_at, keyword', [SHARED_STICKER_OWNER]),
    await pool.query<{ id: string; keywords: string; file_name: string; format: string; width: number | null; height: number | null; use_count: string }>(
      'SELECT id, keywords, file_name, format, width, height, use_count FROM sticker ORDER BY id DESC'),
    await pool.query<{ keyword: string; aliases: string[] | null; asset_order: string[] | null }>(
      'SELECT keyword, aliases, asset_order FROM sticker_group_settings WHERE user_id = $1 ORDER BY keyword', [SHARED_STICKER_OWNER]),
  ]);
  const groups = new Map<string, KeywordGroup>();
  function group(keyword: string): KeywordGroup {
    if (!groups.has(keyword)) groups.set(keyword, { keyword, category: '其他', planned: false, custom: false, assets: [] });
    return groups.get(keyword)!;
  }
  for (const item of coverage.keywords) Object.assign(group(item.keyword), { category: item.category, planned: true });
  for (const item of custom.rows) group(item.keyword).custom = true;
  for (const item of settings.rows) group(item.keyword);
  let systemCount = 0;
  const removed = await removedKeywordGifHashes(pool, SHARED_STICKER_OWNER);
  for (const asset of mergeKeywordGifCatalog(catalog.templates, await readKeywordGifCatalog(serverRoot))) {
    if (asset.type === 'synthesis-template' || !asset.keywords.length) continue;
    for (const keyword of asset.keywords) group(keyword);
    if (removed.has(asset.sha256)) continue;
    systemCount++;
    for (const keyword of new Set(asset.keywords)) {
      group(keyword).assets.push({ id: asset.id, source: 'system', keywords: asset.keywords,
        url: `/uploads/expression/${asset.fileName}`, format: asset.format,
        width: asset.width, height: asset.height, useCount: null });
    }
  }
  for (const asset of personal.rows) {
    const keywords = splitStickerKeywords(asset.keywords);
    for (const keyword of keywords) group(keyword).assets.push({ id: Number(asset.id), source: 'personal', keywords,
      url: `/uploads/stickers/${asset.file_name}`, format: asset.format,
      width: asset.width, height: asset.height, useCount: Number(asset.use_count) });
  }
  const merged = mergeSemanticGroups([...groups.values()]);
  for (const group of merged) {
    const setting = settings.rows.find(item => item.keyword === group.keyword);
    if (setting?.aliases != null) {
      group.aliases = setting.aliases;
      group.confirmedAliases = setting.aliases;
    }
    // 默认上传图在前；保存过的顺序优先，新图不破坏已保存的相对顺序。
    const positions = new Map((setting?.asset_order ?? []).map((key, index) => [key, index]));
    group.assets.sort((a, b) => {
      const ai = positions.get(stickerAssetKey(a)), bi = positions.get(stickerAssetKey(b));
      if (ai !== undefined || bi !== undefined) return (ai ?? Infinity) - (bi ?? Infinity);
      return Number(b.source === 'personal') - Number(a.source === 'personal');
    });
  }
  return { groups: merged, systemCount, personalCount: personal.rows.length, warnings };
}


export function stickerAssetKey(asset: LibraryAsset): string { return `${asset.source}:${asset.id}`; }

export class StickerGroupError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

/** 公共图库的说法检查与写入串行执行，阻止跨组并发创建相同匹配说法。 */
async function withGroupLock<T>(pool: pg.Pool, _userId: string, action: (db: Pick<pg.Pool, 'query'>) => Promise<T>): Promise<T> {
  const db = await pool.connect();
  try {
    await db.query('BEGIN');
    await db.query('SELECT pg_advisory_xact_lock(hashtext($1))', [`sticker-groups:${SHARED_STICKER_OWNER}`]);
    const result = await action(db);
    await db.query('COMMIT');
    return result;
  } catch (error) { await db.query('ROLLBACK'); throw error; }
  finally { db.release(); }
}

export async function createStickerKeyword(pool: pg.Pool, _userId: string, value: string): Promise<string> {
  const keyword = normalizeRecommendationPhrase(value);
  if (!keyword) throw new StickerGroupError(400, '关键词不能仅含标点或空白');
  return withGroupLock(pool, SHARED_STICKER_OWNER, async db => {
    const library = await loadStickerLibrary(db, SHARED_STICKER_OWNER);
    const existing = library.groups.find(group => [group.keyword, ...group.aliases].some(alias => normalizeRecommendationPhrase(alias) === keyword));
    if (existing) return existing.keyword;
    await rememberStickerKeywords(db, SHARED_STICKER_OWNER, keyword);
    return keyword;
  });
}

export async function updateStickerGroup(pool: pg.Pool, _userId: string, keyword: string, input: unknown) {
  return withGroupLock(pool, SHARED_STICKER_OWNER, db => updateLockedGroup(db, SHARED_STICKER_OWNER, keyword, input));
}

async function updateLockedGroup(pool: Pick<pg.Pool, 'query'>, _userId: string, keyword: string, input: unknown) {
  const body = input as { aliases?: unknown; assetOrder?: unknown } | null;
  if (!body || typeof body !== 'object' || Array.isArray(body) || (!('aliases' in body) && !('assetOrder' in body))) throw new StickerGroupError(400, '请提供同组说法或图片顺序');
  const library = await loadStickerLibrary(pool, SHARED_STICKER_OWNER);
  const group = library.groups.find(item => item.keyword === keyword);
  if (!group) throw new StickerGroupError(404, '关键词组不存在');
  let aliases: string[] | undefined;
  if ('aliases' in body) {
    if (!Array.isArray(body.aliases) || body.aliases.length > 100 || body.aliases.some(value => typeof value !== 'string')) {
      throw new StickerGroupError(400, '同组说法必须为最多100条文字');
    }
    aliases = body.aliases.map(normalizeRecommendationPhrase);
    if (aliases.some(value => !value || value.length > 100 || /[,，\r\n]/u.test(value)) || new Set(aliases).size !== aliases.length) {
      throw new StickerGroupError(400, '说法须为1～100字，不含逗号或换行，且不能重复');
    }
    const others = new Set(library.groups.filter(item => item.keyword !== keyword)
      .flatMap(item => [item.keyword, ...item.aliases]).map(normalizeRecommendationPhrase));
    if (aliases.some(alias => others.has(alias))) throw new StickerGroupError(409, '说法已属于其他关键词组，请先从原组移除');
  }
  let order: string[] | undefined;
  if ('assetOrder' in body) {
    if (!Array.isArray(body.assetOrder) || body.assetOrder.some(value => typeof value !== 'string')) throw new StickerGroupError(400, '图片顺序格式不正确');
    order = body.assetOrder;
    const keys = new Set(group.assets.map(stickerAssetKey));
    if (order.length !== keys.size || new Set(order).size !== order.length || order.some(key => !keys.has(key))) {
      throw new StickerGroupError(409, '组内图片已变化或顺序无效，请刷新后重试');
    }
  }
  // 部分更新不能覆盖另一个字段；清空 [] 与未传字段严格区分。
  await pool.query(`INSERT INTO sticker_group_settings (user_id, keyword, aliases, asset_order) VALUES ($1,$2,$3,$4)
    ON CONFLICT (user_id, keyword) DO UPDATE SET
      aliases = COALESCE(EXCLUDED.aliases, sticker_group_settings.aliases),
      asset_order = COALESCE(EXCLUDED.asset_order, sticker_group_settings.asset_order)`,
    [SHARED_STICKER_OWNER, keyword, aliases === undefined ? null : JSON.stringify(aliases), order === undefined ? null : JSON.stringify(order)]);
  return (await loadStickerLibrary(pool, SHARED_STICKER_OWNER)).groups.find(item => item.keyword === keyword)!;
}
