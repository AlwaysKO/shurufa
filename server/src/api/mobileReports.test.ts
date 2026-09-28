import { readFileSync } from 'node:fs';
import { newDb, DataType } from 'pg-mem';
import request from 'supertest';
import express from 'express';
import { beforeEach, expect, it } from 'vitest';
import { createMobileRouter } from './mobile.js';
const device = '00000000-0000-4000-8000-000000000001';
let pool: any;
let app: express.Express;
beforeEach(async () => {
  const db = newDb();
  db.public.registerFunction({name:'greatest',args:[DataType.timestamptz,DataType.timestamptz],returns:DataType.timestamptz,
    implementation:(a:Date,b:Date)=>a>b?a:b});
  pool = new (db.adapters.createPg().Pool)();
  await pool.query(`CREATE TABLE completion_candidate(user_id UUID, prefix TEXT, completion TEXT, accept_count INT DEFAULT 0, show_count INT DEFAULT 0, last_used_at TIMESTAMPTZ);
    INSERT INTO completion_candidate(user_id,prefix,completion) VALUES('${device}','你好','你好世界');
    CREATE TABLE location_track(id BIGSERIAL PRIMARY KEY, user_id UUID, device_id UUID, latitude DOUBLE PRECISION, longitude DOUBLE PRECISION, accuracy REAL, provider TEXT, speed REAL, occurred_at TIMESTAMPTZ, first_seen_at TIMESTAMPTZ DEFAULT NOW(), last_seen_at TIMESTAMPTZ DEFAULT NOW(), context JSONB);`);
  // The migration is loaded once implemented; before then missing route must fail the assertions.
  try { await pool.query(readFileSync(new URL('../../migrations/013_durable_reports.sql', import.meta.url), 'utf8')); } catch (e: any) { if(e.code !== 'ENOENT') throw e; }
  app = express(); app.use(express.json()); app.use((_req,res,next)=>{res.locals.userId=device;next();});
  app.use(createMobileRouter(pool));
});
it('候选反馈重复送达只计一次且返回稳定确认 ID', async () => {
 const report = { id: crypto.randomUUID(), kind: 'completion_feedback', payload: {prefix:'你好',completion:'你好世界',accepted:true} };
 for(let i=0;i<2;i++) { const res = await request(app).post('/reports').send(report); expect(res.status).toBe(200); expect(res.body).toMatchObject({ok:true,id:report.id}); }
 expect((await pool.query('SELECT accept_count FROM completion_candidate')).rows[0].accept_count).toBe(1);
});
it('离线位置保留原始时间并按报告 ID 去重',async()=>{
 const report={id:crypto.randomUUID(),kind:'location',payload:{latitude:31,longitude:121,occurred_at:'2026-09-01T01:00:00Z'}};
 await request(app).post('/reports').send(report);
 const res=await request(app).post('/reports').send(report); expect(res.status).toBe(200);
 const rows=(await pool.query('SELECT * FROM location_track')).rows; expect(rows).toHaveLength(1); expect(rows[0].occurred_at.toISOString()).toBe('2026-09-01T01:00:00.000Z');
});

