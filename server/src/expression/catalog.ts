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
  const tier = (asset: ExpressionAsset): number => {
    if (normalizedQuery && asset.keywords.some((keyword) => normalize(keyword) === normalizedQuery)) {
      return 0;
    }
    if (normalizedQuery && asset.emotions.some((emotion) => normalize(emotion) === normalizedQuery)) {
      return 1;
    }
    return 2;
  };

  return assets
    .map((asset, index) => ({ asset, index, tier: tier(asset) }))
    .sort((left, right) => (
      left.tier - right.tier
      || right.asset.heat - left.asset.heat
      || left.index - right.index
    ))
    .map(({ asset }) => asset);
}
