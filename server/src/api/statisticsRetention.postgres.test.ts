import { generateCompletions } from '../analysis/analyze.js';
import { createMobileAppUsageRouter } from './appUsage.js';
import { createMobileCallLogsRouter } from './callLogs.js';
import { createMobileNavigationRouter } from './navigationRecords.js';
import { createMobileRouter } from './mobile.js';
import sharp from 'sharp';
import { randomUUID, createHash } from 'node:crypto';
import { readFileSync, readdirSync, realpathSync } from 'node:fs';
import { join } from 'node:path';
import pg from 'pg';
import express from 'express';
import request from 'supertest';
import { beforeAll, beforeEach, afterAll, expect, it, vi } from 'vitest';
import { createDashboardRouter } from './dashboard.js';
const cluster = process.env.CHAT_RETENTION_TEST_CLUSTER;
if (cluster && (!cluster.startsWith('/tmp/shurufa-chat-retention.') || readFileSync(join(cluster,'test-instance-only'),'utf8') !== 'retention-test-only')) throw Error('非独立测试实例');
const test = cluster ? it : it.skip;
const A=randomUUID(), B=randomUUID(), OLD='2020-01-01T00:00:00Z';
let pool:pg.Pool, app:ReturnType<typeof express>;
beforeAll(async()=>{
 if(!cluster)return;
 const config={host:join(cluster,'socket'),port:5432,user:'ko'};
 const bootstrap=new pg.Pool({...config,database:'postgres'});
 try { const identity=(await bootstrap.query("SELECT current_setting('data_directory') AS dir")).rows[0];expect(realpathSync(identity.dir)).toBe(realpathSync(join(cluster,'data')));await bootstrap.query('CREATE DATABASE statistics_retention_test'); }
 finally { await bootstrap.end(); }
 pool=new pg.Pool({...config,database:'statistics_retention_test'});
 for(const file of readdirSync(new URL('../../migrations/',import.meta.url)).filter(f=>f.endsWith('.sql')).sort())await pool.query(readFileSync(new URL(`../../migrations/${file}`,import.meta.url),'utf8'));
});
beforeEach(async()=>{
 if(!cluster)return;
 await pool.query('TRUNCATE retention_deleted_record,input_event,location_track,app_usage_segment,navigation_record,call_recording,phone_call_log,completion_candidate,phrase_stat CASCADE');
 await pool.query('INSERT INTO device(id) VALUES($1),($2) ON CONFLICT DO NOTHING',[A,B]);
 app=express();app.use(express.json());app.use((req,res,next)=>{res.locals.userId=req.query.user_id||A;next();});app.use(createDashboardRouter(pool));app.use('/upload-usage',createMobileAppUsageRouter(pool));app.use('/upload-calls',createMobileCallLogsRouter(pool));app.use('/upload-navigation',createMobileNavigationRouter(pool));app.use('/upload-input',createMobileRouter(pool));
});
afterAll(async()=>{await pool?.end();});
const preview=(kind:string,days=7,filters:Record<string,unknown>={})=>request(app).post(`/retention/${kind}/preview`).send({days,filters});
const batch=(kind:string,token:string,offset=0,user=A)=>request(app).post(`/retention/${kind}/batch`).query({user_id:user}).send({confirm:'DELETE',token,offset});
const tables:Record<string,[string,string,string]>={locations:['location_track','user_id','id'],'app-usage':['app_usage_segment','user_id','id'],navigation:['navigation_record','user_id','id'],'call-logs':['phone_call_log','device_id','source_id'],'call-recordings':['call_recording','device_id','record_id']};
async function seed(kind:string,time=OLD,user=A) {
 const id=randomUUID(),ms=Date.parse(time);
 switch(kind){
 case 'locations': return String((await pool.query('INSERT INTO location_track(user_id,device_id,latitude,longitude,occurred_at,first_seen_at,last_seen_at) VALUES($1,$1,23,113,$2,$2,$2) RETURNING id',[user,time])).rows[0].id);
 case 'app-usage': await pool.query("INSERT INTO app_usage_segment(user_id,id,kind,package_name,start_ms,end_ms,end_reason) VALUES($1,$2,'usage','com.test',$3,$4,'switch')",[user,id,ms-5000,ms]);break;
 case 'navigation': await pool.query("INSERT INTO navigation_record(user_id,id,platform,origin,destination,started_at,overview_at,sha256,payload_sha256,mime_type,screenshot) VALUES($1,$2,'amap','起点','终点',$3,$3,'hash','payload','image/png',$4)",[user,id,time,Buffer.from('synthetic')]);break;
 case 'call-logs': await pool.query('INSERT INTO phone_call_log(device_id,source_id,type,date,duration_seconds) VALUES($1,$2,1,$3,0)',[user,id,ms]);break;
 case 'call-recordings':await pool.query("INSERT INTO call_recording(device_id,record_id,metadata,metadata_sha256,sha256,byte_size,recorded_at,platform,recording_status,audio_ciphertext) VALUES($1,$2,'{}','metadata','hash',9,$3,'phone','ended',$4)",[user,id,time,Buffer.from('synthetic')]);break;
 default:throw Error(kind);
 }return id;
}
async function ids(kind:string,user=A){const [table,owner,key]=tables[kind];return (await pool.query(`SELECT ${key}::text AS id FROM ${table} WHERE ${owner}=$1 ${kind==='call-recordings'?'AND deleted_at IS NULL':''} ORDER BY ${key}`,[user])).rows.map(r=>r.id).sort();}

