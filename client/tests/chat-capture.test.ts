import { readFileSync } from 'node:fs';
import { afterEach, expect, it, vi } from '../../server/node_modules/vitest/dist/index.js';
import { compileScript, parse } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';

type Node = {
  tag: string;
  text: string;
  children: Node[];
  parent: Node | null;
  props: Record<string, any>;
  addEventListener: () => void;
  removeEventListener: () => void;
  getRootNode: () => object;
  tagName: string;
  options: Node[];
  selectedIndex: number;
};

const mounted: Vue.App[] = [];
afterEach(() => mounted.splice(0).forEach((app) => app.unmount()));

async function settle() {
  for (let index = 0; index < 12; index += 1) {
    await Promise.resolve();
    await Vue.nextTick();
  }
}

async function mountChatCapture(overrides: Record<string, any> = {}) {
  const deletedImages: Array<[string, number]> = [];
  const source = readFileSync(new URL('../src/views/ChatCapture.vue', import.meta.url), 'utf8');
  const { descriptor } = parse(source);
  const compiled = compileScript(descriptor, { id: 'chat-capture-test', inlineTemplate: true });
  const code = ts.transpileModule(compiled.content, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const node = (tag = '', text = ''): Node => {
    const target = {
      tag, text, children: [], parent: null, props: {}, selectedIndex: -1,
      addEventListener() {}, removeEventListener() {}, getRootNode: () => ({ activeElement: null }), tagName: tag.toUpperCase(),
    } as Node;
    Object.defineProperty(target, 'options', { get: () => target.children });
    return target;
  };
  function insert(child: Node, parent: Node, anchor?: Node | null) {
    child.parent = parent;
    const index = anchor ? parent.children.indexOf(anchor) : -1;
    if (index < 0) parent.children.push(child); else parent.children.splice(index, 0, child);
  }
  const renderer = Vue.createRenderer<Node, Node>({
    createElement: (tag) => node(tag),
    createText: (text) => node('', text),
    createComment: (text) => node('', text),
    setText: (target, text) => { target.text = text; },
    setElementText: (target, text) => { target.text = text; target.children = []; },
    parentNode: (target) => target.parent,
    nextSibling: (target) => target.parent?.children[target.parent.children.indexOf(target) + 1] ?? null,
    patchProp: (target, key, _previous, value) => { target.props[key] = value; },
    insert,
    remove(target) {
      if (target.parent) target.parent.children.splice(target.parent.children.indexOf(target), 1);
      target.parent = null;
    },
    insertStaticContent(text, parent, anchor) {
      const target = node('static', text);
      insert(target, parent, anchor);
      return [target, target];
    },
  });
  const api = {
    chatCaptureOverview: async () => ({ conversation_count: 1, message_count: 1, media_count: 1 }),
    chatConversations: async () => ({ total: 1, conversations: [{
      id: 1, platform: 'wechat', account_key: 'self', external_key: 'peer', display_name: '对方',
      conversation_type: 'direct', identity_confidence: 1, message_count: 1,
      first_seen_at: '2026-09-16T00:00:00Z', last_seen_at: '2026-09-16T00:00:00Z', last_message_at: '2026-09-16T00:00:00Z',
    }] }),
    chatMessages: async () => ({ total: 1, messages: [{
      id: 'message-1', platform: 'wechat', direction: 'system', message_type: 'image',
      sender_key: `title:${'a'.repeat(64)}:viewport`, sender_name: null,
      text: '聊天截图', displayed_time: null, occurred_at: '2026-09-16T00:00:00Z', captured_at: '2026-09-16T00:00:00Z',
      sequence_hint: null, metadata: { capture_source: 'wechat_empty_tree_screenshot' }, assets: [{ id: 8, sha256: 'a'.repeat(64), mime_type: 'image/png', width: 1200, height: 2664, role: 'content', position: 0, url: '/uploads/chat/screenshot.png' }],
    }] }),
    deleteChatImage: async (messageId: string, assetId: number) => {
      deletedImages.push([messageId, assetId]);
      return { ok: true, deleted_asset: true, deleted_message: true };
    },
  };
  Object.assign(api, overrides);
  const module = { exports: {} as { default: Vue.Component } };
  const require = (name: string) => {
    if (name === 'vue') return Vue;
    if (name === '../confirmation') return { useConfirmation: () => async (message: string) => Boolean(globalThis.window?.confirm?.(message)) };
    if (name === '../api') return { api, scopedAssetUrl: (url: string) => `/scoped${url}` };
    throw new Error(`Unexpected import: ${name}`);
  };
  const previousDocument = globalThis.document;
  const previousDocumentConstructor = globalThis.Document;
  const previousShadowRoot = globalThis.ShadowRoot;
  Object.assign(globalThis, {
    document: { activeElement: null },
    Document: class {},
    ShadowRoot: class {},
  });
  new Function('require', 'module', 'exports', code)(require, module, module.exports);
  const root = node('root');
  const app = renderer.createApp(module.exports.default);
  app.mount(root); mounted.push(app); await settle();
  const all = (target: Node): Node[] => [target, ...target.children.flatMap(all)];
  const find = (id: string) => all(root).find((target) => target.props['data-testid'] === id);
  Object.assign(globalThis, { document: previousDocument, Document: previousDocumentConstructor, ShadowRoot: previousShadowRoot });
  return { source, find, deletedImages, api, all: () => all(root), text: () => all(root).map(n => n.text).join(' ') };
}

it('点击聊天图片在本页弹窗预览并可关闭，不再生成新窗口链接', async () => {
  const view = await mountChatCapture();
  expect(view.source).not.toContain('target="_blank"');
  expect(view.find('chat-image-preview')).toBeUndefined();

  view.find('open-chat-image-8')!.props.onClick();
  await Vue.nextTick();
  expect(view.find('chat-image-preview')?.props.role).toBe('dialog');
  expect(view.find('chat-image-preview-image')?.props.src).toBe('/scoped/uploads/chat/screenshot.png');

  view.find('close-chat-image-preview')!.props.onClick();
  await Vue.nextTick();
  expect(view.find('chat-image-preview')).toBeUndefined();
});

it('单张聊天图片提供独立删除并调用消息资源接口', async () => {
  const view = await mountChatCapture();
  const previousWindow = globalThis.window;
  globalThis.window = { confirm: () => true } as Window & typeof globalThis;
  try {
    view.find('delete-chat-image-8')!.props.onClick();
    await settle();
    expect(view.deletedImages).toEqual([['message-1', 8]]);
  } finally {
    globalThis.window = previousWindow;
  }
});

it('截图名称显示聊天名称和精确到秒的采集时间，不暴露内部哈希', async () => {
  const view = await mountChatCapture();
  const label = view.find('chat-image-label-message-1')?.text ?? '';

  expect(label).toMatch(/^对方 \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
  expect(label).not.toContain('title:');
  expect(label).not.toContain(':viewport');
});

const screenshot = (n: number) => ({
  id: `message-${n}`, platform: 'wechat', direction: 'system', message_type: 'image',
  sender_key: 'peer', sender_name: '对方', text: '聊天截图',
  captured_at: new Date(Date.UTC(2026, 8, 17, 0, n)).toISOString(), occurred_at: null,
  metadata: { capture_source: 'wechat_empty_tree_screenshot' },
  assets: [{ id: n, url: `/uploads/chat/${n}.png` }],
});

it('几百条消息按服务端倒序分页，每页仅渲染24条并支持前后翻页', async () => {
  const rows = Array.from({ length: 301 }, (_, i) => screenshot(301 - i));
  const chatMessages = vi.fn(async (_id, page, size) => ({ total: rows.length, messages: rows.slice((page - 1) * size, page * size) }));
  const view = await mountChatCapture({ chatMessages });
  expect(chatMessages).toHaveBeenLastCalledWith(1, 1, 24);
  expect(view.all().filter(n => n.tag === 'article')).toHaveLength(24);
  expect(view.find('open-chat-image-301')).toBeDefined();
  expect(view.find('open-chat-image-277')).toBeUndefined();
  expect(view.text()).toContain('1 / 13');
  expect(view.text()).toContain('最新在前');
  expect(view.find('chat-page-prev')!.props.disabled).toBe(true);
  view.find('chat-page-next')!.props.onClick(); await settle();
  expect(chatMessages).toHaveBeenLastCalledWith(1, 2, 24);
  expect(view.find('open-chat-image-277')).toBeDefined();
  expect(view.find('open-chat-image-301')).toBeUndefined();
  view.find('chat-page-prev')!.props.onClick(); await settle();
  expect(view.find('open-chat-image-301')).toBeDefined();
});

it('切换会话回到第一页，较慢的旧会话响应不会覆盖当前内容', async () => {
  let resolveOld!: (result: any) => void;
  const chatMessages = vi.fn().mockResolvedValueOnce({ total: 25, messages: [screenshot(25)] })
    .mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }))
    .mockResolvedValueOnce({ total: 1, messages: [screenshot(99)] });
  const view = await mountChatCapture({ chatMessages, chatConversations: async () => ({ total: 2, conversations: [
    { id: 1, display_name: '对方', platform: 'wechat' }, { id: 2, display_name: '另一会话', platform: 'wechat' },
  ] }) });
  view.find('chat-page-next')!.props.onClick(); await settle();
  view.all().find(n => n.tag === 'button' && n.children.some(c => c.text === '另一会话'))!.props.onClick(); await settle();
  expect(chatMessages).toHaveBeenLastCalledWith(2, 1, 24);
  resolveOld({ total: 25, messages: [screenshot(1)] }); await settle();
  expect(view.find('open-chat-image-99')).toBeDefined();
  expect(view.find('open-chat-image-1')).toBeUndefined();
  expect(view.text()).toContain('1 / 1');
});

