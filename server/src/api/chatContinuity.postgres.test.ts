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
