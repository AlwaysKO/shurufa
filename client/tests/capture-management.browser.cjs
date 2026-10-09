// 全部数据及登录均为本机虚构夹具，不连接生产。
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const {createServer}=require('node:http');
const {readFileSync,existsSync,mkdirSync}=require('node:fs');
const {resolve,extname}=require('node:path');
const root=resolve(__dirname,'../..'),dist=resolve(root,'client/dist'),output=resolve(root,'.runtime/page-capture-20261009');mkdirSync(output,{recursive:true});
const A='aaaaaaaa-0000-4000-8000-000000000001',B='bbbbbbbb-0000-4000-8000-000000000002';
const assert=(v,m)=>{if(!v)throw Error(m)};
(async()=>{
 const server=createServer((req,res)=>{const path=new URL(req.url,'http://localhost').pathname,candidate=resolve(dist,'.'+path),file=candidate.startsWith(dist+'/')&&existsSync(candidate)&&extname(candidate)?candidate:resolve(dist,'index.html');res.setHeader('Content-Type',({'.js':'application/javascript','.css':'text/css','.html':'text/html'})[extname(file)]||'application/octet-stream');res.end(readFileSync(file));});await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_EXECUTABLE,args:['--no-sandbox']});
 try{for(const resource of ['page-captures','video-visits']){
  const context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage();const requests=[],errors=[];let failEdit=true,failDelete=true,cleanupBatches=0,paginated=false,lastDeleted=false;
  const rows=Array.from({length:3},(_,i)=>({id:`aaaaaaaa-0000-4000-8000-${String(i+1).padStart(12,'0')}`,title:'测试名称 '+i,note:'备注 '+i,platform:'douyin',kind:'payment',captured_at:'2026-10-09T00:00:00Z',received_at:'2026-10-09T00:01:00Z',width:600,height:1332,sha256:'a'.repeat(64),mime_type:'image/webp',observation_kind:'confirmed_video',entered_at:1791504000000,ended_at:1791504010000,duration_ms:10000,complete:true,exit_reason:'locked',first_image_id:null,last_image_id:null}));
  page.on('pageerror',e=>errors.push(e.message));await context.addInitScript(id=>localStorage.setItem('shurufa_dashboard_user_id',id),A);
  const device=id=>({id,dashboard_name:id===A?'夹具 A':'夹具 B',brand:'Test',model:'Fixture',last_seen_at:'2026-10-09T00:00:00Z'});
  await context.route('**/api/**',async route=>{
   const req=route.request(),url=new URL(req.url()),path=url.pathname,body=req.postDataJSON();
   if(path==='/api/v1/auth/session')return route.fulfill({json:{username:'fixture'}});
   if(path==='/api/v1/dashboard/users')return route.fulfill({json:{users:url.searchParams.get('id')?[device(url.searchParams.get('id'))]:[device(A),device(B)],total:2,page:1,page_size:12}});
   if(path.endsWith('/image'))return route.fulfill({contentType:'image/svg+xml',body:'<svg xmlns="http://www.w3.org/2000/svg" width="600" height="1332"><rect width="600" height="1332" fill="#315173"/></svg>'});
   assert(req.headers()['x-dashboard-request']==='1','鉴权包装缺失');assert(url.searchParams.get('user_id')===A||url.searchParams.get('user_id')===B,'手机作用域缺失');requests.push({path,method:req.method(),body,query:Object.fromEntries(url.searchParams)});
   if(path===`/api/v1/dashboard/${resource}`){const num=Number(url.searchParams.get('page'));return route.fulfill({json:{records:paginated&&num===2?(lastDeleted?[]:[rows.at(-1)]):rows,total:paginated?(lastDeleted?20:21):rows.length,page:num,page_size:20}});}
   if(req.method()==='PATCH'){assert(Object.keys(body).sort().join(',')==='note,title','编辑触碰了原始观看字段');if(failEdit){failEdit=false;return route.fulfill({status:503,json:{error:'夹具保存失败'}});}Object.assign(rows.find(r=>path.endsWith(r.id)),body);return route.fulfill({json:{ok:true}});}
   if(path.endsWith('/delete')){assert(body.confirm==='DELETE','删除缺确认');if(failDelete){failDelete=false;return route.fulfill({status:503,json:{error:'夹具删除失败'}});}if(paginated)lastDeleted=true;for(const id of body.ids){const index=rows.findIndex(r=>r.id===id);if(index>=0)rows.splice(index,1);}return route.fulfill({json:{deleted:body.ids.length}});}
   if(path.endsWith('/cleanup/preview')){assert(body.days===7,'保留天数错误');assert(body.q==='搜索名称','清理未冻结搜索范围');return route.fulfill({json:{token:'fixture-token',total:3,cutoff:'2026-10-02T00:00:00Z'}});}
   if(path.endsWith('/cleanup/batch')){cleanupBatches++;assert(body.confirm==='DELETE'&&body.token==='fixture-token','清理确认错误');assert(body.offset===(cleanupBatches===1?0:2),'分批游标错误');if(cleanupBatches===2)return route.fulfill({status:503,json:{error:'夹具清理失败'}});return route.fulfill({json:{processed:cleanupBatches===1?2:3,total:3,deleted:cleanupBatches===1?1:2,skipped:1,done:cleanupBatches>1}});}
   throw Error('未预期接口 '+path);
  });
  await page.goto(`http://127.0.0.1:${server.address().port}/${resource}`);await page.locator('.cards article').first().waitFor();
  if(resource==='page-captures')assert(!await page.getByLabel('页面类型',{exact:true}).locator('option[value="media_feed"]').count(),'应用页仍列信息流类型');
  await page.getByLabel('开始日期',{exact:true}).fill('2026-10-01');await page.getByLabel('结束日期',{exact:true}).fill('2026-10-09');await page.getByLabel('名称或备注',{exact:true}).fill('搜索名称');await page.getByLabel('名称或备注',{exact:true}).press('Enter');
  if(resource==='video-visits'){await page.getByLabel('记录类型',{exact:true}).selectOption('confirmed_video');await page.getByLabel('结束状态',{exact:true}).selectOption('true');await page.getByLabel('结束原因',{exact:true}).selectOption('locked');}
  await page.getByRole('button',{name:'编辑名称备注',exact:true}).first().click();await page.getByLabel('记录名称',{exact:true}).fill('已编辑名称');await page.getByLabel('记录备注',{exact:true}).fill('已编辑备注');await page.getByRole('button',{name:'保存名称备注',exact:true}).click();await page.getByRole('alert').filter({hasText:'夹具保存失败'}).waitFor();assert(await page.getByLabel('记录备注',{exact:true}).inputValue()==='已编辑备注','保存失败丢编辑内容');await page.getByRole('button',{name:'保存名称备注',exact:true}).click();await page.getByRole('heading',{name:'已编辑名称',exact:true}).waitFor();
  await page.getByRole('button',{name:'全选当前页',exact:true}).click();assert(await page.locator('.record-actions input:checked').count()===3,'当前页全选失败');await page.getByRole('button',{name:'取消选择',exact:true}).click();assert(await page.locator('.record-actions input:checked').count()===0,'取消选择失败');
  await page.locator('.record-actions input').first().check();await page.getByRole('button',{name:'删除所选（1）',exact:true}).click();assert(!requests.some(r=>r.path.endsWith('/delete')),'未确认提前删除');await page.getByRole('button',{name:'确认删除',exact:true}).click();await page.getByRole('alert').filter({hasText:'夹具删除失败'}).waitFor();await page.getByRole('button',{name:'确认删除',exact:true}).click();await page.waitForFunction(()=>document.querySelectorAll('.cards article').length===2);
  await page.getByRole('button',{name:'删除记录',exact:true}).first().click();await page.getByRole('button',{name:'取消删除',exact:true}).click();
  await page.getByRole('button',{name:'预览清理',exact:true}).click();await page.getByRole('region',{name:'清理预览'}).waitFor();assert(cleanupBatches===0,'预览时提前清理');await page.getByRole('button',{name:'确认清理',exact:true}).click();await page.getByRole('alert').filter({hasText:'夹具清理失败'}).waitFor();await page.getByRole('button',{name:'继续清理',exact:true}).click();await page.getByRole('status').filter({hasText:'删除 2 条，跳过 1 条'}).waitFor();
  paginated=true;await page.getByRole('button',{name:'刷新',exact:true}).click();await page.getByRole('button',{name:'下一页',exact:true}).click();await page.waitForFunction(()=>document.querySelectorAll('.cards article').length===1);await page.getByRole('button',{name:'删除记录',exact:true}).first().click();await page.getByRole('button',{name:'确认删除',exact:true}).click();await page.waitForFunction(()=>document.querySelector('.cards article')&&!document.querySelector('nav[aria-label]'));assert(requests.at(-1).query.page==='1','删除最后页后未回有效末页');
  await page.getByRole('button',{name:'删除记录',exact:true}).first().click();await page.locator('.current-user').filter({hasText:'切换'}).click();await page.locator('.user-select').filter({hasText:'夹具 B'}).click();await page.waitForFunction(()=>!document.querySelector('[aria-label="确认删除记录"]'));
  assert(requests.some(r=>r.query.from==='2026-10-01'&&r.query.to==='2026-10-09'&&r.query.q==='搜索名称'),'筛选未传递');if(resource==='video-visits')assert(requests.some(r=>r.query.complete==='true'&&r.query.exit_reason==='locked'),'视频状态未传递');
  await page.setViewportSize({width:390,height:844});await page.screenshot({path:`${output}/${resource}-management-mobile.png`,fullPage:true});assert(errors.length===0,errors.join(';'));console.log('PASS '+resource+': 日期关键词/状态/编辑失败重试/全选取消/确认删除/自动分批清理失败续传/删末页回退/切手机废弃确认');await context.close();
 }}finally{await browser.close();await new Promise(r=>server.close(r));}
})().catch(e=>{console.error(e);process.exitCode=1});
