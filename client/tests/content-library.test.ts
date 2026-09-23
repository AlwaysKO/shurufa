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
    if (id === '../confirmation') return { useConfirmation: () => async (message: string) => Boolean(globalThis.confirm?.(message)) };
    if (id === '../api') return { api, scopedAssetUrl: (url: string) => url };
    if (id === '../data/phrasePresets') return presets;
    if (id.endsWith('.css')) return {};
    throw new Error(`Unexpected import: ${id}`);
  };
  new Function('require', 'module', 'exports', code)(require, module, module.exports);
  const root = node('root'); const app = renderer.createApp(module.exports.default); app.mount(root); mounted.push(app); await settle();
  const all = (n: Node): Node[] => [n, ...n.children.flatMap(all)];
  const find = (id: string) => all(root).find(n => n.props['data-testid'] === id);
  return { unmount: () => app.unmount(), root, find, all: () => all(root), text: () => all(root).map(n => n.text).join(' ') };
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
  { keyword: '你好', aliases: ['你好'], confirmedAliases: [], category: '问候', planned: true, custom: false, assets: [{ id: 'hello', source: 'system', url: '/uploads/expression/hello.gif', format: 'gif', keywords: ['你好'], width: 240, height: 240, useCount: null }] },
  { keyword: '晚安', aliases: ['晚安'], confirmedAliases: [], category: '问候', planned: true, custom: false, assets: [] },
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
it('原组说法转给其他组后，上传完成仍打开精确原组而不跳到说法所在组', async () => {
  const data = structuredClone(library);
  data.groups[0]!.aliases = ['你好', '晚安'];
  data.groups[1]!.aliases = [];
  const upload = vi.fn().mockResolvedValue({ id: 1 });
  const view = await mount('Stickers', { stickerLibrary: vi.fn().mockResolvedValue(data), uploadSticker: upload });
  view.find('keyword-晚安')!.props.onClick(); await settle();
  vi.stubGlobal('Image', class { naturalWidth = 240; naturalHeight = 240; onload: (() => void) | null = null; set src(_s: string) { this.onload?.(); } });
  await view.find('group-upload-input')!.props.onChange({ target: { files: [new File(['GIF89a'], 'night.gif')], value: 'night.gif' } });
  await settle();
  expect(upload).toHaveBeenCalledWith(expect.objectContaining({ group_keyword: '晚安' }));
  expect(view.find('keyword-晚安')!.props['aria-current']).toBe('true');
});
it('删除关键词先确认组名及图片范围，取消不请求，确认后刷新移除该组', async () => {
  const data = structuredClone(library);
  const remove = vi.fn(async () => { data.groups = data.groups.filter(g => g.keyword !== '你好'); return {keyword:'你好',files_pending:false}; });
  const confirm = vi.fn().mockReturnValueOnce(false).mockReturnValueOnce(true);
  vi.stubGlobal('confirm',confirm);
  const view = await mount('Stickers',{stickerLibrary:vi.fn(async()=>structuredClone(data)),deleteStickerGroup:remove});
  view.find('keyword-你好')!.props.onClick(); await settle();
  expect(view.find('delete-keyword-group')).toBeDefined();
  await view.find('delete-keyword-group')!.props.onClick(); await settle();
  expect(remove).not.toHaveBeenCalled();
  expect(confirm.mock.calls[0][0]).toContain('你好'); expect(confirm.mock.calls[0][0]).toContain('1 张图片');
  await view.find('delete-keyword-group')!.props.onClick(); await settle();
  expect(remove).toHaveBeenCalledWith('你好',{confirm:'DELETE',aliases:['你好'],assetKeys:['system:hello']});
  expect(view.find('keyword-你好')).toBeUndefined(); expect(view.find('keyword-晚安')).toBeDefined();
  expect(view.text()).toContain('已删除');
});
it('关键词删除失败保留列表并显示原因，不能伪报成功', async () => {
  vi.stubGlobal('confirm',()=>true);
  const view = await mount('Stickers',{stickerLibrary:vi.fn().mockResolvedValue(structuredClone(library)),deleteStickerGroup:vi.fn().mockRejectedValue(new Error('组内图片已变化'))});
  view.find('keyword-你好')!.props.onClick(); await settle();
  expect(view.find('delete-keyword-group')).toBeDefined();
  await view.find('delete-keyword-group')!.props.onClick(); await settle();
  expect(view.find('keyword-你好')).toBeDefined();
  expect(view.text()).toContain('组内图片已变化'); expect(view.text()).not.toContain('已删除');
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
function pagedLibrary(count = 25) {
  return { systemCount: 0, personalCount: 0, warnings: [], groups: Array.from({ length: count }, (_, i) => ({
    keyword: `词${String(i + 1).padStart(2, '0')}`, aliases: [`词${String(i + 1).padStart(2, '0')}`], confirmedAliases: [],
    category: '测试', planned: false, custom: true, assets: [],
  })) };
}
it('搜索无结果可直接建组并选中，不覆盖上方新增草稿', async () => {
  const data = structuredClone(library);
  const add = vi.fn(async (keyword: string) => {
    data.groups.push({ keyword, aliases: [keyword], confirmedAliases: [], category: '自定义', planned: false, custom: true, assets: [] });
    return { keyword };
  });
  const view = await mount('Stickers', { stickerLibrary: vi.fn(async () => structuredClone(data)), addStickerKeyword: add });
  view.find('new-keyword')!.props['onUpdate:modelValue']('尚未提交的草稿');
  const search = view.all().find(n => n.props['aria-label'] === '搜索关键词')!;
  search.props['onUpdate:modelValue']('  眼神  '); await settle();
  expect(view.find('add-search-keyword')).toBeDefined();
  expect(view.text()).toContain('新增词组「眼神」');
  await view.find('add-search-keyword')!.props.onClick(); await settle();
  expect(add).toHaveBeenCalledExactlyOnceWith('眼神');
  expect(view.find('keyword-眼神')!.props['aria-current']).toBe('true');
  expect(view.find('empty-keyword')).toBeDefined();
  expect(search.value).toBe('');
  expect(view.find('new-keyword')!.value).toBe('尚未提交的草稿');
});
it('搜索词是被筛选隐藏的已有说法时直接打开所属组', async () => {
  const data = structuredClone(library); data.groups.find(g => g.keyword === '你好')!.aliases.push('您好');
  const add = vi.fn();
  const view = await mount('Stickers', { stickerLibrary: vi.fn().mockResolvedValue(data), addStickerKeyword: add });
  view.all().find(n => n.tag === 'button' && n.text === '待补图')!.props.onClick();
  view.all().find(n => n.props['aria-label'] === '搜索关键词')!.props['onUpdate:modelValue']('您好'); await settle();
  expect(view.find('add-search-keyword')).toBeDefined();
  expect(view.text()).toContain('打开已有词组');
  await view.find('add-search-keyword')!.props.onClick(); await settle();
  expect(add).not.toHaveBeenCalled();
  expect(view.find('keyword-你好')!.props['aria-current']).toBe('true');
});
it('搜索新增失败保留原词可重试，空白搜索不显示新增入口', async () => {
  const add = vi.fn().mockRejectedValue(new Error('网络中断'));
  const view = await mount('Stickers', { stickerLibrary: vi.fn().mockResolvedValue({ ...library, groups: [] }), addStickerKeyword: add });
  const search = view.all().find(n => n.props['aria-label'] === '搜索关键词')!;
  search.props['onUpdate:modelValue']('   '); await settle();
  expect(view.find('add-search-keyword')).toBeUndefined();
  search.props['onUpdate:modelValue']('眼神'); await settle();
  expect(view.find('add-search-keyword')).toBeDefined();
  await view.find('add-search-keyword')!.props.onClick(); await settle();
  expect(view.text()).toContain('新增失败：网络中断');
  expect(search.value).toBe('眼神');
  expect(view.find('add-search-keyword')!.props.disabled).toBe(false);
  for (const invalid of ['眼神,嘚瑟', '词'.repeat(101)]) {
    search.props['onUpdate:modelValue'](invalid); await settle();
    await view.find('add-search-keyword')!.props.onClick(); await settle();
    expect(view.text()).toContain('请输入单个关键词');
  }
  expect(add).toHaveBeenCalledTimes(1);
});
it('每页10组，可前后翻页、首末页与跳页，非法跳页保持当前页', async () => {
  const view = await mount('Stickers', { stickerLibrary: vi.fn().mockResolvedValue(pagedLibrary()) });
  const keywordButtons = () => view.all().filter(n => n.tag === 'button' && n.props['data-testid']?.startsWith('keyword-'));
  expect(keywordButtons()).toHaveLength(10);
  expect(view.find('first-keyword-page')!.props.disabled).toBe(true);
  view.find('next-keyword-page')!.props.onClick(); await settle();
  expect(view.find('keyword-词11')).toBeDefined(); expect(view.find('keyword-词01')).toBeUndefined();
  expect(view.find('keyword-词11')!.props['aria-current']).toBe('true');
  view.find('last-keyword-page')!.props.onClick(); await settle();
  expect(keywordButtons()).toHaveLength(5); expect(view.find('next-keyword-page')!.props.disabled).toBe(true);
  view.find('previous-keyword-page')!.props.onClick(); await settle(); expect(view.find('keyword-词11')).toBeDefined();
  view.find('first-keyword-page')!.props.onClick(); await settle(); expect(view.find('keyword-词01')).toBeDefined();
  view.find('keyword-page-input')!.props['onUpdate:modelValue']('2');
  view.find('jump-keyword-page')!.props.onClick(); await settle(); expect(view.find('keyword-词11')).toBeDefined();
  for (const invalid of ['', '0', '-1', '1.5', 'abc', '4']) {
    view.find('keyword-page-input')!.props['onUpdate:modelValue'](invalid);
    view.find('jump-keyword-page')!.props.onClick(); await settle();
    expect(view.find('keyword-词11')).toBeDefined(); expect(view.find('keyword-page-error')).toBeDefined();
  }
});
it('搜索及筛选重置到首页，零结果禁用全部分页按钮', async () => {
  const view = await mount('Stickers', { stickerLibrary: vi.fn().mockResolvedValue(pagedLibrary()) });
  expect(view.find('last-keyword-page')).toBeDefined(); view.find('last-keyword-page')!.props.onClick(); await settle();
  const search = view.all().find(n => n.props['aria-label'] === '搜索关键词')!;
  search.props['onUpdate:modelValue']('词01'); await settle();
  expect(view.find('keyword-词01')).toBeDefined(); expect(view.find('first-keyword-page')!.props.disabled).toBe(true);
  search.props['onUpdate:modelValue']('不存在'); await settle();
  for (const id of ['first-keyword-page', 'previous-keyword-page', 'next-keyword-page', 'last-keyword-page', 'jump-keyword-page']) expect(view.find(id)!.props.disabled).toBe(true);
  search.props['onUpdate:modelValue'](''); await settle();
  view.find('last-keyword-page')!.props.onClick(); await settle();
  view.all().find(n => n.tag === 'button' && n.text === '待补图')!.props.onClick(); await settle();
  expect(view.find('keyword-词01')).toBeDefined();
});
it('搜索别名定位语义组，新增已有别名跳到所在页而不重复建词，上传携带整组标签', async () => {
  const data = pagedLibrary(10);
  const aliases = ['打闹', '打你', '揍你', '扁你', '我来打你了', '过来打我啊'];
  data.groups.push({ keyword: '打闹', aliases, confirmedAliases: ['扁你', '我来打你了', '过来打我啊'], category: '动作', planned: true, custom: false, assets: [] });
  const add = vi.fn(); const upload = vi.fn().mockResolvedValue({});
  const view = await mount('Stickers', { stickerLibrary: vi.fn().mockResolvedValue(data), addStickerKeyword: add, uploadSticker: upload });
  view.find('new-keyword')!.props['onUpdate:modelValue']('扁你');
  await view.find('add-keyword')!.props.onClick(); await settle();
  expect(add).not.toHaveBeenCalled(); expect(view.find('keyword-打闹')).toBeDefined();
  expect(view.find('group-aliases')).toBeDefined(); expect(view.text()).toContain('过来打我啊');
  const search = view.all().find(n => n.props['aria-label'] === '搜索关键词')!;
  search.props['onUpdate:modelValue']('我来打你了'); await settle(); expect(view.find('keyword-打闹')).toBeDefined();
  vi.stubGlobal('Image', class { naturalWidth = 240; naturalHeight = 240; onload: (() => void) | null = null; set src(_s: string) { this.onload?.(); } });
  // 打开选择器时捕获组信息，随后翻到别页也不能把图片传错组。
  view.find('group-upload-button')!.props.onClick();
  search.props['onUpdate:modelValue'](''); await settle();
  view.find('first-keyword-page')!.props.onClick(); await settle();
  await view.find('group-upload-input')!.props.onChange({ target: { files: [new File(['GIF89a'], 'group.gif')], value: 'group.gif' } });
  await settle(); expect(upload).toHaveBeenCalledWith(expect.objectContaining({ keywords: aliases.join(',') }));
  expect(view.find('keyword-打闹')!.props['aria-current']).toBe('true');
});
it('有表情筛选的尾页数量缩减后，刷新自动收敛到有效页', async () => {
  const base = pagedLibrary();
  const full = { ...base, groups: base.groups.map((g, i) => ({ ...g, assets: [
    { id: i + 1, source: 'personal', keywords: g.aliases, url: `/uploads/stickers/${i + 1}.gif`, format: 'gif', width: 240, height: 240, useCount: 0 },
  ] })) };
  const reduced = { ...full, groups: full.groups.map((g, i) => i < 20 ? g : { ...g, assets: [] }) };
  const view = await mount('Stickers', { stickerLibrary: vi.fn().mockResolvedValueOnce(full).mockResolvedValueOnce(reduced), deleteSticker: vi.fn().mockResolvedValue({}) });
  view.all().find(n => n.tag === 'button' && n.text === '有表情')!.props.onClick(); await settle();
  view.find('last-keyword-page')!.props.onClick(); await settle();
  view.find('keyword-词25')!.props.onClick(); await settle();
  vi.stubGlobal('confirm', () => true);
  await view.find('delete-sticker-25')!.props.onClick(); await settle();
  expect(view.find('keyword-词11')).toBeDefined(); expect(view.find('keyword-词25')).toBeUndefined();
  expect(view.find('last-keyword-page')!.props.disabled).toBe(true);
});
it('新增成功但刷新失败时保留新分组，重复输入不重复提交', async () => {
  const add = vi.fn().mockResolvedValue({ keyword: '新增成功' });
  const view = await mount('Stickers', { stickerLibrary: vi.fn().mockResolvedValueOnce(library).mockRejectedValueOnce(new Error('刷新离线')), addStickerKeyword: add });
  view.find('new-keyword')!.props['onUpdate:modelValue']('新增成功');
  await view.find('add-keyword')!.props.onClick(); await settle();
  expect(view.find('keyword-新增成功')).toBeDefined(); expect(view.text()).toContain('刷新离线');
  view.find('new-keyword')!.props['onUpdate:modelValue']('新增成功');
  await view.find('add-keyword')!.props.onClick(); await settle();
  expect(add).toHaveBeenCalledTimes(1);
});

const synthesisAsset = { id: 'blank-test', name: '测试底图', source: 'personal', deletable: true, url: '/uploads/synthesis/test.gif', width: 240, height: 240, format: 'gif', sha256: 'abc', textSafeArea: { x: 6, y: 190, width: 228, height: 44 } };
it('AI底图库独立加载，系统只读，个人删除须确认', async () => {
  const remove = vi.fn().mockResolvedValue({ ok: true });
  const view = await mount('SynthesisLibrary', { synthesisLibrary: vi.fn().mockResolvedValue({ assets: [synthesisAsset, { ...synthesisAsset, id: 'system', source: 'system', deletable: false }], total: 2 }), deleteSynthesisAsset: remove });
  expect(view.find('delete-synthesis-system')).toBeUndefined();
  vi.stubGlobal('confirm', () => false); await view.find('delete-synthesis-blank-test')!.props.onClick(); expect(remove).not.toHaveBeenCalled();
  vi.stubGlobal('confirm', () => true); await view.find('delete-synthesis-blank-test')!.props.onClick(); expect(remove).toHaveBeenCalledWith('blank-test');
});
it('选择GIF直接入库，无确认勾选或来源必填，文件名作为名称', async () => {
 const upload=vi.fn().mockResolvedValue({asset:synthesisAsset,duplicate:true});
 const view=await mount('SynthesisLibrary',{synthesisLibrary:vi.fn().mockResolvedValue({assets:[]}),uploadSynthesisAsset:upload});
 expect(view.find('synthesis-no-text')).toBeUndefined();expect(view.find('synthesis-rights')).toBeUndefined();
 await view.find('synthesis-file')!.props.onChange({target:{files:[new File(['GIF89a'],'眼神.gif')],value:''}});await settle();
 expect(upload).toHaveBeenCalledWith(expect.objectContaining({name:'眼神',filename:'眼神.gif'}));
 expect(upload.mock.calls[0][0]).not.toHaveProperty('noTextConfirmed');expect(upload.mock.calls[0][0]).not.toHaveProperty('rightsConfirmed');
 expect(view.text()).toContain('已存在');
});
it('AI底图拒绝非GIF、空文件、超限文件', async () => {
 const upload=vi.fn();const view=await mount('SynthesisLibrary',{synthesisLibrary:vi.fn().mockResolvedValue({assets:[]}),uploadSynthesisAsset:upload});
 for(const file of [new File(['x'],'x.png'),new File([],'x.gif'),{name:'x.gif',size:256001}]){
  await view.find('synthesis-file')!.props.onChange({target:{files:[file],value:''}});await settle();expect(view.text()).toContain('250');
 }
 expect(upload).not.toHaveBeenCalled();
});
it('AI底图库加载失败可重试，自动上传失败保留文件重试', async () => {
 const list=vi.fn().mockRejectedValueOnce(new Error('离线')).mockResolvedValue({assets:[]});
 const upload=vi.fn().mockRejectedValueOnce(new Error('网络中断')).mockResolvedValue({asset:synthesisAsset,duplicate:false});
 const view=await mount('SynthesisLibrary',{synthesisLibrary:list,uploadSynthesisAsset:upload});
 expect(view.text()).toContain('离线');await view.find('retry-synthesis')!.props.onClick();await settle();
 await view.find('synthesis-file')!.props.onChange({target:{files:[new File(['GIF89a'],'保留我.gif')],value:''}});await settle();
 expect(view.text()).toContain('网络中断');expect(view.find('retry-synthesis-upload')).toBeDefined();
 await view.find('retry-synthesis-upload')!.props.onClick();await settle();expect(upload).toHaveBeenCalledTimes(2);
 expect(upload.mock.calls[1][0].name).toBe('保留我');
});
it('切换用户卸载页面后，尚在读文件的自动上传不得写入新用户', async () => {
 const upload=vi.fn();const view=await mount('SynthesisLibrary',{synthesisLibrary:vi.fn().mockResolvedValue({assets:[]}),uploadSynthesisAsset:upload});
 const file=new File(['GIF89a'],'a.gif');let finish!:(value:ArrayBuffer)=>void;
 vi.spyOn(file,'arrayBuffer').mockImplementation(()=>new Promise(resolve=>{finish=resolve}));
 const pending=view.find('synthesis-file')!.props.onChange({target:{files:[file],value:''}});
 view.unmount();finish(new ArrayBuffer(6));await pending;expect(upload).not.toHaveBeenCalled();
});

function editableLibrary() {
  return { systemCount:1,personalCount:1,warnings:[],groups:[
    {keyword:'赞',aliases:['赞','给你点赞'],confirmedAliases:[],category:'其他',planned:false,custom:false,assets:[
      {id:7,source:'personal',url:'/mine.gif',format:'gif',keywords:['赞'],width:1,height:1,useCount:0},
      {id:'praise',source:'system',url:'/praise.gif',format:'gif',keywords:['赞'],width:1,height:1,useCount:null},
    ]},
    {keyword:'空组',aliases:['空组'],confirmedAliases:[],category:'其他',planned:false,custom:true,assets:[]},
  ]};
}
it('组内拖放只修改草稿，保存发送完整顺序并保留失败草稿', async () => {
  const data = editableLibrary(); const update = vi.fn().mockRejectedValueOnce(new Error('保存断网')).mockImplementation(async (_keyword, patch) => {
    const group = data.groups[0]!; group.assets.reverse(); return {group};
  });
  const view = await mount('Stickers', {stickerLibrary:vi.fn(async()=>structuredClone(data)),updateStickerGroup:update});
  expect(view.find('save-sticker-order')).toBeDefined();
  view.find('drag-sticker-personal:7')!.props.onDragstart({dataTransfer:{setData(){},effectAllowed:''}}); await settle();
  view.find('sticker-cell-system:praise')!.props.onDrop({preventDefault(){}}); await settle();
  expect(update).not.toHaveBeenCalled();
  const order = () => view.all().filter(n=>String(n.props['data-testid']??'').startsWith('sticker-cell-')).map(n=>n.props['data-testid']);
  expect(order()).toEqual(['sticker-cell-system:praise','sticker-cell-personal:7']);
  await view.find('save-sticker-order')!.props.onClick(); await settle();
  expect(update).toHaveBeenLastCalledWith('赞',{assetOrder:['system:praise','personal:7']});
  expect(view.text()).toContain('保存断网'); expect(view.find('save-sticker-order')!.props.disabled).toBe(false);
  await view.find('save-sticker-order')!.props.onClick(); await settle();
  expect(view.find('save-sticker-order')!.props.disabled).toBe(true);
  expect(order()).toEqual(['sticker-cell-system:praise','sticker-cell-personal:7']);
});
it('同组说法可编辑、删除、新增，失败保留草稿，成功显示服务器结果', async () => {
  const data=editableLibrary(); const update=vi.fn().mockRejectedValueOnce(new Error('冲突说法')).mockImplementation(async (_k, patch)=>({group:{...data.groups[0],aliases:patch.aliases}}));
  const view=await mount('Stickers',{stickerLibrary:vi.fn(async()=>structuredClone(data)),updateStickerGroup:update});
  expect(view.find('edit-group-aliases')).toBeDefined();
  view.find('edit-group-aliases')!.props.onClick(); await settle();
  view.find('group-alias-input-0')!.props['onUpdate:modelValue']('夸夸你');
  view.find('remove-group-alias-1')!.props.onClick(); await settle();
  view.find('add-group-alias')!.props.onClick(); await settle();
  view.find('group-alias-input-1')!.props['onUpdate:modelValue']('真棒');
  await view.find('save-group-aliases')!.props.onClick(); await settle();
  expect(update).toHaveBeenLastCalledWith('赞',{aliases:['夸夸你','真棒']});
  expect(view.text()).toContain('冲突说法'); expect(view.find('group-alias-input-0')).toBeDefined();
  await view.find('save-group-aliases')!.props.onClick(); await settle();
  expect(view.find('group-alias-input-0')).toBeUndefined(); expect(view.text()).toContain('夸夸你');
});
it('切换组丢弃未保存排序和说法，不能误保存到下一组', async () => {
  const update=vi.fn(); const view=await mount('Stickers',{stickerLibrary:vi.fn(async()=>editableLibrary()),updateStickerGroup:update});
  expect(view.find('edit-group-aliases')).toBeDefined();
  view.find('edit-group-aliases')!.props.onClick(); await settle();
  view.find('keyword-空组')!.props.onClick(); await settle();
  expect(view.find('group-alias-input-0')).toBeUndefined(); expect(update).not.toHaveBeenCalled();
});
it('拖拽草稿期间同组上传新图仍显示，并把新图纳入最终保存顺序', async()=>{
 const data=editableLibrary(); const update=vi.fn(async()=>({group:data.groups[0]}));
 const upload=vi.fn(async()=>{data.groups[0]!.assets.push({...data.groups[0]!.assets[0]!,id:8});return {};});
 const view=await mount('Stickers',{stickerLibrary:vi.fn(async()=>structuredClone(data)),uploadSticker:upload,updateStickerGroup:update});
 view.find('drag-sticker-personal:7')!.props.onDragstart({dataTransfer:{setData(){},effectAllowed:''}});
 view.find('sticker-cell-system:praise')!.props.onDrop({preventDefault(){}});await settle();
 vi.stubGlobal('Image',class {naturalWidth=1;naturalHeight=1;onload:(()=>void)|null=null;set src(_s:string){this.onload?.();}});
 await view.find('group-upload-input')!.props.onChange({target:{files:[new File(['GIF89a'],'new.gif',{type:'image/gif'})],value:'new.gif'}});await settle();
 expect(view.find('sticker-cell-personal:8')).toBeDefined();
 await view.find('save-sticker-order')!.props.onClick();await settle();
 expect(update).toHaveBeenLastCalledWith('赞',{assetOrder:['system:praise','personal:7','personal:8']});
});
it('排序草稿期间删除同组图片剔除失效ID，不影响剩余顺序',async()=>{
 const data=editableLibrary();data.groups[0]!.assets.push({...data.groups[0]!.assets[0]!,id:8});
 const update=vi.fn(async()=>({group:data.groups[0]}));
 const remove=vi.fn(async()=>{data.groups[0]!.assets=data.groups[0]!.assets.filter(asset=>asset.id!==7);return {};});
 vi.stubGlobal('confirm',()=>true);
 const view=await mount('Stickers',{stickerLibrary:vi.fn(async()=>structuredClone(data)),deleteSticker:remove,updateStickerGroup:update});
 view.find('drag-sticker-personal:7')!.props.onDragstart({dataTransfer:{setData(){},effectAllowed:''}});
 view.find('sticker-cell-system:praise')!.props.onDrop({preventDefault(){}});await settle();
 await view.find('delete-sticker-7')!.props.onClick();await settle();
 expect(view.find('sticker-cell-personal:7')).toBeUndefined();
 // 删除后原始剩余顺序恰好相同，不再保存冗余或失效ID。
 expect(view.find('save-sticker-order')!.props.disabled).toBe(true);
 expect(update).not.toHaveBeenCalled();
});

it('底图全选和全不选仅选择可管理图片，批量删除取消不请求且失败可重试', async () => {
 const mine={...synthesisAsset,id:'mine'},other={...synthesisAsset,id:'other'},system={...synthesisAsset,id:'system',source:'system',deletable:false};
 const remove=vi.fn().mockResolvedValueOnce({ok:true}).mockRejectedValueOnce(new Error('网络中断'));
 const view=await mount('SynthesisLibrary',{synthesisLibrary:vi.fn().mockResolvedValue({assets:[mine,other,system]}),deleteSynthesisAsset:remove});
 expect(view.find('select-all-synthesis')).toBeDefined();
 view.find('select-all-synthesis')!.props.onClick();await settle();
 expect(view.text()).toContain('已选 2 张');expect(view.find('select-synthesis-system')).toBeUndefined();
 view.find('clear-synthesis-selection')!.props.onClick();await settle();
 expect(view.find('delete-selected-synthesis')!.props.disabled).toBe(true);
 view.find('select-all-synthesis')!.props.onClick();await settle();
 vi.stubGlobal('confirm',()=>false);await view.find('delete-selected-synthesis')!.props.onClick();expect(remove).not.toHaveBeenCalled();
 vi.stubGlobal('confirm',()=>true);await view.find('delete-selected-synthesis')!.props.onClick();await settle();
 expect(remove.mock.calls.map(c=>c[0])).toEqual(['mine','other']);
 expect(view.text()).toContain('已删除 1 张');expect(view.text()).toContain('1 张删除失败');expect(view.text()).toContain('已选 1 张');
});
it('底图编辑回填资料，无需重传GIF，保存失败保留草稿', async () => {
 const asset={...synthesisAsset,sourceStatement:'原创',layout:{minFontSize:12,maxFontSize:24,textColor:'#222222',strokeColor:'#ffffff',strokeWidth:1,alignment:'center',maxLines:2}};
 const update=vi.fn().mockRejectedValueOnce(new Error('保存中断')).mockResolvedValue({asset:{...asset,name:'新名称'}});
 const view=await mount('SynthesisLibrary',{synthesisLibrary:vi.fn().mockResolvedValue({assets:[asset]}),updateSynthesisAsset:update});
 expect(view.find('edit-synthesis-blank-test')).toBeDefined();
 view.find('edit-synthesis-blank-test')!.props.onClick();await settle();
 expect(view.find('synthesis-name')!.value).toBe('测试底图');
 view.find('synthesis-name')!.props['onUpdate:modelValue']('新名称');
 await view.find('synthesis-form')!.props.onSubmit({preventDefault(){}});await settle();
 expect(update).toHaveBeenCalledWith(asset.id,expect.objectContaining({name:'新名称',sourceStatement:'原创',textSafeArea:asset.textSafeArea,layout:asset.layout}));
 expect(update.mock.calls[0][1]).not.toHaveProperty('file_base64');
 expect(view.text()).toContain('保存中断');expect(view.find('synthesis-name')!.value).toBe('新名称');
 await view.find('synthesis-form')!.props.onSubmit({preventDefault(){}});await settle();
 expect(view.find('cancel-synthesis-edit')).toBeUndefined();expect(view.text()).toContain('底图已更新');
});
it('底图批量删除切换用户卸载后停止后续请求', async () => {
 let finish!:()=>void;
 const remove=vi.fn(()=>new Promise<void>(resolve=>{finish=resolve}));
 const view=await mount('SynthesisLibrary',{synthesisLibrary:vi.fn().mockResolvedValue({assets:[synthesisAsset,{...synthesisAsset,id:'second'}]}),deleteSynthesisAsset:remove});
 expect(view.find('select-all-synthesis')).toBeDefined();
 view.find('select-all-synthesis')!.props.onClick();await settle();vi.stubGlobal('confirm',()=>true);
 const pending=view.find('delete-selected-synthesis')!.props.onClick();await settle();
 view.unmount();finish();await pending;
 expect(remove).toHaveBeenCalledTimes(1);
});

it('较早的底图库刷新晚返回不能覆盖刚上传的图片', async () => {
 let finish!:(value:any)=>void;
 const list=vi.fn().mockResolvedValueOnce({assets:[]}).mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve})).mockResolvedValue({assets:[synthesisAsset]});
 const view=await mount('SynthesisLibrary',{synthesisLibrary:list,uploadSynthesisAsset:vi.fn().mockResolvedValue({asset:synthesisAsset,duplicate:false})});
 const pending=view.all().find(n=>n.tag==='button'&&n.text==='刷新列表')!.props.onClick();await settle();
 await view.find('synthesis-file')!.props.onChange({target:{files:[new File(['GIF89a'],'a.gif')],value:''}});await settle();
 expect(view.find('synthesis-card-blank-test')).toBeDefined();
 finish({assets:[]});await pending;await settle();
 expect(view.find('synthesis-card-blank-test')).toBeDefined();
});
it('删除响应丢失但刷新确认已删除时移除失效勾选', async () => {
 const list=vi.fn().mockResolvedValueOnce({assets:[synthesisAsset]}).mockResolvedValue({assets:[]});
 const view=await mount('SynthesisLibrary',{synthesisLibrary:list,deleteSynthesisAsset:vi.fn().mockRejectedValue(new Error('响应丢失'))});
 view.find('select-all-synthesis')!.props.onClick();await settle();vi.stubGlobal('confirm',()=>true);
 await view.find('delete-selected-synthesis')!.props.onClick();await settle();
 expect(view.find('synthesis-card-blank-test')).toBeUndefined();expect(view.text()).toContain('已选 0 张');expect(view.text()).toContain('已删除 1 张');
 expect(view.text()).not.toContain('删除失败');
});