test.each(Object.keys(tables))('%s清理隔离手机、精确截止边界、新到达旧记录；附件与录音墓碑',async kind=>{
 const now=Date.now(),clock=vi.spyOn(Date,'now').mockReturnValue(now);
 try {
 const old=await seed(kind),boundary=await seed(kind,new Date(now-86400000).toISOString()),foreign=await seed(kind,OLD,B);
 const p=await preview(kind,1);expect(p.status).toBe(200);expect(p.body.total_records).toBe(1);expect(p.body.total_files).toBe(['navigation','call-recordings'].includes(kind)?1:0);
 const late=await seed(kind);expect((await batch(kind,p.body.token,0,B)).status).toBe(410);
 const r=await batch(kind,p.body.token);expect(r.status).toBe(200);expect(r.body).toMatchObject({done:true,deleted_records:1,skipped_records:0});
 expect(await ids(kind)).toEqual([boundary,late].sort());expect(await ids(kind,B)).toEqual([foreign]);
 if(kind==='call-recordings'){const row=(await pool.query('SELECT metadata,audio_ciphertext,deleted_at FROM call_recording WHERE record_id=$1',[old])).rows[0];expect(row.metadata).toEqual({});expect(row.audio_ciphertext).toBeNull();expect(row.deleted_at).toBeTruthy();}
 }finally{clock.mockRestore();}
});

test('应用使用段跨截止时间完整保留，按来源App筛选；预览后修改跳过，批次幂等',async()=>{
 const now=Date.now(),clock=vi.spyOn(Date,'now').mockReturnValue(now);
 try{
 const keep=await seed('app-usage',new Date(now-86400000+1).toISOString());const other=await seed('app-usage');await pool.query("UPDATE app_usage_segment SET package_name='other' WHERE id=$1",[other]);
 for(let i=0;i<205;i++)await seed('app-usage');
 const p=await preview('app-usage',1,{package_name:'com.test'});expect(p.status).toBe(200);expect(p.body.total_records).toBe(205);
 const r=await batch('app-usage',p.body.token);expect(r.status).toBe(200);expect(r.body.processed).toBe(200);expect((await batch('app-usage',p.body.token)).body).toEqual(r.body);
 const changed=(await ids('app-usage')).find(id=>id!==keep&&id!==other)!;await pool.query("UPDATE app_usage_segment SET app_name='已变化' WHERE id=$1",[changed]);
 const done=await batch('app-usage',p.body.token,200);expect(done.body).toMatchObject({done:true,deleted_records:204,skipped_records:1});expect(await ids('app-usage')).toEqual([keep,other,changed].sort());
 }finally{clock.mockRestore();}
});

test('位置记录仍有近期末次到达时保留；导航和录音严格平台筛选',async()=>{
 const location=await seed('locations');await pool.query('UPDATE location_track SET last_seen_at=NOW() WHERE id=$1',[location]);expect((await preview('locations')).body.total_records).toBe(0);
 await seed('navigation');expect((await preview('navigation',7,{platform:'baidu'})).body.total_records).toBe(0);expect((await preview('navigation',7,{platform:'amap'})).body.total_records).toBe(1);
 await seed('call-recordings');expect((await preview('call-recordings',7,{platform:'wechat'})).body.total_records).toBe(0);expect((await preview('call-recordings',7,{platform:'phone',recording_status:'ended'})).body.total_records).toBe(1);
});

