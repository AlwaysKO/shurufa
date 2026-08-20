<script setup lang="ts">
import { onMounted, ref } from 'vue';
import * as echarts from 'echarts';
import { api, type HeatmapCell } from '../api';

const days = ref(30);
const error = ref('');
let chart: echarts.ECharts | null = null;
let hmChart: echarts.ECharts | null = null;

async function load() {
  try {
    const [tl, hr, hm] = await Promise.all([api.timeline(days.value), api.hours(days.value), api.heatmap(days.value)]);
    render(tl.timeline.map((p) => ({ day: p.day, chars: Number(p.input_chars), events: Number(p.event_count) })), hr.hours);
    renderHeatmap(hm.cells);
  } catch (e) {
    error.value = (e as Error).message;
  }
}

function render(timeline: Array<{ day: string; chars: number; events: number }>, hours: Array<{ hour: number; input_chars: string }>) {
  const el = document.getElementById('timeline-chart');
  if (!el) return;
  chart ??= echarts.init(el);
  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['输入字符', '事件数'] },
    grid: { left: 50, right: 50, top: 40, bottom: 30 },
    xAxis: { type: 'category', data: timeline.map((p) => p.day.slice(5)), axisLabel: { rotate: 45 } },
    yAxis: [{ type: 'value', name: '字符' }, { type: 'value', name: '事件' }],
    series: [
      { name: '输入字符', type: 'bar', data: timeline.map((p) => p.chars), itemStyle: { color: '#3742fa' } },
      { name: '事件数', type: 'line', yAxisIndex: 1, data: timeline.map((p) => p.events), smooth: true },
    ],
  });

  const el2 = document.getElementById('hour-chart');
  if (!el2) return;
  const hc = echarts.getInstanceByDom(el2) ?? echarts.init(el2);
  const full = Array.from({ length: 24 }, (_, h) => Number(hours.find((x) => x.hour === h)?.input_chars ?? 0));
  hc.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 50, right: 20, top: 20, bottom: 30 },
    xAxis: { type: 'category', data: full.map((_, h) => `${h}时`) },
    yAxis: { type: 'value' },
    series: [{ name: '输入字符', type: 'bar', data: full, itemStyle: { color: '#ff6348' } }],
  });
}

/** 7×24 活跃热力图：行=星期（周一~周日），列=小时 */
function renderHeatmap(cells: HeatmapCell[]) {
  const el = document.getElementById('heatmap-chart');
  if (!el) return;
  hmChart = echarts.getInstanceByDom(el) ?? echarts.init(el);
  const dowLabels = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
  const data = cells.map((c) => [c.hour, c.dow - 1, Number(c.chars)]);
  const max = Math.max(1, ...data.map((d) => d[2]));
  hmChart.setOption({
    tooltip: {
      position: 'top',
      formatter: (p: { value: [number, number, number] }) => `${dowLabels[p.value[1]]} ${p.value[0]} 时：${p.value[2].toLocaleString()} 字`,
    },
    grid: { left: 60, right: 20, top: 10, bottom: 40 },
    xAxis: { type: 'category', data: Array.from({ length: 24 }, (_, h) => `${h}时`), splitArea: { show: true } },
    yAxis: { type: 'category', data: dowLabels, splitArea: { show: true } },
    visualMap: {
      min: 0,
      max,
      calculable: true,
      orient: 'horizontal',
      left: 'center',
      bottom: 0,
      inRange: { color: ['#dfe4ea', '#70a1ff', '#3742fa', '#2f3542'] },
    },
    series: [
      {
        type: 'heatmap',
        data,
        label: { show: false },
        emphasis: { itemStyle: { shadowBlur: 8, shadowColor: 'rgba(0,0,0,.4)' } },
      },
    ],
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
    <h3>每日输入量</h3>
    <div id="timeline-chart" class="chart"></div>
  </div>
  <div class="card">
    <h3>小时分布（几点最爱打字）</h3>
    <div id="hour-chart" class="chart"></div>
  </div>
  <div class="card">
    <h3>活跃热力图（星期 × 小时，近 {{ days }} 天）</h3>
    <div id="heatmap-chart" class="chart"></div>
  </div>
</template>
