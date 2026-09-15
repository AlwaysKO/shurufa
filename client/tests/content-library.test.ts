import { readFileSync } from 'node:fs';
import { expect, it, vi, afterEach } from '../../server/node_modules/vitest/dist/index.js';
import { parse, compileScript } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';

type Node = { tag: string; text: string; children: Node[]; parent: Node | null; props: Record<string, any>; value: string; files?: File[]; addEventListener: () => void; removeEventListener: () => void; click: () => void; tagName: string; getRootNode: () => object };
const mounted: Vue.App[] = [];
afterEach(() => { mounted.splice(0).forEach(app => app.unmount()); vi.unstubAllGlobals(); });
async function settle() { for (let i = 0; i < 12; i++) { await Promise.resolve(); await Vue.nextTick(); } }
async function mount(name: string, api: Record<string, any>) {
  const { descriptor } = parse(readFileSync(new URL(`../src/views/${name}.vue`, import.meta.url), 'utf8'));
  const compiled = compileScript(descriptor, { id: name, inlineTemplate: true });
  const code = ts.transpileModule(compiled.content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const node = (tag = '', text = ''): Node => ({ tag, text, children: [], parent: null, props: {}, value: '', addEventListener() {}, removeEventListener() {}, click() {}, getRootNode: () => ({ activeElement: null }), tagName: tag.toUpperCase() });
  function insert(n: Node, p: Node, anchor?: Node | null) { if (n.parent) n.parent.children.splice(n.parent.children.indexOf(n), 1); n.parent = p; const i = anchor ? p.children.indexOf(anchor) : -1; if (i < 0) p.children.push(n); else p.children.splice(i, 0, n); }
  const renderer = Vue.createRenderer<Node, Node>({
    createElement: tag => node(tag), createText: text => node('', text), createComment: text => node('', text),
    setText: (n, text) => { n.text = text; }, setElementText: (n, text) => { n.text = text; n.children = []; },
    parentNode: n => n.parent, nextSibling: n => n.parent?.children[n.parent.children.indexOf(n) + 1] ?? null,
    patchProp: (n, key, _prev, value) => { n.props[key] = value; }, insert,
    remove(n) { if (n.parent) n.parent.children.splice(n.parent.children.indexOf(n), 1); n.parent = null; },
    insertStaticContent(text, p, anchor) { const n = node('static', text); insert(n, p, anchor); return [n, n]; },
  });
  vi.stubGlobal('document', { activeElement: null });
  vi.stubGlobal('Document', class {}); vi.stubGlobal('ShadowRoot', class {});
  const presets = compiled.content.includes('../data/phrasePresets') ? await import('../src/data/phrasePresets') : {};
  const module = { exports: {} as { default: Vue.Component } };
  const require = (id: string) => {
    if (id === 'vue') return Vue;
    if (id === '../api') return { api, scopedAssetUrl: (url: string) => url };
    if (id === '../data/phrasePresets') return presets;
    if (id.endsWith('.css')) return {};
    throw new Error(`Unexpected import: ${id}`);
  };
  new Function('require', 'module', 'exports', code)(require, module, module.exports);
  const root = node('root'); const app = renderer.createApp(module.exports.default); app.mount(root); mounted.push(app); await settle();
  const all = (n: Node): Node[] => [n, ...n.children.flatMap(all)];
  const find = (id: string) => all(root).find(n => n.props['data-testid'] === id);
  return { root, find, all: () => all(root), text: () => all(root).map(n => n.text).join(' ') };
}

it('预置短句独立展示，只有点击加入才写个人库，重复项禁用', async () => {
  const add = vi.fn(async (content: string) => ({ id: 1, content, useCount: 0 }));
  const rows: any[] = [];
  const view = await mount('Phrases', { userPhrases: vi.fn(async () => ({ total: rows.length, phrases: rows })), addUserPhrase: add });
  expect(add).not.toHaveBeenCalled();
  expect(view.find('preset-tab')).toBeDefined();
  view.find('preset-tab')!.props.onClick(); await settle();
  const button = view.all().find(n => n.props['data-testid'] === 'add-preset'); expect(button).toBeDefined();
  await button!.props.onClick(); await settle();
  expect(add).toHaveBeenCalledTimes(1); expect(typeof add.mock.calls[0][0]).toBe('string');
  expect(view.all().filter(n => n.props['data-testid'] === 'add-preset')[0].props.disabled).toBe(true);
});
it('编辑常用语保存失败保持输入内容，成功才退出编辑', async () => {
  const view = await mount('Phrases', { userPhrases: vi.fn(async () => ({ total: 1, phrases: [{ id: 1, content: '原句', useCount: 2 }] })), updateUserPhrase: vi.fn().mockRejectedValue(new Error('离线')) });
  expect(view.find('edit-phrase-1')).toBeDefined();
  view.find('edit-phrase-1')!.props.onClick(); await settle();
  view.find('edit-phrase-input')!.props['onUpdate:modelValue']('新句');
  await view.find('save-phrase')!.props.onClick(); await settle();
  expect(view.find('edit-phrase-input')).toBeDefined(); expect(view.text()).toContain('离线');
});
const library = { systemCount: 1, personalCount: 0, warnings: [], groups: [
  { keyword: '你好', category: '问候', planned: true, custom: false, assets: [{ id: 'hello', source: 'system', url: '/uploads/expression/hello.gif', format: 'gif', keywords: ['你好'], width: 240, height: 240, useCount: null }] },
  { keyword: '晚安', category: '问候', planned: true, custom: false, assets: [] },
] };
it('完整词表显示空组，新增词不上传文件，切换关键词可查看已有表情', async () => {
  const add = vi.fn().mockResolvedValue({ keyword: '测试' }); const upload = vi.fn();
  const view = await mount('Stickers', { stickerLibrary: vi.fn().mockResolvedValue(library), addStickerKeyword: add, uploadSticker: upload });
  expect(view.find('keyword-晚安')).toBeDefined();
  view.find('keyword-晚安')!.props.onClick(); await settle(); expect(view.find('empty-keyword')).toBeDefined();
  view.find('new-keyword')!.props['onUpdate:modelValue']('测试');
  await view.find('add-keyword')!.props.onClick(); await settle();
  expect(add).toHaveBeenCalledWith('测试'); expect(upload).not.toHaveBeenCalled();
});
it('组内选择文件立即上传且自动携带该词，公共图片没有删除按钮', async () => {
  const upload = vi.fn().mockResolvedValue({});
  const view = await mount('Stickers', { stickerLibrary: vi.fn().mockResolvedValue(library), uploadSticker: upload });
  expect(view.find('delete-sticker-hello')).toBeUndefined();
  expect(view.find('keyword-晚安')).toBeDefined(); view.find('keyword-晚安')!.props.onClick(); await settle();
  vi.stubGlobal('Image', class { naturalWidth = 240; naturalHeight = 240; onload: (() => void) | null = null; set src(_s: string) { this.onload?.(); } });
  const file = new File(['GIF89a'], 'hello.gif', { type: 'image/gif' });
  const input = { files: [file], value: 'hello.gif' };
  await view.find('group-upload-input')!.props.onChange({ target: input }); await settle();
  expect(upload).toHaveBeenCalledWith(expect.objectContaining({ filename: 'hello.gif', keywords: '晚安', width: 240 }));
});
it('词库加载失败显示重试，不伪装成空表情库', async () => {
  const view = await mount('Stickers', { stickerLibrary: vi.fn().mockRejectedValue(new Error('离线')) });
  expect(view.text()).toContain('离线'); expect(view.find('retry-library')).toBeDefined();
});
it('非法文件和超限文件不上传，上传失败保留当前关键词并提示错误', async () => {
  const upload = vi.fn().mockRejectedValue(new Error('网络中断'));
  const view = await mount('Stickers', { stickerLibrary: vi.fn().mockResolvedValue(library), uploadSticker: upload });
  view.find('keyword-晚安')!.props.onClick(); await settle();
  const onChange = view.find('group-upload-input')!.props.onChange;
  for (const file of [new File(['bad'], 'bad.txt'), new File([], 'empty.gif'), { name: 'huge.gif', size: 5 * 1024 * 1024 + 1 }]) {
    await onChange({ target: { files: [file], value: '' } }); await settle();
  }
  expect(upload).not.toHaveBeenCalled();
  vi.stubGlobal('Image', class { naturalWidth = 240; naturalHeight = 240; onload: (() => void) | null = null; set src(_s: string) { this.onload?.(); } });
  await onChange({ target: { files: [new File(['GIF89a'], 'valid.gif')], value: 'valid.gif' } }); await settle();
  expect(view.text()).toContain('网络中断'); expect(view.find('keyword-晚安')!.props['aria-current']).toBe('true');
});
it('搜索和待补图筛选不把系统图片串到空关键词', async () => {
  const view = await mount('Stickers', { stickerLibrary: vi.fn().mockResolvedValue(library) });
  const search = view.all().find(n => n.props['aria-label'] === '搜索关键词')!;
  search.props['onUpdate:modelValue']('晚安'); await settle();
  expect(view.find('keyword-你好')).toBeUndefined(); expect(view.find('keyword-晚安')).toBeDefined();
  expect(view.find('empty-keyword')).toBeDefined();
  search.props['onUpdate:modelValue'](''); await settle();
  const emptyFilter = view.all().find(n => n.tag === 'button' && n.text === '待补图')!;
  emptyFilter.props.onClick(); await settle();
  expect(view.find('keyword-你好')).toBeUndefined(); expect(view.find('keyword-晚安')).toBeDefined();
});
