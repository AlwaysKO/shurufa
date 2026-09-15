<script setup lang="ts">
import { onMounted, ref } from 'vue';
import * as echarts from 'echarts';
import { api, type ClipboardData } from '../api';

const days = ref(30);
const data = ref<ClipboardData | null>(null);
const error = ref('');
let chart: echarts.ECharts | null = null;

async function load() {
  try {
    data.value = await api.clipboard(days.value);
    render();
  } catch (e) {
    error.value = (e as Error).message;
  }
}

function render() {
  if (!data.value) return;
  const el = document.getElementById('clipboard-chart');
  if (!el) return;
  chart ??= echarts.init(el);
  const t = data.value.type_distribution;
  chart.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c} 次 ({d}%)' },
    series: [
      {
        type: 'pie',
        radius: ['40%', '70%'],
        data: [
          { name: '文字', value: t.plain },
          { name: '链接', value: t.url },
          { name: '邮箱', value: t.email },
          { name: '电话', value: t.phone },
        ],
        label: { formatter: '{b}\n{d}%' },
      },
    ],
  });
}

const fmtDate = (s: string) => (s ? new Date(s).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '-');
onMounted(load);
</script>

<template>
  <div class="filters">
    <button v-for="d in [7, 30, 90]" :key="d" :class="{ active: days === d }" @click="days = d; load()">{{ d }} 天</button>
  </div>
  <div v-if="error" class="empty">加载失败：{{ error }}</div>
  <div v-else-if="data">
    <div class="stat-grid" style="margin-bottom: 20px">
      <div class="stat"><div class="num">{{ data.counts.copy_count }}</div><div class="label">复制次数</div></div>
      <div class="stat"><div class="num">{{ data.counts.paste_count }}</div><div class="label">粘贴次数</div></div>
      <div class="stat"><div class="num">{{ data.paste_intervals.within_10s }}</div><div class="label">复制后 10 秒内粘贴</div></div>
      <div class="stat"><div class="num">{{ data.paste_intervals.within_1m }}</div><div class="label">1 分钟内粘贴</div></div>
    </div>

    <div class="card">
      <h3>复制内容类型分布</h3>
      <div id="clipboard-chart" class="chart" style="height: 260px"></div>
    </div>

    <div class="card">
      <h3>最常复制的内容</h3>
      <table>
        <thead>
          <tr><th>内容</th><th>次数</th><th>最近复制</th></tr>
        </thead>
        <tbody>
          <tr v-for="(c, i) in data.top" :key="i">
            <td style="max-width: 420px; word-break: break-all">{{ c.text }}</td>
            <td>{{ c.count }}</td>
            <td>{{ fmtDate(c.last_used_at) }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="!data.top.length" class="empty">暂无复制记录</div>
    </div>
  </div>
  <div v-else class="empty">加载中…</div>
</template>
