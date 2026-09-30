import { expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
const read=(path:string)=>readFileSync(new URL('../../../client/src/'+path,import.meta.url),'utf8');
it('通话记录入口与路由源码交付，按日选择且播放走鉴权请求',()=>{
  expect(read('App.vue')).toContain("path: '/call-recordings'");
  expect(read('main.ts')).toContain("path: '/call-recordings'");
  const view=read('views/CallRecordingsView.vue');
  expect(view).toContain('type="date"');expect(view).not.toContain('datetime-local');
  expect(view).toContain('URL.revokeObjectURL');expect(view).toContain('currentUserId');
  expect(read('api/callRecordings.ts')).toContain('dashboardFetch');
});
