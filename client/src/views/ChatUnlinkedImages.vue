<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { CaptureManagement } from '../captureManagement';
import { captureMutation } from '../api/captureManagement';
import { currentUserId } from '../api';
import { authenticated, dashboardFetch } from '../auth';

interface ImageRow { id: string; url: string; received_at: string; byte_size: number; width: number | null; height: number | null }
const rows = ref<ImageRow[]>([]), total = ref(0), page = ref(1), pageSize = 24;
const from = ref(''), to = ref(''), loading = ref(false), error = ref(''), owner = ref('');
const failed = ref<string[]>([]), selected = ref<ImageRow | null>(null), dialog = ref<HTMLDialogElement>();
const pages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)));
const selectedIndex = computed(() => rows.value.findIndex(row => row.id === selected.value?.id));
const manager = reactive(new CaptureManagement(captureMutation('chat/unlinked-images')));
const confirmation = ref<HTMLDialogElement>();
const isConfirming = computed(() => manager.pendingDelete.length > 0 || !!manager.cleanup);
function cancelManagement() { if (!manager.busy) { manager.pendingDelete = []; manager.cleanup = null; } }
const disabled = computed(() => loading.value || manager.busy || !authenticated.value || !currentUserId.value);
async function complete(action: Promise<boolean>) { if (await action) await load(); }
watch(isConfirming, async (open, _old, onCleanup) => {
  if (open) {
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    onCleanup(() => { document.body.style.overflow = overflow; });
  }
  await nextTick();
  if (isConfirming.value) { if (!confirmation.value?.open) confirmation.value?.showModal(); }
  else confirmation.value?.close();
});
let requestId = 0;
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false });
const size = (bytes: number) => bytes >= 1_048_576 ? `${(bytes / 1_048_576).toFixed(1)} MB` : `${Math.ceil(bytes / 1024)} KB`;
function imageUrl(row: ImageRow) { return `${row.url}?${new URLSearchParams({ user_id: owner.value })}`; }
function closeImage() { dialog.value?.close(); selected.value = null; }
function showImage(row: ImageRow) { selected.value = row; dialog.value?.showModal(); }
function moveImage(delta: number) { const row = rows.value[selectedIndex.value + delta]; if (row) selected.value = row; }
async function load() {
  const id = ++requestId, userId = currentUserId.value;
  manager.setScope({ userId: authenticated.value ? userId : '', page: page.value, from: from.value, to: to.value });
  manager.setRows([]);
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
    rows.value = result.images; manager.setRows(rows.value);
  } catch (e) { if (id === requestId) error.value = e instanceof Error ? e.message : '加载失败，请重试'; }
  finally { if (id === requestId) loading.value = false; }
}
watch([currentUserId, authenticated, from, to], () => { if (page.value !== 1) page.value = 1; else void load(); }, { immediate: true, flush: 'sync' });
watch(page, () => void load(), { flush: 'sync' });
onBeforeUnmount(() => { requestId++; manager.invalidate(); confirmation.value?.close(); closeImage(); });
</script>

