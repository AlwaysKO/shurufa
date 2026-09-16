import type { ExpressionAsset } from '../types/expression.js';
import { normalizeExpressionQuery, expressionPhraseScore } from './queryMatching.js';

/** 保守本地表达规则，不是通用幽默理解；手动选图不经过此门禁。与 Android 同步。 */
export function hasPlayfulSynthesisIntent(query: string): boolean {
  const text = normalizeExpressionQuery(query);
  if (/(不要|别|禁止|停止|不能|不该).{0,6}(嘲讽|调侃|开玩笑|阴阳怪气)/u.test(text)) return false;
  if (/(不是|没有|没在|并非).{0,3}(开玩笑|调侃|嘲讽)|我是认真的/u.test(text)) return false;
  return ['笑死', '笑不活', '绷不住', '蚌埠住', '离大谱', '原地裂开', '你可真是个人才',
    '你是真会', '给你颁个奖', '给爷整笑', '戏精', '显眼包', '小丑竟是我',
    '我可真谢谢你', '阴阳怪气', '调侃', '嘲讽', '开玩笑', '逗你玩'].some(marker => text.includes(marker));
}

export function blankGifTemplates(assets: readonly ExpressionAsset[], query: string): ExpressionAsset[] {
  const text = normalizeExpressionQuery(query);
  if (!text) return [];
  return assets.map((asset, index) => ({ asset, index, score: expressionPhraseScore(text, asset.keywords) }))
    .filter(({ asset }) => asset.type === 'synthesis-template' && asset.format === 'gif'
      && !asset.embeddedText?.trim() && asset.textSafeArea != null && asset.layout != null)
    .sort((a, b) => b.score - a.score || a.index - b.index)
    .map(({ asset }) => asset);
}
