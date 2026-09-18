import { randomUUID, createHash } from "node:crypto";
import { readFileSync, readdirSync, realpathSync, existsSync } from "node:fs";
import { mkdir, writeFile, readFile } from "node:fs/promises";
import { join } from "node:path";
import pg from "pg";
import request from "supertest";
import { beforeAll, beforeEach, afterAll, expect, it, vi } from "vitest";
import { storeAsset } from "../chat/assetStorage.js";
import { retryDeviceFileCleanup } from "../lib/deleteDeviceData.js";
import { createApp } from "../app.js";
import { authenticatedRequest } from "../lib/dashboardAuthTestHelper.js";

const cluster = process.env.DEVICE_CONTROLS_TEST_CLUSTER;
if (cluster && (!cluster.startsWith("/tmp/shurufa-device-controls.") || readFileSync(join(cluster,"test-instance-only"),"utf8") !== "device-controls-only")) throw Error("独立测试实例验证失败");
const test = cluster ? it : it.skip;
const A=randomUUID(), B=randomUUID();
const migrations = readdirSync(new URL("../../migrations/",import.meta.url)).filter(f=>f.endsWith(".sql")).sort().map(f=>readFileSync(new URL(`../../migrations/${f}`,import.meta.url),"utf8"));
let pool:pg.Pool, app:ReturnType<typeof createApp>, agent:Awaited<ReturnType<typeof authenticatedRequest>>, root:string;
let verified=false;
beforeAll(async()=>{
 if(!cluster)return;
 pool=new pg.Pool({host:join(cluster,"socket"),port:5432,user:process.env.USER||"ko",database:"device_controls_test",max:6});
 const identity=(await pool.query("SELECT current_setting('data_directory') AS dir, current_database() AS db")).rows[0];
 expect(realpathSync(identity.dir)).toBe(realpathSync(join(cluster,"data")));expect(identity.db).toBe("device_controls_test");verified=true;
});
beforeEach(async()=>{
 if(!cluster)return;if(!verified)throw Error("禁止操作非隔离实例");
 const schema=`test_${randomUUID().replaceAll("-","")}`;
 await pool.end();
 pool=new pg.Pool({host:join(cluster,"socket"),port:5432,user:process.env.USER||"ko",database:"device_controls_test",max:6,options:`-c search_path=${schema}`});
 await pool.query(`CREATE SCHEMA ${schema}`);for(const sql of migrations)await pool.query(sql);
 root=join(cluster,schema);await mkdir(root,{recursive:true});vi.spyOn(process,"cwd").mockReturnValue(root);
 app=createApp(pool);agent=await authenticatedRequest(app);
 await pool.query("INSERT INTO device(id,name) VALUES($1,$2),($3,$4)",[A,"手机A",B,"手机B"]);
});
afterAll(async()=>{vi.restoreAllMocks();await pool?.end();});
const saving=(value:boolean,user=A)=>agent.post(`/api/v1/dashboard/users/${user}/saving?user_id=${B}`).send({save_uploads:value});
const remove=(user:string=A,body:unknown={confirm:"DELETE"})=>agent.post(`/api/v1/dashboard/users/${user}/delete?user_id=${B}`).send(body as object);
const mobile=(path:string,body:unknown,user=A)=>request(app).post(`/api/v1/mobile${path}`).set("X-Device-Id",user).send(body as object);
const event=(user=A)=>({device_id:user,events:[{id:randomUUID(),device_id:user,event_type:"commit",text:"测试输入",occurred_at:new Date().toISOString()}]});
async function count(table:string,user=A){return Number((await pool.query(`SELECT COUNT(*) AS n FROM ${table} WHERE user_id=$1`,[user])).rows[0].n);}

