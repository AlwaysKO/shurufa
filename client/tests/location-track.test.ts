import { readFileSync } from 'node:fs';
import { afterEach, expect, it, vi } from '../../server/node_modules/vitest/dist/index.js';
import { compileScript, parse } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';
import type { LocationRow } from '../src/api';

type Node = { tag: string; text: string; children: Node[]; parent: Node | null; props: Record<string, any>; options: Node[]; selectedIndex: number; tagName: string; addEventListener: () => void; removeEventListener: () => void };
const mounted: Vue.App[] = [];
afterEach(() => { mounted.splice(0).forEach(app => app.unmount()); vi.unstubAllGlobals(); vi.useRealTimers(); });

async function mountLocations(locations: LocationRow[], overrides: Record<string, any> = {}) {
  const { descriptor } = parse(readFileSync(new URL('../src/views/LocationTrack.vue', import.meta.url), 'utf8'));
  const compiled = compileScript(descriptor, { id: 'location-track-test', inlineTemplate: true });
  const code = ts.transpileModule(compiled.content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const node = (tag = '', text = ''): Node => ({ tag, text, children: [], parent: null, props: {}, selectedIndex: -1, tagName: tag.toUpperCase(), get options() { return this.children; }, addEventListener() {}, removeEventListener() {} });
  function insert(n: Node, p: Node, anchor?: Node | null) {
    if (n.parent) n.parent.children.splice(n.parent.children.indexOf(n), 1);
    n.parent = p;
    const i = anchor ? p.children.indexOf(anchor) : -1;
    if (i < 0) p.children.push(n); else p.children.splice(i, 0, n);
  }
  const renderer = Vue.createRenderer<Node, Node>({
    createElement: tag => node(tag), createText: text => node('', text), createComment: text => node('', text),
    setText: (n, text) => { n.text = text; }, setElementText: (n, text) => { n.text = text; n.children = []; },
    parentNode: n => n.parent, nextSibling: n => n.parent?.children[n.parent.children.indexOf(n) + 1] ?? null,
    patchProp: (n, key, _previous, value) => { n.props[key] = value; }, insert,
    remove(n) { if (n.parent) n.parent.children.splice(n.parent.children.indexOf(n), 1); n.parent = null; },
    insertStaticContent(text, p, anchor) { const n = node('static', text); insert(n, p, anchor); return [n, n]; },
  });
  const popups: string[] = [];
  const layer = () => ({ addTo() { return this; }, remove() {} });
  const leaflet = {
    map: () => ({ setView() { return this; }, getZoom: () => 13, remove() {} }),
    layerGroup: layer, tileLayer: layer, polyline: layer,
    circleMarker: () => ({ ...layer(), bindPopup(html: string) { popups.push(html); return this; } }),
  };
  const api = { devices: vi.fn(async () => ({ devices: [] })), locations: vi.fn(async () => ({ locations })) };
  Object.assign(api, overrides);
  const module = { exports: {} as { default: Vue.Component } };
  const require = (name: string) => {
    if (name === 'vue') return Vue;
    if (name === 'leaflet') return { default: leaflet };
    if (name === 'leaflet/dist/leaflet.css') return {};
    if (name === '../api') return { api, deviceLabel: () => '测试设备' };
    throw new Error(`Unexpected import: ${name}`);
  };
  vi.stubGlobal('document', { activeElement: null });
  vi.stubGlobal('Document', class {}); vi.stubGlobal('ShadowRoot', class {});
  new Function('require', 'module', 'exports', code)(require, module, module.exports);
  const root = node('root');
  const app = renderer.createApp(module.exports.default); app.mount(root); mounted.push(app);
  for (let i = 0; i < 8; i++) { await Promise.resolve(); await Vue.nextTick(); }
  const all = (n: Node): Node[] => [n, ...n.children.flatMap(all)];
  return { api, popups, unmount: () => app.unmount(), all: () => all(root), text: () => all(root).map(n => n.text).join(' '), summary: () => all(root).find(n => n.props.class === 'summary')?.text,
    cells: () => all(root).filter(n => n.tag === 'td').map(n => n.text) };
}

function point(occurred_at: string, first_seen_at = occurred_at, last_seen_at = occurred_at): LocationRow {
  return { id: '1', device_id: 'test-device', latitude: '23.13', longitude: '113.26', accuracy: '10',
    provider: 'gps', speed: null, address: '测试位置', occurred_at, first_seen_at, last_seen_at };
}

it('列表、最近记录与地图弹窗把01:00:56 UTC统一显示为09:00:56北京时间', async () => {
  const row = Object.freeze(point('2026-09-17T01:00:56.000Z'));
  const view = await mountLocations([row]);
  expect(view.cells()).toContain('2026-09-17 09:00:56');
  expect(view.summary()).toContain('2026-09-17 09:00:56');
  expect(view.summary()).toContain('北京时间');
  expect(view.popups[0]).toContain('2026-09-17 09:00:56');
  expect(view.popups[0]).toContain('北京时间');
  expect(view.text()).toContain('时间范围（北京时间）');
  expect(row.occurred_at).toBe('2026-09-17T01:00:56.000Z');
});

it('时间范围的起止时间都转换时区，正确处理跨日和午夜00点', async () => {
  const view = await mountLocations([point('2026-09-17T16:00:56Z', '2026-09-17T15:59:59Z', '2026-09-17T16:00:56Z')]);
  const range = '2026-09-17 23:59:59 ~ 2026-09-18 00:00:56';
  expect(view.cells()).toContain(range);
  expect(view.summary()).toContain(range);
  expect(view.popups[0]).toContain('2026-09-18 00:00:56');
});

it('带+08:00偏移的时间不重复加八小时', async () => {
  const view = await mountLocations([point('2026-09-17T09:00:56+08:00')]);
  expect(view.cells()).toContain('2026-09-17 09:00:56');
  expect(view.popups[0]).toContain('2026-09-17 09:00:56');
});

it('带负时区偏移的时间同样按绝对时刻转换，不受浏览器时区影响', async () => {
  const view = await mountLocations([point('2026-09-16T18:00:56-07:00')]);
  expect(view.cells()).toContain('2026-09-17 09:00:56');
  expect(view.summary()).toContain('2026-09-17 09:00:56');
  expect(view.popups[0]).toContain('2026-09-17 09:00:56');
});

it('无效时间显示占位符，不导致整个轨迹页面加载失败', async () => {
  const view = await mountLocations([point('invalid-time')]);
  expect(view.cells().at(-1)).toBe('-');
  expect(view.summary()).not.toContain('Invalid Date');
  expect(view.popups[0]).toContain('-');
});

it('空轨迹保持原有空状态，不伪造最新时间', async () => {
  const view = await mountLocations([]);
  expect(view.summary()).toBe('暂无位置数据');
  expect(view.popups).toEqual([]);
});

async function settle() { for (let i = 0; i < 12; i++) { await Promise.resolve(); await Vue.nextTick(); } }
const unresolved = (status: LocationRow['address_status']): LocationRow => ({ ...point('2026-09-17T01:00:56Z'), address: null, address_status: status });

it('排队中的地址自动更新为解析结果，完成后停止轮询', async () => {
  vi.useFakeTimers();
  const rows = [unresolved('resolving')];
  const locations = vi.fn().mockResolvedValueOnce({ locations: rows })
    .mockResolvedValue({ locations: [{ ...rows[0], address: '已解析地址', address_status: 'resolved' }] });
  const view = await mountLocations(rows, { locations });
  expect(view.text()).toContain('解析中');
  await vi.advanceTimersByTimeAsync(5000); await settle();
  expect(locations).toHaveBeenCalledTimes(2);
  expect(view.text()).toContain('已解析地址');
  await vi.advanceTimersByTimeAsync(60_000);
  expect(locations).toHaveBeenCalledTimes(2);
});

it('失败明确显示原因和北京时间的重试时间，按退避时间而不是频繁刷新', async () => {
  vi.useFakeTimers(); vi.setSystemTime(new Date('2026-09-17T01:00:00Z'));
  const row = { ...unresolved('failed'), address_error: '地址服务返回 HTTP 503', address_retry_at: '2026-09-17T01:00:30Z' };
  const view = await mountLocations([row]);
  expect(view.text()).toContain('解析失败');
  expect(view.text()).toContain('HTTP 503');
  expect(view.text()).toContain('2026-09-17 09:00:30');
  expect(view.text()).not.toContain('解析中');
  await vi.advanceTimersByTimeAsync(29_999);
  expect(view.api.locations).toHaveBeenCalledTimes(1);
  await vi.advanceTimersByTimeAsync(1); await settle();
  expect(view.api.locations).toHaveBeenCalledTimes(2);
});

it('旧版接口没有解析状态时不假装正在处理，也不无限轮询', async () => {
  vi.useFakeTimers();
  const view = await mountLocations([unresolved(undefined)]);
  expect(view.text()).toContain('尚未解析');
  expect(view.text()).not.toContain('解析中');
  await vi.advanceTimersByTimeAsync(60_000);
  expect(view.api.locations).toHaveBeenCalledTimes(1);
});

it('自动更新请求失败保留现有轨迹，稍后继续重试', async () => {
  vi.useFakeTimers();
  const rows = [unresolved('resolving')];
  const locations = vi.fn().mockResolvedValueOnce({ locations: rows }).mockRejectedValueOnce(new Error('网络暂不可用'))
    .mockResolvedValue({ locations: [{ ...rows[0], address: '恢复后的地址' }] });
  const view = await mountLocations(rows, { locations });
  await vi.advanceTimersByTimeAsync(5000); await settle();
  expect(view.text()).toContain('网络暂不可用');
  expect(view.cells()).toContain('23.1300, 113.2600');
  await vi.advanceTimersByTimeAsync(10_000); await settle();
  expect(view.text()).toContain('恢复后的地址');
});

it('离开页面取消轮询，隐藏页面不继续发送请求', async () => {
  vi.useFakeTimers();
  const view = await mountLocations([unresolved('pending')]);
  Object.assign(document, { hidden: true });
  await vi.advanceTimersByTimeAsync(5000);
  expect(view.api.locations).toHaveBeenCalledTimes(1);
  Object.assign(document, { hidden: false });
  view.unmount();
  await vi.advanceTimersByTimeAsync(60_000);
  expect(view.api.locations).toHaveBeenCalledTimes(1);
});

it('手动刷新后的新结果不会被较慢的自动更新响应覆盖', async () => {
  vi.useFakeTimers();
  const rows = [unresolved('resolving')];
  let resolveOld!: (data: any) => void;
  const locations = vi.fn().mockResolvedValueOnce({ locations: rows })
    .mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }))
    .mockResolvedValueOnce({ locations: [{ ...rows[0], address: '新结果' }] });
  const view = await mountLocations(rows, { locations });
  await vi.advanceTimersByTimeAsync(5000); await settle();
  view.all().find(n => n.tag === 'button' && n.text === '刷新')!.props.onClick(); await settle();
  resolveOld({ locations: rows }); await settle();
  expect(view.text()).toContain('新结果');
  expect(view.text()).not.toContain('解析中');
});
