import { randomUUID, createHash } from 'node:crypto';
import { readFileSync, readdirSync, realpathSync } from 'node:fs';
import { mkdir } from 'node:fs/promises';
import { join } from 'node:path';
import pg from 'pg';
import express from 'express';
import request from 'supertest';
import { beforeAll, beforeEach, afterAll, expect, it, vi } from 'vitest';
import { createChatDashboardRouter } from './chatDashboard.js';
const cluster = process.env.CHAT_RETENTION_TEST_CLUSTER;
if (cluster && (!cluster.startsWith('/tmp/shurufa-chat-retention.') || readFileSync(join(cluster,'test-instance-only'),'utf8') !== 'retention-test-only')) throw Error('非独立测试实例');
const test = cluster ? it : it.skip;
const A=randomUUID(),B=randomUUID(); let pool:pg.Pool,app:ReturnType<typeof express>;
beforeAll(async()=>{
 if(!cluster)return;
 pool=new pg.Pool({host:join(cluster,'socket'),port:5432,user:'ko',database:'chat_retention_test'});
 const identity=(await pool.query("SELECT current_setting('data_directory') AS dir,current_database() AS db")).rows[0];
 expect(realpathSync(identity.dir)).toBe(realpathSync(join(cluster,'data')));expect(identity.db).toBe('chat_retention_test');
 for(const file of readdirSync(new URL('../../migrations/',import.meta.url)).filter(f=>f.endsWith('.sql')).sort()) await pool.query(readFileSync(new URL(`../../migrations/${file}`,import.meta.url),'utf8'));
 await mkdir(join(cluster,'uploads/chat'),{recursive:true});vi.spyOn(process,'cwd').mockReturnValue(cluster);
});
beforeEach(async()=>{
 if(!cluster)return;await pool.query('TRUNCATE chat_message,chat_conversation,media_asset CASCADE');
 app=express();app.use(express.json());app.use((req,res,next)=>{res.locals.userId=req.query.user_id||A;next();});app.use(createChatDashboardRouter(pool));
});
afterAll(async()=>{vi.restoreAllMocks();await pool?.end();});
async function conversation(platform='wechat',user=A,pending=true){return Number((await pool.query(`INSERT INTO chat_conversation(user_id,platform,account_key,external_key,display_name,conversation_type,identity_confidence) VALUES($1,$2,'self',$3,$4,'direct',$5) RETURNING id`,[user,platform,randomUUID(),pending?'待确认会话':'已确认',pending?.5:1])).rows[0].id);}
async function message(c:number,time='2020-01-01T00:00:00Z',type='text',user=A){const id=randomUUID();await pool.query(`INSERT INTO chat_message(id,user_id,device_id,conversation_id,platform,fingerprint,content_fingerprint,sender_key,direction,message_type,text,captured_at) SELECT $1,$2,$2,id,platform,$3,$3,'peer','incoming',$4,'合成测试',$5 FROM chat_conversation WHERE id=$6`,[id,user,createHash('sha256').update(id).digest('hex'),type,time,c]);return id;}
const preview=(days=7,include_images=false,platform='wechat',user=A)=>request(app).post('/pending/cleanup/preview').query({user_id:user}).send({days,include_images,platform});
const batch=(token:string,offset=0,user=A)=>request(app).post('/pending/cleanup/batch').query({user_id:user}).send({confirm:'DELETE',token,offset});
const visible=async()=> (await pool.query("SELECT id FROM chat_message WHERE COALESCE(metadata->>'screenshot_deleted','')<>'true' ORDER BY id")).rows.map(r=>r.id);

test('7/30天预览固定截止时间，排除新记录/其他App/其他手机/已确认/图片；没有预览不删除',async()=>{
 const c=await conversation();const old=await message(c),recent=await message(c,new Date().toISOString()),pic=await message(c,undefined,'image');
 await message(await conversation('qq'));await message(await conversation('wechat',B),undefined,'text',B);await message(await conversation('wechat',A,false));
 const start=Date.now();const p=await preview();expect(p.status).toBe(200);expect(p.body.total_messages).toBe(1);expect(p.body.total_images).toBe(0);
 expect(Date.parse(p.body.cutoff)).toBeGreaterThanOrEqual(start-7*86400000);expect(Date.parse(p.body.cutoff)).toBeLessThanOrEqual(Date.now()-7*86400000);
 expect((await batch('unknown')).status).toBe(410);
 const result=await batch(p.body.token);expect(result.status).toBe(200);expect(result.body).toMatchObject({done:true,deleted_messages:1,processed:1});
 expect(await visible()).toEqual(expect.arrayContaining([recent,pic]));expect(await visible()).not.toContain(old);
 const thirty=await preview(30,true);expect(thirty.status).toBe(200);expect(Date.parse(thirty.body.cutoff)).toBeLessThanOrEqual(Date.now()-30*86400000);
});

