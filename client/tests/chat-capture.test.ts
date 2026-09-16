import { readFileSync } from 'node:fs';
import { afterEach, expect, it } from '../../server/node_modules/vitest/dist/index.js';
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

async function mountChatCapture() {
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
    chatConversations: async () => ({ conversations: [{
      id: 1, platform: 'wechat', account_key: 'self', external_key: 'peer', display_name: '对方',
      conversation_type: 'direct', identity_confidence: 1, message_count: 1,
      first_seen_at: '2026-09-16T00:00:00Z', last_seen_at: '2026-09-16T00:00:00Z', last_message_at: '2026-09-16T00:00:00Z',
    }] }),
    chatMessages: async () => ({ messages: [{
      id: 'message-1', platform: 'wechat', direction: 'incoming', message_type: 'image', sender_key: 'peer', sender_name: '对方',
      text: '聊天截图', displayed_time: null, occurred_at: '2026-09-16T00:00:00Z', captured_at: '2026-09-16T00:00:00Z',
      sequence_hint: null, metadata: {}, assets: [{ id: 8, sha256: 'a'.repeat(64), mime_type: 'image/png', width: 1200, height: 2664, role: 'content', position: 0, url: '/uploads/chat/screenshot.png' }],
    }] }),
  };
  const module = { exports: {} as { default: Vue.Component } };
  const require = (name: string) => {
    if (name === 'vue') return Vue;
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
  return { source, find };
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
