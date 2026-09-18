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
