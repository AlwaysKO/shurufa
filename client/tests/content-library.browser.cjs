// 可选浏览器验收：使用本机已有 Playwright，不增加应用运行依赖。
const { resolve } = require('node:path');
const project = resolve(__dirname, '../..');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const { readFileSync, mkdirSync, writeFileSync } = require('node:fs');
const dotenv = require(resolve(project, 'server/node_modules/dotenv'));
const pg = require(resolve(project, 'server/node_modules/pg'));
const env = dotenv.parse(readFileSync(resolve(project, '.env.local')));
const user = require('node:crypto').randomUUID();
const origin = process.env.DASHBOARD_TEST_ORIGIN || 'http://127.0.0.1:5175';
if (!['localhost','127.0.0.1','::1'].includes(env.PGHOST || 'localhost') || !['localhost','127.0.0.1','[::1]'].includes(new URL(origin).hostname)) throw Error('验收仅允许本地实例与数据库');
const output = resolve(project, 'artifacts/diagnostics/2026-09-15-dashboard-content-library'); mkdirSync(output, {recursive:true});
const assert = (condition, message) => { if (!condition) throw Error(message); };
(async () => {
 const browser = await chromium.launch({headless:true, executablePath:process.env.CHROMIUM_EXECUTABLE,args:['--no-sandbox']});
 const context = await browser.newContext({viewport:{width:1440,height:1080}});
 const page = await context.newPage(); const errors=[]; page.on('pageerror', e=>errors.push(e.message));
 const pool=new pg.Pool({host:env.PGHOST||'localhost',port:Number(env.PGPORT||5432),user:env.PGUSER||'ime',password:env.PGPASSWORD,database:env.PGDATABASE||'personal_ime'});
 let owned=false; let result;
 try {
  const old=await pool.query('SELECT (SELECT count(*) FROM sticker WHERE user_id=$1)+(SELECT count(*) FROM user_phrase WHERE user_id=$1)+(SELECT count(*) FROM sticker_keyword WHERE user_id=$1) AS count',[user]);
  assert(Number(old.rows[0].count)===0,'验收 UUID 已有数据，停止以防误删'); owned=true;
  const login=await context.request.post(origin+'/api/v1/auth/login',{headers:{'X-Dashboard-Request':'1'},data:{username:env.DASHBOARD_USERNAME||'admin',password:env.DASHBOARD_PASSWORD||'adminhaha'}});
  assert(login.status()===200,'本地登录失败 '+login.status());
  await context.addInitScript(user=>localStorage.setItem('shurufa_dashboard_user_id',user),user);
  await page.route('**/api/v1/dashboard/users?*',route=>route.fulfill({json:{total:1,page:1,page_size:1,users:[{id:user,dashboard_name:'界面验收设备',tags:null,brand:'Test',model:'本地隔离验收',last_seen_at:new Date().toISOString()}]}}));
  await page.goto(origin+'/user-phrases');
  await page.getByRole('heading',{name:'常说的话，一键就好'}).waitFor();
  const add=page.getByRole('button',{name:'＋ 添加常用语'});
  for(const content of ['您好，请问在吗？','麻烦发顺丰到付，感谢！']){
   await page.getByLabel('新的常用语',{exact:true}).fill(content); await add.click(); await page.locator('.phrase-body').filter({hasText:content}).waitFor();
  }
  await page.screenshot({path:output+'/phrases-mine-desktop.png',fullPage:true});
  await page.getByTestId('preset-tab').click();
  assert(await page.getByTestId('add-preset').count()===60,'预置短句不是60条');
  const first=page.getByTestId('add-preset').first();await first.click();await page.getByRole('button',{name:'✓ 已加入',exact:true}).waitFor();
  await page.getByRole('button',{name:'工作沟通',exact:true}).click();
  assert(await page.getByTestId('add-preset').count()===10,'分类筛选失败');
  await page.screenshot({path:output+'/phrases-presets-desktop.png',fullPage:true});
  await page.goto(origin+'/stickers');
  await page.getByTestId('keyword-你好').waitFor();
  await page.getByTestId('keyword-你好').click();
  await page.locator('.sticker-preview img').first().waitFor();
  await page.waitForFunction(()=>[...document.querySelectorAll('.sticker-preview img')].every(i=>i.complete && i.naturalWidth>0));
  const images=await page.locator('.sticker-preview img').count();
  await page.screenshot({path:output+'/stickers-desktop.png',fullPage:true});
  await page.getByLabel('新关键词',{exact:true}).fill('验收专用词');await page.getByTestId('add-keyword').click();await page.getByTestId('empty-keyword').waitFor();
  assert((await page.locator('.keyword-heading h3').innerText())==='验收专用词','创建词未打开该组');
  await page.screenshot({path:output+'/stickers-empty-desktop.png',fullPage:true});
  await page.getByTestId('group-upload-input').setInputFiles({name:'browser-check.gif',mimeType:'image/gif',buffer:Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7','base64')});
  await page.getByText('个人上传',{exact:true}).waitFor();
  await page.waitForFunction(()=>[...document.querySelectorAll('.sticker-preview img')].every(i=>i.complete && i.naturalWidth>0));
  await page.reload();await page.getByLabel('搜索关键词',{exact:true}).fill('验收专用词');await page.getByTestId('keyword-验收专用词').click();await page.getByText('个人上传',{exact:true}).waitFor();
  page.on('dialog',d=>d.accept());await page.getByRole('button',{name:'删除',exact:true}).click();await page.getByTestId('empty-keyword').waitFor();
  await page.reload();await page.getByLabel('搜索关键词',{exact:true}).fill('验收专用词');await page.getByTestId('keyword-验收专用词').click();await page.getByTestId('empty-keyword').waitFor();
  await page.getByLabel('搜索关键词',{exact:true}).fill('你好');await page.getByTestId('keyword-你好').click();
  await page.setViewportSize({width:390,height:844});await page.screenshot({path:output+'/stickers-mobile.png',fullPage:true});
  const mobile=await page.evaluate(()=>({viewport:innerWidth,scrollWidth:document.documentElement.scrollWidth,contentWidth:document.querySelector('.content-library').getBoundingClientRect().width}));
  await page.goto(origin+'/user-phrases'); await page.getByTestId('preset-tab').click();
  await page.getByRole('button',{name:'工作沟通',exact:true}).click();
  await page.screenshot({path:output+'/phrases-mobile.png',fullPage:true});
  const phraseWidth = await page.locator('.content-library').evaluate(el=>el.getBoundingClientRect().width);
  assert(phraseWidth >= 330, '窄屏常用语被侧边栏挤压');
  assert(!errors.length,'浏览器存在脚本错误');
  assert(mobile.contentWidth >= 330, '窄屏内容被侧边栏挤压');
  result = {success:true,presetCount:60,displayedSystemImages:images,keywordCreateUploadReloadDeleteRetain:true,pageErrors:errors,mobile,phraseWidth,screenshots:output};
 } catch(error) {
  await page.screenshot({path:output+'/failure.png',fullPage:true});
  console.error('页面诊断',JSON.stringify({url:page.url(),text:(await page.locator('body').innerText()).slice(0,1800),errors}));
  throw error;
 } finally {
  try {
   if(owned){
    const stickers=(await pool.query('SELECT id FROM sticker WHERE user_id=$1',[user])).rows;
    for(const sticker of stickers) {
     const response=await context.request.delete(origin+`/api/v1/dashboard/stickers/${sticker.id}?user_id=${user}`,{headers:{'X-Dashboard-Request':'1'}});
     assert(response.ok(), `删除验收图片失败：HTTP ${response.status()}，图片 ID ${sticker.id}`);
    }
    await pool.query('DELETE FROM sticker_keyword WHERE user_id=$1',[user]);
    await pool.query('DELETE FROM user_phrase WHERE user_id=$1',[user]);
    const remaining=await pool.query('SELECT (SELECT count(*) FROM sticker WHERE user_id=$1)+(SELECT count(*) FROM user_phrase WHERE user_id=$1)+(SELECT count(*) FROM sticker_keyword WHERE user_id=$1) AS count',[user]);
    assert(Number(remaining.rows[0].count)===0, '验收数据未清理完');
   }
  } catch(error) { throw new Error(`本地验收清理失败，请检查隔离用户 ${user} 的残留；不要删除其他用户数据`, {cause:error}); }
  finally { try { await pool.end(); } finally { await browser.close(); } }
 }
 console.log(JSON.stringify({...result,cleanupVerified:true},null,2));
 writeFileSync(output+'/result.json',JSON.stringify({...result,cleanupVerified:true},null,2));
})().catch(e=>{console.error(e);process.exit(1)});
