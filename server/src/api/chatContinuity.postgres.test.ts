import { randomUUID, createHash } from 'node:crypto';
import { readFileSync, readdirSync, realpathSync, existsSync } from 'node:fs';
import { mkdir, writeFile, chmod } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import pg from 'pg';
import request from 'supertest';
import { beforeAll, beforeEach, afterAll, expect, it, vi } from 'vitest';
import { createApp } from '../app.js';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';
import { ingestCapturedMessages } from '../chat/chatRepository.js';
import { retryDeviceFileCleanup } from '../lib/deleteDeviceData.js';
const cluster = process.env.CHAT_CONTINUITY_TEST_CLUSTER;
if (cluster && (!cluster.startsWith('/tmp/shurufa-chat-continuity.') || readFileSync(join(cluster,'test-instance-only'),'utf8') !== 'chat-continuity-only')) throw Error('独立测试实例验证失败');
const test = cluster ? it : it.skip;
const A=randomUUID(), B=randomUUID();
const migrations=readdirSync(new URL('../../migrations/',import.meta.url)).filter(f=>f.endsWith('.sql')).sort().map(f=>readFileSync(new URL(`../../migrations/${f}`,import.meta.url),'utf8'));
let pool:pg.Pool, app:ReturnType<typeof createApp>, agent:Awaited<ReturnType<typeof authenticatedRequest>>, root:string, verified=false;
beforeAll(async()=>{
 if(!cluster)return;
 pool=new pg.Pool({host:join(cluster,'socket'),port:5432,user:'ko',database:'chat_continuity_test',max:4});
 const identity=(await pool.query("SELECT current_setting('data_directory') AS dir,current_database() AS db")).rows[0];
 expect(realpathSync(identity.dir)).toBe(realpathSync(join(cluster,'data')));expect(identity.db).toBe('chat_continuity_test');verified=true;
});
beforeEach(async()=>{
 if(!cluster)return;if(!verified)throw Error('禁止业务库测试');
 const schema='test_'+randomUUID().replaceAll('-','');await pool.end();
 pool=new pg.Pool({host:join(cluster,'socket'),port:5432,user:'ko',database:'chat_continuity_test',max:4,options:`-c search_path=${schema}`});
 await pool.query(`CREATE SCHEMA ${schema}`);for(const sql of migrations)await pool.query(sql);
 root=join(cluster,schema);await mkdir(root,{recursive:true});vi.spyOn(process,'cwd').mockReturnValue(root);
 app=createApp(pool);agent=await authenticatedRequest(app);
});
afterAll(async()=>{vi.restoreAllMocks();await pool?.end();});
async function conversation(platform='wechat',user=A){return Number((await pool.query(`INSERT INTO chat_conversation(user_id,platform,account_key,external_key,conversation_type,identity_confidence) VALUES($1,$2,'self',$3,'direct',1) RETURNING id`,[user,platform,randomUUID()])).rows[0].id);}
async function message(conversationId:number,options:{id?:string;user?:string;time?:string;text?:string|null;type?:string}={}){
 const id=options.id||randomUUID(),user=options.user||A;
 await pool.query(`INSERT INTO chat_message(id,user_id,device_id,conversation_id,platform,fingerprint,content_fingerprint,sender_key,direction,message_type,text,captured_at)
 SELECT $1,$2,$2,id,platform,$3,$3,'peer','incoming',$4,$5,$6 FROM chat_conversation WHERE id=$7`,[id,user,createHash('sha256').update(id).digest('hex'),options.type||'image',options.text??null,options.time||'2026-09-18T01:00:00Z',conversationId]);return id;
}

