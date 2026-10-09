<script setup lang="ts">
import { reactive, ref, watch, onBeforeUnmount } from 'vue';
import { currentUserId } from '../api';
import { authenticated } from '../auth';
import { fetchPageCaptures } from '../api/pageCaptures';
import { PageCaptureBrowser, pageCaptureImageUrl, pageKindLabels, type PageCaptureKind, type PageCaptureRow } from '../pageCaptureBrowser';

const browser = reactive(new PageCaptureBrowser(fetchPageCaptures));
const page = ref(1), platform = ref<'' | 'wechat' | 'douyin'>(''), kind = ref<'' | PageCaptureKind>('');
const dialog = ref<HTMLDialogElement>();
const platformLabel = (value: string) => value === 'wechat' ? '微信' : value === 'douyin' ? '抖音' : '未知平台';
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false });
function closeImage() { dialog.value?.close(); browser.selected = null; }
function load() {
  closeImage();
  void browser.load({ userId: authenticated.value ? currentUserId.value : '', page: page.value, platform: platform.value, kind: kind.value });
}
watch([currentUserId, authenticated, platform, kind], () => { if (page.value !== 1) page.value = 1; else load(); }, { immediate: true, flush: 'sync' });
watch(page, load, { flush: 'sync' });
function showImage(row: PageCaptureRow) { browser.select(row); if (browser.selected) dialog.value?.showModal(); }
onBeforeUnmount(() => { closeImage(); browser.invalidate(); });
</script>

<template>
  <section class="page-captures">
    <h2>应用页面</h2>
    <p class="hint">微信、抖音的非聊天页面单独保存，不参与联系人会话合并。这里仅展示已成功入库的截图；没有记录不代表没有使用应用。</p>
    <p class="hint">信息流截图不等于已确认的视频，当前不显示视频首尾或观看时长。支付相关截图也不代表后台已核实交易结果。</p>
    <div class="filters">
      <label>应用 <select v-model="platform" aria-label="应用"><option value="">全部</option><option value="wechat">微信</option><option value="douyin">抖音</option></select></label>
      <label>页面类型 <select v-model="kind" aria-label="页面类型"><option value="">全部</option><option v-for="(label, value) in pageKindLabels" :key="value" :value="value">{{ label }}</option></select></label>
      <button :disabled="browser.loading || !currentUserId || !authenticated" @click="load">刷新</button>
      <span>共 {{ browser.total }} 张</span>
    </div>
    <p v-if="!authenticated" class="empty">请登录后查看。</p>
    <p v-else-if="!currentUserId" class="empty">请先在顶部选择手机。</p>
    <p v-else-if="browser.error" role="alert" class="error">{{ browser.error }}</p>
    <p v-else-if="browser.loading" role="status">正在加载…</p>
    <p v-else-if="!browser.rows.length" class="empty">当前筛选暂无已入库页面截图。此页面不能单凭空列表判断手机未截图或上传失败。</p>
    <div class="cards">
      <article v-for="row in browser.rows" :key="`${browser.userId}:${row.id}`">
        <div v-if="browser.failedImages.includes(row.id)" class="image-error" role="status">图片加载失败，记录仍保留。<button @click="browser.retryImage(row)">重试图片</button></div>
        <button v-else class="preview" :aria-label="`查看${platformLabel(row.platform)}${pageKindLabels[row.kind] || '页面'}原图`" @click="showImage(row)">
          <img :src="pageCaptureImageUrl(row.id, browser.userId)" :alt="pageKindLabels[row.kind] || '页面截图'" loading="lazy" @error="browser.imageFailed(row)">
        </button>
        <div class="details">
          <span class="badge">{{ platformLabel(row.platform) }}</span> <strong>{{ pageKindLabels[row.kind] || '未知页面' }}</strong>
          <time>采集：{{ time(row.captured_at) }}</time><time>入库：{{ time(row.received_at) }}</time>
          <small>{{ row.width }} × {{ row.height }} · 北京时间</small>
        </div>
      </article>
    </div>
    <nav v-if="browser.total > 20" aria-label="应用页面分页">
      <button :disabled="page <= 1 || browser.loading" @click="page--">上一页</button>
      <span>{{ page }} / {{ Math.ceil(browser.total / 20) }}</span>
      <button :disabled="page * 20 >= browser.total || browser.loading" @click="page++">下一页</button>
    </nav>
    <dialog ref="dialog" aria-label="页面截图原图" @cancel.prevent="closeImage" @click="($event.target === dialog) && closeImage()">
      <template v-for="selectedRow in browser.selected ? [browser.selected] : []" :key="`${browser.userId}:${selectedRow.id}`">
        <header><strong>{{ platformLabel(selectedRow.platform) }} · {{ pageKindLabels[selectedRow.kind] || '页面截图' }}</strong><button @click="closeImage">关闭</button></header>
        <div v-if="browser.failedImages.includes(selectedRow.id)" class="image-error">图片加载失败。<button @click="browser.retryImage(selectedRow)">重试图片</button></div>
        <img v-else :src="pageCaptureImageUrl(selectedRow.id, browser.userId)" alt="页面原图" @error="browser.imageFailed(selectedRow)">
      </template>
    </dialog>
  </section>