test("开关默认开启，关闭仅丢弃目标手机，上报成功ACK且恢复后保存",async()=>{
 const list=await agent.get("/api/v1/dashboard/users");expect(list.body.users.find((u:any)=>u.id===A).save_uploads).toBe(true);
 expect((await saving(false)).status).toBe(200);
 const dropped=await mobile("/events/batch",event());expect(dropped.status).toBe(200);expect(dropped.body).toMatchObject({ok:true,inserted:0,discarded:true});expect(await count("input_event")).toBe(0);
 expect((await mobile("/events/batch",event(B),B)).status).toBe(200);expect(await count("input_event",B)).toBe(1);
 expect((await saving(true)).status).toBe(200);expect((await mobile("/events/batch",event())).status).toBe(200);expect(await count("input_event")).toBe(1);
});
test("关闭期间不同上报入口均成功但正文、统计、附件不入库不落盘",async()=>{
 expect((await saving(false)).status).toBe(200);const reportId=randomUUID();
 const calls:[string,unknown][]=[
 ["/session",{id:randomUUID(),device_id:A,started_at:new Date().toISOString()}],
 ["/location",{device_id:A,latitude:31,longitude:121}],
 ["/reports",{id:reportId,kind:"phrase_upsert",payload:{content:"不保存"}}],
 ["/completions/feedback",{completion:"不保存",accepted:true}],
 ["/phrases",{content:"不保存"}],["/phrases/use",{content:"不保存"}],
 ["/stickers/1/use",{}],["/expressions/example/use",{}],
 ["/dictionary/report",{sequence:1,entries:[],migration_status:"complete",imported:0}],
 ["/dictionary/ack",{revision:"a".repeat(64)}],
 ["/chat/assets",{sha256:"a".repeat(64),mime_type:"image/png",file_base64:Buffer.from("不要存").toString("base64")}],
 ["/chat/messages/batch",{device_id:A,conversation:{},messages:[]}],
 ];
 for(const [path,body] of calls){const r=await mobile(path,body);expect(r.status,path).toBeLessThan(300);expect(r.body.ok,path).toBe(true);if(path==="/reports")expect(r.body.id).toBe(reportId);}
 for(const table of ["input_event","location_track","mobile_report_receipt","user_phrase","completion_feedback_usage","media_asset","chat_conversation","chat_message","expression_asset_usage"])expect(await count(table),table).toBe(0);
 expect((await pool.query("SELECT * FROM input_session")).rowCount).toBe(0);expect(existsSync(join(root,"uploads/chat"))).toBe(false);
 expect((await request(app).get("/api/v1/mobile/phrases").set("X-Device-Id",A)).status).toBe(200);
 expect((await mobile("/not-a-route",{})).status).toBe(404);
});
test("删除当前数据与设备但保留开关，不影响其他手机并允许重新注册",async()=>{
 await saving(false);await pool.query("INSERT INTO input_event(id,user_id,device_id,occurred_at,event_type) VALUES($1,$2,$2,NOW(),$3),($4,$5,$5,NOW(),$3)",[randomUUID(),A,"commit",randomUUID(),B]);
 const r=await remove();expect(r.status).toBe(200);expect(r.body.deleted_device_id).toBe(A);expect(await count("input_event")).toBe(0);expect(await count("input_event",B)).toBe(1);
 expect((await pool.query("SELECT id FROM device WHERE id=$1",[A])).rowCount).toBe(0);
 expect((await mobile("/device",{id:A,name:"不得保存的设备详情",model:"秘密型号"})).status).toBe(200);
 const row=(await pool.query("SELECT * FROM device WHERE id=$1",[A])).rows[0];expect(row).toBeDefined();expect(row.model).toBeNull();
 expect((await mobile("/events/batch",event())).body.discarded).toBe(true);
 await saving(true);await mobile("/device",{id:A,name:"允许保存"});await mobile("/events/batch",event());expect(await count("input_event")).toBe(1);
});
test("删除必须登录、确认和精确ID；不存在不删除其他数据",async()=>{
 expect((await request(app).post(`/api/v1/dashboard/users/${A}/delete?user_id=${A}`).send({confirm:"DELETE"})).status).toBe(401);
 expect((await remove(A,{})).status).toBe(400);expect((await remove("invalid")).status).toBe(400);expect((await remove(randomUUID())).status).toBe(404);
 expect((await pool.query("SELECT * FROM device")).rowCount).toBe(2);
});
test("聊天同内容共享文件必须保留，仅删除专属上传文件",async()=>{
 const shared="b".repeat(64), own="c".repeat(64);
 for(const [user,hash] of [[A,shared],[B,shared],[A,own]]){
 const path=`chat/${hash.slice(0,2)}/${hash}.png`;await mkdir(join(root,"uploads",`chat/${hash.slice(0,2)}`),{recursive:true});await writeFile(join(root,"uploads",path),"fixture");
 await pool.query("INSERT INTO media_asset(user_id,sha256,mime_type,storage_path,byte_size) VALUES($1,$2,$3,$4,7)",[user,hash,"image/png",path]);
 }
 expect((await remove()).status).toBe(200);expect(await count("media_asset")).toBe(0);expect(await count("media_asset",B)).toBe(1);
 expect(existsSync(join(root,"uploads",`chat/bb/${shared}.png`))).toBe(true);expect(existsSync(join(root,"uploads",`chat/cc/${own}.png`))).toBe(false);
});
test("删除词库设备只移除其记录，共享组策略及另一手机保留",async()=>{
 await pool.query("INSERT INTO dictionary_device(device_id,group_id,token_hash) VALUES($1,$1,$3),($2,$1,$3)",[A,B,"hash"]);
 await pool.query("INSERT INTO dictionary_entry(device_id,entry_key,sequence,payload) VALUES($1,$3,1,$4),($2,$3,1,$4)",[A,B,"key",{}]);
 await pool.query("INSERT INTO dictionary_policy(group_id,text,status) VALUES($1,$2,$3)",[A,"测试","enabled"]);
 expect((await remove()).status).toBe(200);expect((await pool.query("SELECT device_id FROM dictionary_entry")).rows).toEqual([{device_id:B}]);expect((await pool.query("SELECT * FROM dictionary_policy")).rowCount).toBe(1);
 expect((await remove(B)).status).toBe(200);expect((await pool.query("SELECT * FROM dictionary_policy")).rowCount).toBe(0);
});
test("事务中失败全部回滚，不误报成功",async()=>{
 await pool.query("INSERT INTO user_phrase(user_id,content) VALUES($1,$2)",[A,"保留"]);
 await pool.query(`CREATE FUNCTION refuse_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test rollback'; END $$; CREATE TRIGGER refuse BEFORE DELETE ON device FOR EACH ROW EXECUTE FUNCTION refuse_delete()`);
 expect((await remove()).status).toBe(500);expect(await count("user_phrase")).toBe(1);expect((await pool.query("SELECT id FROM device WHERE id=$1",[A])).rowCount).toBe(1);
});
test("媒体记录中的越界路径不能操作 uploads 外文件",async()=>{
 const outside=join(root,"private.txt");await writeFile(outside,"do not remove");
 await pool.query("INSERT INTO media_asset(user_id,sha256,mime_type,storage_path,byte_size) VALUES($1,$2,$3,$4,1)",[A,"d".repeat(64),"image/png","../private.txt"]);
 expect((await remove()).status).toBe(409);expect(await readFile(outside,"utf8")).toBe("do not remove");expect(await count("media_asset")).toBe(1);
});

