import { readFileSync } from 'node:fs';
import { expect, it, vi } from '../../server/node_modules/vitest/dist/index.js';
import { parse, compileScript } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';

function compile(code: string) {
  return ts.transpileModule(code, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
}
const apiModule = { exports: {} as { appName: (pkg: string | null, name?: string | null) => string } };
new Function('require', 'module', 'exports', 'localStorage', compile(readFileSync(new URL('../src/api/index.ts', import.meta.url), 'utf8')))(
  (id: string) => { if (id === 'vue') return Vue; if (id === '../auth') return { dashboardFetch: vi.fn() }; throw Error(id); },
  apiModule, apiModule.exports, { getItem: () => null },
);
const appName = apiModule.exports.appName;

it('系统真实名称优先，常用历史包名仍用名称兜底', () => {
  expect(appName('com.tencent.mm', '  企业定制微信  ')).toBe('企业定制微信');
  expect(appName('org.example.notes', 'My Notes')).toBe('My Notes');
  expect(appName('com.tencent.mm', '')).toBe('微信');
  expect(appName('com.tencent.mobileqq', null)).toBe('QQ');
});
it('未识别应用保留完整包名，不能截成容易混淆的后缀', () => {
  expect(appName('org.example.unknown')).toBe('org.example.unknown');
  expect(appName(null)).toBe('未知');
});

it('APP 图表显示真实名字、同名不同包仍分开且悬浮可核对包名', async () => {
  type Node = { children: Node[]; parent: Node | null; props: Record<string, unknown> };
  const node = (): Node => Vue.markRaw({ children: [], parent: null, props: {} });
  const root = node();
  const renderer = Vue.createRenderer<Node, Node>({
    createElement: node, createText: node, createComment: node,
    setText() {}, setElementText() {}, parentNode: n => n.parent,
    nextSibling: n => n.parent?.children[n.parent.children.indexOf(n) + 1] ?? null,
    patchProp(n, k, _prev, next) { n.props[k] = next; },
    insert(n, p) { n.parent = p; p.children.push(n); },
    remove(n) { if (n.parent) n.parent.children.splice(n.parent.children.indexOf(n), 1); },
    insertStaticContent(_s, p) { const n = node(); n.parent = p; p.children.push(n); return [n,n]; },
  });
  const find = (n: Node): Node | undefined => n.props.id === 'apps-chart' ? n : n.children.map(find).find(Boolean);
  const setOption = vi.fn();
  const rows = [
    { package_name: 'org.example.first', app_name: '聊天', input_chars: '12', event_count: '3' },
    { package_name: 'org.example.second', app_name: '聊天', input_chars: '8', event_count: '2' },
    { package_name: 'com.tencent.mm', input_chars: '5', event_count: '1' },
  ];
  const { descriptor } = parse(readFileSync(new URL('../src/views/Applications.vue', import.meta.url), 'utf8'));
  const script = compileScript(descriptor, { id: 'apps-test', inlineTemplate: true });
  const module = { exports: {} as { default: Vue.Component } };
  new Function('require','module','exports','document','window',compile(script.content))((id: string) => {
    if (id === 'vue') return Vue;
    if (id === 'echarts') return { init: () => ({setOption,resize() {},dispose() {}}) };
    if (id === '../api') return { appName, api: { apps: () => Promise.resolve({ apps: rows }) } };
    throw Error(id);
  }, module, module.exports, { getElementById: () => find(root) }, {addEventListener() {},removeEventListener() {}});
  const app = renderer.createApp(module.exports.default); app.mount(root);
  try {
    for (let i=0; i<5; i++) await Vue.nextTick();
    const option = setOption.mock.lastCall![0];
    // 类目使用唯一包名，展示时再映射真实名称，不能按名称合并。
    expect(option.yAxis.data).toEqual(['com.tencent.mm','org.example.second','org.example.first']);
    expect(option.yAxis.data.map((pkg: string) => option.yAxis.axisLabel.formatter(pkg))).toEqual(['微信','聊天','聊天']);
    expect(option.series[0].data).toEqual([5,8,12]);
    expect(option.tooltip.renderMode).toBe('richText');
    const tip=option.tooltip.formatter([{dataIndex:1}]);
    expect(tip).toContain('聊天'); expect(tip).toContain('org.example.second'); expect(tip).toContain('8');
  } finally { app.unmount(); }
});