const merge=(source:number,target:number)=>agent.post(`/api/v1/dashboard/chat/conversations/${source}/merge?user_id=${A}`).send({confirm:'MERGE',target_id:target});
test('合并保留消息，隐藏源，旧ID解析到目标，名称不覆盖',async()=>{
 const a=await conversation(),b=await conversation(); await pool.query('UPDATE chat_conversation SET display_name=$1 WHERE id=$2',['目标',b]);
 const id=await message(a); const response=await merge(a,b);expect(response.status).toBe(200);
 expect((await pool.query('SELECT conversation_id FROM chat_message WHERE id=$1',[id])).rows[0].conversation_id).toBe(String(b));
 const list=await agent.get(`/api/v1/dashboard/chat/conversations?user_id=${A}`);expect(list.body.conversations.map((c:any)=>c.id)).toEqual([b]);
 const restored=await agent.get(`/api/v1/dashboard/chat/conversations/${a}/resolve?user_id=${A}`);expect(restored.body.conversation.id).toBe(b);expect(restored.body.conversation.display_name).toBe('目标');
 expect((await pool.query('SELECT count(*) FROM chat_conversation')).rows[0].count).toBe('2');
});
test('后续源标识上报进入目标且不覆盖目标名字',async()=>{
 const a=await conversation(),b=await conversation();const source=(await pool.query('SELECT * FROM chat_conversation WHERE id=$1',[a])).rows[0];
 await pool.query('UPDATE chat_conversation SET display_name=$1 WHERE id=$2',['目标',b]);await merge(a,b);
 await ingestCapturedMessages(pool,A,A,{platform:'wechat',account_key:source.account_key,external_key:source.external_key,display_name:'错字',conversation_type:'direct',identity_confidence:1},[{id:randomUUID(),fingerprint:'a'.repeat(64),content_fingerprint:'a'.repeat(64),sender_key:'peer',direction:'incoming',message_type:'text',text:'new',captured_at:'2026-09-18T00:00:00Z'}]);
 expect((await pool.query('SELECT conversation_id FROM chat_message')).rows[0].conversation_id).toBe(String(b));
 expect((await pool.query('SELECT display_name FROM chat_conversation WHERE id=$1',[b])).rows[0].display_name).toBe('目标');
});
test('链式合并展平，旧别名不能用于意外合并整个目标',async()=>{
 const a=await conversation(),b=await conversation(),c=await conversation();await message(a);await merge(a,b);expect((await merge(b,c)).status).toBe(200);
 expect((await pool.query('SELECT merged_into_id FROM chat_conversation WHERE id IN ($1,$2)',[a,b])).rows.every(r=>Number(r.merged_into_id)===c)).toBe(true);
 expect((await merge(a,c)).status).toBe(409);expect((await merge(c,a)).status).toBe(400);
});
test('禁止跨用户、跨App、自合并与未确认请求',async()=>{
 const a=await conversation(),b=await conversation('qq'),other=await conversation('wechat',B);
 expect((await merge(a,b)).status).toBe(400);expect((await merge(a,other)).status).toBe(404);expect((await merge(a,a)).status).toBe(400);
 expect((await agent.post(`/api/v1/dashboard/chat/conversations/${a}/merge?user_id=${A}`).send({target_id:b})).status).toBe(400);
 expect((await agent.get(`/api/v1/dashboard/chat/conversations/${other}/resolve?user_id=${A}`)).status).toBe(404);
});
test('搜索分页包含100项之后的会话，精确ID不依赖列表位置',async()=>{
 const first=await conversation();await pool.query('UPDATE chat_conversation SET display_name=$1 WHERE id=$2',['老会话',first]);for(let i=0;i<101;i++)await conversation();
 const list=await agent.get(`/api/v1/dashboard/chat/conversations?user_id=${A}&q=${encodeURIComponent('老会话')}`);expect(list.body.total).toBe(1);expect(list.body.conversations[0].id).toBe(first);
 expect((await agent.get(`/api/v1/dashboard/chat/conversations/${first}/resolve?user_id=${A}`)).body.conversation.id).toBe(first);
});
test('合并同时上报不会留消息在隐藏会话',async()=>{
 const a=await conversation(),b=await conversation();const source=(await pool.query('SELECT * FROM chat_conversation WHERE id=$1',[a])).rows[0];
 const upload=()=>ingestCapturedMessages(pool,A,A,{platform:'wechat',account_key:source.account_key,external_key:source.external_key,conversation_type:'direct',identity_confidence:1},[{id:randomUUID(),fingerprint:randomUUID().replaceAll('-','').repeat(2),content_fingerprint:'b'.repeat(64),sender_key:'peer',direction:'incoming',message_type:'text',text:'并发',captured_at:'2026-09-18T00:00:00Z'}]);
 await Promise.all([upload(),merge(a,b),upload()]);expect((await pool.query('SELECT count(*) FROM chat_message WHERE conversation_id=$1',[a])).rows[0].count).toBe('0');expect((await pool.query('SELECT count(*) FROM chat_message WHERE conversation_id=$1',[b])).rows[0].count).toBe('2');
});

test('同一页面待确认恢复已知身份时迁移，迟到pending不复活独立分组',async()=>{
 const pendingKey='screenshot-v2:pending:'+randomUUID(), targetKey='screenshot-v2:'+ 'c'.repeat(64);
 const conv=(key:string,confidence:number)=>({platform:'wechat' as const,account_key:'local',external_key:key,display_name:confidence<0.8?'待确认':'已知',conversation_type:'direct' as const,identity_confidence:confidence});
 const msg=(n:string)=>({id:randomUUID(),fingerprint:n.repeat(64),content_fingerprint:n.repeat(64),sender_key:'peer',direction:'system' as const,message_type:'image' as const,captured_at:'2026-09-18T00:00:00Z'});
 const old=await ingestCapturedMessages(pool,A,A,conv(pendingKey,0.55),[msg('a')]);
 const recovered=await ingestCapturedMessages(pool,A,A,conv(targetKey,0.85),[{...msg('b'),metadata:{conversation_identity_previous_key:pendingKey,conversation_identity_status:'confirmed'}}]);
 await ingestCapturedMessages(pool,A,A,conv(pendingKey,0.55),[msg('d')]);
 expect((await pool.query('SELECT conversation_id FROM chat_message')).rows.every(r=>Number(r.conversation_id)===recovered.conversationId)).toBe(true);
 expect((await pool.query('SELECT merged_into_id FROM chat_conversation WHERE id=$1',[old.conversationId])).rows[0].merged_into_id).toBe(String(recovered.conversationId));
});