test("清理与同内容新上传互斥，清理之后的新文件不被误删",async()=>{
 const bytes=Buffer.from("concurrent file"), hash=createHash("sha256").update(bytes).digest("hex");
 const input={sha256:hash,mime_type:"image/png",file_base64:bytes.toString("base64")};
 await storeAsset(pool,A,input);
 const cleanup=await pool.connect(); await cleanup.query("BEGIN");await cleanup.query("LOCK TABLE media_asset IN SHARE ROW EXCLUSIVE MODE");
 let finished=false;const uploading=storeAsset(pool,B,input).then(v=>{finished=true;return v;});
 // 上传不能先碰原文件，再等待数据库锁；清理完成后才可以创建新文件。
 const path=join(root,"uploads",`chat/${hash.slice(0,2)}/${hash}.png`);
 const {unlink}=await import("node:fs/promises");await unlink(path);
 await new Promise(r=>setTimeout(r,100));
 const fileWasRecreated=existsSync(path); const finishedEarly=finished;
 await cleanup.query("COMMIT");cleanup.release();await uploading;
 expect(fileWasRecreated).toBe(false);expect(finishedEarly).toBe(false);expect(existsSync(path)).toBe(true);
});
test("持久化清理任务重试不删除新的共享引用",async()=>{
 const path="chat/aa/"+"a".repeat(64)+".png";
 await mkdir(join(root,"uploads/chat/aa"),{recursive:true});await writeFile(join(root,"uploads",path),"fixture");
 await pool.query("INSERT INTO runtime_setting(key,value) VALUES($1,$2)",["device_delete_files:"+randomUUID(),JSON.stringify([path])]);
 await pool.query("INSERT INTO media_asset(user_id,sha256,mime_type,storage_path,byte_size) VALUES($1,$2,$3,$4,7)",[B,"a".repeat(64),"image/png",path]);
 await retryDeviceFileCleanup(pool);expect(existsSync(join(root,"uploads",path))).toBe(true);
 expect((await pool.query("SELECT key FROM runtime_setting WHERE key LIKE 'device_delete_files:%'")).rowCount).toBe(0);
});
test("删除覆盖全部个人业务表、附件关联和分析游标，系统素材保留",async()=>{
 const hash="e".repeat(64);
 await pool.query("INSERT INTO expression_asset(id,type,format,version,file_name,sha256,width,height) VALUES('shared','prebuilt','gif','1','shared.gif',$1,1,1)",[hash]);
 for(const user of [A,B]){
 const insert=async(table:string,fields:Record<string,unknown>)=>{
 const row={user_id:user,...fields};const keys=Object.keys(row);return (await pool.query(`INSERT INTO ${table}(${keys.join(',')}) VALUES(${keys.map((_,i)=>`$${i+1}`).join(',')}) RETURNING *`,Object.values(row))).rows[0];};
 const c=await insert("chat_conversation",{platform:"wechat",account_key:"a",external_key:"b",conversation_type:"direct",identity_confidence:1});
 const m=await insert("chat_message",{id:randomUUID(),device_id:user,conversation_id:c.id,platform:"wechat",fingerprint:hash,content_fingerprint:hash,sender_key:"s",direction:"incoming",message_type:"image",captured_at:new Date()});
 const asset=await insert("media_asset",{sha256:hash,mime_type:"image/png",storage_path:`chat/ee/${hash}.png`,byte_size:7});
 await pool.query("INSERT INTO chat_message_asset(message_id,asset_id) VALUES($1,$2)",[m.id,asset.id]);
 await insert("relationship_profile",{conversation_id:c.id});
 await insert("relationship_ai_profile",{conversation_id:c.id,version:1,profile:{},source_message_count:1,model:"fixture"});
 await insert("relationship_ai_call",{conversation_id:c.id,purpose:"reply",model:"fixture",status:"success",duration_ms:1});
 await insert("relationship_ai_reply_session",{session_id:randomUUID(),conversation_id:c.id,context_hash:hash});
 await insert("input_event",{id:randomUUID(),device_id:user,event_type:"commit",occurred_at:new Date()});
 await insert("location_track",{device_id:user,latitude:1,longitude:1,occurred_at:new Date()});
 await insert("phrase_stat",{phrase:"词"});await insert("completion_candidate",{prefix:"词",completion:"语"});
 await insert("user_phrase",{content:"常用语"});await insert("sticker",{keywords:"测试",file_name:user+".gif",format:"gif"});
 await insert("sticker_keyword",{keyword:"测试"});
 await insert("synthesis_asset",{id:randomUUID(),name:"底图",file_name:user+".gif",sha256:hash,width:1,height:1,text_safe_area:{},layout:{},source_statement:"测试",no_text_confirmed:true,rights_confirmed:true});
 await insert("mobile_report_receipt",{report_id:randomUUID(),payload_hash:hash});
 await insert("personal_candidate_usage",{code:"ci",text:"词",count:1,weight:1,last_used:1});
 await insert("completion_feedback_usage",{prefix:"词",completion:"语"});
 await insert("sticker_file_usage",{file_name:user+".gif"});await insert("expression_asset_usage",{asset_id:"shared"});
 await insert("keyword_gif_removal",{sha256:hash,asset_id:"shared"});
 await pool.query("INSERT INTO analysis_state(key,value) VALUES($1,1)",[`last_analyzed_epoch_ms:${user}`]);
 }
 expect((await remove()).status).toBe(200);
 const tables=(await pool.query("SELECT table_name FROM information_schema.columns WHERE table_schema=current_schema() AND column_name='user_id'")).rows;
 for(const {table_name} of tables){expect(await count(table_name),table_name).toBe(0);expect(await count(table_name,B),table_name).toBe(1);}
 expect((await pool.query("SELECT * FROM expression_asset")).rowCount).toBe(1);
 expect((await pool.query("SELECT * FROM chat_message_asset")).rowCount).toBe(1);
 expect((await pool.query("SELECT key FROM analysis_state")).rows).toEqual([{key:`last_analyzed_epoch_ms:${B}`}]);
});

