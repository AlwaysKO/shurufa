<script setup lang="ts">
import { onMounted, ref } from 'vue';
import * as echarts from 'echarts';
import { api, appName } from '../api';

const days = ref(30);
const error = ref('');
let chart: echarts.ECharts | null = null;

async function load() {
  try {
    const res = await api.apps(days.value);
    const rows = res.apps.map((a) => ({ name: appName(a.package_name), chars: Number(a.input_chars), events: Number(a.event_count) }));
    render(rows);
  } catch (e) {
    error.value = (e as Error).message;
  }
}

function render(rows: Array<{ name: string; chars: number; events: number }>) {
  const el = document.getElementById('apps-chart');
  if (!el) return;
  chart ??= echarts.init(el);
  chart.setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 100, right: 40, top: 20, bottom: 30 },
    xAxis: { type: 'value' },
    yAxis: { type: 'category', data: rows.map((r) => r.name).reverse() },
    series: [{ name: '输入字符', type: 'bar', data: rows.map((r) => r.chars).reverse(), itemStyle: { color: '#2ed573' } }],
  });
}

onMounted(load);
</script>

<template>
  <div class="filters">
    <button v-for="d in [7, 30, 90]" :key="d" :class="{ active: days === d }" @click="days = d; load()">{{ d }} 天</button>
  </div>
  <div v-if="error" class="empty">加载失败：{{ error }}</div>
  <div class="card">
    <h3>各 App 输入字符分布</h3>
    <div id="apps-chart" class="chart"></div>
  </div>
</template>