test('已合并的旧页面不能删除归属映射，删除目标才清除整组',async()=>{
 const a=await conversation(),b=await conversation();await message(a);await merge(a,b);
 expect((await agent.delete(`/api/v1/dashboard/chat/conversations/${a}?user_id=${A}`)).status).toBe(409);
 expect((await pool.query('SELECT count(*) FROM chat_conversation')).rows[0].count).toBe('2');
 expect((await agent.delete(`/api/v1/dashboard/chat/conversations/${b}?user_id=${A}`)).status).toBe(200);
 expect((await pool.query('SELECT count(*) FROM chat_conversation')).rows[0].count).toBe('0');
});
test('附件关联和文字原样保留，目标名称和资料不被源覆盖',async()=>{
 const a=await conversation(),b=await conversation();const mid=await message(a,{type:'text',text:'不能删除的原文'});
 const asset=(await pool.query(`INSERT INTO media_asset(user_id,sha256,mime_type,storage_path,byte_size) VALUES($1,$2,'image/png','fixture.png',4) RETURNING id`,[A,'9'.repeat(64)])).rows[0].id;
 await pool.query(`INSERT INTO chat_message_asset(message_id,asset_id,role,position) VALUES($1,$2,'content',0)`,[mid,asset]);
 await merge(a,b);
 expect((await pool.query('SELECT text FROM chat_message WHERE id=$1',[mid])).rows[0].text).toBe('不能删除的原文');
 expect((await pool.query('SELECT asset_id FROM chat_message_asset WHERE message_id=$1',[mid])).rows[0].asset_id).toBe(asset);
 expect((await pool.query('SELECT count(*) FROM media_asset')).rows[0].count).toBe('1');
});

test('普通文字和旧命名空间不能借确认字段自动合并待确认截图',async()=>{
 const a=await conversation(),key='capture-v3:pending:'+randomUUID();await message(a);
 await pool.query('UPDATE chat_conversation SET external_key=$1,identity_confidence=0.55 WHERE id=$2',[key,a]);
 await ingestCapturedMessages(pool,A,A,{platform:'wechat',account_key:'self',external_key:'direct:peer',conversation_type:'direct',identity_confidence:1},[{id:randomUUID(),fingerprint:'e'.repeat(64),content_fingerprint:'f'.repeat(64),sender_key:'peer',direction:'incoming',message_type:'text',text:'普通文本',captured_at:'2026-09-18T00:00:00Z',metadata:{conversation_identity_previous_key:key,conversation_identity_status:'confirmed'}}]);
 expect((await pool.query('SELECT merged_into_id FROM chat_conversation WHERE id=$1',[a])).rows[0].merged_into_id).toBeNull();
 expect((await pool.query('SELECT count(*) FROM chat_message WHERE conversation_id=$1',[a])).rows[0].count).toBe('1');
});

async function pendingAndKnown() {
 const p=await conversation(),c=await conversation(),pendingKey='capture-v3:pending:'+randomUUID(),knownKey='capture-v3:'+createHash('sha256').update(randomUUID()).digest('hex');
 await pool.query('UPDATE chat_conversation SET external_key=$1,identity_confidence=0.55 WHERE id=$2',[pendingKey,p]);
 await pool.query('UPDATE chat_conversation SET external_key=$1 WHERE id=$2',[knownKey,c]);
 const confirm=()=>ingestCapturedMessages(pool,A,A,{platform:'wechat',account_key:'self',external_key:knownKey,conversation_type:'direct',identity_confidence:0.85},[{id:randomUUID(),fingerprint:createHash('sha256').update(randomUUID()).digest('hex'),content_fingerprint:'8'.repeat(64),sender_key:'peer',direction:'system',message_type:'image',captured_at:'2026-09-18T00:00:00Z',metadata:{conversation_identity_previous_key:pendingKey,conversation_identity_status:'confirmed'}}]);
 return{p,c,pendingKey,knownKey,confirm};
}
test('手动A到pendingP后，P自动确认到C也展平A并迁移整个归属组',async()=>{
 const a=await conversation(),{p,c,confirm}=await pendingAndKnown();const source=(await pool.query('SELECT * FROM chat_conversation WHERE id=$1',[a])).rows[0];await message(a);expect((await merge(a,p)).status).toBe(200);await confirm();
 expect((await pool.query('SELECT merged_into_id FROM chat_conversation WHERE id IN ($1,$2)',[a,p])).rows.every(r=>Number(r.merged_into_id)===c)).toBe(true);
 await ingestCapturedMessages(pool,A,A,{platform:'wechat',account_key:'self',external_key:source.external_key,conversation_type:'direct',identity_confidence:1},[{id:randomUUID(),fingerprint:'7'.repeat(64),content_fingerprint:'7'.repeat(64),sender_key:'peer',direction:'incoming',message_type:'text',text:'后续A上报',captured_at:'2026-09-18T00:00:00Z'}]);
 expect((await pool.query('SELECT conversation_id FROM chat_message')).rows.every(r=>Number(r.conversation_id)===c)).toBe(true);
 expect((await agent.get(`/api/v1/dashboard/chat/conversations/${a}/resolve?user_id=${A}`)).body.conversation.id).toBe(c);
});
test('已知C手动合并到pendingP后迟到确认不得让P指向自己',async()=>{
 const {p,c,confirm}=await pendingAndKnown();await message(c);expect((await merge(c,p)).status).toBe(200);await confirm();
 expect((await pool.query('SELECT merged_into_id FROM chat_conversation WHERE id=$1',[p])).rows[0].merged_into_id).toBeNull();
 const list=await agent.get(`/api/v1/dashboard/chat/conversations?user_id=${A}`);expect(list.body.conversations.map((r:any)=>r.id)).toEqual([p]);
 expect((await pool.query('SELECT conversation_id FROM chat_message')).rows.every(r=>Number(r.conversation_id)===p)).toBe(true);
});