test('精确边界保留；预览之后新到达的旧消息不扩大删除范围',async()=>{
 const c=await conversation();const now=Date.now();const clock=vi.spyOn(Date,'now').mockReturnValue(now);
 try{const old=await message(c,new Date(now-7*86400000-1).toISOString()),boundary=await message(c,new Date(now-7*86400000).toISOString());
 const p=await preview();expect(p.status).toBe(200);const late=await message(c);await batch(p.body.token);
 expect(await visible()).toEqual([boundary,late].sort());expect(await visible()).not.toContain(old);
 }finally{clock.mockRestore();}
});

test('跨页5201条自动分批，重试旧游标不继续删除，凭据隔离手机',async()=>{
 const c=await conversation();await pool.query(`INSERT INTO chat_message(id,user_id,device_id,conversation_id,platform,fingerprint,content_fingerprint,sender_key,direction,message_type,captured_at) SELECT gen_random_uuid(),$1,$1,$2,'wechat',md5(n::text)||md5(n::text),md5(n::text)||md5(n::text),'peer','incoming','text','2020-01-01' FROM generate_series(1,5201)n`,[A,c]);
 const p=await preview();expect(p.status).toBe(200);expect(p.body.total_messages).toBe(5201);
 expect((await batch(p.body.token,0,B)).status).toBe(410);
 let r=await batch(p.body.token);expect(r.status,JSON.stringify(r.body)).toBe(200);expect(r.body.processed).toBe(200);
 const retry=await batch(p.body.token);expect(retry.body).toEqual(r.body);
 while(!r.body.done){r=await batch(p.body.token,r.body.processed);expect(r.status,JSON.stringify(r.body)).toBe(200);}
 expect(r.body.deleted_messages).toBe(5201);expect(await visible()).toEqual([]);
});

test('执行时跳过已确认及移动来源的记录',async()=>{
 const c=await conversation(),other=await conversation();const first=await message(c),moved=await message(other);
 const p=await preview();expect(p.status).toBe(200);
 await pool.query("UPDATE chat_conversation SET display_name='已确认',identity_confidence=1 WHERE id=$1",[c]);await pool.query('UPDATE chat_message SET conversation_id=$1 WHERE id=$2',[c,moved]);
 const r=await batch(p.body.token);expect(r.status,JSON.stringify(r.body)).toBe(200);expect(r.body).toMatchObject({deleted_messages:0,skipped_messages:2,done:true});expect(await visible()).toEqual([first,moved].sort());
});

test('参数必须明确7或30天及图片范围，确认标记/游标/过期校验',async()=>{
 for(const days of [0,1,8,31,'7'])expect((await preview(days as number)).status).toBe(400);
 expect((await preview(7,false,'invalid')).status).toBe(400);
 const c=await conversation();await message(c);const p=await preview();expect(p.status).toBe(200);
 expect((await request(app).post('/pending/cleanup/batch').send({token:p.body.token,offset:0})).status).toBe(400);
 expect((await batch(p.body.token,1)).status).toBe(409);
 const clock=vi.spyOn(Date,'now').mockReturnValue(Date.now()+16*60000);try{expect((await batch(p.body.token)).status).toBe(410);}finally{clock.mockRestore();}
});

