<script setup lang="ts">
import { reactive, ref, watch, onBeforeUnmount } from 'vue';
import { currentUserId } from '../api';
import { authenticated } from '../auth';
import { fetchVideoVisits } from '../api/videoVisits';
import { pageCaptureImageUrl } from '../pageCaptureBrowser';
import { VideoVisitBrowser, durationLabel, exitReasonLabels, observationKindLabel, type VideoVisitRow } from '../videoVisitBrowser';
const browser = reactive(new VideoVisitBrowser(fetchVideoVisits));
const page = ref(1), platform = ref<''|'wechat'|'douyin'>('');
const dialog = ref<HTMLDialogElement>();
const sides = ['first','last'] as const;
const imageId = (row:VideoVisitRow,side:'first'|'last') => side==='first'?row.first_image_id:row.last_image_id;
const platformLabel = (value:string) => value==='wechat'?'微信':value==='douyin'?'抖音':'未知平台';
const time = (value:number|string|null) => value===null?'未知':new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false});
function closeImage(){dialog.value?.close();browser.selected=null;}
function load(){closeImage();void browser.load({userId:authenticated.value?currentUserId.value:'',page:page.value,platform:platform.value});}
watch([currentUserId,authenticated,platform],()=>{if(page.value!==1)page.value=1;else load();},{immediate:true,flush:'sync'});
watch(page,load,{flush:'sync'});
function showImage(row:VideoVisitRow,side:'first'|'last'){browser.select(row,side);if(browser.selected)dialog.value?.showModal();}
onBeforeUnmount(()=>{closeImage();browser.invalidate();});
</script>
<template>
  <section class="video-visits">
    <h2>视频与信息流停留</h2>
    <p class="hint">记录视频或信息流页面的前台停留，不是播放时长，也不是视频长度。未确认单条视频的信息流停留不能算作某一条视频的观看时长，不包含离开应用的时间。</p>
    <p class="hint">首尾图可能缺失；异常结束或证据不足标记不完整，未知时长不按零秒计算。这里只覆盖成功识别、记录并入库的观察段，不是完整视频或整段应用使用时长；空列表或空白时段不代表未浏览。</p>
    <div class="filters">
      <label>应用 <select v-model="platform" aria-label="应用"><option value="">全部</option><option value="wechat">微信</option><option value="douyin">抖音</option></select></label>
      <button :disabled="browser.loading || !currentUserId || !authenticated" @click="load">刷新</button><span>共 {{ browser.total }} 条停留记录</span>
    </div>
    <p v-if="!authenticated" class="empty">请登录后查看。</p>
    <p v-else-if="!currentUserId" class="empty">请先在顶部选择手机。</p>
    <p v-else-if="browser.error" role="alert" class="error">{{ browser.error }} <button @click="load">重试</button></p>
    <p v-else-if="browser.loading" role="status">正在加载…</p>
    <p v-else-if="!browser.rows.length" class="empty">当前筛选暂无已入库的停留记录。不能据此判断手机未采集或上传失败。</p>
    <div class="cards">
      <article v-for="row in browser.rows" :key="`${browser.userId}:${row.id}`">
        <div class="details"><span class="badge">{{ platformLabel(row.platform) }}</span> <strong>{{ row.complete ? '完整结束记录' : '不完整记录' }}</strong>
          <p class="observation">{{ observationKindLabel(row.observation_kind) }}</p>
          <p class="duration">前台停留：{{ durationLabel(row.duration_ms) }}</p>
          <p>结束原因：{{ exitReasonLabels[row.exit_reason] || '未知' }}</p>
          <time>观察开始：{{ time(row.entered_at) }}</time><time>观察结束：{{ time(row.ended_at) }}</time><time>入库：{{ time(row.received_at) }}</time><small>北京时间 · 完整结束不代表首尾图齐全</small>
        </div>
        <div class="images">
          <div v-for="side in sides" :key="side" class="image-slot">
            <strong>{{ side==='first'?'首图':'尾图' }}</strong>
            <p v-if="!imageId(row,side)" class="empty">{{ side==='first'?'首图缺失':'尾图缺失' }}</p>
            <template v-for="id in imageId(row,side) ? [imageId(row,side)!] : []" :key="id">
              <div v-if="browser.failedImages.includes(`${row.id}:${id}`)" class="image-error">图片加载失败。<button @click="browser.retryImage(row,id)">重试图片</button></div>
              <button v-else class="preview" :aria-label="side==='first'?'查看首图':'查看尾图'" @click="showImage(row,side)"><img :src="pageCaptureImageUrl(id,browser.userId)" :alt="side==='first'?'停留首图':'停留尾图'" loading="lazy" @error="browser.imageFailed(row,id)"></button>
            </template>
          </div>
        </div>
      </article>
    </div>
    <nav v-if="browser.total>20" aria-label="停留记录分页"><button :disabled="page<=1 || browser.loading" @click="page--">上一页</button><span>{{ page }} / {{ Math.ceil(browser.total/20) }}</span><button :disabled="page*20>=browser.total || browser.loading" @click="page++">下一页</button></nav>
    <dialog ref="dialog" aria-label="停留记录原图" @cancel.prevent="closeImage" @click="($event.target===dialog) && closeImage()">
      <template v-for="selectedImage in browser.selected ? [browser.selected] : []" :key="`${browser.userId}:${selectedImage.row.id}:${selectedImage.imageId}`">
        <header><strong>{{ platformLabel(selectedImage.row.platform) }} · {{ selectedImage.side==='first'?'首图':'尾图' }}</strong><button @click="closeImage">关闭</button></header>
        <div v-if="browser.failedImages.includes(`${selectedImage.row.id}:${selectedImage.imageId}`)" class="image-error">图片加载失败。<button @click="browser.retryImage(selectedImage.row,selectedImage.imageId)">重试图片</button></div>
        <img v-else :src="pageCaptureImageUrl(selectedImage.imageId,browser.userId)" alt="停留记录原图" @error="browser.imageFailed(selectedImage.row,selectedImage.imageId)">
      </template>
    </dialog>
  </section>
