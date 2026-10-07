import { lockStatisticsRetention, type StatisticsRetentionDataset } from '../lib/statisticsRetentionLock.js';
import { createCompletionRetentionRouter } from './completionRetention.js';
import { randomUUID } from 'node:crypto';
import { Router } from 'express';
import type pg from 'pg';
import { createActivityRetentionRouter } from './activityRetention.js';

const TTL = 15 * 60_000, LIMIT = 50_000, BATCH = 200;
type Definition = {
  table: string; owner: string; key: string; keyType: 'uuid' | 'bigint' | 'text';
  time: string; milliseconds?: boolean; signature?: string; condition?: string;
  files?: boolean; tombstone?: boolean; receipt?: StatisticsRetentionDataset; receiptVersion?: string; filters: Record<string, readonly string[] | null>;
};
// Identifiers/SQL are server-owned. Client input is accepted only as bound filter values.
const definitions: Record<string, Definition> = {
  locations: { table:'location_track', owner:'user_id', key:'id', keyType:'bigint', time:'GREATEST(occurred_at,last_seen_at)', filters:{device_id:null} },
  'app-usage': { table:'app_usage_segment', owner:'user_id', key:'id', keyType:'uuid', time:'end_ms', milliseconds:true, receipt:'app-usage', filters:{package_name:null} },
  navigation: { table:'navigation_record', owner:'user_id', key:'id', keyType:'uuid', time:'started_at', files:true, receipt:'navigation', receiptVersion:'payload_sha256', filters:{platform:['amap','baidu']},
    signature:'md5(ROW(platform,origin,destination,started_at,overview_at,sha256,payload_sha256,mime_type,received_at)::text)' },
  'call-logs': { table:'phone_call_log', owner:'device_id', key:'source_id', keyType:'text', time:'date + duration_seconds::bigint * 1000', milliseconds:true, receipt:'call-logs', receiptVersion:'date::text', filters:{} },
  'call-recordings': { table:'call_recording', owner:'device_id', key:'record_id', keyType:'uuid',
    time:"GREATEST(recorded_at,CASE WHEN jsonb_typeof(metadata->'recording_ended_at')='number' THEN to_timestamp((metadata->>'recording_ended_at')::double precision/1000) END)",
    files:true, tombstone:true, condition:'deleted_at IS NULL', filters:{platform:['phone','wechat'],recording_status:['ended','interrupted','restricted']},
    signature:'md5(ROW(metadata,metadata_sha256,sha256,byte_size,recorded_at,platform,recording_status,stored_at,deleted_at)::text)' },
};
type Target = { id: string; at: Date; signature: string };
type Job = { user: string; kind: string; targets: Target[]; processed: number; deleted: number; skipped: number; expires: number; busy: boolean };
const progress = (job: Job) => ({ processed:job.processed, total:job.targets.length, deleted_records:job.deleted,
  skipped_records:job.skipped, done:job.processed===job.targets.length, files_pending:false });
function filtersValid(value: unknown, def: Definition): value is Record<string,string> {
  return !!value && typeof value==='object' && !Array.isArray(value) && Object.entries(value).every(([key,val])=>
    Object.hasOwn(def.filters,key) && typeof val==='string' && !!val.trim() && val.length<=255 &&
    (key!=='device_id' || /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(val)) &&
    (def.filters[key]===null || def.filters[key].includes(val)));
}
function projection(def: Definition) {
  return `t.${def.key}::text AS id,${def.milliseconds ? `to_timestamp((${def.time})::double precision/1000)` : def.time} AS at,
    ${def.signature ?? 'md5(row_to_json(t)::text)'} AS signature`;
}

