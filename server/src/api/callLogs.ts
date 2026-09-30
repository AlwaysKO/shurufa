import { Router, type Request, type Response, type NextFunction } from 'express';
import { randomUUID } from 'node:crypto';
import type pg from 'pg';
import { savingFlags } from '../lib/deviceSaving.js';
const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
// 手机读取严格限制最近7天；服务端为读取、传输耗时和设备时钟差保留5分钟容差。
const WEEK=7*86400000,CLOCK_TOLERANCE=5*60000;
class LogError extends Error { constructor(readonly status:number,message:string){super(message);} }
const fail=(status:number,message:string):never=>{throw new LogError(status,message);};
const handle=(fn:(req:Request,res:Response)=>Promise<void>)=>(req:Request,res:Response,next:NextFunction)=>{void fn(req,res).catch(next);};
function errors(e:Error,_req:Request,res:Response,_next:NextFunction){res.status(e instanceof LogError?e.status:503).json({error:e instanceof LogError?e.message:'call_log_unavailable'});}
function batchOf(value:unknown){
  const b=value as Record<string,unknown>;
  if(!b||typeof b!=='object'||Array.isArray(b)||!(b.request_id===null||typeof b.request_id==='string'&&uuid.test(b.request_id))||
    !['synced','permission_required','disabled','failed'].includes(String(b.status))||typeof b.truncated!=='boolean'||!Array.isArray(b.records)||b.records.length>2000)fail(400,'invalid_call_log_batch');
  if(b.status!=='synced'&&(b.records as unknown[]).length)fail(400,'invalid_call_log_batch');
  const now=Date.now();
  const text=(v:unknown,n:number)=>v===null||typeof v==='string'&&v.length<=n&&!/[\x00-\x1f]/.test(v);
  const records=(b.records as unknown[]).map(value=>{
    const row=value as Record<string,unknown>;
    if(!row||typeof row!=='object'||Array.isArray(row)||typeof row.source_id!=='string'||!row.source_id.length||row.source_id.length>200||/[\x00-\x1f]/.test(row.source_id)||
      !text(row.number,200)||!text(row.name,200)||!Number.isInteger(row.type)||Number(row.type)<1||Number(row.type)>7||
      !Number.isSafeInteger(row.date)||Number(row.date)<now-WEEK-CLOCK_TOLERANCE||Number(row.date)>now+CLOCK_TOLERANCE||
      !Number.isSafeInteger(row.duration_seconds)||Number(row.duration_seconds)<0||Number(row.duration_seconds)>2147483647)fail(400,'invalid_call_log_record');
    return {source_id:row.source_id,number:row.number,name:row.name,type:row.type,date:row.date,duration_seconds:row.duration_seconds};
  });
  if(new Set(records.map(row=>row.source_id)).size!==records.length)fail(400,'duplicate_call_log_source');
  return {request_id:b.request_id,status:String(b.status),truncated:b.truncated,records};
}
export function createMobileCallLogsRouter(pool:pg.Pool){
  const r=Router();
  r.get('/sync',handle(async(_req,res)=>{
    const row=(await pool.query('SELECT request_id FROM phone_call_log_sync WHERE device_id=$1',[res.locals.userId])).rows[0];
    res.json({request_id:row?.request_id??null});
  }));
  r.post('/sync',handle(async(req,res)=>{
    const b=batchOf(req.body),device=res.locals.userId;
    if((await savingFlags(pool,[device])).get(device)===false)fail(409,'saving_disabled');
    const db=await pool.connect();
    try{
      await db.query('BEGIN');await db.query('SET LOCAL synchronous_commit = on');
      await db.query('INSERT INTO phone_call_log_sync(device_id) VALUES($1) ON CONFLICT DO NOTHING',[device]);
      const state=(await db.query('SELECT request_id FROM phone_call_log_sync WHERE device_id=$1 FOR UPDATE',[device])).rows[0];
      if(b.request_id!==null&&b.request_id!==state.request_id)fail(409,'stale_sync_request');
      if(b.records.length)await db.query(`INSERT INTO phone_call_log(device_id,source_id,number,name,type,date,duration_seconds)
        SELECT $1,x.source_id,x.number,x.name,x.type,x.date,x.duration_seconds FROM jsonb_to_recordset($2::jsonb)
          AS x(source_id text,number text,name text,type integer,date bigint,duration_seconds integer)
        ON CONFLICT(device_id,source_id) DO UPDATE SET number=EXCLUDED.number,name=EXCLUDED.name,type=EXCLUDED.type,
          date=EXCLUDED.date,duration_seconds=EXCLUDED.duration_seconds`,[device,JSON.stringify(b.records)]);
      // 自动增量上报不能消费另一轮后台请求或把等待状态伪装成已完成。
      if(b.request_id!==null||state.request_id===null)await db.query(`UPDATE phone_call_log_sync SET request_id=NULL,status=$2,
        synced_at=CASE WHEN $2='synced' THEN NOW() ELSE synced_at END,truncated=$3 WHERE device_id=$1`,[device,b.status,b.truncated]);
      await db.query('COMMIT');res.json({stored:true,count:b.records.length});
    }catch(e){await db.query('ROLLBACK').catch(()=>{});throw e;}finally{db.release();}
  }));
  r.use(errors);return r;
}
export function createDashboardCallLogsRouter(pool:pg.Pool){
  const r=Router();
  r.get('/',handle(async(req,res)=>{
    const page=Number(req.query.page??1);if(!Number.isSafeInteger(page)||page<1||page>100000)fail(400,'invalid_page');
    const device=res.locals.userId;
    const total=Number((await pool.query('SELECT COUNT(*) AS n FROM phone_call_log WHERE device_id=$1',[device])).rows[0].n);
    const records=(await pool.query('SELECT source_id,number,name,type,date,duration_seconds FROM phone_call_log WHERE device_id=$1 ORDER BY date DESC,source_id LIMIT 50 OFFSET $2',[device,(page-1)*50])).rows.map(row=>({...row,date:Number(row.date)}));
    const sync=(await pool.query('SELECT request_id,requested_at,status,synced_at,truncated FROM phone_call_log_sync WHERE device_id=$1',[device])).rows[0]??{request_id:null,requested_at:null,status:'never',synced_at:null,truncated:false};
    res.json({total,page,records,sync});
  }));
  r.post('/sync',handle(async(_req,res)=>{
    const requestId=randomUUID();
    await pool.query(`INSERT INTO phone_call_log_sync(device_id,request_id,requested_at,status) VALUES($1,$2,NOW(),'pending')
      ON CONFLICT(device_id) DO UPDATE SET request_id=EXCLUDED.request_id,requested_at=EXCLUDED.requested_at,status='pending'`,[res.locals.userId,requestId]);
    res.json({request_id:requestId,status:'pending'});
  }));
  r.use(errors);return r;
}
