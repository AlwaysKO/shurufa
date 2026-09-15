import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import type { ExpressionAsset } from '../types/expression.js';
import { rankExpressionAssets } from './catalog.js';
import { expressionPhraseScore } from './queryMatching.js';

const cases = JSON.parse(readFileSync(new URL(
  '../../../assets/expression/query/recommendation-cases.json', import.meta.url,
), 'utf8')) as Array<{ query: string; first: string | null; forbidden: string[] }>;
const words = ['谢谢', '打闹', '追赶', '开心', '难过', '可以', '不要', '喜欢'];
const assets = words.map((word): ExpressionAsset => ({
  id: word, type: 'prebuilt', format: 'gif', embeddedText: word,
  keywords: word === '打闹' ? ['打闹', '打你', '玩闹']
    : word === '追赶' ? ['追赶', '抓你', '追你'] : [word],
  heat: 0, version: '1', fileName: `${word}.gif`, thumbnailFileName: null,
  sha256: 'a'.repeat(64), width: 240, height: 240,
  emotions: [], textSafeArea: null, layout: null,
}));

describe('两端共享查询语义矩阵', () => {
  for (const item of cases) it(JSON.stringify(item), () => {
    const results = rankExpressionAssets(assets, item.query);
    if (item.first) expect(results[0]?.id).toBe(item.first);
    for (const forbidden of item.forbidden) {
      expect(results.map(({ id }) => id)).not.toContain(forbidden);
    }
    for (const result of results) expect(result.embeddedText).toBe(result.id);
  });

  it('完整原词先于别名', () => {
    const exact = { ...assets[0], id: 'exact', embeddedText: '谢谢啦', format: 'webp' as const };
    expect(rankExpressionAssets([...assets, exact], '谢谢啦')[0].id).toBe('exact');
  });

  it('同等相关预制图优先真实 GIF 而非高热静态图', () => {
    const staticAsset = { ...assets[0], id: 'static', heat: 999, format: 'webp' as const };
    expect(rankExpressionAssets([staticAsset, ...assets], '谢谢啦')[0].format).toBe('gif');
  });

  it('否定查询的模板兜底也不返回被否定的动作与正向情绪', () => {
    const templates = assets.map((item) => ({
      ...item, id: `tpl-${item.id}`, type: 'synthesis-template' as const, embeddedText: null,
    }));
    for (const [query, blocked] of [
      ['不喜欢', '喜欢'], ['不会打你', '打闹'], ['不开心', '开心'], ['不可以', '可以'],
    ]) {
      expect(rankExpressionAssets(templates, query).map(({ id }) => id)).not.toContain(`tpl-${blocked}`);
    }
  });
});


describe('句子核心短语的相关性顺序', () => {
  it('直接出现的动作优先近义动作，即使近义图更热门', () => {
    expect(expressionPhraseScore('你过来打我啊', ['打我']))
      .toBeGreaterThan(expressionPhraseScore('你过来打我啊', ['揍你']));
    const exact = { ...assets[0], id: '打我', embeddedText: '打我', keywords: ['打我'] };
    const related = { ...exact, id: '揍你', embeddedText: '揍你', keywords: ['揍你'], heat: 9999 };
    expect(rankExpressionAssets([related, exact, ...assets], '你过来打我啊')[0].id).toBe('打我');
  });

  it('较长核心短语优先较短短语，完整原句仍然最高', () => {
    const query = '你过来打我啊';
    expect(expressionPhraseScore(query, ['过来打我']))
      .toBeGreaterThan(expressionPhraseScore(query, ['打我']));
    expect(expressionPhraseScore(query, [query]))
      .toBeGreaterThan(expressionPhraseScore(query, ['过来打我']));
  });
});
