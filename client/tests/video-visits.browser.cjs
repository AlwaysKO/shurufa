// 本地构建产物+完全虚构接口数据；不连接业务服务器/数据库、不使用用户登录凭据。
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const { createServer } = require('node:http');
const { readFileSync, existsSync, mkdirSync, writeFileSync } = require('node:fs');
const { resolve, extname } = require('node:path');
const root = resolve(__dirname, '../..'), dist = resolve(root, 'client/dist');
const output = resolve(root, '.runtime/page-capture-20261009'); mkdirSync(output, {recursive:true});
const A='aaaaaaaa-0000-4000-8000-000000000001', B='bbbbbbbb-0000-4000-8000-000000000002';
const assert=(value,message)=>{if(!value)throw Error(message)};
(async()=>{
 const server=createServer((req,res)=>{
  const path=new URL(req.url,'http://localhost').pathname;
  const candidate=resolve(dist,'.'+path);
  const file=candidate.startsWith(dist+'/')&&existsSync(candidate)&&extname(candidate)?candidate:resolve(dist,'index.html');
  res.setHeader('Content-Type',({'.js':'application/javascript','.css':'text/css','.html':'text/html'})[extname(file)]||'application/octet-stream');res.end(readFileSync(file));
 });
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const origin=`http://127.0.0.1:${server.address().port}`;
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_EXECUTABLE,args:['--no-sandbox']});
 const context=await browser.newContext({viewport:{width:1440,height:1000}});
 const page=await context.newPage(), errors=[], requests=[]; let failImage=true;
 page.on('pageerror',e=>errors.push(e.message));
 await context.addInitScript(id=>localStorage.setItem('shurufa_dashboard_user_id',id),A);
 const device=id=>({id,dashboard_name:id===A?'虚构设备 A':'虚构设备 B',brand:'Test',model:'测试夹具',last_seen_at:'2026-10-09T00:00:00Z',last_data_received_at:null});
 await context.route('**/api/**',async route=>{
  const url=new URL(route.request().url()), path=url.pathname;
  if(path==='/api/v1/auth/session')return route.fulfill({json:{username:'fixture'}});
  if(path==='/api/v1/auth/logout')return route.fulfill({json:{ok:true}});
  if(path==='/api/v1/dashboard/users')return route.fulfill({json:{users:url.searchParams.get('id')?[device(url.searchParams.get('id'))]:[device(A),device(B)],total:2,page:1,page_size:12}});
  if(path==='/api/v1/dashboard/video-visits'){
   assert(route.request().headers()['x-dashboard-request']==='1','列表未使用鉴权请求包装');
   requests.push(Object.fromEntries(url.searchParams));
   const user=url.searchParams.get('user_id'), num=Number(url.searchParams.get('page'));
   const records=Array.from({length:num===1?20:1},(_,i)=>({id:`${user===A?'aaaaaaaa':'bbbbbbbb'}-0000-4000-8000-${String((num-1)*20+i+1).padStart(12,'0')}`,
    observation_kind:i===0?'unconfirmed_feed':'confirmed_video',platform:url.searchParams.get('platform')||'wechat',entered_at:1791504000000,ended_at:i===2?null:1791504010000,duration_ms:i===2?null:10000,
    complete:i!==2,exit_reason:i===0?'page_changed':i===2?'interrupted':'background',first_image_id:`image-${user}-${i}`,last_image_id:null,received_at:'2026-10-09T00:01:00Z'}));
   return route.fulfill({json:{records,total:21,page:num,page_size:20}});
  }
  if(/^\/api\/v1\/dashboard\/page-captures\/[^/]+\/image$/.test(path)){
   if(failImage){failImage=false;return route.fulfill({status:404,body:'fixture missing image'});}
   return route.fulfill({contentType:'image/png',headers:{'Cache-Control':'no-store'},body:Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a7XsAAAAASUVORK5CYII=','base64')});
  }
  throw Error('未预期接口 '+path);
 });
 try{
  await page.goto(origin+'/video-visits');
  await page.getByRole('heading',{name:'视频与信息流停留',level:2,exact:true}).waitFor();
  await page.locator('.cards article').first().waitFor();
  await page.getByRole('button',{name:'重试图片',exact:true}).first().click();
  await page.locator('.cards .preview img').first().waitFor();
  assert(await page.getByText('信息流页面停留（未确认单条视频）',{exact:true}).count()===1,'信息流被误称单条视频');
  assert(await page.getByText('已确认视频',{exact:true}).count()===19,'确认视频标签缺失');
  assert(await page.getByText('共 21 条停留记录',{exact:true}).count()===1,'总数冒称视频数量');
  assert(await page.getByText('结束原因：页面变化',{exact:true}).count()===1,'页面变化语义缺失');
  assert(await page.locator('.cards article').count()===20,'首页不是20条');
  assert(await page.getByText('未知（记录不完整）',{exact:false}).count()===1,'未知时长未显示');
  assert(await page.getByText('尾图缺失',{exact:true}).count()===20,'缺尾图没有如实显示');
  await page.getByRole('button',{name:'查看首图',exact:true}).nth(0).click();
  await page.locator('dialog[open] img').waitFor();
  await page.evaluate(()=>{window.oldPageImage=document.querySelector('dialog[open] img')});
  await page.keyboard.press('Escape');
  await page.getByRole('button',{name:'查看首图',exact:true}).nth(1).click();
  await page.locator('dialog[open] img').waitFor();
  await page.evaluate(()=>window.oldPageImage.dispatchEvent(new Event('error')));
  await page.waitForTimeout(50);
  assert(await page.locator('dialog[open] img').count()===1,'旧弹窗迟到错误污染新原图');
  await page.keyboard.press('Escape');
  await page.getByLabel('应用',{exact:true}).selectOption('douyin');
  await page.getByRole('button',{name:'下一页',exact:true}).click();
  await page.waitForFunction(()=>document.querySelectorAll('.cards article').length===1);
  assert(requests.some(x=>x.platform==='douyin'&&x.page==='2'),'筛选/分页未传递');
  await page.screenshot({path:output+'/video-ui-desktop.png',fullPage:true});
  await page.getByRole('button',{name:'查看首图',exact:true}).click();
  await page.keyboard.press('Escape');
  await page.locator('.current-user').filter({hasText:'切换'}).click();
  await page.locator('.user-select').filter({hasText:'虚构设备 B'}).click();
  await page.waitForFunction(id=>Array.from(document.querySelectorAll('.cards img')).some(x=>x.src.includes(id)),B);
  assert(await page.locator('dialog[open]').count()===0,'切手机未关闭原图');
  assert(!await page.locator(`.cards img[src*="${A}"]`).count(),'切手机仍显示旧手机图片');
  await page.setViewportSize({width:390,height:844});
  assert(await page.locator('.video-visits').evaluate(el=>el.getBoundingClientRect().width>300),'窄屏侧栏挤压视频访问');
  await page.getByRole('heading',{name:'视频与信息流停留',level:2,exact:true}).scrollIntoViewIfNeeded();
  await page.screenshot({path:output+'/video-ui-mobile.png',fullPage:false});
  await page.getByRole('button',{name:'fixture · 退出登录',exact:true}).click();
  await page.waitForURL('**/login');
  assert(await page.locator('.video-visits').count()===0,'退出仍保留私有页面');
  assert(errors.length===0,'浏览器错误 '+errors.join(';'));
  writeFileSync(output+'/video-ui-browser-result.json',JSON.stringify({ok:true,requests,errors},null,2));
  console.log('PASS: 列表/鉴权包装/原图重试/旧弹窗错误隔离/筛选分页/切手机/退出/桌面窄屏截图（虚构接口）');
 }catch(error){await page.screenshot({path:output+'/video-ui-browser-failure.png',fullPage:true});throw error;}
 finally{await browser.close();await new Promise(r=>server.close(r));}
})().catch(error=>{console.error(error);process.exitCode=1});
