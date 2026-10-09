import { defaultCaptureConfig,validCaptureRules } from '../lib/chatCaptureConfig.js';
import { readFileSync } from 'node:fs';
import { newDb } from 'pg-mem';
import type pg from 'pg';
import request from 'supertest';
import { beforeEach, expect, it } from 'vitest';
import { authenticatedRequest } from '../lib/dashboardAuthTestHelper.js';
import { createApp } from '../app.js';
const uid='00000000-0000-4000-8000-000000000001', other='00000000-0000-4000-8000-000000000002';
const path='/api/v1/dashboard/settings/chat-capture', url=`${path}?user_id=${uid}`;
let pool:pg.Pool;
beforeEach(async()=>{const db=newDb(); const adapter=db.adapters.createPg();pool=new adapter.Pool();await pool.query(readFileSync(new URL('../../migrations/020_runtime_settings.sql',import.meta.url),'utf8'));await pool.query('CREATE TABLE device(id uuid PRIMARY KEY,last_seen_at timestamptz DEFAULT NOW()); INSERT INTO device(id) VALUES($1)',[uid]);});
async function setup(){const app=createApp(pool);const dashboard=await authenticatedRequest(app);const state=await dashboard.get(url);return {app,dashboard,state};}
it('提供固定默认规则且移动端一致',async()=>{const {app,state}=await setup();expect(state.status).toBe(200);expect(state.body.current).toMatchObject({schemaVersion:1,revision:0});expect(state.body.current.rules[1]).toMatchObject({titleIds:['vw3','vww'],bodyIds:['jta','v6q'],voicePosition:'either'});expect((await request(app).get('/api/v1/mobile/chat-capture-config').set('X-Device-Id',uid)).body).toEqual(state.body.current);});
it('CAS保存、冲突、持久化及回滚；历史最多20',async()=>{const {dashboard,state}=await setup();const rules=state.body.current.rules;rules[0].enabled=false;expect((await dashboard.put(url).send({expectedRevision:0,rules})).body.current.revision).toBe(1);expect((await dashboard.put(url).send({expectedRevision:0,rules})).status).toBe(409);expect((await dashboard.post(`${path}/rollback?user_id=${uid}`).send({expectedRevision:1,revision:0})).body.current.rules[0].enabled).toBe(true);for(let i=2;i<23;i++)expect((await dashboard.put(url).send({expectedRevision:i,rules})).status).toBe(200);expect((await dashboard.get(url)).body.history).toHaveLength(20);});
it.each([{packageName:'evil.app'},{titleIds:['.*']},{titleIds:['com.tencent.mobileqq:id/name']},{voiceLabels:['a\n']},{voiceLabels:['']},{voicePosition:'bottom'},{minVersionCode:-1},{maxVersionCode:-1},{script:'x'},{enabled:1},{titleIds:Array(17).fill('x')}])('非法规则不写入 %j',async patch=>{const {dashboard,state}=await setup();const rules=state.body.current.rules;Object.assign(rules[0],patch);expect((await dashboard.put(url).send({expectedRevision:0,rules})).status).toBe(400);expect((await dashboard.get(url)).body.current.revision).toBe(0);});
it('拒绝重复、超量、未知字段与超大配置',async()=>{const {dashboard,state}=await setup();const r=state.body.current.rules[0];for(const rules of [[r,r],Array.from({length:21},(_,i)=>({...r,id:`r${i}`})),[{...r,voiceLabels:['x'.repeat(32768)]}]])expect((await dashboard.put(url).send({expectedRevision:0,rules})).status).toBe(400);expect((await dashboard.put(url).send({expectedRevision:0,rules:[],extra:true})).status).toBe(400);});
const diagnostic={device_id:uid,platform:'wechat',app_version_code:123,app_version_name:'8.0',config_revision:0,stage:'page',status:'empty_tree',error_code:null,observed_at:1000};
it('无内容诊断四阶段默认未上报，只保存最新事件且另记服务器时间',async()=>{const {app,dashboard}=await setup();const durl=`/api/v1/dashboard/chat-capture-diagnostics?user_id=${uid}&device_id=${uid}`;expect((await dashboard.get(durl)).body.platforms.wechat.page).toBeNull();const post=(d:any)=>request(app).post('/api/v1/mobile/chat/diagnostics').set('X-Device-Id',uid).send(d);expect((await post(diagnostic)).status).toBe(200);expect((await post({...diagnostic,observed_at:999,status:'matched'})).body.saved).toBe(false);const data=(await dashboard.get(durl)).body;expect(data.platforms.wechat.page).toMatchObject({...diagnostic,received_at:expect.any(Number)});expect(data.platforms.wechat.upload).toBeNull();});
it('诊断拒绝跨设备、缺设备、内容字段及阶段状态不匹配',async()=>{const {app,dashboard}=await setup();for(const patch of [{device_id:other},{content:'secret'},{stage:'upload',status:'matched'},{observed_at:0},{error_code:'1'}])expect((await request(app).post('/api/v1/mobile/chat/diagnostics').set('X-Device-Id',uid).send({...diagnostic,...patch})).status).toBe(400);expect((await request(app).post('/api/v1/mobile/chat/diagnostics').set('X-Device-Id',other).send({...diagnostic,device_id:other})).status).toBe(404);expect((await dashboard.get(`/api/v1/dashboard/chat-capture-diagnostics?user_id=${uid}&device_id=${other}`)).status).toBe(403);});

