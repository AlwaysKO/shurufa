<script setup lang="ts">
import RetentionCleanup from '../components/RetentionCleanup.vue';
import { ref, watch, onBeforeUnmount } from 'vue';
import PhoneCallLogs from './PhoneCallLogs.vue';
import { currentUserId } from '../api';
import { authenticated } from '../auth';
import { useConfirmation } from '../confirmation';
import { listCalls, callAudio, deleteCall, type CallRecording } from '../api/callRecordings';
const day=(offset=0)=>new Date(Date.now()+8*3600000+offset*86400000).toISOString().slice(0,10);
const from=ref(day(-6)),to=ref(day()),platform=ref(''),status=ref(''),page=ref(1);
const rows=ref<CallRecording[]>([]),total=ref(0),loading=ref(false),error=ref(''),busy=ref('');
const cleanupBusy=ref(false);
const selected=ref<CallRecording|null>(null),audioUrl=ref('');
const confirm=useConfirmation();let generation=0,alive=true,audioGeneration=0;
let request:AbortController|undefined,playRequest:AbortController|undefined;
function stopAudio(){audioGeneration++;if(playRequest){playRequest.abort();playRequest=undefined;busy.value='';}if(audioUrl.value)URL.revokeObjectURL(audioUrl.value);audioUrl.value='';selected.value=null;}
async function load(){
  const version=++generation;request?.abort();stopAudio();rows.value=[];total.value=0;error.value='';busy.value='';loading.value=false;
  if(!currentUserId.value||!authenticated.value)return;
  if(from.value&&to.value&&from.value>to.value){error.value='开始日期不能晚于结束日期';return;}
  request=new AbortController();loading.value=true;
  try{
    const result=await listCalls(currentUserId.value,{from:from.value,to:to.value,platform:platform.value,recording_status:status.value,page:page.value},request.signal);
    if(version===generation&&alive){rows.value=result.records;total.value=result.total;}
  }catch(e){if(version===generation&&alive&&!(e instanceof DOMException&&e.name==='AbortError'))error.value=e instanceof Error?e.message:'加载失败';}
  finally{if(version===generation)loading.value=false;}
}
watch([currentUserId,authenticated,from,to,platform,status],()=>{page.value=1;void load();},{immediate:true});
watch(page,()=>{void load();});
function preset(days:number,yesterday=false){from.value=day(yesterday?-1:1-days);to.value=day(yesterday?-1:0);}
async function play(row:CallRecording){
  stopAudio();const version=generation,device=currentUserId.value,audioVersion=audioGeneration;
  selected.value=row;busy.value=row.record_id;error.value='';playRequest=new AbortController();
  try{const blob=await callAudio(device,row.record_id,playRequest.signal);if(version===generation&&alive&&audioVersion===audioGeneration)audioUrl.value=URL.createObjectURL(blob);}
  catch(e){if(version===generation&&alive&&audioVersion===audioGeneration&&!(e instanceof DOMException&&e.name==='AbortError'))error.value=e instanceof Error?e.message:'播放失败';}
  finally{if(version===generation&&audioVersion===audioGeneration){busy.value='';playRequest=undefined;}}
}
async function remove(row:CallRecording){
  const version=generation,device=currentUserId.value;
  if(!await confirm('删除此条线上录音及其详情？此操作不可撤销。')||!alive||version!==generation)return;
  busy.value=row.record_id;error.value='';
  try{await deleteCall(device,row.record_id);if(version===generation&&alive){if(rows.value.length===1&&page.value>1)page.value--;else await load();}}
  catch(e){if(version===generation&&alive)error.value=e instanceof Error?e.message:'删除失败';}
  finally{if(version===generation)busy.value='';}
}
const formatTime=(v:number|null)=>v===null?'未知':new Date(v).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false});
const duration=(v:number|null)=>v===null?'未知':`${Math.floor(v/60000)}分${Math.floor(v%60000/1000)}秒`;
const recordingLabel=(v:string)=>({ended:'已结束',interrupted:'中断',restricted:'受限'}[v]??v);
const qualityLabel=(v:string)=>({unverified:'双方声音未验证',suspected_silent:'疑似静音',user_confirmed:'用户已确认'}[v]??v);
onBeforeUnmount(()=>{alive=false;generation++;request?.abort();stopAudio();});
function refreshAfterCleanup() { stopAudio(); if (page.value !== 1) page.value = 1; else void load(); }
</script>
<template>
  <RetentionCleanup dataset="call-recordings" label="通话录音" :filters="{ platform: platform || undefined, recording_status: status || undefined }" :scope-label="`${platform ? (platform === 'phone' ? '普通电话' : '微信') : '全部平台'} · ${status ? recordingLabel(status) : '全部录音状态'}`" :context="[from, to, page]" :disabled="!!busy" @changed="refreshAfterCleanup" @busy="cleanupBusy = $event" />
  <section class="calls">
    <h2>通话录音</h2>
    <p class="notice">当前设备由左侧选择。上传已保存不代表双方声音完整；线上保留由管理员管理，本页面不自动清理。日期与时间均为北京时间。</p>
    <div class="filters">
      <label>开始日期 <input v-model="from" type="date"></label><label>结束日期 <input v-model="to" type="date"></label>
      <button @click="preset(1)">今天</button><button @click="preset(1,true)">昨天</button><button @click="preset(7)">近 7 天</button><button @click="preset(30)">近 30 天</button>
      <select v-model="platform" aria-label="平台"><option value="">全部平台</option><option value="phone">普通电话</option><option value="wechat">微信</option></select>
      <select v-model="status" aria-label="录音状态"><option value="">全部录音状态</option><option value="ended">已结束</option><option value="interrupted">中断</option><option value="restricted">受限</option></select>
      <button :disabled="loading" @click="load">刷新</button>
    </div>
    <p v-if="error" role="alert" class="error">{{error}}</p><p v-if="loading">加载中…</p>
    <p v-else-if="!currentUserId">请先选择设备。</p><p v-else-if="!rows.length&&!error">此范围暂无已上传录音。手机待传、录音受限或尚未启用不会显示成上传成功。</p>
    <div v-if="rows.length" class="table-wrap"><table><thead><tr><th>平台 / 类型</th><th>通话对象</th><th>通话起止</th><th>通话时长</th><th>录音起止</th><th>录音时长</th><th>录音状态 / 质量</th><th>上传</th><th>操作</th></tr></thead>
      <tbody><tr v-for="row in rows" :key="row.record_id">
        <td>{{row.platform==='phone'?'普通电话':'微信'}} / {{row.call_type==='video'?'视频（仅声音）':'语音'}}</td>
        <td>{{row.counterpart_display||'未知'}}</td><td>{{formatTime(row.call_started_at)}}<br>{{formatTime(row.call_ended_at)}}</td>
        <td>{{duration(row.call_duration_ms)}}{{row.call_duration_estimated?'（估算）':''}}</td>
        <td>{{formatTime(row.recording_started_at)}}<br>{{formatTime(row.recording_ended_at)}}</td><td>{{duration(row.audio_duration_ms)}}</td>
        <td>{{recordingLabel(row.recording_status)}}<br>{{qualityLabel(row.quality_status)}}<small v-if="row.failure_reason">{{row.failure_reason}}</small></td>
        <td>已保存</td><td><button :disabled="!!busy || cleanupBusy" @click="play(row)">详情 / 试听</button><button :disabled="!!busy || cleanupBusy" @click="remove(row)">删除</button></td>
      </tr></tbody></table></div>
    <div v-if="total" class="filters"><span>共 {{total}} 条，第 {{page}} 页</span><button :disabled="page<=1||loading" @click="page--">上一页</button><button :disabled="page*50>=total||loading" @click="page++">下一页</button></div>
    <aside v-if="selected"><h3>{{selected.counterpart_display||'未知对象'}} · {{qualityLabel(selected.quality_status)}}</h3>
      <p>保存时间：{{new Date(selected.stored_at).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'})}}</p>
      <audio v-if="audioUrl" :src="audioUrl" controls preload="metadata" @error="error='音频无法解码或播放；不能据此认定录音完整。'" />
      <p v-else>音频尚未加载成功。</p><button @click="stopAudio">关闭试听</button>
    </aside>
    <PhoneCallLogs />
  </section>
</template>
<style scoped>
.calls{padding:24px;color:#263248}.notice{color:#657286;line-height:1.7}.filters{display:flex;flex-wrap:wrap;align-items:center;gap:10px;margin:18px 0}input,select,button{padding:7px;border:1px solid #ccd3df;border-radius:5px;background:white}button{cursor:pointer}button:disabled{opacity:.5;cursor:default}.table-wrap{overflow:auto}table{border-collapse:collapse;width:100%;font-size:13px}td,th{padding:12px;text-align:left;border-bottom:1px solid #e0e5eb;white-space:nowrap}small{display:block}.error{color:#b42318}aside{padding:16px;background:#f4f6fa;margin-top:20px}audio{display:block;margin:12px 0;width:min(100%,500px)}
</style>
