import { readFile, realpath } from 'node:fs/promises';
import { resolve, sep } from 'node:path';
import type pg from 'pg';
import type { ExpressionAsset } from '../types/expression.js';

export interface KeywordGifAsset extends ExpressionAsset {
  sourceGif: string;
  sourceThumbnail?: string;
  sourceReport: string;
  approval: { date: string; basis: string };
}
export async function readKeywordGifCatalog(): Promise<KeywordGifAsset[]> {
  try {
    const data = JSON.parse(await readFile(resolve(process.cwd(), '../assets/expression/approved-keyword-gifs.json'), 'utf8'));
    if (!Array.isArray(data.items)) throw new Error('关键词GIF清单格式错误');
    return data.items;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') return [];
    throw error;
  }
}
export function mergeKeywordGifCatalog(system: ExpressionAsset[], additions: ExpressionAsset[]): ExpressionAsset[] {
  const ids = new Set(system.map(x => x.id));
  const hashes = new Set(system.map(x => x.sha256));
  const result = [...system];
  for (const { sourceGif: _gif, sourceThumbnail: _thumb, sourceReport: _report, approval: _approval, ...item } of additions as KeywordGifAsset[]) {
    if (ids.has(item.id) || hashes.has(item.sha256)) continue;
    result.push(item); ids.add(item.id); hashes.add(item.sha256);
  }
  return result;
}
export async function resolveKeywordGifFile(id: string, format: 'gif' | 'webp'): Promise<string | null> {
  if (!/^[a-z0-9][a-z0-9_-]*$/.test(id)) return null;
  const item = (await readKeywordGifCatalog()).find(x => x.id === id);
  const path = format === 'gif' ? item?.sourceGif : item?.sourceThumbnail;
  if (!path) return null;
  const root = await realpath(resolve(process.cwd(), '..'));
  const file = await realpath(resolve(root, path));
  if (!/^(artifacts|server\/images)\//.test(path) || ![resolve(root, 'artifacts') + sep, resolve(root, 'server/images') + sep].some(prefix => file.startsWith(prefix)) || !file.endsWith('.' + format)) throw new Error('关键词GIF来源路径越界');
  return file;
}
export async function removedKeywordGifHashes(pool: Pick<pg.Pool, 'query'>, userId: string): Promise<Set<string>> {
  const result = await pool.query<{ sha256: string }>('SELECT sha256 FROM keyword_gif_removal WHERE user_id = $1 ORDER BY sha256', [userId]);
  return new Set(result.rows.map(row => row.sha256));
}
export function keywordGifExclusion(item: { id: string; sourceType?: string; issues?: unknown[]; publicationAllowed?: boolean }, review: { needsRework?: string[] }, manifest: { items?: { id: string; visualTriage?: string }[] }): string | null {
  if (review.needsRework?.includes(item.id) || manifest.items?.some(x => x.id === item.id && x.visualTriage === 'needs-rework')) return '明确标记需返工';
  if (item.issues?.length) return '既有机器审计失败';
  if (!['ai-original', 'licensed', 'cc0', 'public-domain'].includes(item.sourceType ?? '')) return '来源或许可未确认';
  return null;
}
