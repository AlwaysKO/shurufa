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
): ExpressionAsset[] {
  const normalizedQuery = normalize(query);
  if (!normalizedQuery) return [];

  return assets
    .map((asset, index) => ({ asset, index }))
    .filter(({ asset }) => (
      asset.keywords.some((keyword) => normalize(keyword) === normalizedQuery)
    ))
    .sort((left, right) => (
      right.asset.heat - left.asset.heat
      || left.index - right.index
    ))
    .map(({ asset }) => asset);
}