test('待确认集中展示但保留来源，已确认和跨手机App不混入',async()=>{
 const p=await conversation(), q=await conversation(), known=await conversation(), foreign=await conversation('wechat',B), douyin=await conversation('douyin');
 for(const id of [p,q,foreign,douyin]) await pool.query("UPDATE chat_conversation SET identity_confidence=0.55,display_name='待确认会话',external_key=$2 WHERE id=$1",[id,'screenshot-v2:pending:'+randomUUID()]);
 const first=await message(p,{time:'2026-09-18T03:00:00Z'}),second=await message(q,{time:'2026-09-18T02:00:00Z'});
 await message(known);await message(foreign,{user:B});await message(douyin);
 const list=await agent.get(`/api/v1/dashboard/chat/conversations?user_id=${A}&platform=wechat&group_pending=true`);
 expect(list.status).toBe(200);expect(list.body.total).toBe(2);
 expect(list.body.conversations.map((c:any)=>c.id)).toEqual([-1,known]);
 expect(list.body.conversations[0]).toMatchObject({display_name:'待确认会话',message_count:2});
 const page=await agent.get(`/api/v1/dashboard/chat/messages?user_id=${A}&conversation_id=-1&platform=wechat&page_size=1`);
 expect(page.status).toBe(200);expect(page.body.total).toBe(2);expect(page.body.messages[0]).toMatchObject({id:first,conversation_id:p});
 const next=await agent.get(`/api/v1/dashboard/chat/messages?user_id=${A}&conversation_id=-1&platform=wechat&page_size=1&page=2`);
 expect(next.body.messages[0].id).toBe(second);
 expect((await agent.get(`/api/v1/dashboard/chat/messages?user_id=${A}&conversation_id=-1`)).status).toBe(400);
 await pool.query("UPDATE chat_conversation SET identity_confidence=.85,display_name='已确认' WHERE id=$1",[p]);
 const after=await agent.get(`/api/v1/dashboard/chat/messages?user_id=${A}&conversation_id=-1&platform=wechat`);
 expect(after.body.total).toBe(1);expect(after.body.messages[0].id).toBe(second);
 expect((await pool.query('SELECT COUNT(*) FROM chat_conversation')).rows[0].count).toBe('5');
});

test('待确认集合导航与批量删除仅作用于明确图片，确认后移出即整批拒绝',async()=>{
 const p=await conversation(),q=await conversation(),known=await conversation();
 for(const id of [p,q])await pool.query("UPDATE chat_conversation SET identity_confidence=.55,display_name='待确认会话' WHERE id=$1",[id]);
 async function picture(c:number,time:string){const m=await message(c,{time});const hash=createHash('sha256').update(m).digest('hex');
  const a=Number((await pool.query("INSERT INTO media_asset(user_id,sha256,mime_type,storage_path,byte_size) VALUES($1,$2,'image/png',$3,4) RETURNING id",[A,hash,`chat/${hash.slice(0,2)}/${hash}.png`])).rows[0].id);
  await pool.query("INSERT INTO chat_message_asset(message_id,asset_id,position,role) VALUES($1,$2,0,'content')",[m,a]);return {message_id:m,asset_id:a};}
 const a=await picture(p,'2026-09-18T03:00:00Z'),b=await picture(q,'2026-09-18T02:00:00Z'),c=await picture(known,'2026-09-18T01:00:00Z');
 const nav=await agent.get('/api/v1/dashboard/chat/images/adjacent').query({user_id:A,conversation_id:-1,platform:'wechat',...a,direction:'next'});
 expect(nav.status).toBe(200);expect(nav.body.image).toMatchObject({...b,total:2});
 const remove=(images:any[])=>agent.post(`/api/v1/dashboard/chat/images/delete-batch?user_id=${A}`).send({confirm:'DELETE',conversation_id:-1,platform:'wechat',images});
 expect((await remove([a,c])).status).toBe(409);expect((await pool.query('SELECT COUNT(*) FROM chat_message_asset')).rows[0].count).toBe('3');
 await pool.query("UPDATE chat_conversation SET identity_confidence=.85,display_name='已确认联系人' WHERE id=$1",[q]);
 expect((await remove([a,b])).status).toBe(409);
 const deleted=await remove([a]);expect(deleted.status, JSON.stringify(deleted.body)).toBe(200);expect(deleted.body.deleted_images).toBe(1);
 expect((await pool.query('SELECT COUNT(*) FROM chat_message_asset')).rows[0].count).toBe('2');
});

test('待确认虚拟入口分页不重复，旧接口保持真实来源，模糊搜索不返回虚拟联系人',async()=>{
 const p=await conversation(),a=await conversation(),b=await conversation();
 await pool.query("UPDATE chat_conversation SET identity_confidence=.55,display_name='待确认会话' WHERE id=$1",[p]);await message(p);
 const get=(query:string)=>agent.get(`/api/v1/dashboard/chat/conversations?user_id=${A}&platform=wechat&${query}`);
 const pages=[];for(let page=1;page<=3;page++)pages.push((await get(`group_pending=true&page_size=1&page=${page}`)).body);
 expect(pages.map(p=>p.total)).toEqual([3,3,3]);expect(pages[0].conversations[0].id).toBe(-1);
 expect(new Set(pages.flatMap(p=>p.conversations.map((r:any)=>r.id)))).toEqual(new Set([-1,a,b]));
 expect((await get('')).body.conversations.map((r:any)=>r.id)).toContain(p);
 expect((await get('group_pending=true&q=待确认')).body.conversations).toEqual([]);
 expect((await agent.get(`/api/v1/dashboard/chat/conversations?user_id=${A}&group_pending=true`)).status).toBe(400);
});

