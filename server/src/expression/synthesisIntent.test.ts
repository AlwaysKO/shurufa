import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { hasPlayfulSynthesisIntent } from './synthesisIntent.js';
const cases = JSON.parse(readFileSync(new URL('../../../assets/expression/query/synthesis-intent-cases.json', import.meta.url), 'utf8')) as Array<{ query: string; playful: boolean }>;
describe('跨端共享玩笑表达门禁', () => {
  for (const row of cases) it(JSON.stringify(row.query), () => {
    expect(hasPlayfulSynthesisIntent(row.query)).toBe(row.playful);
  });
});