async function asset(messageId:string,mime='image/png') {
 const {writeFile}=await import('node:fs/promises');const hash=createHash('sha256').update(randomUUID()).digest('hex');
 const storage=`chat/${hash.slice(0,2)}/${hash}.png`;await mkdir(join(cluster!,'uploads/chat',hash.slice(0,2)),{recursive:true});await writeFile(join(cluster!,'uploads',storage),'synthetic');
 const id=String((await pool.query(`INSERT INTO media_asset(user_id,sha256,mime_type,storage_path,byte_size) VALUES($1,$2,$3,$4,9) RETURNING id`,[A,createHash('sha256').update(storage).digest('hex'),mime,storage])).rows[0].id);
 await pool.query("INSERT INTO chat_message_asset(message_id,asset_id,role,position) VALUES($1,$2,'content',0)",[messageId,id]);return {id,storage};
}
test('默认排除夹带图片的文字；包含图片时清理独占附件、保留已确认记录共享文件',async()=>{
 const {existsSync}=await import('node:fs');const c=await conversation(),known=await conversation('wechat',A,false);
 const mixed=await message(c),image=await message(c,undefined,'image'),keep=await message(known,undefined,'image');
 const shared=await asset(mixed),unique=await asset(image);await pool.query("INSERT INTO chat_message_asset(message_id,asset_id,role) VALUES($1,$2,'content')",[keep,shared.id]);
 expect((await preview()).body.total_messages).toBe(0);
 const p=await preview(7,true);expect(p.body).toMatchObject({total_messages:2,total_images:2});
 const r=await batch(p.body.token);expect(r.status,JSON.stringify(r.body)).toBe(200);expect(r.body).toMatchObject({done:true,deleted_messages:2,files_pending:false});
 expect(await visible()).toEqual([keep]);expect(existsSync(join(cluster!,'uploads',unique.storage))).toBe(false);expect(existsSync(join(cluster!,'uploads',shared.storage))).toBe(true);
 expect((await pool.query('SELECT id FROM media_asset')).rows.map(r=>r.id)).toEqual([shared.id]);
});
test('预览后增加附件或修改正文，整条记录跳过',async()=>{
 const c=await conversation(),first=await message(c),second=await message(c);
 const p=await preview(7,true);await asset(first);await pool.query("UPDATE chat_message SET text='后来更正' WHERE id=$1",[second]);
 const r=await batch(p.body.token);expect(r.status,JSON.stringify(r.body)).toBe(200);expect(r.body).toMatchObject({deleted_messages:0,skipped_messages:2});expect(await visible()).toHaveLength(2);
});
test('重复截图收据清理同一附件但保留幂等标记，不影响其他App',async()=>{
 const {existsSync}=await import('node:fs');const c=await conversation(),root=await message(c,undefined,'image'),duplicate=await message(c,undefined,'image');const a=await asset(root);
 await pool.query("UPDATE chat_message SET text='聊天截图',metadata=jsonb_build_object('screenshot_duplicate_of',$2::text) WHERE id=$1",[duplicate,root]);
 await pool.query("INSERT INTO chat_message_asset(message_id,asset_id,role) VALUES($1,$2,'content')",[duplicate,a.id]);
 const p=await preview(7,true);expect(p.body.total_messages).toBe(1);const r=await batch(p.body.token);expect(r.status,JSON.stringify(r.body)).toBe(200);expect(r.body.deleted_messages).toBe(1);
 expect((await pool.query('SELECT message_id FROM chat_message_asset')).rowCount).toBe(0);
 expect(existsSync(join(cluster!,'uploads',a.storage))).toBe(false);
 const rows=(await pool.query('SELECT id,text,metadata FROM chat_message ORDER BY id')).rows;expect(rows).toHaveLength(2);
 expect(rows.find(row=>row.id===root)).toMatchObject({text:null,metadata:{screenshot_deleted:true,screenshot_assets_deleted:true}});
 expect(rows.find(row=>row.id===duplicate).metadata.screenshot_deleted).toBe(true);
 expect((await preview(7,true)).body.total_messages).toBe(0);
});

test('附件路径异常整批回滚，修复后相同游标可重试',async()=>{
 const c=await conversation(),text=await message(c),image=await message(c,undefined,'image'),a=await asset(image);
 await pool.query("UPDATE media_asset SET storage_path='../outside.png' WHERE id=$1",[a.id]);const p=await preview(7,true);
 expect((await batch(p.body.token)).status).toBe(409);expect(await visible()).toEqual([text,image].sort());expect((await pool.query('SELECT message_id FROM chat_message_asset')).rowCount).toBe(1);
 await pool.query('UPDATE media_asset SET storage_path=$2 WHERE id=$1',[a.id,a.storage]);
 const r=await batch(p.body.token);expect(r.status,JSON.stringify(r.body)).toBe(200);expect(r.body).toMatchObject({deleted_messages:2,processed:2,done:true});
});
