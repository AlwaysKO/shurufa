import { readFileSync } from 'node:fs';
import { newDb } from 'pg-mem';
import request from 'supertest';
import express from 'express';
import { beforeEach, expect, it } from 'vitest';
import { createMobileRouter } from './mobile.js';
const device = '00000000-0000-4000-8000-000000000001';
let pool: any;
let app: express.Express;
beforeEach(async () => {
  pool = new (newDb().adapters.createPg().Pool)();
  await pool.query(`CREATE TABLE completion_candidate(user_id UUID, prefix TEXT, completion TEXT, accept_count INT DEFAULT 0, show_count INT DEFAULT 0, last_used_at TIMESTAMPTZ);
    INSERT INTO completion_candidate(user_id,prefix,completion) VALUES('${device}','你好','你好世界');
    CREATE TABLE location_track(user_id UUID, device_id UUID, latitude DOUBLE PRECISION, longitude DOUBLE PRECISION, accuracy REAL, provider TEXT, speed REAL, occurred_at TIMESTAMPTZ);`);
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