it('删除末页最后一张图片后回到有效页，取消删除不请求接口', async () => {
  const chatMessages = vi.fn().mockResolvedValueOnce({ total: 25, messages: [screenshot(25)] })
    .mockResolvedValueOnce({ total: 25, messages: [screenshot(1)] })
    .mockResolvedValueOnce({ total: 24, messages: [] })
    .mockResolvedValueOnce({ total: 24, messages: [screenshot(25)] });
  const view = await mountChatCapture({ chatMessages });
  view.find('chat-page-next')!.props.onClick(); await settle();
  const previousWindow = globalThis.window;
  try {
    globalThis.window = { confirm: () => false } as Window & typeof globalThis;
    view.find('delete-chat-image-1')!.props.onClick(); await settle();
    expect(view.deletedImages).toHaveLength(0);
    globalThis.window = { confirm: () => true } as Window & typeof globalThis;
    view.find('delete-chat-image-1')!.props.onClick(); await settle();
    expect(view.deletedImages).toEqual([['message-1', 1]]);
    expect(chatMessages.mock.calls.slice(-2)).toEqual([[1, 2, 24], [1, 1, 24]]);
    expect(view.find('open-chat-image-25')).toBeDefined();
    expect(view.text()).toContain('1 / 1');
  } finally { globalThis.window = previousWindow; }
});