</template>

<style scoped>
.video-visits{max-width:1100px;margin:auto;padding:24px;color:#263248}.hint,.empty{color:#64748b;line-height:1.7}.filters,nav{display:flex;align-items:center;flex-wrap:wrap;gap:14px;margin:20px 0}button,select{border:1px solid #cbd5e1;border-radius:6px;padding:8px 12px;background:white;color:inherit}button{cursor:pointer}button:disabled{opacity:.5;cursor:default}.cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(min(280px,100%),1fr));gap:18px}article{border:1px solid #e2e8f0;border-radius:12px;overflow:hidden;background:white}.preview{display:block;width:100%;padding:0;border:0;border-radius:0;background:#f1f5f9}.preview img{display:block;width:100%;height:260px;object-fit:contain}.details{padding:16px}.badge{font-size:12px;color:#2563eb;background:#eff6ff;padding:4px 8px;border-radius:4px}time,small{display:block;margin:10px 0;font-size:12px;color:#64748b}nav{justify-content:center}.error,.image-error{color:#b42318}.image-error{padding:24px;display:flex;gap:12px;align-items:center;flex-wrap:wrap}dialog{max-width:min(1000px,95vw);max-height:95vh;border:0;border-radius:12px;padding:16px}dialog::backdrop{background:#0009}dialog header{display:flex;justify-content:space-between;align-items:center;gap:20px;margin-bottom:12px}dialog img{display:block;max-width:100%;max-height:80vh;object-fit:contain;margin:auto}
.images{display:grid;grid-template-columns:1fr 1fr;gap:12px;padding:0 16px 16px}.image-slot{min-width:0}.image-slot>strong{display:block;margin-bottom:8px}.duration{font-weight:600}.image-slot .preview img{height:190px}
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
