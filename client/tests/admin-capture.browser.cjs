// 严格本地 dist + 虚构接口，所有未知 API/外网/写操作拒绝，不访问业务服务。
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const { createServer } = require('node:http');
const { readFileSync, existsSync, mkdirSync, writeFileSync } = require('node:fs');
const { resolve, extname } = require('node:path');
const { userId, data, pages } = require('./admin-capture.fixtures.cjs');
const dist = resolve(__dirname,'../dist'), output = resolve(__dirname,'../../.runtime/admin-capture');
const portrait = '<svg xmlns="http://www.w3.org/2000/svg" width="600" height="1332"><rect width="600" height="1332" fill="#315173"/></svg>';
(async()=>{
 mkdirSync(output,{recursive:true});
 const server=createServer((req,res)=>{const path=new URL(req.url,'http://local').pathname; if(path.startsWith('/api/')){res.writeHead(500);return res.end('mock required');} const candidate=resolve(dist,'.'+path),file=candidate.startsWith(dist+'/')&&extname(candidate)&&existsSync(candidate)?candidate:resolve(dist,'index.html');res.setHeader('Content-Type',({'.js':'application/javascript','.css':'text/css','.html':'text/html'})[extname(file)]||'application/octet-stream');res.end(readFileSync(file));});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const origin=`http://127.0.0.1:${server.address().port}`, failures=[],unknown=[],errors=[],checks=[],requests=[]; let browser, failSave=true, scenario='populated';
 try {
  browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_EXECUTABLE,args:['--no-sandbox']});
  const context=await browser.newContext({serviceWorkers:'block'});await context.addInitScript(id=>localStorage.setItem('shurufa_dashboard_user_id',id),userId);
  await context.route('**/*',route=>{
   const req=route.request(),url=new URL(req.url()),path=url.pathname,method=req.method();
   if(url.origin===origin&&!path.startsWith('/api/'))return route.continue();
   if(url.origin===origin){
    requests.push({path,method,query:Object.fromEntries(url.searchParams)});
    if(method==='GET'&&path==='/api/v1/dashboard/chat/conversations/1/resolve'&&scenario==='empty')return route.fulfill({json:{conversation:null}});
    if(method==='GET'&&Object.hasOwn(data,path)){
     if(!['/api/v1/auth/session','/api/v1/dashboard/users','/api/v1/dashboard/devices'].includes(path)){
      if(scenario==='error')return route.fulfill({status:503,json:{error:'模拟加载失败'}});
      if(scenario==='empty'){const value=structuredClone(data[path]);for(const key of Object.keys(value))if(Array.isArray(value[key]))value[key]=[];if('total' in value)value.total=0;if(value.current?.rules)value.current.rules=[];return route.fulfill({json:value});}
     }
     return route.fulfill({json:data[path]});
    }
    if(method==='GET'&&(/^\/api\/v1\/dashboard\/(page-captures|video-visits|navigation-records)\/aaaaaaaa-0000-4000-8000-000000000011\/(image|first-image)$/.test(path)||path==='/api/v1/dashboard/chat/assets/1'))return route.fulfill({contentType:'image/svg+xml',body:portrait});
    if(method==='PATCH'&&/^\/api\/v1\/dashboard\/(page-captures|video-visits)\/aaaaaaaa-0000-4000-8000-000000000011$/.test(path)){
     const body=req.postDataJSON();assert.deepEqual(Object.keys(body).sort(),['note','title']);if(failSave){failSave=false;return route.fulfill({status:503,json:{error:'模拟保存失败',message:'模拟保存失败'}});}return route.fulfill({json:{ok:true}});
    }
    if(method==='PUT'&&['/api/v1/dashboard/settings/chat-capture','/api/v1/dashboard/settings/expression-delivery'].includes(path)){
     if(failSave){failSave=false;return route.fulfill({status:503,json:{error:'模拟保存失败',message:'模拟保存失败'}});}return route.fulfill({json:{current:{...data[path].current,revision:2,rules:req.postDataJSON().rules},history:[]}});
    }
    if(method==='POST'&&['/api/v1/dashboard/retention/input/preview','/api/v1/dashboard/retention/navigation/preview'].includes(path))return route.fulfill({json:{token:'fixture',total_records:1,total_files:1,cutoff:'2026-10-01T00:00:00Z',first_at:'2026-09-01T00:00:00Z',last_at:'2026-09-30T00:00:00Z'}});
    if(method==='POST'&&/^\/api\/v1\/dashboard\/(page-captures|video-visits)\/cleanup\/preview$/.test(path))return route.fulfill({json:{token:'fixture',total:1,cutoff:'2026-10-01T00:00:00Z'}});
   }
   unknown.push(method+' '+req.url());return route.fulfill({status:500,json:{error:'未预期请求'}});
  });
  const page=await context.newPage();page.setDefaultTimeout(5000);page.on('pageerror',e=>errors.push(e.message));
  async function fit(label){const m=await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth,offenders:[...document.querySelectorAll('.content *')].filter(e=>e.getBoundingClientRect().right>innerWidth+1).slice(0,5).map(e=>e.tagName+'.'+e.className)}));if(m.scroll>m.width+1)failures.push(label+' 溢出 '+JSON.stringify(m));const spills=await page.locator('.cards,.platforms,.fields,.form-row,.collector-form,.capture-management .editor').evaluateAll(els=>els.flatMap(el=>{const b=el.getBoundingClientRect(),s=getComputedStyle(el);return [...el.children].filter(c=>c.getBoundingClientRect().right>b.right-parseFloat(s.paddingRight)+1).map(c=>el.className+' > '+c.tagName)}));if(spills.length)failures.push(label+' 内容超出容器 '+spills.join(','));checks.push(label);}
  for(const [path,ready] of pages){
   try {
    await page.setViewportSize({width:1440,height:900});await page.goto(origin+path);await page.locator(ready).first().waitFor();
    for(const width of [1440,768,390,360]){await page.setViewportSize({width,height:844});await fit(path+' '+width);if(width===390||width===1440)await page.screenshot({path:resolve(output,path.slice(1)+'-'+width+'.png'),fullPage:true});}
    await page.goto(origin+path);await page.locator(ready).first().waitFor();await fit(path+' 首次360');
    if(path==='/chat-capture'){
     const boxes=await page.locator('.capture-layout').evaluate(el=>{const [a,b]=el.children;return {stacked:b.getBoundingClientRect().top>=a.getBoundingClientRect().bottom,list:el.querySelector('.conversation-list').getBoundingClientRect().height}});assert(boxes.stacked&&boxes.list<=151,'会话列表未上下排列/有界');
     await page.getByTestId('chat-select-listed-conversations').click();await page.getByTestId('chat-delete-selected-conversations').click();await page.getByRole('alertdialog').waitFor();await page.getByTestId('confirmation-cancel').click();
     for(const viewport of [{width:360,height:844},{width:844,height:360}]){
      await page.setViewportSize(viewport);await page.locator('.media-preview-button').first().click();
      const close=page.locator('.image-preview-close'), box=await close.boundingBox();assert(box.x>=0&&box.y>=0&&box.x+box.width<=viewport.width&&box.y+box.height<=viewport.height,'聊天图片关闭屏外');await close.click();checks.push('chat 预览 '+viewport.width);
     }
    }
    if(['page-captures','video-visits','navigation-records'].includes(path.slice(1))){
     for(const viewport of [{width:360,height:844},{width:844,height:360}]){
      await page.setViewportSize(viewport);await page.locator('.preview').first().click();const dialog=page.locator('dialog[open]');await dialog.waitFor();await dialog.locator('img').evaluate(img=>img.decode());
      const box=await dialog.boundingBox();if(box.x<0||box.y<0||box.x+box.width>viewport.width+1||box.y+box.height>viewport.height+1||Math.abs(box.x+box.width/2-viewport.width/2)>2||Math.abs(box.y+box.height/2-viewport.height/2)>2)failures.push(path+' 弹窗未屏内居中 '+JSON.stringify({viewport,box}));
      const close=dialog.getByRole('button',{name:'关闭',exact:true}),c=await close.boundingBox();if(c.x<0||c.y<0||c.x+c.width>viewport.width||c.y+c.height>viewport.height)failures.push(path+' 关闭按钮屏外');
      await page.screenshot({path:resolve(output,path.slice(1)+'-preview-'+viewport.width+'.png')});await close.click();checks.push(path+' 预览 '+viewport.width);
     }
    }
    await page.setViewportSize({width:390,height:844});
    if(['/page-captures','/video-visits'].includes(path)){
     await page.getByLabel('开始日期',{exact:true}).fill('2026-10-01');await page.getByLabel('结束日期',{exact:true}).fill('2026-10-14');
     await page.getByRole('button',{name:'编辑名称备注',exact:true}).first().click();await page.getByLabel('记录名称',{exact:true}).fill('编辑测试');await page.getByLabel('记录备注',{exact:true}).fill('失败后保留草稿');await fit(path+' 编辑');failSave=true;
     await page.getByRole('button',{name:'保存名称备注',exact:true}).click();await page.getByRole('alert').filter({hasText:'模拟保存失败'}).waitFor();assert.equal(await page.getByLabel('记录备注',{exact:true}).inputValue(),'失败后保留草稿');await page.getByRole('button',{name:'保存名称备注',exact:true}).click();await page.getByLabel('记录备注',{exact:true}).waitFor({state:'hidden'});
     await page.getByRole('button',{name:'全选当前页',exact:true}).click();await page.getByRole('button',{name:'删除所选（1）',exact:true}).click();await page.getByRole('button',{name:'取消删除',exact:true}).click();
     await page.getByRole('button',{name:'预览清理',exact:true}).click();await page.getByRole('region',{name:'清理预览'}).waitFor();await page.getByRole('button',{name:'取消清理',exact:true}).click();assert(requests.some(r=>r.path==='/api/v1/dashboard'+path&&r.query.from==='2026-10-01'&&r.query.to==='2026-10-14'),'日期筛选缺失');checks.push(path+' 日期/编辑失败重试/批量删除取消/清理取消');
    }
    if(['/chat-capture-settings','/expression-delivery'].includes(path)){
     const field=path==='/chat-capture-settings'?page.getByLabel('聊天采集适配规则 JSON'):page.getByLabel('精确版本名（空为不限）');
     const draft=path==='/chat-capture-settings'?JSON.stringify([{...data['/api/v1/dashboard/settings/chat-capture'].current.rules[0],enabled:false}]):'8.0.79';await field.fill(draft);
     failSave=true;const save=page.getByRole('button',{name:path==='/chat-capture-settings'?'保存全部规则':'保存全部 App 配置',exact:true});await save.click();await page.getByRole('alert').filter({hasText:'模拟保存失败'}).waitFor();assert.equal(await field.inputValue(),draft,'配置保存失败丢草稿');await save.click();await page.getByRole('status').waitFor();checks.push(path+' 保存失败重试');
    }
    if(['/data','/navigation-records'].includes(path)){await page.getByTestId('retention-keep-7').click();await page.getByRole('alertdialog').waitFor();await page.getByTestId('confirmation-cancel').click();checks.push(path+' 保留期限确认取消');}
    if(path==='/data'){await page.locator('.confirm-input').fill('DELETE');await page.getByRole('button',{name:'确认清理',exact:true}).click();await page.getByRole('alertdialog').waitFor();await page.getByTestId('confirmation-cancel').click();checks.push('data 清理取消');}
    await page.setViewportSize({width:844,height:360});await fit(path+' 短横屏');
    for(const state of ['empty','error']){
     scenario=state;await page.setViewportSize({width:360,height:844});await page.goto(origin+path);await page.waitForLoadState('networkidle');await fit(path+' '+state);scenario='populated';
    }
   }catch(e){failures.push(path+' '+e.message);}
  }
  assert.equal(unknown.length,0,unknown.join('\n'));assert.equal(errors.length,0,errors.join('\n'));
 }finally{writeFileSync(resolve(output,'result.json'),JSON.stringify({checks,failures,unknown,errors,requests},null,2));if(browser)await browser.close();await new Promise(r=>server.close(r));}
 assert.equal(failures.length,0,failures.join('\n'));console.log('PASS admin-capture '+checks.length+' 检查');
})().catch(e=>{console.error(e);process.exitCode=1;});
