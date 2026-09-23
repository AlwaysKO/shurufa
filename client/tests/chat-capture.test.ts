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

const statisticValues = (view: { all: () => Node[] }) =>
  view.all().filter(node => node.props.class === 'num').map(node => Number(node.text));

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
      focus() {}, addEventListener() {}, removeEventListener() {}, getRootNode: () => ({ activeElement: null }), tagName: tag.toUpperCase(),
    } as Node;
    Object.defineProperty(target, 'options', { get: () => target.children });
    return Vue.markRaw(target);
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
  const currentUserId = Vue.ref('user-a');
  const module = { exports: {} as { default: Vue.Component } };
  const require = (name: string) => {
    if (name === 'vue') return Vue;
    if (name === '../confirmation') return { useConfirmation: () => async (message: string) => Boolean(await globalThis.window?.confirm?.(message)) };
    if (name === '../api') return { api, currentUserId, scopedAssetUrl: (url: string) => `/scoped${url}` };
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
  return { source, find, deletedImages, api, currentUserId, unmount: () => app.unmount(), all: () => all(root), text: () => all(root).map(n => n.text).join(' ') };
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
  assets: [{ id: n, mime_type: 'image/png', role: 'content', position: 0, url: `/uploads/chat/${n}.png` }],
});

it('几百条消息按服务端倒序分页，每页仅渲染20条并支持前后翻页', async () => {
  const rows = Array.from({ length: 301 }, (_, i) => screenshot(301 - i));
  const chatMessages = vi.fn(async (_id, page, size) => ({ total: rows.length, messages: rows.slice((page - 1) * size, page * size) }));
  const view = await mountChatCapture({ chatMessages });
  expect(chatMessages).toHaveBeenLastCalledWith(1, 1, 20);
  expect(view.all().filter(n => n.tag === 'article')).toHaveLength(20);
  expect(view.find('open-chat-image-301')).toBeDefined();
  expect(view.find('open-chat-image-281')).toBeUndefined();
  expect(view.text()).toContain('1 / 16');
  expect(view.text()).toContain('最新在前');
  expect(view.find('chat-page-prev')!.props.disabled).toBe(true);
  view.find('chat-page-next')!.props.onClick(); await settle();
  expect(chatMessages).toHaveBeenLastCalledWith(1, 2, 20);
  expect(view.find('open-chat-image-281')).toBeDefined();
  expect(view.find('open-chat-image-301')).toBeUndefined();
  view.find('chat-page-prev')!.props.onClick(); await settle();
  expect(view.find('open-chat-image-301')).toBeDefined();
});

it('切换会话回到第一页，较慢的旧会话响应不会覆盖当前内容', async () => {
  let resolveOld!: (result: any) => void;
  const chatMessages = vi.fn().mockResolvedValueOnce({ total: 21, messages: [screenshot(21)] })
    .mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }))
    .mockResolvedValueOnce({ total: 1, messages: [screenshot(99)] });
  const view = await mountChatCapture({ chatMessages, chatConversations: async () => ({ total: 2, conversations: [
    { id: 1, display_name: '对方', platform: 'wechat' }, { id: 2, display_name: '另一会话', platform: 'wechat' },
  ] }) });
  view.find('chat-page-next')!.props.onClick(); await settle();
  view.all().find(n => n.tag === 'button' && n.children.some(c => c.text === '另一会话'))!.props.onClick(); await settle();
  expect(chatMessages).toHaveBeenLastCalledWith(2, 1, 20);
  resolveOld({ total: 21, messages: [screenshot(1)] }); await settle();
  expect(view.find('open-chat-image-99')).toBeDefined();
  expect(view.find('open-chat-image-1')).toBeUndefined();
  expect(view.text()).toContain('1 / 1');
});

it('删除末页最后一张图片后回到有效页，取消删除不请求接口', async () => {
  const chatMessages = vi.fn().mockResolvedValueOnce({ total: 21, messages: [screenshot(21)] })
    .mockResolvedValueOnce({ total: 21, messages: [screenshot(1)] })
    .mockResolvedValueOnce({ total: 20, messages: [] })
    .mockResolvedValueOnce({ total: 20, messages: [screenshot(21)] });
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
    expect(chatMessages.mock.calls.slice(-2)).toEqual([[1, 2, 20], [1, 1, 20]]);
    expect(view.find('open-chat-image-21')).toBeDefined();
    expect(view.text()).toContain('1 / 1');
  } finally { globalThis.window = previousWindow; }
});

