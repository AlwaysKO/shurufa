// 本地 dist、显式 GET fixtures；未知 API/外网/写操作全部拒绝。
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const { createServer } = require('node:http');
const { readFileSync, existsSync, mkdirSync, writeFileSync } = require('node:fs');
const { resolve, extname } = require('node:path');
const { userId, data, pages, usagePages } = require('./admin-pages.fixtures.cjs');
const dist = resolve(__dirname, '../dist'), output = resolve(__dirname, '../../.runtime/admin-pages');
const tile = '<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256"><rect width="256" height="256" fill="#e8edf1"/><path d="M0 128H256M128 0V256" stroke="#cbd5df"/><text x="20" y="36" fill="#6b7785" font-size="14">Mock map tile</text></svg>';
(async () => {
 mkdirSync(output, { recursive: true });
 const server = createServer((req, res) => {
  const path = new URL(req.url, 'http://local').pathname;
  if (path.startsWith('/api/')) { res.writeHead(500); return res.end('mock required'); }
  const candidate = resolve(dist, '.' + path), file = candidate.startsWith(dist + '/') && extname(candidate) && existsSync(candidate) ? candidate : resolve(dist, 'index.html');
  res.setHeader('Content-Type', ({'.js':'application/javascript','.css':'text/css','.html':'text/html','.png':'image/png','.svg':'image/svg+xml'})[extname(file)] || 'application/octet-stream'); res.end(readFileSync(file));
 });
 await new Promise(r => server.listen(0, '127.0.0.1', r));
 let scenario = 'populated', pagination = false, failPage = null;
 const paginationRequests = [];
 let browser; const failures = [], checks = [], unknown = [], errors = [];
 try {
  const origin = `http://127.0.0.1:${server.address().port}`;
  browser = await chromium.launch({ headless: true, executablePath: process.env.CHROMIUM_EXECUTABLE, args: ['--no-sandbox'] });
  const context = await browser.newContext({ serviceWorkers: 'block' });
  await context.addInitScript(id => localStorage.setItem('shurufa_dashboard_user_id', id), userId);
  await context.route('**/*', route => {
   const req = route.request(), url = new URL(req.url());
   if (url.origin === origin && !url.pathname.startsWith('/api/')) return route.continue();
   if (/^[abc]\.tile\.openstreetmap\.org$/.test(url.hostname) && req.method() === 'GET') return route.fulfill({ contentType: 'image/svg+xml', body: tile });
   if (url.origin === origin && req.method() === 'GET' && Object.hasOwn(data, url.pathname)) {
    if (pagination) {
     paginationRequests.push(url.pathname + url.search);
     if (url.pathname === '/api/v1/dashboard/app-usage') {
      const number = Number(url.searchParams.get('page') || 1);
      if (![1, 2].includes(number)) throw new Error('未预期分页');
      if (failPage === number) return route.fulfill({ status: 503, json: { error: '模拟分页失败' } });
      return route.fulfill({ json: usagePages[number - 1] });
     }
    }
    const stable = ['/api/v1/auth/session', '/api/v1/dashboard/users', '/api/v1/dashboard/devices'];
    if (scenario === 'error' && !stable.includes(url.pathname)) return route.fulfill({ status: 503, json: { error: '模拟加载失败' } });
    const value = structuredClone(data[url.pathname]);
    if (scenario === 'empty' && !stable.includes(url.pathname)) {
     for (const key of Object.keys(value)) if (Array.isArray(value[key])) value[key] = [];
     if ('total' in value) value.total = 0;
    }
    return route.fulfill({ json: value });
   }
   unknown.push(req.method() + ' ' + req.url()); return route.fulfill({ status: 500, json: { error: '未预期的测试请求' } });
  });
  const page = await context.newPage(); page.setDefaultTimeout(7000);
  page.on('pageerror', e => errors.push(e.message));
  for (const [path, ready] of pages) {
   try {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto(origin + path); await page.locator(ready).first().waitFor();
    // 默认 ECharts 初始动画 1000ms；词云布局也须完成后才检查/截图。
    await page.waitForTimeout(1500);
    for (const width of [1440, 768, 390, 360, 1440]) {
     await page.setViewportSize({ width, height: 900 }); await page.waitForTimeout(350);
     const label = `${path} ${width}`;
     const metrics = await page.evaluate(() => ({ width: innerWidth, scroll: document.documentElement.scrollWidth, offenders: [...document.querySelectorAll('.content *')].filter(e => e.getBoundingClientRect().right > innerWidth + 1 && !e.closest('.table-scroll,.table-wrap,.timeline-scroll')).slice(0, 5).map(e => e.tagName + '.' + e.className) }));
     if (metrics.scroll > width + 1) failures.push(`${label} 全页溢出 ${JSON.stringify(metrics)}`);
     for (const chart of await page.locator('.chart').all()) {
      if (!await chart.isVisible() || !await chart.locator('canvas').count()) continue;
      const size = await chart.evaluate(el => ({ container: el.clientWidth, canvas: el.querySelector('canvas').getBoundingClientRect().width }));
      if (Math.abs(size.container - size.canvas) > 2) failures.push(`${label} 图表未 resize ${JSON.stringify(size)}`);
     }
     for (const table of await page.locator('.content table').all()) {
      const readable = await table.evaluate(el => ({
       narrowHeaders: [...el.querySelectorAll('th')].filter(th => (() => { const range = document.createRange(); range.selectNodeContents(th); return range.getClientRects().length > 1; })()).map(th => th.textContent),
       narrowContent: [...el.querySelectorAll('td')].filter(td => td.innerText.length > 40 && td.clientWidth < 100).map(td => ({ column: td.cellIndex + 1, width: td.clientWidth, text: td.innerText.slice(0, 80) })),
      }));
      if (readable.narrowHeaders.length || readable.narrowContent.length) failures.push(`${label} 表格列不可读 ${JSON.stringify(readable)}`);
      const state = await table.evaluate(el => {
       let p = el.parentElement; while (p && !['auto','scroll'].includes(getComputedStyle(p).overflowX)) p = p.parentElement;
       if (!p) return { scrollable: false, width: el.getBoundingClientRect().width };
       const keyboard = p.tabIndex >= 0;
       p.scrollLeft = p.scrollWidth;
       const last = el.querySelector('tbody tr td:last-child');
       return { scrollable: true, keyboard, overflowing: p.scrollWidth > p.clientWidth + 1, reachable: !last || last.getBoundingClientRect().right <= p.getBoundingClientRect().right + 1 };
      });
      if (state.overflowing && !state.keyboard) failures.push(`${label} 滚动容器不可键盘聚焦`);
      if (state.width > width || state.reachable === false) failures.push(`${label} 末列不可达 ${JSON.stringify(state)}`);
     }
     if (width === 390 && ['/activity', '/call-recordings'].includes(path)) {
      const action = path === '/activity' ? page.getByTestId('delete-activity-1') : page.locator('.calls > .table-wrap tbody tr').first().getByRole('button', { name: '删除', exact: true });
      await action.click(); await page.getByRole('alertdialog').waitFor();
      await page.getByTestId('confirmation-cancel').click();
      checks.push(label + ' 末列操作确认后取消');
      await page.screenshot({ path: resolve(output, path.slice(1) + '-390-last-column.png'), fullPage: true });
     }
     await page.locator('.table-scroll,.table-wrap').evaluateAll(els => els.forEach(el => { el.scrollLeft = 0; }));
     checks.push(label);
     if (width === 390 || width === 1440) await page.screenshot({ path: resolve(output, (path === '/' ? 'overview' : path.slice(1)) + `-${width}.png`), fullPage: true });
    }
    await page.setViewportSize({ width: 390, height: 900 });
    await page.goto(origin + path); await page.locator(ready).first().waitFor(); await page.waitForTimeout(1500);
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), `${path} 手机首次加载溢出`);
    if (path === '/completion') {
     const region = page.getByRole('region', { name: '补全候选，可横向滚动' });
     await region.focus(); await page.keyboard.press('ArrowRight'); await page.waitForTimeout(150);
     assert(await region.evaluate(el => el.scrollLeft > 0), '补全表格键盘无法横向滚动');
     checks.push('补全表格键盘横向滚动');
    }
    checks.push(path + ' 手机首次加载');
   } catch (e) { failures.push(`${path} 加载/检查失败：${e.message}`); }
  }
  // 手机真实点击两页及失败重试：只换明细，不重载统计/时间轴、不跳回页顶。
  pagination = true;
  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto(origin + '/app-usage');
  await page.locator('.usage-records').getByText('分页测试第1页', { exact: false }).waitFor();
  await page.locator('.timeline-lane').waitFor();
  const records = page.locator('.usage-records');
  const summary = page.locator('.summary');
  const summaryBefore = await summary.innerText();
  await summary.evaluate(el => { window.__paginationSummary = el; });
  await page.locator('.timeline-grid').evaluate(el => { window.__paginationTimeline = el; });
  const next = records.getByRole('button', { name: '下一页', exact: true });
  await next.scrollIntoViewIfNeeded();
  const scrollBefore = await page.evaluate(() => scrollY);
  paginationRequests.length = 0;
  await next.click();
  await records.getByText('分页测试第2页', { exact: false }).waitFor();
  const assertLocalPage = async expectedPage => {
   assert.equal(paginationRequests.length, 1, '翻页不能附带重载其他接口');
   const request = new URL(paginationRequests[0], origin);
   assert.equal(request.pathname, '/api/v1/dashboard/app-usage');
   assert.equal(request.searchParams.get('page'), String(expectedPage));
   assert.equal(await summary.innerText(), summaryBefore, '翻页覆盖了上方统计');
   assert(await summary.evaluate(el => el === window.__paginationSummary), '统计 DOM 被重建');
   assert(await page.locator('.timeline-grid').evaluate(el => el === window.__paginationTimeline), '时间轴 DOM 被重建');
  };
  await assertLocalPage(2);
  assert(Math.abs(await page.evaluate(() => scrollY) - scrollBefore) <= 2, '成功翻页改变页面滚动位置');
  checks.push('AppUsage 390 下一页仅明细请求、统计/时间轴保留、滚动保持');
  failPage = 1; paginationRequests.length = 0;
  await records.getByRole('button', { name: '上一页', exact: true }).click();
  await records.getByRole('alert').waitFor();
  await assertLocalPage(1);
  assert(await records.getByText('分页测试第2页', { exact: false }).isVisible(), '分页失败丢失当前记录');
  assert(await page.evaluate(() => scrollY > 0), '分页失败跳回页顶');
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'AppUsage 分页错误文本造成全页溢出');
  checks.push('AppUsage 390 局部分页失败保留记录/统计/时间轴');
  failPage = null; paginationRequests.length = 0;
  await records.getByRole('button', { name: '上一页', exact: true }).click();
  await records.getByText('分页测试第1页', { exact: false }).waitFor();
  await assertLocalPage(1);
  assert.equal(await records.getByRole('alert').count(), 0, '分页重试成功仍遗留错误');
  checks.push('AppUsage 390 分页失败后重试恢复');
  pagination = false;
  // 图表、普通列表、表格、地图、关系表单各一代表：空态/失败不撑宽，恢复真实页面内容。
  for (const [path, emptyText, errorText, ready] of [
   ['/wordcloud', '暂无数据', '加载失败', '#wordcloud-chart canvas'],
   ['/user-phrases', '把常说的话收藏在这里', '加载失败', '.phrase-item'],
   ['/clipboard', '暂无复制记录', '加载失败', '#clipboard-chart canvas'],
   ['/locations', '暂无位置数据', '503', '.leaflet-tile-loaded'],
   ['/relationships', '暂无可建档会话', '加载关系档案失败', '.profile-form'],
  ]) {
   for (const mode of ['empty', 'error']) {
    scenario = mode; await page.goto(origin + path);
    try {
     await page.locator('.content').getByText(mode === 'empty' ? emptyText : errorText, { exact: false }).first().waitFor();
     assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), `${path} ${mode} 溢出`);
     checks.push(`${path} ${mode}`);
    } catch (e) { failures.push(`${path} ${mode}: ${e.message}`); }
   }
   scenario = 'populated';
   // 有重试按钮直接重试，其余通过现有路由重新加载，不模拟业务写操作。
   const retry = page.getByRole('button', { name: '重试', exact: true });
   if (await retry.count()) await retry.first().click(); else await page.goto(origin + path);
   await page.locator(ready).first().waitFor(); checks.push(path + ' 恢复');
  }
  assert.deepEqual(unknown, [], '未知请求'); assert.deepEqual(errors, [], '页面 JS 错误');
  assert.deepEqual(failures, [], '响应式回归失败');
 } finally {
  writeFileSync(resolve(output, 'result.json'), JSON.stringify({ checks, failures, unknown, errors }, null, 2));
  console.log(JSON.stringify({ checks: checks.length, failures, unknown, errors }, null, 2));
  await browser?.close(); await new Promise(r => server.close(r));
 }
})().catch(e => { console.error(e.message); process.exitCode = 1; });