it('翻页失败不残留上一页内容，提供重试且保留当前页码', async () => {
  const chatMessages = vi.fn().mockResolvedValueOnce({ total: 25, messages: [screenshot(25)] })
    .mockRejectedValueOnce(new Error('网络异常'))
    .mockResolvedValueOnce({ total: 25, messages: [screenshot(1)] });
  const view = await mountChatCapture({ chatMessages });
  view.find('chat-page-next')!.props.onClick(); await settle();
  expect(view.text()).toContain('网络异常');
  expect(view.find('open-chat-image-25')).toBeUndefined();
  view.find('chat-retry')!.props.onClick(); await settle();
  expect(chatMessages).toHaveBeenLastCalledWith(1, 2, 24);
  expect(view.find('open-chat-image-1')).toBeDefined();
});

it('首次会话尚在加载时切换，旧请求结束不能提前解除新会话的加载状态', async () => {
  let resolveFirst!: (result: any) => void;
  let resolveSecond!: (result: any) => void;
  const chatMessages = vi.fn().mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve; }))
    .mockImplementationOnce(() => new Promise(resolve => { resolveSecond = resolve; }));
  const view = await mountChatCapture({ chatMessages, chatConversations: async () => ({ total: 2, conversations: [
    { id: 1, display_name: '对方', platform: 'wechat' }, { id: 2, display_name: '另一会话', platform: 'wechat' },
  ] }) });
  view.all().find(n => n.tag === 'button' && n.children.some(c => c.text === '另一会话'))!.props.onClick(); await settle();
  resolveFirst({ total: 1, messages: [screenshot(1)] }); await settle();
  expect(view.text()).toContain('加载中');
  expect(view.find('open-chat-image-1')).toBeUndefined();
  resolveSecond({ total: 1, messages: [screenshot(2)] }); await settle();
  expect(view.find('open-chat-image-2')).toBeDefined();
});

it('混合消息保留文字和全部附件，本页类型筛选不会打乱顺序', async () => {
  const image = screenshot(3);
  image.assets.push({ id: 4, url: '/uploads/chat/4.png' });
  const view = await mountChatCapture({ chatMessages: async () => ({ total: 2, messages: [
    image, { ...screenshot(2), message_type: 'text', text: '保留文字内容', assets: [] },
  ] }) });
  expect(view.find('open-chat-image-3')).toBeDefined();
  expect(view.find('open-chat-image-4')).toBeDefined();
  expect(view.text()).toContain('保留文字内容');
  const filter = view.all().find(n => n.tag === 'select')!;
  expect(filter.props['aria-label']).toBe('本页消息类型筛选');
  filter.props['onUpdate:modelValue']('text'); await settle();
  expect(view.all().filter(n => n.tag === 'article')).toHaveLength(1);
  expect(view.text()).toContain('保留文字内容');
  expect(view.find('open-chat-image-3')).toBeUndefined();
});