test('拒绝额外范围/无效保留天数/确认/未来游标，过期和跨数据类别token失效',async()=>{
 for(const filters of [{user_id:B},{from:OLD},{table:'input_event'},{package_name:1}])expect((await preview('app-usage',7,filters)).status).toBe(400);
 for(const days of [0,2,31])expect((await preview('locations',days)).status).toBe(400);
 expect((await preview('navigation',7,{platform:'wechat'})).status).toBe(400);
 expect((await preview('not-a-dataset')).status).toBe(400);
 await seed('locations');const p=await preview('locations');expect(p.status).toBe(200);
 expect((await request(app).post('/retention/locations/batch').send({token:p.body.token,offset:0})).status).toBe(400);
 expect((await batch('locations',p.body.token,1)).status).toBe(409);expect((await batch('navigation',p.body.token)).status).toBe(410);
 const clock=vi.spyOn(Date,'now').mockReturnValue(Date.now()+16*60000);try{expect((await batch('locations',p.body.token)).status).toBe(410);}finally{clock.mockRestore();}
});

async function event(type='commit',time=OLD,session:string|null=null,text='测试'){const id=randomUUID();await pool.query(`INSERT INTO input_event(id,user_id,device_id,event_type,occurred_at,session_id,package_name,editor_id,metadata,text) VALUES($1,$2,$2,$3,$4,$5,'com.test','editor','{"edit_protocol":1}',$6)`,[id,A,type,time,session,text]);return id;}
test('共用输入统计清理包括底层事件但保留近期编辑组；剪贴板只清理复制粘贴',async()=>{
 const group=randomUUID();const keep=[await event('commit',OLD,group),await event('commit',new Date().toISOString(),group)];await event('key');const copy=await event('clipboard_change');const old=await event('commit');
 const clipboard=await preview('clipboard',7,{q:'测试',package_name:'com.test'});expect(clipboard.status).toBe(200);expect(clipboard.body.total_records).toBe(1);await batch('clipboard',clipboard.body.token);
 expect((await pool.query('SELECT id FROM input_event WHERE id=$1',[copy])).rowCount).toBe(0);expect((await pool.query('SELECT id FROM input_event WHERE id=$1',[old])).rowCount).toBe(1);
 const input=await preview('input');expect(input.status).toBe(200);expect(input.body.total_records).toBe(2);expect((await batch('input',input.body.token)).body.deleted_records).toBe(2);
 expect((await pool.query('SELECT id FROM input_event')).rows.map(r=>r.id).sort()).toEqual(keep.sort());
});

test('位置设备筛选不扩大，录音/通话结束跨截止保留，旧日期范围参数拒绝',async()=>{
 const keep=await seed('locations');await pool.query('UPDATE location_track SET device_id=$1 WHERE id=$2',[B,keep]);await seed('locations');
 const p=await preview('locations',7,{device_id:A});expect(p.status).toBe(200);expect(p.body.total_records).toBe(1);await batch('locations',p.body.token);expect(await ids('locations')).toEqual([keep]);
 expect((await preview('locations',7,{device_id:'invalid'})).status).toBe(400);
 const ms=Date.now()-86400000+1000,call=await seed('call-logs',new Date(ms-10000).toISOString());await pool.query('UPDATE phone_call_log SET duration_seconds=20 WHERE source_id=$1',[call]);expect((await preview('call-logs',1)).body.total_records).toBe(0);
 const recording=await seed('call-recordings');await pool.query('UPDATE call_recording SET metadata=$1 WHERE record_id=$2',[JSON.stringify({recording_ended_at:ms}),recording]);expect((await preview('call-recordings',1)).body.total_records).toBe(0);
 for(const kind of ['input','clipboard'])expect((await preview(kind,7,{from:OLD})).status).toBe(400);
});

test('预览不暴露内容，空预览不建立删除任务，导航记录改动和删除后跳过',async()=>{
 const empty=await preview('navigation');expect(empty.body.total_records).toBe(0);expect((await batch('navigation',empty.body.token)).status).toBe(410);
 const old=await seed('navigation'),gone=await seed('navigation');const p=await preview('navigation');expect(p.status).toBe(200);expect(p.body).not.toHaveProperty('targets');expect(JSON.stringify(p.body)).not.toContain('起点');
 await pool.query("UPDATE navigation_record SET destination='新终点' WHERE id=$1",[old]);await pool.query('DELETE FROM navigation_record WHERE id=$1',[gone]);
 const r=await batch('navigation',p.body.token);expect(r.body).toMatchObject({deleted_records:0,skipped_records:2,done:true});expect(await ids('navigation')).toEqual([old]);
});

