<script setup lang="ts">
import RetentionCleanup from '../components/RetentionCleanup.vue';
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import * as echarts from 'echarts';
import { api, type OverviewData } from '../api';

const data = ref<OverviewData | null>(null);
const error = ref('');
let rolloverTimer: ReturnType<typeof setTimeout> | undefined;

function scheduleRollover() {
  clearTimeout(rolloverTimer);
  const end = Date.parse(data.value?.today_range?.to ?? '');
  if (Number.isFinite(end)) {
    const delay = end - Date.now();
    rolloverTimer = setTimeout(() => void load(), delay > 0 ? delay + 100 : 60_000);
  }
}
function refreshIfDayChanged() {
  if (document.visibilityState === 'visible' && Date.now() >= Date.parse(data.value?.today_range?.to ?? '')) void load();
}

let requestId = 0, disposed = false;
onBeforeUnmount(() => { disposed = true; requestId++; });
async function load() {
  if (disposed) return;
  const request = ++requestId;
  error.value = '';
  try {
    const result = await api.overview(7);
    if (disposed || request !== requestId) return;
    data.value = result;
    scheduleRollover();
    // 图表容器受 v-if 控制，必须等本轮 DOM 更新完成。
    await nextTick();
    if (disposed || request !== requestId) return;
    renderSourceChart();
  } catch (e) {
    if (!disposed && request === requestId) error.value = (e as Error).message;
  }
}

let chart: echarts.ECharts | null = null;
function renderSourceChart() {
  const el = document.getElementById('source-chart');
  if (!el || !data.value) return;
  chart ??= echarts.init(el);
  const s = data.value.source_distribution;
  chart.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c} 字 ({d}%)' },
    series: [
      {
        type: 'pie',
        radius: ['40%', '70%'],
        data: [
          { name: '键盘输入', value: s.typed },
          { name: '复制粘贴', value: s.pasted },
          { name: '外部插入', value: s.external },
          { name: '语音', value: s.voice },
        ],
        label: { formatter: '{b}\n{d}%' },
      },
    ],
  });
}

function resizeChart() { chart?.resize(); }
onMounted(() => {
  window.addEventListener('resize', resizeChart);
  document.addEventListener('visibilitychange', refreshIfDayChanged);
  load();
});
onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeChart);
  document.removeEventListener('visibilitychange', refreshIfDayChanged);
  clearTimeout(rolloverTimer);
  chart?.dispose();
  chart = null;
});
</script>

<template>
  <RetentionCleanup dataset="input" label="输入记录" @changed="load" />
  <p class="today-range">
    今日统计：{{ data?.today_range?.date ?? '当天' }} 00:00:00—23:59:59（北京时间）。按记录发生时间统计已上报数据。
    <button type="button" @click="load">刷新统计</button>
  </p>
  <div v-if="error" class="empty">加载失败：{{ error }}（请确认 server 已启动）</div>
  <div v-else-if="data">
    <div class="stat-grid" style="margin-bottom: 20px">
      <div class="stat"><div class="num">{{ data.today.input_chars }}</div><div class="label">今日输入（字）</div></div>
      <div class="stat"><div class="num">{{ data.today.input_events }}</div><div class="label">今日输入事件</div></div>
      <div class="stat"><div class="num">{{ data.today.clipboard_count }}</div><div class="label">今日复制</div></div>
      <div class="stat"><div class="num">{{ data.today.delete_count }}</div><div class="label">今日删除</div></div>
      <div class="stat"><div class="num">{{ data.period.active_days }}</div><div class="label">近7天活跃天数</div></div>
      <div class="stat"><div class="num">{{ data.total_chars }}</div><div class="label">累计输入（字）</div></div>
    </div>

    <div class="card">
      <h3>输入方式分布（近 7 天）</h3>
      <div id="source-chart" class="chart"></div>
    </div>
  </div>
  <div v-else class="empty">加载中…</div>
</template>

<style scoped>
.today-range { margin:0 0 16px; color:#64748b; font-size:13px; line-height:1.8; }
.today-range button { margin-left:8px; padding:5px 10px; border:1px solid #cbd5e1; border-radius:6px; background:white; cursor:pointer; }
</style>
