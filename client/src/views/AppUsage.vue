<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { api, currentUserId, appName, type AppUsageData, type AppUsageDayData } from '../api';
// 输入框与显示统一北京时间，不依赖浏览器所在时区。
const beijingInput = (ms: number) => new Date(ms + 8 * 3600000).toISOString().slice(0, 16);
const to = ref(beijingInput(Date.now()));
const from = ref(beijingInput(Date.now() - 7 * 86400000));
const packageName = ref('');
const data = ref<AppUsageData | null>(null);
const loading = ref(false), error = ref('');
let version = 0, dayVersion = 0;
const timelineDay = ref(to.value.slice(0, 10));
const timeline = ref<AppUsageDayData | null>(null);
const timelineError = ref(''), timelineLoading = ref(false);
let activePackage = '';
let submittedQuery: { from: string; to: string; package_name?: string } | null = null;
async function loadDay() {
  const request = ++dayVersion;
  timeline.value = null; timelineError.value = '';
  if (!currentUserId.value) { timelineLoading.value = false; return; }
  timelineLoading.value = true;
  try {
    const result = await api.appUsageDay(timelineDay.value, activePackage || undefined);
    if (request === dayVersion) timeline.value = result;
  } catch (e) { if (request === dayVersion) timelineError.value = (e as Error).message; }
  finally { if (request === dayVersion) timelineLoading.value = false; }
}
const lanes = computed(() => {
  const groups = new Map<string, { key: string; label: string; color: string; records: AppUsageDayData['records'] }>();
  for (const r of timeline.value?.records ?? []) {
    const key = r.kind === 'gap' ? '__gap__' : r.package_name!;
    if (!groups.has(key)) {
      let hash = 0;
      for (const c of key) hash = (hash * 31 + c.charCodeAt(0)) | 0;
      groups.set(key, { key, label: r.kind === 'gap' ? '数据断档' : appName(r.package_name!, r.app_name), color: r.kind === 'gap' ? '#d89530' : `hsl(${Math.abs(hash) % 360} 60% 48%)`, records: [] });
    }
    groups.get(key)!.records.push(r);
  }
  return [...groups.values()];
});
const segmentStyle = (r: AppUsageDayData['records'][number], color: string) => ({
  left: `${(r.clipped_start_ms - timeline.value!.start_ms) / 86400000 * 100}%`,
  width: `${(r.clipped_end_ms - r.clipped_start_ms) / 86400000 * 100}%`, backgroundColor: color,
});
const duration = (ms: number) => {
  const s = Math.floor(ms / 1000);
  return `${Math.floor(s / 3600)}小时 ${Math.floor(s % 3600 / 60)}分 ${s % 60}秒`;
};
const time = (ms: number) => new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(ms);
const maxDay = computed(() => Math.max(1, ...(data.value?.daily.map(d => d.duration_ms) ?? [])));
const maxApp = computed(() => Math.max(1, ...(data.value?.apps.map(a => a.duration_ms) ?? [])));
const pages = computed(() => Math.max(1, Math.ceil((data.value?.total ?? 0) / 50)));
// 无页码表示显式提交；数字页码（包括返回第1页）仅翻动已提交的查询。
async function load(page?: number) {
  const submit = page === undefined;
  const request = ++version;
  data.value = null; error.value = '';
  if (submit) { dayVersion++; timeline.value = null; timelineLoading.value = false; }
  if (!currentUserId.value) { loading.value = false; return; }
  if (submit) {
    const start = Date.parse(`${from.value}:00+08:00`), end = Date.parse(`${to.value}:00+08:00`);
    if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start || end - start > 366 * 86400000) {
      loading.value = false; error.value = '请选择有效的起止时间，范围最多366天。'; return;
    }
    submittedQuery = { from: new Date(start).toISOString(), to: new Date(end).toISOString(), package_name: packageName.value.trim() || undefined };
    activePackage = submittedQuery.package_name ?? '';
    timelineDay.value = beijingInput(end - 1).slice(0, 10);
    void loadDay();
  }
  if (!submittedQuery) { loading.value = false; return; }
  const query = { ...submittedQuery, page: page ?? 1 };
  loading.value = true;
  try {
    const result = await api.appUsage(query);
    if (request === version) data.value = result;
  } catch (e) { if (request === version) error.value = (e as Error).message; }
  finally { if (request === version) loading.value = false; }
}
watch(currentUserId, () => { packageName.value = ''; void load(); }, { immediate: true, flush: 'sync' });
onBeforeUnmount(() => { version++; dayVersion++; });
const reasons: Record<string, string> = { switch: '切换应用', process_restart: '进程重启记录中断', resume: '切换应用', pause: '离开前台', lock: '锁屏', off: '熄屏', shutdown: '关机', startup: '启动', permission_lost: '权限中断', query_unavailable: '系统记录不可用', history_gap: '历史记录缺失', collection_paused: '采集暂停', clock_changed: '系统时间改变', reboot: '设备重启' };
</script>
<template>
  <section>
    <p class="note">全部 App 的前台使用记录，与输入法是否打开无关。仅统计已结束段；后台播放不计时。全部时间为北京时间，时长按查询范围裁剪，断档不计使用。</p>
    <form class="filters" @submit.prevent="load()">
      <label>开始 <input v-model="from" type="datetime-local" required /></label>
      <label>结束 <input v-model="to" type="datetime-local" required /></label>
      <label>App <input v-model="packageName" placeholder="包名（留空为全部）" /></label>
      <button type="submit" :disabled="loading">查询 / 刷新</button>
      <button type="button" @click="packageName = ''; load()">全部 App</button>
    </form>
    <p v-if="error" role="alert" class="empty">{{ error }}</p>
    <p v-else-if="loading" class="empty">加载中…</p>
    <template v-else-if="data">
      <p class="note">最近收到记录：{{ data.overview.last_received_at ? time(Date.parse(data.overview.last_received_at)) : '未收到记录' }}（设备整体最近入库时间，不代表采集持续正常或已全量同步）</p>
      <div class="summary">
        <div class="card"><h3>前台使用时长</h3><strong>{{ duration(data.overview.duration_ms) }}</strong></div>
        <div class="card"><h3>使用次数（已结束段）</h3><strong>{{ data.overview.count }}</strong></div>
        <div class="card"><h3>断档段数</h3><strong>{{ data.overview.gap_count }}</strong><p class="note">断档不代表未使用手机，筛选 App 时仍显示。</p></div>
      </div>
      <div class="charts">
        <div class="card"><h3>App 使用排行 <small>点击筛选</small></h3>
          <div v-if="!data.apps.length" class="empty">暂无使用数据</div>
          <button v-for="a in data.apps" :key="a.package_name" class="rank" @click="packageName = a.package_name; load()">
            <span>{{ appName(a.package_name, a.app_name) }} <small>{{ a.package_name }}</small></span>
            <span>{{ duration(a.duration_ms) }} · {{ a.count }}次</span>
            <meter :value="a.duration_ms" :max="maxApp" /></button>
        </div>
        <div class="card"><h3>每日趋势（跨天分摊）</h3><div v-if="!data.daily.length" class="empty">暂无使用数据</div>
          <div v-for="d in data.daily" :key="d.day" class="day"><span>{{ d.day }}</span><meter :value="d.duration_ms" :max="maxDay" /><span>{{ duration(d.duration_ms) }}</span></div>
        </div>
      </div>
      <div class="card">
        <h3>当天使用时间轴</h3>
        <div class="filters"><label>日期 <input v-model="timelineDay" type="date" @change="loadDay()" /></label><button @click="loadDay()">刷新当天</button></div>
        <p class="note">独立读取所选日期的北京时间 00:00–24:00，沿用已查询的 App 筛选，不受下方分页或查询起止时刻裁剪。仅包含已结束段；空白不代表确认未使用。悬停查看包名、起止与时长；区间支持键盘聚焦。</p>
        <p v-if="timelineError" role="alert">{{ timelineError }}</p>
        <p v-else-if="timelineLoading" class="empty">时间轴加载中…</p>
        <template v-else-if="timeline">
          <p v-if="timeline.truncated" role="alert" class="gap">当天共 {{ timeline.total }} 段，当前仅展示按开始时间排序的前 {{ timeline.limit }} 段，后续区段已截断；请缩小 App 范围或查询下方分页明细。</p>
          <p v-else class="note">当天共 {{ timeline.total }} 段（含断档），全部展示。</p>
          <div v-if="lanes.length" class="timeline-scroll">
            <div class="timeline-grid">
              <div class="axis"><span v-for="hour in [0, 4, 8, 12, 16, 20, 24]" :key="hour">{{ String(hour).padStart(2, '0') }}:00</span></div>
              <div v-for="lane in lanes" :key="lane.key" class="timeline-lane">
                <div class="lane-label" :title="lane.key">{{ lane.label }}</div>
                <div class="lane-track">
                  <button v-for="r in lane.records" :key="r.id" class="segment" :class="{ 'segment-gap': r.kind === 'gap' }" :style="segmentStyle(r, lane.color)"
                    :title="`${lane.label} ${r.package_name ?? ''}\n原始：${time(r.start_ms)} — ${time(r.end_ms)}\n当天：${time(r.clipped_start_ms)} — ${time(r.clipped_end_ms)}\n当天时长：${duration(r.duration_ms)}\n${reasons[r.end_reason] ?? r.end_reason}`"
                    :aria-label="`${lane.label} ${time(r.clipped_start_ms)} 至 ${time(r.clipped_end_ms)}，${duration(r.duration_ms)}`" />
                </div>
              </div>
            </div>
          </div>
          <p v-else class="empty">当天暂无记录。</p>
        </template>
      </div>
      <div class="card"><h3>使用时间段 / 断档 <small>共 {{ data.total }} 段</small></h3>
        <p class="note">起止时间保留原始值；“范围内时长”已裁剪。统计不受分页影响，次数指与查询范围相交的原始使用段。</p>
        <div class="table-scroll"><table><thead><tr><th>App / 类型</th><th>原始开始</th><th>原始结束</th><th>范围内时长</th><th>结束 / 断档原因</th></tr></thead>
          <tbody><tr v-for="r in data.records" :key="r.id" :class="{ gap: r.kind === 'gap' }">
            <td>{{ r.kind === 'gap' ? '⚠ 数据断档' : appName(r.package_name!, r.app_name) }}<small v-if="r.package_name">{{ r.package_name }}</small></td>
            <td>{{ time(r.start_ms) }}</td><td>{{ time(r.end_ms) }}</td><td>{{ duration(r.duration_ms) }}</td><td>{{ reasons[r.end_reason] ?? r.end_reason }}</td>
          </tr></tbody></table></div>
        <p v-if="!data.records.length" class="empty">暂无记录。请在手机开启应用使用记录并授予使用情况访问权限，切换 App 后刷新；不会导入授权前历史。</p>
        <div class="filters"><button :disabled="data.page <= 1" @click="load(data.page - 1)">上一页</button><span>{{ data.page }} / {{ pages }}</span><button :disabled="data.page >= pages" @click="load(data.page + 1)">下一页</button></div>
      </div>
    </template>
  </section>