const locationContext = {
 version: 1, captured_at: '2026-09-29T01:00:00Z', capture_mode: 'balanced', network_type: 'wifi',
 wifi: {status:'connected',ssid:'办公室 <Wi-Fi>',bssid:'aa:bb:cc:dd:ee:ff',rssi:-60,frequency_mhz:5180,link_speed_mbps:866},
 battery_percent: 75, charging: false, is_interactive: true, power_save: false,
 altitude_m: 15.5, bearing_deg: 90, speed_accuracy_mps: 0.5,
};
it('位置快照保存Wi-Fi和设备状态，离线补传与重复报告不丢字段',async()=>{
 const report={id:crypto.randomUUID(),kind:'location',payload:{latitude:31,longitude:121,occurred_at:'2026-09-29T01:00:00Z',context:locationContext}};
 for(let i=0;i<2;i++) expect((await request(app).post('/reports').send(report)).status).toBe(200);
 const rows=(await pool.query('SELECT * FROM location_track')).rows;
 expect(rows).toHaveLength(1); expect(rows[0].context).toEqual(locationContext);
 expect(rows[0].first_seen_at.toISOString()).toBe('2026-09-29T01:00:00.000Z');
 expect(rows[0].last_seen_at.toISOString()).toBe('2026-09-29T01:00:00.000Z');
});
it.each([
 [], {version:2}, {version:1,battery_percent:101}, {version:1,charging:'yes'},
 {version:1,captured_at:'bad-date'}, {version:1,network_type:'bad'},
 {version:1,wifi:{status:'connected',ssid:'x'.repeat(129)}},
 {version:1,wifi:{status:'connected',bssid:'02:00:00:00:00:00'}},
 {version:1,wifi:{status:'connected',rssi:500}}, {version:1,bearing_deg:360},
 {version:1,speed_accuracy_mps:-1}, {version:1,password:'not-a-supported-field'},
].map(context=>[context]))('拒绝非法位置快照 %j',async context=>{
 const payload={latitude:31,longitude:121,occurred_at:'2026-09-29T01:00:00Z',context};
 expect((await request(app).post('/reports').send({id:crypto.randomUUID(),kind:'location',payload})).status).toBe(400);
 expect((await pool.query('SELECT * FROM location_track')).rows).toHaveLength(0);
});
it('旧位置入口也保存新快照，同坐标不同采集时间保留心跳记录',async()=>{
 for(const minute of ['00','05']) {
  const payload={device_id:device,latitude:31,longitude:121,occurred_at:`2026-09-29T01:${minute}:00Z`,context:{...locationContext,captured_at:`2026-09-29T01:${minute}:00Z`}};
  expect((await request(app).post('/location').send(payload)).status).toBe(200);
 }
 const rows=(await pool.query('SELECT * FROM location_track ORDER BY occurred_at')).rows;
 expect(rows).toHaveLength(2); expect(rows[1].context.wifi.ssid).toBe('办公室 <Wi-Fi>');
 expect(rows[1].last_seen_at.toISOString()).toBe('2026-09-29T01:05:00.000Z');
});
it('旧位置入口拒绝非法快照',async()=>{
 expect((await request(app).post('/location').send({device_id:device,latitude:31,longitude:121,context:{version:1,battery_percent:-1}})).status).toBe(400);
});
it('旧格式补传不能覆盖同坐标新快照，旧记录合并只推进最近一条采集时间',async()=>{
 const send=(minute:string,context?:unknown)=>request(app).post('/location').send({device_id:device,latitude:31,longitude:121,occurred_at:`2026-09-29T01:${minute}:00Z`,context});
 for(const [minute,context] of [['00',locationContext],['05',locationContext],['10',undefined],['15',undefined],['12',undefined]] as const) {
  const res=await send(minute,context); expect(res.status,res.text).toBe(200);
 }
 const rows=(await pool.query('SELECT * FROM location_track ORDER BY id')).rows;
 expect(rows).toHaveLength(3);
 expect(rows.map((r:any)=>r.last_seen_at.toISOString())).toEqual(['2026-09-29T01:00:00.000Z','2026-09-29T01:05:00.000Z','2026-09-29T01:15:00.000Z']);
});
it('不接受未知类型和无效坐标',async()=>{
 expect((await request(app).post('/reports').send({id:crypto.randomUUID(),kind:'unknown',payload:{}})).status).toBe(400);
 expect((await request(app).post('/reports').send({id:crypto.randomUUID(),kind:'location',payload:{latitude:91,longitude:0}})).status).toBe(400);
});
it('同 ID 不同内容不能伪装重复成功',async()=>{
 const id=crypto.randomUUID();
 const payload={prefix:'你好',completion:'你好世界',accepted:true};
 await request(app).post('/reports').send({id,kind:'completion_feedback',payload});
 expect((await request(app).post('/reports').send({id,kind:'completion_feedback',payload:{...payload,accepted:false}})).status).toBe(409);
});
it('个人选词快照即时存储且迟到旧快照不覆盖新统计', async()=>{
 const send=(count:number,time:number)=>request(app).post('/reports').send({id:crypto.randomUUID(),kind:'personal_choice',payload:{code:'xuq',text:'需求',count,weight:count,last_used:time}});
 expect((await send(3,3000)).status).toBe(200); expect((await send(2,2000)).status).toBe(200);
 expect((await pool.query('SELECT count FROM personal_candidate_usage')).rows[0].count).toBe(3);
});
it('目标不存在候选时反馈仍持久保存，不得只确认空更新',async()=>{
 const report={id:crypto.randomUUID(),kind:'completion_feedback',payload:{prefix:'再见',completion:'再见朋友',accepted:true}};
 expect((await request(app).post('/reports').send(report)).status).toBe(200);
 const exists=(await pool.query(`SELECT table_name FROM information_schema.tables WHERE table_name='completion_feedback_usage'`)).rows;
 expect(exists.length).toBeGreaterThan(0);
 expect((await pool.query('SELECT accept_count FROM completion_feedback_usage WHERE completion=$1',['再见朋友'])).rows[0].accept_count).toBe(1);
});
it('本地没有线上表情素材也保留稳定文件标识使用次数',async()=>{
 await pool.query('CREATE TABLE sticker(user_id UUID, file_name TEXT, use_count INT DEFAULT 0)');
 const report={id:crypto.randomUUID(),kind:'sticker_use',payload:{file_name:'cloud-sticker.gif'}};
 expect((await request(app).post('/reports').send(report)).status).toBe(200);
 expect((await pool.query(`SELECT table_name FROM information_schema.tables WHERE table_name='sticker_file_usage'`)).rows.length).toBeGreaterThan(0);
 expect((await pool.query('SELECT use_count FROM sticker_file_usage')).rows[0].use_count).toBe(1);
});
it('手机时钟回拨后更高累计次数的快照仍可推进',async()=>{
 const send=(count:number,time:number)=>request(app).post('/reports').send({id:crypto.randomUUID(),kind:'personal_choice',payload:{code:'xuq',text:'需求',count,weight:count,last_used:time}});
 await send(3,3000); expect((await send(4,1000)).status).toBe(200);
 expect((await pool.query('SELECT count FROM personal_candidate_usage')).rows[0].count).toBe(4);
});

it('一两键个人选择允许上报且重复回执不累计',async()=>{
 for(const code of ['3','62']) {
  const report={id:crypto.randomUUID(),kind:'personal_choice',payload:{code,text:'的',count:1,weight:1,last_used:1000}};
  expect((await request(app).post('/reports').send(report)).status).toBe(200);
  expect((await request(app).post('/reports').send(report)).status).toBe(200);
 }
 expect((await pool.query('SELECT count FROM personal_candidate_usage')).rows.map((r:any)=>r.count)).toEqual([1,1]);
});

it('公共图库使用次数跨设备归集，重发报告不重复计数', async()=>{
  await pool.query('CREATE TABLE sticker(user_id UUID, file_name TEXT, use_count INT DEFAULT 0)');
  await pool.query("INSERT INTO sticker(user_id,file_name) VALUES('00000000-0000-4000-8000-000000000000','shared.gif')");
  const report={id:crypto.randomUUID(),kind:'sticker_use',payload:{file_name:'shared.gif'}};
  for(let i=0;i<2;i++) expect((await request(app).post('/reports').send(report)).status).toBe(200);
  expect((await pool.query('SELECT use_count FROM sticker')).rows[0].use_count).toBe(1);
});
