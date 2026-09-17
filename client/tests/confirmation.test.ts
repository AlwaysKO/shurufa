import { afterEach, expect, it, vi } from '../../server/node_modules/vitest/dist/index.js';
import { readFileSync } from 'node:fs';
import * as Vue from 'vue';
import { confirmation, requestConfirmation, finishConfirmation, cancelConfirmation, handleConfirmationKey, useConfirmation } from '../src/confirmation';

afterEach(() => cancelConfirmation());
const key = (value: string, extra: Record<string, unknown> = {}) => ({ key: value, repeat: false, isComposing: false, keyCode: 0, preventDefault: vi.fn(), stopImmediatePropagation: vi.fn(), ...extra } as unknown as KeyboardEvent);

it('自定义确认保持原文，取消返回false，确认返回true', async () => {
  const first = requestConfirmation('共3条原始记录\n删除后不可恢复');
  expect(confirmation.value?.message).toContain('共3条原始记录');
  expect(confirmation.value?.confirmText).toBe('确认删除');
  finishConfirmation(false); expect(await first).toBe(false);
  const second = requestConfirmation('清理当前范围', { title: '确认清理数据', confirmText: '确认清理' });
  expect(confirmation.value?.title).toBe('确认清理数据');
  finishConfirmation(true); expect(await second).toBe(true);
  expect(confirmation.value).toBeNull();
});

it('Enter确认、Esc取消；弹窗关闭时不拦截页面键盘', async () => {
  const closed = key('Enter'); handleConfirmationKey(closed);
  expect(closed.preventDefault).not.toHaveBeenCalled();
  const approved = requestConfirmation('删除记录');
  handleConfirmationKey(key('Enter')); expect(await approved).toBe(true);
  const cancelled = requestConfirmation('删除记录');
  handleConfirmationKey(key('Escape')); expect(await cancelled).toBe(false);
});

it('长按和输入法选词不确认删除，且阻止默认按钮重复激活', async () => {
  const result = requestConfirmation('删除记录');
  for (const options of [{ repeat: true }, { isComposing: true }, { keyCode: 229 }]) {
    const event = key('Enter', options); handleConfirmationKey(event);
    expect(event.preventDefault).toHaveBeenCalled();
    expect(confirmation.value).not.toBeNull();
  }
  handleConfirmationKey(key('Escape')); expect(await result).toBe(false);
});

it('同时只能有一个确认请求，重复点击不创建第二次删除', async () => {
  const first = requestConfirmation('第一条');
  expect(await requestConfirmation('第二条')).toBe(false);
  expect(confirmation.value?.message).toBe('第一条');
  finishConfirmation(true); finishConfirmation(true);
  expect(await first).toBe(true);
});

it('切换页面、用户或登录状态会作废待确认及刚确认尚未执行的请求', async () => {
  const pending = requestConfirmation('待确认'); cancelConfirmation(); expect(await pending).toBe(false);
  const resolved = requestConfirmation('刚确认'); finishConfirmation(true); cancelConfirmation();
  expect(await resolved).toBe(false);
});

it('页面组件卸载取消自己的请求，不留下可执行的确认', async () => {
  let confirm!: ReturnType<typeof useConfirmation>;
  const renderer = Vue.createRenderer<any, any>({ createElement: () => ({}), createText: () => ({}), createComment: () => ({}), setText() {}, setElementText() {}, parentNode: () => null, nextSibling: () => null, patchProp() {}, insert() {}, remove() {}, insertStaticContent: () => [{}, {}] });
  const app = renderer.createApp({ setup() { confirm = useConfirmation(); return () => null; } });
  app.mount({});
  const pending = confirm('组件内删除'); app.unmount();
  expect(await pending).toBe(false); expect(confirmation.value).toBeNull();
});

it('全部相关页面改用统一确认服务，不遗留浏览器原生确认', () => {
  for (const page of ['Activity','ChatCapture','Phrases','Stickers','SynthesisLibrary','PersonalDictionary','DataManage']) {
    const source = readFileSync(new URL(`../src/views/${page}.vue`, import.meta.url), 'utf8');
    expect(source, page).toContain('useConfirmation');
    expect(source, page).not.toMatch(/(?:window\.)?\bconfirm\s*\(/);
  }
  const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8');
  expect(app).toContain('ConfirmationDialog');
  expect(app).toContain('cancelConfirmation');
});

it('确认结果传回组件前发生用户切换，也不能继续删除', async () => {
  let confirm!: ReturnType<typeof useConfirmation>;
  const renderer = Vue.createRenderer<any, any>({ createElement: () => ({}), createText: () => ({}), createComment: () => ({}), setText() {}, setElementText() {}, parentNode: () => null, nextSibling: () => null, patchProp() {}, insert() {}, remove() {}, insertStaticContent: () => [{}, {}] });
  const app = renderer.createApp({ setup() { confirm = useConfirmation(); return () => null; } });
  app.mount({});
  try {
    const pending = confirm('即将删除');
    finishConfirmation(true);
    await Promise.resolve(); // 底层请求已接受，组件封装层尚未恢复。
    cancelConfirmation();
    expect(await pending).toBe(false);
  } finally { app.unmount(); }
});