</template>
<style scoped>
.note,small{color:var(--text-muted,#888);font-size:12px;line-height:1.8}small{display:block}.summary{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px}.charts{display:grid;grid-template-columns:1fr 1fr;gap:16px}.rank{background:#fff;border:1px solid #e5e7eb;border-radius:8px;padding:10px;color:inherit;cursor:pointer;display:grid;grid-template-columns:1fr auto;width:100%;text-align:left;margin:8px 0;gap:4px}.rank meter{grid-column:1/-1;width:100%}.day{display:flex;align-items:center;gap:12px;margin:12px 0}.day meter{flex:1;min-width:50px}.table-scroll{overflow-x:auto}table{width:100%;text-align:left;border-collapse:collapse}td,th{padding:10px;border-bottom:1px solid #8883;white-space:nowrap}.gap{color:#d89530}.filters{flex-wrap:wrap}.filters label{display:flex;align-items:center;gap:6px}input{padding:7px}.summary strong{font-size:22px}@media(max-width:900px){.summary,.charts{grid-template-columns:1fr}.day{flex-wrap:wrap}}
.timeline-scroll{overflow-x:auto}.timeline-grid{min-width:700px}.axis{display:flex;justify-content:space-between;margin-left:150px;font-size:12px;color:#888;margin-bottom:8px}.timeline-lane{display:grid;grid-template-columns:150px 1fr;align-items:center;margin:7px 0}.lane-label{font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;padding-right:10px}.lane-track{position:relative;height:26px;overflow:hidden;background:repeating-linear-gradient(to right,transparent 0,transparent calc(16.666% - 1px),#8884 calc(16.666% - 1px),#8884 16.666%);border:1px solid #8883}.segment{position:absolute;top:2px;height:20px;min-width:2px;padding:0;border:0;border-radius:2px}.segment:focus{outline:2px solid currentColor;z-index:2}.segment-gap{background-image:repeating-linear-gradient(45deg,transparent,transparent 3px,#fff5 3px,#fff5 6px)}
</style>
