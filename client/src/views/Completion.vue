<script setup lang="ts">
import { onMounted, ref } from 'vue';
import * as echarts from 'echarts';
import { api, type CompletionStats } from '../api';

const stats = ref<CompletionStats | null>(null);
const error = ref('');
let chart: echarts.ECharts | null = null;

async function load() {
  try {
    stats.value = await api.completions();
    render();
  } catch (e) {
    error.value = (e as Error).message;
  }
}

function render() {
  if (!stats.value) return;
  const el = document.getElementById('completion-chart');
  if (!el) return;
  chart ??= echarts.init(el);
  const top = stats.value.top.slice(0, 15);
  chart.setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 140, right: 40, top: 20, bottom: 30 },
    xAxis: { type: 'value' },
    yAxis: { type: 'category', data: top.map((c) => `${c.completion.slice(0, 12)}…`).reverse() },
    series: [{ name: '使用次数', type: 'bar', data: top.map((c) => Number(c.use_count)).reverse(), itemStyle: { color: '#7bed9f' } }],
  });
}

onMounted(load);
</script>

<template>
  <div v-if="error" class="empty">加载失败：{{ error }}</div>
  <div v-else-if="stats">
    <div class="stat-grid" style="margin-bottom: 20px">
      <div class="stat"><div class="num">{{ stats.candidate_count }}</div><div class="label">候选总数</div></div>
      <div class="stat"><div class="num">{{ stats.show_count }}</div><div class="label">展示次数</div></div>
      <div class="stat"><div class="num">{{ stats.accept_count }}</div><div class="label">点击次数</div></div>
      <div class="stat"><div class="num">{{ stats.accept_rate }}%</div><div class="label">接受率</div></div>
      <div class="stat"><div class="num">{{ stats.saved_chars }}</div><div class="label">预估节省字符</div></div>
    </div>

    <div class="card">
      <h3>Top 补全候选（按使用次数）</h3>
      <table>
        <thead>
          <tr><th>前缀</th><th>补全</th><th>次数</th><th>展示</th><th>接受</th><th>评分</th></tr>
        </thead>
        <tbody>
          <tr v-for="(c, i) in stats.top.slice(0, 20)" :key="i">
            <td>{{ c.prefix }}</td>
            <td style="max-width: 320px; word-break: break-all">{{ c.completion }}</td>
            <td>{{ c.use_count }}</td>
            <td>{{ c.show_count }}</td>
            <td>{{ c.accept_count }}</td>
            <td>{{ c.score }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="!stats.top.length" class="empty">暂无补全候选（高频短语使用 3 次后自动生成）</div>
    </div>
  </div>
  <div v-else class="empty">加载中…</div>
</template>
