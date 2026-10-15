// Real built router, synthetic read-only APIs; simulate missing deployment chunks.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const { createServer } = require('node:http');
const { readFileSync, existsSync } = require('node:fs');
const { resolve, extname } = require('node:path');
const { data } = require('./admin-pages.fixtures.cjs');
const dist = resolve(__dirname, '../dist');
(async () => {
 const server = createServer((req, res) => {
  const path = new URL(req.url, 'http://local').pathname;
  if (path.startsWith('/api/')) { res.writeHead(500); return res.end('Unexpected API'); }
  const candidate = resolve(dist, '.' + path);
  const file = candidate.startsWith(dist + '/') && extname(candidate) && existsSync(candidate) ? candidate : resolve(dist, 'index.html');
  res.setHeader('Content-Type', ({ '.js':'application/javascript', '.css':'text/css', '.html':'text/html' })[extname(file)] || 'application/octet-stream');
  res.end(readFileSync(file));
 });
 await new Promise(r => server.listen(0, '127.0.0.1', r));
 let browser;
 try {
  const origin = `http://127.0.0.1:${server.address().port}`;
  browser = await chromium.launch({ headless:true, executablePath:process.env.CHROMIUM_EXECUTABLE, args:['--no-sandbox'] });
  const context = await browser.newContext();
  const unknown = [], errors = [];
  await context.route('**/*', route => {
   const url = new URL(route.request().url());
   if (url.origin === origin && !url.pathname.startsWith('/api/')) return route.continue();
   const fixture = url.pathname === '/api/v1/auth/login-records' ? { records:[], total:0, page:1 } : data[url.pathname];
   if (url.origin === origin && route.request().method() === 'GET' && fixture) return route.fulfill({ json:fixture });
   unknown.push(url.pathname); return route.abort();
  });
  for (const width of [1440, 390]) {
   const page = await context.newPage();
   page.on('pageerror', e => errors.push(e.message));
   await page.setViewportSize({ width, height:900 });
   await page.goto(origin);
   async function openGroup(name) {
    if (width < 760) await page.getByRole('button', { name:'打开导航', exact:true }).click();
    const group = page.getByRole('button', { name, exact:false }).locator('..');
    if (!await group.locator('.nav-children').isVisible()) await group.locator('.nav-group-title').click();
   }
   for (const [path, title, group] of [
    ['/apps','APP 分布','输入分析'], ['/phrases','高频词句','输入分析'],
    ['/wordcloud','词云','输入分析'], ['/completion','智能补全','输入分析'],
    ['/login-records','登录记录','后台安全'],
   ]) {
    await openGroup(group);
    await page.locator(`a[href="${path}"]`).click();
    await page.waitForURL(origin + path);
    assert.equal(await page.locator('.page-title').innerText(), title);
    await page.locator('.navigation-loading').waitFor({ state:'hidden' });
    assert.equal(await page.locator('.navigation-error').count(), 0);
   }
   await page.close();
  }
  for (const initial of [false, true]) {
   const page = await context.newPage();
   let blocked = true;
   await page.route('**/assets/LoginRecords-*.js', route => blocked
    ? route.fulfill({ contentType:'text/html', body:'<html>new release</html>' }) : route.continue());
   await page.goto(origin + (initial ? '/login-records' : '/'));
   if (!initial) {
    await page.getByRole('button', { name:'后台安全' }).click();
    await page.locator('a[href="/login-records"]').click();
   }
   await page.getByRole('alert').filter({ hasText:'页面加载失败' }).waitFor();
   if (!initial) assert.equal(new URL(page.url()).pathname, '/');
   blocked = false;
   await page.getByRole('button', { name:'刷新并打开目标页面' }).click();
   await page.waitForURL(origin + '/login-records');
   await page.locator('.login-records').waitFor();
   assert.equal(await page.locator('.navigation-error').count(), 0);
   await page.close();
  }
  assert.deepEqual(errors, []); assert.deepEqual(unknown, []);
  console.log('PASS: desktop/mobile analysis and login navigation; failed lazy navigation and initial-load recovery');
 } finally { await browser?.close(); await new Promise(r => server.close(r)); }
})().catch(e => { console.error(e); process.exitCode = 1; });
