<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue';
import { api, currentUserId, navigationImageUrl, type NavigationRecordRow } from '../api';
import { authenticated } from '../auth';

const rows = ref<NavigationRecordRow[]>([]), total = ref(0), page = ref(1), platform = ref('');
const loading = ref(false), error = ref(''), selected = ref<NavigationRecordRow | null>(null);
const dialog = ref<HTMLDialogElement>();
let generation = 0;
function closeImage() { dialog.value?.close(); selected.value = null; }
async function load() {
  const version = ++generation;
  rows.value = []; total.value = 0; error.value = ''; closeImage(); loading.value = false;
  if (!currentUserId.value || !authenticated.value) return;
  loading.value = true;
  try {
    const result = await api.navigationRecords(page.value, platform.value);
    if (version === generation) { rows.value = result.records; total.value = result.total; }
  } catch (e) { if (version === generation) error.value = e instanceof Error ? e.message : '加载失败'; }
  finally { if (version === generation) loading.value = false; }
}
watch([currentUserId, authenticated, platform], () => { if (page.value !== 1) page.value = 1; else void load(); }, { immediate: true, flush: 'sync' });
watch(page, () => { void load(); }, { flush: 'sync' });
function showImage(row: NavigationRecordRow) { selected.value = row; dialog.value?.showModal(); }
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false });
const name = (value: string) => value === 'amap' ? '高德地图' : '百度地图';
onBeforeUnmount(() => { generation++; closeImage(); });
</script>

<template>
  <section class="navigation-records">
    <h2>导航记录</h2>
    <p class="hint">查看选好路线时的总览截图和起终点。记录不表示已经开始导航或到达；新版手机同一天同一地图相同起终点只记一次。</p>
    <div class="filters">
      <label>地图来源 <select v-model="platform"><option value="">全部</option><option value="amap">高德地图</option><option value="baidu">百度地图</option></select></label>
      <button :disabled="loading" @click="load">刷新</button><span>共 {{ total }} 条</span>
    </div>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-else-if="loading" role="status">正在加载…</p>
    <p v-else-if="!rows.length" class="empty">暂无导航记录。在手机「设置 → 其他」开启导航记录及个人数据同步，并开启无障碍服务后在百度或高德选好路线，无需点击开始导航；记录会在连接 Wi-Fi 且熄屏后补传。</p>
    <div class="cards">
      <article v-for="row in rows" :key="row.id">
        <button class="preview" :aria-label="`查看${row.origin}到${row.destination}的路线图`" @click="showImage(row)">
          <img :src="navigationImageUrl(row.id, currentUserId)" :alt="`${row.origin} → ${row.destination}`" loading="lazy">
        </button>
        <div class="details"><span class="badge">{{ name(row.platform) }}</span><time>{{ time(row.overview_at) }}</time>
          <p><span class="endpoint">起</span>{{ row.origin }}</p><p><span class="endpoint destination">终</span>{{ row.destination }}</p>
          <button @click="showImage(row)">查看原图</button>
        </div>
      </article>
    </div>
    <nav v-if="total > 20" aria-label="导航记录分页"><button :disabled="page <= 1 || loading" @click="page--">上一页</button><span>{{ page }} / {{ Math.ceil(total / 20) }}</span><button :disabled="page * 20 >= total || loading" @click="page++">下一页</button></nav>
    <dialog ref="dialog" @close="selected = null" @click="($event.target === dialog) && closeImage()">
      <template v-if="selected"><header><strong>{{ selected.origin }} → {{ selected.destination }}</strong><button @click="closeImage">关闭</button></header>
        <img :src="navigationImageUrl(selected.id, currentUserId)" :alt="`${selected.origin}到${selected.destination}的路线总览`">
      </template>
    </dialog>
  </section>
</template>

<style scoped>
.navigation-records{max-width:1100px;margin:auto;padding:24px;color:#263248}.hint,.empty{color:#64748b;line-height:1.7}.filters,nav{display:flex;align-items:center;gap:14px;margin:20px 0}button,select{border:1px solid #cbd5e1;border-radius:6px;padding:8px 12px;background:white;color:inherit}button{cursor:pointer}button:disabled{opacity:.5;cursor:default}.cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:18px}article{border:1px solid #e2e8f0;border-radius:12px;overflow:hidden;background:white}.preview{display:block;width:100%;padding:0;border:0;border-radius:0;background:#f1f5f9}.preview img{display:block;width:100%;height:250px;object-fit:contain}.details{padding:16px}.badge{font-size:12px;color:#2563eb;background:#eff6ff;padding:4px 8px;border-radius:4px}time{display:block;margin-top:10px;font-size:12px;color:#64748b}.endpoint{display:inline-block;margin-right:10px;color:#16834a}.destination{color:#dc4b3e}.details p{overflow-wrap:anywhere}nav{justify-content:center}.error{color:#b42318}dialog{max-width:min(900px,95vw);max-height:95vh;border:0;border-radius:12px;padding:16px}dialog::backdrop{background:#0009}dialog header{display:flex;justify-content:space-between;align-items:center;gap:20px;margin-bottom:12px}dialog img{display:block;max-width:100%;max-height:80vh;object-fit:contain;margin:auto}
</style>