test("磁盘删除失败明确返回待清理并保留任务，恢复后可重试完成",async()=>{
 const hash="f".repeat(64), dir=join(root,"uploads/chat/ff"), path=`chat/ff/${hash}.png`;
 await mkdir(dir,{recursive:true});await writeFile(join(root,"uploads",path),"fixture");
 await pool.query("INSERT INTO media_asset(user_id,sha256,mime_type,storage_path,byte_size) VALUES($1,$2,$3,$4,7)",[A,hash,"image/png",path]);
 const {chmod}=await import("node:fs/promises");await chmod(dir,0o555);
 try {const r=await remove();expect(r.status).toBe(200);expect(r.body.files_pending).toBe(true);expect(await count("media_asset")).toBe(0);expect(existsSync(join(root,"uploads",path))).toBe(true);}
 finally {await chmod(dir,0o755);}
 expect((await pool.query("SELECT key FROM runtime_setting WHERE key LIKE 'device_delete_files:%'")).rowCount).toBe(1);
 await retryDeviceFileCleanup(pool);expect(existsSync(join(root,"uploads",path))).toBe(false);expect((await pool.query("SELECT key FROM runtime_setting WHERE key LIKE 'device_delete_files:%'")).rowCount).toBe(0);
});
test("跨手机会话引用拒绝整次删除，不能通过级联影响其他用户",async()=>{
 const c=(await pool.query("INSERT INTO chat_conversation(user_id,platform,account_key,external_key,conversation_type,identity_confidence) VALUES($1,'wechat','a','b','direct',1) RETURNING id",[A])).rows[0];
 await pool.query("INSERT INTO chat_message(id,user_id,device_id,conversation_id,platform,fingerprint,content_fingerprint,sender_key,direction,message_type,captured_at) VALUES($1,$2,$2,$3,'wechat',$4,$4,'peer','incoming','text',NOW())",[randomUUID(),B,c.id,"a".repeat(64)]);
 expect((await remove()).status).toBe(409);expect(await count("chat_message",B)).toBe(1);expect(await count("chat_conversation",A)).toBe(1);
});

