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

it('删除关键词发送精确组名和确认快照，保留冲突说明与清理状态', async () => {
  const body = {confirm:'DELETE' as const, aliases:['白眼','翻白眼'], assetKeys:['personal:3']};
  const fetch = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({keyword:'白眼',files_pending:true})))
    .mockResolvedValueOnce(new Response(JSON.stringify({error:'组内图片已变化，请刷新'}),{status:409}))
    .mockResolvedValueOnce(new Response('upstream unavailable',{status:502}));
  const api = loadApi(fetch);
  expect(await api.deleteStickerGroup('白眼',body)).toEqual({keyword:'白眼',files_pending:true});
  const [url, options] = fetch.mock.calls[0];
  expect(url).toBe('/api/v1/dashboard/sticker-groups/%E7%99%BD%E7%9C%BC/delete?user_id=user-a');
  expect(options.method).toBe('POST'); expect(JSON.parse(options.body)).toEqual(body);
  await expect(api.deleteStickerGroup('白眼',body)).rejects.toThrow('组内图片已变化');
  await expect(api.deleteStickerGroup('白眼',body)).rejects.toThrow('502');
});

it('底图删除读取清理状态并携带当前用户，失败保留服务端说明', async () => {
 const fetch=vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ok:true,files_pending:true})))
  .mockResolvedValueOnce(new Response(JSON.stringify({error:'暂时无法删除'}),{status:503}));
 const api=loadApi(fetch);
 expect(await api.deleteSynthesisAsset('synthesis-test')).toEqual({ok:true,files_pending:true});
 expect(fetch.mock.calls[0][0]).toBe('/api/v1/dashboard/synthesis-library/synthesis-test?user_id=user-a');
 expect(fetch.mock.calls[0][1].method).toBe('DELETE');
 await expect(api.deleteSynthesisAsset('synthesis-test')).rejects.toThrow('暂时无法删除');
});

it('底图排序携带当前用户和完整顺序，保留失败说明', async () => {
 const result={assets:[{id:'system'},{id:'synthesis-test'}],total:2};
 const fetch=vi.fn().mockResolvedValueOnce(new Response(JSON.stringify(result)))
  .mockResolvedValueOnce(new Response(JSON.stringify({error:'底图库已变化，请刷新后重试'}),{status:409}));
 const api=loadApi(fetch);
 expect(await api.saveSynthesisOrder(['system','synthesis-test'])).toEqual(result);
 expect(fetch.mock.calls[0][0]).toBe('/api/v1/dashboard/synthesis-library/order?user_id=user-a');
 expect(fetch.mock.calls[0][1].method).toBe('PATCH');
 expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({assetOrder:['system','synthesis-test']});
 await expect(api.saveSynthesisOrder(['system','synthesis-test'])).rejects.toThrow('底图库已变化');
});

it('AI底图上传400显示服务端具体校验原因，无说明时保留状态码', async () => {
 const fetch=vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({error:'仅接受240×240多帧GIF（2～100帧）'}),{status:400}))
  .mockResolvedValueOnce(new Response('upstream unavailable',{status:502}));
 const api=loadApi(fetch);
 const body={file_base64:'R0lGODlh',filename:'test.gif',name:'test',textSafeArea:{x:6,y:190,width:228,height:44},layout:{minFontSize:12,maxFontSize:24,textColor:'#222222',strokeColor:'#ffffff',strokeWidth:1,alignment:'center' as const,maxLines:2}};
 await expect(api.uploadSynthesisAsset(body)).rejects.toThrow('仅接受240×240多帧GIF（2～100帧）');
 expect(fetch.mock.calls[0][0]).toBe('/api/v1/dashboard/synthesis-library?user_id=user-a');
 expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual(body);
 await expect(api.uploadSynthesisAsset(body)).rejects.toThrow('502');
});