it('空会话禁用前后页按钮且显示1/1，不产生额外请求', async () => {
  const chatMessages = vi.fn().mockResolvedValue({ total: 0, messages: [] });
  const view = await mountChatCapture({ chatMessages });
  expect(view.text()).toContain('暂无消息');
  expect(view.text()).toContain('1 / 1');
  expect(view.find('chat-page-prev')!.props.disabled).toBe(true);
  expect(view.find('chat-page-next')!.props.disabled).toBe(true);
  expect(chatMessages).toHaveBeenCalledTimes(1);
});

it('三个App标签按平台请求，切换清空旧消息和预览', async () => {
  const overview = vi.fn(async () => ({conversation_count: 0, message_count: 0, media_count: 0}));
  const list = vi.fn(async (_page: number, _size: number, platform: string) => ({
    total: platform === 'wechat' ? 1 : 0,
    conversations: platform === 'wechat' ? [{id: 1, platform, display_name: '微信测试'}] : [],
  }));
  const view = await mountChatCapture({chatCaptureOverview: overview, chatConversations: list});
  expect(overview).toHaveBeenLastCalledWith('wechat');
  expect(view.find('chat-tab-wechat')).toBeDefined();
  expect(view.find('chat-tab-qq')).toBeDefined();
  expect(view.find('chat-tab-douyin')).toBeDefined();
  view.find('open-chat-image-8')!.props.onClick(); await settle();
  view.find('chat-tab-qq')!.props.onClick(); await settle();
  expect(list).toHaveBeenLastCalledWith(1, 100, 'qq');
  expect(view.find('chat-image-preview')).toBeUndefined();
  expect(view.find('open-chat-image-8')).toBeUndefined();
  view.find('chat-tab-douyin')!.props.onClick(); await settle();
  expect(overview).toHaveBeenLastCalledWith('douyin');
});

it('快速切换App后迟到的旧平台概览和会话不能覆盖当前标签', async () => {
  let resolveQQ!: (value: any) => void;
  const view = await mountChatCapture({
    chatConversations: async (_page: number, _size: number, platform: string) => {
      if (platform === 'qq') return new Promise(resolve => {resolveQQ = resolve;});
      return {total: 0, conversations: []};
    },
  });
  view.find('chat-tab-qq')!.props.onClick(); await settle();
  view.find('chat-tab-douyin')!.props.onClick(); await settle();
  resolveQQ({total: 1, conversations: [{id: 55, platform: 'qq', display_name: '不应出现的QQ会话'}]});
  await settle();
  expect(view.text()).not.toContain('不应出现的QQ会话');
  expect(view.find('chat-tab-douyin')!.props['aria-selected']).toBe(true);
});

it('切换App后旧会话消息迟到也不会重新出现', async () => {
  let resolveOld!: (value: any) => void;
  const view = await mountChatCapture({
    chatConversations: async (_page: number, _size: number, platform: string) => ({
      total: platform === 'wechat' ? 1 : 0,
      conversations: platform === 'wechat' ? [{id: 1, platform, display_name: '旧会话'}] : [],
    }),
    chatMessages: () => new Promise(resolve => {resolveOld = resolve;}),
  });
  view.find('chat-tab-qq')!.props.onClick(); await settle();
  resolveOld({total: 1, messages: [{id: 'late', sender_key: '迟到旧消息', metadata: {}, assets: [], captured_at: '2026-09-17T00:00:00Z'}]});
  await settle();
  expect(view.text()).not.toContain('迟到旧消息');
  expect(view.find('chat-tab-qq')!.props['aria-selected']).toBe(true);
});

it('抖音聊天截图标签显示会话名和采集时间，不显示内部viewport标识', async () => {
  const view = await mountChatCapture({chatMessages: async () => ({total: 1, messages: [{
    id: 'douyin-image', platform: 'douyin', direction: 'system', message_type: 'image',
    sender_key: 'viewport', captured_at: '2026-09-17T00:00:00Z',
    metadata: {capture_source: 'douyin_screenshot', capture_kind: 'conversation_screenshot'}, assets: [],
  }]})});
  expect(view.find('chat-image-label-douyin-image')!.text).toContain('对方');
  expect(view.find('chat-image-label-douyin-image')!.text).toContain('2026-09-17');
});