async function learning(phrase:string,time:string|null=OLD,pkg:string|null='com.test',user=A){return String((await pool.query('INSERT INTO phrase_stat(user_id,phrase,package_name,use_count,last_used_at) VALUES($1,$2,$3,5,$4) RETURNING id',[user,phrase,pkg,time])).rows[0].id);}
async function candidate(phrase:string,time:string|null=OLD,pkg:string|null='com.test',user=A,created=OLD){return String((await pool.query('INSERT INTO completion_candidate(user_id,prefix,completion,package_name,last_used_at,created_at) VALUES($1,$2,$3,$4,$5,$6) RETURNING id',[user,phrase.slice(0,1),phrase,pkg,time,created])).rows[0].id);}
test('补全按最后使用整组清理候选及来源；保留近期/未知时间，区分null和空包名',async()=>{
 const oldPhrase=await learning('旧测试'),oldCandidate=await candidate('旧测试');
 await learning('近期测试');await candidate('近期测试',new Date().toISOString());
 await learning('未知时间',null);await candidate('未知时间');
 await learning('包名区分',OLD,null);await candidate('包名区分',OLD,null);
 await learning('包名区分',new Date().toISOString(),'');await candidate('包名区分',OLD,'');
 await learning('仅学习');await candidate('仅候选',null,'com.test',A,OLD);
 await candidate('新建未知',null,'com.test',A,new Date().toISOString());
 const foreign=await candidate('旧测试',OLD,'com.test',B);
 const p=await preview('completions');expect(p.status).toBe(200);expect(p.body.total_records).toBe(6);
 const r=await batch('completions',p.body.token);expect(r.status).toBe(200);expect(r.body).toMatchObject({deleted_records:6,skipped_records:0,done:true});
 expect((await pool.query('SELECT id FROM completion_candidate WHERE id=$1',[oldCandidate])).rowCount).toBe(0);expect((await pool.query('SELECT id FROM phrase_stat WHERE id=$1',[oldPhrase])).rowCount).toBe(0);
 const remaining=(await pool.query('SELECT completion FROM completion_candidate WHERE user_id=$1',[A])).rows.map(r=>r.completion).sort();expect(remaining).toEqual(['近期测试','未知时间','包名区分','新建未知'].sort());expect((await pool.query('SELECT id FROM completion_candidate WHERE id=$1',[foreign])).rowCount).toBe(1);
});

test('补全预览后新增前缀或学习变化整组跳过',async()=>{
 const source=await learning('变化词语');await candidate('变化词语');await learning('新增前缀');await candidate('新增前缀');const p=await preview('completions');expect(p.body.total_records).toBe(4);
 await pool.query('UPDATE phrase_stat SET use_count=6 WHERE id=$1',[source]);await pool.query("INSERT INTO completion_candidate(user_id,prefix,completion,package_name,last_used_at) VALUES($1,'新增','新增前缀','com.test',$2)",[A,OLD]);
 const r=await batch('completions',p.body.token);expect(r.status).toBe(200);expect(r.body).toMatchObject({deleted_records:0,skipped_records:4,done:true});
});

test('真实生成器等清理提交后再读来源，旧候选不会被并发重建',async()=>{
 await learning('并发旧词');await candidate('并发旧词');
 let locked!:()=>void,release!:()=>void;
 const hasLock=new Promise<void>(resolve=>{locked=resolve;}), gate=new Promise<void>(resolve=>{release=resolve;});
 const wrapped={query:pool.query.bind(pool),connect:async()=>{
   const db=await pool.connect();return new Proxy(db,{get(target,key){
     if(key==='query')return async(...args:any[])=>{const result=await (target.query as any)(...args);if(String(args[0]).startsWith('LOCK TABLE completion_candidate')){locked();await gate;}return result;};
     const value=(target as any)[key];return typeof value==='function'?value.bind(target):value;
   }});
 }} as pg.Pool;
 app=express();app.use(express.json());app.use((_req,res,next)=>{res.locals.userId=A;next();});app.use(createDashboardRouter(wrapped));
 const p=await preview('completions');const deleting=batch('completions',p.body.token).then(r=>r);
 await hasLock;const generating=generateCompletions(pool,A);
 try {
  let waiting=false;for(let i=0;i<100;i++){waiting=Number((await pool.query("SELECT COUNT(*) AS n FROM pg_locks WHERE locktype='advisory' AND NOT granted")).rows[0].n)>0;if(waiting)break;await new Promise(resolve=>setTimeout(resolve,10));}
  expect(waiting).toBe(true);
 }finally{release();}
 expect((await deleting).body.deleted_records).toBe(2);expect(await generating).toBe(0);
 expect((await pool.query('SELECT id FROM completion_candidate WHERE user_id=$1',[A])).rowCount).toBe(0);
},10000);