test('同手机App同名先聚合再分页，待确认高置信度旧占位也去掉字符串',async()=>{
 const a=await conversation(),b=await conversation(),other=await conversation(),qq=await conversation('qq'),foreign=await conversation('wechat',B),p=await conversation(),q=await conversation();
 for(const id of [a,b,qq,foreign])await pool.query("UPDATE chat_conversation SET display_name='同名联系人' WHERE id=$1",[id]);
 await pool.query("UPDATE chat_conversation SET display_name='另一个人' WHERE id=$1",[other]);
 await pool.query("UPDATE chat_conversation SET display_name='待确认会话 abc12345',identity_confidence=.85 WHERE id=$1",[p]);
 await pool.query("UPDATE chat_conversation SET display_name='待确认会话 def67890',identity_confidence=.55 WHERE id=$1",[q]);
 for(const id of [a,b,other,qq,p,q])await message(id);await message(foreign,{user:B});
 const get=(extra='')=>agent.get(`/api/v1/dashboard/chat/conversations?user_id=${A}&platform=wechat&group_pending=true&group_names=true${extra}`);
 const result=await get();expect(result.status).toBe(200);expect(result.body.total).toBe(3);
 const grouped=result.body.conversations.find((r:any)=>r.display_name==='同名联系人');
 expect(grouped).toMatchObject({id:Math.min(a,b),group_name:'同名联系人',is_name_group:true,source_count:2,source_ids:[a,b],message_count:2});
 expect(result.body.conversations.filter((r:any)=>r.display_name.startsWith('待确认'))).toEqual([expect.objectContaining({id:-1,display_name:'待确认会话',message_count:2})]);
 const pages=[];for(let page=1;page<=3;page++)pages.push((await get(`&page_size=1&page=${page}`)).body.conversations[0].id);
 expect(new Set(pages).size).toBe(3);
 expect((await get('&name='+encodeURIComponent('同名联系人'))).body.conversations).toEqual([grouped]);
 await pool.query("UPDATE chat_conversation SET display_name='同名联系人 ' WHERE id=$1",[b]);
 expect((await get()).body.conversations.find((r:any)=>r.group_name==='同名联系人').message_count).toBe(2);
 expect((await pool.query('SELECT COUNT(*) FROM chat_conversation')).rows[0].count).toBe('7');
});

test('同名会话读取涵盖历史及后来来源，不混相似名字或跨手机App',async()=>{
 const a=await conversation(),b=await conversation(),c=await conversation(),d=await conversation('qq'),foreign=await conversation('wechat',B);
 for(const id of [a,b,d,foreign])await pool.query("UPDATE chat_conversation SET display_name='王彦兵' WHERE id=$1",[id]);
 await pool.query("UPDATE chat_conversation SET display_name='王彦斌' WHERE id=$1",[c]);
 const old=await message(a,{time:'2026-09-17T01:00:00Z'}),recent=await message(b,{time:'2026-09-18T01:00:00Z'});
 await message(c);await message(d);await message(foreign,{user:B});
 const get=(extra='')=>agent.get('/api/v1/dashboard/chat/messages').query({user_id:A,conversation_id:a,platform:'wechat',group_name:'王彦兵',page_size:1,...(extra?{page:2}:{})});
 const first=await get();expect(first.status).toBe(200);expect(first.body.total).toBe(2);expect(first.body.messages[0].id).toBe(recent);
 expect((await get('next')).body.messages[0].id).toBe(old);
 const later=await conversation();await pool.query("UPDATE chat_conversation SET display_name='王彦兵' WHERE id=$1",[later]);await message(later);
 expect((await get()).body.total).toBe(3);
 expect((await agent.get('/api/v1/dashboard/chat/messages').query({user_id:A,conversation_id:a,group_name:'王彦兵'})).status).toBe(400);
});

async function groupPicture(c:number,time='2026-09-18T03:00:00Z') {
 const m=await message(c,{time}),hash=createHash('sha256').update(m).digest('hex');
 const id=Number((await pool.query("INSERT INTO media_asset(user_id,sha256,mime_type,storage_path,byte_size) VALUES($1,$2,'image/png',$3,4) RETURNING id",[A,hash,`chat/${hash.slice(0,2)}/${hash}.png`])).rows[0].id);
 await pool.query("INSERT INTO chat_message_asset(message_id,asset_id,position,role) VALUES($1,$2,0,'content')",[m,id]);return {message_id:m,asset_id:id};
}
test('同名组图片跨真实来源导航与批量删除，改名移出后整批拒绝',async()=>{
 const a=await conversation(),b=await conversation(),other=await conversation();
 for(const id of [a,b])await pool.query("UPDATE chat_conversation SET display_name='同名' WHERE id=$1",[id]);
 const first=await groupPicture(a),next=await groupPicture(b,'2026-09-17T01:00:00Z'),outside=await groupPicture(other);
 const scope={user_id:A,conversation_id:a,platform:'wechat',group_name:'同名'};
 const nav=await agent.get('/api/v1/dashboard/chat/images/adjacent').query({...scope,...first,direction:'next'});
 expect(nav.status).toBe(200);expect(nav.body.image).toMatchObject({...next,total:2});
 const remove=(images:any[])=>agent.post(`/api/v1/dashboard/chat/images/delete-batch?user_id=${A}`).send({...scope,confirm:'DELETE',images});
 expect((await remove([first,outside])).status).toBe(409);
 await pool.query("UPDATE chat_conversation SET display_name='改名' WHERE id=$1",[b]);
 expect((await remove([first,next])).status).toBe(409);
 expect((await pool.query('SELECT COUNT(*) FROM chat_message_asset')).rows[0].count).toBe('3');
 await pool.query("UPDATE chat_conversation SET display_name='同名' WHERE id=$1",[b]);
 const done=await remove([first,next]);expect(done.status).toBe(200);expect(done.body.deleted_images).toBe(2);
 expect((await pool.query('SELECT COUNT(*) FROM chat_message_asset')).rows[0].count).toBe('1');
});
test('删除同名展示组必须匹配明确来源快照，不能只删除代表ID或扩大到新来源',async()=>{
 const a=await conversation(),b=await conversation(),other=await conversation();
 for(const id of [a,b])await pool.query("UPDATE chat_conversation SET display_name='同名' WHERE id=$1",[id]);
 await groupPicture(a);await groupPicture(b);await groupPicture(other);
 const remove=(ids:number[])=>agent.post(`/api/v1/dashboard/chat/conversation-groups/delete?user_id=${A}`).send({confirm:'DELETE',platform:'wechat',group_name:'同名',source_ids:ids});
 expect((await remove([a])).status).toBe(409);
 expect((await pool.query('SELECT COUNT(*) FROM chat_message')).rows[0].count).toBe('3');
 const done=await remove([a,b]);expect(done.status).toBe(200);expect(done.body).toMatchObject({deleted_messages:2,deleted_sources:2});
 expect((await pool.query('SELECT id FROM chat_conversation')).rows.map(r=>Number(r.id))).toEqual([other]);
});

