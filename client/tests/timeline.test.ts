import { readFileSync } from 'node:fs';
import { afterEach, expect, it, vi } from '../../server/node_modules/vitest/dist/index.js';
import { parse, compileScript } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';

// 编译真实时间线组件，保留 Vue 异步渲染；仅替换网络及需要浏览器 Canvas 的绘图库。
const source = readFileSync(new URL('../src/views/Timeline.vue', import.meta.url), 'utf8');
const { descriptor } = parse(source);
const script = compileScript(descriptor, { id: 'timeline-test', inlineTemplate: true });
const code = ts.transpileModule(script.content, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
const cleanups: Array<() => void> = [];
afterEach(() => { cleanups.splice(0).forEach(cleanup => cleanup()); });

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
  const request = vi.fn((_days: number) => new Promise((resolve, reject) => pending.push({ resolve, reject })));
  const charts: Array<{ setOption: ReturnType<typeof vi.fn>; dispose: ReturnType<typeof vi.fn>; resize: ReturnType<typeof vi.fn> }> = [];
  const init = vi.fn((_el: unknown) => {
    const chart = { setOption: vi.fn(), dispose: vi.fn(), resize: vi.fn() }; charts.push(chart); return chart;
  });
  const listeners = new Map<string, () => void>();
  const window = { addEventListener: (event: string, fn: () => void) => listeners.set(event, fn), removeEventListener: (event: string) => listeners.delete(event) };
  const module = { exports: {} as { default: Vue.Component } };
  const require = (name: string) => {
    if (name === 'vue') return Vue;
    if (name === 'echarts') return { init, getInstanceByDom: () => undefined };
    if (name === '../api') return { api: { timeline: request, hours: request, heatmap: request } };
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
    text: () => text(root),
  };
}


async function loaded() {
  const h = mount();
  h.pending[0].resolve({ timeline: [
    { day: '2026-09-13', input_chars: '2', event_count: '1' },
    { day: '2026-09-14', input_chars: '5', event_count: '2' },
  ] });
  h.pending[1].resolve({ hours: [{ hour: 0, input_chars: '3' }, { hour: 8, input_chars: '2' }] });
  h.pending[2].resolve({ cells: [{ dow: 1, hour: 0, chars: '3' }] });
  await flush();
  return h;
}
it('横轴日期水平显示、预留标签空间，提示保留完整日期', async () => {
  const h = await loaded();
  const option = h.charts[0].setOption.mock.calls[0][0];
  expect(option.xAxis.axisLabel.rotate).toBe(0);
  expect(option.grid.containLabel).toBe(true);
  expect(option.xAxis.data).toEqual(['2026-09-13', '2026-09-14']);
  expect(option.xAxis.axisLabel.formatter('2026-09-14')).toBe('09-14');
  expect(option.series.map((s: any) => s.data)).toEqual([[2, 5], [1, 2]]);
});
it('明确标注北京时间，小时与星期不再次按浏览器时区偏移', async () => {
  const h = await loaded();
  expect(h.text()).toContain('北京时间');
  const hours = h.charts[1].setOption.mock.calls[0][0];
  expect(hours.series[0].data[0]).toBe(3);
  expect(hours.series[0].data[8]).toBe(2);
  const heatmap = h.charts[2].setOption.mock.calls[0][0];
  expect(heatmap.series[0].data).toEqual([[0, 0, 3]]);
});
it('窗口缩放重新布局，离开页面清理图表与监听器', async () => {
  const h = await loaded();
  expect(h.listeners.has('resize')).toBe(true);
  h.listeners.get('resize')!();
  h.charts.forEach(chart => expect(chart.resize).toHaveBeenCalledOnce());
  h.unmount();
  h.charts.forEach(chart => expect(chart.dispose).toHaveBeenCalledOnce());
  expect(h.listeners.has('resize')).toBe(false);
});
