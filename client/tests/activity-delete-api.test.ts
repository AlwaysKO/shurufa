import { readFileSync } from 'node:fs';
import ts from 'typescript';
import * as Vue from 'vue';
import { expect, it, vi } from '../../server/node_modules/vitest/dist/index.js';

function loadApi(fetch: ReturnType<typeof vi.fn>) {
  const module = { exports: {} as { api: { deleteActivity: (id: string, body: unknown) => Promise<{ deleted: number }> } } };
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

it('删除请求使用所选用户范围及明确ID快照，不调用批量清理', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ deleted: 2 })));
  const api = loadApi(fetch);
  const body = { confirm: 'DELETE', mode: 'group', event_ids: ['one', 'two'] };
  expect(await api.deleteActivity('two', body)).toEqual({ deleted: 2 });
  const [url, options] = fetch.mock.calls[0];
  expect(url).toBe('/api/v1/dashboard/events/two/delete?user_id=user-a');
  expect(options.method).toBe('POST'); expect(JSON.parse(options.body)).toEqual(body);
});

it('删除失败把后端刷新提示展示给调用者，非JSON错误也安全失败', async () => {
  const fetch = vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify({ error: '记录已变化，请刷新后重新确认' }), { status: 409 }))
    .mockResolvedValueOnce(new Response('upstream unavailable', { status: 502 }));
  const api = loadApi(fetch);
  await expect(api.deleteActivity('id', {})).rejects.toThrow('记录已变化');
  await expect(api.deleteActivity('id', {})).rejects.toThrow('502');
});
