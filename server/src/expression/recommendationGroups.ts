/** 自动推荐只允许完整说法，不能删除句中标点后拼成另一个词。 */
export function normalizeRecommendationPhrase(value: string): string {
  return value.trim().replace(/[\p{P}\s]+$/gu, '');
}

export interface RecommendationGroup {
  keyword: string;
  aliases: string[];
  assetIds: string[];
}

export function recommendationIds(groups: RecommendationGroup[], query: string): string[] {
  const normalized = normalizeRecommendationPhrase(query);
  if (!normalized) return [];
  return [...new Set(groups.filter(group => group.aliases.some(alias => normalizeRecommendationPhrase(alias) === normalized))
    .flatMap(group => group.assetIds))];
}