</template>

<style scoped>
.page-captures{max-width:1100px;margin:auto;padding:24px;color:#263248}.hint,.empty{color:#64748b;line-height:1.7}.filters,nav{display:flex;align-items:center;flex-wrap:wrap;gap:14px;margin:20px 0}button,select{border:1px solid #cbd5e1;border-radius:6px;padding:8px 12px;background:white;color:inherit}button{cursor:pointer}button:disabled{opacity:.5;cursor:default}.cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(min(280px,100%),1fr));gap:18px}article{border:1px solid #e2e8f0;border-radius:12px;overflow:hidden;background:white}.preview{display:block;width:100%;padding:0;border:0;border-radius:0;background:#f1f5f9}.preview img{display:block;width:100%;height:260px;object-fit:contain}.details{padding:16px}.badge{font-size:12px;color:#2563eb;background:#eff6ff;padding:4px 8px;border-radius:4px}time,small{display:block;margin:10px 0;font-size:12px;color:#64748b}nav{justify-content:center}.error,.image-error{color:#b42318}.image-error{padding:24px;display:flex;gap:12px;align-items:center;flex-wrap:wrap}dialog{position:fixed;inset:0;margin:auto;width:fit-content;max-width:min(1000px,95vw);max-height:95vh;border:0;border-radius:12px;padding:16px}dialog::backdrop{background:#0009}dialog header{display:flex;justify-content:space-between;align-items:center;gap:20px;margin-bottom:12px}dialog img{display:block;max-width:100%;max-height:80vh;object-fit:contain;margin:auto}
</style>

<style>
/* 仅本页窄屏沿用素材页的顶部导航布局，不改变其他后台页面。 */
@media (max-width:760px) {
  .layout:has(.page-captures) { flex-direction:column; }
  .layout:has(.page-captures)>.sidebar { position:static; width:100%; height:auto; padding:8px 0; }
  .layout:has(.page-captures) .sidebar>.logo { display:none; }
  .layout:has(.page-captures) .sidebar>.current-user { display:inline-flex; width:calc(50% - 16px); margin:4px 6px; vertical-align:middle; }
  .layout:has(.page-captures) .sidebar-nav { display:flex; overflow-x:auto; gap:5px; align-items:flex-start; padding:6px 8px; }
  .layout:has(.page-captures) .nav-group { width:130px; flex-shrink:0; }
  .layout:has(.page-captures) .content { min-width:0; padding:12px; }
  .page-captures { min-width:0; padding:8px !important; }
  .page-captures .filters label { display:flex; flex-wrap:wrap; gap:6px; max-width:100%; }
  .page-captures .filters select { max-width:100%; }
}
</style>
