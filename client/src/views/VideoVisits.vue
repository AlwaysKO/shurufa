<script setup lang="ts">
import { reactive, ref, watch, onBeforeUnmount } from 'vue';
import CaptureManagementControls from '../components/CaptureManagementControls.vue';
import CaptureRecordActions from '../components/CaptureRecordActions.vue';
import { CaptureManagement } from '../captureManagement';
import { captureMutation } from '../api/captureManagement';
import { currentUserId } from '../api';
import { authenticated } from '../auth';
import { fetchVideoVisits } from '../api/videoVisits';
import { pageCaptureImageUrl } from '../pageCaptureBrowser';
import { VideoVisitBrowser, durationLabel, exitReasonLabels, observationKindLabel, type VideoVisitRow } from '../videoVisitBrowser';
const browser = reactive(new VideoVisitBrowser(fetchVideoVisits));
const page = ref(1), platform = ref<''|'wechat'|'douyin'>('');
const manager=reactive(new CaptureManagement(captureMutation('video-visits')));
const from=ref(''),to=ref(''),q=ref('');
const observation_kind=ref<''|'confirmed_video'|'unconfirmed_feed'>(''),complete=ref<''|'true'|'false'>(''),exit_reason=ref<''|import('../videoVisitBrowser').VideoExitReason>('');
const dialog = ref<HTMLDialogElement>();
const platformLabel = (value:string) => value==='wechat'?'微信':value==='douyin'?'抖音':'未知平台';
const time = (value:number|string|null) => value===null?'未知':new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false});
function closeImage(){dialog.value?.close();browser.selected=null;}
async function load(){
 closeImage();const query={userId:authenticated.value?currentUserId.value:'',page:page.value,platform:platform.value,from:from.value,to:to.value,q:q.value,observation_kind:observation_kind.value,complete:complete.value,exit_reason:exit_reason.value};
 manager.setScope(query);manager.setRows([]);await browser.load(query);
  if(!browser.error && !browser.rows.length && browser.total>0 && page.value>Math.ceil(browser.total/20)){page.value=Math.ceil(browser.total/20);return;}
  manager.setRows(browser.rows);
}
watch([currentUserId,authenticated,platform,from,to,q,observation_kind,complete,exit_reason],()=>{if(page.value!==1)page.value=1;else void load();},{immediate:true,flush:'sync'});
watch(page,load,{flush:'sync'});
function showImage(row:VideoVisitRow){browser.select(row,'first');if(browser.selected)dialog.value?.showModal();}
onBeforeUnmount(()=>{closeImage();browser.invalidate();manager.invalidate();});
</script>
<template>
  <section class="video-visits">
    <h2>视频与信息流停留</h2>
    <p class="hint">观看时长按进入视频到划走、退出或锁屏的前台停留计算，不是播放时长或视频长度；返回同一视频另记一次。未确认单条视频时，仅展示信息流页面停留。</p>
    <p class="hint">点击首帧可放大。快速划过或截图失败可能缺图；异常中断时保留记录并标明未知时间。时间均为北京时间。</p>
    <div class="filters">
      <label>应用 <select v-model="platform" aria-label="应用"><option value="">全部</option><option value="wechat">微信</option><option value="douyin">抖音</option></select></label>
      <label>记录类型 <select v-model="observation_kind" aria-label="记录类型"><option value="">全部</option><option value="confirmed_video">已确认视频</option><option value="unconfirmed_feed">信息流页面停留</option></select></label>
      <label>结束状态 <select v-model="complete" aria-label="结束状态"><option value="">全部</option><option value="true">完整结束</option><option value="false">不完整</option></select></label>
      <label>结束原因 <select v-model="exit_reason" aria-label="结束原因"><option value="">全部</option><option v-for="(label,value) in exitReasonLabels" :key="value" :value="value">{{ label }}</option></select></label>
      <label>开始日期 <input v-model="from" type="date" aria-label="开始日期"></label>
      <label>结束日期 <input v-model="to" type="date" aria-label="结束日期"></label>
      <label>名称或备注 <input v-model.lazy="q" type="search" aria-label="名称或备注" placeholder="输入后回车搜索"></label>
      <button :disabled="browser.loading || !currentUserId || !authenticated" @click="load">刷新</button><span>共 {{ browser.total }} 条停留记录</span>
    </div>
    <CaptureManagementControls :manager="manager" :enabled="!!currentUserId && authenticated && !browser.loading" :row-count="browser.rows.length" @refresh="load" />
    <p v-if="!authenticated" class="empty">请登录后查看。</p>
    <p v-else-if="!currentUserId" class="empty">请先在顶部选择手机。</p>
    <p v-else-if="browser.error" role="alert" class="error">{{ browser.error }} <button @click="load">重试</button></p>
    <p v-else-if="browser.loading" role="status">正在加载…</p>
    <p v-else-if="!browser.rows.length" class="empty">当前筛选暂无已入库的停留记录。不能据此判断手机未采集或上传失败。</p>
    <div class="cards">
      <article v-for="row in browser.rows" :key="`${browser.userId}:${row.id}`">
        <div class="details">
          <h3 v-if="row.title">{{ row.title }}</h3><p v-if="row.note" class="record-note">{{ row.note }}</p>
          <span class="badge">{{ platformLabel(row.platform) }}</span> <strong>{{ observationKindLabel(row.observation_kind) }}</strong>
          <p class="duration">{{ row.observation_kind==='confirmed_video'?'观看时长':'页面停留' }}：{{ durationLabel(row.duration_ms) }}</p>
          <time>{{ row.observation_kind==='confirmed_video'?'观看开始':'停留开始' }}：{{ time(row.entered_at) }}</time>
          <time class="end-time">{{ row.observation_kind==='confirmed_video'?'观看结束':'停留结束' }}：{{ time(row.ended_at) }}</time>
          <p class="ending"><span v-if="!row.complete" class="incomplete">不完整记录 · </span>结束原因：{{ exitReasonLabels[row.exit_reason] || '未知' }}</p>
        </div>
        <div class="images">
          <div class="image-slot">
            <strong>首帧</strong>
            <p v-if="!row.first_image_id" class="empty">首帧缺失</p>
            <template v-for="id in row.first_image_id ? [row.first_image_id] : []" :key="id">
              <div v-if="browser.failedImages.includes(`${row.id}:${id}`)" class="image-error">图片加载失败。<button @click="browser.retryImage(row,id)">重试图片</button></div>
              <button v-else class="preview" aria-label="查看首帧" @click="showImage(row)"><img :src="pageCaptureImageUrl(id,browser.userId)" alt="停留首帧" loading="lazy" @error="browser.imageFailed(row,id)"></button>
            </template>
          </div>
        </div>
        <CaptureRecordActions :manager="manager" :row="row" />
      </article>
    </div>
    <nav v-if="browser.total>20" aria-label="停留记录分页"><button :disabled="page<=1 || browser.loading" @click="page--">上一页</button><span>{{ page }} / {{ Math.ceil(browser.total/20) }}</span><button :disabled="page*20>=browser.total || browser.loading" @click="page++">下一页</button></nav>
    <dialog ref="dialog" aria-label="停留记录原图" @cancel.prevent="closeImage" @click="($event.target===dialog) && closeImage()">
      <template v-for="selectedImage in browser.selected ? [browser.selected] : []" :key="`${browser.userId}:${selectedImage.row.id}:${selectedImage.imageId}`">
        <header><strong>{{ platformLabel(selectedImage.row.platform) }} · 首帧</strong><button @click="closeImage">关闭</button></header>
        <div v-if="browser.failedImages.includes(`${selectedImage.row.id}:${selectedImage.imageId}`)" class="image-error">图片加载失败。<button @click="browser.retryImage(selectedImage.row,selectedImage.imageId)">重试图片</button></div>
        <img v-else :src="pageCaptureImageUrl(selectedImage.imageId,browser.userId)" alt="停留记录原图" @error="browser.imageFailed(selectedImage.row,selectedImage.imageId)">
      </template>
    </dialog>
  </section>
