import { readKeywordGifCatalog, mergeKeywordGifCatalog, removedKeywordGifHashes } from '../expression/keywordGifLibrary.js';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import type pg from 'pg';
import { expressionAssetRoot } from './expressions.js';
import type { ExpressionAsset } from '../types/expression.js';
import { expressionSynonymGroups } from '../expression/queryMatching.js';

export function splitStickerKeywords(value: string): string[] {
  return [...new Set(value.split(/[,，]/).map(word => word.trim()).filter(Boolean))];
}

export async function rememberStickerKeywords(pool: pg.Pool, userId: string, keywords: string): Promise<void> {
  for (const keyword of splitStickerKeywords(keywords)) {
    await pool.query(`INSERT INTO sticker_keyword(user_id, keyword) VALUES($1, $2)
      ON CONFLICT (user_id, keyword) DO NOTHING`, [userId, keyword]);
  }
}

interface LibraryAsset {
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

interface SemanticKeywordGroup extends KeywordGroup {
  aliases: string[];
  confirmedAliases: string[];
}

// 来源：2026-09-15 用户明确要求以下说法在后台共用“打闹”组。
// 仅后台分组及个人上传标签，不修改系统/Android 匹配器或启用整份语义草案。
const confirmedPlayAliases = ['扁你', '我来打你了', '过来打我啊'];
function mergeSemanticGroups(rawGroups: KeywordGroup[]): SemanticKeywordGroup[] {
  const definitions = expressionSynonymGroups.map(words => ({
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

export async function loadStickerLibrary(pool: pg.Pool, userId: string) {
  const warnings: string[] = [];
  async function readOptional<T>(path: string, fallback: T, message: string): Promise<T> {
    try { return JSON.parse(await readFile(path, 'utf8')) as T; }
    catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
      warnings.push(message); return fallback;
    }
  }
  const [catalog, coverage, custom, personal] = await Promise.all([
    readOptional<{ templates: ExpressionAsset[] }>(join(expressionAssetRoot(), 'catalog.json'), { templates: [] }, '运行表情库未安装；当前只展示词表与个人上传。'),
    // 仅用草案展示规划词，不读取 existingAssets，不启用草案别名/匹配或发布素材。
    readOptional<{ keywords: { keyword: string; category: string }[] }>(join(process.cwd(), '../assets/expression/query/keyword-coverage.draft.json'), { keywords: [] }, '规划词表未安装；当前只展示运行库与个人关键词。'),
    pool.query<{ keyword: string }>('SELECT keyword FROM sticker_keyword WHERE user_id = $1 ORDER BY created_at, keyword', [userId]),
    pool.query<{ id: string; keywords: string; file_name: string; format: string; width: number | null; height: number | null; use_count: string }>(
      'SELECT id, keywords, file_name, format, width, height, use_count FROM sticker WHERE user_id = $1 ORDER BY id DESC', [userId]),
  ]);
  const groups = new Map<string, KeywordGroup>();
  function group(keyword: string): KeywordGroup {
    if (!groups.has(keyword)) groups.set(keyword, { keyword, category: '其他', planned: false, custom: false, assets: [] });
    return groups.get(keyword)!;
  }
  for (const item of coverage.keywords) Object.assign(group(item.keyword), { category: item.category, planned: true });
  for (const item of custom.rows) group(item.keyword).custom = true;
  let systemCount = 0;
  const removed = await removedKeywordGifHashes(pool, userId);
  for (const asset of mergeKeywordGifCatalog(catalog.templates, await readKeywordGifCatalog())) {
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
  return { groups: mergeSemanticGroups([...groups.values()]), systemCount, personalCount: personal.rows.length, warnings };
}
