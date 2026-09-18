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


it('预览方向请求带当前用户与精确会话/图片锚点，不按页批量下载图片',async()=>{
 const fetch=vi.fn().mockResolvedValue(new Response(JSON.stringify({image:null})));const api=loadApi(fetch);
 expect(await api.chatAdjacentImage(12,'message-id',8,'next')).toEqual({image:null});
 expect(fetch.mock.calls[0][0]).toBe('/api/v1/dashboard/chat/images/adjacent?conversation_id=12&message_id=message-id&asset_id=8&direction=next&user_id=user-a');
});
it('图片批量一次提交明确关联，失败传递后端说明，非JSON也安全失败',async()=>{
 const body={confirm:'DELETE',conversation_id:12,images:[{message_id:'m',asset_id:8}]};
 const fetch=vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({deleted_images:1,deleted_messages:0,files_pending:false})))
 .mockResolvedValueOnce(new Response(JSON.stringify({error:'整批未删除'}),{status:409})).mockResolvedValueOnce(new Response('upstream failure',{status:502}));
 const api=loadApi(fetch);await api.deleteChatImages(body);
 expect(fetch.mock.calls[0][0]).toBe('/api/v1/dashboard/chat/images/delete-batch?user_id=user-a');expect(fetch.mock.calls[0][1].method).toBe('POST');expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual(body);
 await expect(api.deleteChatImages(body)).rejects.toThrow('整批未删除');await expect(api.deleteChatImages(body)).rejects.toThrow('502');
});
it('同名显示组的API编码精确名字，读取和删除保持同一范围',async()=>{
 const fetch=vi.fn().mockImplementation(async()=>new Response(JSON.stringify({conversations:[],image:null,messages:[]})));const api=loadApi(fetch);
 await api.chatConversations(1,100,'wechat','',true,true);
 expect(fetch.mock.calls[0][0]).toContain('group_names=true');
 const name='张三 & 同学%';
 await api.chatMessages(11,1,24,'wechat',name);await api.chatAdjacentImage(11,'message-id',8,'next','wechat',name);await api.chatConversationGroup('wechat',name);
 for(const call of fetch.mock.calls.slice(1,3)){const url=new URL(call[0],'http://test.local');expect(url.searchParams.get('group_name')).toBe(name);expect(url.searchParams.get('platform')).toBe('wechat');expect(url.searchParams.get('user_id')).toBe('user-a');}
 expect(new URL(fetch.mock.calls[3][0],'http://test.local').searchParams.get('name')).toBe(name);
 const body={confirm:'DELETE' as const,platform:'wechat' as const,group_name:name,source_ids:[11,12]};await api.deleteChatConversationGroup(body);
 expect(fetch.mock.calls[4][0]).toBe('/api/v1/dashboard/chat/conversation-groups/delete?user_id=user-a');expect(JSON.parse(fetch.mock.calls[4][1].body)).toEqual(body);
 fetch.mockResolvedValueOnce(new Response(JSON.stringify({error:'来源已变化'}),{status:409}));await expect(api.deleteChatConversationGroup(body)).rejects.toThrow('来源已变化');
});