</template>

<style scoped>
.video-visits{max-width:1100px;margin:auto;padding:24px;color:#263248}.hint,.empty{color:#64748b;line-height:1.7}.filters,nav{display:flex;align-items:center;flex-wrap:wrap;gap:14px;margin:20px 0}button,select,input{border:1px solid #cbd5e1;border-radius:6px;padding:8px 12px;background:white;color:inherit}button{cursor:pointer}button:disabled{opacity:.5;cursor:default}.cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(min(280px,100%),1fr));gap:18px}article{border:1px solid #e2e8f0;border-radius:12px;overflow:hidden;background:white}.preview{display:block;width:100%;padding:0;border:0;border-radius:0;background:#f1f5f9}.preview img{display:block;width:100%;height:260px;object-fit:contain}.details{padding:16px}.details h3{margin-bottom:8px;font-size:16px;overflow-wrap:anywhere}.record-note{white-space:pre-wrap;overflow-wrap:anywhere;margin-bottom:12px;font-size:13px;color:#64748b}.badge{font-size:12px;color:#2563eb;background:#eff6ff;padding:4px 8px;border-radius:4px}time,small{display:block;margin:10px 0;font-size:12px;color:#64748b}nav{justify-content:center}.error,.image-error{color:#b42318}.image-error{padding:24px;display:flex;gap:12px;align-items:center;flex-wrap:wrap}dialog{position:fixed;inset:0;margin:auto;width:fit-content;max-width:min(1000px,95vw);max-height:95vh;border:0;border-radius:12px;padding:16px}dialog::backdrop{background:#0009}dialog header{display:flex;justify-content:space-between;align-items:center;gap:20px;margin-bottom:12px}dialog img{display:block;max-width:100%;max-height:80vh;object-fit:contain;margin:auto}
.images{padding:0 16px 16px}.image-slot{min-width:0}.image-slot>strong{display:block;margin-bottom:8px}.duration{font-size:18px;font-weight:600;margin:16px 0}.end-time{font-size:14px;font-weight:600;color:#263248}.ending{font-size:13px;color:#263248;margin-top:12px}.incomplete{color:#b45309}
</style>

<style>
/* 仅本页窄屏沿用素材页的顶部导航布局，不改变其他后台页面。 */
@media (max-width:760px) {
  .layout:has(.video-visits) { flex-direction:column; }
  .layout:has(.video-visits)>.sidebar { position:static; width:100%; height:auto; padding:8px 0; }
  .layout:has(.video-visits) .sidebar>.logo { display:none; }
  .layout:has(.video-visits) .sidebar>.current-user { display:inline-flex; width:calc(50% - 16px); margin:4px 6px; vertical-align:middle; }
  .layout:has(.video-visits) .sidebar-nav { display:flex; overflow-x:auto; gap:5px; align-items:flex-start; padding:6px 8px; }
  .layout:has(.video-visits) .nav-group { width:130px; flex-shrink:0; }
  .layout:has(.video-visits) .content { min-width:0; padding:12px; }
  .video-visits { min-width:0; padding:8px !important; }
  .video-visits .filters label { display:flex; flex-wrap:wrap; gap:6px; max-width:100%; }
  .video-visits .filters select { max-width:100%; }
}
</style>
