<script setup lang="ts">
import {onMounted,onUnmounted,ref,watch} from 'vue';
import {api,currentUserId} from '../api';
import {parseCaptureRules,diagnosticFreshness,type CaptureState,type CaptureConfig,type CaptureDiagnostics} from '../api/chatCapture';
const text=ref(''),revision=ref(0),history=ref<CaptureConfig[]>([]),ready=ref(false),busy=ref(false),error=ref(''),notice=ref('');
const diagnostics=ref<CaptureDiagnostics|null>(null),diagnosticError=ref(''),diagnosticBusy=ref(false);
const platforms=[{id:'wechat',name:'微信'},{id:'douyin',name:'抖音'}],stages=[{id:'page',name:'页面识别'},{id:'screenshot',name:'截图'},{id:'persist',name:'本地落盘'},{id:'upload',name:'上传回执'}];
const labels:Record<string,string>={matched:'已匹配',rejected:'非聊天页/证据不足',empty_tree:'页面树为空',ready:'截图就绪',failed:'失败',cancelled:'已取消',inserted:'已落盘',duplicate:'重复',acknowledged:'服务器已接收',waiting:'等待上传'};
function apply(state:CaptureState){revision.value=state.current.revision;text.value=JSON.stringify(state.current.rules,null,2);history.value=state.history;ready.value=true;}
async function load(){if(busy.value)return;busy.value=true;error.value='';try{apply(await api.chatCaptureConfig());}catch(e){error.value=String(e);}finally{busy.value=false;}}
async function save(){if(busy.value||!ready.value)return;error.value='';notice.value='';try{const rules=parseCaptureRules(text.value,revision.value+1);busy.value=true;apply(await api.saveChatCaptureConfig(revision.value,rules));notice.value='已保存，支持此协议的手机下次成功刷新后生效；不代表真机采集已验收。';}catch(e){error.value=e instanceof Error?e.message:String(e);}finally{busy.value=false;}}
async function rollback(target:number){if(busy.value||!ready.value)return;if(!window.confirm('回滚全部规则并丢弃当前草稿，是否继续？'))return;busy.value=true;error.value='';try{apply(await api.rollbackChatCaptureConfig(revision.value,target));notice.value='已恢复历史规则并生成新版本。';}catch(e){error.value=String(e);}finally{busy.value=false;}}
let diagnosticGeneration=0;
async function loadDiagnostics(){const generation=++diagnosticGeneration;diagnostics.value=null;diagnosticError.value='';if(!currentUserId.value){diagnosticBusy.value=false;return;}diagnosticBusy.value=true;try{const result=await api.chatCaptureDiagnostics();if(generation===diagnosticGeneration)diagnostics.value=result;}catch(e){if(generation===diagnosticGeneration)diagnosticError.value=String(e);}finally{if(generation===diagnosticGeneration)diagnosticBusy.value=false;}}
const time=(n:number)=>Number.isSafeInteger(n)&&n>0&&Number.isFinite(new Date(n).getTime())?new Date(n).toLocaleString():'无效时间';
const now=ref(Date.now());
let freshnessTimer:ReturnType<typeof setInterval>|undefined;
onUnmounted(()=>{if(freshnessTimer!==undefined)clearInterval(freshnessTimer);diagnosticGeneration++;});
watch(currentUserId,()=>{void loadDiagnostics();if(currentUserId.value&&!ready.value)void load();});
onMounted(()=>{freshnessTimer=setInterval(()=>{now.value=Date.now();},15_000);void load();void loadDiagnostics();});
</script>
<template>
<section class="capture-settings">
<h2>聊天采集配置与诊断</h2>
<p class="warning">规则为全局配置，影响所有支持此协议的手机。只能补充微信/抖音适配证据，不能取消客户端固定的同层多证据、非聊天页隔离、输入/游戏避让和上传限制。宿主机制彻底变化仍可能需要升级 APK。</p>
<p>enabled=false 仅禁用该条适配规则，不是整个聊天采集开关；手机仍保留保守内置识别与兜底。修改前先核对版本并复测，错误配置可能造成漏采。</p>
<div class="actions"><strong>当前配置版本 {{ revision }}</strong><button :disabled="busy" @click="load">重新加载（放弃草稿）</button><button :disabled="busy||!ready" @click="save">保存全部规则</button></div>
<p v-if="error" role="alert" class="error">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
<details><summary>字段与安全限制</summary><p>按列表顺序选择第一条启用且包名、版本范围匹配的规则。特定新版本规则应放在默认规则之前，避免被宽范围规则先匹配；结构安全校验始终保留。</p><p>编辑 rules JSON 数组：每条必须包含 id、packageName、minVersionCode、maxVersionCode（null 为不限）、enabled、titleIds、inputIds、bodyIds、backLabels、settingsLabels、voiceLabels、voicePosition。仅支持 com.tencent.mm / com.ss.android.ugc.aweme；voicePosition 为 left/right/either；id 小写字母开头且唯一。版本号为非负整数且范围不能倒置。</p><p>最多20条规则，各数组最多16项且不重复。标签最多64个字符，禁止首尾空格。资源 ID 只能是短 ID 或同包 :id/ 前缀；标签使用精确文本，非空且无控制字符，禁止正则、脚本与通配。完整配置最多32KB。空规则不会撤销客户端内置兜底。</p></details>
<label>规则 JSON<textarea v-model="text" :disabled="busy||!ready" rows="25" spellcheck="false" aria-label="聊天采集适配规则 JSON"></textarea></label>
<details><summary>历史回滚（最近 {{ history.length }} 版）</summary><p>回滚替换全部规则并生成更高版本，不会降低客户端版本号。</p><div v-for="old in history" :key="old.revision">版本 {{ old.revision }} · {{ old.rules.length }} 条 <button :disabled="busy||!ready" @click="rollback(old.revision)">回滚</button></div></details>
<h3>当前设备四阶段诊断</h3><p>使用顶部设备选择：{{ currentUserId || '未选择设备' }}。诊断不包含会话名、消息文字或图片。设备观察时间来自手机，接收时间来自服务器，不能把旧结果当作当前正常。以服务器接收时间10分钟为显示新鲜度阈值，超过阈值仅标记历史结果，不代表已经漏采。</p>
<button :disabled="diagnosticBusy||!currentUserId" @click="loadDiagnostics">刷新诊断</button><p v-if="diagnosticError" class="error" role="alert">{{ diagnosticError }}</p><p v-if="diagnosticBusy">加载诊断中…</p>
<p v-if="!currentUserId">请先从顶部选择设备。</p>
<div v-if="diagnostics" class="platforms"><article v-for="platform in platforms" :key="platform.id"><h4>{{ platform.name }}</h4><div v-for="stage in stages" :key="stage.id" class="stage"><strong>{{ stage.name }}：</strong><template v-if="diagnostics.platforms[platform.id][stage.id]"><p :class="{error:diagnosticFreshness(diagnostics.platforms[platform.id][stage.id]!.received_at,now).state!=='recent'}">{{ diagnosticFreshness(diagnostics.platforms[platform.id][stage.id]!.received_at,now).text }}</p><span>{{ labels[diagnostics.platforms[platform.id][stage.id]!.status] || diagnostics.platforms[platform.id][stage.id]!.status }}</span><p>错误码 {{ diagnostics.platforms[platform.id][stage.id]!.error_code ?? '无' }} · App {{ diagnostics.platforms[platform.id][stage.id]!.app_version_name }} ({{ diagnostics.platforms[platform.id][stage.id]!.app_version_code }}) · 配置 {{ diagnostics.platforms[platform.id][stage.id]!.config_revision }}</p><p>设备观察 {{ time(diagnostics.platforms[platform.id][stage.id]!.observed_at) }}<br>服务器接收 {{ time(diagnostics.platforms[platform.id][stage.id]!.received_at) }}</p></template><span v-else>未上报：可能尚未触发、旧版 APK 不支持或上传未成功，不能判定正常。</span></div></article></div>
</section>
</template>
<style scoped>
.capture-settings{max-width:1100px;padding:20px;line-height:1.7}.warning{background:#fff5df;padding:12px;border-radius:8px}.actions{display:flex;gap:12px;align-items:center;flex-wrap:wrap}textarea{display:block;width:100%;box-sizing:border-box;font:13px/1.5 monospace;margin:12px 0;padding:12px;border:1px solid #ccd2df;border-radius:8px}button{padding:8px 12px;margin:6px;border:1px solid #ccd2df;border-radius:6px;cursor:pointer}button:disabled{opacity:.5;cursor:default}.error{color:#b42318}.platforms{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:16px}.stage{padding:12px;border:1px solid #dce0e8;margin-bottom:10px;border-radius:8px}.stage p{margin:4px 0;overflow-wrap:anywhere}
</style>
