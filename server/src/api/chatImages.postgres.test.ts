import { randomUUID, createHash } from 'node:crypto';
import { readFileSync, readdirSync, realpathSync, existsSync } from 'node:fs';
import { mkdir, writeFile, chmod } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import pg from 'pg';
import request from 'supertest';
import { beforeAll, beforeEach, afterAll, expect, it, vi } from 'vitest';
import { createApp } from '../app.js';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';
import { storeAsset } from '../chat/assetStorage.js';
import { retryDeviceFileCleanup } from '../lib/deleteDeviceData.js';
const cluster = process.env.CHAT_IMAGES_TEST_CLUSTER;
if (cluster && (!cluster.startsWith('/tmp/shurufa-chat-images.') || readFileSync(join(cluster,'test-instance-only'),'utf8') !== 'chat-images-only')) throw Error('独立测试实例验证失败');
const test = cluster ? it : it.skip;
const A=randomUUID(), B=randomUUID();
const migrations=readdirSync(new URL('../../migrations/',import.meta.url)).filter(f=>f.endsWith('.sql')).sort().map(f=>readFileSync(new URL(`../../migrations/${f}`,import.meta.url),'utf8'));
let pool:pg.Pool, app:ReturnType<typeof createApp>, agent:Awaited<ReturnType<typeof authenticatedRequest>>, root:string, verified=false;
beforeAll(async()=>{
 if(!cluster)return;
 pool=new pg.Pool({host:join(cluster,'socket'),port:5432,user:'ko',database:'chat_images_test',max:4});
 const identity=(await pool.query("SELECT current_setting('data_directory') AS dir,current_database() AS db")).rows[0];
 expect(realpathSync(identity.dir)).toBe(realpathSync(join(cluster,'data')));expect(identity.db).toBe('chat_images_test');verified=true;
});
beforeEach(async()=>{
 if(!cluster)return;if(!verified)throw Error('禁止业务库测试');
 const schema='test_'+randomUUID().replaceAll('-','');await pool.end();
 pool=new pg.Pool({host:join(cluster,'socket'),port:5432,user:'ko',database:'chat_images_test',max:4,options:`-c search_path=${schema}`});
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
async function asset(user=A,hash=createHash('sha256').update(randomUUID()).digest('hex')){
 const path=`chat/${hash.slice(0,2)}/${hash}.png`;
 const id=Number((await pool.query(`INSERT INTO media_asset(user_id,sha256,mime_type,storage_path,byte_size) VALUES($1,$2,'image/png',$3,4) RETURNING id`,[user,hash,path])).rows[0].id);
 await mkdir(dirname(join(root,'uploads',path)),{recursive:true});await writeFile(join(root,'uploads',path),'test');return {id,path,hash};
}
async function link(messageId:string,assetId:number,position=0,role='content'){await pool.query('INSERT INTO chat_message_asset(message_id,asset_id,position,role) VALUES($1,$2,$3,$4)',[messageId,assetId,position,role]);}
const target=(message_id:string,asset_id:number)=>({message_id,asset_id});
const remove=(conversation_id:number,images:ReturnType<typeof target>[])=>agent.post(`/api/v1/dashboard/chat/images/delete-batch?user_id=${A}`).send({confirm:'DELETE',conversation_id,images});
const adjacent=(conversation_id:number,message_id:string,asset_id:number,direction='next')=>agent.get('/api/v1/dashboard/chat/images/adjacent').query({user_id:A,conversation_id,message_id,asset_id,direction});
const count=async(table:string)=>Number((await pool.query(`SELECT COUNT(*) AS n FROM ${table}`)).rows[0].n);
for(const platform of ['wechat','qq','douyin'])test(`${platform}相邻图片跨消息页跳过文字，按倒序和附件顺序，不跨会话`,async()=>{
 const c=await conversation(platform),other=await conversation(platform);
 const newest=await message(c,{id:'ffffffff-0000-4000-8000-000000000001'}),older=await message(c,{id:'00000000-0000-4000-8000-000000000002'});
 const a=await asset(),b=await asset(),d=await asset();await link(newest,b.id,1);await link(newest,a.id,0);await link(newest,a.id,0,'thumbnail');await link(older,d.id);
 for(let i=0;i<25;i++)await message(c,{type:'text',text:'文字不参与图片浏览'});
 const outside=await message(other);await link(outside,(await asset()).id);
 let r=await adjacent(c,newest,a.id);expect(r.status).toBe(200);expect(r.body.image).toMatchObject({message_id:newest,asset_id:b.id,ordinal:2,total:3});
 r=await adjacent(c,newest,b.id);expect(r.body.image).toMatchObject({message_id:older,asset_id:d.id,ordinal:3});
 expect((await adjacent(c,older,d.id)).body.image).toBeNull();expect((await adjacent(c,newest,a.id,'previous')).body.image).toBeNull();
 expect((await adjacent(c,older,d.id,'previous')).body.image.asset_id).toBe(b.id);
 expect((await adjacent(other,newest,a.id)).status).toBe(404);
});
test('图片导航限制当前用户，错误方向和锚点返回明确错误',async()=>{
 const c=await conversation('wechat',B),m=await message(c,{user:B}),a=await asset(B);await link(m,a.id);
 expect((await adjacent(c,m,a.id)).status).toBe(404);expect((await adjacent(c,m,a.id,'invalid')).status).toBe(400);
 expect((await adjacent(c,'bad',a.id)).status).toBe(400);
});
test('批量仅移除选中图片，保留文字、未选附件和其他会话；纯图删空移除空记录',async()=>{
 const c=await conversation(),other=await conversation(),plain=await message(c),mixed=await message(c,{text:'保留原文'}),keep=await message(other);
 const a=await asset(),b=await asset(),d=await asset(),e=await asset();await link(plain,a.id);await link(mixed,b.id);await link(mixed,d.id,1);await link(keep,e.id);
 const r=await remove(c,[target(plain,a.id),target(mixed,b.id)]);expect(r.status).toBe(200);expect(r.body).toMatchObject({deleted_images:2,deleted_messages:1,files_pending:false});
 expect((await pool.query('SELECT text FROM chat_message WHERE id=$1',[mixed])).rows[0].text).toBe('保留原文');expect(await count('chat_message_asset')).toBe(2);
 expect(existsSync(join(root,'uploads',a.path))).toBe(false);expect(existsSync(join(root,'uploads',b.path))).toBe(false);expect(existsSync(join(root,'uploads',d.path))).toBe(true);expect(existsSync(join(root,'uploads',e.path))).toBe(true);
});
test('同图其他消息仍引用或其他用户共享存储路径时不删共享文件',async()=>{
 const c=await conversation(),other=await conversation(),m=await message(c),k=await message(other),a=await asset();await link(m,a.id);await link(k,a.id);
 expect((await remove(c,[target(m,a.id)])).status).toBe(200);expect(existsSync(join(root,'uploads',a.path))).toBe(true);expect(await count('media_asset')).toBe(1);
 const shared=await asset(B,a.hash);expect(shared.path).toBe(a.path);
 expect((await remove(other,[target(k,a.id)])).status).toBe(200);expect(existsSync(join(root,'uploads',a.path))).toBe(true);expect(await count('media_asset')).toBe(1);
});
test('混入其他用户、会话、已消失关联时整批拒绝，文件不变',async()=>{
 const c=await conversation(),o=await conversation(),m=await message(c),n=await message(o),a=await asset(),b=await asset();await link(m,a.id);await link(n,b.id);
 expect((await remove(c,[target(m,a.id),target(n,b.id)])).status).toBe(409);
 expect((await remove(c,[target(m,a.id),target(m,b.id)])).status).toBe(409);
 const foreign=await asset(B);await link(m,foreign.id);expect((await remove(c,[target(m,a.id),target(m,foreign.id)])).status).toBe(409);
 expect(await count('chat_message_asset')).toBe(3);expect(existsSync(join(root,'uploads',a.path))).toBe(true);
});
test('批量参数和认证严格校验，重复图片与缺确认不执行删除',async()=>{
 const c=await conversation(),m=await message(c),a=await asset();await link(m,a.id);
 for(const body of [{},{confirm:'DELETE',conversation_id:c,images:[]},{confirm:'DELETE',conversation_id:c,images:[target(m,a.id),target(m,a.id)]},{confirm:'DELETE',conversation_id:c,images:[target('bad',a.id)]}])expect((await agent.post(`/api/v1/dashboard/chat/images/delete-batch?user_id=${A}`).send(body)).status).toBe(400);
 expect((await request(app).post(`/api/v1/dashboard/chat/images/delete-batch?user_id=${A}`).send({})).status).toBe(401);
 expect((await agent.post(`/api/v1/dashboard/chat/images/delete-batch?user_id=${A}`).set('Origin','https://other.invalid').send({})).status).toBe(403);expect(await count('chat_message_asset')).toBe(1);
});
test('数据库中途报错时整批回滚且任何图片文件均不删除',async()=>{
 const c=await conversation(),m=await message(c),a=await asset(),b=await asset();await link(m,a.id);await link(m,b.id,1);
 await pool.query(`CREATE FUNCTION fail_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated failure'; END $$; CREATE TRIGGER fail_delete BEFORE DELETE ON media_asset FOR EACH ROW EXECUTE FUNCTION fail_delete()`);
 expect((await remove(c,[target(m,a.id),target(m,b.id)])).status).toBe(500);expect(await count('chat_message_asset')).toBe(2);expect(await count('chat_message')).toBe(1);
 expect(existsSync(join(root,'uploads',a.path))).toBe(true);expect(existsSync(join(root,'uploads',b.path))).toBe(true);
});
test('文件清理失败保留持久化任务，恢复后重试，不误报数据库回滚',async()=>{
 const c=await conversation(),m=await message(c),a=await asset();await link(m,a.id);const dir=dirname(join(root,'uploads',a.path));await chmod(dir,0o500);
 try {const r=await remove(c,[target(m,a.id)]);expect(r.status).toBe(200);expect(r.body.files_pending).toBe(true);expect(await count('chat_message_asset')).toBe(0);expect(await count('runtime_setting')).toBe(1);expect(existsSync(join(root,'uploads',a.path))).toBe(true);}
 finally{await chmod(dir,0o700);}
 await retryDeviceFileCleanup(pool);expect(await count('runtime_setting')).toBe(0);expect(existsSync(join(root,'uploads',a.path))).toBe(false);
});
test('异常附件路径必须回滚，不删除路径之外的文件',async()=>{
 const c=await conversation(),m=await message(c),a=await asset();await link(m,a.id);await pool.query("UPDATE media_asset SET storage_path='../outside' WHERE id=$1",[a.id]);
 expect((await remove(c,[target(m,a.id)])).status).toBe(409);expect(await count('chat_message_asset')).toBe(1);expect(existsSync(join(root,'uploads',a.path))).toBe(true);
});

test('删除和同内容并发上传串行，上传不能拿到即将删除的旧附件ID',async()=>{
 const c=await conversation(),m=await message(c),bytes=Buffer.from('isolated concurrent image');
 const a=await asset(A,createHash('sha256').update(bytes).digest('hex'));await link(m,a.id);
 const keeper=await pool.connect();await keeper.query('SELECT pg_advisory_lock(9182026)');
 await pool.query(`CREATE FUNCTION pause_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(9182026); RETURN OLD; END $$;
 CREATE TRIGGER pause_delete BEFORE DELETE ON chat_message_asset FOR EACH ROW EXECUTE FUNCTION pause_delete()`);
 const deletion=remove(c,[target(m,a.id)]).then(r=>r);
 let uploaded=false;let upload:ReturnType<typeof storeAsset>|undefined;
 const until=async(check:()=>Promise<boolean>)=>{const deadline=Date.now()+4000;while(!await check()){if(Date.now()>deadline)throw Error('并发测试等待超时');await new Promise(r=>setTimeout(r,10));}};
 try{
  await until(async()=>Number((await pool.query("SELECT count(*) AS n FROM pg_stat_activity WHERE datname=current_database() AND wait_event='advisory'")).rows[0].n)>0);
  upload=storeAsset(pool,A,{sha256:a.hash,mime_type:'image/png',file_base64:bytes.toString('base64')}).then(r=>{uploaded=true;return r;});
  await until(async()=>uploaded||Number((await pool.query("SELECT count(*) AS n FROM pg_stat_activity WHERE datname=current_database() AND wait_event='relation' AND query LIKE 'LOCK TABLE media_asset IN ROW EXCLUSIVE MODE%'")).rows[0].n)>0);
  expect(uploaded).toBe(false);
 }finally{await keeper.query('SELECT pg_advisory_unlock(9182026)');keeper.release();}
 const result=await deletion;expect(result.status).toBe(200);
 const stored=await upload!;expect(stored.id).not.toBe(a.id);expect((await pool.query('SELECT id FROM media_asset WHERE id=$1',[stored.id])).rowCount).toBe(1);expect(existsSync(join(root,'uploads',a.path))).toBe(true);
});
