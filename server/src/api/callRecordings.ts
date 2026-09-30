import express, { Router, type Request, type Response, type NextFunction } from 'express';
import { createHash, timingSafeEqual } from 'node:crypto';
import type pg from 'pg';
import { savingFlags } from '../lib/deviceSaving.js';
import { encryptAudio, decryptAudio, loadAudioKey } from '../calls/storage.js';

const MAX_BYTES = 64 * 1024 * 1024, MAX_DURATION = 2 * 60 * 60 * 1000;
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const hash = (value: Buffer|string) => createHash('sha256').update(value).digest('hex');
class CallError extends Error { constructor(readonly status: number, message: string) { super(message); } }
const fail = (status: number, message: string): never => { throw new CallError(status, message); };
function handler(fn:(req:Request,res:Response)=>Promise<void>) {
  return (req:Request,res:Response,next:NextFunction) => { void fn(req,res).catch(next); };
}
function errors(error:Error & {type?:string}, _req:Request,res:Response,_next:NextFunction) {
  // 不把请求头、录音元数据、SQL 或凭据送到通用日志/响应。
  const status = error instanceof CallError ? error.status : error.type === 'entity.too.large' ? 413 : 503;
  res.status(status).json({error:error instanceof CallError ? error.message : 'call_recording_unavailable'});
}
function idOf(req:Request):string {
  const id=String(req.params.id);
  if (!uuid.test(id)) fail(400,'invalid_record_id');
  return id;
}
function metadataOf(req:Request):Record<string,unknown> {
  const encoded=req.get('X-Call-Metadata');
  if (!encoded || encoded.length > 8192 || !/^[A-Za-z0-9+/]+={0,2}$/.test(encoded)) fail(400,'invalid_metadata');
  let m:Record<string,unknown>;
  try { m=JSON.parse(Buffer.from(encoded!,'base64').toString('utf8')); } catch { return fail(400,'invalid_metadata'); }
  if (!m || typeof m !== 'object' || Array.isArray(m)) fail(400,'invalid_metadata');
  const integer=(v:unknown):v is number=>typeof v==='number' && Number.isSafeInteger(v) && v>=0;
  if (integer(m.byte_size) && m.byte_size>MAX_BYTES) fail(413,'audio_too_large');
  if (!integer(m.byte_size) || m.byte_size<1 || typeof m.sha256!=='string' || !/^[a-f0-9]{64}$/.test(m.sha256) || m.mime_type!=='audio/mp4') fail(400,'invalid_audio_metadata');
  if (!['phone','wechat'].includes(String(m.platform)) || !['voice','video'].includes(String(m.call_type)) || (m.platform==='phone' && m.call_type!=='voice')) fail(400,'invalid_platform');
  if (!integer(m.recording_started_at) || !integer(m.recording_ended_at) || m.recording_ended_at<m.recording_started_at || m.recording_ended_at>Date.now()+300000 ||
      !integer(m.audio_duration_ms) || m.audio_duration_ms>MAX_DURATION || m.audio_duration_ms>m.recording_ended_at-m.recording_started_at+1000) fail(400,'invalid_recording_time');
  if (!['ended','interrupted','restricted'].includes(String(m.recording_status)) || !['unverified','suspected_silent','user_confirmed'].includes(String(m.quality_status))) fail(400,'invalid_status');
  const bounded=(v:unknown,n:number)=>v===null || (typeof v==='string' && v.length<=n && !/[\x00-\x1f]/.test(v));
  if (!bounded(m.counterpart_display,200) || !['unknown','user','trusted'].includes(String(m.counterpart_source)) || (m.counterpart_source==='unknown' && m.counterpart_display!==null) || !bounded(m.failure_reason,200)) fail(400,'invalid_text');
  for(const k of ['call_started_at','call_ended_at','call_duration_ms']) if(m[k]!==null && !integer(m[k])) fail(400,'invalid_call_time');
  if(typeof m.call_duration_estimated!=='boolean') fail(400,'invalid_call_time');
  if(m.call_started_at!==null && m.call_ended_at!==null && Number(m.call_ended_at)<Number(m.call_started_at)) fail(400,'invalid_call_time');
  if(m.call_duration_ms!==null && (m.call_started_at===null || m.call_ended_at===null || Number(m.call_duration_ms)>Number(m.call_ended_at)-Number(m.call_started_at))) fail(400,'invalid_call_duration');
  if(m.consent_version!=='call-audio-v1' || typeof m.destination!=='string') fail(400,'invalid_consent');
  try { const u=new URL(String(m.destination));if(u.protocol!=='https:' || u.username || u.password || u.search || u.hash || u.pathname!=='/') fail(400,'invalid_destination'); } catch { fail(400,'invalid_destination'); }
  const keys=['platform','call_type','counterpart_display','counterpart_source','call_started_at','call_ended_at','call_duration_ms','call_duration_estimated','recording_started_at','recording_ended_at','audio_duration_ms','recording_status','quality_status','failure_reason','mime_type','byte_size','sha256','consent_version','destination'];
  return Object.fromEntries(keys.map(k=>[k,m[k]]));
}
function receipt(row:pg.QueryResultRow) {
  if(row.deleted_at) fail(410,'record_deleted');
  return {stored:true,record_id:row.record_id,device_id:row.device_id,byte_size:row.byte_size,sha256:row.sha256,stored_at:new Date(row.stored_at).toISOString()};
}
const receiptColumns='device_id,record_id,byte_size,sha256,stored_at,deleted_at,metadata_sha256';
export function createMobileCallRecordingsRouter(pool:pg.Pool):Router {
  const r=Router();
  r.use((req,res,next)=>{ void (async()=>{
    res.set('Cache-Control','no-store');
    const token=req.get('X-Dictionary-Token');
    if(!token || !/^[a-f0-9]{64}$/.test(token)) fail(401,'device_credential_required');
    const row=(await pool.query('SELECT token_hash FROM dictionary_device WHERE device_id=$1',[res.locals.userId])).rows[0];
    if(!row || typeof row.token_hash!=='string' || row.token_hash.length!==64 || !timingSafeEqual(Buffer.from(hash(token!)),Buffer.from(row.token_hash))) fail(401,'device_credential_mismatch');
    next();
  })().catch(next); });
  r.get('/:id/receipt',handler(async(req,res)=>{
    const row=(await pool.query(`SELECT ${receiptColumns} FROM call_recording WHERE device_id=$1 AND record_id=$2`,[res.locals.userId,idOf(req)])).rows[0];
    if(!row) fail(404,'record_not_found');res.json(receipt(row));
  }));
  r.put('/:id', (req,res,next)=> {
    try { idOf(req);res.locals.callMetadata=metadataOf(req);next(); } catch(e) {next(e);}
  }, express.raw({type:'application/octet-stream',limit:MAX_BYTES}),handler(async(req,res)=>{
    const id=idOf(req),device=String(res.locals.userId).toLowerCase(),m=res.locals.callMetadata as Record<string,unknown>;
    if(!Buffer.isBuffer(req.body) || req.body.length!==m.byte_size || hash(req.body)!==m.sha256 || req.body.length<12 || req.body.toString('ascii',4,8)!=='ftyp') fail(400,'audio_integrity_mismatch');
    if((await savingFlags(pool,[device])).get(device)===false) fail(409,'saving_disabled');
    let key:Buffer;try {key=await loadAudioKey();} catch {return fail(503,'audio_key_unavailable');}
    const fingerprint=hash(JSON.stringify(m));
    const encrypted=encryptAudio(req.body,key,`${device}:${id}`);
    const db=await pool.connect();
    try {
      await db.query('BEGIN');await db.query('SET LOCAL synchronous_commit = on');
      await db.query(`INSERT INTO call_recording(device_id,record_id,metadata,metadata_sha256,sha256,byte_size,recorded_at,platform,recording_status,audio_ciphertext)
        VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) ON CONFLICT(device_id,record_id) DO NOTHING`,
        [device,id,JSON.stringify(m),fingerprint,m.sha256,m.byte_size,new Date(Number(m.recording_started_at)),m.platform,m.recording_status,encrypted]);
      const row=(await db.query(`SELECT ${receiptColumns} FROM call_recording WHERE device_id=$1 AND record_id=$2 FOR UPDATE`,[device,id])).rows[0];
      const result=receipt(row);
      if(row.sha256!==m.sha256 || row.byte_size!==m.byte_size || row.metadata_sha256!==fingerprint) fail(409,'record_conflict');
      await db.query('COMMIT');res.json(result);
    } catch(e) {await db.query('ROLLBACK').catch(()=>{});throw e;} finally {db.release();}
  }));
  r.use(errors);return r;
}
function dateBoundary(v:unknown,end=false):Date|null {
  if(v===undefined)return null;
  if(typeof v!=='string' || !/^\d{4}-\d{2}-\d{2}$/.test(v)) return fail(400,'invalid_day');
  const d=new Date(v+'T00:00:00.000Z');
  if(!Number.isFinite(d.getTime()) || d.toISOString().slice(0,10)!==v) fail(400,'invalid_day');
  // 后台日期统一北京时间，结束日包含整天。
  return new Date(d.getTime()-8*3600000+(end?86400000:0));
}
export function createDashboardCallRecordingsRouter(pool:pg.Pool):Router {
  const r=Router();r.use((_req,res,next)=>{res.set('Cache-Control','no-store');next();});
  r.get('/',handler(async(req,res)=>{
    const page=Number(req.query.page??1);
    if(!Number.isSafeInteger(page)||page<1||page>100000) fail(400,'invalid_page');
    const params:unknown[]=[res.locals.userId], filters=['device_id=$1','deleted_at IS NULL'];
    const add=(sql:string,v:unknown)=>{params.push(v);filters.push(sql+'$'+params.length);};
    for(const [key,allowed] of [['platform',['phone','wechat']],['recording_status',['ended','interrupted','restricted']]] as const){
      const v=req.query[key];if(v!==undefined){if(typeof v!=='string'||!(allowed as readonly string[]).includes(v))fail(400,'invalid_filter');add(key+'=',v);}
    }
    const from=dateBoundary(req.query.from),to=dateBoundary(req.query.to,true);
    if(from&&to&&from>=to)fail(400,'invalid_range');
    if(from)add('recorded_at>=',from);if(to)add('recorded_at<',to);
    const where=filters.join(' AND ');
    const total=Number((await pool.query(`SELECT COUNT(*) AS n FROM call_recording WHERE ${where}`,params)).rows[0].n);
    const rows=(await pool.query(`SELECT record_id,metadata,stored_at FROM call_recording WHERE ${where} ORDER BY recorded_at DESC,record_id LIMIT 50 OFFSET ${(page-1)*50}`,params)).rows;
    res.json({total,page,records:rows.map(row=>({...row.metadata,record_id:row.record_id,stored_at:row.stored_at,upload_status:'saved'}))});
  }));
  r.get('/:id/audio',handler(async(req,res)=>{
    const device=String(res.locals.userId).toLowerCase(),id=idOf(req);
    const row=(await pool.query('SELECT audio_ciphertext,sha256,byte_size FROM call_recording WHERE device_id=$1 AND record_id=$2 AND deleted_at IS NULL',[device,id])).rows[0];
    if(!row)fail(404,'record_not_found');
    const audio=decryptAudio(row.audio_ciphertext,await loadAudioKey(),`${device}:${id}`);
    if(audio.length!==row.byte_size||hash(audio)!==row.sha256)fail(503,'stored_audio_invalid');
    res.set({'Content-Type':'audio/mp4','X-Content-Type-Options':'nosniff','Content-Disposition':'inline; filename="call.m4a"'});res.send(audio);
  }));
  r.delete('/:id',handler(async(req,res)=>{
    const result=await pool.query("UPDATE call_recording SET audio_ciphertext=NULL,metadata='{}'::jsonb,deleted_at=COALESCE(deleted_at,NOW()) WHERE device_id=$1 AND record_id=$2 RETURNING record_id",[res.locals.userId,idOf(req)]);
    if(!result.rowCount)fail(404,'record_not_found');res.sendStatus(204);
  }));
  r.use(errors);return r;
}