test("提交后的清理连接故障返回待重试，不把已成功删除报告为失败",async()=>{
 const hash="9".repeat(64);await pool.query("INSERT INTO media_asset(user_id,sha256,mime_type,storage_path,byte_size) VALUES($1,$2,$3,$4,1)",[A,hash,"image/png",`chat/99/${hash}.png`]);
 const connect=pool.connect.bind(pool);
 const mocked=vi.spyOn(pool,"connect").mockImplementationOnce(connect as any).mockRejectedValueOnce(Error("fixture unavailable"));
 try {const r=await remove();expect(r.status).toBe(200);expect(r.body.files_pending).toBe(true);}
 finally {mocked.mockRestore();}
 expect((await pool.query("SELECT id FROM device WHERE id=$1",[A])).rowCount).toBe(0);
 expect((await pool.query("SELECT key FROM runtime_setting WHERE key LIKE 'device_delete_files:%'")).rowCount).toBe(1);
});

test("关闭上报保存不破坏词库登记和已有快照查询，但新的词库上报不保存",async()=>{
 const token="a".repeat(64);
 const call=(path:string,body:object)=>request(app).post('/api/v1/mobile/dictionary'+path).set('X-Device-Id',A).set('X-Dictionary-Token',token).send(body);
 expect((await call('/register',{})).status).toBe(200);
 const entry={kind:'word',text:'测试',code:'',pinyin:'ce shi',source:'selection',count:0,weight:0,last_used:0};
 expect((await call('/report',{sequence:1,entries:[entry],migration_status:'complete',imported:1})).status).toBe(200);
 await saving(false);
 const result=await call('/report',{sequence:2,entries:[{...entry,text:'不要',pinyin:'bu yao'}],migration_status:'complete',imported:1});
 expect(result.status).toBe(200);expect(result.body.discarded).toBe(true);
 expect((await call('/register',{})).status).toBe(200);
 const snapshot=await request(app).get('/api/v1/mobile/dictionary').set('X-Device-Id',A).set('X-Dictionary-Token',token);
 expect(snapshot.status).toBe(200);expect(snapshot.body.entries.map((e:any)=>e.text)).toEqual(['测试']);
 expect((await call('/ack',{revision:snapshot.body.revision})).status).toBe(200);
});

