import { beforeEach, afterEach, expect, it } from 'vitest';
import pg from 'pg';
import { readFileSync } from 'node:fs';
import request from 'supertest';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { mkdtemp, writeFile, rm, readFile } from 'node:fs/promises';
import { tmpdir, userInfo } from 'node:os';
import { join } from 'node:path';
import { createApp } from '../app.js';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';
const root=process.env.CALL_RECORDING_TEST_CLUSTER;
if(root && (!root.startsWith('/tmp/shurufa-call-audio.') || readFileSync(root+'/test-instance-only','utf8')!=='call-audio-only')) throw Error('独立测试实例校验失败');
const test=root?it:it.skip;
let schema:string;
const A = '00000000-0000-4000-8000-00000000000a', B = '00000000-0000-4000-8000-00000000000b';
const token = 'a'.repeat(64), otherToken = 'b'.repeat(64);
const audio = Buffer.from('0000ftypM4A synthetic test audio');
const hash = (v: Buffer|string) => createHash('sha256').update(v).digest('hex');
const metadata = (overrides = {}) => ({ platform:'phone',call_type:'voice',counterpart_display:null,counterpart_source:'unknown',
  call_started_at:null,call_ended_at:null,call_duration_ms:null,call_duration_estimated:false,
  recording_started_at:1780000000000,recording_ended_at:1780000001000,audio_duration_ms:1000,
  recording_status:'ended',quality_status:'unverified',failure_reason:null,mime_type:'audio/mp4',
  byte_size:audio.length,sha256:hash(audio),consent_version:'call-audio-v1',destination:'https://example.test',...overrides });
let pool:pg.Pool, app:ReturnType<typeof createApp>, dir:string;
let agent:Awaited<ReturnType<typeof authenticatedRequest>>;
beforeEach(async () => {
  if(!root)return;
  schema='calls_'+randomUUID().replaceAll('-','');
  dir=await mkdtemp(join(tmpdir(),'shurufa-call-test-'));
  process.env.CALL_RECORDING_KEY_FILE=join(dir,'key');
  await writeFile(process.env.CALL_RECORDING_KEY_FILE,randomBytes(32),{mode:0o600});
  pool=new pg.Pool({host:root+'/socket',user:userInfo().username,database:'call_audio_test',options:'-c search_path='+schema});
  expect((await pool.query("SELECT current_setting('data_directory') AS dir")).rows[0].dir).toBe(root+'/data');
  await pool.query('CREATE SCHEMA '+schema);
  await pool.query('CREATE TABLE dictionary_device(device_id UUID PRIMARY KEY,token_hash TEXT); CREATE TABLE runtime_setting(key TEXT PRIMARY KEY,value TEXT)');
  await pool.query('INSERT INTO dictionary_device VALUES($1,$2),($3,$4)',[A,hash(token),B,hash(otherToken)]);
  await pool.query(await readFile(new URL('../../migrations/037_call_recordings.sql',import.meta.url),'utf8'));
  app=createApp(pool);agent=await authenticatedRequest(app);
});
afterEach(async()=>{if(!root)return;await pool?.query('DROP SCHEMA '+schema+' CASCADE');await pool?.end();if(dir)await rm(dir,{recursive:true,force:true});delete process.env.CALL_RECORDING_KEY_FILE;});
const put=(id:string, m=metadata(), bytes=audio, device=A, credential=token)=>request(app)
  .put(`/api/v1/mobile/call-recordings/${id}`).set('X-Device-Id',device).set('X-Dictionary-Token',credential)
  .set('X-Call-Metadata',Buffer.from(JSON.stringify(m)).toString('base64')).set('Content-Type','application/octet-stream').send(bytes);
