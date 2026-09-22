import { Router, type Request, type Response, type NextFunction } from 'express';
import type pg from 'pg';
import { polyphonic } from 'pinyin-pro';
import { createHash } from 'node:crypto';

const hash = (value: string) => createHash('sha256').update(value).digest('hex');
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const chinese = (v: unknown): v is string => typeof v === 'string' && /^[\u4e00-\u9fff]{1,30}$/.test(v);
const safeText = (v: unknown): v is string => chinese(v) && !/密码|验证码|校验码|动态口令|一次性口令/.test(v);
const integer = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0;
type Status = 'enabled' | 'disabled' | 'deleted';
type Entry = {kind: 'word'|'choice'; text: string; code: string; pinyin: string; source: string; count: number; weight: number; last_used: number};
type Db = pg.PoolClient;
class HttpError extends Error { constructor(readonly status: number, message: string, readonly code?: string) { super(message); } }
function entry(value: unknown): Entry {
  if (!value || typeof value !== 'object') throw new HttpError(400,'invalid entry');
  const v = value as Entry;
  if (!safeText(v.text) || !integer(v.count) || !integer(v.last_used) || typeof v.weight !== 'number' || !Number.isFinite(v.weight) || v.weight < 0 || v.weight > v.count || !['selection','system_dictionary'].includes(v.source)) throw new HttpError(400,'invalid entry');
  if (v.kind === 'choice') {
    if (v.source !== 'selection' || v.pinyin !== '' || typeof v.code !== 'string' || !/^(?:[a-z]{2,30}|[2-9]{1,30})$/.test(v.code) || v.count < 1 || v.last_used < 1) throw new HttpError(400,'invalid choice');
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
      if (error instanceof HttpError) res.status(error.status).json({error:error.message,...(error.code ? {code:error.code} : {})}); else next(error);
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
async function snapshot(db: Db, group: string, shortCodes = true) {
  const devices = (await db.query('SELECT device_id FROM dictionary_device WHERE group_id=$1 ORDER BY device_id',[group])).rows.map(r => r.device_id);
  const rows = (await db.query(`SELECT e.device_id,e.entry_key,e.sequence,e.payload FROM dictionary_entry e
    JOIN dictionary_device d ON e.device_id=d.device_id WHERE d.group_id=$1 ORDER BY e.device_id,e.entry_key LIMIT 100001`,[group])).rows;
  if (rows.length > 100000) throw new HttpError(413,'词库过大，未下发截断数据');
  const entries = rows.map(r => ({device_id:r.device_id,version:Number(r.sequence),...r.payload}))
    .filter(e => shortCodes || e.kind !== 'choice' || !/^[2-9]{1,2}$/.test(e.code));
  const policies = (await db.query('SELECT text,status FROM dictionary_policy WHERE group_id=$1 ORDER BY text',[group])).rows;
  const revision = hash(JSON.stringify({group,devices,entries,policies}));
  return {group_id:group,revision,entries,policies};
}
// 校验每个汉字的真实读音，接受多音字及 ü/v，内部统一为无声调空格拼音。
function normalizedPinyin(text: unknown, value: unknown): string | null {
  if (!safeText(text) || typeof value !== 'string' || value.length > 210) return null;
  const normalized=value.trim().toLowerCase().replaceAll('ü','v').replace(/\s+/g,' ');
  if (!/^[a-z]+(?: [a-z]+)*$/.test(normalized)) return null;
  const syllables=normalized.split(' ');
  const readings=polyphonic(text,{type:'array',toneType:'none'});
  if (syllables.length!==text.length || !readings.every((values,i)=>values.some(v=>v.replaceAll('ü','v')===syllables[i]))) return null;
  return normalized;
}
async function dashboardEntries(db: Db, group: string, filter: {device_id?:unknown,q?:unknown,status?:unknown}) {
  const data=await snapshot(db,group);
  const manual=(await db.query('SELECT text,pinyin FROM dictionary_dashboard_word WHERE group_id=$1 ORDER BY text,pinyin LIMIT 100001',[group])).rows;
  if(data.entries.length+manual.length>100000) throw new HttpError(413,'词库过大，未下发截断数据');
  const id=filter.device_id;
  if(id && (typeof id!=='string' || !uuid.test(id) || (await device(db,id)).group_id!==group)) throw new HttpError(403,'设备不属于此个人词库');
  if(filter.status && !['enabled','disabled','deleted'].includes(String(filter.status))) throw new HttpError(400,'invalid status');
  const q=typeof filter.q==='string' ? filter.q.slice(0,100) : '';
  const policies=new Map(data.policies.map(p=>[p.text,p.status]));
  return [...data.entries,...manual.map(v=>({...v,device_id:'',kind:'word',source:'dashboard',code:'',count:0,weight:0,last_used:0}))]
    .map(e=>({...e,status:policies.get(e.text) ?? 'enabled'}))
    .filter(e=>(!id || e.device_id===id) && (!q || e.text.includes(q) || e.pinyin.includes(q)) && (!filter.status || e.status===filter.status));
}
export function createMobileDictionaryRouter(pool: pg.Pool): Router {
  const r = Router();
  r.post('/register',transaction(pool,async(db,req,res) => {
    const id = res.locals.userId, token = req.get('X-Dictionary-Token');
    const restores=req.body?.restore_enabled ?? true;
    const additions=req.body?.additions_supported ?? false;
    const habits=req.body?.habits_supported ?? false;
    const shortCodes=req.body?.short_codes_supported ?? false;
    if(typeof shortCodes!=='boolean') throw new HttpError(400,'invalid short code capability');
    if(typeof habits!=='boolean') throw new HttpError(400,'invalid habits capability');
    if(typeof additions!=='boolean') throw new HttpError(400,'invalid additions capability');
    if(typeof restores!=='boolean') throw new HttpError(400,'invalid restore mode');
    if (!token || !/^[0-9a-f]{64}$/.test(token)) throw new HttpError(401,'dictionary credential required');
    if (!(await db.query('SELECT id FROM device WHERE id=$1',[id])).rowCount) throw new HttpError(409,'请先注册设备');
    await db.query(`INSERT INTO dictionary_device(device_id,group_id,token_hash) VALUES($1,$1,$2) ON CONFLICT DO NOTHING`,[id,hash(token)]);
    const registered = await authenticate(db,req,id);
    // 主控角色变化后必须重新应用并确认，不能沿用切换前的确认版本。
    if (registered.restore_enabled !== restores || registered.short_codes_supported !== shortCodes) {
      await db.query('UPDATE dictionary_device SET applied_revision=NULL WHERE device_id=$1',[id]);
    }
    await db.query('UPDATE dictionary_device SET restore_enabled=$2 WHERE device_id=$1',[id,restores]);
    await db.query('UPDATE dictionary_device SET additions_supported=$2 WHERE device_id=$1',[id,additions]);
    await db.query('UPDATE dictionary_device SET habits_supported=$2 WHERE device_id=$1',[id,habits]);
    await db.query('UPDATE dictionary_device SET short_codes_supported=$2 WHERE device_id=$1',[id,shortCodes]);
    return {ok:true,has_report:registered.last_report_at != null,additions_supported:true,habits_supported:true,short_codes_supported:true};
  }));
  r.get('/additions',transaction(pool,async(db,req,res)=>{
    const id=res.locals.userId,d=await authenticate(db,req,id);
    const raw=req.query.after ?? '0';
    if(typeof raw!=='string' || !/^\d+$/.test(raw) || !integer(Number(raw))) throw new HttpError(400,'invalid cursor');
    const after=Number(raw);
    if(after>Number(d.additions_delivered)) throw new HttpError(409,'cursor was not delivered','dictionary_cursor_reset');
    const rows=(await db.query('SELECT cursor,text,pinyin,preferred FROM dictionary_addition WHERE device_id=$1 AND cursor>$2 ORDER BY cursor LIMIT 501',[id,after])).rows;
    const entries=rows.slice(0,500).map(row=>({...row,cursor:Number(row.cursor)}));
    const cursor=entries.at(-1)?.cursor ?? after;
    if(!integer(cursor)) throw new HttpError(413,'cursor capacity exceeded');
    if(entries.length) {
      await db.query('UPDATE dictionary_addition SET delivered=TRUE WHERE device_id=$1 AND cursor>$2 AND cursor<=$3',[id,after,cursor]);
      await db.query('UPDATE dictionary_device SET additions_delivered=GREATEST(additions_delivered,$2) WHERE device_id=$1',[id,cursor]);
    }
    return {entries,cursor,has_more:rows.length>500};
  }));
  r.post('/additions/ack',transaction(pool,async(db,req,res)=>{
    const id=res.locals.userId,d=await authenticate(db,req,id),cursor=req.body?.cursor;
    if(!integer(cursor)) throw new HttpError(400,'invalid cursor');
    // 重复确认不会回退；不能用其他设备更小的游标冒充已接收。
    if(cursor!==Number(d.additions_ack)) {
      const row=(await db.query('SELECT delivered FROM dictionary_addition WHERE device_id=$1 AND cursor=$2',[id,cursor])).rows[0];
      if(!row?.delivered || cursor>Number(d.additions_delivered)) throw new HttpError(409,'cursor was not delivered');
      if(cursor>Number(d.additions_ack)) await db.query('UPDATE dictionary_device SET additions_ack=$2,additions_applied_at=NOW() WHERE device_id=$1',[id,cursor]);
    }
    return {ok:true};
  }));
  r.get('/habits',transaction(pool,async(db,req,res)=>{
    const id=res.locals.userId,d=await authenticate(db,req,id);
    if(!d.habits_supported) throw new HttpError(409,'请升级输入法以接收真实习惯');
    const raw=req.query.after ?? '0';
    if(typeof raw!=='string' || !/^\d+$/.test(raw) || !integer(Number(raw))) throw new HttpError(400,'invalid cursor');
    const after=Number(raw);
    if(after>Number(d.habits_delivered)) throw new HttpError(409,'cursor was not delivered','dictionary_cursor_reset');
    const rows=(await db.query(`SELECT cursor,source_device_id,version,payload FROM dictionary_habit WHERE device_id=$1 AND cursor>$2
      ${d.short_codes_supported ? '' : "AND (code LIKE '___%' OR code>='a')"} ORDER BY cursor LIMIT 501`,[id,after])).rows;
    const entries=rows.slice(0,500).map(row=>({...row.payload,device_id:row.source_device_id,version:Number(row.version),cursor:Number(row.cursor)}));
    const cursor=entries.at(-1)?.cursor ?? after;
    if(!integer(cursor) || entries.some(e=>!integer(e.version))) throw new HttpError(413,'cursor capacity exceeded');
    if(entries.length) {
      await db.query('UPDATE dictionary_habit SET delivered=TRUE WHERE device_id=$1 AND cursor>$2 AND cursor<=$3',[id,after,cursor]);
      await db.query('UPDATE dictionary_device SET habits_delivered=GREATEST(habits_delivered,$2) WHERE device_id=$1',[id,cursor]);
    }
    return {entries,cursor,has_more:rows.length>500};
  }));
  r.post('/habits/ack',transaction(pool,async(db,req,res)=>{
    const id=res.locals.userId,d=await authenticate(db,req,id),cursor=req.body?.cursor;
    if(!d.habits_supported) throw new HttpError(409,'请升级输入法以接收真实习惯');
    if(!integer(cursor)) throw new HttpError(400,'invalid cursor');
    if(cursor!==Number(d.habits_ack)) {
      const row=(await db.query('SELECT delivered FROM dictionary_habit WHERE device_id=$1 AND cursor=$2',[id,cursor])).rows[0];
      if(!row?.delivered || cursor>Number(d.habits_delivered)) throw new HttpError(409,'cursor was not delivered');
      if(cursor>Number(d.habits_ack)) await db.query('UPDATE dictionary_device SET habits_ack=$2,habits_applied_at=NOW() WHERE device_id=$1',[id,cursor]);
    }
    return {ok:true};
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
  r.get('/',transaction(pool,async(db,req,res) => {
    const d=await authenticate(db,req,res.locals.userId);
    return snapshot(db,d.group_id,d.short_codes_supported);
  }));
  r.post('/ack',transaction(pool,async(db,req,res) => {
    const id = res.locals.userId, d = await authenticate(db,req,id);
    const current = await snapshot(db,d.group_id,d.short_codes_supported);
    if (req.body?.revision !== current.revision) throw new HttpError(409,'词库已更新，请重新同步');
    await db.query('UPDATE dictionary_device SET applied_revision=$2,applied_at=NOW() WHERE device_id=$1',[id,current.revision]);
    return {ok:true};
  }));
  return r;
}
export function createDashboardDictionaryRouter(pool: pg.Pool): Router {
  const r = Router();
  r.get('/devices',transaction(pool,async(db,_req,res) => {
    const d = await device(db,res.locals.userId), current = await snapshot(db,d.group_id), legacy = await snapshot(db,d.group_id,false);
    const rows = (await db.query(`SELECT s.device_id,s.group_id,s.last_report_at,s.applied_at,s.applied_revision,s.migration_status,s.imported,s.restore_enabled,s.short_codes_supported,s.additions_supported,s.additions_ack,s.additions_applied_at,s.habits_supported,s.habits_ack,s.habits_applied_at,
      d.name,d.model,d.brand,d.dashboard_name FROM dictionary_device s JOIN device d ON d.id=s.device_id ORDER BY s.device_id`)).rows;
    for(const row of rows) row.additions_pending=Number((await db.query('SELECT COUNT(*) AS n FROM dictionary_addition WHERE device_id=$1 AND cursor>$2',[row.device_id,row.additions_ack])).rows[0].n);
    for(const row of rows) row.habits_pending=Number((await db.query('SELECT COUNT(*) AS n FROM dictionary_habit WHERE device_id=$1 AND cursor>$2',[row.device_id,row.habits_ack])).rows[0].n);
    return {group_id:d.group_id,devices:rows.map(row => ({...row,in_group:row.group_id===d.group_id,synced:row.restore_enabled && row.group_id===d.group_id && row.applied_revision===(row.short_codes_supported ? current.revision : legacy.revision)}))};
  }));
  r.get('/entries',transaction(pool,async(db,req,res) => {
    const d = await device(db,res.locals.userId);
    let all = await dashboardEntries(db,d.group_id,req.query);
    // 仅调整后台展示顺序，手工确认的词不再埋在手机原始上报的后续页。
    all.sort((a,b)=>Number(b.source==='dashboard')-Number(a.source==='dashboard'));
    const totalWords=new Set(all.map(e=>e.text)).size;
    if (req.query.view === 'merged') {
      const grouped = new Map<string, typeof all>();
      all.forEach(e => grouped.set(e.text,[...(grouped.get(e.text) ?? []),e]));
      all = [...grouped.values()].map(values => {
        const choices=values.filter(e => e.kind==='choice');
        const at=Math.max(0,...choices.map(e => e.last_used));
        return {...values[0],kind:'merged',device_id:'',device_ids:[...new Set(values.map(e=>e.device_id).filter(Boolean))].sort(),
          pinyin:[...new Set(values.map(e=>e.pinyin).filter(Boolean))].join(' / '),
          code:[...new Set(values.map(e=>e.code).filter(Boolean))].join(' / '),source:'merged',
          sources:[...new Set(values.map(e=>e.source))].sort(),has_choices:choices.length>0,
          count:choices.reduce((n,e)=>n+e.count,0),
          weight:choices.reduce((n,e)=>n+e.weight*Math.pow(0.5,(at-e.last_used)/(14*24*60*60*1000)),0),last_used:at};
      });
    }
    const page = Math.max(1,Math.min(100000,Math.floor(Number(req.query.page)||1))), size=50;
    return {total:all.length,total_words:totalWords,page,page_size:size,entries:all.slice((page-1)*size,page*size)};
  }));
  r.post('/words',transaction(pool,async(db,req,res)=>{
    const d=await device(db,res.locals.userId),text=req.body?.text,pinyin=normalizedPinyin(text,req.body?.pinyin);
    if(!pinyin) throw new HttpError(400,'请输入汉字及逐字匹配的拼音（空格分隔、无声调）');
    const result=await db.query('INSERT INTO dictionary_dashboard_word(group_id,text,pinyin) VALUES($1,$2,$3) ON CONFLICT DO NOTHING',[d.group_id,text,pinyin]);
    await dashboardEntries(db,d.group_id,{}); // 容量溢出时事务回滚。
    return {ok:true,created:!!result.rowCount};
  }));
  r.post(['/sync','/sync-all'],transaction(pool,async(db,req,res)=>{
    const syncAll=req.path==='/sync-all';
    if(syncAll && (req.body?.texts!==undefined || req.body?.filter!==undefined || req.body?.all!==undefined)) throw new HttpError(400,'全量同步不接受筛选');
    const {device_ids,texts,all,filter}=syncAll ? {...req.body,all:true} : req.body ?? {};
    if(!Array.isArray(device_ids) || !device_ids.length || device_ids.length>500 || !device_ids.every(id=>typeof id==='string' && uuid.test(id))) throw new HttpError(400,'请选择目标手机');
    if(all!==undefined && all!==true) throw new HttpError(400,'invalid selection');
    if(all===true ? texts!==undefined : !Array.isArray(texts) || !texts.length || texts.length>500 || !texts.every(chinese)) throw new HttpError(400,'请选择词语或全部筛选结果');
    if(filter!==undefined && (!filter || typeof filter!=='object' || Array.isArray(filter))) throw new HttpError(400,'invalid filter');
    const d=await device(db,res.locals.userId);
    const targets=await Promise.all([...new Set<string>(device_ids)].map(id=>device(db,id)));
    const selected=all===true ? null : new Set<string>(texts);
    const rows=(await dashboardEntries(db,d.group_id,filter ?? {})).filter(e=>!selected || selected.has(e.text));
    const requested=new Set<string>(selected ?? rows.map(e=>e.text));
    const words=new Map<string,{text:string,pinyin:string,preferred:boolean}>();
    for(const row of rows) {
      const pinyin=normalizedPinyin(row.text,row.pinyin);
      if(row.status!=='enabled' || !pinyin) continue;
      const key=JSON.stringify([row.text,pinyin]),old=words.get(key);
      words.set(key,{text:row.text,pinyin,preferred:old?.preferred===true || row.source==='dashboard'});
    }
    const eligibleTexts=new Set<string>();
    let queued=0;
    for(const target of targets) {
      const blocked=new Set((await db.query("SELECT text FROM dictionary_policy WHERE group_id=$1 AND status<>'enabled'",[target.group_id])).rows.map(p=>p.text));
      const existing=(await db.query('SELECT text,pinyin,preferred FROM dictionary_addition WHERE device_id=$1',[target.device_id])).rows;
      const known=new Map(existing.map(e=>[JSON.stringify([e.text,e.pinyin]),e.preferred]));
      let added=0;
      for(const [key,word] of words) {
        if(blocked.has(word.text)) continue;
        eligibleTexts.add(word.text);
        if(known.has(key)) {
          if(word.preferred && !known.get(key)) {
            await db.query("UPDATE dictionary_addition SET preferred=TRUE,cursor=nextval('dictionary_addition_cursor'),delivered=FALSE WHERE device_id=$1 AND text=$2 AND pinyin=$3",[target.device_id,word.text,word.pinyin]);
            queued++;
          }
        } else {
          if(existing.length+ ++added>100000) throw new HttpError(413,'目标词库追加容量超限');
          await db.query('INSERT INTO dictionary_addition(device_id,text,pinyin,preferred) VALUES($1,$2,$3,$4)',[target.device_id,word.text,word.pinyin,word.preferred]);
          queued++;
        }
      }
    }
    let habitsQueued=0;
    const habits=syncAll ? rows.filter(e=>e.kind==='choice' && e.status==='enabled') : [];
    for(const target of targets) {
      const blocked=new Set((await db.query("SELECT text FROM dictionary_policy WHERE group_id=$1 AND status<>'enabled'",[target.group_id])).rows.map(p=>p.text));
      for(const habit of habits) {
        if(blocked.has(habit.text)) continue;
        const old=(await db.query('SELECT version FROM dictionary_habit WHERE device_id=$1 AND source_device_id=$2 AND code=$3 AND text=$4',[target.device_id,habit.device_id,habit.code,habit.text])).rows[0];
        const version=Number((habit as typeof habit & {version:number}).version);
        if(!integer(version) || version<1) throw new HttpError(400,'invalid source version');
        if(old && Number(old.version)>=version) continue;
        await db.query(`INSERT INTO dictionary_habit(device_id,source_device_id,code,text,version,payload) VALUES($1,$2,$3,$4,$5,$6)
          ON CONFLICT(device_id,source_device_id,code,text) DO UPDATE SET version=EXCLUDED.version,payload=EXCLUDED.payload,cursor=nextval('dictionary_habit_cursor'),delivered=FALSE`,
          [target.device_id,habit.device_id,habit.code,habit.text,version,JSON.stringify(entry(habit))]);
        habitsQueued++;
      }
      const total=Number((await db.query('SELECT COUNT(*) AS n FROM dictionary_habit WHERE device_id=$1',[target.device_id])).rows[0].n);
      if(total>100000) throw new HttpError(413,'目标习惯追加容量超限');
    }
    return {ok:true,...(syncAll ? {habits:habits.length,habits_queued:habitsQueued} : {}),words:words.size,queued,skipped:[...requested].filter(text=>!eligibleTexts.has(text)).length,devices:targets.length};
  }));
  r.post('/bind',transaction(pool,async(db,req,res) => {
    const id=req.body?.device_id;
    if (typeof id !== 'string' || !uuid.test(id)) throw new HttpError(400,'请选择目标设备');
    const current=await device(db,res.locals.userId), incoming=await device(db,id);
    if (!current.restore_enabled || !incoming.restore_enabled) throw new HttpError(409,'此设备仅向本站备份，请在主后台管理词库');
    if (current.group_id===incoming.group_id) return {ok:true};
    const policies=(await db.query('SELECT text,status FROM dictionary_policy WHERE group_id=$1',[incoming.group_id])).rows;
    const severity: Record<Status,number>={enabled:0,disabled:1,deleted:2};
    for (const p of policies) {
      const old=(await db.query('SELECT status FROM dictionary_policy WHERE group_id=$1 AND text=$2',[current.group_id,p.text])).rows[0];
      if (!old || severity[p.status as Status]>severity[old.status as Status]) await db.query(`INSERT INTO dictionary_policy(group_id,text,status) VALUES($1,$2,$3)
        ON CONFLICT(group_id,text) DO UPDATE SET status=EXCLUDED.status,updated_at=NOW()`,[current.group_id,p.text,p.status]);
    }
    await db.query(`INSERT INTO dictionary_dashboard_word(group_id,text,pinyin)
      SELECT $1,text,pinyin FROM dictionary_dashboard_word WHERE group_id=$2 ON CONFLICT DO NOTHING`,[current.group_id,incoming.group_id]);
    await db.query('UPDATE dictionary_device SET group_id=$1,applied_revision=NULL WHERE group_id=$2',[current.group_id,incoming.group_id]);
    await dashboardEntries(db,current.group_id,{}); // 超限时整个绑定回滚，不建立无法恢复的分组。
    return {ok:true};
  }));
  r.post('/decisions',transaction(pool,async(db,req,res) => {
    const d=await device(db,res.locals.userId), {texts,status}=req.body ?? {};
    if (!d.restore_enabled) throw new HttpError(409,'此设备仅向本站备份，请在主后台管理词库');
    if (!Array.isArray(texts) || !texts.length || texts.length>500 || !texts.every(chinese) || !['enabled','disabled','deleted'].includes(status)) throw new HttpError(400,'invalid decision');
    for (const text of new Set(texts)) await db.query(`INSERT INTO dictionary_policy(group_id,text,status) VALUES($1,$2,$3)
      ON CONFLICT(group_id,text) DO UPDATE SET status=EXCLUDED.status,updated_at=NOW()`,[d.group_id,text,status]);
    return {ok:true};
  }));
  return r;
}
