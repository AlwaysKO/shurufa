// Local dist and synthetic records only; timestamps must not depend on browser timezone.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const { createServer } = require('node:http');
const { readFileSync, existsSync, mkdirSync } = require('node:fs');
const { resolve, extname } = require('node:path');
const { userId, data: original } = require('./admin-capture.fixtures.cjs');
const dist = resolve(__dirname, '../dist');
const portrait = '<svg xmlns="http://www.w3.org/2000/svg" width="600" height="1332"><rect width="600" height="1332" fill="#315173"/><text x="50" y="120" fill="white" font-size="32">Synthetic test image</text></svg>';
(async () => {
 const server = createServer((req, res) => {
  const path = new URL(req.url, 'http://local').pathname, candidate = resolve(dist, '.' + path);
  const file = candidate.startsWith(dist + '/') && extname(candidate) && existsSync(candidate) ? candidate : resolve(dist, 'index.html');
  res.setHeader('Content-Type', ({ '.js': 'application/javascript', '.css': 'text/css', '.html': 'text/html' })[extname(file)] || 'application/octet-stream'); res.end(readFileSync(file));
 });
 await new Promise(r => server.listen(0, '127.0.0.1', r)); let browser;
 try {
  const origin = `http://127.0.0.1:${server.address().port}`;
  browser = await chromium.launch({ headless: true, executablePath: process.env.CHROMIUM_EXECUTABLE, args: ['--no-sandbox'] });
  for (const [width, timezoneId] of [[1440, 'UTC'], [390, 'America/Los_Angeles']]) {
   const context = await browser.newContext({ viewport: { width, height: 900 }, timezoneId }), errors = [], unknown = [];
   const data = structuredClone(original), first = data['/api/v1/dashboard/chat/messages'].messages[0];
   first.sender_name = '合成测试'; first.text = '时间验证'; first.captured_at = '2026-10-27T17:15:03Z';
   first.metadata = { capture_kind: 'conversation_screenshot' }; first.assets[0].received_at = '2026-10-28T04:32:32Z';
   const missing = structuredClone(first); missing.id = 'message-2'; missing.assets[0].id = 2; delete missing.assets[0].received_at;
   data['/api/v1/dashboard/chat/messages'] = { messages: [first, missing], total: 2, page: 1, page_size: 20 };
   data['/api/v1/dashboard/chat/conversations'].conversations.forEach(c => { c.display_name = '合成测试会话'; });
   await context.addInitScript(id => localStorage.setItem('shurufa_dashboard_user_id', id), userId);
   await context.route('**/*', route => {
    const req = route.request(), u = new URL(req.url());
    if (u.origin !== origin || req.method() !== 'GET') { unknown.push(req.method() + ' ' + u.pathname); return route.abort(); }
    if (u.pathname === '/api/v1/dashboard/chat/assets/1') return route.fulfill({ contentType: 'image/svg+xml', body: portrait });
    if (u.pathname === '/api/v1/dashboard/chat/images/adjacent') return route.fulfill({ json: { image: {
     message_id: 'message-3', asset_id: 3, url: '/api/v1/dashboard/chat/assets/1', alt: '时间验证',
     captured_at: '2026-10-27T16:08:56Z', received_at: '2026-10-28T04:32:13Z', ordinal: 2, total: 3,
    } } });
    if (data[u.pathname]) return route.fulfill({ json: data[u.pathname] });
    if (u.pathname.startsWith('/api/')) { unknown.push(u.pathname); return route.abort(); }
    return route.continue();
   });
   const page = await context.newPage(); page.on('pageerror', e => errors.push(e.message));
   await page.goto(origin + '/chat-capture');
   const times = page.getByTestId('chat-image-times-message-1-1'); await times.waitFor();
   const text = await times.textContent(); assert(text.includes('截图/采集：2026/10/28 01:15:03')); assert(text.includes('上传接收：2026/10/28 12:32:32'));
   assert((await page.getByTestId('chat-image-times-message-2-2').textContent()).includes('上传接收：未知'));
   await page.getByTestId('open-chat-image-1').click();
   const preview = page.getByTestId('chat-preview-times'); await preview.waitFor();
   assert((await preview.textContent()).includes('01:15:03')); assert((await preview.textContent()).includes('12:32:32'));
   await page.getByTestId('chat-image-next').click(); await page.waitForFunction(() => document.querySelector('[data-testid=chat-preview-times]')?.textContent.includes('00:08:56'));
   assert((await preview.textContent()).includes('12:32:13'));
   for (const height of [900, 360]) {
    await page.setViewportSize({ width, height }); const box = await preview.boundingBox();
    assert(box.x >= 0 && box.y >= 0 && box.x + box.width <= width + 1 && box.y + box.height <= height + 1);
   }
   await page.setViewportSize({ width, height: 900 });
   if (process.env.CHAT_TIMES_SCREENSHOT_DIR) { mkdirSync(process.env.CHAT_TIMES_SCREENSHOT_DIR, { recursive: true }); await page.screenshot({ path: resolve(process.env.CHAT_TIMES_SCREENSHOT_DIR, `preview-${width}.png`) }); }
   await page.getByTestId('close-chat-image-preview').click();
   assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false);
   assert.deepEqual(errors, []); assert.deepEqual(unknown, []);
   await context.close(); console.log(`chat image timestamps ${width}px / ${timezoneId} passed`);
  }
 } finally { await browser?.close(); await new Promise(r => server.close(r)); }
})().catch(e => { console.error(e); process.exitCode = 1; });
