import { randomUUID } from 'node:crypto';
import { readFileSync, readdirSync, realpathSync } from 'node:fs';
import pg from 'pg';
import request from 'supertest';
import { beforeAll, afterAll, expect, it } from 'vitest';
import { createApp } from '../app.js';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';
const root=process.env.APP_USAGE_TEST_CLUSTER;
if(root && (!root.startsWith('/tmp/shurufa-app-usage.') || readFileSync(`${root}/test-instance-only`,'utf8')!=='app-usage-only'))throw Error('独立实例校验失败');
const test=root?it:it.skip;
let pool:pg.Pool, app:ReturnType<typeof createApp>, agent:Awaited<ReturnType<typeof authenticatedRequest>>;
const device=randomUUID();
const pause=Date.parse('2026-10-15T02:08:00Z'), off=pause+120000;
const snapshot=(id=device)=>({version:randomUUID(),device_id:id,app_activity:{at_ms:pause,source:'usage_pause'},screen_off:{at_ms:off,source:'usage_screen_off'}});
const record=()=>({id:randomUUID(),kind:'usage',package_name:'com.example.app',app_name:'测试',start_ms:pause-60000,end_ms:pause,end_reason:'screen_off'});
const post=(body:Record<string,unknown>,id=device)=>request(app).post('/api/v1/mobile/app-usage/batch').set('X-Device-Id',id).send(body);
const row=async(id=device)=>(await pool.query('SELECT * FROM device WHERE id=$1',[id])).rows[0];
beforeAll(async()=>{if(!root)return;
 pool=new pg.Pool({host:`${root}/socket`,user:'ko',database:'usage_evidence_test'});
 expect(realpathSync((await pool.query("SELECT current_setting('data_directory') AS dir")).rows[0].dir)).toBe(realpathSync(`${root}/data`));
 for(const f of readdirSync(new URL('../../migrations/',import.meta.url)).filter(f=>f.endsWith('.sql')).sort())await pool.query(readFileSync(new URL(`../../migrations/${f}`,import.meta.url),'utf8'));
 app=createApp(pool);agent=await authenticatedRequest(app,pool);
});
afterAll(async()=>{await pool?.end();});
test('原始熄屏独立于pausedAt；精确ACK；离线补传和重复不刷新证据接收时间',async()=>{
 const evidence=snapshot(), r=record();
 const response=await post({records:[r],usage_evidence:evidence});
 expect(response.status).toBe(200);
 expect(response.body).toMatchObject({received:1,usage_evidence_ack:{version:evidence.version,disposition:'saved'}});
 const saved=await row();expect(+saved.last_app_activity_at).toBe(pause);expect(+saved.last_screen_off_at).toBe(off);expect(+saved.latest_usage_at).toBe(off);
 await post({records:[r],usage_evidence:evidence});
 const older=snapshot();older.screen_off.at_ms=off-1000;older.app_activity.at_ms=pause-1000;
 await post({records:[],usage_evidence:older});
 const latest=await row();expect(+latest.latest_usage_at).toBe(off);expect(+latest.last_screen_off_received_at).toBe(+saved.last_screen_off_received_at);
 expect((await pool.query('SELECT end_ms FROM app_usage_segment WHERE user_id=$1',[device])).rows).toEqual([{end_ms:String(pause)}]);
});
test('仅新快照可保存；两者都空拒绝；旧客户端不伪造熄屏',async()=>{
 const id=randomUUID(), e=snapshot(id);
 expect((await post({usage_evidence:e},id)).body.usage_evidence_ack.version).toBe(e.version);
 expect((await post({records:[]},id)).status).toBe(400);
 const old=randomUUID();expect((await post({records:[record()]},old)).status).toBe(200);
 expect((await row(old)).last_screen_off_at).toBeNull();expect((await row(old)).latest_usage_at).toBeNull();
});
test('坏证据导致整批拒绝且不得跨设备',async()=>{
 for(const patch of [{device_id:randomUUID()},{screen_off:{at_ms:Date.now()+3600000,source:'usage_screen_off'}},{screen_off:{at_ms:off,source:'heartbeat'}}]){
 const id=randomUUID();expect((await post({records:[record()],usage_evidence:{...snapshot(id),...patch}},id)).status).toBe(400);
 expect(await row(id)).toBeUndefined();
 }
});
test('关闭保存明确discarded ACK且不存证据；交互仍为原始补充',async()=>{
 const id=randomUUID(),e=snapshot(id);
 await pool.query("INSERT INTO runtime_setting(key,value) VALUES($1,'false')",['device_save_uploads:'+id]);
 const response=await post({records:[],usage_evidence:e},id);
 expect(response.body).toMatchObject({discarded:true,usage_evidence_ack:{version:e.version,disposition:'discarded'}});
 expect((await row(id)).latest_usage_at).toBeNull();
 const other=randomUUID();await post({usage_evidence:snapshot(other),last_interaction_at:new Date(pause-1000).toISOString(),last_interaction_source:'ime_input'},other);
 expect(+(await row(other)).last_interaction_at).toBe(pause-1000);
});
test('目录按使用事件排序而非交互/接收，未知不回填，提供分项来源',async()=>{
 const id=randomUUID();await post({usage_evidence:snapshot(id)},id);
 const response=await agent.get('/api/v1/dashboard/users?id='+id);
 expect(response.status).toBe(200);expect(response.body.users[0]).toMatchObject({latest_usage_at:new Date(off).toISOString(),latest_usage_source:'usage_screen_off',last_app_activity_source:'usage_pause'});
});
test('支持能力标记，不提供不存在的完整同步状态',async()=>{
 const r=await request(app).get('/api/v1/mobile/config').set('X-Device-Id',device);
 expect(r.body.capabilities).toEqual({usage_evidence:1,location_altitude_evidence:1});expect(r.body.pending).toBeUndefined();
});
test('显式null records不是省略；非法交互不与合法快照一起吞下',async()=>{
 const id=randomUUID();
 expect((await post({records:null,usage_evidence:snapshot(id)},id)).status).toBe(400);
 expect((await post({usage_evidence:snapshot(id),last_interaction_at:'bad',last_interaction_source:'touch'},id)).status).toBe(400);
 expect(await row(id)).toBeUndefined();
});
test('相同事件时间固定锁屏优先，不随请求顺序改变',async()=>{
 for(const reverse of [false,true]){
 const id=randomUUID();const events=[{screen_off:{at_ms:off,source:'usage_screen_off'}},{lock:{at_ms:off,source:'usage_keyguard_shown'}}];
 for(const event of reverse?events.reverse():events)await post({usage_evidence:{version:randomUUID(),device_id:id,...event}},id);
 expect((await row(id)).latest_usage_source).toBe('usage_keyguard_shown');
 }
});
test('旧保留期墓碑继续阻止使用段重放，不伪造该段的屏幕事件',async()=>{
 const id=randomUUID(),r=record();
 await pool.query("INSERT INTO retention_deleted_record(user_id,dataset,record_key,source_version) VALUES($1,'app-usage',$2,'')",[id,r.id]);
 expect((await post({records:[r]},id)).status).toBe(200);
 expect((await pool.query('SELECT id FROM app_usage_segment WHERE user_id=$1',[id])).rows).toEqual([]);
 expect((await row(id)).latest_usage_at).toBeNull();
});
test('证据写入失败时同批使用段回滚，失败请求不返回版本ACK',async()=>{
 const id=randomUUID(),r=record();
 await pool.query(`CREATE FUNCTION reject_evidence_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id='${id}'::uuid THEN RAISE EXCEPTION 'test evidence failure'; END IF; RETURN NEW; END $$`);
 await pool.query('CREATE TRIGGER reject_evidence_test BEFORE UPDATE OF last_app_activity_at ON device FOR EACH ROW EXECUTE FUNCTION reject_evidence_test()');
 try{
 const response=await post({records:[r],usage_evidence:snapshot(id)},id);
 expect(response.status).toBe(500);expect(response.body.usage_evidence_ack).toBeUndefined();
 expect((await pool.query('SELECT id FROM app_usage_segment WHERE user_id=$1',[id])).rows).toEqual([]);
 expect((await row(id)).latest_usage_at).toBeNull();
 }finally{await pool.query('DROP TRIGGER reject_evidence_test ON device; DROP FUNCTION reject_evidence_test()');}
});
test('并发乱序快照按每类保持最大原始时间，各自版本ACK不串台',async()=>{
 const id=randomUUID(),older=snapshot(id),newer=snapshot(id);newer.app_activity.at_ms+=30000;newer.screen_off.at_ms+=30000;
 const responses=await Promise.all([post({usage_evidence:newer},id),post({usage_evidence:older},id)]);
 expect(responses.map(r=>r.body.usage_evidence_ack.version)).toEqual([newer.version,older.version]);
 expect(+(await row(id)).last_app_activity_at).toBe(newer.app_activity.at_ms);expect(+(await row(id)).latest_usage_at).toBe(newer.screen_off.at_ms);
});
