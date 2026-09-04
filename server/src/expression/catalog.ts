import type { ExpressionAsset } from '../types/expression.js';

function normalize(value: string): string {
  return value.trim().toLocaleLowerCase().replace(/[\s\p{P}\p{S}]+/gu, '');
}

function relevance(asset: ExpressionAsset, query: string): number {
  return asset.keywords.reduce((best, keywordValue) => {
    const keyword = normalize(keywordValue);
    if (!keyword) return best;
    if (keyword === query) return Math.max(best, 1_000);
    if (keyword.includes(query) || query.includes(keyword)) {
      return Math.max(best, 700 + Math.min(keyword.length, query.length));
    }
    const queryChars = new Set(Array.from(query));
    const overlap = new Set(Array.from(keyword).filter((char) => queryChars.has(char))).size;
    const boundaryBonus = keyword.endsWith(Array.from(query).at(-1) ?? '') ? 10 : 0;
    return Math.max(best, overlap * 100 / Math.max(queryChars.size, 1) + boundaryBonus);
  }, 0);
}

/** 与 Java/Kotlin String.hashCode 相同的 31 倍哈希，保证服务端和手机离线排序一致。 */
function javaHash(value: string): number {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) {
    hash = (Math.imul(hash, 31) + value.charCodeAt(index)) >>> 0;
  }
  return hash;
}

function stableQueryOrder(query: string, id: string): number {
  return Math.imul(javaHash(query) ^ javaHash(id), 0x45d9f3b) >>> 0;
}

export function emojiCombinationKey(firstId: string, secondId: string): string {
  return `${firstId}__${secondId}`;
}

export function rankExpressionAssets(
  assets: readonly ExpressionAsset[],
  query: string,
  limit = 20,
): ExpressionAsset[] {
  const normalizedQuery = normalize(query);
  if (!normalizedQuery || limit <= 0) return [];

  const ranked = (candidates: readonly { asset: ExpressionAsset; index: number }[]) => candidates
    .slice()
    .sort((left, right) => (
      right.asset.heat - left.asset.heat
      || left.index - right.index
    ))
    .slice(0, limit)
    .map(({ asset }) => asset);

  const indexed = assets.map((asset, index) => ({ asset, index }));
  const prebuilt = indexed.filter(({ asset }) => (
    asset.type === 'prebuilt'
    && asset.embeddedText !== null
    && normalize(asset.embeddedText) === normalizedQuery
  ));
  if (prebuilt.length > 0) return ranked(prebuilt);

  return indexed
    .filter(({ asset }) => asset.type === 'synthesis-template')
    .map((candidate) => ({
      ...candidate,
      relevance: relevance(candidate.asset, normalizedQuery),
      queryOrder: stableQueryOrder(normalizedQuery, candidate.asset.id),
    }))
    .sort((left, right) => (
      right.relevance - left.relevance
      || right.asset.heat - left.asset.heat
      || left.queryOrder - right.queryOrder
      || left.index - right.index
    ))
    .slice(0, limit)
    .map(({ asset }) => asset);
}
