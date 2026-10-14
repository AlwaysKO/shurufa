// 严格隔离：本地 dist 服务、白名单 API、外网拒绝；不读取 .env、不访问数据库。
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict');
const {createServer}=require('node:http');
const {readFileSync,existsSync,mkdirSync,writeFileSync}=require('node:fs');
const {resolve,extname}=require('node:path');
const overviewData=require('./admin-pages.fixtures.cjs').data;
const {userId,long,image,device,group,material,asset,data}=require('./admin-assets.fixtures.cjs');
(async()=>{
 const dist=resolve(__dirname,'../dist'),output=resolve(__dirname,'../../.runtime/admin-assets');mkdirSync(output,{recursive:true});
 const server=createServer((req,res)=>{const p=new URL(req.url,'http://local').pathname;if(p.startsWith('/api/')){res.writeHead(500);return res.end('mock required');}const candidate=resolve(dist,'.'+p),file=candidate.startsWith(dist+'/')&&extname(candidate)&&existsSync(candidate)?candidate:resolve(dist,'index.html');res.setHeader('Content-Type',({'.js':'application/javascript','.css':'text/css','.html':'text/html'})[extname(file)]||'application/octet-stream');res.end(readFileSync(file));});await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const origin=`http://127.0.0.1:${server.address().port}`,checks=[],failures=[],unknown=[],errors=[],requests=[];let browser,authenticated=true,failLogin=true,scenario='populated',phrases=[{id:1,content:long,use_count:1}];
 try{
 browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_EXECUTABLE,args:['--no-sandbox']});
 const context=await browser.newContext({serviceWorkers:'block',hasTouch:true});await context.addInitScript(id=>localStorage.setItem('shurufa_dashboard_user_id',id),userId);
 await context.route('**/*',route=>{const req=route.request(),url=new URL(req.url()),p=url.pathname,m=req.method();if(url.origin===origin&&!p.startsWith('/api/'))return route.continue();if(url.origin===origin){requests.push({path:p,method:m,body:req.postData(),query:Object.fromEntries(url.searchParams)});if(p==='/api/v1/auth/session'&&!authenticated)return route.fulfill({status:401,json:{error:'未登录'}});if(m==='GET'&&['/api/v1/dashboard/overview','/api/v1/dashboard/hours','/api/v1/dashboard/heatmap','/api/v1/dashboard/apps'].includes(p))return route.fulfill({json:overviewData[p]});if(m==='GET'&&Object.hasOwn(data,p)){
 if(!['/api/v1/auth/session','/api/v1/dashboard/users','/api/v1/dashboard/devices'].includes(p)){
 if(scenario==='error')return route.fulfill({status:503,json:{error:'虚构加载失败'}});
 if(scenario==='empty'){const value=structuredClone(data[p]);for(const key of Object.keys(value))if(Array.isArray(value[key]))value[key]=[];if('total'in value)value.total=0;return route.fulfill({json:value});}}
 return route.fulfill({json:data[p]});}
 if(p==='/api/v1/dashboard/user-phrases'){
 if(m==='GET')return route.fulfill({json:{phrases}});
 if(m==='POST'){const phrase={id:2,content:req.postDataJSON().content,use_count:0};phrases.push(phrase);return route.fulfill({json:phrase});}}
 if(m==='PATCH'&&p==='/api/v1/dashboard/user-phrases/2'){phrases[1].content=req.postDataJSON().content;return route.fulfill({json:{ok:true}});}
 if(m==='POST'&&p==='/api/v1/dashboard/sticker-materials/match')return route.fulfill({json:{items:req.postDataJSON().sha256s.map(sha256=>({sha256,status:'missing'}))}});
 if(m==='POST'&&p==='/api/v1/dashboard/sticker-materials')return route.fulfill({json:{status:'imported',material:{...material,sha256:url.searchParams.get('sha256')}}});
 if(m==='PATCH'&&p===`/api/v1/dashboard/sticker-materials/${material.sha256}/keywords`)return route.fulfill({json:{material:{...material,keywords:['测试']}}});
 if(m==='POST'&&p==='/api/v1/auth/login'){if(failLogin)return route.fulfill({status:401,json:{error:'账号或密码错误'}});authenticated=true;return route.fulfill({json:{username:'fixture'}});}
 if(m==='POST'&&p==='/api/v1/dashboard/dictionary/sync')return route.fulfill({json:{ok:true,words:1,queued:1,skipped:0,devices:1}});
 if(m==='PATCH'&&p===`/api/v1/dashboard/users/${userId}`)return route.fulfill({json:{user:{...device,...req.postDataJSON()}}});
 if(m==='PATCH'&&p==='/api/v1/dashboard/sticker-groups/'+encodeURIComponent('测试'))return route.fulfill({json:{group}});
 if(m==='PATCH'&&p==='/api/v1/dashboard/synthesis-library/mine')return route.fulfill({json:{asset:{...asset,...req.postDataJSON()}}});
 }
 unknown.push(m+' '+req.url());return route.abort('blockedbyclient');});
 const page=await context.newPage();page.setDefaultTimeout(6000);page.on('pageerror',e=>errors.push(e.message));
 async function fit(label){await page.waitForTimeout(50);const info=await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth,overflow:[...document.querySelectorAll('body *')].filter(el=>{const r=el.getBoundingClientRect();return r.width&&r.right>innerWidth+1&&!el.closest('aside')}).map(el=>[el.tagName,el.className,Math.round(el.getBoundingClientRect().right)]).slice(0,12)}));if(info.scroll>info.width+1)failures.push(label+' 页面横向溢出 '+JSON.stringify(info));else checks.push(label);}
 for(const path of ['/stickers','/sticker-materials','/ai-synthesis','/personal-dictionary','/login']){
 try{authenticated=path!='/login';await page.setViewportSize({width:360,height:844});await page.goto(origin+path);await page.waitForLoadState('networkidle');
 for(const width of [360,390,768,1440]){await page.setViewportSize({width,height:844});await fit(path+' '+width);await page.screenshot({path:resolve(output,path.slice(1)+'-'+width+'.png'),fullPage:true});}
 await page.setViewportSize({width:844,height:360});await fit(path+' 短横屏');
 await page.setViewportSize({width:360,height:844});
 if(path==='/stickers'){
  await page.getByTestId('edit-group-aliases').click();await fit('关键词编辑 360');await page.getByTestId('group-alias-input-0').fill('测试');await page.getByTestId('save-group-aliases').click();await page.locator('.alias-editor').waitFor({state:'hidden'});
  await page.getByRole('button',{name:'图片后移',exact:true}).first().tap();await page.getByTestId('save-sticker-order').click();await page.getByTestId('delete-keyword-group').click();await page.getByRole('alertdialog').waitFor();await fit('关键词删除确认');await page.keyboard.press('Escape');checks.push('关键词编辑/触摸排序/删除取消');
 }
 if(path==='/sticker-materials'){
  await page.getByLabel('搜索素材关键词').fill('测试');await page.locator('.material-filters').getByRole('button',{name:'搜索',exact:true}).click();await page.waitForLoadState('networkidle');await fit('素材筛选');
  await page.getByRole('button',{name:'全选当前页',exact:true}).click();await page.getByRole('button',{name:'删除选中',exact:true}).click();await page.getByRole('alertdialog').waitFor();await page.keyboard.press('Escape');checks.push('素材筛选/全选/批量删除取消');
  await page.getByLabel('搜索已有推荐词').fill('测试');await page.getByRole('button',{name:'测试',exact:true}).click();await page.waitForLoadState('networkidle');await fit('素材推荐词关联');
  await page.getByLabel('选择批量上传图片',{exact:true}).setInputFiles({name:'虚构上传.gif',mimeType:'image/gif',buffer:Buffer.from(image.split(',')[1],'base64')});await page.getByText('（本批结束）',{exact:false}).waitFor();await fit('素材批量上传结果');assert(requests.some(r=>r.path==='/api/v1/dashboard/sticker-materials'&&r.method==='POST'));checks.push('素材推荐词关联/原图上传');
 }
 if(path==='/ai-synthesis'){
  await page.getByTestId('edit-synthesis-mine').click();for(const width of [360,390,768,1440]){await page.setViewportSize({width,height:844});await fit('合成编辑 '+width);}
  await page.setViewportSize({width:360,height:844});await page.getByTestId('synthesis-name').fill('修改底图');await page.getByTestId('safe-x').fill('12');
  assert.equal(await page.locator('.safe-overlay').evaluate(el=>el.style.left),'5%');await page.getByTestId('save-synthesis').click();await page.getByTestId('synthesis-form').waitFor({state:'hidden'});assert.equal(JSON.parse(requests.findLast(r=>r.path.endsWith('/synthesis-library/mine')).body).textSafeArea.x,12);checks.push('合成编辑坐标仍为原图像素');
  await page.getByTestId('select-all-synthesis').click();await page.getByTestId('delete-selected-synthesis').click();await page.getByRole('alertdialog').waitFor();await page.setViewportSize({width:844,height:360});await fit('合成长名称删除确认');
  const close=page.getByTestId('confirmation-cancel');await close.scrollIntoViewIfNeeded();const box=await close.boundingBox();assert(box.y>=0&&box.y+box.height<=360,'长确认按钮屏外');await close.click();checks.push('合成批量删除取消/短横屏长确认');
 }
 if(path==='/personal-dictionary'){
  await page.getByTestId('target-'+userId).check();await page.getByTestId('select-page').click();await page.getByTestId('sync-selected').click();await page.getByRole('status').waitFor();assert.deepEqual(JSON.parse(requests.findLast(r=>r.path.endsWith('/dictionary/sync')).body),{device_ids:[userId],texts:['测试词']});checks.push('词库指定目标增量同步');
  const scroller=page.locator('.table-scroll');await scroller.evaluate(el=>el.scrollLeft=el.scrollWidth);await page.getByTestId('delete-0').click();await page.getByRole('alertdialog').waitFor();await page.keyboard.press('Escape');await fit('词库末列操作');checks.push('词库横向滚动末列删除取消');
  await page.getByRole('button',{name:'打开导航',exact:true}).click();await page.locator('.current-user').filter({hasText:'切换'}).click();await page.locator('.edit-user').click();if(await page.locator('.user-row-main strong').evaluate(el=>el.scrollWidth>el.clientWidth+1))failures.push('设备目录长名称被裁切');else checks.push('设备目录长名称完整换行');
  for(const viewport of [{width:360,height:844},{width:844,height:360}]){await page.setViewportSize(viewport);await fit('设备目录编辑 '+viewport.width);const save=page.locator('.edit-panel').getByRole('button',{name:'保存',exact:true});await save.scrollIntoViewIfNeeded();const b=await save.boundingBox();if(b.y<0||b.y+b.height>viewport.height)failures.push('设备目录编辑保存按钮屏外 '+viewport.width);else checks.push('设备目录编辑保存可达 '+viewport.width);}
  await page.getByPlaceholder('用户名称，如：上海客服 025').fill('虚构修改');await page.locator('.edit-panel').getByRole('button',{name:'保存',exact:true}).click();await page.locator('.edit-panel').waitFor({state:'hidden'});await page.locator('.delete-user').click();await page.getByRole('alertdialog').waitFor();await page.keyboard.press('Escape');await page.keyboard.press('Escape');checks.push('设备编辑/删除取消');
 }
 if(path==='/login'){
  await page.getByLabel('账号',{exact:true}).fill('fixture');await page.getByLabel('密码',{exact:true}).fill('fake-password');await page.getByLabel('密码',{exact:true}).press('Enter');await page.getByRole('alert').waitFor();assert.equal(await page.getByLabel('账号',{exact:true}).inputValue(),'fixture');await page.setViewportSize({width:360,height:420});await page.getByLabel('密码',{exact:true}).focus();await fit('登录软键盘缩短可视区');await page.getByRole('button',{name:'登录',exact:true}).scrollIntoViewIfNeeded();await fit('登录失败保留账号');failLogin=false;await page.getByLabel('密码',{exact:true}).press('Enter');await page.waitForURL(origin+'/');checks.push('登录键盘失败重试成功');
 }

 }catch(e){failures.push(path+' '+e.message);}
 }
 authenticated=true;await page.setViewportSize({width:360,height:844});await page.goto(origin+'/user-phrases');await page.getByLabel('新的常用语').fill('虚构新增短句');await page.getByRole('button',{name:'＋ 添加常用语',exact:true}).click();await page.getByTestId('edit-phrase-2').click();await page.getByTestId('edit-phrase-input').fill('虚构修改短句');await page.getByTestId('edit-phrase-input').press('Enter');await page.getByText('虚构修改短句',{exact:true}).waitFor();await page.locator('.phrase-item').last().getByRole('button',{name:'删除',exact:true}).click();await page.getByRole('alertdialog').waitFor();await page.keyboard.press('Escape');await fit('常用语新增编辑');checks.push('常用语新增/键盘编辑/删除取消');
 for(const path of ['/stickers','/sticker-materials','/ai-synthesis','/personal-dictionary'])for(const state of ['empty','error']){scenario=state;await page.goto(origin+path);await page.waitForLoadState('networkidle');await fit(path+' '+state);if(state==='error')assert(await page.getByRole('alert').count()>0,path+' 未提示加载失败');}scenario='populated';
 assert.equal(unknown.length,0,unknown.join('\n'));assert.equal(errors.length,0,errors.join('\n'));
 }finally{writeFileSync(resolve(output,'result.json'),JSON.stringify({checks,failures,unknown,errors,requests},null,2));if(browser)await browser.close();await new Promise(r=>server.close(r));}
 assert.equal(failures.length,0,failures.join('\n'));console.log('PASS admin-assets '+checks.length+' 检查');
})().catch(e=>{console.error(e);process.exitCode=1;});
