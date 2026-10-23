// Synthetic APIs only: pagination, original images, errors and device isolation.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert=require('node:assert/strict');
const {createServer}=require('node:http');
const {readFileSync,existsSync,mkdirSync}=require('node:fs');
const {resolve,extname}=require('node:path');
const {data,userId}=require('./admin-pages.fixtures.cjs');
const other='bbbbbbbb-0000-4000-8000-000000000002',dist=resolve(__dirname,'../dist');
const image=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=','base64');
(async()=>{
 const server=createServer((req,res)=>{
  const path=new URL(req.url,'http://local').pathname,candidate=resolve(dist,'.'+path);
  const file=candidate.startsWith(dist+'/')&&extname(candidate)&&existsSync(candidate)?candidate:resolve(dist,'index.html');
  res.setHeader('Content-Type',({'.js':'application/javascript','.css':'text/css','.html':'text/html'})[extname(file)]||'application/octet-stream');res.end(readFileSync(file));
 });await new Promise(r=>server.listen(0,'127.0.0.1',r));let browser;
 try{
  const origin=`http://127.0.0.1:${server.address().port}`;
  browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_EXECUTABLE,args:['--no-sandbox']});
  for(const width of [1440,390]){
   const context=await browser.newContext({viewport:{width,height:900}}),errors=[],unknown=[],queries=[];
   let fail=false,empty=false,delay=false,release,delayed;
   const row=id=>({id:String(id),url:`/uploads/chat/fixture-${id}.png`,received_at:'2026-10-22T00:00:00Z',byte_size:12000,width:1080,height:1920});
   await context.addInitScript(id=>localStorage.setItem('shurufa_dashboard_user_id',id),userId);
   await context.route('**/*',async route=>{
    const u=new URL(route.request().url());
    if(u.origin!==origin){unknown.push(u.href);return route.abort();}
    if(u.pathname.startsWith('/uploads/'))return route.fulfill({contentType:'image/png',body:image});
    if(!u.pathname.startsWith('/api/'))return route.continue();
    if(u.pathname==='/api/v1/dashboard/users')return route.fulfill({json:{users:[...data[u.pathname].users,{id:other,dashboard_name:'第二部测试手机',model:'Fixture'}],total:2,page:1}});
    if(u.pathname==='/api/v1/dashboard/chat/unlinked-images'){
     queries.push(Object.fromEntries(u.searchParams));
     const mine=u.searchParams.get('user_id')===userId;
     if(mine&&delay){delayed?.();await new Promise(r=>release=r);return route.fulfill({json:{total:1,images:[row('stale')]}}).catch(()=>{});}
     if(fail)return route.fulfill({status:503,json:{error:'temporary'}});
     return route.fulfill({json:{total:empty||!mine?0:25,images:empty||!mine?[]:u.searchParams.get('page')==='2'?[row(25)]:[row(1),row(2)]}});
    }
    if(data[u.pathname])return route.fulfill({json:data[u.pathname]});
    unknown.push(u.pathname);return route.abort();
   });
   const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));await page.goto(origin+'/chat-unlinked-images');
   await page.getByRole('button',{name:'查看图片 1',exact:true}).waitFor();
   assert.equal(await page.locator('.sidebar a[href="/chat-unlinked-images"]').count(),1);
   assert(await page.locator('.hint').first().textContent().then(s=>s.includes('来源应用')));
   await page.getByRole('button',{name:'查看图片 1',exact:true}).click();
   await page.getByRole('dialog').waitFor();assert(await page.getByRole('dialog').textContent().then(s=>s.includes('原始采集时间未知')));
   await page.getByRole('button',{name:'下一张',exact:true}).click();assert(await page.getByRole('dialog').textContent().then(s=>s.includes('#2')));
   await page.keyboard.press('Escape');assert.equal(await page.getByRole('dialog').count(),0);
   await page.getByRole('button',{name:'下一页',exact:true}).click();await page.getByRole('button',{name:'查看图片 25',exact:true}).waitFor();
   await page.getByLabel('接收开始日期').fill('2026-10-22');await page.getByRole('button',{name:'查看图片 1',exact:true}).waitFor();
   assert.equal(queries.at(-1).from,'2026-10-22');assert.equal(queries.at(-1).page,'1');
   fail=true;await page.getByRole('button',{name:'刷新',exact:true}).click();await page.getByRole('alert').filter({hasText:'503'}).waitFor();
   fail=false;await page.getByRole('button',{name:'重试',exact:true}).click();await page.getByRole('button',{name:'查看图片 1',exact:true}).waitFor();
   empty=true;await page.getByRole('button',{name:'刷新',exact:true}).click();await page.getByText('当前范围没有未关联图片。',{exact:true}).waitFor();empty=false;
   const seen=new Promise(r=>delayed=r);delay=true;await page.getByRole('button',{name:'刷新',exact:true}).click();await seen;
   if(width===390)await page.getByRole('button',{name:'打开导航',exact:true}).click();
   await page.locator('button.current-user').filter({hasText:'切换'}).click();
   await page.locator('.user-select').filter({hasText:'第二部测试手机'}).click();
   await page.getByText('当前范围没有未关联图片。',{exact:true}).waitFor();release();
   await page.waitForTimeout(150);assert.equal(await page.locator('.cards article').count(),0);
   assert.equal(await page.locator('.unlinked-images').evaluate(el=>el.scrollWidth>el.clientWidth+1),false);
   assert.deepEqual(errors,[]);assert.deepEqual(unknown,[]);
   await context.close();console.log(`unlinked images ${width}px passed`);
  }
 }finally{await browser?.close();await new Promise(r=>server.close(r));}
})().catch(e=>{console.error(e);process.exitCode=1;});
