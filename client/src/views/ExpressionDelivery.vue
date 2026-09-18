<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { api } from '../api';
import type { DeliveryRule, DeliveryState, DeliveryConfig } from '../api/expressionDelivery';
const apps = [{name:'微信',pkg:'com.tencent.mm'},{name:'QQ',pkg:'com.tencent.mobileqq'},{name:'抖音',pkg:'com.ss.android.ugc.aweme'}];
const mimes = ['image/gif','image/webp','image/png','image/jpeg'];
const activePackage = ref(apps[0].pkg);
const rules = ref<DeliveryRule[]>([]);
const revision = ref(0);
const history = ref<DeliveryConfig[]>([]);
const extrasText = ref<Record<string,string>>({});
const busy = ref(false), ready = ref(false), error = ref(''), notice = ref('');
const visibleRules = computed(()=>rules.value.filter(r=>r.packageName===activePackage.value));
function apply(state:DeliveryState) {
  revision.value=state.current.revision;rules.value=state.current.rules;history.value=state.history;
  extrasText.value=Object.fromEntries(rules.value.map(r=>[r.id,JSON.stringify(r.requiredEditorExtras,null,2)]));ready.value=true;
}
async function load() {
  if(busy.value)return;busy.value=true;error.value='';notice.value='';
  try{apply(await api.expressionDelivery());}catch(e){error.value=e instanceof Error?e.message:'加载失败';}finally{busy.value=false;}
}
async function save() {
  if(busy.value||!ready.value)return;busy.value=true;error.value='';notice.value='';
  try {
    const next=rules.value.map(r=>{
      let extras:unknown;try{extras=JSON.parse(extrasText.value[r.id]??'{}');}catch{throw Error(`规则 ${r.id}：编辑器条件必须是合法 JSON 对象`);}
      if(extras===null||typeof extras!=='object'||Array.isArray(extras))throw Error(`规则 ${r.id}：编辑器条件必须是对象`);
      return {...r,versionName:r.versionName?.trim()||null,maxVersionCode:r.maxVersionCode==null||String(r.maxVersionCode)===''?null:Number(r.maxVersionCode),requiredEditorExtras:extras as Record<string,number>,action:r.method==='commit_content'?null:r.action,uriKey:r.method==='commit_content'?null:r.uriKey};
    });
    apply(await api.saveExpressionDelivery(revision.value,next));notice.value='配置已保存；手机在下次刷新成功后生效（通常 5 分钟内）。';
  } catch(e){error.value=e instanceof Error?e.message:'保存失败';}finally{busy.value=false;}
}
async function rollback(target:number) {
  if(busy.value||!ready.value)return;busy.value=true;error.value='';notice.value='';
  try{apply(await api.rollbackExpressionDelivery(revision.value,target));notice.value=`已回滚至历史内容，生成新版本 ${revision.value}`;}catch(e){error.value=e instanceof Error?e.message:'回滚失败';}finally{busy.value=false;}
}
function addRule(){
  const prefix=activePackage.value==='com.tencent.mm'?'wechat':activePackage.value==='com.tencent.mobileqq'?'qq':'douyin';
  let i=1;while(rules.value.some(r=>r.id===`${prefix}-rule-${i}`))i++;
  const id=`${prefix}-rule-${i}`;rules.value.push({id,packageName:activePackage.value,mimeTypes:['image/gif'],minVersionCode:0,maxVersionCode:null,versionName:null,minSdk:23,enabled:false,method:'commit_content',requireCompatIme:false,requiredEditorExtras:{},action:null,uriKey:null});extrasText.value[id]='{}';
  if(activePackage.value==='com.tencent.mm') {
    const r=rules.value[rules.value.length-1];
    Object.assign(r,{method:'private_command',minSdk:26,versionName:'8.0.78',requireCompatIme:true,requiredEditorExtras:{SUPPORT_SOGOU_EXPRESSION:1},action:'com.sogou.inputmethod.exp.commit',uriKey:'EXP_PATH_URI'});
    extrasText.value[id]=JSON.stringify(r.requiredEditorExtras,null,2);
  }
}
function removeRule(id:string){rules.value=rules.value.filter(r=>r.id!==id);delete extrasText.value[id];}
function moveRule(id:string,direction:number){
  const visible=visibleRules.value;const index=visible.findIndex(r=>r.id===id);const neighbor=visible[index+direction];if(!neighbor)return;
  const a=rules.value.findIndex(r=>r.id===id),b=rules.value.findIndex(r=>r.id===neighbor.id);[rules.value[a],rules.value[b]]=[rules.value[b],rules.value[a]];
}
function methodChanged(r:DeliveryRule){if(r.method==='private_command'){r.minSdk=Math.max(26,r.minSdk);r.action??='';r.uriKey??='';}else{r.action=null;r.uriKey=null;}}
onMounted(load);
</script>
<template>
  <section class="delivery">
    <h2>图片 / GIF 发送配置</h2>
    <p class="warning">全局配置：影响所有手机，不仅是当前选择的设备。仅调整已内置的发送方式；全新协议仍需升级客户端。GIF 不会自动降为静态图，也不会自动保存相册。</p>
    <p class="warning">微信 GIF 标准方式仅可指定已验证的新精确版本，8.0.78 禁止；客户端不转码不代表接收端保真。切换方式前请实测接收端动画，不能仅以交接成功为依据。</p>
    <p>按顺序使用第一条匹配 App、格式、版本和 SDK 的规则。禁用规则表示拒绝发送；无匹配规则也拒绝。能力检查失败不尝试下一条。交接成功不代表对方已确认发送。</p>
    <div class="actions"><strong>当前版本：{{ revision }}</strong><button :disabled="busy" @click="load">重新加载（放弃草稿）</button><button class="primary" :disabled="busy || !ready" @click="save">保存全部 App 配置</button></div>
    <p v-if="error" role="alert" class="error">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p><p v-if="busy">处理中…</p>
    <div role="tablist" class="tabs"><button v-for="app in apps" :key="app.pkg" role="tab" :aria-selected="activePackage===app.pkg" :class="{active:activePackage===app.pkg}" @click="activePackage=app.pkg">{{ app.name }}</button></div>
    <fieldset :disabled="busy || !ready">
      <p v-if="!visibleRules.length">此 App 没有规则，将拒绝图片发送。</p>
      <article v-for="(rule,index) in visibleRules" :key="rule.id" class="rule">
        <header><strong>{{ index+1 }}. {{ rule.id }}</strong><span><button :disabled="index===0" @click="moveRule(rule.id,-1)">上移</button><button :disabled="index===visibleRules.length-1" @click="moveRule(rule.id,1)">下移</button><button @click="removeRule(rule.id)">删除规则</button></span></header>
        <div class="fields">
          <label><input v-model="rule.enabled" type="checkbox">启用（关闭即阻断匹配内容）</label>
          <label>交付方式<select v-model="rule.method" @change="methodChanged(rule)"><option value="commit_content">标准图片交付</option><option value="private_command">专用命令</option></select></label>
          <label>最低版本号<input v-model.number="rule.minVersionCode" type="number" min="0" step="1"></label>
          <label>最高版本号（空为不限）<input v-model.number="rule.maxVersionCode" type="number" min="0" step="1"></label>
          <label>精确版本名（空为不限）<input v-model="rule.versionName" maxlength="80" placeholder="例如 8.0.78"></label>
          <label>最低 Android SDK<input v-model.number="rule.minSdk" type="number" :min="rule.method==='private_command'?26:23" max="100" step="1"></label>
          <label><input v-model="rule.requireCompatIme" type="checkbox">要求内置兼容输入法组件</label>
        </div>
        <div class="formats">格式：<label v-for="mime in mimes" :key="mime"><input v-model="rule.mimeTypes" type="checkbox" :value="mime">{{ mime.replace('image/','').toUpperCase() }}</label></div>
        <div v-if="rule.method==='private_command'" class="fields"><label>命令 action<input v-model="rule.action" maxlength="160"></label><label>URI 参数名 uriKey<input v-model="rule.uriKey" maxlength="80"></label></div>
        <label class="extras">编辑器条件 requiredEditorExtras（JSON 键→整数，最多 8 项）<textarea v-model="extrasText[rule.id]" rows="3" spellcheck="false"></textarea></label>
      </article>
      <button :disabled="rules.length>=40" @click="addRule">添加规则（默认禁用）</button>
    </fieldset>
    <details class="history"><summary>历史与回滚（最近 {{ history.length }} 个版本）</summary><p>回滚替换全部 App 配置，并生成更高版本。未保存的草稿会丢弃。</p><div v-for="old in history" :key="old.revision">版本 {{ old.revision }} · {{ old.rules.length }} 条规则 <button :disabled="busy" @click="rollback(old.revision)">回滚到此版本</button></div></details>
  </section>
</template>
<style scoped>
.delivery{max-width:1100px;padding:20px;line-height:1.65}.warning{background:#fff5df;padding:12px;border-radius:8px}.actions,.tabs,header,.formats{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.tabs{margin:20px 0}.tabs button{min-width:90px}.active,.primary{background:#4964de;color:white}.rule{border:1px solid #dce0e8;border-radius:10px;padding:16px;margin-bottom:16px;background:white}header{justify-content:space-between;margin-bottom:12px}.fields{display:grid;grid-template-columns:repeat(auto-fit,minmax(230px,1fr));gap:12px}.fields label,.extras{display:flex;flex-direction:column;gap:4px}.fields label:has(input[type=checkbox]){flex-direction:row;align-items:center}input:not([type=checkbox]),select,textarea{padding:8px;border:1px solid #ccd2df;border-radius:6px;font:inherit;min-width:0}button{padding:8px 12px;border:1px solid #ccd2df;border-radius:6px;cursor:pointer}button:disabled{opacity:.5;cursor:default}fieldset{border:0;padding:0;margin:0}.formats,.extras{margin-top:12px}.history{margin-top:24px}.history button{margin:6px}.error{color:#b42318}.extras textarea{font-family:monospace}
</style>
