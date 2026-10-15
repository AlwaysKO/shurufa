// 本地 dist + 虚构接口；所有未知 API/外部请求失败，禁止访问真实业务服务。
// npm --prefix client run build && node client/tests/admin-responsive.browser.cjs
// 可通过 PLAYWRIGHT_MODULE / CHROMIUM_EXECUTABLE 指定已安装的测试工具。
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const { createServer } = require('node:http');
const { readFileSync, existsSync, mkdirSync, writeFileSync } = require('node:fs');
const { resolve, extname } = require('node:path');
const root = resolve(__dirname, '../..');
const dist = resolve(root, 'client/dist');
const output = resolve(root, '.runtime/admin-responsive');
const userId = 'aaaaaaaa-0000-4000-8000-000000000001';
const device = { id: userId, dashboard_name: '响应式虚构设备', brand: 'Test', model: 'Fixture', last_seen_at: null };
const overview = {
  days: 7, today: { input_events: '10', input_chars: '123', clipboard_count: '3', delete_count: '4' },
  period: { event_count: '100', input_chars: '1234', active_days: '7' }, total_chars: '12345',
  source_distribution: { typed: 100, pasted: 20, external: 2, voice: 1, total: 123 },
};
// 从源码核对入口，避免遗漏菜单；各页面数据布局在后续分组用例中扩展。
const routePaths = [...readFileSync(resolve(root, 'client/src/main.ts'), 'utf8').matchAll(/\{ path: '([^']+)'/g)]
  .map(match => match[1]).filter(path => !['/login', '/clipboard-history'].includes(path));

async function assertNoOverflow(page, label) {
  const size = await page.evaluate(() => ({ viewport: innerWidth, document: document.documentElement.scrollWidth }));
  assert(size.document <= size.viewport + 1, `${label} 页面横向溢出：${JSON.stringify(size)}`);
}
async function assertClosed(page, restoreFocus = false) {
  const trigger = page.getByRole('button', { name: '打开导航', exact: true });
  await page.waitForFunction(() => document.querySelector('button[aria-label="打开导航"]')?.getAttribute('aria-expanded') === 'false');
  assert.equal(await trigger.getAttribute('aria-expanded'), 'false', '抽屉未关闭');
  assert.equal(await page.locator('[data-testid="navigation-backdrop"]').isVisible(), false, '关闭后遮罩仍可见');
  if (restoreFocus) assert(await trigger.evaluate(el => el === document.activeElement), '关闭后焦点未返回导航按钮');
  assert(await page.locator('.content').evaluate(el => !el.closest('[inert]')), '关闭后内容仍 inert');
  assert(await page.evaluate(() => getComputedStyle(document.body).overflow !== 'hidden'), '关闭后遗留滚动锁');
}
async function openNavigation(page) {
  const trigger = page.getByRole('button', { name: '打开导航', exact: true });
  await trigger.click();
  assert.equal(await trigger.getAttribute('aria-expanded'), 'true', '未标记菜单展开');
  await page.getByRole('button', { name: '关闭导航', exact: true }).waitFor();
  assert(await page.locator('.sidebar').evaluate(el => el.contains(document.activeElement)), '打开后焦点未进入导航');
  assert(await page.locator('.content').evaluate(el => !!el.closest('[inert]')), '打开后背景内容可交互');
}

(async () => {
  mkdirSync(output, { recursive: true });
  assert(existsSync(resolve(dist, 'index.html')), '请先 npm --prefix client run build');
  const server = createServer((req, res) => {
    const pathname = new URL(req.url, 'http://localhost').pathname;
    if (pathname.startsWith('/api/')) { res.writeHead(500); return res.end('API must be mocked'); }
    const candidate = resolve(dist, '.' + pathname);
    const file = candidate.startsWith(dist + '/') && extname(candidate) && existsSync(candidate) ? candidate : resolve(dist, 'index.html');
    res.setHeader('Content-Type', ({ '.js': 'application/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml' })[extname(file)] || 'application/octet-stream');
    res.end(readFileSync(file));
  });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  let browser, page;
  let logoutAttempts = 0;
  const errors = [], unknownRequests = [], requests = [], checks = [];
  try {
    const origin = `http://127.0.0.1:${server.address().port}`;
    browser = await chromium.launch({ headless: true, executablePath: process.env.CHROMIUM_EXECUTABLE, args: ['--no-sandbox'] });
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, serviceWorkers: 'block' });
    await context.addInitScript(id => localStorage.setItem('shurufa_dashboard_user_id', id), userId);
    await context.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url()), path = url.pathname;
      if (url.origin === origin && !path.startsWith('/api/')) return route.continue();
      requests.push(`${request.method()} ${path}`);
      const json = value => route.fulfill({ json: value });
      if (url.origin === origin) {
        if (request.method() === 'GET' && path === '/api/v1/auth/session') return json({ username: 'fixture' });
        if (request.method() === 'POST' && path === '/api/v1/auth/logout') {
          logoutAttempts++;
          return logoutAttempts === 1 ? route.fulfill({ status: 503, json: { error: '模拟退出失败' } }) : json({ ok: true });
        }
        if (request.method() === 'GET' && path === '/api/v1/dashboard/users') return json({ users: [device], total: 1, page: 1, page_size: 12 });
        if (request.method() === 'GET' && path === '/api/v1/dashboard/overview') return json(overview);
        if (request.method() === 'GET' && path === '/api/v1/dashboard/page-captures') return json({ records: [], total: 0, page_size: 20 });
      }
      unknownRequests.push(`${request.method()} ${request.url()}`);
      return route.fulfill({ status: 500, json: { error: '未预期的测试请求' } });
    });
    page = await context.newPage();
    page.setDefaultTimeout(5000);
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(origin + '/');
    await page.locator('#source-chart canvas').waitFor();
    assert.deepEqual(unknownRequests, [], '夹具缺失');
    assert.deepEqual(errors, [], '浏览器错误');
    const width = await page.locator('.content').evaluate(el => el.clientWidth);
    assert(width >= 360, `390px 手机内容被侧栏挤压：实际 ${width}px，要求 >=360px`);
    await assertNoOverflow(page, '总览 390px');
    checks.push('总览 390px 内容宽度和溢出');
    await page.screenshot({ path: resolve(output, 'overview-390.png'), fullPage: true });

    await openNavigation(page);
    await page.screenshot({ path: resolve(output, 'navigation-390.png'), fullPage: false });
    const close = page.getByRole('button', { name: '关闭导航', exact: true });
    await close.focus();
    await page.keyboard.press('Shift+Tab');
    assert(await page.locator('.sidebar').evaluate(el => el.contains(document.activeElement)), 'Shift+Tab 离开抽屉');
    await page.keyboard.press('Tab');
    assert(await close.evaluate(el => el === document.activeElement), 'Tab 未圈回首按钮');
    await page.keyboard.press('Escape');
    await assertClosed(page, true);
    await openNavigation(page);
    await page.locator('[data-testid="navigation-backdrop"]').click({ position: { x: 385, y: 400 } });
    await assertClosed(page, true);
    await openNavigation(page);
    await page.getByRole('button', { name: '关闭导航', exact: true }).click();
    await assertClosed(page, true);
    checks.push('Esc / 遮罩 / 关闭按钮与焦点恢复');

    await openNavigation(page);
    await page.getByRole('link', { name: '输入总览', exact: true }).click();
    await assertClosed(page);
    await openNavigation(page);
    for (const group of await page.locator('.nav-group').all()) {
      if (!await group.locator('.nav-children').isVisible()) await group.locator('.nav-group-title').click();
    }
    const links = page.locator('.sidebar .nav-item');
    assert.deepEqual((await links.evaluateAll(els => els.map(el => el.getAttribute('href')))).sort(), [...routePaths].sort(), '菜单与路由清单不一致');
    for (const link of await links.all()) {
      await link.scrollIntoViewIfNeeded();
      await link.click({ trial: true });
    }
    await page.getByRole('link', { name: '应用页面', exact: true }).click();
    await page.waitForURL('**/page-captures');
    await page.getByRole('heading', { name: '应用页面', level: 2, exact: true }).waitFor();
    await assertClosed(page);
    checks.push('同路由 / 切换路由关闭；全部导航可达');
    await openNavigation(page);
    await page.locator('.current-user').filter({ hasText: '切换' }).click();
    await page.getByRole('dialog', { name: '选择用户', exact: true }).waitFor();
    assert.equal(await page.locator('[data-testid="navigation-backdrop"]').count(), 0);
    assert(await page.locator('.layout').evaluate(el => el.inert), '目录打开时背景未隔离');
    assert(await page.evaluate(() => getComputedStyle(document.body).overflow === 'hidden'), '目录未锁定背景滚动');
    assert(await page.locator('.directory-dialog').evaluate(el => el.contains(document.activeElement)), '设备目录焦点未接管');
    const directoryClose = page.locator('.directory-dialog .close-button');
    await directoryClose.focus();
    await page.keyboard.press('Shift+Tab');
    assert(await page.locator('.directory-dialog').evaluate(el => el.contains(document.activeElement)), 'Shift+Tab 离开设备目录');
    await page.keyboard.press('Tab');
    assert(await directoryClose.evaluate(el => el === document.activeElement), '目录 Tab 未圈回首按钮');
    await page.locator('.directory-dialog .delete-user').click();
    await page.getByRole('alertdialog').waitFor();
    await page.keyboard.press('Escape');
    await page.getByRole('alertdialog').waitFor({ state: 'hidden' });
    assert(await page.locator('.directory-dialog').isVisible(), '确认框 Esc 错误关闭目录');
    assert(await page.evaluate(() => getComputedStyle(document.body).overflow === 'hidden'), '取消确认后目录丢失滚动锁');
    await page.keyboard.press('Escape');
    await assertClosed(page, true);
    await openNavigation(page);
    await page.locator('.current-user').filter({ hasText: '切换' }).click();
    await page.locator('.directory-dialog .user-select').click();
    await assertClosed(page, true);
    checks.push('设备目录隔离、Tab圈定、确认层交接、Esc及选择设备清锁');

    for (const boundary of [759, 760, 761, 1440, 390]) {
      await page.setViewportSize({ width: boundary, height: 844 });
      const trigger = page.getByRole('button', { name: '打开导航', exact: true });
      if (boundary <= 760) { await trigger.waitFor(); await assertClosed(page); }
      else {
        assert.equal(await trigger.isVisible(), false, `${boundary}px 仍显示移动导航按钮`);
        assert(await page.locator('.sidebar').isVisible(), `${boundary}px 桌面侧栏不可见`);
        assert.equal(Math.round((await page.locator('.sidebar').boundingBox()).width), 224, '桌面侧栏宽度改变');
      }
      await assertNoOverflow(page, `${boundary}px`);
    }
    await openNavigation(page);
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.waitForFunction(() => !document.querySelector('.content')?.closest('[inert]') && getComputedStyle(document.body).overflow !== 'hidden');
    assert(await page.locator('.content').evaluate(el => !el.closest('[inert]')), '切回桌面仍 inert');
    assert(await page.evaluate(() => getComputedStyle(document.body).overflow !== 'hidden'), '切回桌面遗留滚动锁');
    assert(await page.locator('.sidebar .nav-item.active').evaluate(el => el === document.activeElement), '抽屉关闭按钮在桌面隐藏后焦点未转移');
    const desktopDirectoryTrigger = page.locator('.current-user').filter({ hasText: '切换' });
    await desktopDirectoryTrigger.click();
    await page.locator('.directory-dialog .close-button').click();
    assert(await desktopDirectoryTrigger.evaluate(el => el === document.activeElement), '桌面目录关闭未恢复原按钮焦点');
    assert(await page.evaluate(() => getComputedStyle(document.body).overflow !== 'hidden'), '桌面目录关闭遗留滚动锁');
    await page.setViewportSize({ width: 390, height: 844 });
    await assertClosed(page);
    await page.waitForFunction(() => document.activeElement?.getAttribute('aria-label') === '打开导航');
    assert(await page.getByRole('button', { name: '打开导航', exact: true }).evaluate(el => el === document.activeElement), '桌面侧栏隐藏后焦点未转移');
    const contentFilter = page.locator('.content .filters select').first();
    await contentFilter.focus();
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 844 });
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      assert(await contentFilter.evaluate(el => el === document.activeElement), '断点切换抢走内容区焦点');
    }
    checks.push('759 / 760 / 761 断点、宽窄往返、隐藏焦点转移且不抢内容焦点');

    await openNavigation(page);
    await page.getByRole('button', { name: 'fixture · 退出登录', exact: true }).click();
    await page.getByRole('alert').filter({ hasText: '退出失败' }).waitFor();
    assert(await page.getByRole('button', { name: '关闭导航', exact: true }).isVisible(), '退出失败后抽屉被隐藏');
    assert(!page.url().endsWith('/login'), '退出失败却显示为未登录');
    await page.getByRole('button', { name: 'fixture · 退出登录', exact: true }).click();
    await page.waitForURL('**/login');
    assert.equal(await page.locator('.sidebar').count(), 0, '退出后保留私有导航');
    assert.equal(await page.locator('.content').count(), 0, '退出后保留私有内容');
    await assertNoOverflow(page, '登录 390px');
    assert(await page.evaluate(() => getComputedStyle(document.body).overflow !== 'hidden'), '退出后遗留滚动锁');
    assert.deepEqual(unknownRequests, [], '未知 API / 外部请求');
    assert.deepEqual(errors, [], '浏览器错误');
    checks.push('退出失败可见并可重试、成功退出清锁');
    writeFileSync(resolve(output, 'shell-result.json'), JSON.stringify({ ok: true, checks, requests, errors, unknownRequests }, null, 2));
    console.log('PASS: 后台响应式外壳：' + checks.join('；'));
  } catch (error) {
    if (page) await page.screenshot({ path: resolve(output, 'shell-failure.png'), fullPage: true }).catch(() => {});
    writeFileSync(resolve(output, 'shell-result.json'), JSON.stringify({ ok: false, failure: error.message, checks, requests, errors, unknownRequests }, null, 2));
    throw error;
  } finally {
    if (browser) await browser.close();
    await new Promise(r => server.close(r));
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
