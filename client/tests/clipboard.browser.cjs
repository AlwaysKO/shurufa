// Real clipboard views with synthetic read-only APIs, including failed requests.
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
   let failHistory = false, failStatistics = false;
   await page.route('**/api/v1/dashboard/events?*', route => failHistory
    ? route.fulfill({status:503,json:{error:'历史暂时不可用'}})
    : route.fulfill({json:data['/api/v1/dashboard/events']}));
   await page.route('**/api/v1/dashboard/clipboard?*', route => failStatistics
    ? route.fulfill({status:503,json:{error:'统计暂时不可用'}})
    : route.fulfill({json:data['/api/v1/dashboard/clipboard']}));
   await page.goto(origin + '/clipboard');
   await page.locator('.list > .row').first().waitFor();
   assert.equal(await page.locator('.sidebar a[href="/clipboard"]').count(),1);
   assert.equal(await page.locator('.sidebar a[href="/clipboard-history"]').count(),0);
   failHistory=true;
   await page.getByRole('button',{name:'搜索',exact:true}).click();
   await page.getByRole('alert').filter({hasText:'503'}).waitFor();
   failHistory=false;
   await page.getByRole('button',{name:'重试',exact:true}).click();
   await page.locator('.list > .row').first().waitFor();
   failStatistics=true;
   await page.getByRole('link',{name:'统计分析',exact:true}).click();
   await page.getByRole('alert').filter({hasText:'503'}).waitFor();
   failStatistics=false;
   await page.getByRole('button',{name:'重试',exact:true}).click();
   await page.locator('#clipboard-chart canvas').waitFor();
   await page.getByRole('link',{name:'历史记录',exact:true}).click();
   await page.locator('.list > .row').first().waitFor();
   await page.goto(origin + '/clipboard-history');
   await page.waitForURL(origin + '/clipboard');
   await page.locator('.list > .row').first().waitFor();
   assert.equal(await page.locator('.page-title').innerText(),'剪贴板');
   await page.close();
  }
  assert.deepEqual(errors, []); assert.deepEqual(unknown, []);
  console.log('PASS: single clipboard menu, history/statistics tabs, failed API retry, legacy redirect on desktop/mobile');
 } finally { await browser?.close(); await new Promise(r => server.close(r)); }
})().catch(e => { console.error(e); process.exitCode = 1; });
