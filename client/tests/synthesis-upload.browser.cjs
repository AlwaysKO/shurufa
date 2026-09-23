// 隔离HTTP夹具，验证原文件上传、中文参数、进度及不刷新全库。
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict');
const origin=process.env.DASHBOARD_TEST_ORIGIN||'http://127.0.0.1:5175';
if(!['localhost','127.0.0.1','[::1]'].includes(new URL(origin).hostname))throw Error('仅允许本地UI');
const bytes=Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7','base64');
(async()=>{const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
try{
 const page=await browser.newPage();let listCalls=0,release,uploadId,report;
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>localStorage.setItem('shurufa_dashboard_user_id','00000000-0000-4000-8000-00000000000a'));
 await page.route('**/api/v1/**',async route=>{
  const req=route.request(),url=new URL(req.url());let json={};
  if(url.pathname==='/api/v1/auth/session')json={username:'fixture'};
  else if(url.pathname==='/api/v1/dashboard/users')json={users:[{id:'00000000-0000-4000-8000-00000000000a'}],total:1};
  else if(url.pathname==='/api/v1/dashboard/upload-diagnostics'){report=req.postDataJSON();json={ok:true};}
  else if(url.pathname==='/api/v1/dashboard/synthesis-library'){
   if(req.method()==='GET'){listCalls++;json={assets:[],total:0};}
   else{
    assert.equal(req.headers()['content-type'],'application/octet-stream');assert.deepEqual(req.postDataBuffer(),bytes);
    const metadata=JSON.parse(Buffer.from(req.headers()['x-upload-metadata'],'base64').toString());
    assert.equal(metadata.name,'中文底图');assert.equal(url.searchParams.has('metadata'),false);
    uploadId=req.headers()['x-upload-id'];await new Promise(resolve=>{release=resolve;});
    json={asset:{id:'synthesis-test',name:'中文底图',source:'personal',deletable:true,format:'gif',width:320,height:180,url:'data:image/gif;base64,'+bytes.toString('base64'),textSafeArea:{x:8,y:140,width:300,height:30},layout:metadata.layout},duplicate:false};
   }
  }
  await route.fulfill({json});
 });
 await page.goto(origin+'/ai-synthesis');await page.getByText('还没有底图',{exact:true}).waitFor();
 await page.getByTestId('synthesis-file').setInputFiles({name:'中文底图.gif',mimeType:'image/gif',buffer:bytes});
 await page.getByText(/正在上传|文件已传输/).waitFor();
 for(let i=0;!release&&i<500;i++)await new Promise(r=>setTimeout(r,10));assert.ok(release);release();
 await page.getByTestId('synthesis-card-synthesis-test').waitFor();assert.equal(listCalls,1);
 await page.getByTestId('edit-synthesis-synthesis-test').click();await page.getByTestId('safe-x').waitFor();
 assert.equal(await page.locator('.safe-preview').evaluate(e=>getComputedStyle(e).aspectRatio),'320 / 180');
 for(let i=0;!report&&i<500;i++)await new Promise(r=>setTimeout(r,10));
 assert.equal(report.id,uploadId);assert.equal(report.kind,'synthesis');assert.equal(report.outcome,'load');
 assert.deepEqual(errors,[]);console.log('PASS: 原文件/中文参数/进度/局部更新/实际画布/关联耗时上报');
}finally{await browser.close();}})().catch(e=>{console.error(e);process.exitCode=1;});
