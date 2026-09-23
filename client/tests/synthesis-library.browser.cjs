// 隔离 HTTP 夹具，不修改真实底图库。
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const origin = process.env.DASHBOARD_TEST_ORIGIN || 'http://127.0.0.1:5175';
if (!['localhost','127.0.0.1','[::1]'].includes(new URL(origin).hostname)) throw Error('仅允许本地 UI');
const image='data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';
const base={name:'猫咪底图',source:'personal',deletable:true,url:image,width:240,height:240,format:'gif',sha256:'test',sourceStatement:'',textSafeArea:{x:6,y:190,width:228,height:44},layout:{minFontSize:12,maxFontSize:24,textColor:'#222222',strokeColor:'#ffffff',strokeWidth:1,alignment:'center',maxLines:2}};
let assets=[{...base,id:'mine'},{...base,id:'second',name:'熊猫底图'},{...base,id:'system',name:'系统底图',source:'system',deletable:false}];
let uploads=0,patches=0,deletes=0,failDelete=true;
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_EXECUTABLE});
 try{
  const context=await browser.newContext({viewport:{width:1360,height:1000}});
  await context.addInitScript(()=>localStorage.setItem('shurufa_dashboard_user_id','00000000-0000-4000-8000-00000000000a'));
  const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/api/v1/**',async route=>{
   const request=route.request(),path=new URL(request.url()).pathname;let body={};
   if(path==='/api/v1/auth/session')body={username:'browser-fixture'};
   else if(path==='/api/v1/dashboard/users')body={users:[{id:'00000000-0000-4000-8000-00000000000a',dashboard_name:'隔离验收'}],total:1};
   else if(path==='/api/v1/dashboard/synthesis-library'&&request.method()==='GET')body={assets,total:assets.length};
   else if(path==='/api/v1/dashboard/synthesis-library'&&request.method()==='POST'){
    const input=request.postDataJSON();assert.equal(input.noTextConfirmed,undefined);assert.equal(input.rightsConfirmed,undefined);uploads++;
    const asset={...base,...input,id:'new'};assets.push(asset);body={asset,duplicate:false};
   }else if(path.startsWith('/api/v1/dashboard/synthesis-library/')&&request.method()==='PATCH'){
    const id=path.split('/').pop();const patch=request.postDataJSON();patches++;
    assets=assets.map(a=>a.id===id?{...a,...patch}:a);body={asset:assets.find(a=>a.id===id)};
   }else if(path.startsWith('/api/v1/dashboard/synthesis-library/')&&request.method()==='DELETE'){
    const id=path.split('/').pop();deletes++;
    if(id==='second'&&failDelete){failDelete=false;return route.fulfill({status:503,json:{error:'测试断网'}});}
    assets=assets.filter(a=>a.id!==id);body={ok:true};
   }
   await route.fulfill({json:body});
  });
  await page.goto(origin+'/ai-synthesis');await page.getByTestId('edit-synthesis-mine').waitFor();
  assert.equal(await page.getByTestId('synthesis-no-text').count(),0);
  assert.equal(await page.getByTestId('synthesis-rights').count(),0);
  assert.equal(await page.getByTestId('select-synthesis-system').count(),0);
  await page.getByTestId('synthesis-file').setInputFiles({name:'新底图.gif',mimeType:'image/gif',buffer:Buffer.from('GIF89a')});
  await page.getByTestId('synthesis-card-new').waitFor();assert.equal(uploads,1);
  await page.getByTestId('edit-synthesis-new').click();await page.getByTestId('synthesis-name').fill('编辑后的底图');
  await page.getByTestId('safe-width').fill('1');await page.getByTestId('save-synthesis').click();
  await page.getByRole('alert').filter({hasText:'至少 14'}).waitFor();assert.equal(patches,0);
  await page.getByTestId('safe-width').fill('220');
  await page.getByTestId('save-synthesis').click();await page.getByText('底图已更新。',{exact:true}).waitFor();
  assert.equal(patches,1);assert.equal(assets.find(a=>a.id==='new').name,'编辑后的底图');
  await page.getByTestId('replace-synthesis-new').click();
  await page.getByTestId('synthesis-file').setInputFiles({name:'替换.gif',mimeType:'image/gif',buffer:Buffer.from('GIF89a-replacement')});
  await page.getByTestId('save-synthesis').click();await page.getByTestId('cancel-synthesis-edit').waitFor({state:'detached'});
  assert.equal(patches,2);assert.equal(assets.find(a=>a.id==='new').filename,'替换.gif');
  await page.getByTestId('select-all-synthesis').click();await page.getByText('已选 3 张',{exact:true}).waitFor();
  await page.getByTestId('clear-synthesis-selection').click();await page.getByText('已选 0 张',{exact:true}).waitFor();
  await page.getByTestId('select-synthesis-mine').check();assert.equal(await page.getByTestId('select-synthesis-mine').isChecked(),true);
  await page.getByTestId('select-all-synthesis').click();
  await page.screenshot({path:'/tmp/shurufa-synthesis-desktop.png',fullPage:true});
  await page.setViewportSize({width:390,height:844});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  await page.screenshot({path:'/tmp/shurufa-synthesis-mobile.png',fullPage:true});
  await page.getByTestId('delete-selected-synthesis').click();await page.getByTestId('confirmation-cancel').click();assert.equal(deletes,0);
  await page.getByTestId('delete-selected-synthesis').click();await page.getByTestId('confirmation-accept').click();
  await page.getByRole('alert').filter({hasText:'1 张删除失败'}).waitFor();await page.getByText('已选 1 张',{exact:true}).waitFor();
  assert.equal(assets.length,2);await page.getByTestId('delete-selected-synthesis').click();await page.getByTestId('confirmation-accept').click();
  await page.getByTestId('synthesis-card-second').waitFor({state:'detached'});assert.equal(assets.length,1);
  await page.reload();await page.getByTestId('synthesis-card-system').waitFor();assert.equal(await page.getByTestId('edit-synthesis-mine').count(),0);
  assert.deepEqual(errors,[]);console.log('PASS: 自动上传、无确认勾选、编辑/区域校验/替换、全选/全不选/单选、删除取消/部分失败重试、390px窄屏；隔离夹具');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