test('带前导空格的高置信度待确认占位也归统一桶，旧分组查询保留来源字段',async()=>{
 const pending=await conversation(),known=await conversation();await message(pending);await message(known);
 await pool.query("UPDATE chat_conversation SET display_name=' 待确认会话 abc123 ',identity_confidence=.85 WHERE id=$1",[pending]);
 await pool.query("UPDATE chat_conversation SET display_name='联系人',metadata='{\"fixture\":true}' WHERE id=$1",[known]);
 const list=await agent.get('/api/v1/dashboard/chat/conversations').query({user_id:A,platform:'wechat',group_names:true});
 expect(list.body.conversations.map((c:any)=>c.display_name)).toEqual(['待确认会话','联系人']);
 const resolve=await agent.get(`/api/v1/dashboard/chat/conversations/${pending}/resolve`).query({user_id:A});expect(resolve.body.conversation.is_pending_source).toBe(true);
 const old=await agent.get('/api/v1/dashboard/chat/conversations').query({user_id:A,platform:'wechat',group_pending:true});
 expect(old.body.conversations.find((c:any)=>c.id===known)).toMatchObject({user_id:A,metadata:{fixture:true},merged_into_id:null});
});

test('名称组删除对新增来源及跨用户App伪造快照整批拒绝，保留共享资产文件',async()=>{
 const a=await conversation(),b=await conversation(),qq=await conversation('qq'),foreign=await conversation('wechat',B),other=await conversation();
 for(const id of [a,b,qq,foreign])await pool.query("UPDATE chat_conversation SET display_name='同名' WHERE id=$1",[id]);
 await pool.query("UPDATE chat_conversation SET display_name='其他人' WHERE id=$1",[other]);
 const pic=await groupPicture(a), second=await groupPicture(b);await message(qq);await message(foreign,{user:B});
 const otherMessage=await message(other);
 await pool.query("INSERT INTO chat_message_asset(message_id,asset_id,position,role) VALUES($1,$2,0,'content')",[otherMessage,pic.asset_id]);
 const storage=(await pool.query('SELECT storage_path FROM media_asset WHERE id=$1',[pic.asset_id])).rows[0].storage_path;
 const file=join(root,'uploads',storage);await mkdir(dirname(file),{recursive:true});await writeFile(file,'shared');
 const secondRow=(await pool.query('SELECT * FROM media_asset WHERE id=$1',[second.asset_id])).rows[0];
 await pool.query('INSERT INTO media_asset(user_id,sha256,mime_type,storage_path,byte_size) VALUES($1,$2,$3,$4,$5)',[B,secondRow.sha256,secondRow.mime_type,secondRow.storage_path,secondRow.byte_size]);
 const sharedPath=join(root,'uploads',secondRow.storage_path);await mkdir(dirname(sharedPath),{recursive:true});await writeFile(sharedPath,'shared-across-users');
 const remove=(ids:number[],platform='wechat',user=A)=>agent.post('/api/v1/dashboard/chat/conversation-groups/delete').query({user_id:user}).send({confirm:'DELETE',platform,group_name:'同名',source_ids:ids});
 expect((await remove([a,b,qq])).status).toBe(409);expect((await remove([a,b,foreign])).status).toBe(409);
 expect((await remove([a,b],'qq')).status).toBe(409);expect((await remove([a,b],'wechat',B)).status).toBe(409);
 const later=await conversation();await pool.query("UPDATE chat_conversation SET display_name='同名' WHERE id=$1",[later]);await message(later);
 expect((await remove([a,b])).status).toBe(409);expect((await pool.query('SELECT count(*) FROM chat_message')).rows[0].count).toBe('6');
 const done=await remove([a,b,later]);expect(done.status).toBe(200);expect(done.body.deleted_messages).toBe(3);
 expect((await pool.query('SELECT id FROM media_asset WHERE id=$1',[pic.asset_id])).rowCount).toBe(1);expect(readFileSync(file,'utf8')).toBe('shared');
 expect((await pool.query('SELECT id FROM media_asset WHERE id=$1',[second.asset_id])).rowCount).toBe(0);expect(readFileSync(sharedPath,'utf8')).toBe('shared-across-users');
 expect((await pool.query('SELECT count(*) FROM chat_message')).rows[0].count).toBe('3');
});

