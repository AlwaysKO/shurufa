import { Router } from 'express';
import type pg from 'pg';
const statuses={page:['matched','rejected','empty_tree'],screenshot:['ready','failed','cancelled'],persist:['inserted','duplicate','failed'],upload:['acknowledged','failed','waiting'],browse_capture:['saved','duplicate','interval_limited','budget_limited','queue_full','page_uncovered','failed','cancelled'],browse_upload:['acknowledged','failed','waiting_wifi','discarded']} as const;
const stages=Object.keys(statuses) as (keyof typeof statuses)[],platforms=['wechat','douyin'];
const key=(id:string,p:string,s:string)=>`chat_capture_diagnostic_v1:${id.toLowerCase()}:${p}:${s}`;
const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const integer=(v:unknown)=>Number.isSafeInteger(v)&&Number(v)>=0;
const fields=['device_id','platform','app_version_code','app_version_name','config_revision','stage','status','error_code','observed_at'].sort().join(',');
function valid(b:any):boolean{return b!==null&&typeof b==='object'&&!Array.isArray(b)&&Object.keys(b).sort().join(',')===fields&&typeof b.device_id==='string'&&uuid.test(b.device_id)&&platforms.includes(b.platform)&&integer(b.app_version_code)&&typeof b.app_version_name==='string'&&b.app_version_name.trim().length>0&&b.app_version_name.length<=80&&!/[\x00-\x1f\x7f]/.test(b.app_version_name)&&integer(b.config_revision)&&stages.includes(b.stage)&&(statuses[b.stage as keyof typeof statuses] as readonly string[]).includes(b.status)&&(b.error_code===null||Number.isSafeInteger(b.error_code))&&integer(b.observed_at)&&b.observed_at>0;}
async function exists(pool:pg.Pool,id:string){return !!(await pool.query('SELECT id FROM device WHERE id=$1',[id])).rowCount;}
export function createMobileDiagnosticsRouter(pool:pg.Pool):Router{
 const router=Router();router.post('/chat/diagnostics',async(req,res,next)=>{
  const b=req.body,id=String(res.locals.userId).toLowerCase();if(!valid(b)||b.device_id.toLowerCase()!==id){res.status(400).json({error:'invalid_diagnostic'});return;}
  try{if(!await exists(pool,id)){res.status(404).json({error:'device_not_found'});return;}
   const k=key(id,b.platform,b.stage);
   // Compare the exact stored value: concurrent writers cannot regress device observation time.
   for(let attempt=0;attempt<5;attempt++){
    const old=(await pool.query<{value:string}>('SELECT value FROM runtime_setting WHERE key=$1',[k])).rows[0]?.value;
    if(old!==undefined&&JSON.parse(old).observed_at>=b.observed_at){res.json({ok:true,saved:false});return;}
    const value=JSON.stringify({...b,device_id:id,received_at:Date.now()});
    const saved=old===undefined?await pool.query('INSERT INTO runtime_setting(key,value,updated_at) VALUES($1,$2,NOW()) ON CONFLICT(key) DO NOTHING RETURNING key',[k,value]):await pool.query('UPDATE runtime_setting SET value=$2,updated_at=NOW() WHERE key=$1 AND value=$3 RETURNING key',[k,value,old]);
    if(saved.rowCount){res.json({ok:true,saved:true});return;}
   }res.status(409).json({error:'diagnostic_conflict'});
  }catch(error){next(error);}
 });return router;
}
export function createDashboardDiagnosticsRouter(pool:pg.Pool):Router{
 const router=Router();router.get('/chat-capture-diagnostics',async(req,res,next)=>{
  const id=req.query.device_id;if(typeof id!=='string'||!uuid.test(id)){res.status(400).json({error:'invalid_device_id'});return;}
  if(id.toLowerCase()!==String(res.locals.userId).toLowerCase()){res.status(403).json({error:'device_id_mismatch'});return;}
  try{if(!await exists(pool,id)){res.status(404).json({error:'device_not_found'});return;}
   const result:Record<string,Record<string,unknown>>={};for(const p of platforms){result[p]={};for(const s of stages){const raw=(await pool.query<{value:string}>('SELECT value FROM runtime_setting WHERE key=$1',[key(id,p,s)])).rows[0]?.value;result[p][s]=raw===undefined?null:JSON.parse(raw);}}
   res.set('Cache-Control','no-store').json({device_id:id.toLowerCase(),platforms:result});
  }catch(error){next(error);}
 });return router;
}
