import { readFileSync } from 'node:fs';
import { expect, it, vi } from '../../server/node_modules/vitest/dist/index.js';
import { parse, compileScript } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';

it('异步总览数据返回后先挂载图表容器再初始化图表', async () => {
  // 真实 Vue 调度和 SFC 模板，仅替换 DOM 与 ECharts 绘图。
  const source = readFileSync(new URL('../src/views/Overview.vue', import.meta.url), 'utf8');
  const { descriptor } = parse(source);
  const script = compileScript(descriptor, { id: 'overview-test', inlineTemplate: true });
  const code = ts.transpileModule(script.content, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  type Node = { children: Node[]; parent: Node | null; props: Record<string, unknown> };
  const node = (): Node => ({ children: [], parent: null, props: {} });
  const root = node();
  function insert(n: Node, parent: Node, anchor?: Node | null) {
    n.parent = parent;
    const i = anchor ? parent.children.indexOf(anchor) : -1;
    if (i < 0) parent.children.push(n); else parent.children.splice(i, 0, n);
  }
  const renderer = Vue.createRenderer<Node, Node>({
    createElement: node, createText: node, createComment: node,
    setText() {}, setElementText() {},
    parentNode: n => n.parent,
    nextSibling: n => n.parent?.children[n.parent.children.indexOf(n) + 1] ?? null,
    patchProp: (n, key, _prev, next) => { n.props[key] = next; },
    insert,
    remove(n) { const p = n.parent; if (p) p.children.splice(p.children.indexOf(n), 1); n.parent = null; },
    insertStaticContent(content, parent, anchor) {
      const n = node();
      const id = content.match(/id="([^"]+)"/); if (id) n.props.id = id[1];
      insert(n, parent, anchor);
      return [n, n];
    },
  });
  const find = (n: Node, id: string): Node | null => n.props.id === id ? n : n.children.map(c => find(c, id)).find(Boolean) ?? null;
  let respond!: (data: unknown) => void;
  const response = new Promise(resolve => { respond = resolve; });
  const setOption = vi.fn(); const init = vi.fn(() => ({ setOption }));
  const module = { exports: {} as { default: Vue.Component } };
  const require = (name: string) => {
    if (name === 'vue') return Vue;
    if (name === 'echarts') return { init };
    if (name === '../api') return { api: { overview: () => response } };
    throw new Error(`Unexpected import: ${name}`);
  };
  new Function('require', 'module', 'exports', 'document', code)(require, module, module.exports, { getElementById: (id: string) => find(root, id) });
  const app = renderer.createApp(module.exports.default);
  app.mount(root);
  try {
    expect(init).not.toHaveBeenCalled();
    respond({ today: {}, period: {}, total_chars: '147', source_distribution: { typed: 145, pasted: 2, external: 0, voice: 0, total: 147 } });
    await response;
    await Vue.nextTick();
    await Vue.nextTick();
    expect(find(root, 'source-chart')).not.toBeNull();
    expect(init).toHaveBeenCalledTimes(1);
    expect(init).toHaveBeenCalledWith(find(root, 'source-chart'));
    expect(setOption.mock.calls[0][0].series[0].data.map((d: { value: number }) => d.value)).toEqual([145, 2, 0, 0]);
  } finally { app.unmount(); }
});
