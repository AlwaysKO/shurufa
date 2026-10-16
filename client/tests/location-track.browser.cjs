// 真 Leaflet + 本地 dist + 显式虚构 fixtures；所有未知 API/外网请求拒绝。
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const { createServer } = require('node:http');
const { readFileSync, existsSync, mkdirSync, writeFileSync } = require('node:fs');
const { resolve, extname } = require('node:path');
const { userId, data } = require('./admin-pages.fixtures.cjs');
const output = resolve(process.env.OUTPUT || resolve(__dirname, '../../.runtime/usage-location-quality/task5/browser'));
const dist = resolve(__dirname, '../dist');
const stamp = '2026-10-15T00:00:00Z';
const row = (id, minute, lat = 23.13, device_id = userId) => ({id,device_id,latitude:String(lat),longitude:'113.26',accuracy:'5',provider:'gps',speed:null,address:id === 'b' ? '<img src=x onerror="window.pwned=1">重叠B' : `虚构地址${id}`,occurred_at:new Date(Date.parse(stamp)+minute*60000).toISOString(),first_seen_at:stamp,last_seen_at:stamp,address_status:'resolved'});
const initial = [row('c',2,23.13108),row('b',1),row('d',3,23.13,'other'),row('a',0)];
const device = {...data['/api/v1/dashboard/devices'].devices[0],latest_usage_at:'2026-10-15T02:10:00Z',latest_usage_source:'usage_screen_off',last_app_activity_at:'2026-10-15T02:08:00Z',last_app_activity_source:'usage_pause',last_app_activity_received_at:'2026-10-15T02:45:00Z',last_screen_off_at:'2026-10-15T02:10:00Z',last_screen_off_source:'usage_screen_off',last_screen_off_received_at:'2026-10-15T02:45:00Z',last_interaction_at:'2026-10-15T01:54:00Z',last_interaction_source:'usage_interaction'};
const tile='<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256"><rect width="256" height="256" fill="#edf2f7"/></svg>';
(async()=>{
 mkdirSync(output,{recursive:true});
 const server=createServer((req,res)=>{
  const path=new URL(req.url,'http://local').pathname;
  if(path.startsWith('/api/')){res.writeHead(500);return res.end('fixture required');}
  const candidate=resolve(dist,'.'+path),file=candidate.startsWith(dist+'/')&&extname(candidate)&&existsSync(candidate)?candidate:resolve(dist,'index.html');
  res.setHeader('Content-Type',({'.js':'application/javascript','.css':'text/css','.html':'text/html','.svg':'image/svg+xml'})[extname(file)]||'application/octet-stream');res.end(readFileSync(file));
 });
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const origin=`http://127.0.0.1:${server.address().port}`;
 let browser,page;const checks=[],unknown=[],errors=[];
 try{
  browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_EXECUTABLE,args:['--no-sandbox']});
  for(const width of [1280,390]){
   let rows=structuredClone(initial),calls=0;
   rows[0].address=null;rows[0].address_status='pending';rows[2].address=null;rows[2].address_status='pending';
   const context=await browser.newContext({viewport:{width,height:900},hasTouch:width===390,serviceWorkers:'block'});
   await context.addInitScript(id=>localStorage.setItem('shurufa_dashboard_user_id',id),userId);
   await context.route('**/*',route=>{
    const req=route.request(),url=new URL(req.url());
    if(url.origin===origin&&!url.pathname.startsWith('/api/'))return route.continue();
    if(/^[abc]\.tile\.openstreetmap\.org$/.test(url.hostname)&&req.method()==='GET')return route.fulfill({contentType:'image/svg+xml',body:tile});
    if(url.origin===origin&&req.method()==='GET'){
     if(url.pathname==='/api/v1/dashboard/locations'){
      calls++;return route.fulfill({json:{locations:rows.filter(r=>!url.searchParams.get('device_id')||r.device_id===url.searchParams.get('device_id'))}});
     }
     if(url.pathname==='/api/v1/dashboard/devices')return route.fulfill({json:{devices:[device,{id:'other',dashboard_name:'其他手机'}]}});
     if(url.pathname==='/api/v1/dashboard/users')return route.fulfill({json:{users:[device,{id:'other',dashboard_name:'旧版未知手机'}],total:2,page:1}});
     if(['/api/v1/auth/session','/api/v1/dashboard/overview','/api/v1/dashboard/timeline'].includes(url.pathname))return route.fulfill({json:data[url.pathname]});
    }
    unknown.push(req.method()+' '+req.url());return route.fulfill({status:500,json:{error:'未预期请求'}});
   });
   page=await context.newPage();page.setDefaultTimeout(8000);page.on('pageerror',e=>errors.push(e.message));
   await page.goto(origin+'/locations');await page.locator('.location-hit').first().waitFor();
   assert.equal(await page.locator('.location-hit').count(),4);assert.equal(await page.locator('.location-route').count(),1);
   assert.equal(await page.locator('.location-start').count(),2);assert.equal(await page.locator('.location-end').count(),2);
   assert.equal(await page.locator('.location-direction').count(),1);
   assert.match(await page.locator('.location-direction span').getAttribute('style'),/rotate\(-90deg\)/);
   assert.equal(await page.locator('.location-direction').evaluate(el=>getComputedStyle(el).pointerEvents),'none');
   // 使用SVG真实命中圈外沿（距中心15px，视觉点只有5/8px），覆盖鼠标及触控。
   await page.locator('#loc-map').scrollIntoViewIfNeeded();
   const hit=await page.locator('.location-hit').first().boundingBox();assert(hit.width>=40);
   if(width===390)await page.touchscreen.tap(hit.x+hit.width/2+15,hit.y+hit.height/2);
   else await page.mouse.click(hit.x+hit.width/2+15,hit.y+hit.height/2);
   await page.locator('[data-testid="location-candidate"]').first().waitFor();
   const buttons=page.locator('[data-testid="location-candidate"]');
   const labels=await buttons.allTextContents();assert(labels.length>=3);assert(labels[0].includes('08:00:00'));assert(labels[1].includes('08:01:00'));
   const target=buttons.filter({hasText:'重叠B'});await target.click();
   await page.waitForFunction(()=>document.querySelectorAll('.leaflet-popup-content').length===1);
   await page.locator('.leaflet-popup-content').waitFor();
   assert((await page.locator('.leaflet-popup-content').innerText()).includes('<img src=x'));
   assert.equal(await page.locator('.leaflet-popup-content img').count(),0);assert.equal(await page.evaluate(()=>window.pwned),undefined);
   assert.equal(await target.getAttribute('aria-pressed'),'true');
   assert.equal(await page.locator('.location-point[stroke="#f59e0b"]').count(),1);
   const assertHighlightOnTop = async () => {
    const paint = await page.locator('.location-point').evaluateAll(nodes => nodes.map(n => ({stroke:n.getAttribute('stroke'),last:n.parentElement.lastElementChild===n,interactive:n.classList.contains('leaflet-interactive')})));
    assert.equal(paint.at(-1).stroke,'#f59e0b','重叠点选中样式必须最后绘制，不能被其他设备遮住');
    assert.equal(paint.at(-1).last,true,'高亮必须处于SVG绘制顶层');
    assert.equal(paint.at(-1).interactive,false,'置顶视觉层不得截获命中圈点击');
   };
   await assertHighlightOnTop();
   await page.locator('.leaflet-control-zoom-in').click();await page.waitForTimeout(350);
   const geometry=()=>page.locator('.location-hit').evaluateAll(nodes=>nodes.map(n=>n.getAttribute('d')));
   const before=await geometry();const previousCalls=calls;
   rows=rows.map(r=>r.id==='b'?{...r,address:'更新后的B地址'}:r);
   await page.waitForFunction(()=>document.querySelector('[data-testid="selected-location"]')?.textContent.includes('更新后的B地址'),null,{timeout:8000});
   assert(calls>previousCalls);assert.deepEqual(await geometry(),before,'后台刷新不得改变缩放或中心');
   await page.waitForFunction(()=>document.querySelectorAll('.leaflet-popup-content').length===1);
   assert((await page.locator('.leaflet-popup-content').innerText()).includes('更新后的B地址'));
   await assertHighlightOnTop();
   await page.screenshot({path:resolve(output,`map-${width}.png`),fullPage:true});
   await page.locator('.leaflet-popup-close-button').click();
   await page.locator('.leaflet-popup').waitFor({state:'detached'});
   rows=rows.map(r=>r.id==='b'?{...r,address:'关闭弹窗后的B地址'}:r);
   await page.waitForFunction(()=>document.querySelector('[data-testid="selected-location"]')?.textContent.includes('关闭弹窗后的B地址'),null,{timeout:8000});
   assert.equal(await page.locator('.leaflet-popup').count(),0,'地址更新不应重新打开用户关闭的弹窗');
   await assertHighlightOnTop();
   rows=rows.filter(r=>r.id!=='b');
   await page.waitForFunction(()=>!document.querySelector('[data-testid="selected-location"]'),null,{timeout:8000});
   await page.locator('.leaflet-popup').waitFor({state:'detached'});
   rows.push(row('gap',23,23.132));
   await page.locator('.location-gap').waitFor({state:'attached'});
   assert.equal(await page.locator('.location-gap').count(),1);
   assert.equal(await page.locator('.location-gap').getAttribute('stroke-dasharray'),'6 8');
   assert.equal(await page.locator('.location-gap').evaluate(el=>el.classList.contains('leaflet-interactive')),false);
   assert((await page.getByTestId('location-gap-hint').innerText()).includes('期间缺少定位点，路线未知'));
   assert.equal(await page.locator('.location-route').count(),1,'缺口不能合并连续路线');
   assert.equal(await page.locator('.location-direction').count(),1,'缺口不能生成路线方向');
   await page.screenshot({path:resolve(output,`gap-${width}.png`),fullPage:true});
   // 筛选变化清空旧设备选择。
   await page.locator('.toolbar select').selectOption('other');
   await page.waitForFunction(()=>document.querySelectorAll('.location-hit').length===1);
   assert.equal(await page.locator('[data-testid="location-candidate"]').count(),0);
   assert.equal(await page.locator('.location-route').count(),0);
   assert.equal(await page.locator('.location-gap').count(),0);
   checks.push(`${width}:真实命中外沿/重叠选择/转义/高亮/时间方向分段/自动刷新保留缩放与弹窗开关/删除清选择/设备切换`);
   // 目录与已有响应式外壳入口。
   if(width===390)await page.getByRole('button',{name:'打开导航',exact:true}).click();
   await page.locator('.current-user').filter({hasText:'切换'}).click();
   await page.getByRole('dialog',{name:'选择用户',exact:true}).waitFor();
   await page.locator('.user-row-time').first().waitFor();
   assert((await page.locator('.directory-dialog').innerText()).includes('最近使用记录 · 熄屏'));
   await page.locator('.usage-evidence summary').first().click();
   assert.equal(await page.locator('.usage-evidence small').first().evaluate(el=>getComputedStyle(el).display),'block','收到时间须独立成行');
   assert(await page.locator('.usage-evidence').first().evaluate(el=>el.getBoundingClientRect().width>=el.parentElement.getBoundingClientRect().width-30),'分项证据必须独占一行');
   const evidence=await page.locator('.usage-evidence').first().innerText();
   assert(evidence.includes('2026-10-15 10:08:00'));assert(evidence.includes('服务器收到：2026-10-15 10:45:00'));assert(evidence.includes('最近记录的交互'));assert(evidence.includes('最近锁屏：未知'));
   assert((await page.locator('.user-row').nth(1).innerText()).includes('未知（未收到记录）'));
   assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
   await page.screenshot({path:resolve(output,`directory-${width}.png`),fullPage:true});
   await page.locator('.directory-header .close-button').click();
   // 经Vue路由离开，而非整页导航，检查旧页面轮询释放。
   if(width===390&&!await page.locator('.sidebar').isVisible())await page.getByRole('button',{name:'打开导航',exact:true}).click();
   await page.locator('a[href="/"]').first().click();await page.locator('#loc-map').waitFor({state:'detached'});
   const afterLeave=calls;await page.waitForTimeout(5500);assert.equal(calls,afterLeave,'卸载后仍在轮询');
   assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
   checks.push(`${width}:目录真实时间/收到时间/旧字段未知/无溢出/路由卸载无残留请求`);
   await context.close();
  }
  assert.deepEqual(unknown,[]);assert.deepEqual(errors,[]);
  writeFileSync(resolve(output,'result.json'),JSON.stringify({ok:true,checks,unknown,errors},null,2));console.log(checks.join('\n'));
 }catch(e){if(page)await page.screenshot({path:resolve(output,'failure.png'),fullPage:true}).catch(()=>{});writeFileSync(resolve(output,'result.json'),JSON.stringify({ok:false,error:e.stack,checks,unknown,errors},null,2));throw e;}
 finally{await browser?.close();await new Promise(r=>server.close(r));}
})().catch(e=>{console.error(e);process.exitCode=1;});
