import { blankGifTemplates, hasPlayfulSynthesisIntent } from './synthesisIntent.js';
import type { ExpressionAsset } from '../types/expression.js';
import { normalizeExpressionQuery as normalize, expressionPhraseScore } from './queryMatching.js';

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
      Number(right.asset.format === 'gif') - Number(left.asset.format === 'gif')
      || right.asset.heat - left.asset.heat
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

  const related = indexed
    .filter(({ asset }) => asset.type === 'prebuilt')
    .map((candidate) => ({ ...candidate, score: expressionPhraseScore(normalizedQuery,
      [...candidate.asset.keywords, candidate.asset.embeddedText ?? '']) }))
    .filter(({ score }) => score > 0)
    .sort((left, right) => right.score - left.score
      || Number(right.asset.format === 'gif') - Number(left.asset.format === 'gif')
      || right.asset.heat - left.asset.heat || left.index - right.index);
  if (related.length > 0) return related.slice(0, limit).map(({ asset }) => asset);

  if (hasPlayfulSynthesisIntent(normalizedQuery)) {
    const pool = blankGifTemplates(assets, normalizedQuery);
    if (pool.length > 0) return pool.slice(0, limit);
  }

  return indexed
    .filter(({ asset }) => asset.type === 'synthesis-template')
    .map((candidate) => ({
      ...candidate,
      relevance: expressionPhraseScore(normalizedQuery, candidate.asset.keywords),
      queryOrder: stableQueryOrder(normalizedQuery, candidate.asset.id),
    }))
    .filter(({ relevance }) => relevance > 0)
    .sort((left, right) => (
      right.relevance - left.relevance
      || right.asset.heat - left.asset.heat
      || left.queryOrder - right.queryOrder
      || left.index - right.index
    ))
    .slice(0, limit)
    .map(({ asset }) => asset);
}