test('微信OCR固定页面的旧错字标签集中展示，不改原始数据也不合并同名真实联系人',async()=>{
 const ids=[];for(const name of ['朋友圈','朋友屠','用友殿','田友殿']){const c=await conversation();ids.push(c);await pool.query("UPDATE chat_conversation SET account_key='wechat-empty-tree',display_name=$2 WHERE id=$1",[c,name]);await message(c);}
 const peer=await conversation();await pool.query("UPDATE chat_conversation SET display_name='朋友屠' WHERE id=$1",[peer]);await message(peer);
 const list=await agent.get('/api/v1/dashboard/chat/conversations').query({user_id:A,platform:'wechat',group_names:true});
 expect(list.status).toBe(200);expect(list.body.conversations).toHaveLength(2);expect(list.body.conversations.find((r:any)=>r.group_name==='朋友圈')).toMatchObject({message_count:4,source_ids:ids});
 const messages=await agent.get('/api/v1/dashboard/chat/messages').query({user_id:A,platform:'wechat',conversation_id:ids[0],group_name:'朋友圈'});expect(messages.body.total).toBe(4);
 const resolved=await agent.get(`/api/v1/dashboard/chat/conversations/${ids[1]}/resolve`).query({user_id:A});expect(resolved.body.conversation.display_name).toBe('朋友圈');
 expect((await pool.query('SELECT display_name FROM chat_conversation WHERE id=$1',[ids[1]])).rows[0].display_name).toBe('朋友屠');
});

test('截断简称独立显示且不参与待确认桶或按同名读取删除',async()=>{
 const ids=[await conversation(),await conversation()];const name='测试…店5337（名称被截断）';
 for(const id of ids){await pool.query("UPDATE chat_conversation SET external_key=$2,display_name=$3,identity_confidence=.55 WHERE id=$1",[id,'screenshot-v2:truncated:'+randomUUID(),name]);await message(id);}
 const list=await agent.get(`/api/v1/dashboard/chat/conversations?user_id=${A}&platform=wechat&group_names=true&group_pending=true`);
 expect(list.status).toBe(200);expect(list.body.total).toBe(2);
 expect(list.body.conversations.map((c:any)=>c.id).sort()).toEqual([...ids].sort());
 for(const c of list.body.conversations){expect(c).toMatchObject({display_name:name,group_name:null,is_name_group:false,source_count:1,message_count:1});}
 const resolved=await agent.get(`/api/v1/dashboard/chat/conversations/${ids[0]}/resolve?user_id=${A}`);
 expect(resolved.body.conversation).toMatchObject({display_name:name,is_pending_source:false});
 const single=await agent.get(`/api/v1/dashboard/chat/messages?user_id=${A}&conversation_id=${ids[0]}`);
 expect(single.body.total).toBe(1);expect(single.body.messages[0].conversation_id).toBe(ids[0]);
 const grouped=await agent.get(`/api/v1/dashboard/chat/messages?user_id=${A}&conversation_id=${ids[0]}&platform=wechat&group_name=${encodeURIComponent(name)}`);
 expect(grouped.status).toBe(200);expect(grouped.body.total).toBe(0);
 const deletion=await agent.post(`/api/v1/dashboard/chat/conversation-groups/delete?user_id=${A}`).send({platform:'wechat',group_name:name,source_ids:ids,confirm:'DELETE'});
 expect(deletion.status).toBe(409);expect((await pool.query('SELECT COUNT(*) FROM chat_message')).rows[0].count).toBe('2');
});

test('截断群名真实以待确认开头仍显示其可见名称',async()=>{
 const id=await conversation(),name='待确认订单…门店（名称被截断）';
 await pool.query('UPDATE chat_conversation SET external_key=$2,display_name=$3,identity_confidence=.55 WHERE id=$1',[id,'screenshot-v2:truncated:'+randomUUID(),name]);await message(id);
 const list=await agent.get(`/api/v1/dashboard/chat/conversations?user_id=${A}&platform=wechat&group_names=true&group_pending=true`);
 expect(list.body.conversations).toHaveLength(1);expect(list.body.conversations[0]).toMatchObject({id,display_name:name,is_name_group:false});
 const resolved=await agent.get(`/api/v1/dashboard/chat/conversations/${id}/resolve?user_id=${A}`);
 expect(resolved.body.conversation.is_pending_source).toBe(false);
});