it('允许宿主混淆资源ID并拒绝重复ID与超长标签',async()=>{const {dashboard,state}=await setup();const rules=state.body.current.rules;rules[1].bodyIds=['2kk','d_-','lm=','com.ss.android.ugc.aweme:id/2kk'];expect((await dashboard.put(url).send({expectedRevision:0,rules})).status).toBe(200);for(const patch of [{bodyIds:['2kk','2kk']},{voiceLabels:['x'.repeat(65)]},{voiceLabels:[' 语音 ']}]){Object.assign(rules[1],patch);expect((await dashboard.put(url).send({expectedRevision:1,rules})).status).toBe(400);}});

it('32KiB按完整合法配置UTF8字节限制，合法近边界可保存',async()=>{
 const {dashboard}=await setup();
 const makeRules=(length:number)=>Array.from({length:4},(_,i)=>({...defaultCaptureConfig().rules[0],id:`wechat-${i}`,backLabels:Array.from({length:16},(_,n)=>`返${n}${'界'.repeat(length)}`),settingsLabels:Array.from({length:16},(_,n)=>`设${n}${'界'.repeat(length)}`),voiceLabels:Array.from({length:16},(_,n)=>`语${n}${'界'.repeat(length)}`)}));
 const near=makeRules(50),oversize=makeRules(60);
 expect(validCaptureRules(near)).toBe(true);expect(validCaptureRules(oversize)).toBe(true);
 const bytes=(rules:unknown[])=>Buffer.byteLength(JSON.stringify({schemaVersion:1,revision:1,rules}));
 expect(bytes(near)).toBeGreaterThan(30000);expect(bytes(near)).toBeLessThanOrEqual(32768);expect(bytes(oversize)).toBeGreaterThan(32768);
 expect((await dashboard.put(url).send({expectedRevision:0,rules:oversize})).status).toBe(400);expect((await dashboard.get(url)).body.current.revision).toBe(0);
 expect((await dashboard.put(url).send({expectedRevision:0,rules:near})).status).toBe(200);expect((await dashboard.get(url)).body.current.revision).toBe(1);
});

it.each([{packageName:['com.tencent.mm']},{voicePosition:['either']},{packageName:{toString:'com.tencent.mm'}},{voicePosition:{toString:'either'}}])('枚举字段严格拒绝非字符串 %j',async patch=>{const {dashboard,state}=await setup();const rules=state.body.current.rules;Object.assign(rules[0],patch);expect((await dashboard.put(url).send({expectedRevision:0,rules})).status).toBe(400);expect((await dashboard.get(url)).body.current.revision).toBe(0);});
it('浏览诊断独立于聊天阶段且拒绝内容和未知原因',async()=>{
 const {app,dashboard}=await setup();
 const post=(patch:object)=>request(app).post('/api/v1/mobile/chat/diagnostics').set('X-Device-Id',uid).send({...diagnostic,...patch});
 expect((await post({})).status).toBe(200);
 for(const [stage,status] of [['browse_capture','interval_limited'],['browse_upload','waiting_wifi']])
  expect((await post({stage,status})).status).toBe(200);
 for(const patch of [{stage:'browse_capture',status:'message text'},{stage:'browse_upload',status:'saved'},{stage:'browse_capture',status:'saved',content:'private'}])
  expect((await post(patch)).status).toBe(400);
 const data=(await dashboard.get(`/api/v1/dashboard/chat-capture-diagnostics?user_id=${uid}&device_id=${uid}`)).body;
 expect(data.platforms.wechat.page.status).toBe('empty_tree');
 expect(data.platforms.wechat.browse_capture.status).toBe('interval_limited');
 expect(data.platforms.wechat.browse_upload.status).toBe('waiting_wifi');
 expect(data.platforms.douyin.browse_capture).toBeNull();
});
