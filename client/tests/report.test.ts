import { readFileSync } from 'node:fs';
import { afterEach, expect, it, vi } from '../../server/node_modules/vitest/dist/index.js';
import { parse, compileScript } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';

// 编译真实报表组件，保留 Vue 异步渲染；仅替换网络及需要浏览器 Canvas 的绘图库。
const source = readFileSync(new URL('../src/views/Report.vue', import.meta.url), 'utf8');
const { descriptor } = parse(source);
const script = compileScript(descriptor, { id: 'report-test', inlineTemplate: true });
const code = ts.transpileModule(script.content, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
const cleanups: Array<() => void> = [];
afterEach(() => { cleanups.splice(0).forEach(cleanup => cleanup()); });

function report(typed = 120, pasted = 30, external = 20, voice = 10) {
  return {
    type: 'daily', date: '2026-09-16',
    summary: { input_events: '12', input_chars: String(typed + pasted + external + voice), copy_count: '1', delete_count: '2', voice_count: '1', device_count: '1', active_days: '1' },
    source_distribution: { typed, pasted, external, voice, total: typed + pasted + external + voice },
    peak_hours: [], top_apps: [], top_phrases: [], top_locations: [],
  };
}
async function flush() { for (let i = 0; i < 5; i++) await Vue.nextTick(); }
function mount() {
  type Node = { children: Node[]; parent: Node | null; props: Record<string, any>; text: string; tag: string };
  // 模拟 DOM 节点不可响应式代理的特性，避免 template ref 将测试宿主对象代理。
  const node = (tag = '', text = ''): Node => Vue.markRaw({ children: [], parent: null, props: {}, text, tag });
  const root = node();
  function insert(n: Node, parent: Node, anchor?: Node | null) {
    n.parent = parent;
    const i = anchor ? parent.children.indexOf(anchor) : -1;
    if (i < 0) parent.children.push(n); else parent.children.splice(i, 0, n);
  }
  const renderer = Vue.createRenderer<Node, Node>({
    createElement: tag => node(tag), createText: text => node('', text), createComment: () => node(),
    setText(n, text) { n.text = text; }, setElementText(n, text) { n.text = text; n.children = []; },
    parentNode: n => n.parent, nextSibling: n => n.parent?.children[n.parent.children.indexOf(n) + 1] ?? null,
    patchProp: (n, key, _prev, next) => { n.props[key] = next; }, insert,
    remove(n) { const p = n.parent; if (p) p.children.splice(p.children.indexOf(n), 1); n.parent = null; },
    insertStaticContent(content, parent, anchor) {
      const n = node('', content); const id = content.match(/id="([^"]+)"/); if (id) n.props.id = id[1];
      insert(n, parent, anchor); return [n, n];
    },
  });
  function find(n: Node, predicate: (n: Node) => boolean): Node | undefined {
    return predicate(n) ? n : n.children.map(c => find(c, predicate)).find(Boolean);
  }
  const text = (n: Node): string => n.text + n.children.map(text).join('');
  const pending: Array<{ resolve: (data: unknown) => void; reject: (e: Error) => void }> = [];
  const request = vi.fn((_type: string, _date: string) => new Promise((resolve, reject) => pending.push({ resolve, reject })));
  const charts: Array<{ setOption: ReturnType<typeof vi.fn>; dispose: ReturnType<typeof vi.fn>; resize: ReturnType<typeof vi.fn> }> = [];
  const init = vi.fn((_el: unknown) => {
    const chart = { setOption: vi.fn(), dispose: vi.fn(), resize: vi.fn() }; charts.push(chart); return chart;
  });
  const listeners = new Map<string, () => void>();
  const window = { addEventListener: (event: string, fn: () => void) => listeners.set(event, fn), removeEventListener: (event: string) => listeners.delete(event) };
  const module = { exports: {} as { default: Vue.Component } };
  const require = (name: string) => {
    if (name === 'vue') return Vue;
    if (name === 'echarts') return { init };
    if (name === '../api') return { api: { report: request }, appName: (s: string, name?: string) => name || s };
    throw Error(`Unexpected import: ${name}`);
  };
  new Function('require', 'module', 'exports', 'document', 'window', code)(require, module, module.exports,
    { getElementById: (id: string) => find(root, n => n.props.id === id) }, window);
  const app = renderer.createApp(module.exports.default); app.mount(root);
  let mounted = true;
  const unmount = () => { if (mounted) { app.unmount(); mounted = false; } };
  cleanups.push(unmount);
  return {
    init, charts, pending, request, listeners, unmount,
    text: () => text(root), container: () => find(root, n => n.props.id === 'report-source-chart'),
    click(label: string) { const button = find(root, n => n.tag === 'button' && text(n).includes(label)); expect(button).toBeDefined(); button!.props.onClick(); },
  };
}