test('清理手机通话后再次同步旧source_id/date不回生，真正新日期复用ID允许',async()=>{
 const date=Date.now()-2*86400000,id=await seed('call-logs',new Date(date).toISOString());
 const p=await preview('call-logs',1);await batch('call-logs',p.body.token);
 const upload=(time:number)=>request(app).post('/upload-calls/sync').send({request_id:null,status:'synced',truncated:false,records:[{source_id:id,number:'10000',name:null,type:1,date:time,duration_seconds:0}]});
 const r=await upload(date);expect(r.status).toBe(200);expect(await ids('call-logs')).toEqual([]);
 expect((await upload(Date.now())).status).toBe(200);expect(await ids('call-logs')).toEqual([id]);
});

test('已删应用使用段和原始输入重传成功回执但不复活；新ID不被期限规则拦截',async()=>{
 const id=await seed('app-usage'),input=await event();
 for(const kind of ['app-usage','input']){const p=await preview(kind);expect(p.status).toBe(200);await batch(kind,p.body.token);}
 const ms=Date.parse(OLD),fresh=randomUUID();
 const usage=await request(app).post('/upload-usage/app-usage/batch').set('X-Device-Id',A).send({records:[id,fresh].map(id=>({id,kind:'usage',package_name:'com.test',app_name:null,start_ms:ms-5000,end_ms:ms,end_reason:'switch'}))});expect(usage.status).toBe(200);expect(await ids('app-usage')).toEqual([fresh]);
 const freshEvent=randomUUID();const events=await request(app).post('/upload-input/events/batch').send({device_id:A,events:[input,freshEvent].map(id=>({id,device_id:A,event_type:'commit',occurred_at:OLD,text:'测试'}))});expect(events.status).toBe(200);expect(events.body).toMatchObject({received:2,inserted:1});
 expect((await pool.query('SELECT id FROM input_event')).rows.map(r=>r.id)).toEqual([freshEvent]);
});

test('已删导航同内容重试不重存截图，同ID不同内容拒绝',async()=>{
 const bytes=await sharp({create:{width:2,height:2,channels:3,background:'#112233'}}).png().toBuffer();
 const id=randomUUID(),body={id,platform:'amap',origin:'起点',destination:'终点',started_at:Date.parse(OLD),overview_at:Date.parse(OLD),mime_type:'image/png',sha256:createHash('sha256').update(bytes).digest('hex'),file_base64:bytes.toString('base64')};
 expect((await request(app).post('/upload-navigation').send(body)).status).toBe(200);const p=await preview('navigation');await batch('navigation',p.body.token);
 const retry=await request(app).post('/upload-navigation').send(body);expect(retry.status).toBe(200);expect(retry.body.deleted).toBe(true);expect(await ids('navigation')).toEqual([]);
 expect((await request(app).post('/upload-navigation').send({...body,destination:'其他终点'})).status).toBe(409);
});

test('删除事务未提交时到达的重传等待同锁，不会绕过尚未提交的精确墓碑',async()=>{
 const id=await seed('app-usage');let marked!:()=>void,release!:()=>void;
 const hasMarker=new Promise<void>(r=>{marked=r;}),gate=new Promise<void>(r=>{release=r;});
 const wrapped={query:pool.query.bind(pool),connect:async()=>{const db=await pool.connect();return new Proxy(db,{get(target,key){
  if(key==='query')return async(...args:any[])=>{const result=await(target.query as any)(...args);if(String(args[0]).trim().startsWith('INSERT INTO retention_deleted_record')){marked();await gate;}return result;};
  const value=(target as any)[key];return typeof value==='function'?value.bind(target):value;
 }});}} as pg.Pool;
 app=express();app.use(express.json());app.use((_req,res,next)=>{res.locals.userId=A;next();});app.use(createDashboardRouter(wrapped));app.use('/upload-usage',createMobileAppUsageRouter(wrapped));
 const p=await preview('app-usage');const deleting=batch('app-usage',p.body.token).then(r=>r);await hasMarker;
 const ms=Date.parse(OLD);const upload=request(app).post('/upload-usage/app-usage/batch').set('X-Device-Id',A).send({records:[{id,kind:'usage',package_name:'com.test',app_name:null,start_ms:ms-5000,end_ms:ms,end_reason:'switch'}]}).then(r=>r);
 try{let waiting=false;for(let i=0;i<100;i++){waiting=Number((await pool.query("SELECT COUNT(*) AS n FROM pg_locks WHERE locktype='advisory' AND NOT granted")).rows[0].n)>0;if(waiting)break;await new Promise(r=>setTimeout(r,10));}expect(waiting).toBe(true);}finally{release();}
 expect((await deleting).body.deleted_records).toBe(1);expect((await upload).status).toBe(200);expect(await ids('app-usage')).toEqual([]);
},10000);
