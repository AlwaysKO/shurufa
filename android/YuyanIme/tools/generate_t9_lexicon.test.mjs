import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildLexicon } from './generate_t9_lexicon.mjs';

test('保留常用词及低频固定表达并生成有音节边界的离线拼音', () => {
  const data = buildLexicon('你好 100 l\n候选词 3 l\n生僻人名 1 nr\n');
  assert.match(data, /你好\tni hao\t100/);
  assert.match(data, /候选词\thou xuan ci\t3/);
  assert.ok(!data.includes('生僻人名'));
});
