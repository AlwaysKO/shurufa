import { readFileSync } from 'node:fs';
import { parse, compileScript } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';
import { afterEach, expect, it, vi } from '../../server/node_modules/vitest/dist/index.js';
import { defaultDeliveryConfig } from '../../server/src/lib/expressionDelivery';
const mounted: Vue.App[]=[];
afterEach(()=>mounted.splice(0).forEach(a=>a.unmount()));
const settle=async()=>{for(let i=0;i<12;i++)await Promise.resolve();await Vue.nextTick();};
async function setup(overrides:Record<string,unknown>={}) {
 const initial={current:defaultDeliveryConfig(),history:[]};
 const api={expressionDelivery:vi.fn(async()=>structuredClone(initial)),saveExpressionDelivery:vi.fn(async(expectedRevision:number,rules:unknown)=>({current:{schemaVersion:1,revision:expectedRevision+1,rules},history:[initial.current]})),rollbackExpressionDelivery:vi.fn(),...overrides};
 const source=readFileSync(new URL('../src/views/ExpressionDelivery.vue',import.meta.url),'utf8');
 const {descriptor}=parse(source);const code=ts.transpileModule(compileScript(descriptor,{id:'delivery-test'}).content,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
 const module={exports:{} as any};new Function('require','module','exports',code)((id:string)=>id==='vue'?Vue:id==='../api'?{api}: (()=>{throw Error(id)})(),module,module.exports);
 let state:any;const renderer=Vue.createRenderer<any,any>({createElement:()=>({}),createText:()=>({}),createComment:()=>({}),setText(){},setElementText(){},parentNode:()=>null,nextSibling:()=>null,patchProp(){},insert(){},remove(){},insertStaticContent:()=>[{},{}]});
 const app=renderer.createApp({setup(){state=module.exports.default.setup({}, {expose(){}});return()=>null;}});app.mount({});mounted.push(app);await settle();return {state,api};
}
it('显示三个App标签、版本与全局边界及编辑/排序/回滚控件',()=>{const s=readFileSync(new URL('../src/views/ExpressionDelivery.vue',import.meta.url),'utf8');for(const term of ['微信','QQ','抖音','全局','第一条','交接','回滚','role="tab"','minVersionCode','maxVersionCode','requiredEditorExtras','uriKey','moveRule'])expect(s).toContain(term);});
it('修改当前App保留其他App规则并携带加载revision，成功才更新revision',async()=>{const {state,api}=await setup();state.rules.value[0].enabled=false;await state.save();expect(api.saveExpressionDelivery).toHaveBeenCalledWith(0,expect.arrayContaining([expect.objectContaining({id:'qq-content'}),expect.objectContaining({id:'wechat-gif',enabled:false})]));expect(state.revision.value).toBe(1);});
it('409保留草稿与旧revision，明确显示错误而不覆盖',async()=>{const {state}=await setup({saveExpressionDelivery:vi.fn().mockRejectedValue(Error('配置已被其他管理员更新，请重新加载'))});state.rules.value[0].versionName='8.0.79';await state.save();expect(state.revision.value).toBe(0);expect(state.rules.value[0].versionName).toBe('8.0.79');expect(state.error.value).toContain('重新加载');});
it('切App、添加和移动不丢其他App；非法extras阻止请求',async()=>{const {state,api}=await setup();state.activePackage.value='com.tencent.mobileqq';state.addRule();expect(state.visibleRules.value).toHaveLength(2);state.moveRule(state.visibleRules.value[1].id,-1);expect(state.visibleRules.value[0].id).not.toBe('qq-content');state.extrasText.value['wechat-gif']='{broken';await state.save();expect(api.saveExpressionDelivery).not.toHaveBeenCalled();expect(state.error.value).toContain('编辑器');});
it('加载失败禁止使用默认revision覆盖服务端',async()=>{const {state,api}=await setup({expressionDelivery:vi.fn().mockRejectedValue(Error('断网'))});await state.save();expect(api.saveExpressionDelivery).not.toHaveBeenCalled();expect(state.ready.value).toBe(false);});
it('回滚使用当前revision且返回新状态，避免重复提交',async()=>{const rollback=vi.fn(async()=>({current:{...defaultDeliveryConfig(),revision:4},history:[]}));const {state,api}=await setup({rollbackExpressionDelivery:rollback});const first=state.rollback(0);await state.rollback(0);await first;expect(api.rollbackExpressionDelivery).toHaveBeenCalledTimes(1);expect(api.rollbackExpressionDelivery).toHaveBeenCalledWith(0,0);expect(state.revision.value).toBe(4);});
it('真实API透传冲突解释和用户范围，不用泛化状态码掩盖恢复操作',async()=>{
 const fetch=vi.fn(async()=>new Response(JSON.stringify({error:'revision_conflict',message:'配置已被其他管理员更新，请重新加载后修改'}),{status:409}));
 const source=readFileSync(new URL('../src/api/index.ts',import.meta.url),'utf8');const code=ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
 const module={exports:{} as any};new Function('require','module','exports','localStorage','window',code)((id:string)=>id==='vue'?Vue:{dashboardFetch:fetch},module,module.exports,{getItem:()=> '00000000-0000-4000-8000-000000000001'},{location:{origin:'https://fixture.local'}});
 await expect(module.exports.api.saveExpressionDelivery(2,[])).rejects.toThrow('请重新加载');expect(fetch.mock.calls[0][0]).toContain('user_id=');
 await expect(module.exports.api.rollbackExpressionDelivery(2,0)).rejects.toThrow('请重新加载');
});

it('新增微信GIF沿用保真私有参数且提示标准方式的版本限制',async()=>{const {state}=await setup();state.addRule();const r=state.visibleRules.value.at(-1);expect(r).toMatchObject({method:'private_command',minSdk:26,versionName:'8.0.78',requireCompatIme:true,action:'com.sogou.inputmethod.exp.commit',uriKey:'EXP_PATH_URI',requiredEditorExtras:{SUPPORT_SOGOU_EXPRESSION:1}});const s=readFileSync(new URL('../src/views/ExpressionDelivery.vue',import.meta.url),'utf8');expect(s).toContain('8.0.78 禁止');expect(s).toContain('客户端不转码不代表接收端保真');});

it('新增QQ抖音标准规则最低SDK为23，保留legacy宿主兼容',async()=>{const {state}=await setup();for(const pkg of ['com.tencent.mobileqq','com.ss.android.ugc.aweme']){state.activePackage.value=pkg;state.addRule();expect(state.visibleRules.value.at(-1)).toMatchObject({method:'commit_content',minSdk:23});}});