export function createStatisticsRetentionRouter(pool: pg.Pool): Router {
  const router=Router();
  router.use('/input',createActivityRetentionRouter(pool,'input'));
  router.use('/clipboard',createActivityRetentionRouter(pool,'clipboard'));
  router.use('/completions',createCompletionRetentionRouter(pool));
  const jobs=new Map<string,Job>();
  const expire=()=>{for(const [token,job] of jobs)if(!job.busy&&job.expires<Date.now())jobs.delete(token);};
  router.post('/:kind/preview',async(req,res,next)=>{
    const kind=req.params.kind, def=Object.hasOwn(definitions,kind)?definitions[kind]:undefined, body=req.body;
    if(!def || ![1,7,30].includes(body?.days) || !filtersValid(body?.filters,def)) {res.status(400).json({error:'清理类别、保留天数或筛选条件无效'});return;}
    expire();if(jobs.size>=20){res.status(429).json({error:'清理预览较多，请稍后重试'});return;}
    const cutoff=new Date(Date.now()-body.days*86_400_000),params:unknown[]=[res.locals.userId,def.milliseconds?cutoff.getTime():cutoff];
    const where=[`t.${def.owner}=$1`,`(${def.time})<$2`,def.condition??'TRUE'];
    for(const [key,value] of Object.entries(body.filters)){params.push(value);where.push(`${key}=$${params.length}`);}
    try {
      const result=await pool.query<Target>(`SELECT ${projection(def)} FROM ${def.table} t WHERE ${where.join(' AND ')} ORDER BY at,t.${def.key} LIMIT ${LIMIT+1}`,params);
      if(result.rows.length>LIMIT){res.status(400).json({error:'待清理记录超过5万条，请缩小筛选范围'});return;}
      const token=randomUUID();
      for(const [key,job] of jobs)if(job.user===res.locals.userId&&!job.busy&&!job.processed)jobs.delete(key);
      if(result.rows.length)jobs.set(token,{user:res.locals.userId,kind,targets:result.rows,processed:0,deleted:0,skipped:0,expires:Date.now()+TTL,busy:false});
      res.json({token,cutoff:cutoff.toISOString(),total_records:result.rows.length,total_files:def.files?result.rows.length:0,
        first_at:result.rows[0]?.at??null,last_at:result.rows.at(-1)?.at??null});
    }catch(error){next(error);}
  });
  router.post('/:kind/batch',async(req,res,next)=>{
    const body=req.body;
    if(body?.confirm!=='DELETE'||typeof body?.token!=='string'||!Number.isSafeInteger(body?.offset)||body.offset<0){res.status(400).json({error:'清理确认参数无效'});return;}
    expire();const job=jobs.get(body.token);
    if(!job||job.user!==res.locals.userId||job.kind!==req.params.kind){res.status(410).json({error:'清理预览已失效，请重新预览'});return;}
    if(job.busy||body.offset>job.processed){res.status(409).json({error:'清理批次正在处理或进度不一致'});return;}
    if(body.offset<job.processed||job.processed===job.targets.length){res.json(progress(job));return;}
    job.busy=true;const targets=job.targets.slice(job.processed,job.processed+BATCH),def=definitions[job.kind];
    try {
      const db=await pool.connect();let deleted=0;
      try {
        await db.query('BEGIN');await db.query("SET LOCAL lock_timeout='5s'");await db.query("SET LOCAL statement_timeout='20s'");
        if(def.receipt)await lockStatisticsRetention(db,def.receipt,job.user);
        const scope=`t.${def.owner}=$1 AND t.${def.key}=ANY($2::${def.keyType}[])`;
        const current=await db.query<Target>(`SELECT ${projection(def)} FROM ${def.table} t WHERE ${scope} AND ${def.condition??'TRUE'} FOR UPDATE OF t`,[job.user,targets.map(row=>row.id)]);
        const expected=new Map(targets.map(row=>[row.id,row.signature]));
        const ids=current.rows.filter(row=>expected.get(row.id)===row.signature).map(row=>row.id);
        if(def.receipt)await db.query(`INSERT INTO retention_deleted_record(user_id,dataset,record_key,source_version)
          SELECT t.${def.owner},$3,t.${def.key}::text,${def.receiptVersion ?? "''"} FROM ${def.table} t WHERE ${scope}
          ON CONFLICT DO NOTHING`,[job.user,ids,def.receipt]);
        const mutation=def.tombstone ? `UPDATE ${def.table} t SET audio_ciphertext=NULL,metadata='{}'::jsonb,deleted_at=NOW() WHERE ${scope}`
          : `DELETE FROM ${def.table} t WHERE ${scope}`;
        deleted=(await db.query(mutation,[job.user,ids])).rowCount??0;
        await db.query('COMMIT');
      }catch(error){await db.query('ROLLBACK').catch(()=>{});throw error;}finally{db.release();}
      job.processed+=targets.length;job.deleted+=deleted;job.skipped+=targets.length-deleted;job.expires=Date.now()+TTL;
      res.json(progress(job));
    }catch(error){next(error);}finally{job.busy=false;}
  });
  return router;
}
