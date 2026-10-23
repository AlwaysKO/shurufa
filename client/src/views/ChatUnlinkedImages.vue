<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { currentUserId } from '../api';
import { authenticated, dashboardFetch } from '../auth';

interface ImageRow { id: string; url: string; received_at: string; byte_size: number; width: number | null; height: number | null }
const rows = ref<ImageRow[]>([]), total = ref(0), page = ref(1), pageSize = 24;
const from = ref(''), to = ref(''), loading = ref(false), error = ref(''), owner = ref('');
const failed = ref<string[]>([]), selected = ref<ImageRow | null>(null), dialog = ref<HTMLDialogElement>();
const pages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)));
const selectedIndex = computed(() => rows.value.findIndex(row => row.id === selected.value?.id));
let requestId = 0;
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false });
const size = (bytes: number) => bytes >= 1_048_576 ? `${(bytes / 1_048_576).toFixed(1)} MB` : `${Math.ceil(bytes / 1024)} KB`;
function imageUrl(row: ImageRow) { return `${row.url}?${new URLSearchParams({ user_id: owner.value })}`; }
function closeImage() { dialog.value?.close(); selected.value = null; }
function showImage(row: ImageRow) { selected.value = row; dialog.value?.showModal(); }
function moveImage(delta: number) { const row = rows.value[selectedIndex.value + delta]; if (row) selected.value = row; }
async function load() {
  const id = ++requestId, userId = currentUserId.value;
  closeImage(); rows.value = []; total.value = 0; failed.value = []; error.value = ''; loading.value = false; owner.value = userId;
  if (!authenticated.value || !userId) return;
  loading.value = true;
  try {
    const query = new URLSearchParams({ user_id: userId, page: String(page.value), page_size: String(pageSize) });
    if (from.value) query.set('from', from.value); if (to.value) query.set('to', to.value);
    const response = await dashboardFetch(`/api/v1/dashboard/chat/unlinked-images?${query}`);
    if (!response.ok) throw Error(`未关联图片加载失败（${response.status}）`);
    const result = await response.json();
    if (id !== requestId) return;
    total.value = result.total;
    if (page.value > pages.value) { page.value = pages.value; return; }
    rows.value = result.images;
  } catch (e) { if (id === requestId) error.value = e instanceof Error ? e.message : '加载失败，请重试'; }
  finally { if (id === requestId) loading.value = false; }
}
watch([currentUserId, authenticated, from, to], () => { if (page.value !== 1) page.value = 1; else void load(); }, { immediate: true, flush: 'sync' });
watch(page, () => void load(), { flush: 'sync' });
onBeforeUnmount(() => { requestId++; closeImage(); });
</script>

<template>
  <section class="unlinked-images">
    <h2>未关联图片</h2>
    <p class="hint">当前手机已上传、尚未关联聊天记录的图片。来源应用、会话和原始采集时间尚未关联，不能据此认定图片来自微信或某次聊天。</p>
    <p class="hint">按服务器接收时间倒序展示（北京时间）；聊天关联完成后，刷新会自动移出此列表。</p>
    <div class="filters">
      <label>接收开始日期 <input v-model="from" type="date" aria-label="接收开始日期"></label>
      <label>接收结束日期 <input v-model="to" type="date" aria-label="接收结束日期"></label>
      <button :disabled="loading || !authenticated || !currentUserId" @click="load">刷新</button>
      <span>共 {{ total }} 张</span>
      <RouterLink to="/chat-capture">查看聊天记录</RouterLink>
    </div>
    <p v-if="!authenticated">请登录后查看。</p>
    <p v-else-if="!currentUserId">请先在顶部选择手机。</p>
    <p v-else-if="error" role="alert">{{ error }} <button @click="load">重试</button></p>
    <p v-else-if="loading" role="status">正在加载…</p>
    <p v-else-if="!rows.length" class="empty">当前范围没有未关联图片。</p>
    <div class="cards">
      <article v-for="row in rows" :key="`${owner}:${row.id}`">
        <div v-if="failed.includes(row.id)" class="image-error">图片加载失败 <button @click="failed = failed.filter(id => id !== row.id)">重试图片</button></div>
        <button v-else class="preview" :aria-label="`查看图片 ${row.id}`" @click="showImage(row)">
          <img :src="imageUrl(row)" alt="未关联图片" loading="lazy" @error="failed.push(row.id)">
        </button>
        <div class="details"><strong>未关联图片 #{{ row.id }}</strong>
          <time>接收：{{ time(row.received_at) }}</time>
          <small>{{ row.width && row.height ? `${row.width} × ${row.height} · ` : '' }}{{ size(row.byte_size) }}</small>
        </div>
      </article>
    </div>
    <nav v-if="total > pageSize" aria-label="未关联图片分页">
      <button :disabled="page <= 1 || loading" @click="page--">上一页</button><span>{{ page }} / {{ pages }}</span>
      <button :disabled="page >= pages || loading" @click="page++">下一页</button>
    </nav>
    <dialog ref="dialog" aria-label="未关联图片原图" @cancel.prevent="closeImage" @click="($event.target === dialog) && closeImage()"
      @keydown.left.prevent="moveImage(-1)" @keydown.right.prevent="moveImage(1)">
      <template v-if="selected">
        <header><strong>未关联图片 #{{ selected.id }}</strong><button aria-label="关闭原图" @click="closeImage">关闭</button></header>
        <p>接收：{{ time(selected.received_at) }}（北京时间）· 原始采集时间未知</p>
        <img :key="selected.id" :src="imageUrl(selected)" alt="未关联图片原图">
        <nav aria-label="本页图片浏览"><button :disabled="selectedIndex <= 0" @click="moveImage(-1)">上一张</button>
          <span>本页 {{ selectedIndex + 1 }} / {{ rows.length }}</span>
          <button :disabled="selectedIndex >= rows.length - 1" @click="moveImage(1)">下一张</button></nav>
      </template>
    </dialog>
  </section>
</template>

<style scoped>
.unlinked-images { min-width: 0; }
.hint { color: #64748b; line-height: 1.6; }
.filters, nav, header { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.filters { margin: 20px 0; }
.filters label { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
input { padding: 7px; border: 1px solid #cbd5e1; border-radius: 6px; max-width: 100%; }
.cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(240px, 100%), 1fr)); gap: 16px; }
article { min-width: 0; border: 1px solid #e2e8f0; border-radius: 10px; overflow: hidden; background: white; }
.preview { width: 100%; height: 240px; padding: 8px; border: 0; background: #f1f5f9; cursor: zoom-in; }
.preview img { width: 100%; height: 100%; object-fit: contain; }
.details { display: grid; gap: 8px; padding: 14px; overflow-wrap: anywhere; }
.details time, small { color: #64748b; font-size: 13px; }
.image-error { padding: 25px; }
[role=alert] { color: #b91c1c; }
nav { justify-content: center; margin: 20px 0; }
dialog { width: min(1100px, 94vw); max-width: 94vw; max-height: 94vh; box-sizing: border-box; border: 0; border-radius: 12px; padding: 16px; }
dialog::backdrop { background: #0f172ab8; }
dialog header { justify-content: space-between; }
dialog p { color: #64748b; font-size: 13px; }
dialog > img { display: block; max-width: 100%; max-height: 72vh; object-fit: contain; margin: auto; }
@media (max-width: 600px) { .filters { align-items: stretch; } .filters label { width: 100%; } dialog { padding: 10px; } }
</style>
