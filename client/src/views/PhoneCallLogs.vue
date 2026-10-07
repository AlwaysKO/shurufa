<script setup lang="ts">
import RetentionCleanup from '../components/RetentionCleanup.vue';
import { ref, watch, computed, onBeforeUnmount } from 'vue';
import { currentUserId } from '../api';
import { authenticated } from '../auth';
import { listPhoneCallLogs, requestPhoneCallLogs, type PhoneCallLog, type PhoneCallLogSync } from '../api/callRecordings';
const rows=ref<PhoneCallLog[]>([]),total=ref(0),page=ref(1),sync=ref<PhoneCallLogSync|null>(null),error=ref(''),loading=ref(false),requesting=ref(false);
let alive=true,generation=0,controller:AbortController|undefined,requestController:AbortController|undefined,timer:ReturnType<typeof setTimeout>|undefined;
function cancel(){generation++;controller?.abort();requestController?.abort();clearTimeout(timer);loading.value=false;requesting.value=false;}
async function load(){
  const version=++generation;controller?.abort();clearTimeout(timer);error.value='';
  if(!currentUserId.value||!authenticated.value){rows.value=[];total.value=0;sync.value=null;return;}
  controller=new AbortController();loading.value=true;
  try{
    const result=await listPhoneCallLogs(currentUserId.value,page.value,controller.signal);
    if(alive&&version===generation){rows.value=result.records;total.value=result.total;sync.value=result.sync;if(result.sync.request_id)timer=setTimeout(()=>{void load();},5000);}
  }catch(e){if(alive&&version===generation&&!(e instanceof DOMException&&e.name==='AbortError'))error.value=e instanceof Error?e.message:'通话记录加载失败';}
  finally{if(version===generation)loading.value=false;}
}
async function requestSync(){
  const device=currentUserId.value;if(!device||!authenticated.value)return;
  requestController?.abort();requestController=new AbortController();const active=requestController;
  requesting.value=true;error.value='';
  try{await requestPhoneCallLogs(device,active.signal);if(alive&&active===requestController&&device===currentUserId.value&&authenticated.value)await load();}
  catch(e){if(alive&&active===requestController&&device===currentUserId.value&&!(e instanceof DOMException&&e.name==='AbortError'))error.value=e instanceof Error?e.message:'请求失败';}
  finally{if(active===requestController)requesting.value=false;}
}
watch([currentUserId,authenticated],()=>{cancel();rows.value=[];total.value=0;sync.value=null;page.value=1;void load();},{immediate:true});
watch(page,()=>{void load();});
const state=computed(()=>({never:'尚未获取',pending:'等待手机同步',synced:'已同步',permission_required:'手机尚未授予通话记录权限',disabled:'手机同步未开启',failed:'手机读取失败，请稍后重试'}[sync.value?.status??'never']));
const kind=(type:number)=>({1:'呼入',2:'呼出',3:'未接',4:'语音信箱',5:'拒接',6:'拦截',7:'其他设备接听'}[type]??'未知');
const time=(value:number|string)=>new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false});
onBeforeUnmount(()=>{alive=false;cancel();});
function refreshAfterCleanup() { if (page.value !== 1) page.value = 1; else void load(); }
</script>
<template>
  <RetentionCleanup dataset="call-logs" label="手机通话记录" :context="page" @changed="refreshAfterCleanup" />
  <section class="phone-call-logs">
    <h3>手机通话记录</h3>
    <p>读取手机最近 7 天的普通电话呼入、呼出、未接记录及通话时长。即使没有录音，也可显示这些记录；不包含微信通话历史。</p>
    <div class="actions"><button :disabled="!currentUserId||!authenticated||requesting" @click="requestSync">{{requesting?'正在请求…':'获取手机通话记录'}}</button><button :disabled="loading||!currentUserId" @click="load">刷新记录</button><span>{{state}}</span></div>
    <p v-if="sync?.request_id">请求已保存，等待手机联网并开启“同步手机通话记录”、授予通话记录权限。后台通常约 15 分钟检查一次，也可在手机录音设置中立即同步；系统后台限制可能延迟。</p>
    <p v-if="sync?.synced_at">上次成功同步：{{time(sync.synced_at)}}<span v-if="sync.truncated">；本次仅取最近 2,000 条。</span></p>
    <p v-if="error" role="alert" class="error">{{error}}</p><p v-if="!loading&&!rows.length&&!error">暂无已同步的手机通话记录。</p>
    <div v-if="rows.length" class="table-wrap"><table><thead><tr><th>时间（北京时间）</th><th>联系人</th><th>号码</th><th>类型</th><th>通话时长</th></tr></thead><tbody><tr v-for="row in rows" :key="row.source_id"><td>{{time(row.date)}}</td><td>{{row.name||'未知'}}</td><td>{{row.number||'未知'}}</td><td>{{kind(row.type)}}</td><td>{{Math.floor(row.duration_seconds/60)}}分{{row.duration_seconds%60}}秒</td></tr></tbody></table></div>
    <div v-if="total" class="actions"><span>共 {{total}} 条，第 {{page}} 页</span><button :disabled="page<=1||loading" @click="page--">上一页</button><button :disabled="page*50>=total||loading" @click="page++">下一页</button></div>
  </section>
</template>
<style scoped>
.phone-call-logs{margin-top:32px;border-top:1px solid #ccd3df;padding-top:16px}.phone-call-logs p{color:#657286;line-height:1.7}.actions{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin:12px 0}button{padding:7px;border:1px solid #ccd3df;border-radius:5px;background:white;cursor:pointer}button:disabled{opacity:.5;cursor:default}.table-wrap{overflow:auto}table{border-collapse:collapse;width:100%;font-size:13px}td,th{padding:12px;text-align:left;border-bottom:1px solid #e0e5eb;white-space:nowrap}.phone-call-logs .error{color:#b42318}
</style>
