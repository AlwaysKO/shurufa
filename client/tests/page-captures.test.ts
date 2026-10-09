import { expect, it } from '../../server/node_modules/vitest/dist/index.js';
import { readFileSync } from 'node:fs';
import { PageCaptureBrowser, pageCaptureListUrl, pageCaptureImageUrl, pageKindLabels, type PageCaptureRow, type PageCaptureQuery } from '../src/pageCaptureBrowser';
const read = (path: string) => readFileSync(new URL('../src/' + path, import.meta.url), 'utf8');
const query = (userId = 'phone-a', page = 1): PageCaptureQuery => ({ userId, page, platform: '', kind: '' });
const row = (id: string): PageCaptureRow => ({ id, platform: 'wechat', kind: 'payment', captured_at: '2026-10-09T00:00:00Z',
  received_at: '2026-10-09T00:01:00Z', width: 500, height: 1000, sha256: 'a'.repeat(64), mime_type: 'image/webp' });
const response = (id: string) => ({ records: [row(id)], total: 1, page_size: 20 });
function deferred<T>() { let resolve!: (v: T) => void, reject!: (e: Error) => void; const promise = new Promise<T>((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; }
it('列表和原图显式携带冻结手机作用域，筛选值编码且不混聊天接口', () => {
  const url = new URL(pageCaptureListUrl({ ...query('phone & b', 2), platform: 'douyin', kind: 'media_feed' }), 'https://example.com');
  expect(url.pathname).toBe('/api/v1/dashboard/page-captures');
  expect(Object.fromEntries(url.searchParams)).toEqual({ user_id: 'phone & b', page: '2', platform: 'douyin', kind: 'media_feed' });
  expect(pageCaptureImageUrl('a/b', 'phone & b')).toBe('/api/v1/dashboard/page-captures/a%2Fb/image?user_id=phone%20%26%20b');
});
it('成功返回列表及总数，选择原图只允许本次列表记录', async () => {
  const browser = new PageCaptureBrowser(async () => response('one'));
  await browser.load(query()); expect(browser.loading).toBe(false); expect(browser.total).toBe(1);
  browser.select(row('foreign')); expect(browser.selected).toBeNull();
  browser.select(browser.rows[0]); expect(browser.selected?.id).toBe('one');
});
it('切换手机立即清旧图且旧响应不能覆盖新手机', async () => {
  const a = deferred<ReturnType<typeof response>>(), b = deferred<ReturnType<typeof response>>();
  const browser = new PageCaptureBrowser(q => q.userId === 'phone-a' ? a.promise : b.promise);
  const first = browser.load(query()); const second = browser.load(query('phone-b'));
  b.resolve(response('b')); await second; browser.select(browser.rows[0]);
  a.resolve(response('a')); await first;
  expect(browser.rows[0].id).toBe('b'); expect(browser.userId).toBe('phone-b'); expect(browser.selected?.id).toBe('b');
});
it('筛选和页码切换也隔离迟到响应/错误', async () => {
  const a = deferred<ReturnType<typeof response>>(); let calls = 0;
  const browser = new PageCaptureBrowser(() => ++calls === 1 ? a.promise : Promise.resolve(response('page2')));
  const old = browser.load(query()); await browser.load({ ...query('phone-a', 2), kind: 'payment' });
  a.reject(Error('old failure')); await old;
  expect(browser.rows[0].id).toBe('page2'); expect(browser.error).toBe(''); expect(browser.loading).toBe(false);
});
it('退出登录或卸载时清空状态，未选择手机不请求', async () => {
  let calls = 0; const d = deferred<ReturnType<typeof response>>();
  const browser = new PageCaptureBrowser(() => { calls++; return d.promise; });
  await browser.load(query('')); expect(calls).toBe(0);
  const old = browser.load(query()); browser.invalidate(); d.resolve(response('late')); await old;
  expect(browser.rows).toEqual([]); expect(browser.selected).toBeNull(); expect(browser.loading).toBe(false);
});
it('当前错误如实显示，重试不会保留旧图', async () => {
  let fail = false;
  const browser = new PageCaptureBrowser(async () => { if (fail) throw Error('连接失败'); return response('one'); });
  await browser.load(query()); browser.select(browser.rows[0]); fail = true;
  await browser.load(query()); expect(browser.error).toBe('连接失败'); expect(browser.rows).toEqual([]); expect(browser.selected).toBeNull();
});
it('图片加载失败可重试，旧列表图片错误不污染新列表', async () => {
  const browser = new PageCaptureBrowser(async () => response('same-id'));
  await browser.load(query()); const old = browser.rows[0];
  browser.imageFailed(old); expect(browser.failedImages).toContain('same-id');
  browser.retryImage(old); expect(browser.failedImages).toEqual([]);
  await browser.load(query('phone-b')); browser.imageFailed(old); expect(browser.failedImages).toEqual([]);
});
it('菜单、路由、图片错误及生命周期保护有生产接线，未伪造视频时长', () => {
  expect(read('App.vue')).toContain("path: '/page-captures'"); expect(read('main.ts')).toContain("path: '/page-captures'");
  const view = read('views/PageCaptures.vue');
  for (const text of ['currentUserId', 'authenticated', 'onBeforeUnmount', 'browser.invalidate()', 'browser.imageFailed', 'browser.retryImage', 'browser.selected']) expect(view).toContain(text);
  expect(read('api/pageCaptures.ts')).toContain('dashboardFetch');
  expect(pageKindLabels.media_feed).toContain('未确认视频'); expect(view).not.toContain('watch_duration');
});
it('响应格式异常不能当作空记录，离开页后迟到错误不更新状态', async () => {
  const browser = new PageCaptureBrowser(async () => ({ records: [], total: -1, page_size: 20 }));
  await browser.load(query()); expect(browser.error).toContain('格式异常');
  const pending = deferred<ReturnType<typeof response>>();
  const leaving = new PageCaptureBrowser(() => pending.promise);
  const work = leaving.load(query()); leaving.invalidate(); pending.reject(Error('late')); await work;
  expect(leaving.error).toBe(''); expect(leaving.loading).toBe(false);
});
it('旧原图错误只标记所属记录，不误标记刚打开的新原图', async () => {
  const browser = new PageCaptureBrowser(async () => ({ records: [row('a'), row('b')], total: 2, page_size: 20 }));
  await browser.load(query()); const old = browser.rows[0];
  browser.select(old); browser.select(browser.rows[1]); browser.imageFailed(old);
  expect(browser.selected?.id).toBe('b'); expect(browser.failedImages).toEqual(['a']);
  const view = read('views/PageCaptures.vue'); expect(view).toContain('@error="browser.imageFailed(selectedRow)"');
  expect(view).not.toContain('@error="browser.selected');
});
