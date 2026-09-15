import { readFileSync } from 'node:fs';
import { expect, it } from '../../server/node_modules/vitest/dist/index.js';

it('关系页提供画像训练、画像证据和 AI 回复预览入口', () => {
  const view = readFileSync(new URL('../src/views/Relationships.vue', import.meta.url), 'utf8');
  const api = readFileSync(new URL('../src/api/index.ts', import.meta.url), 'utf8');
  expect(view).toContain('训练 AI 画像');
  expect(view).toContain('AI 回复预览');
  expect(view).toContain('profile.profile.summary');
  expect(api).toContain('/ai-profile/train');
  expect(api).toContain('/ai-replies');
});
