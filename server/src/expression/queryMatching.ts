/** Deterministic phrase matching. Keep rules aligned with ExpressionQueryMatching.kt. */
export function normalizeExpressionQuery(value: string): string {
  return value.toLowerCase().replace(/[\s\p{P}\p{S}]+/gu, '');
}

export const expressionSynonymGroups: readonly (readonly string[])[] = [
  ['谢谢', '感谢', '多谢', '感激'],
  ['打闹', '玩闹', '打你', '打我', '揍你', '揍我', '捶你', '捶我'],
  ['追赶', '抓你', '抓我', '追你', '追我', '捉你', '捉我'],
  ['难过', '伤心', '不开心', '不高兴', '悲伤'],
  ['开心', '高兴', '快乐'],
  ['不要', '不可以', '不行', '拒绝'],
  ['可以', '好的', '好呀', '同意', '没问题'],
  ['喜欢', '爱你', '心动'],
  ['对不起', '抱歉', '不好意思'],
  ['哈哈', '笑死', '好笑'],
  ['震惊', '惊讶', '惊呆', '吓一跳'],
  ['生气', '愤怒', '气死'],
  ['加油', '努力', '坚持'],
  ['收到', '明白', '知道了'],
  ['再见', '拜拜', '回见'],
  ['抱抱', '拥抱'],
];
const negativePrefix = /(?:不|没|没有|别|不要|不会|不能|不想|不愿|不许|禁止)(?:再|去|来|要|会|想|能|愿意|真的|太|很|要来|打算|准备|计划|过来|过去){0,3}$/u;

function containsPositive(query: string, phrase: string): boolean {
  let start = query.indexOf(phrase);
  while (start >= 0) {
    // “打你的电话” is calling, not playful hitting. Check only this occurrence,
    // so a later independent “打你” can still express the action.
    const phoneContext = (phrase === '打你' || phrase === '打我')
      && /^的?(?:电话|手机|号码)/u.test(query.slice(start + phrase.length));
    if (!phoneContext && !negativePrefix.test(query.slice(0, start))) return true;
    start = query.indexOf(phrase, start + 1);
  }
  return false;
}

/** Exact sentence > contained core phrase > synonym; no popularity-based relevance.
 * No one-character overlap or fuzzy character intersection: only complete phrases. */
export function expressionPhraseScore(query: string, values: readonly string[]): number {
  let score = 0;
  let blocked = false;
  for (const value of values) {
    const phrase = normalizeExpressionQuery(value);
    if (!phrase) continue;
    if (phrase === query) score = Math.max(score, 1000);
    if (phrase.length < 2) continue;
    if (containsPositive(query, phrase)) score = Math.max(score, 900 + Math.min(phrase.length, 99));
    else if (query.includes(phrase)) blocked = true;
    for (const group of expressionSynonymGroups) {
      if (!group.some((alias) => containsPositive(phrase, alias))) continue;
      if (group.some((alias) => containsPositive(query, alias))) score = Math.max(score, 800);
      else if (group.some((alias) => query.includes(alias))) blocked = true;
    }
  }
  return score > 0 ? score : blocked ? -1 : 0;
}