<template>
  <section class="unlinked-images">
    <header class="page-heading"><h2>未关联图片</h2><span class="count">{{ total }} 张图片</span></header>
    <p class="hint">当前手机已上传、尚未关联聊天记录的图片。来源应用、会话和原始采集时间尚未关联，不能据此认定图片来自微信或某次聊天。</p>
    <p class="hint">按服务器接收时间倒序展示（北京时间）；聊天关联完成后，刷新会自动移出此列表。</p>
    <div class="filters">
      <label>接收开始日期 <input v-model="from" type="date" aria-label="接收开始日期"></label>
      <label>接收结束日期 <input v-model="to" type="date" aria-label="接收结束日期"></label>
      <button :disabled="disabled" @click="load">刷新</button>
      <RouterLink to="/chat-capture">查看聊天记录</RouterLink>
    </div>
    <div class="actions" aria-label="图片清理">
      <div class="selection-actions">
      <button :disabled="disabled || !rows.length" @click="manager.selectAll()">全选当前页</button>
      <button :disabled="disabled || !manager.selectedIds.length" @click="manager.cancelSelection()">取消选择</button>
      <button class="danger" :disabled="disabled || !manager.selectedIds.length" @click="manager.requestDelete()">删除所选（{{ manager.selectedIds.length }}）</button>
      </div>
      <div class="retention-actions"><span>清理旧图片</span>
      <button v-for="days in ([1, 7, 30] as const)" :key="days" :disabled="disabled" @click="manager.previewCleanup(days)">保留 {{ days }} 天</button>
      </div>
    </div>
    <p class="hint">保留按钮清理当前手机、当前日期筛选范围内超过最近 N × 24 小时的图片，包含所有分页；以接收时间计算，先预览再确认。仅删除后台当前副本，手机后续补传可能重新出现。</p>
    <p v-if="manager.busy && !isConfirming" class="notice" role="status">正在获取清理预览…</p>
    <p v-if="manager.error && !isConfirming" class="notice error" role="alert">{{ manager.error }}</p>
    <p v-if="manager.message && !isConfirming" class="notice success" role="status">{{ manager.message }}</p>
    <dialog ref="confirmation" class="confirmation" role="alertdialog" aria-labelledby="unlinked-confirm-title"
      aria-describedby="unlinked-confirm-description" :aria-busy="manager.busy" @cancel.prevent="cancelManagement"
      @click="($event.target === confirmation) && cancelManagement()">
      <template v-if="isConfirming">
        <header class="confirm-heading"><span class="warning-icon" aria-hidden="true">!</span>
          <h3 id="unlinked-confirm-title">{{ manager.pendingDelete.length ? '删除确认' : '清理确认' }}</h3>
          <button class="close-confirm" aria-label="关闭删除确认" :disabled="manager.busy" @click="cancelManagement">×</button>
        </header>
        <div class="confirm-body">
          <p v-if="manager.pendingDelete.length" id="unlinked-confirm-description">将删除当前手机的 <strong>{{ manager.pendingDelete.length }} 张</strong>未关联图片及无其他引用的文件，此操作无法撤销。</p>
          <p v-else-if="manager.cleanup" id="unlinked-confirm-description">当前手机、当前日期筛选范围内，接收时间早于 <strong>{{ time(manager.cleanup.cutoff) }}</strong>（北京时间）的 <strong>{{ manager.cleanup.total }} 张图片</strong>将被清理。已关联或已变更的图片会跳过；此操作无法撤销。</p>
          <p v-if="manager.error" class="notice error" role="alert">{{ manager.error }}</p>
          <p v-if="manager.busy || manager.cleanup?.offset" class="notice" role="status">{{ manager.pendingDelete.length ? '正在删除，请稍候…' : `已处理 ${manager.cleanup?.offset || 0} / ${manager.cleanup?.total || 0} 张` }}</p>
        </div>
        <footer class="confirm-actions">
          <button v-if="manager.cleanup && manager.busy" @click="manager.stopCleanup()">停止后续批次</button>
          <button v-else autofocus :disabled="manager.busy" @click="cancelManagement">取消</button>
          <button v-if="manager.pendingDelete.length" class="danger solid" :disabled="manager.busy" @click="complete(manager.confirmDelete())">{{ manager.busy ? '正在删除…' : '确认删除' }}</button>
          <button v-else-if="manager.cleanup" class="danger solid" :disabled="manager.busy || !manager.cleanup.total" @click="complete(manager.confirmCleanup())">{{ manager.busy ? '正在清理…' : manager.cleanup.offset ? '继续清理' : '确认清理' }}</button>
        </footer>
      </template>
    </dialog>
    <p v-if="!authenticated">请登录后查看。</p>
    <p v-else-if="!currentUserId">请先在顶部选择手机。</p>
    <p v-else-if="error" role="alert">{{ error }} <button @click="load">重试</button></p>
    <p v-else-if="loading" role="status">正在加载…</p>
    <p v-else-if="!rows.length" class="empty">当前范围没有未关联图片。</p>
    <div class="cards">
      <article v-for="row in rows" :key="`${owner}:${row.id}`" :class="{ checked: manager.selectedIds.includes(row.id) }">
        <div v-if="failed.includes(row.id)" class="image-error">图片加载失败 <button @click="failed = failed.filter(id => id !== row.id)">重试图片</button></div>
        <button v-else class="preview" :aria-label="`查看图片 ${row.id}`" @click="showImage(row)">
          <img :src="imageUrl(row)" alt="未关联图片" loading="lazy" @error="failed.push(row.id)">
        </button>
        <div class="card-actions">
          <label><input type="checkbox" :aria-label="`选择图片 ${row.id}`" :checked="manager.selectedIds.includes(row.id)" :disabled="disabled" @change="manager.toggle(row.id)">{{ manager.selectedIds.includes(row.id) ? '已选择' : '选择图片' }}</label>
          <button class="danger" :aria-label="`删除图片 ${row.id}`" :disabled="disabled" @click="manager.requestDelete(row.id)">删除</button>
        </div>
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
.page-heading { margin-bottom: 14px; }
h2 { font-size: 22px; letter-spacing: -.3px; }
.count { padding: 5px 10px; border-radius: 20px; background: #e9edff; color: #4451c9; font-size: 13px; font-weight: 600; }
.hint { color: #64748b; line-height: 1.7; font-size: 13px; margin: 6px 0; }
.filters, nav, header, .actions, .card-actions, .selection-actions, .retention-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.filters { margin: 20px 0 12px; padding: 16px; background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; }
.filters label { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; font-size: 13px; color: #475569; }
.filters a { margin-left: auto; color: #4451e8; font-size: 13px; text-decoration: none; }
.filters a:hover { text-decoration: underline; }
input[type=date] { padding: 9px 10px; min-height: 40px; border: 1px solid #cbd5e1; border-radius: 8px; max-width: 100%; background: #fff; color: #334155; }
button:not(.preview) { min-height: 40px; padding: 9px 14px; border: 1px solid #dce2ec; border-radius: 8px; background: #fff; color: #475569; font: inherit; font-size: 13px; font-weight: 500; line-height: 1.4; cursor: pointer; transition: background .15s, border-color .15s; }
button:not(.preview):hover:not(:disabled) { background: #eef2ff; border-color: #a5b4fc; color: #373eac; }
button:not(.preview):disabled { opacity: .45; cursor: not-allowed; }
button:focus-visible, input:focus-visible, a:focus-visible { outline: 3px solid #a5b4fc; outline-offset: 3px; }
button.danger { color: #b42332; border-color: #f2c5cb; background: #fff5f5; }
button.danger:hover:not(:disabled) { color: #9f1b2b; border-color: #e4939d; background: #ffe8eb; }
button.solid { background: #c93143; border-color: #c93143; color: white; }
button.solid:hover:not(:disabled) { background: #ab2636; border-color: #ab2636; color: white; }
.actions { justify-content: space-between; padding: 14px 16px; border: 1px solid #e2e8f0; border-radius: 12px; background: #fff; margin: 12px 0; }
.retention-actions > span { color: #64748b; font-size: 12px; }
.notice { margin: 12px 0; padding: 12px 14px; border-radius: 8px; background: #eef2ff; color: #4451b5; line-height: 1.6; font-size: 14px; }
.notice.error { color: #b42332; background: #fff1f2; border: 1px solid #fecdd3; }
.notice.success { color: #166534; background: #ecfdf3; border: 1px solid #bbf7d0; }
.cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(240px, 100%), 1fr)); gap: 16px; margin-top: 20px; }
article { min-width: 0; border: 1px solid #e2e8f0; border-radius: 12px; overflow: hidden; background: white; transition: border-color .15s, box-shadow .15s; }
article.checked { border-color: #6675ea; box-shadow: 0 0 0 2px #6675ea30; background: #fafbff; }
.preview { display: block; width: 100%; height: 240px; padding: 8px; border: 0; background: #f1f5f9; cursor: zoom-in; }
.preview img { width: 100%; height: 100%; object-fit: contain; }
.card-actions { padding: 12px 14px 0; justify-content: space-between; }
.card-actions label { display: flex; align-items: center; gap: 8px; min-height: 40px; cursor: pointer; font-size: 13px; color: #475569; }
input[type=checkbox] { width: 18px; height: 18px; accent-color: #4451e8; cursor: pointer; }
.details { display: grid; gap: 8px; padding: 14px; overflow-wrap: anywhere; }
.details strong { font-size: 14px; }
.details time, small { color: #64748b; font-size: 13px; }
.image-error { padding: 25px; }
[role=alert] { color: #b91c1c; }
nav { justify-content: center; margin: 20px 0; }
dialog { margin: auto; width: min(1100px, 94vw); max-width: 94vw; max-height: 94dvh; box-sizing: border-box; border: 0; border-radius: 14px; padding: 16px; box-shadow: 0 24px 80px #0f172a40; }
dialog::backdrop { background: #0f172a99; }
dialog header { justify-content: space-between; }
dialog p { color: #64748b; font-size: 13px; line-height: 1.7; margin: 10px 0; }
dialog > img { display: block; max-width: 100%; max-height: 72vh; object-fit: contain; margin: auto; }
dialog.confirmation { width: min(500px, calc(100vw - 32px)); max-height: calc(100dvh - 32px); padding: 0; color: #334155; }
.confirm-heading { padding: 20px 22px 12px; flex-wrap: nowrap; }
.confirm-heading h3 { flex: 1; font-size: 18px; }
.warning-icon { width: 32px; height: 32px; display: grid; place-items: center; flex-shrink: 0; border-radius: 50%; background: #fff1f2; color: #c93143; font-size: 20px; font-weight: 700; }
button.close-confirm { padding: 0; min-height: 36px; width: 36px; border: 0; font-size: 24px; background: transparent; }
.confirm-body { padding: 0 22px 20px; }
.confirm-body p { margin: 8px 0 0; font-size: 14px; overflow-wrap: anywhere; }
.confirm-body strong { color: #334155; }
.confirm-actions { display: flex; justify-content: flex-end; gap: 10px; padding: 16px 22px; border-top: 1px solid #e8edf3; background: #f8fafc; }
@media (max-width: 600px) {
  .filters { align-items: stretch; padding: 12px; } .filters label { width: 100%; justify-content: space-between; }
  .filters a { align-self: center; } .actions { padding: 12px; gap: 14px; } .selection-actions, .retention-actions { gap: 8px; }
  .retention-actions > span { width: 100%; } button:not(.preview) { min-height: 44px; padding: 10px 12px; }
  dialog { padding: 10px; } .confirm-heading { padding: 18px 16px 8px; } .confirm-body { padding: 0 16px 16px; } .confirm-actions { padding: 14px 16px; }
}
</style>
