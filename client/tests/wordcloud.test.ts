import { readFileSync } from 'node:fs';
import { afterEach, expect, it, vi } from '../../server/node_modules/vitest/dist/index.js';
import { parse, compileScript } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';

// 编译真实词云组件，保留 Vue 异步渲染；仅替换网络及需要浏览器 Canvas 的绘图库。
const source = readFileSync(new URL('../src/views/WordCloud.vue', import.meta.url), 'utf8');
const { descriptor } = parse(source);
const script = compileScript(descriptor, { id: 'wordcloud-test', inlineTemplate: true });
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
  const request = vi.fn((_type: string, _date: string) => new Promise((resolve, reject) => pending.push({ resolve, reject })));
  const push = vi.fn();
  const charts: Array<{ setOption: ReturnType<typeof vi.fn>; dispose: ReturnType<typeof vi.fn>; resize: ReturnType<typeof vi.fn>; off: ReturnType<typeof vi.fn>; on: ReturnType<typeof vi.fn> }> = [];
  const init = vi.fn((_el: unknown) => {
    expect(_el).toBe(find(root, n => n.props.id === 'wordcloud-chart'));
    expect(_el).toBeDefined();
    const chart = { setOption: vi.fn(), dispose: vi.fn(), resize: vi.fn(), off: vi.fn(), on: vi.fn() }; charts.push(chart); return chart;
  });
  const listeners = new Map<string, () => void>();
  const window = { addEventListener: (event: string, fn: () => void) => listeners.set(event, fn), removeEventListener: (event: string) => listeners.delete(event) };
  const module = { exports: {} as { default: Vue.Component } };
  const require = (name: string) => {
    if (name === 'vue') return Vue;
    if (name === 'echarts') return { init };
    if (name === '../api') return { api: { phrases: request } };
    if (name === 'vue-router') return { useRouter: () => ({ push }) };
    if (name === 'echarts-wordcloud') return {};
    throw Error(`Unexpected import: ${name}`);
  };
  new Function('require', 'module', 'exports', 'document', 'window', code)(require, module, module.exports,
    { getElementById: (id: string) => find(root, n => n.props.id === id) }, window);
  const app = renderer.createApp(module.exports.default); app.mount(root);
  let mounted = true;
  const unmount = () => { if (mounted) { app.unmount(); mounted = false; } };
  cleanups.push(unmount);
  return {
    init, charts, pending, request, listeners, unmount, push,
    text: () => text(root), container: () => find(root, n => n.props.id === 'wordcloud-chart'),
    click(label: string) { const button = find(root, n => n.tag === 'button' && text(n).includes(label)); expect(button).toBeDefined(); button!.props.onClick(); },
  };
}


const response = (...words: string[]) => ({ kind: 'word', phrases: words.map((phrase, i) => ({
  phrase, use_count: String(10 - i), use_days: '2', last_used_at: '2026-09-18T01:00:00Z',
})) });
async function loaded() {
  const h = mount(); h.pending[0].resolve(response('测试', '输入')); await flush(); return h;
}
it('首次异步加载必须等可见容器挂载后初始化，词云与列表使用同一份数据', async () => {
  const h = mount(); await flush(); expect(h.init).not.toHaveBeenCalled(); expect(h.text()).toContain('加载中');
  h.pending[0].resolve(response('测试', '输入')); await flush();
  expect(h.init).toHaveBeenCalledOnce(); expect(h.text()).toContain('测试');
  expect(h.charts[0].setOption.mock.calls[0][0].series[0].data).toEqual([{ name: '测试', value: 10 }, { name: '输入', value: 9 }]);
});
it('切换范围先释放旧画布，在新容器绘制新词而非沿用已移除的DOM', async () => {
  const h = await loaded(); const el = h.container();
  h.click('近7天'); await flush();
  expect(h.charts[0].dispose).toHaveBeenCalledOnce(); expect(h.container()).toBeUndefined();
  expect(h.request).toHaveBeenLastCalledWith('word', 7, 100);
  h.pending[1].resolve(response('新词')); await flush();
  expect(h.container()).not.toBe(el); expect(h.init).toHaveBeenCalledTimes(2);
  expect(h.charts[1].setOption.mock.calls[0][0].series[0].data).toEqual([{ name: '新词', value: 10 }]);
});
it('无数据或只有超长句子时显示提示，不初始化空白词云', async () => {
  const h = mount(); h.pending[0].resolve(response()); await flush();
  expect(h.text()).toContain('暂无数据'); expect(h.init).not.toHaveBeenCalled();
  h.click('全部'); h.pending[1].resolve(response('这是一句超过十个字符的长句子')); await flush();
  expect(h.container()).toBeUndefined(); expect(h.init).not.toHaveBeenCalled();
  expect(h.text()).toContain('这是一句超过十个字符的长句子');
});
it('请求失败显示原因，并可原范围重试恢复绘图', async () => {
  const h = mount(); h.pending[0].reject(Error('测试网络失败')); await flush();
  expect(h.text()).toContain('测试网络失败'); expect(h.init).not.toHaveBeenCalled();
  h.click('重试'); h.pending[1].resolve(response('恢复')); await flush();
  expect(h.init).toHaveBeenCalledOnce(); expect(h.text()).not.toContain('测试网络失败');
});
for (const staleError of [false, true]) it(`快速切换忽略旧请求${staleError ? '失败' : '成功'}，不会覆盖最新范围`, async () => {
  const h = mount(); h.click('近7天'); h.pending[1].resolve(response('最新')); await flush();
  if (staleError) h.pending[0].reject(Error('旧错误')); else h.pending[0].resolve(response('旧词'));
  await flush(); expect(h.text()).toContain('最新'); expect(h.text()).not.toContain('旧词'); expect(h.text()).not.toContain('旧错误');
  expect(h.init).toHaveBeenCalledOnce(); expect(h.charts[0].setOption).toHaveBeenCalledOnce();
});
it('卸载时移除监听并释放图表，窗口缩放会重排词云', async () => {
  const h = await loaded(); expect(h.listeners.has('resize')).toBe(true); h.listeners.get('resize')!();
  expect(h.charts[0].resize).toHaveBeenCalledOnce(); h.unmount();
  expect(h.charts[0].dispose).toHaveBeenCalledOnce(); expect(h.listeners.has('resize')).toBe(false);
});
it('卸载后迟到请求不会初始化图表', async () => {
  const h = mount(); h.unmount(); h.pending[0].resolve(response('迟到')); await flush();
  expect(h.init).not.toHaveBeenCalled();
});
it('词云点击仍跳转该词的输入明细', async () => {
  const h = await loaded(); expect(h.charts[0].on.mock.calls[0][0]).toBe('click');
  h.charts[0].on.mock.calls[0][1]({ data: { name: '测试' } });
  expect(h.push).toHaveBeenCalledWith({ path: '/activity', query: { q: '测试' } });
});
