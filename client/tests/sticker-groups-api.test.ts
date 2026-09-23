import { readFileSync } from 'node:fs';
import ts from 'typescript';
import * as Vue from 'vue';
import { expect, it, vi } from '../../server/node_modules/vitest/dist/index.js';

function loadApi(fetch: ReturnType<typeof vi.fn>) {
  const module = { exports: {} as { api: typeof import('../src/api').api } };
  const code = ts.transpileModule(readFileSync(new URL('../src/api/index.ts', import.meta.url), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  new Function('require', 'module', 'exports', 'localStorage', 'window', code)((id: string) => {
    if (id === 'vue') return Vue;
    if (id === '../auth') return { dashboardFetch: fetch };
    throw Error(id);
  }, module, module.exports, { getItem: () => 'user-a' }, { location: { origin: 'http://test.local' } });
  return module.exports.api;
}

it('保存说法时向页面保留服务端的冲突说法和所属组提示', async () => {
  const message = '说法“翻白眼”已属于关键词组“翻白眼”，请先从原组移除';
  const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: message }), { status: 409 }));
  const api = loadApi(fetch);
  await expect(api.updateStickerGroup('白眼', { aliases: ['白眼', '翻白眼'] })).rejects.toThrow(message);
  const [url, options] = fetch.mock.calls[0];
  expect(url).toBe('/api/v1/dashboard/sticker-groups/%E7%99%BD%E7%9C%BC?user_id=user-a');
  expect(options.method).toBe('PATCH');
  expect(JSON.parse(options.body)).toEqual({ aliases: ['白眼', '翻白眼'] });
});

it.each(['upstream unavailable', '{}', '{"error":{}}', 'null'])(
  '保存说法遇到无有效说明的响应仍保留状态码：%s', async body => {
    const api = loadApi(vi.fn().mockResolvedValue(new Response(body, { status: 502 })));
    await expect(api.updateStickerGroup('白眼', { aliases: ['白眼'] })).rejects.toThrow('502');
  },
);

it('正常保存说法返回更新后的组', async () => {
  const result = { group: { keyword: '白眼', aliases: ['白眼', '给你个白眼'] } };
  const api = loadApi(vi.fn().mockResolvedValue(new Response(JSON.stringify(result))));
  expect(await api.updateStickerGroup('白眼', { aliases: result.group.aliases })).toEqual(result);
});
