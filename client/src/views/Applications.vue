<script setup lang="ts">
import RetentionCleanup from '../components/RetentionCleanup.vue';
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import * as echarts from 'echarts';
import { api, appName } from '../api';

const days = ref(30);
const error = ref('');
let chart: echarts.ECharts | null = null;

let requestId = 0, disposed = false;
onBeforeUnmount(() => { disposed = true; requestId++; });
async function load() {
  if (disposed) return;
  const request = ++requestId;
  error.value = '';
  try {
    const res = await api.apps(days.value);
    if (disposed || request !== requestId) return;
    const rows = res.apps.map((a) => ({ packageName: a.package_name, name: appName(a.package_name, a.app_name), chars: Number(a.input_chars), events: Number(a.event_count) }));
    await nextTick();
    if (disposed || request !== requestId) return;
    render(rows);
  } catch (e) {
    if (!disposed && request === requestId) error.value = (e as Error).message;
  }
}

function render(rows: Array<{ packageName: string; name: string; chars: number; events: number }>) {
  const el = document.getElementById('apps-chart');
  if (!el) return;
  chart ??= echarts.init(el);
  const ordered = [...rows].reverse();
  const names = new Map(rows.map(row => [row.packageName, row.name]));
  chart.setOption({
    tooltip: {
      trigger: 'axis', axisPointer: { type: 'shadow' }, renderMode: 'richText', confine: true,
      formatter: (params: unknown) => {
        const item = (Array.isArray(params) ? params[0] : params) as { dataIndex: number };
        const row = ordered[item.dataIndex];
        return row ? `${row.name}\n${row.packageName}\n输入字符：${row.chars.toLocaleString()}\n输入事件：${row.events.toLocaleString()}` : '';
      },
    },
    grid: { left: 12, right: 28, top: 20, bottom: 30, containLabel: true },
    xAxis: { type: 'value' },
    yAxis: {
      type: 'category', data: ordered.map(row => row.packageName),
      axisLabel: { width: 140, overflow: 'truncate', formatter: (pkg: string) => names.get(pkg) ?? pkg },
    },
    series: [{ name: '输入字符', type: 'bar', data: ordered.map(row => row.chars), itemStyle: { color: '#2ed573' } }],
  });
}

onMounted(load);
</script>

<template>
  <RetentionCleanup dataset="input" label="输入记录" :context="days" @changed="load" />
  <div class="filters">
    <button v-for="d in [7, 30, 90]" :key="d" :class="{ active: days === d }" @click="days = d; load()">{{ d }} 天</button>
  </div>
  <div v-if="error" class="empty">加载失败：{{ error }}</div>
  <div class="card">
    <h3>各 App 输入字符分布</h3>
    <div id="apps-chart" class="chart"></div>
  </div>
</template>
