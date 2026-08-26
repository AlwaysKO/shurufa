import type { ExpressionAsset } from '../types/expression.js';

function normalize(value: string): string {
  return value.trim().toLocaleLowerCase();
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

  return ranked(indexed.filter(({ asset }) => asset.type === 'synthesis-template'));
}
