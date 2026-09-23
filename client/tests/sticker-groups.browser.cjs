// 浏览器交互回归：隔离 HTTP 夹具，不读取凭据、不改真实用户图库。
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const origin = process.env.DASHBOARD_TEST_ORIGIN || 'http://127.0.0.1:5175';
if (!['localhost','127.0.0.1','[::1]'].includes(new URL(origin).hostname)) throw Error('仅允许本地 UI');
const image = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';
const group = {keyword:'赞',aliases:['赞','给你点赞'],confirmedAliases:[],category:'其他',planned:false,custom:false,assets:[
 {id:7,source:'personal',url:image,format:'gif',keywords:['赞'],width:1,height:1,useCount:0},
 {id:'praise',source:'system',url:image,format:'gif',keywords:['赞'],width:1,height:1,useCount:null},
]};
let deleted = false, deleteCalls = 0;
const createdGroups = [];
let createCalls = 0;
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_EXECUTABLE,args:['--no-sandbox']});
 try {
  const context=await browser.newContext({viewport:{width:1280,height:900}});
  await context.addInitScript(()=>localStorage.setItem('shurufa_dashboard_user_id','00000000-0000-4000-8000-00000000000a'));
  const page=await context.newPage(), errors=[]; page.on('pageerror',error=>errors.push(error.message));
  await page.route('**/api/v1/**',async route=>{
   const request=route.request(), path=new URL(request.url()).pathname;
   let body={};
   if(path==='/api/v1/auth/session')body={username:'browser-fixture'};
   else if(path==='/api/v1/dashboard/users')body={users:[{id:'00000000-0000-4000-8000-00000000000a',dashboard_name:'隔离交互验收'}],total:1};
   else if(path==='/api/v1/dashboard/sticker-library')body={groups:[...(deleted?[]:[group]),...createdGroups],systemCount:deleted?0:1,personalCount:deleted?0:1,warnings:[]};
   else if(path==='/api/v1/dashboard/sticker-keywords'&&request.method()==='POST'){
    const {keyword}=request.postDataJSON();createCalls++;
    createdGroups.push({keyword,aliases:[keyword],confirmedAliases:[],category:'自定义',planned:false,custom:true,assets:[]});
    body={keyword};
   }
   else if(path.endsWith('/delete')&&path.startsWith('/api/v1/dashboard/sticker-groups/')){
    assert.deepEqual(request.postDataJSON(),{confirm:'DELETE',aliases:group.aliases,assetKeys:group.assets.map(a=>`${a.source}:${a.id}`)});
    deleteCalls++;deleted=true;body={keyword:group.keyword,files_pending:false};
   }
   else if(path.startsWith('/api/v1/dashboard/sticker-groups/')&&request.method()==='PATCH'){
    const patch=request.postDataJSON();
    if(patch.aliases?.includes('翻白眼'))return route.fulfill({status:409,json:{error:'说法“翻白眼”已属于关键词组“翻白眼”，请先从原组移除'}});
    if(patch.assetOrder)group.assets=patch.assetOrder.map(key=>group.assets.find(a=>`${a.source}:${a.id}`===key));
    if(patch.aliases)group.aliases=patch.aliases;
    body={group};
   }
   await route.fulfill({json:body});
  });
  await page.goto(origin+'/stickers');
  await page.getByTestId('drag-sticker-personal:7').waitFor({timeout:10000}).catch(async error=>{ console.error('页面诊断',page.url(),await page.locator('body').innerText(),errors); throw error; });
  await page.getByTestId('sticker-cell-system:praise').scrollIntoViewIfNeeded();
  const from=await page.getByTestId('drag-sticker-personal:7').boundingBox(),to=await page.getByTestId('sticker-cell-system:praise').boundingBox();
  await page.mouse.move(from.x+from.width/2,from.y+from.height/2);await page.mouse.down();
  await page.mouse.move(from.x+from.width/2+10,from.y+from.height/2+10,{steps:5});
  await page.mouse.move(to.x+to.width/2,to.y+to.height/2,{steps:10});await page.mouse.up();
  assert.equal(await page.getByTestId('save-sticker-order').isEnabled(),true);
  await page.getByTestId('save-sticker-order').click();
  await page.getByText('图片顺序已保存；手机下次打开键盘检查更新后生效。').waitFor();
  await page.reload();
  await page.getByTestId('drag-sticker-system:praise').waitFor();
  assert.equal(await page.locator('.sticker-cell').first().getAttribute('data-testid'),'sticker-cell-system:praise');
  await page.getByTestId('edit-group-aliases').click();
  await page.getByTestId('group-alias-input-0').fill('翻白眼');
  await page.getByTestId('save-group-aliases').click();
  await page.getByText('说法保存失败：说法“翻白眼”已属于关键词组“翻白眼”，请先从原组移除').waitFor({timeout:5000});
  assert.equal(await page.getByTestId('group-alias-input-0').inputValue(),'翻白眼');
  assert.deepEqual(group.aliases,['赞','给你点赞']);
  await page.getByTestId('group-alias-input-0').fill('夸夸你');
  await page.getByTestId('remove-group-alias-1').click();
  await page.getByTestId('add-group-alias').click();
  await page.getByTestId('group-alias-input-1').fill('真棒');
  await page.getByTestId('save-group-aliases').click();
  await page.getByText('同组说法已保存并纳入手机同步；自动推荐只匹配完整说法。').waitFor();
  await page.reload(); await page.getByTestId('edit-group-aliases').waitFor();
  assert.deepEqual(await page.locator('.alias-tags .library-badge').allTextContents(),['夸夸你','真棒']);
  await page.setViewportSize({width:390,height:844});
  assert.equal(await page.getByTestId('edit-group-aliases').isVisible(),true);
  assert.equal(await page.getByRole('button',{name:'图片后移',exact:true}).first().isEnabled(),true);
  await page.getByRole('button',{name:'图片后移',exact:true}).first().click();
  assert.equal(await page.getByTestId('save-sticker-order').isEnabled(),true);
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth <= window.innerWidth),true);
  await page.getByRole('button',{name:'待补图',exact:true}).click();
  await page.getByRole('searchbox',{name:'搜索关键词'}).fill('真棒');
  await page.getByRole('button',{name:'打开已有词组「赞」',exact:true}).click();
  await page.getByTestId('keyword-赞').waitFor();assert.equal(createCalls,0);
  await page.getByTestId('delete-keyword-group').click();
  await page.getByTestId('confirmation-dialog').waitFor();
  assert.match(await page.getByTestId('confirmation-dialog').innerText(),/2 张图片/);
  await page.getByTestId('confirmation-cancel').click();assert.equal(deleteCalls,0);
  await page.getByTestId('delete-keyword-group').click();
  await page.getByTestId('confirmation-accept').click();
  await page.getByText('已删除“赞”及其说法和图片。',{exact:true}).waitFor();
  assert.equal(deleteCalls,1);assert.equal(await page.locator('.sticker-cell').count(),0);
  await page.reload();await page.getByText('没有匹配的关键词',{exact:true}).waitFor();
  await page.getByTestId('new-keyword').fill('未提交草稿');
  await page.getByRole('searchbox',{name:'搜索关键词'}).fill('眼神');
  await page.getByRole('button',{name:'＋ 新增词组「眼神」',exact:true}).waitFor();
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth <= window.innerWidth),true);
  await page.getByTestId('add-search-keyword').click();
  await page.getByTestId('keyword-眼神').waitFor();
  assert.equal(await page.getByTestId('keyword-眼神').getAttribute('aria-current'),'true');
  assert.equal(await page.getByTestId('new-keyword').inputValue(),'未提交草稿');
  assert.equal(await page.getByRole('searchbox',{name:'搜索关键词'}).inputValue(),'');
  assert.equal(createCalls,1);
  await page.reload();await page.getByTestId('keyword-眼神').waitFor();
  assert.deepEqual(errors,[]);
  console.log('PASS: 原生拖放、保存/刷新、说法增删改、409提示、390px窄屏、删除确认/取消/刷新不复活、搜索建组/已有说法定位/保留草稿；隔离 HTTP 夹具');
 }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
