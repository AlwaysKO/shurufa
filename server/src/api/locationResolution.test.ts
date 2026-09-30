import express from 'express';
import request from 'supertest';
import type pg from 'pg';
import { afterEach, expect, it, vi } from 'vitest';
import { createDashboardRouter } from './dashboard.js';
import { resolveMissingAddresses, addressResolution } from '../lib/geocoder.js';

vi.mock('../lib/geocoder.js', () => ({ resolveMissingAddresses: vi.fn(), addressResolution: vi.fn() }));
afterEach(() => vi.resetAllMocks());

it('指定日期按北京时间零点到次日零点查询，并保留用户和设备范围', async () => {
  const query = vi.fn().mockResolvedValue({ rows: [] });
  vi.mocked(resolveMissingAddresses).mockResolvedValue(undefined);
  const app = express();
  app.use((_req, res, next) => { res.locals.userId = 'owner'; next(); });
  app.use(createDashboardRouter({ query } as unknown as pg.Pool));
  const response = await request(app).get('/locations?date=2026-09-29&device_id=phone&limit=1000');
  expect(response.status).toBe(200);
  expect(response.body).toMatchObject({ date: '2026-09-29', total: 0 });
  expect(query.mock.calls[0][0]).toContain('occurred_at >= $2');
  expect(query.mock.calls[0][0]).toContain('occurred_at < $3');
  expect(query.mock.calls[0][1]).toEqual(['owner', new Date('2026-09-28T16:00:00Z'), new Date('2026-09-29T16:00:00Z'), 'phone', 1001]);
});

it.each(['2026-02-30', '2026-13-01', '2026-9-29', '', 'not-a-date'])('日期 %s 无效时拒绝而非查询全部', async date => {
  const query = vi.fn().mockResolvedValue({ rows: [] });
  vi.mocked(resolveMissingAddresses).mockResolvedValue(undefined);
  const app = express();
  app.use((_req, res, next) => { res.locals.userId = 'owner'; next(); });
  app.use(createDashboardRouter({ query } as unknown as pg.Pool));
  const response = await request(app).get('/locations').query({ date });
  expect(response.status).toBe(400);
  expect(query).not.toHaveBeenCalled();
});
it('位置接口不等待解析完成，返回明确状态并仅调度当前用户空地址记录', async () => {
  const userId = '00000000-0000-4000-8000-000000000001';
  const rows = [
    { id: '1', latitude: '1', longitude: '2', address: null },
    { id: '2', latitude: '3', longitude: '4', address: '已有地址' },
  ];
  const pool = { query: vi.fn().mockResolvedValue({ rows }) } as unknown as pg.Pool;
  vi.mocked(resolveMissingAddresses).mockImplementation(() => new Promise(() => {}));
  vi.mocked(addressResolution).mockReturnValue({ address_status: 'failed', address_error: '地址服务请求失败或超时', address_retry_at: '2026-09-17T01:01:00.000Z' });
  const app = express();
  app.use((_req, res, next) => { res.locals.userId = userId; next(); });
  app.use(createDashboardRouter(pool));
  const response = await request(app).get('/locations');
  expect(response.status).toBe(200);
  expect(resolveMissingAddresses).toHaveBeenCalledWith(pool, [{ id: '1', lat: 1, lng: 2 }], userId);
  expect(response.body.locations[0]).toMatchObject({ address: null, address_status: 'failed', address_error: '地址服务请求失败或超时', address_retry_at: '2026-09-17T01:01:00.000Z' });
  expect(response.body.locations[1]).toMatchObject({ address: '已有地址', address_status: 'resolved', address_error: null, address_retry_at: null });
  expect(addressResolution).toHaveBeenCalledTimes(1);
});

it('位置查询返回快照和截断标记，额外探测点不参与地址解析',async()=>{
 const rows=[1,2,3].map(id=>({id,latitude:'31',longitude:'121',address:'已有地址',context:{version:1,network_type:'wifi'}}));
 const query=vi.fn().mockResolvedValue({rows});
 const pool={query} as unknown as pg.Pool;
 vi.mocked(resolveMissingAddresses).mockResolvedValue(undefined);
 const app=express();app.use((_req,res,next)=>{res.locals.userId='owner';next();});app.use(createDashboardRouter(pool));
 const response=await request(app).get('/locations?limit=2&device_id=device-a');
 expect(response.status).toBe(200);
 expect(response.body).toMatchObject({total:2,has_more:true});
 expect(response.body.locations).toHaveLength(2);
 expect(response.body.locations[0].context).toEqual({version:1,network_type:'wifi'});
 expect(query.mock.calls[0][0]).toMatch(/\bcontext\b/);
 expect(query.mock.calls[0][1].slice(-2)).toEqual(['device-a',3]);
});

it('跨天范围包含结束日全天，使用北京时间并保留用户和设备限制',async()=>{
 const query=vi.fn().mockResolvedValue({rows:[]});vi.mocked(resolveMissingAddresses).mockResolvedValue(undefined);
 const app=express();app.use((_req,res,next)=>{res.locals.userId='owner';next();});app.use(createDashboardRouter({query} as unknown as pg.Pool));
 const response=await request(app).get('/locations').query({from:'2026-02-24',to:'2026-03-02',device_id:'phone',limit:1000});
 expect(response.status).toBe(200);expect(response.body).toMatchObject({from:'2026-02-24',to:'2026-03-02',days:7});
 expect(query.mock.calls[0][0]).toContain('occurred_at < $3');
 expect(query.mock.calls[0][1]).toEqual(['owner',new Date('2026-02-23T16:00:00Z'),new Date('2026-03-02T16:00:00Z'),'phone',1001]);
});
it.each([
 {from:'2026-09-01'}, {to:'2026-09-30'}, {from:'',to:'2026-09-30'},
 {from:'2026-02-30',to:'2026-03-02'}, {from:'2026-09-30',to:'2026-09-01'},
 {from:'2026-09-01',to:'2026-09-30',date:'2026-09-05'},
 {from:'2026-09-01',to:'2026-09-30',days:7},
])('拒绝无效、不完整或冲突范围 %j，不回退为默认查询',async range=>{
 vi.mocked(resolveMissingAddresses).mockResolvedValue(undefined);
 const query=vi.fn().mockResolvedValue({rows:[]});const app=express();app.use(createDashboardRouter({query} as unknown as pg.Pool));
 const response=await request(app).get('/locations').query(range);
 expect(response.status).toBe(400);expect(query).not.toHaveBeenCalled();
});

it.each([
 ['2026-09-30','2026-09-30',1,'2026-09-29T16:00:00Z','2026-09-30T16:00:00Z'],
 ['2024-02-28','2024-03-01',3,'2024-02-27T16:00:00Z','2024-03-01T16:00:00Z'],
 ['2025-12-31','2026-01-01',2,'2025-12-30T16:00:00Z','2026-01-01T16:00:00Z'],
])('范围 %s 至 %s 的北京时间边界正确',async(from,to,days,start,end)=>{
 const query=vi.fn().mockResolvedValue({rows:[]});vi.mocked(resolveMissingAddresses).mockResolvedValue(undefined);
 const app=express();app.use((_req,res,next)=>{res.locals.userId='owner';next();});app.use(createDashboardRouter({query} as unknown as pg.Pool));
 const response=await request(app).get('/locations').query({from,to});
 expect(response.status).toBe(200);expect(response.body.days).toBe(days);
 expect(query.mock.calls[0][1]).toEqual(['owner',new Date(start),new Date(end),201]);
});
