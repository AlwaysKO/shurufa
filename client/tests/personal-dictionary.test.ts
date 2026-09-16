import { readFileSync } from 'node:fs';
import { expect, it, vi, afterEach } from '../../server/node_modules/vitest/dist/index.js';
import { parse, compileScript } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';

type Node = { tag: string; text: string; children: Node[]; parent: Node | null; props: Record<string, any>; value: string; options: Node[]; files?: File[]; addEventListener: () => void; removeEventListener: () => void; click: () => void; tagName: string; getRootNode: () => object };
const mounted: Vue.App[] = [];
afterEach(() => { mounted.splice(0).forEach(app => app.unmount()); vi.unstubAllGlobals(); });
async function settle() { for (let i = 0; i < 12; i++) { await Promise.resolve(); await Vue.nextTick(); } }
async function mount(name: string, api: Record<string, any>) {
  const { descriptor } = parse(readFileSync(new URL(`../src/views/${name}.vue`, import.meta.url), 'utf8'));
  const compiled = compileScript(descriptor, { id: name, inlineTemplate: true });
  const code = ts.transpileModule(compiled.content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const node = (tag = '', text = ''): Node => ({ tag, text, children: [], parent: null, props: {}, value: '', get options() { return this.children.filter(n => n.tag === 'option'); }, addEventListener() {}, removeEventListener() {}, click() {}, getRootNode: () => ({ activeElement: null }), tagName: tag.toUpperCase() });
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
    if (id === 'vue-router') return { useRoute: () => ({ query: {} }) };
    if (id === '../api/personalDictionary') return { dictionaryApi: api };
    if (id === '../api') return { api, appName: (s: string) => s, deviceDetailLines: () => [], deviceLabel: () => '', eventTypeName: (s: string) => s, networkName: (s: string) => s };
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

const devices = [
  { device_id: 'old', name: '旧手机', in_group: true, synced: false, migration_status: 'complete', imported: 3 },
  { device_id: 'new', name: '新手机', in_group: false, synced: false, migration_status: 'unavailable', imported: 0 },
];
function mockApi() { return {
  devices: vi.fn().mockResolvedValue({ devices }),
  entries: vi.fn().mockResolvedValue({ total: 51, page: 1, entries: [{device_id:'old',text:'充电宝',kind:'choice',source:'selection',code:'2466434262',pinyin:'',count:3,weight:2.7,last_used:1000,status:'enabled'}] }),
  bind: vi.fn().mockResolvedValue({ok:true}), decisions: vi.fn().mockResolvedValue({ok:true}),
}; }
it('展示原始来源、未知拼音、等待手机确认，支持按设备查询和分页', async () => {
  const api = mockApi(); const view = await mount('PersonalDictionary', api);
  expect(view.text()).toContain('旧手机'); expect(view.text()).toContain('新手机');
  expect(view.text()).toContain('等待手机同步'); expect(view.text()).toContain('未记录');
  view.find('device-old')!.props.onClick(); await settle();
  expect(api.entries.mock.calls.at(-1)?.[0]).toMatchObject({device_id:'old',page:1});
  view.find('next-page')!.props.onClick(); await settle();
  expect(api.entries.mock.calls.at(-1)?.[0]).toMatchObject({page:2});
});
it('绑定必须确认，删除发出后台决策而不是抹掉上报明细', async () => {
  const api = mockApi(); vi.stubGlobal('confirm', vi.fn().mockReturnValue(false));
  const view = await mount('PersonalDictionary', api);
  view.find('bind-new')!.props.onClick(); await settle(); expect(api.bind).not.toHaveBeenCalled();
  vi.stubGlobal('confirm', vi.fn().mockReturnValue(true));
  view.find('bind-new')!.props.onClick(); await settle(); expect(api.bind).toHaveBeenCalledWith('new');
  view.find('delete-0')!.props.onClick(); await settle();
  expect(api.decisions).toHaveBeenCalledWith(['充电宝'],'deleted');
  expect(view.text()).toContain('充电宝');
});
