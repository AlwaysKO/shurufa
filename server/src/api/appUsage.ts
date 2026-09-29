import { Router } from 'express';
import type pg from 'pg';
import { savingFlags } from '../lib/deviceSaving.js';
const UUID=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const text=(v:unknown,max:number)=>typeof v==='string' && v.trim().length>0 && v.length<=max && !v.includes('\0');
function validRecords(records:unknown): records is Array<Record<string,unknown>> {
 const now=Date.now()+300000;
 return Array.isArray(records) && records.length>=1 && records.length<=200 && records.every(r=>r && typeof r==='object'
  && typeof r.id==='string' && UUID.test(r.id) && ['usage','gap'].includes(r.kind)
  && Number.isSafeInteger(r.start_ms) && Number.isSafeInteger(r.end_ms) && r.start_ms>=946684800000 && r.end_ms>r.start_ms && r.end_ms<=now
  && text(r.end_reason,80) && (r.kind==='gap' ? r.package_name===null && r.app_name===null : text(r.package_name,255) && (r.app_name===null || text(r.app_name,256))));
}
export function createMobileAppUsageRouter(pool:pg.Pool) {
 const router=Router();
 router.post('/app-usage/batch',async(req,res,next)=>{
  const records=req.body?.records;
  if(!req.get('X-Device-Id') || !validRecords(records)){res.status(400).json({error:'invalid app usage batch'});return;}
  try {
   const user=String(res.locals.userId).toLowerCase();
   // 在校验之后检查保存开关：关闭也不能把坏请求当作有效回执。
   const flags=await savingFlags(pool,[user]);
   await pool.query('INSERT INTO device(id) VALUES($1) ON CONFLICT(id) DO NOTHING',[user]);
   if(flags.get(user)===false){res.json({ok:true,received:records.length,discarded:true});return;}
   await pool.query(`INSERT INTO app_usage_segment(user_id,id,kind,package_name,app_name,start_ms,end_ms,end_reason)
     SELECT $1,r.id,r.kind,r.package_name,r.app_name,r.start_ms,r.end_ms,r.end_reason
     FROM jsonb_to_recordset($2::jsonb) AS r(id uuid,kind text,package_name text,app_name text,start_ms bigint,end_ms bigint,end_reason text)
     ON CONFLICT(user_id,id) DO NOTHING`,[user,JSON.stringify(records)]);
   res.json({ok:true,received:records.length});
  }catch(error){next(error);}
 });return router;
}
export function createDashboardAppUsageRouter(pool:pg.Pool) {
 const router=Router();
 router.get('/app-usage/day',async(req,res,next)=>{
  const day=req.query.day,pkg=req.query.package_name??null;
  const utc=typeof day==='string' && /^\d{4}-\d{2}-\d{2}$/.test(day) ? Date.parse(`${day}T00:00:00Z`) : NaN;
  if(!Number.isFinite(utc)||new Date(utc).toISOString().slice(0,10)!==day||(pkg!==null&&!text(pkg,255))){res.status(400).json({error:'请选择有效日期（北京时间）和App包名'});return;}
  const start=utc-8*3600000,end=start+86400000,limit=2000;
  try {
   const result=await pool.query(`WITH selected AS (
    SELECT id,kind,package_name,app_name,start_ms::float8,end_ms::float8,end_reason,
      GREATEST(start_ms,$2::bigint)::float8 AS clipped_start_ms,LEAST(end_ms,$3::bigint)::float8 AS clipped_end_ms,
      (LEAST(end_ms,$3::bigint)-GREATEST(start_ms,$2::bigint))::float8 AS duration_ms
    FROM app_usage_segment WHERE user_id=$1 AND end_ms>$2 AND start_ms<$3
     AND ($4::text IS NULL OR package_name=$4 OR kind='gap')
   ), limited AS (SELECT * FROM selected ORDER BY start_ms,id LIMIT 2000)
   SELECT (SELECT COUNT(*)::int FROM selected) AS total,
    COALESCE((SELECT json_agg(r ORDER BY start_ms,id) FROM limited r),'[]'::json) AS records`,[res.locals.userId,start,end,pkg]);
   const {total,records}=result.rows[0];
   res.json({day,start_ms:start,end_ms:end,total,limit,truncated:total>limit,records});
  }catch(error){next(error);}
 });

 router.get('/app-usage',async(req,res,next)=>{
  const iso=(v:unknown)=>{
   if(typeof v!=='string'||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,3})?)?(Z|[+-]\d{2}:\d{2})$/.test(v))return NaN;
   const date=Date.parse(`${v.slice(0,10)}T00:00:00Z`);
   if(!Number.isFinite(date)||new Date(date).toISOString().slice(0,10)!==v.slice(0,10))return NaN;
   return Date.parse(v);
  };
  const from=iso(req.query.from),to=iso(req.query.to),page=Number(req.query.page??1),pkg=req.query.package_name??null;
  if(!Number.isFinite(from)||!Number.isFinite(to)||to<=from||to-from>366*86400000||!Number.isSafeInteger(page)||page<1||page>1000000||(pkg!==null&&!text(pkg,255))){res.status(400).json({error:'有效起止时间（含时区，最多366天）、页码和App包名必填/必需有效'});return;}
  try {
   const result=await pool.query(`WITH clipped AS (
     SELECT *,GREATEST(start_ms,$2::bigint) AS s, LEAST(end_ms,$3::bigint) AS e
     FROM app_usage_segment WHERE user_id=$1 AND end_ms>$2 AND start_ms<$3
       AND ($4::text IS NULL OR package_name=$4 OR kind='gap')
    ), usage AS (SELECT * FROM clipped WHERE kind='usage'),
    apps AS (SELECT package_name,MAX(app_name) AS app_name,SUM(e-s)::float8 AS duration_ms,COUNT(*)::int AS count FROM usage GROUP BY package_name),
    days AS (
     SELECT to_char(d,'YYYY-MM-DD') AS day,
      SUM(LEAST(e,extract(epoch FROM ((d+interval '1 day') AT TIME ZONE 'Asia/Shanghai'))*1000)
         -GREATEST(s,extract(epoch FROM (d AT TIME ZONE 'Asia/Shanghai'))*1000))::float8 AS duration_ms
     FROM usage CROSS JOIN LATERAL generate_series(
       date_trunc('day',to_timestamp(s/1000.0) AT TIME ZONE 'Asia/Shanghai'),
       date_trunc('day',to_timestamp((e-1)/1000.0) AT TIME ZONE 'Asia/Shanghai'), interval '1 day') d GROUP BY d
    ), records AS (
     SELECT id,kind,package_name,app_name,start_ms::float8,end_ms::float8,end_reason,(e-s)::float8 AS duration_ms
     FROM clipped ORDER BY start_ms DESC,id LIMIT 50 OFFSET $5
    )
    SELECT json_build_object(
     'overview',json_build_object('last_received_at',(SELECT MAX(received_at) FROM app_usage_segment WHERE user_id=$1),'duration_ms',(SELECT COALESCE(SUM(e-s),0)::float8 FROM usage),'count',(SELECT COUNT(*)::int FROM usage),'gap_count',(SELECT COUNT(*)::int FROM clipped WHERE kind='gap')),
     'apps',COALESCE((SELECT json_agg(a ORDER BY duration_ms DESC,package_name) FROM apps a),'[]'::json),
     'daily',COALESCE((SELECT json_agg(d ORDER BY day) FROM days d),'[]'::json),
     'records',COALESCE((SELECT json_agg(r ORDER BY start_ms DESC,id) FROM records r),'[]'::json),
     'total',(SELECT COUNT(*)::int FROM clipped)) AS data`,[res.locals.userId,from,to,pkg,(page-1)*50]);
   const data=result.rows[0].data;
   if(data.overview.last_received_at) data.overview.last_received_at=new Date(data.overview.last_received_at).toISOString();
   res.json({...data,page,page_size:50});
  }catch(error){next(error);}
 });return router;
}
