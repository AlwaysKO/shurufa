<script setup lang="ts">
import { onMounted, ref } from 'vue';
import * as echarts from 'echarts';
import { api } from '../api';

const days = ref(30);
const error = ref('');
let chart: echarts.ECharts | null = null;

async function load() {
  try {
    const [tl, hr] = await Promise.all([api.timeline(days.value), api.hours(days.value)]);
    render(tl.timeline.map((p) => ({ day: p.day, chars: Number(p.input_chars), events: Number(p.event_count) })), hr.hours);
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
</template>