it('首次异步加载等待容器挂载，并显示四种输入方式的真实字数', async () => {
  const h = mount(); expect(h.init).not.toHaveBeenCalled();
  h.pending[0].resolve(report()); await flush();
  expect(h.container()).toBeDefined(); expect(h.init).toHaveBeenCalledWith(h.container());
  expect(h.charts[0].setOption.mock.calls[0][0].series[0].data).toEqual([
    { name: '键盘输入', value: 120 }, { name: '复制粘贴', value: 30 },
    { name: '外部插入', value: 20 }, { name: '语音', value: 10 },
  ]);
});

it('切换日期和日报周报会更新现有图表', async () => {
  const h = mount(); h.pending[0].resolve(report()); await flush();
  h.click('前一天'); h.pending[1].resolve(report(5, 4, 3, 2)); await flush();
  h.click('周报'); expect(h.request.mock.calls[2][0]).toBe('weekly');
  h.pending[2].resolve({ ...report(9, 8, 7, 6), type: 'weekly' }); await flush();
  expect(h.init).toHaveBeenCalledTimes(1);
  expect(h.charts[0].setOption.mock.lastCall?.[0].series[0].data.map((d: { value: number }) => d.value)).toEqual([9, 8, 7, 6]);
});

it('没有输入时显示空状态而非误导性饼图，随后可以切回有数据日期', async () => {
  const h = mount(); h.pending[0].resolve(report()); await flush();
  h.click('前一天'); h.pending[1].resolve(report(0, 0, 0, 0)); await flush();
  expect(h.text()).toContain('暂无输入数据'); expect(h.charts[0].dispose).toHaveBeenCalledTimes(1);
  expect(h.container()).toBeUndefined();
  h.click('后一天'); h.pending[2].resolve(report()); await flush();
  expect(h.init).toHaveBeenCalledTimes(2); expect(h.init.mock.lastCall?.[0]).toBe(h.container());
});

it('首次无数据时不初始化图表', async () => {
  const h = mount(); h.pending[0].resolve(report(0, 0, 0, 0)); await flush();
  expect(h.text()).toContain('暂无输入数据'); expect(h.init).not.toHaveBeenCalled();
});

it('请求失败后重试会清除错误并为新容器初始化图表', async () => {
  const h = mount(); h.pending[0].resolve(report()); await flush();
  h.click('前一天'); h.pending[1].reject(Error('暂时不可用')); await flush();
  expect(h.text()).toContain('加载失败'); expect(h.charts[0].dispose).toHaveBeenCalledTimes(1);
  h.click('今天'); h.pending[2].resolve(report()); await flush();
  expect(h.text()).not.toContain('加载失败'); expect(h.init).toHaveBeenCalledTimes(2);
});

it('快速切换日期时旧响应不覆盖最新报表', async () => {
  const h = mount(); h.pending[0].resolve(report()); await flush();
  h.click('前一天'); h.click('前一天');
  h.pending[2].resolve(report(9, 0, 0, 0)); await flush();
  h.pending[1].resolve(report(99, 0, 0, 0)); await flush();
  expect(h.charts[0].setOption.mock.lastCall?.[0].series[0].data[0].value).toBe(9);
});

it('窗口变化时重排，离开页面销毁图表并移除监听', async () => {
  const h = mount(); h.pending[0].resolve(report()); await flush();
  h.listeners.get('resize')?.(); expect(h.charts[0]?.resize).toHaveBeenCalledTimes(1);
  h.unmount(); expect(h.charts[0].dispose).toHaveBeenCalledTimes(1); expect(h.listeners.has('resize')).toBe(false);
});

it('离开页面后未完成请求不会重新创建图表', async () => {
  const h = mount(); h.unmount(); h.pending[0].resolve(report()); await flush();
  expect(h.init).not.toHaveBeenCalled();
});

it('旧请求失败不会隐藏已加载的新日期图表', async () => {
  const h = mount(); h.click('前一天');
  h.pending[1].resolve(report(9, 0, 0, 0)); await flush();
  h.pending[0].reject(Error('过期请求失败')); await flush();
  expect(h.text()).not.toContain('加载失败'); expect(h.container()).toBeDefined();
  expect(h.charts[0].dispose).not.toHaveBeenCalled();
});

it('首次请求失败后可通过今天按钮重新加载图表', async () => {
  const h = mount(); h.pending[0].reject(Error('暂时不可用')); await flush();
  expect(h.text()).toContain('加载失败');
  h.click('今天'); h.pending[1].resolve(report()); await flush();
  expect(h.text()).not.toContain('加载失败'); expect(h.container()).toBeDefined();
  expect(h.init).toHaveBeenCalledTimes(1);
});


it('报表摘要及 Top 应用优先展示接口返回的真实名称', async () => {
  const h = mount(); h.pending[0].resolve({ ...report(), top_apps: [
    { package_name: 'org.example.chat', app_name: '我的聊天', input_chars: '120', event_count: '12' },
  ] }); await flush();
  expect(h.text()).toContain('主要在 我的聊天 中');
  expect(h.text()).not.toContain('org.example.chat');
});