const batchRemove=(conversations:any[],platform='wechat',user=A,confirm='DELETE')=>agent.post('/api/v1/dashboard/chat/conversations/delete-batch').query({user_id:user}).send({confirm,platform,conversations});
test('批量会话删除在一个事务中处理同名组和独立来源，保留未选择会话及共享图片',async()=>{
 const a=await conversation(),b=await conversation(),single=await conversation(),other=await conversation();
 for(const id of [a,b])await pool.query("UPDATE chat_conversation SET display_name='同名' WHERE id=$1",[id]);
 const shared=await groupPicture(a);await groupPicture(b);await message(single);const remaining=await message(other);
 await pool.query("INSERT INTO chat_message_asset(message_id,asset_id,position,role) VALUES($1,$2,0,'content')",[remaining,shared.asset_id]);
 const result=await batchRemove([{group_name:'同名',source_ids:[a,b]},{id:single}]);
 expect(result.status,JSON.stringify(result.body)).toBe(200);expect(result.body).toMatchObject({deleted_conversations:2,deleted_sources:3,deleted_messages:3});
 expect((await pool.query('SELECT id FROM chat_conversation')).rows.map(r=>Number(r.id))).toEqual([other]);
 expect((await pool.query('SELECT id FROM media_asset WHERE id=$1',[shared.asset_id])).rowCount).toBe(1);
});
test('批量会话删除对非法重复和重叠来源拒绝且不修改任何数据',async()=>{
 const a=await conversation();await message(a);
 for(const entries of [[],[{id:-1}],[{id:0}],[{id:'1'}],[{id:a},{id:a}],[{group_name:'同名',source_ids:[a,a]}],[{group_name:'同名',source_ids:[a]},{id:a}],[{id:a,group_name:'同名',source_ids:[a]}],Array.from({length:102},(_,i)=>({id:i+1})),[{group_name:'同名',source_ids:Array.from({length:1001},(_,i)=>i+1)}]]){
  expect((await batchRemove(entries)).status,JSON.stringify(entries).slice(0,100)).toBe(400);
 }
 expect((await batchRemove([{id:a}],'wechat',A,'')).status).toBe(400);
 expect((await batchRemove([{id:a}],'bad')).status).toBe(400);
 expect((await pool.query('SELECT count(*) FROM chat_message')).rows[0].count).toBe('1');
});
test('任一选中会话跨用户App或已合并，整批会话删除回滚',async()=>{
 const a=await conversation(),foreign=await conversation('wechat',B),qq=await conversation('qq'),source=await conversation(),target=await conversation();
 await message(a);await message(foreign,{user:B});await message(qq);await merge(source,target);
 for(const id of [foreign,qq,source,999999])expect((await batchRemove([{id:a},{id}])).status).toBe(409);
 expect((await batchRemove([{id:a}],'wechat',B)).status).toBe(409);
 expect((await pool.query('SELECT count(*) FROM chat_message')).rows[0].count).toBe('3');
});
test('一个同名组来源新增或改名时，其他选中会话也不能先删除',async()=>{
 const a=await conversation(),b=await conversation(),single=await conversation();
 for(const id of [a,b]){await pool.query("UPDATE chat_conversation SET display_name='同名' WHERE id=$1",[id]);await message(id);}await message(single);
 expect((await batchRemove([{id:single},{group_name:'同名',source_ids:[a]}])).status).toBe(409);
 await pool.query("UPDATE chat_conversation SET display_name='改名' WHERE id=$1",[b]);
 expect((await batchRemove([{id:single},{group_name:'同名',source_ids:[a,b]}])).status).toBe(409);
 expect((await pool.query('SELECT count(*) FROM chat_message')).rows[0].count).toBe('3');
});


test('批量会话删除清理独占文件，保留其他手机引用的同路径文件和关联会话数据',async()=>{
 const a=await conversation(),b=await conversation(),other=await conversation();const first=await groupPicture(a),second=await groupPicture(b);await message(other);
 const firstRow=(await pool.query('SELECT * FROM media_asset WHERE id=$1',[first.asset_id])).rows[0],secondRow=(await pool.query('SELECT * FROM media_asset WHERE id=$1',[second.asset_id])).rows[0];
 for(const row of [firstRow,secondRow]){const file=join(root,'uploads',row.storage_path);await mkdir(dirname(file),{recursive:true});await writeFile(file,'fixture');}
 await pool.query('INSERT INTO media_asset(user_id,sha256,mime_type,storage_path,byte_size) VALUES($1,$2,$3,$4,$5)',[B,secondRow.sha256,secondRow.mime_type,secondRow.storage_path,secondRow.byte_size]);
 for(const id of [a,b,other])await pool.query('INSERT INTO relationship_profile(user_id,conversation_id) VALUES($1,$2)',[A,id]);
 const result=await batchRemove([{id:a},{id:b}]);expect(result.status).toBe(200);expect(result.body.files_pending).toBe(false);
 expect(existsSync(join(root,'uploads',firstRow.storage_path))).toBe(false);
 expect(readFileSync(join(root,'uploads',secondRow.storage_path),'utf8')).toBe('fixture');
 expect((await pool.query('SELECT conversation_id FROM relationship_profile')).rows.map(r=>Number(r.conversation_id))).toEqual([other]);
});
test('同名组批量删除禁止混入其他手机App来源和待确认汇总，重复组名不能拆分提交',async()=>{
 const a=await conversation(),b=await conversation('qq'),foreign=await conversation('wechat',B),pending=await conversation();
 for(const id of [a,b,foreign])await pool.query("UPDATE chat_conversation SET display_name='同名' WHERE id=$1",[id]);
 await pool.query("UPDATE chat_conversation SET display_name='待确认会话' WHERE id=$1",[pending]);
 for(const id of [a,b,pending])await message(id);await message(foreign,{user:B});
 for(const ids of [[a,b],[a,foreign],[pending]])expect((await batchRemove([{group_name:'同名',source_ids:ids}])).status).toBe(409);
 expect((await batchRemove([{id:pending}])).status).toBe(409);
 expect((await batchRemove([{group_name:'同名',source_ids:[a]},{group_name:'同名',source_ids:[b]}])).status).toBe(400);
 expect((await pool.query('SELECT count(*) FROM chat_message')).rows[0].count).toBe('4');
});
