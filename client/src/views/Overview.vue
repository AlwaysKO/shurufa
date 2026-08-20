<script setup lang="ts">
import { onMounted, ref } from 'vue';
import * as echarts from 'echarts';
import { api, type OverviewData } from '../api';

const data = ref<OverviewData | null>(null);
const error = ref('');

async function load() {
  try {
    data.value = await api.overview(7);
    renderSourceChart();
  } catch (e) {
    error.value = (e as Error).message;
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

onMounted(load);
</script>

<template>
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