test("搜索路径前置空schema也必须读取实际业务schema的关闭设置",async()=>{
 await saving(false);
 const own=(await pool.query('SELECT current_schema() AS name')).rows[0].name;
 const empty='empty_'+randomUUID().replaceAll('-','');await pool.query(`CREATE SCHEMA ${empty}`);
 const mixed=new pg.Pool({host:join(cluster!, 'socket'),port:5432,user:process.env.USER||'ko',database:'device_controls_test',options:`-c search_path=${empty},${own}`});
 try {const r=await request(createApp(mixed)).post('/api/v1/mobile/events/batch').set('X-Device-Id',A).send(event());expect(r.status).toBe(200);expect(r.body.discarded).toBe(true);expect(await count('input_event')).toBe(0);}
 finally {await mixed.end();}
});

test("保存开关配置异常时不能默认为允许保存",async()=>{
 await pool.query("INSERT INTO runtime_setting(key,value) VALUES($1,$2)",['device_save_uploads:'+A,'invalid']);
 expect((await mobile('/events/batch',event())).status).toBe(500);expect(await count('input_event')).toBe(0);
});

test("手机缓存注册状态时，删除后下一条上报仍可恢复目录且沿用开关",async()=>{
 for(const enabled of [false,true]) {
  await saving(enabled);expect((await remove()).status).toBe(200);
  // 客户端可能记着已注册，不会先请求/device。
  const r=await mobile('/events/batch',event());expect(r.status).toBe(200);
  expect((await pool.query('SELECT id FROM device WHERE id=$1',[A])).rowCount).toBe(1);
  expect(await count('input_event')).toBe(enabled?1:0);
  const directory=await agent.get('/api/v1/dashboard/users?id='+A);expect(directory.body.users[0].save_uploads).toBe(enabled);
 }
});