it('翻页失败不残留上一页内容，提供重试且保留当前页码', async () => {
  const chatMessages = vi.fn().mockResolvedValueOnce({ total: 21, messages: [screenshot(21)] })
    .mockRejectedValueOnce(new Error('网络异常'))
    .mockResolvedValueOnce({ total: 21, messages: [screenshot(1)] });
  const view = await mountChatCapture({ chatMessages });
  view.find('chat-page-next')!.props.onClick(); await settle();
  expect(view.text()).toContain('网络异常');
  expect(view.find('open-chat-image-21')).toBeUndefined();
  view.find('chat-retry')!.props.onClick(); await settle();
  expect(chatMessages).toHaveBeenLastCalledWith(1, 2, 20);
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
  image.assets.push({ ...image.assets[0], id: 4, url: '/uploads/chat/4.png' });
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
  expect(list).toHaveBeenLastCalledWith(1, 100, 'qq', '', true, true);
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

const nextImage = (n: number) => ({ image: { message_id: `message-${n}`, asset_id: n, url: `/uploads/chat/${n}.png`, alt: `图片${n}`, captured_at: '2026-09-18T00:00:00Z', ordinal: n, total: 30 } });
it('放大后上一张下一张按会话锚点查询，跨越列表页且不改变当前列表页', async () => {
  const chatAdjacentImage = vi.fn().mockResolvedValue(nextImage(9));
  const view = await mountChatCapture({ chatAdjacentImage });
  view.find('open-chat-image-8')!.props.onClick(); await settle();
  view.find('chat-image-next')!.props.onClick(); await settle();
  expect(chatAdjacentImage).toHaveBeenCalledWith(1, 'message-1', 8, 'next');
  expect(view.find('chat-image-preview-image')!.props.src).toBe('/scoped/uploads/chat/9.png');
  view.find('chat-image-prev')!.props.onClick(); await settle();
  expect(chatAdjacentImage).toHaveBeenLastCalledWith(1, 'message-9', 9, 'previous');
  expect(view.find('open-chat-image-8')).toBeDefined();
});
it('方向键和横向滑动都可翻图，竖向或短距离移动不误触，Esc关闭', async () => {
  const chatAdjacentImage = vi.fn().mockResolvedValue(nextImage(9));const view = await mountChatCapture({ chatAdjacentImage });
  view.find('open-chat-image-8')!.props.onClick(); await settle();
  const key = (key: string) => ({ key, preventDefault: vi.fn(), stopPropagation: vi.fn(), isComposing: false, repeat: false });
  view.find('chat-image-preview')!.props.onKeydown(key('ArrowRight')); await settle(); expect(chatAdjacentImage).toHaveBeenCalledTimes(1);
  const surface = view.find('chat-image-swipe')!;
  const point = (x:number,y:number) => ({ pointerId:1,clientX:x,clientY:y,button:0,isPrimary:true });
  surface.props.onPointerdown(point(100,100));surface.props.onPointerup(point(95,200));await settle();expect(chatAdjacentImage).toHaveBeenCalledTimes(1);
  surface.props.onPointerdown(point(180,100));surface.props.onPointerup(point(60,105));await settle();expect(chatAdjacentImage).toHaveBeenCalledTimes(2);
  surface.props.onPointerdown(point(50,100));surface.props.onPointerup(point(160,100));await settle();expect(chatAdjacentImage.mock.calls.at(-1)![3]).toBe('previous');
  view.find('chat-image-preview')!.props.onKeydown(key('Escape'));await settle();expect(view.find('chat-image-preview')).toBeUndefined();
});
it('浏览边界不循环，失败保留当前图并允许重试', async () => {
  const chatAdjacentImage = vi.fn().mockRejectedValueOnce(Error('网络中断')).mockResolvedValue({ image:null });
  const view = await mountChatCapture({ chatAdjacentImage });view.find('open-chat-image-8')!.props.onClick();await settle();
  view.find('chat-image-next')!.props.onClick();await settle();expect(view.text()).toContain('网络中断');expect(view.find('chat-image-preview-image')!.props.src).toContain('screenshot.png');
  view.find('chat-image-next')!.props.onClick();await settle();expect(view.find('chat-image-next')!.props.disabled).toBe(true);expect(view.text()).toContain('最后一张');
});
it('连续点击只请求一次，关闭后迟到响应不能重新打开预览', async () => {
  let resolve!: (value: unknown) => void;const chatAdjacentImage = vi.fn(() => new Promise(r=>{resolve=r;}));
  const view = await mountChatCapture({ chatAdjacentImage });view.find('open-chat-image-8')!.props.onClick();await settle();
  const click=view.find('chat-image-next')!.props.onClick;click();click();await settle();expect(chatAdjacentImage).toHaveBeenCalledOnce();
  view.find('close-chat-image-preview')!.props.onClick();await settle();resolve(nextImage(9));await settle();expect(view.find('chat-image-preview')).toBeUndefined();
});
it('全选只选当前筛选可见图片，支持一消息多图和共享图片不同消息，全不选清空', async () => {
  const first=screenshot(1), second={...screenshot(2),message_type:'text',text:'保留文字',assets:[{...first.assets[0]}]};
  first.assets.push({...first.assets[0],id:3,url:'/uploads/chat/3.png'});
  const view=await mountChatCapture({chatMessages:async()=>({total:99,messages:[first,second]})});
  view.find('chat-select-page')!.props.onClick();await settle();expect(view.find('chat-selection-count')!.text).toContain('3');
  view.find('chat-clear-selection')!.props.onClick();await settle();expect(view.find('chat-delete-selected')!.props.disabled).toBe(true);
  view.all().find(n=>n.tag==='select')!.props['onUpdate:modelValue']('image');await settle();
  view.find('chat-select-page')!.props.onClick();await settle();expect(view.find('chat-selection-count')!.text).toContain('2');
});
it('批量取消不请求，失败保留选择和图片，确认发送精确关联而非整条消息', async () => {
  const previous=globalThis.window;const confirm=vi.fn().mockReturnValue(false);globalThis.window={confirm} as unknown as Window & typeof globalThis;
  try {
    const deleteChatImages=vi.fn().mockRejectedValue(Error('记录已变化'));
    const view=await mountChatCapture({deleteChatImages});view.find('chat-select-page')!.props.onClick();await settle();
    view.find('chat-delete-selected')!.props.onClick();await settle();expect(deleteChatImages).not.toHaveBeenCalled();
    confirm.mockReturnValue(true);view.find('chat-delete-selected')!.props.onClick();await settle();
    expect(deleteChatImages).toHaveBeenCalledWith({confirm:'DELETE',conversation_id:1,images:[{message_id:'message-1',asset_id:8}]});
    expect(confirm.mock.calls.at(-1)![0]).toContain('1 张');expect(view.find('chat-selection-count')!.text).toContain('1');expect(view.text()).toContain('记录已变化');expect(view.find('open-chat-image-8')).toBeDefined();
  } finally{globalThis.window=previous;}
});
it('删除末页全部图片回到有效页，文件待重试提示不伪装事务失败', async()=>{
  const previous=globalThis.window;globalThis.window={confirm:()=>true} as unknown as Window & typeof globalThis;
  try{
    const chatMessages=vi.fn().mockResolvedValueOnce({total:21,messages:[screenshot(1)]}).mockResolvedValueOnce({total:21,messages:[screenshot(21)]})
      .mockResolvedValueOnce({total:20,messages:[]}).mockResolvedValue({total:20,messages:[screenshot(1)]});
    const view=await mountChatCapture({chatMessages,deleteChatImages:async()=>({deleted_images:1,deleted_messages:1,files_pending:true})});
    view.find('chat-page-next')!.props.onClick();await settle();view.find('chat-select-page')!.props.onClick();await settle();view.find('chat-delete-selected')!.props.onClick();await settle();
    expect(chatMessages.mock.calls.map(c=>c[1])).toEqual([1,2,2,1]);expect(view.find('chat-selection-count')!.text).toContain('0');expect(view.text()).toContain('后台重试');
  }finally{globalThis.window=previous;}
});
it('翻页、筛选和App切换清空选择并关闭预览，不保留隐藏选择',async()=>{
  const view=await mountChatCapture({chatMessages:async()=>({total:21,messages:[screenshot(1)]})});
  view.find('chat-select-page')!.props.onClick();await settle();view.find('open-chat-image-1')!.props.onClick();await settle();
  view.all().find(n=>n.tag==='select')!.props['onUpdate:modelValue']('image');await settle();
  expect(view.find('chat-selection-count')!.text).toContain('0');expect(view.find('chat-image-preview')).toBeUndefined();
  view.find('chat-select-page')!.props.onClick();await settle();view.find('chat-page-next')!.props.onClick();await settle();expect(view.find('chat-selection-count')!.text).toContain('0');
  view.find('chat-select-page')!.props.onClick();await settle();view.find('chat-tab-qq')!.props.onClick();await settle();expect(view.find('chat-selection-count')!.text).toContain('0');
});
it('确认期间筛选改变则取消旧批次，快速重复提交只有一次请求',async()=>{
  const previous=globalThis.window;let answer!:(yes:boolean)=>void;const confirm=vi.fn(()=>new Promise<boolean>(r=>{answer=r;}));globalThis.window={confirm} as unknown as Window & typeof globalThis;
  try{
    const deleteChatImages=vi.fn().mockResolvedValue({deleted_images:1,files_pending:false});const view=await mountChatCapture({deleteChatImages});
    view.find('chat-select-page')!.props.onClick();await settle();const click=view.find('chat-delete-selected')!.props.onClick;click();click();await settle();expect(confirm).toHaveBeenCalledOnce();
    view.all().find(n=>n.tag==='select')!.props['onUpdate:modelValue']('image');await settle();answer(true);await settle();expect(deleteChatImages).not.toHaveBeenCalled();
  }finally{globalThis.window=previous;}
});

it('图片已经删除但统计刷新失败时保留成功提示，不能误报整批删除失败',async()=>{
 const previous=globalThis.window;globalThis.window={confirm:()=>true} as unknown as Window & typeof globalThis;
 try{
  const overview=vi.fn().mockResolvedValueOnce({conversation_count:1,message_count:1,media_count:1}).mockRejectedValueOnce(Error('统计网络错误'));
  const view=await mountChatCapture({chatCaptureOverview:overview,deleteChatImages:async()=>({deleted_images:1,deleted_messages:1,files_pending:false})});
  view.find('chat-select-page')!.props.onClick();await settle();view.find('chat-delete-selected')!.props.onClick();await settle();
  expect(view.text()).toContain('已删除 1 张');expect(view.text()).toContain('刷新失败');expect(view.text()).not.toContain('删除图片失败');
 }finally{globalThis.window=previous;}
});

const rememberedChat = (id:number, platform='wechat') => ({id,platform,account_key:'self',external_key:`peer-${id}`,display_name:`会话${id}`,conversation_type:'direct',identity_confidence:1,message_count:0});
function fakeChatStorage(initial:Record<string,string>={}) { const data=new Map(Object.entries(initial)); vi.stubGlobal('localStorage',{getItem:(k:string)=>data.get(k)??null,setItem:(k:string,v:string)=>data.set(k,v)});return data; }
afterEach(()=>vi.unstubAllGlobals());
it('刷新恢复App和该App会话，而不是微信第一项',async()=>{
 fakeChatStorage({'chat-capture-selection:user-a':JSON.stringify({platform:'qq',conversations:{qq:22}})});
 const requests:number[]=[];
 const view=await mountChatCapture({chatConversations:async()=>({total:2,conversations:[rememberedChat(21,'qq'),rememberedChat(22,'qq')]}),chatMessages:async(id:number)=>{requests.push(id);return{total:0,messages:[]};}});
 expect(view.find('chat-tab-qq')!.props['aria-selected']).toBe(true);expect(requests).toEqual([22]);
});
it('不在首页的保存ID解析到合并目标',async()=>{
 const data=fakeChatStorage({'chat-capture-selection:user-a':JSON.stringify({platform:'wechat',conversations:{wechat:200}})});
 const requests:number[]=[];
 const view=await mountChatCapture({resolveChatConversation:async()=>({conversation:rememberedChat(300)}),chatMessages:async(id:number)=>{requests.push(id);return{total:0,messages:[]};}});
 expect(requests).toEqual([300]);expect(JSON.parse(data.get('chat-capture-selection:user-a')!).conversations.wechat).toBe(300);expect(view.text()).toContain('会话300');
});
it('不同App记住各自会话，重新加载仍保留',async()=>{
 const data=fakeChatStorage();
 const view=await mountChatCapture({chatConversations:async(_p:number,_s:number,app:string)=>({total:2,conversations:[rememberedChat(app==='qq'?11:1,app),rememberedChat(app==='qq'?12:2,app)]})});
 await view.find('chat-conversation-2')!.props.onClick();await settle();await view.find('chat-tab-qq')!.props.onClick();await settle();await view.find('chat-conversation-12')!.props.onClick();await settle();await view.find('chat-tab-wechat')!.props.onClick();await settle();
 expect(JSON.parse(data.get('chat-capture-selection:user-a')!).conversations).toEqual({wechat:2,qq:12});expect(view.text()).toContain('会话2');
});
it('手动合并经确认调用明确目标，随后跳转目标会话',async()=>{
 fakeChatStorage();vi.stubGlobal('window',{confirm:vi.fn(()=>true)});const calls:any[]=[];
 const view=await mountChatCapture({chatConversations:async()=>({total:2,conversations:[rememberedChat(1),rememberedChat(2)]}),mergeChatConversation:async(...args:any[])=>{calls.push(args);return{target_id:2};},resolveChatConversation:async()=>({conversation:rememberedChat(2)})});
 await view.find('chat-open-merge')!.props.onClick();await settle();await view.find('chat-merge-target-2')!.props.onClick();await settle();expect(calls).toEqual([[1,2]]);
 expect(view.find('chat-conversation-2')!.props.class).toContain('selected');
});

it('恢复会话网络错误不回退到第一项、不覆盖已记忆ID',async()=>{
 const data=fakeChatStorage({'chat-capture-selection:user-a':JSON.stringify({platform:'qq',conversations:{qq:99}})});const requests:number[]=[];
 const view=await mountChatCapture({resolveChatConversation:async()=>{throw Error('网络失败');},chatMessages:async(id:number)=>{requests.push(id);return{total:0,messages:[]};}});
 expect(view.text()).toContain('网络失败');expect(requests).toEqual([]);expect(JSON.parse(data.get('chat-capture-selection:user-a')!).conversations.qq).toBe(99);
});
it('只有会话确实删除才回退，并修正保存的选择',async()=>{
 const data=fakeChatStorage({'chat-capture-selection:user-a':JSON.stringify({platform:'wechat',conversations:{wechat:99}})});
 await mountChatCapture({resolveChatConversation:async()=>({conversation:null})});expect(JSON.parse(data.get('chat-capture-selection:user-a')!).conversations.wechat).toBe(1);
});

it('切换App立即保存选择，加载中刷新也不会退回旧App或丢失该App会话',async()=>{
 const data=fakeChatStorage({'chat-capture-selection:user-a':JSON.stringify({platform:'wechat',conversations:{wechat:1,qq:12}})});
 const view=await mountChatCapture({chatConversations:async(_p:number,_s:number,app:string)=>app==='qq'?new Promise(()=>{}):({total:1,conversations:[rememberedChat(1)]})});
 view.find('chat-tab-qq')!.props.onClick();
 expect(JSON.parse(data.get('chat-capture-selection:user-a')!).platform).toBe('qq');expect(JSON.parse(data.get('chat-capture-selection:user-a')!).conversations.qq).toBe(12);
});

it('待确认统一入口刷新保留，读取跨来源图片但不能整组误合并或删除',async()=>{
 fakeChatStorage({'chat-capture-selection:user-a':JSON.stringify({platform:'wechat',conversations:{wechat:-1}})});
 const bucket={...rememberedChat(-1),display_name:'待确认会话',identity_confidence:0,is_pending_group:true,message_count:2};
 const chatConversations=vi.fn().mockResolvedValue({total:2,conversations:[rememberedChat(1),bucket]});
 const chatMessages=vi.fn().mockResolvedValue({total:2,messages:[{...screenshot(8),conversation_id:81},{...screenshot(9),conversation_id:82}]});
 const view=await mountChatCapture({chatConversations,chatMessages});
 expect(chatMessages.mock.calls[0]).toEqual([-1,1,20,'wechat']);
 expect(chatConversations.mock.calls[0]).toEqual([1,100,'wechat','',true,true]);
 expect(view.find('chat-open-merge')!.props.disabled).toBe(true);
 expect(view.find('chat-delete-conversation')!.props.disabled).toBe(true);
 expect(view.find('chat-confirm-source-message-8')).toBeDefined();
 expect(view.find('chat-conversation-81')).toBeUndefined();
});

it('待确认中只合并点击图片的真实来源，不把整个集合发给合并接口',async()=>{
 fakeChatStorage();const previous=globalThis.window;globalThis.window={confirm:()=>true} as unknown as Window & typeof globalThis;
 try{
  const bucket={...rememberedChat(-1),display_name:'待确认会话',identity_confidence:0,is_pending_group:true};
  const target=rememberedChat(2);const mergeChatConversation=vi.fn().mockResolvedValue({target_id:2,moved_messages:1});
  const view=await mountChatCapture({chatConversations:async()=>({total:2,conversations:[bucket,target]}),
   chatMessages:async()=>({total:1,messages:[{...screenshot(8),conversation_id:81}]}),
   resolveChatConversation:async()=>({conversation:{...rememberedChat(81),identity_confidence:.55}}),mergeChatConversation});
  view.find('chat-confirm-source-message-8')!.props.onClick();await settle();
  view.find('chat-merge-target-2')!.props.onClick();await settle();
  expect(mergeChatConversation).toHaveBeenCalledExactlyOnceWith(81,2);
 }finally{globalThis.window=previous;}
});
it('来源已确认时拒绝从旧待确认卡片再次合并',async()=>{
 fakeChatStorage();const bucket={...rememberedChat(-1),display_name:'待确认会话',identity_confidence:0,is_pending_group:true};
 const view=await mountChatCapture({chatConversations:async()=>({total:1,conversations:[bucket]}),chatMessages:async()=>({total:1,messages:[{...screenshot(8),conversation_id:81}]}),resolveChatConversation:async()=>({conversation:rememberedChat(81)})});
 view.find('chat-confirm-source-message-8')!.props.onClick();await settle();
 expect(view.text()).toContain('已确认或已合并');expect(view.find('chat-merge-target-81')).toBeUndefined();
});
it('刷新旧的待确认来源选择只恢复汇总入口，不重新插入独立标签',async()=>{
 fakeChatStorage({'chat-capture-selection:user-a':JSON.stringify({platform:'wechat',conversations:{wechat:81}})});
 const bucket={...rememberedChat(-1),display_name:'待确认会话',identity_confidence:0,is_pending_group:true};
 const view=await mountChatCapture({chatConversations:async()=>({total:1,conversations:[bucket]}),resolveChatConversation:async()=>({conversation:{...rememberedChat(81),display_name:'识别中的名字',external_key:'screenshot-v2:pending:old',identity_confidence:.55}})});
 expect(view.find('chat-conversation-81')).toBeUndefined();expect(view.find('chat-conversation--1')!.props.class).toContain('selected');
});

const namedGroup=(id:number,name:string,source_ids:number[])=>({...rememberedChat(id),display_name:name,group_name:name,is_name_group:true,source_ids,source_count:source_ids.length});
it('同名会话请求整组消息和图片，而不是只读取代表ID',async()=>{
 fakeChatStorage();const group=namedGroup(11,'同名联系人',[11,12]);
 const chatMessages=vi.fn().mockResolvedValue({total:2,messages:[{...screenshot(8),conversation_id:12}]});
 const chatAdjacentImage=vi.fn().mockResolvedValue({image:null});
 const view=await mountChatCapture({chatConversations:async()=>({total:1,conversations:[group]}),chatMessages,chatAdjacentImage});
 expect(chatMessages).toHaveBeenCalledWith(11,1,20,'wechat','同名联系人');
 view.find('open-chat-image-8')!.props.onClick();await settle();view.find('chat-image-next')!.props.onClick();await settle();
 expect(chatAdjacentImage).toHaveBeenCalledWith(11,'message-8',8,'next','wechat','同名联系人');
 expect(view.find('chat-open-merge')!.props.disabled).toBe(true);
 expect(view.find('chat-confirm-source-message-8')).toBeDefined();
});
it('旧同名来源选中状态恢复到展示组，不把来源重新插成重复标签',async()=>{
 fakeChatStorage({'chat-capture-selection:user-a':JSON.stringify({platform:'wechat',conversations:{wechat:12}})});
 const group=namedGroup(11,'同名联系人',[11,12]);
 const view=await mountChatCapture({chatConversations:async()=>({total:1,conversations:[group]}),resolveChatConversation:async()=>({conversation:{...rememberedChat(12),display_name:'同名联系人',is_pending_source:false}})});
 expect(view.find('chat-conversation-12')).toBeUndefined();expect(view.find('chat-conversation-11')!.props.class).toContain('selected');
});
it('代表来源删除后刷新仍可按组名恢复，不跳回首项',async()=>{
 fakeChatStorage({'chat-capture-selection:user-a':JSON.stringify({platform:'wechat',conversations:{wechat:11},groups:{wechat:'同名联系人'}})});
 const group=namedGroup(12,'同名联系人',[12,13]);const chatMessages=vi.fn().mockResolvedValue({total:0,messages:[]});
 await mountChatCapture({chatConversations:async()=>({total:2,conversations:[namedGroup(1,'首项',[1]),group]}),chatMessages});
 expect(chatMessages).toHaveBeenCalledWith(12,1,20,'wechat','同名联系人');
});
it('删除同名会话组提交明确来源快照，不把代表ID当整组删除',async()=>{
 fakeChatStorage();const previous=globalThis.window;globalThis.window={confirm:()=>true} as unknown as Window & typeof globalThis;
 try{const group=namedGroup(11,'同名联系人',[11,12]);const deleteChatConversation=vi.fn();const deleteChatConversationGroup=vi.fn().mockResolvedValue({deleted_sources:2});
 const view=await mountChatCapture({chatConversations:async()=>({total:1,conversations:[group]}),deleteChatConversation,deleteChatConversationGroup});
 view.find('chat-delete-conversation')!.props.onClick();await settle();
 expect(deleteChatConversation).not.toHaveBeenCalled();expect(deleteChatConversationGroup).toHaveBeenCalledWith({confirm:'DELETE',platform:'wechat',group_name:'同名联系人',source_ids:[11,12]});
 }finally{globalThis.window=previous;}
});
it('同名组批量图片删除带组名范围，并保留明确图片列表',async()=>{
 fakeChatStorage();const previous=globalThis.window;globalThis.window={confirm:()=>true} as unknown as Window & typeof globalThis;
 try{const group=namedGroup(11,'同名联系人',[11,12]);const deleteChatImages=vi.fn().mockResolvedValue({deleted_images:2});
 const view=await mountChatCapture({chatConversations:async()=>({total:1,conversations:[group]}),chatMessages:async()=>({total:2,messages:[{...screenshot(8),conversation_id:11},{...screenshot(9),conversation_id:12}]}),deleteChatImages});
 view.find('chat-select-page')!.props.onClick();await settle();view.find('chat-delete-selected')!.props.onClick();await settle();
 expect(deleteChatImages).toHaveBeenCalledWith({confirm:'DELETE',conversation_id:11,platform:'wechat',group_name:'同名联系人',images:[{message_id:'message-8',asset_id:8},{message_id:'message-9',asset_id:9}]});
 }finally{globalThis.window=previous;}
});
it('同名组不在首页时按组名精确恢复，不回退到代表来源标签',async()=>{
 fakeChatStorage({'chat-capture-selection:user-a':JSON.stringify({platform:'wechat',conversations:{wechat:11},groups:{wechat:'同名联系人'}})});
 const group=namedGroup(12,'同名联系人',[12,13]);const chatConversationGroup=vi.fn().mockResolvedValue({conversation:group});
 const view=await mountChatCapture({chatConversations:async()=>({total:101,conversations:[namedGroup(1,'首项',[1])]}),chatConversationGroup});
 expect(chatConversationGroup).toHaveBeenCalledWith('wechat','同名联系人');expect(view.find('chat-conversation-12')!.props.class).toContain('selected');
});
it('同名组逐来源合并只提交该图真实来源，并保存目标组名',async()=>{
 fakeChatStorage();const previous=globalThis.window;globalThis.window={confirm:()=>true} as unknown as Window & typeof globalThis;
 try{const group=namedGroup(11,'同名联系人',[11,12]),target=namedGroup(20,'目标联系人',[20,21]);const mergeChatConversation=vi.fn().mockResolvedValue({target_id:20});
 const view=await mountChatCapture({chatConversations:async()=>({total:2,conversations:[group,target]}),chatMessages:async()=>({total:1,messages:[{...screenshot(8),conversation_id:12}]}),resolveChatConversation:async()=>({conversation:{...rememberedChat(12),display_name:'同名联系人',is_pending_source:false}}),mergeChatConversation});
 view.find('chat-confirm-source-message-8')!.props.onClick();await settle();expect(view.find('chat-merge-target-11')).toBeUndefined();
 view.find('chat-merge-target-20')!.props.onClick();await settle();expect(mergeChatConversation).toHaveBeenCalledWith(12,20);
 expect(JSON.parse(localStorage.getItem('chat-capture-selection:user-a')!).groups.wechat).toBe('目标联系人');
 }finally{globalThis.window=previous;}
});
it('高置信度但仍占位的待确认来源允许确认且面板不显示随机后缀',async()=>{
 fakeChatStorage();const bucket={...rememberedChat(-1),display_name:'待确认会话',is_pending_group:true};
 const view=await mountChatCapture({chatConversations:async()=>({total:2,conversations:[bucket,namedGroup(20,'目标',[20])]}),chatMessages:async()=>({total:1,messages:[{...screenshot(8),conversation_id:81}]}),resolveChatConversation:async()=>({conversation:{...rememberedChat(81),display_name:'待确认会话-SECRET_SUFFIX',identity_confidence:.85,is_pending_source:true}})});
 view.find('chat-confirm-source-message-8')!.props.onClick();await settle();expect(view.find('chat-merge-target-20')).toBeDefined();expect(view.text()).not.toContain('SECRET_SUFFIX');
});
it('首页之外的同名组删图后仍保留选中和组名，不残留无归属消息面板',async()=>{
 fakeChatStorage({'chat-capture-selection:user-a':JSON.stringify({platform:'wechat',conversations:{wechat:11},groups:{wechat:'同名联系人'}})});
 const previous=globalThis.window;globalThis.window={confirm:()=>true} as unknown as Window & typeof globalThis;
 try{const group=namedGroup(11,'同名联系人',[11,12]);const chatConversationGroup=vi.fn().mockResolvedValue({conversation:group});
 const view=await mountChatCapture({chatConversations:async()=>({total:101,conversations:[namedGroup(1,'首项',[1])]}),chatConversationGroup,chatMessages:async()=>({total:1,messages:[{...screenshot(8),conversation_id:12}]}),deleteChatImages:async()=>({deleted_images:1})});
 view.find('chat-select-page')!.props.onClick();await settle();view.find('chat-delete-selected')!.props.onClick();await settle();
 expect(view.find('chat-conversation-11')?.props.class).toContain('selected');expect(chatConversationGroup).toHaveBeenCalledTimes(2);expect(view.text()).toContain('同名联系人');
 }finally{globalThis.window=previous;}
});
it('名称组快照只有单来源但消息读到新来源时，仍提供逐来源归属且禁用整组合并',async()=>{
 fakeChatStorage();const group=namedGroup(11,'同名联系人',[11]);
 const view=await mountChatCapture({chatConversations:async()=>({total:1,conversations:[group]}),chatMessages:async()=>({total:1,messages:[{...screenshot(8),conversation_id:12}]})});
 expect(view.find('chat-confirm-source-message-8')).toBeDefined();expect(view.find('chat-open-merge')!.props.disabled).toBe(true);
});
it('图库分页提供数字页码、首末页与跳页，五列布局和归属按钮具有专用样式',async()=>{
 fakeChatStorage();const chatMessages=vi.fn(async(_id,page,size)=>({total:301,messages:[screenshot(page)]}));
 const view=await mountChatCapture({chatMessages});expect(view.find('chat-page-first')).toBeDefined();expect(view.find('chat-page-2')).toBeDefined();
 view.find('chat-page-2')!.props.onClick();await settle();expect(chatMessages).toHaveBeenLastCalledWith(1,2,20);
 view.find('chat-page-last')!.props.onClick();await settle();expect(chatMessages).toHaveBeenLastCalledWith(1,16,20);expect(view.find('chat-page-last')!.props.disabled).toBe(true);
 view.find('chat-page-jump-input')!.props.onInput({target:{value:'5'}});await settle();view.find('chat-page-jump')!.props.onClick();await settle();expect(chatMessages).toHaveBeenLastCalledWith(1,5,20);
 view.find('chat-page-first')!.props.onClick();await settle();expect(chatMessages).toHaveBeenLastCalledWith(1,1,20);
 expect(view.source).toContain('repeat(5, minmax(0, 1fr))');expect(view.find('chat-open-merge')!.props.class).toContain('capture-action');expect(view.source).toContain('.capture-action');
});
it('旧错字页面组刷新服从后端明确已归类结果，不再因低置信度退回待确认',async()=>{
 fakeChatStorage({'chat-capture-selection:user-a':JSON.stringify({platform:'wechat',conversations:{wechat:12},groups:{wechat:'朋友屠'}})});
 const pageGroup=namedGroup(11,'朋友圈',[11,12]),pending={...rememberedChat(-1),is_pending_group:true,display_name:'待确认会话'};
 const view=await mountChatCapture({chatConversations:async()=>({total:2,conversations:[pending,pageGroup]}),chatConversationGroup:async()=>({conversation:null}),resolveChatConversation:async()=>({conversation:{...rememberedChat(12),display_name:'朋友圈',identity_confidence:.4,external_key:'screenshot-v2:legacy',is_pending_source:false}})});
 expect(view.find('chat-conversation-11')!.props.class).toContain('selected');expect(view.find('chat-conversation--1')!.props.class).not.toContain('selected');
});

it('截断来源不在首页时按ID恢复，不再按相同简称查组',async()=>{
 fakeChatStorage({'chat-capture-selection:user-a':JSON.stringify({platform:'wechat',conversations:{wechat:81}})});
 const partial={...rememberedChat(81),external_key:'screenshot-v2:truncated:11111111-1111-1111-1111-111111111111',display_name:'测试…店（名称被截断）',identity_confidence:.55,is_pending_source:false};
 const chatConversationGroup=vi.fn().mockResolvedValue({conversation:null});
 const view=await mountChatCapture({chatConversations:async()=>({total:1,conversations:[namedGroup(1,'其他群',[1])]}),resolveChatConversation:async()=>({conversation:partial}),chatConversationGroup});
 expect(chatConversationGroup).not.toHaveBeenCalled();
 expect(view.find('chat-conversation-81')!.props.class).toContain('selected');
});

it('截断群名以待确认开头也保留可见名称和截断标注',async()=>{
 fakeChatStorage();const name='待确认订单…门店（名称被截断）';
 const partial={...rememberedChat(81),external_key:'screenshot-v2:truncated:11111111-1111-1111-1111-111111111111',display_name:name,identity_confidence:.55,is_name_group:false,group_name:null};
 const view=await mountChatCapture({chatConversations:async()=>({total:1,conversations:[partial]})});
 expect(view.text()).toContain(name);
 const previous=globalThis.window,confirm=vi.fn().mockReturnValue(false);
 globalThis.window={confirm} as unknown as Window & typeof globalThis;
 try { view.find('chat-delete-conversation')!.props.onClick();await settle();expect(confirm.mock.calls[0][0]).toContain(name); }
 finally {globalThis.window=previous;}
});


it('会话批量删除默认不选中，全选当前列表排除待确认桶，取消不会请求', async () => {
 fakeChatStorage(); const previous=globalThis.window; const confirm=vi.fn().mockReturnValue(false);
 globalThis.window={confirm} as unknown as Window & typeof globalThis;
 try {
  const deleteChatConversations=vi.fn();
  const view=await mountChatCapture({chatConversations:async()=>({total:3,conversations:[namedGroup(11,'甲',[11,12]),rememberedChat(20),{...rememberedChat(-1),is_pending_group:true}]}),deleteChatConversations});
  expect(view.find('chat-delete-selected-conversations')?.props.disabled).toBe(true);
  expect(view.find('chat-select-conversation--1')?.props.disabled).toBe(true);
  view.find('chat-select-listed-conversations')!.props.onClick(); await settle();
  view.find('chat-delete-selected-conversations')!.props.onClick(); await settle();
  expect(confirm).toHaveBeenCalledOnce(); expect(confirm.mock.calls[0][0]).toContain('2 个会话');
  expect(deleteChatConversations).not.toHaveBeenCalled();
 } finally { globalThis.window=previous; }
});
it('批量删除只发送勾选会话及同名组明确快照，成功后保留未删除的当前会话', async () => {
 fakeChatStorage(); const previous=globalThis.window; globalThis.window={confirm:()=>true} as unknown as Window & typeof globalThis;
 try {
  const group=namedGroup(11,'甲',[11,12]),other=rememberedChat(20); let deleted=false;
  const deleteChatConversations=vi.fn(async()=>{deleted=true;return{deleted_conversations:1,deleted_sources:2,deleted_messages:2,files_pending:true};});
  const view=await mountChatCapture({chatConversations:async()=>({total:deleted?1:2,conversations:deleted?[other]:[other,group]}),deleteChatConversations});
  view.find('chat-select-conversation-11')!.props.onChange({target:{checked:true}}); await settle();
  view.find('chat-delete-selected-conversations')!.props.onClick(); await settle();
  expect(deleteChatConversations).toHaveBeenCalledExactlyOnceWith({confirm:'DELETE',platform:'wechat',conversations:[{group_name:'甲',source_ids:[11,12]}]});
  expect(view.find('chat-conversation-20')?.props.class).toContain('selected');
  expect(view.text()).toContain('已删除 1 个会话');expect(view.text()).toContain('后台重试');
  expect(view.find('chat-delete-selected-conversations')?.props.disabled).toBe(true);
 } finally { globalThis.window=previous; }
});
it('切换App清空批量勾选，切换手机使待确认操作失效', async () => {
 fakeChatStorage();const previous=globalThis.window;let answer!:(v:boolean)=>void;
 globalThis.window={confirm:()=>new Promise<boolean>(resolve=>{answer=resolve;})} as unknown as Window & typeof globalThis;
 try {
  const deleteChatConversations=vi.fn();const view=await mountChatCapture({deleteChatConversations});
  view.find('chat-select-listed-conversations')!.props.onClick();await settle();
  view.find('chat-tab-qq')!.props.onClick();await settle();expect(view.find('chat-delete-selected-conversations')!.props.disabled).toBe(true);
  view.find('chat-select-listed-conversations')!.props.onClick();await settle();
  view.find('chat-delete-selected-conversations')!.props.onClick();await settle();
  view.currentUserId.value='user-b';await settle();answer(true);await settle();
  expect(deleteChatConversations).not.toHaveBeenCalled();
 } finally {globalThis.window=previous;}
});
it('批量删除失败保留勾选且说明整批未删除', async()=>{
 fakeChatStorage();const previous=globalThis.window;globalThis.window={confirm:()=>true} as unknown as Window & typeof globalThis;
 try{
  const view=await mountChatCapture({deleteChatConversations:async()=>{throw Error('来源已变化，整批未删除');}});
  view.find('chat-select-listed-conversations')!.props.onClick();await settle();view.find('chat-delete-selected-conversations')!.props.onClick();await settle();
  expect(view.text()).toContain('来源已变化，整批未删除');expect(view.find('chat-select-conversation-1')!.props.checked).toBe(true);
 }finally{globalThis.window=previous;}
});
it('批量删除请求完成后不污染已切换手机的新页面',async()=>{
 fakeChatStorage();const previous=globalThis.window;globalThis.window={confirm:()=>true} as unknown as Window & typeof globalThis;
 try{
  let finish!:(value:any)=>void;
  const view=await mountChatCapture({deleteChatConversations:()=>new Promise(resolve=>{finish=resolve;})});
  view.find('chat-select-listed-conversations')!.props.onClick();await settle();view.find('chat-delete-selected-conversations')!.props.onClick();await settle();
  view.currentUserId.value='user-b';await settle();finish({deleted_conversations:1,deleted_sources:1,files_pending:false});await settle();
  expect(view.find('chat-conversation-1')).toBeDefined();expect(view.text()).not.toContain('已删除 1 个会话');
 }finally{globalThis.window=previous;}
});


it('清空选择不会改变当前查看的会话，成功删除当前会话后切到剩余项',async()=>{
 fakeChatStorage();const previous=globalThis.window;globalThis.window={confirm:()=>true} as unknown as Window & typeof globalThis;
 try{
  const first=rememberedChat(1),second=rememberedChat(2);let deleted=false;
  const view=await mountChatCapture({chatConversations:async()=>({total:deleted?1:2,conversations:deleted?[second]:[first,second]}),deleteChatConversations:async()=>{deleted=true;return{deleted_conversations:1,files_pending:false};}});
  view.find('chat-select-listed-conversations')!.props.onClick();await settle();view.find('chat-clear-conversation-selection')!.props.onClick();await settle();
  expect(view.find('chat-delete-selected-conversations')!.props.disabled).toBe(true);expect(view.find('chat-conversation-1')!.props.class).toContain('selected');
  view.find('chat-select-conversation-1')!.props.onChange({target:{checked:true}});await settle();view.find('chat-delete-selected-conversations')!.props.onClick();await settle();
  expect(view.find('chat-conversation-1')).toBeUndefined();expect(view.find('chat-conversation-2')!.props.class).toContain('selected');
 }finally{globalThis.window=previous;}
});
it('确认期间手机切走再切回仍使旧批量操作失效',async()=>{
 fakeChatStorage();const previous=globalThis.window;let answer!:(v:boolean)=>void;
 globalThis.window={confirm:()=>new Promise<boolean>(resolve=>{answer=resolve;})} as unknown as Window & typeof globalThis;
 try{
  const deleteChatConversations=vi.fn();const view=await mountChatCapture({deleteChatConversations});
  view.find('chat-select-listed-conversations')!.props.onClick();await settle();view.find('chat-delete-selected-conversations')!.props.onClick();await settle();
  view.currentUserId.value='user-b';view.currentUserId.value='user-a';await settle();answer(true);await settle();
  expect(deleteChatConversations).not.toHaveBeenCalled();
 }finally{globalThis.window=previous;}
});
it('空列表和仅待确认桶不允许全选或批量删除',async()=>{
 fakeChatStorage();for(const conversations of [[],[{...rememberedChat(-1),is_pending_group:true}]]){
  const view=await mountChatCapture({chatConversations:async()=>({total:conversations.length,conversations})});
  expect(view.find('chat-select-listed-conversations')!.props.disabled).toBe(true);expect(view.find('chat-delete-selected-conversations')!.props.disabled).toBe(true);
 }
});
it('全选当前列表包含恢复的首页外会话，确认期间禁止切App和重复提交',async()=>{
 fakeChatStorage({'chat-capture-selection:user-a':JSON.stringify({platform:'wechat',conversations:{wechat:200}})});
 const previous=globalThis.window;let answer!:(v:boolean)=>void;const confirm=vi.fn(()=>new Promise<boolean>(resolve=>{answer=resolve;}));
 globalThis.window={confirm} as unknown as Window & typeof globalThis;
 try{
  const view=await mountChatCapture({resolveChatConversation:async()=>({conversation:rememberedChat(200)})});
  view.find('chat-select-listed-conversations')!.props.onClick();await settle();expect(view.find('chat-select-conversation-200')!.props.checked).toBe(true);
  view.find('chat-delete-selected-conversations')!.props.onClick();await settle();
  expect(confirm.mock.calls[0][0]).toContain('2 个会话');expect(view.find('chat-tab-qq')!.props.disabled).toBe(true);
  view.find('chat-delete-selected-conversations')!.props.onClick();await settle();expect(confirm).toHaveBeenCalledOnce();answer(false);await settle();
 }finally{globalThis.window=previous;}
});

it('会话统计与分组列表总数一致，不使用原始来源数或当前页长度', async () => {
  fakeChatStorage();
  const view = await mountChatCapture({
    chatCaptureOverview: async () => ({ conversation_count: 705, message_count: 900, media_count: 800 }),
    chatConversations: async () => ({ total: 123, conversations: [namedGroup(1, '同名联系人', [1, 2, 3])] }),
  });
  expect(statisticValues(view)).toEqual([123, 900, 800]);
});

it('切换平台后会话统计使用对应列表的分组总数', async () => {
  fakeChatStorage();
  const view = await mountChatCapture({
    chatCaptureOverview: async () => ({ conversation_count: 705, message_count: 0, media_count: 0 }),
    chatConversations: async (_page: number, _size: number, platform: string) => ({
      total: platform === 'wechat' ? 123 : 0, conversations: [],
    }),
  });
  view.find('chat-tab-qq')!.props.onClick(); await settle();
  expect(statisticValues(view)).toEqual([0, 0, 0]);
});

it.each(['single', 'bulk'])('删除会话后立即刷新分组总数（%s）', async mode => {
  fakeChatStorage();
  const previous = globalThis.window;
  globalThis.window = { confirm: () => true } as unknown as Window & typeof globalThis;
  try {
    let deleted = false;
    const remove = async () => { deleted = true; return { deleted_conversations: 1 }; };
    const view = await mountChatCapture({
      chatCaptureOverview: async () => ({ conversation_count: deleted ? 702 : 705, message_count: 8, media_count: 7 }),
      chatConversations: async () => ({ total: deleted ? 0 : 1, conversations: deleted ? [] : [namedGroup(1, '同名联系人', [1, 2, 3])] }),
      deleteChatConversationGroup: remove, deleteChatConversations: remove,
    });
    if (mode === 'bulk') {
      view.find('chat-select-listed-conversations')!.props.onClick(); await settle();
      view.find('chat-delete-selected-conversations')!.props.onClick();
    } else view.find('chat-delete-conversation')!.props.onClick();
    await settle();
    expect(deleted).toBe(true);
    expect(statisticValues(view)).toEqual([0, 8, 7]);
  } finally { globalThis.window = previous; }
});

it('删除最后待确认图片后统计跟随列表移除待确认桶', async () => {
  fakeChatStorage();
  const previous = globalThis.window;
  globalThis.window = { confirm: () => true } as unknown as Window & typeof globalThis;
  try {
    let deleted = false;
    const view = await mountChatCapture({
      chatCaptureOverview: async () => ({ conversation_count: 705, message_count: deleted ? 0 : 1, media_count: deleted ? 0 : 1 }),
      chatConversations: async () => ({ total: deleted ? 0 : 1, conversations: deleted ? [] : [{ ...rememberedChat(-1), is_pending_group: true }] }),
      deleteChatImages: async () => { deleted = true; return { deleted_images: 1 }; },
    });
    view.find('chat-select-page')!.props.onClick(); await settle();
    view.find('chat-delete-selected')!.props.onClick(); await settle();
    expect(deleted).toBe(true);
    expect(statisticValues(view)).toEqual([0, 0, 0]);
  } finally { globalThis.window = previous; }
});

it.each([
  ['title_unreadable', null, '未读到标题'],
  ['title_unconfirmed', '新联系人', '读到标题，尚未确认'],
  ['non_chat_page', '发现', '非聊天页面'],
])('待确认图片显示具体原因与观测标题（%s）', async (reason, observedTitle, label) => {
  fakeChatStorage();
  const view = await mountChatCapture({
    chatConversations: async () => ({ total: 1, conversations: [{ ...rememberedChat(-1), is_pending_group: true }] }),
    chatMessages: async () => ({ total: 1, messages: [{ ...screenshot(8), conversation_id: 12, pending_diagnostic: {
      reason, observed_title: observedTitle, identity_status: 'pending', identity_source: 'on_device_title_ocr', suggested_conversations: [],
    } }] }),
  });
  expect(view.text()).toContain(label);
  expect(view.text()).toContain(observedTitle ? `观测标题：${observedTitle}` : '观测标题：未读到');
  expect(view.find('chat-confirm-source-message-8')).toBeDefined();
});

it('疑似已有会话展示全部精确同名建议，仍由用户逐来源选择确认', async () => {
  fakeChatStorage();
  const mergeChatConversation = vi.fn();
  const list = vi.fn(async () => ({ total: 1, conversations: [{ ...rememberedChat(-1), is_pending_group: true }] }));
  const view = await mountChatCapture({
    chatConversations: list, mergeChatConversation,
    resolveChatConversation: async () => ({ conversation: { ...rememberedChat(12), is_pending_source: true, identity_confidence: .55 } }),
    chatMessages: async () => ({ total: 1, messages: [{ ...screenshot(8), conversation_id: 12, pending_diagnostic: {
      reason: 'possible_existing_conversation', observed_title: '王小明', identity_status: 'pending', identity_source: 'on_device_title_ocr',
      suggested_conversations: [{ id: 20, display_name: '王小明' }, { id: 21, display_name: '王小明' }],
    } }] }),
  });
  expect(view.text()).toContain('疑似已有会话');
  expect(view.text()).toContain('王小明 #20'); expect(view.text()).toContain('王小明 #21');
  expect(view.text()).toContain('仅名称相同，请核对截图后选择归属');
  expect(mergeChatConversation).not.toHaveBeenCalled();
  view.find('chat-confirm-source-message-8')!.props.onClick(); await settle();
  expect(list).toHaveBeenLastCalledWith(1, 20, 'wechat', '王小明', true, true);
  expect(mergeChatConversation).not.toHaveBeenCalled();
});

it('同名建议可逐个选择真实目标ID，取消确认不会合并', async () => {
  fakeChatStorage();
  const previous = globalThis.window, confirm = vi.fn(() => false);
  globalThis.window = { confirm } as unknown as Window & typeof globalThis;
  try {
    const mergeChatConversation = vi.fn();
    const resolve = vi.fn(async (id: number) => ({ conversation: { ...rememberedChat(id), account_key: 'self', display_name: id === 12 ? '待确认会话' : '王小明', is_pending_source: id === 12, identity_confidence: id === 12 ? .55 : .95 } }));
    const view = await mountChatCapture({
      mergeChatConversation, resolveChatConversation: resolve,
      chatConversations: async () => ({ total: 1, conversations: [{ ...rememberedChat(-1), is_pending_group: true }] }),
      chatMessages: async () => ({ total: 1, messages: [{ ...screenshot(8), conversation_id: 12, pending_diagnostic: {
        reason: 'possible_existing_conversation', observed_title: '王小明', suggested_conversations: [{ id: 20, display_name: '王小明' }, { id: 21, display_name: '王小明' }],
      } }] }),
    });
    view.find('chat-suggested-target-message-8-21')!.props.onClick(); await settle();
    expect(resolve).toHaveBeenCalledWith(12); expect(resolve).toHaveBeenCalledWith(21);
    expect(resolve).not.toHaveBeenCalledWith(20);
    expect(confirm).toHaveBeenCalledOnce(); expect(mergeChatConversation).not.toHaveBeenCalled();
  } finally { globalThis.window = previous; }
});

it.each([false, true])('建议归属必须重新校验，确认仅移动当前真实来源（目标变更：%s）', async changed => {
  fakeChatStorage();
  const previous = globalThis.window, confirm = vi.fn(() => true);
  globalThis.window = { confirm } as unknown as Window & typeof globalThis;
  try {
    const mergeChatConversation = vi.fn(async () => ({ target_id: 21 }));
    const resolve = vi.fn(async (id: number) => ({ conversation: {
      ...rememberedChat(id), account_key: id === 21 && changed ? 'changed-account' : 'self',
      display_name: id === 12 ? '待确认会话' : '王小明', is_pending_source: id === 12, identity_confidence: id === 12 ? .55 : .95,
    } }));
    const view = await mountChatCapture({
      mergeChatConversation, resolveChatConversation: resolve,
      chatConversations: async () => ({ total: 1, conversations: [{ ...rememberedChat(-1), is_pending_group: true }] }),
      chatConversationGroup: async () => ({ conversation: namedGroup(21, '王小明', [21]) }),
      chatMessages: async () => ({ total: 1, messages: [{ ...screenshot(8), conversation_id: 12, pending_diagnostic: {
        reason: 'possible_existing_conversation', observed_title: '王小明', suggested_conversations: [{ id: 21, display_name: '王小明' }],
      } }] }),
    });
    view.find('chat-suggested-target-message-8-21')!.props.onClick(); await settle();
    if (changed) {
      expect(confirm).not.toHaveBeenCalled(); expect(mergeChatConversation).not.toHaveBeenCalled();
      expect(view.text()).toContain('来源或建议归属已变化');
    } else expect(mergeChatConversation).toHaveBeenCalledExactlyOnceWith(12, 21);
  } finally { globalThis.window = previous; }
});