const receipt=(id:string,device=A,credential=token)=>request(app).get(`/api/v1/mobile/call-recordings/${id}/receipt`).set('X-Device-Id',device).set('X-Dictionary-Token',credential);
const url=(id='',device=A)=>`/api/v1/dashboard/call-recordings${id?'/' + id:''}?user_id=${device}`;
test('持久保存回执与内容匹配、密文存储、重试幂等',async()=>{
  const id=randomUUID(), first=await put(id);
  expect(first.status).toBe(200);expect(first.body).toMatchObject({stored:true,record_id:id,device_id:A,byte_size:audio.length,sha256:hash(audio)});
  expect((await put(id)).body).toEqual(first.body);
  expect((await receipt(id)).body).toEqual(first.body);
  const rows=(await pool.query('SELECT audio_ciphertext FROM call_recording')).rows;
  expect(rows).toHaveLength(1);expect(rows[0].audio_ciphertext.includes(audio)).toBe(false);
  expect((await put(id,metadata({counterpart_display:'different',counterpart_source:'user'}))).status).toBe(409);
});
test('错误凭据不能冒充设备、跨设备无法查回执播放删除',async()=>{
  const id=randomUUID();await put(id);
  expect((await put(randomUUID(),metadata(),audio,A,otherToken)).status).toBe(401);
  expect((await receipt(id,B,otherToken)).status).toBe(404);
  expect((await receipt(id,B,token)).status).toBe(401);
  expect((await agent.get(url('',B))).body.records).toEqual([]);
  expect((await agent.get(`/api/v1/dashboard/call-recordings/${id}/audio?user_id=${B}`)).status).toBe(404);
  expect((await agent.delete(url(id,B))).status).toBe(404);
});
test('未登录不能列举或播放，鉴权播放返回实际音频且禁缓存',async()=>{
  const id=randomUUID();await put(id);
  expect((await request(app).get(url())).status).toBe(401);
  expect((await request(app).get(`/api/v1/dashboard/call-recordings/${id}/audio?user_id=${A}`)).status).toBe(401);
  const r=await agent.get(`/api/v1/dashboard/call-recordings/${id}/audio?user_id=${A}`);
  expect(r.status).toBe(200);expect(r.headers['cache-control']).toBe('no-store');expect(r.body).toEqual(audio);
});
test('系统录音常见格式按实际类型保存播放且拒绝伪造类型',async()=>{
  const fixtures=[
    ['audio/mpeg',Buffer.from('ID3synthetic mp3 recording')],
    ['audio/amr',Buffer.from('#!AMR\nsynthetic recording')],
    ['audio/wav',Buffer.from('RIFF0000WAVEsynthetic recording')],
  ] as const;
  for(const [mime,bytes] of fixtures){
    const id=randomUUID();
    expect((await put(id,metadata({mime_type:mime,sha256:hash(bytes),byte_size:bytes.length}),bytes)).status).toBe(200);
    const response=await agent.get(`/api/v1/dashboard/call-recordings/${id}/audio?user_id=${A}`);
    expect(response.status).toBe(200);expect(response.headers['content-type']).toContain(mime);
    expect(response.body).toEqual(bytes);
    expect((await put(randomUUID(),metadata({mime_type:'audio/mp4',sha256:hash(bytes),byte_size:bytes.length}),bytes)).status).toBe(400);
  }
  expect((await put(randomUUID(),metadata({mime_type:'text/html'}))).status).toBe(400);
});
test('保存关闭、密钥缺失、长度/哈希错误、元数据超限均不发可清理回执',async()=>{
  const id=randomUUID();
  expect((await put(id,metadata({sha256:'f'.repeat(64)}))).status).toBe(400);
  expect((await put(id,metadata({byte_size:audio.length+1}))).status).toBe(400);
  expect((await put(id,metadata({byte_size:64*1024*1024+1}))).status).toBe(413);
  expect((await put(id,metadata({audio_duration_ms:7200001}))).status).toBe(400);
  expect((await put(id,metadata({recording_status:'recording'}))).status).toBe(400);
  expect((await put(id,metadata({counterpart_display:'x'.repeat(201),counterpart_source:'user'}))).status).toBe(400);
  delete process.env.CALL_RECORDING_KEY_FILE;expect((await put(id)).status).toBe(503);
  process.env.CALL_RECORDING_KEY_FILE=join(dir,'key');
  await pool.query('INSERT INTO runtime_setting VALUES($1,$2)',[`device_save_uploads:${A}`,'false']);
  const off=await put(id);expect(off.status).toBe(409);expect(off.body.stored).not.toBe(true);
  expect((await receipt(id)).status).toBe(404);
});
test('显式删除音频和敏感元数据，保留幂等墓碑，禁止重试复活',async()=>{
  const id=randomUUID();await put(id);expect((await agent.delete(url(id))).status).toBe(204);
  expect((await agent.delete(url(id))).status).toBe(204);
  expect((await agent.get(url())).body.records).toEqual([]);
  expect((await receipt(id)).status).toBe(410);expect((await put(id)).status).toBe(410);
  const row=(await pool.query('SELECT audio_ciphertext,metadata FROM call_recording')).rows[0];
  expect(row.audio_ciphertext).toBeNull();expect(row.metadata).toEqual({});
});
test('日期/平台/录音状态筛选及分页参数校验',async()=>{
  await put(randomUUID());await put(randomUUID(),metadata({platform:'wechat',call_type:'video',recording_status:'interrupted'}));
  expect((await agent.get(url()+'&platform=phone')).body.records).toHaveLength(1);
  expect((await agent.get(url()+'&recording_status=interrupted')).body.records).toHaveLength(1);
  expect((await agent.get(url()+'&from=2020-01-01&to=2020-01-02')).body.records).toHaveLength(0);
  for(const q of ['&page=-1','&from=2026-02-30','&platform=unknown','&recording_status=wrong']) expect((await agent.get(url()+q)).status).toBe(400);
});
test('数据库拒绝写入时不产生回执，恢复后可重试',async()=>{
  const id=randomUUID();
  await pool.query(`CREATE FUNCTION reject_call() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic failure'; END $$;
    CREATE TRIGGER reject_call BEFORE INSERT ON call_recording FOR EACH ROW EXECUTE FUNCTION reject_call()`);
  const failed=await put(id);expect(failed.status).toBe(503);expect(failed.body.stored).not.toBe(true);
  expect((await receipt(id)).status).toBe(404);
  await pool.query('DROP TRIGGER reject_call ON call_recording');
  expect((await put(id)).status).toBe(200);
});
test('并发重复上传仅有一条记录，不同内容不能抢占原记录',async()=>{
  const id=randomUUID();const responses=await Promise.all([put(id),put(id),put(id)]);
  for(const r of responses)expect(r.status).toBe(200);
  expect((await pool.query('SELECT COUNT(*) AS n FROM call_recording')).rows[0].n).toBe('1');
  const changed=Buffer.from('0000ftypM4A other recording');
  expect((await put(id,metadata({sha256:hash(changed),byte_size:changed.length}),changed)).status).toBe(409);
});
test('实际请求体超过64MiB也拒绝，不信任声明的长度',async()=>{
  const id=randomUUID();
  const r=await put(id,metadata({byte_size:64*1024*1024}),Buffer.alloc(64*1024*1024+1));
  expect(r.status).toBe(413);expect((await receipt(id)).status).toBe(404);
},15000);
