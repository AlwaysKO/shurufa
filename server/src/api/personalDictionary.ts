import { Router, type Request, type Response, type NextFunction } from 'express';
import type pg from 'pg';
import { createHash } from 'node:crypto';

const hash = (value: string) => createHash('sha256').update(value).digest('hex');
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const chinese = (v: unknown): v is string => typeof v === 'string' && /^[\u4e00-\u9fff]{1,30}$/.test(v);
const safeText = (v: unknown): v is string => chinese(v) && !/密码|验证码|校验码|动态口令|一次性口令/.test(v);
const integer = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0;
type Status = 'enabled' | 'disabled' | 'deleted';
type Entry = {kind: 'word'|'choice'; text: string; code: string; pinyin: string; source: string; count: number; weight: number; last_used: number};
type Db = pg.PoolClient;
class HttpError extends Error { constructor(readonly status: number, message: string) { super(message); } }
function entry(value: unknown): Entry {
  if (!value || typeof value !== 'object') throw new HttpError(400,'invalid entry');
  const v = value as Entry;
  if (!safeText(v.text) || !integer(v.count) || !integer(v.last_used) || typeof v.weight !== 'number' || !Number.isFinite(v.weight) || v.weight < 0 || v.weight > v.count || !['selection','system_dictionary'].includes(v.source)) throw new HttpError(400,'invalid entry');
  if (v.kind === 'choice') {
    if (v.source !== 'selection' || v.pinyin !== '' || typeof v.code !== 'string' || !/^(?:[a-z]{2,30}|[2-9]{3,30})$/.test(v.code) || v.count < 1 || v.last_used < 1) throw new HttpError(400,'invalid choice');
  } else if (v.kind === 'word') {
    if (v.code !== '' || typeof v.pinyin !== 'string' || v.pinyin.length > 210 || (v.pinyin !== '' && (!/^[a-z]+(?: [a-z]+)*$/.test(v.pinyin) || v.pinyin.split(' ').length !== v.text.length)) || v.count !== 0 || v.weight !== 0 || v.last_used !== 0) throw new HttpError(400,'invalid word');
  } else throw new HttpError(400,'invalid kind');
  return {kind:v.kind,text:v.text,code:v.code,pinyin:v.pinyin,source:v.source,count:v.count,weight:v.weight,last_used:v.last_used};
}
const key = (e: Entry) => hash(JSON.stringify([e.kind,e.text,e.code,e.pinyin,e.source]));
// 各端绑定、决策与快照确认共用短事务锁，避免确认旧快照或绑定过程穿插上报。
function transaction(pool: pg.Pool, fn: (db: Db, req: Request, res: Response) => Promise<unknown>) {
  return async (req: Request, res: Response, next: NextFunction) => {
    let db: Db | undefined;
    try {
      db = await pool.connect(); await db.query('BEGIN');
      await db.query('SELECT id FROM dictionary_lock WHERE id=1 FOR UPDATE');
      const result = await fn(db, req, res);
      await db.query('COMMIT'); res.json(result);
    } catch (error) {
      if (db) await db.query('ROLLBACK').catch(() => {});
      if (error instanceof HttpError) res.status(error.status).json({error:error.message}); else next(error);
    } finally { db?.release(); }
  };
}
async function device(db: Db, id: string) {
  const row = (await db.query('SELECT * FROM dictionary_device WHERE device_id=$1',[id])).rows[0];
  if (!row) throw new HttpError(404,'设备尚未上报个人词库，请升级并联网启动输入法');
  return row;
}
async function authenticate(db: Db, req: Request, id: string) {
  const token = req.get('X-Dictionary-Token');
  if (!token || !/^[0-9a-f]{64}$/.test(token)) throw new HttpError(401,'dictionary credential required');
  const row = await device(db,id);
  if (row.token_hash !== hash(token)) throw new HttpError(401,'dictionary credential mismatch');
  return row;
}
async function snapshot(db: Db, group: string) {
  const devices = (await db.query('SELECT device_id FROM dictionary_device WHERE group_id=$1 ORDER BY device_id',[group])).rows.map(r => r.device_id);
  const rows = (await db.query(`SELECT e.device_id,e.entry_key,e.payload FROM dictionary_entry e
    JOIN dictionary_device d ON e.device_id=d.device_id WHERE d.group_id=$1 ORDER BY e.device_id,e.entry_key LIMIT 100001`,[group])).rows;
  if (rows.length > 100000) throw new HttpError(413,'词库过大，未下发截断数据');
  const entries = rows.map(r => ({device_id:r.device_id,...r.payload}));
  const policies = (await db.query('SELECT text,status FROM dictionary_policy WHERE group_id=$1 ORDER BY text',[group])).rows;
  const revision = hash(JSON.stringify({group,devices,entries,policies}));
  return {group_id:group,revision,entries,policies};
}
export function createMobileDictionaryRouter(pool: pg.Pool): Router {
  const r = Router();
  r.post('/register',transaction(pool,async(db,req,res) => {
    const id = res.locals.userId, token = req.get('X-Dictionary-Token');
    if (!token || !/^[0-9a-f]{64}$/.test(token)) throw new HttpError(401,'dictionary credential required');
    if (!(await db.query('SELECT id FROM device WHERE id=$1',[id])).rowCount) throw new HttpError(409,'请先注册设备');
    await db.query(`INSERT INTO dictionary_device(device_id,group_id,token_hash) VALUES($1,$1,$2) ON CONFLICT DO NOTHING`,[id,hash(token)]);
    const registered = await authenticate(db,req,id); return {ok:true,has_report:registered.last_report_at != null};
  }));
  r.post('/report',transaction(pool,async(db,req,res) => {
    const id = res.locals.userId; await authenticate(db,req,id);
    const {sequence, entries, migration_status, imported} = req.body ?? {};
    if (!integer(sequence) || sequence < 1 || !Array.isArray(entries) || entries.length > 500 || !['not_attempted','complete','permission_denied','unavailable','failed'].includes(migration_status) || !integer(imported) || imported > 50000) throw new HttpError(400,'invalid batch');
    const values = entries.map(entry);
    if (new Set(values.map(key)).size !== values.length) throw new HttpError(400,'duplicate entry in batch');
    for (const value of values) {
      const entryKey = key(value);
      const old = (await db.query('SELECT sequence,payload FROM dictionary_entry WHERE device_id=$1 AND entry_key=$2',[id,entryKey])).rows[0];
      if (old && Number(old.sequence) === sequence && JSON.stringify(entry(old.payload)) !== JSON.stringify(value)) throw new HttpError(409,'sequence conflict');
      if (old && Number(old.sequence) >= sequence) continue;
      await db.query(`INSERT INTO dictionary_entry(device_id,entry_key,sequence,payload) VALUES($1,$2,$3,$4)
        ON CONFLICT(device_id,entry_key) DO UPDATE SET sequence=EXCLUDED.sequence,payload=EXCLUDED.payload,reported_at=NOW()`,[id,entryKey,sequence,JSON.stringify(value)]);
    }
    await db.query('UPDATE dictionary_device SET last_report_at=NOW(),migration_status=$2,imported=$3 WHERE device_id=$1',[id,migration_status,imported]);
    return {ok:true};
  }));
  r.get('/',transaction(pool,async(db,req,res) => snapshot(db,(await authenticate(db,req,res.locals.userId)).group_id)));
  r.post('/ack',transaction(pool,async(db,req,res) => {
    const id = res.locals.userId, d = await authenticate(db,req,id);
    const current = await snapshot(db,d.group_id);
    if (req.body?.revision !== current.revision) throw new HttpError(409,'词库已更新，请重新同步');
    await db.query('UPDATE dictionary_device SET applied_revision=$2,applied_at=NOW() WHERE device_id=$1',[id,current.revision]);
    return {ok:true};
  }));
  return r;
}
export function createDashboardDictionaryRouter(pool: pg.Pool): Router {
  const r = Router();
  r.get('/devices',transaction(pool,async(db,_req,res) => {
    const d = await device(db,res.locals.userId), current = await snapshot(db,d.group_id);
    const rows = (await db.query(`SELECT s.device_id,s.group_id,s.last_report_at,s.applied_at,s.applied_revision,s.migration_status,s.imported,
      d.name,d.model,d.brand,d.dashboard_name FROM dictionary_device s JOIN device d ON d.id=s.device_id ORDER BY s.device_id`)).rows;
    return {group_id:d.group_id,devices:rows.map(row => ({...row,in_group:row.group_id===d.group_id,synced:row.group_id===d.group_id && row.applied_revision===current.revision}))};
  }));
  r.get('/entries',transaction(pool,async(db,req,res) => {
    const d = await device(db,res.locals.userId), data = await snapshot(db,d.group_id);
    const id = req.query.device_id;
    if (id && (typeof id !== 'string' || !uuid.test(id) || (await device(db,id)).group_id !== d.group_id)) throw new HttpError(403,'设备不属于此个人词库');
    const q = typeof req.query.q==='string' ? req.query.q.slice(0,100) : '';
    const policies = new Map(data.policies.map(p => [p.text,p.status]));
    let all = data.entries.map(e => ({...e,status:policies.get(e.text) ?? 'enabled'})).filter(e => (!id || e.device_id===id) && (!q || e.text.includes(q) || e.pinyin.includes(q)) && (!req.query.status || e.status===req.query.status));
    if (req.query.view === 'merged') {
      const grouped = new Map<string, typeof all>();
      all.forEach(e => grouped.set(e.text,[...(grouped.get(e.text) ?? []),e]));
      all = [...grouped.values()].map(values => {
        const choices=values.filter(e => e.kind==='choice');
        const at=Math.max(0,...choices.map(e => e.last_used));
        return {...values[0],kind:'merged',device_id:'',device_ids:[...new Set(values.map(e=>e.device_id))].sort(),
          pinyin:[...new Set(values.map(e=>e.pinyin).filter(Boolean))].join(' / '),
          code:[...new Set(values.map(e=>e.code).filter(Boolean))].join(' / '),source:'merged',
          count:choices.reduce((n,e)=>n+e.count,0),
          weight:choices.reduce((n,e)=>n+e.weight*Math.pow(0.5,(at-e.last_used)/(14*24*60*60*1000)),0),last_used:at};
      });
    }
    const page = Math.max(1,Math.min(100000,Math.floor(Number(req.query.page)||1))), size=50;
    return {total:all.length,page,page_size:size,entries:all.slice((page-1)*size,page*size)};
  }));
  r.post('/bind',transaction(pool,async(db,req,res) => {
    const id=req.body?.device_id;
    if (typeof id !== 'string' || !uuid.test(id)) throw new HttpError(400,'请选择目标设备');
    const current=await device(db,res.locals.userId), incoming=await device(db,id);
    if (current.group_id===incoming.group_id) return {ok:true};
    const policies=(await db.query('SELECT text,status FROM dictionary_policy WHERE group_id=$1',[incoming.group_id])).rows;
    const severity: Record<Status,number>={enabled:0,disabled:1,deleted:2};
    for (const p of policies) {
      const old=(await db.query('SELECT status FROM dictionary_policy WHERE group_id=$1 AND text=$2',[current.group_id,p.text])).rows[0];
      if (!old || severity[p.status as Status]>severity[old.status as Status]) await db.query(`INSERT INTO dictionary_policy(group_id,text,status) VALUES($1,$2,$3)
        ON CONFLICT(group_id,text) DO UPDATE SET status=EXCLUDED.status,updated_at=NOW()`,[current.group_id,p.text,p.status]);
    }
    await db.query('UPDATE dictionary_device SET group_id=$1,applied_revision=NULL WHERE group_id=$2',[current.group_id,incoming.group_id]);
    await snapshot(db,current.group_id); // 超限时整个绑定回滚，不建立无法恢复的分组。
    return {ok:true};
  }));
  r.post('/decisions',transaction(pool,async(db,req,res) => {
    const d=await device(db,res.locals.userId), {texts,status}=req.body ?? {};
    if (!Array.isArray(texts) || !texts.length || texts.length>500 || !texts.every(chinese) || !['enabled','disabled','deleted'].includes(status)) throw new HttpError(400,'invalid decision');
    for (const text of new Set(texts)) await db.query(`INSERT INTO dictionary_policy(group_id,text,status) VALUES($1,$2,$3)
      ON CONFLICT(group_id,text) DO UPDATE SET status=EXCLUDED.status,updated_at=NOW()`,[d.group_id,text,status]);
    return {ok:true};
  }));
  return r;
}
