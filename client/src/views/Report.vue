<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import * as echarts from 'echarts';
import { api, appName, type ReportData } from '../api';

const type = ref<'daily' | 'weekly'>('daily');
const dateStr = ref(todayStr());
const data = ref<ReportData | null>(null);
const error = ref('');

function todayStr(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function fmt(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** 当前查看的日期范围标签（日报显示单日，周报显示周一~周日） */
const rangeLabel = computed(() => {
  const d = new Date(dateStr.value + 'T00:00:00');
  if (type.value === 'daily') return `${d.getMonth() + 1}月${d.getDate()}日`;
  const day = d.getDay() || 7; // getDay(): 0=周日 → 归一为 1~7（周一~周日）
  const monday = new Date(d);
  monday.setDate(d.getDate() - day + 1);
  const sunday = new Date(monday);
  sunday.setDate(monday.getDate() + 6);
  return `${monday.getMonth() + 1}月${monday.getDate()}日 ~ ${sunday.getMonth() + 1}月${sunday.getDate()}日`;
});

/** 一句话总结（前端基于聚合结果拼装） */
const headline = computed(() => {
  const d = data.value;
  if (!d) return '';
  const chars = Number(d.summary.input_chars);
  const events = Number(d.summary.input_events);
  const peak = d.peak_hours[0];
  const app = d.top_apps[0] ? appName(d.top_apps[0].package_name) : null;
  const parts = [`${d.type === 'daily' ? '这一天' : '这一周'}你输入了 ${chars.toLocaleString()} 字`];
  if (events > 0) parts.push(`${events.toLocaleString()} 次`);
  if (d.type === 'weekly' && Number(d.summary.active_days) > 0) parts.push(`活跃 ${d.summary.active_days} 天`);
  if (peak && Number(peak.chars) > 0) parts.push(`最活跃在 ${peak.hour} 点`);
  if (app) parts.push(`主要在 ${app} 中`);
  return parts.join('，') + '。';
});

function shift(days: number) {
  const d = new Date(dateStr.value + 'T00:00:00');
  d.setDate(d.getDate() + days);
  dateStr.value = fmt(d);
  load();
}

function switchType(t: 'daily' | 'weekly') {
  if (type.value === t) return;
  type.value = t;
  dateStr.value = todayStr();
  load();
}

async function load() {
  try {
    data.value = await api.report(type.value, dateStr.value);
    renderChart();
  } catch (e) {
    error.value = (e as Error).message;
  }
}

let chart: echarts.ECharts | null = null;
function renderChart() {
  const el = document.getElementById('report-source-chart');
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

/** Top 应用横向条宽度（相对最大值） */
function appBarWidth(chars: string): string {
  const max = Math.max(1, ...(data.value?.top_apps.map((a) => Number(a.input_chars)) ?? [0]));
  return `${Math.round((Number(chars) / max) * 100)}%`;
}

function locLabel(l: { address: string | null; latitude: string; longitude: string }): string {
  if (l.address) return l.address;
  return `${Number(l.latitude).toFixed(4)}, ${Number(l.longitude).toFixed(4)}`;
}

onMounted(load);
</script>

<template>
  <div class="filters">
    <button :class="{ active: type === 'daily' }" @click="switchType('daily')">日报</button>
    <button :class="{ active: type === 'weekly' }" @click="switchType('weekly')">周报</button>
    <button @click="shift(type === 'daily' ? -1 : -7)">‹ 前{{ type === 'daily' ? '一天' : '一周' }}</button>
    <span class="date-label">{{ rangeLabel }}</span>
    <button @click="shift(type === 'daily' ? 1 : 7)">后{{ type === 'daily' ? '一天' : '一周' }} ›</button>
    <button @click="dateStr = todayStr(); load()">今天</button>
  </div>

  <div v-if="error" class="empty">加载失败：{{ error }}（请确认 server 已启动）</div>
  <div v-else-if="data">
    <div class="card headline-card">{{ headline }}</div>

    <div class="stat-grid" style="margin-bottom: 20px">
      <div class="stat"><div class="num">{{ Number(data.summary.input_chars).toLocaleString() }}</div><div class="label">输入（字）</div></div>
      <div class="stat"><div class="num">{{ Number(data.summary.input_events).toLocaleString() }}</div><div class="label">输入事件</div></div>
      <div class="stat"><div class="num">{{ data.summary.copy_count }}</div><div class="label">复制</div></div>
      <div class="stat"><div class="num">{{ data.summary.delete_count }}</div><div class="label">删除</div></div>
      <div class="stat"><div class="num">{{ data.summary.voice_count }}</div><div class="label">语音次数</div></div>
      <div class="stat">
        <div class="num">{{ type === 'weekly' ? data.summary.active_days : data.summary.device_count }}</div>
        <div class="label">{{ type === 'weekly' ? '活跃天数' : '使用设备' }}</div>
      </div>
    </div>

    <div class="grid-2">
      <div class="card">
        <h3>活跃时段 Top 3</h3>
        <div v-for="p in data.peak_hours" :key="p.hour" class="rank-row">
          <span class="rank-label">{{ p.hour }} 时</span>
          <span class="pct-bar" :style="{ width: `${Math.max(10, Math.round((Number(p.chars) / Math.max(1, Number(data.peak_hours[0]?.chars ?? 0))) * 100))}%` }"></span>
          <span class="rank-num">{{ Number(p.chars).toLocaleString() }} 字</span>
        </div>
        <div v-if="data.peak_hours.length === 0" class="empty">暂无数据</div>
      </div>

      <div class="card">
        <h3>输入方式分布</h3>
        <div id="report-source-chart" class="chart chart-sm"></div>
      </div>
    </div>

    <div class="grid-2">
      <div class="card">
        <h3>Top 应用</h3>
        <div v-for="a in data.top_apps" :key="a.package_name" class="rank-row">
          <span class="rank-label app-label">{{ appName(a.package_name) }}</span>
          <span class="pct-bar" :style="{ width: appBarWidth(a.input_chars) }"></span>
          <span class="rank-num">{{ Number(a.input_chars).toLocaleString() }} 字</span>
        </div>
        <div v-if="data.top_apps.length === 0" class="empty">暂无数据</div>
      </div>

      <div class="card">
        <h3>Top 词句</h3>
        <ol class="phrase-list">
          <li v-for="(p, i) in data.top_phrases" :key="i">
            <span class="phrase-text">{{ p.phrase }}</span>
            <span class="phrase-count">{{ p.use_count }} 次</span>
          </li>
        </ol>
        <div v-if="data.top_phrases.length === 0" class="empty">暂无数据</div>
      </div>
    </div>

    <div class="card">
      <h3>常用位置</h3>
      <table>
        <thead>
          <tr><th>位置</th><th>停留次数</th><th>最后出现</th></tr>
        </thead>
        <tbody>
          <tr v-for="(l, i) in data.top_locations" :key="i">
            <td>{{ locLabel(l) }}</td>
            <td>{{ l.count }}</td>
            <td>{{ new Date(l.last_seen_at).toLocaleString() }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="data.top_locations.length === 0" class="empty">暂无数据（需开启位置采集）</div>
    </div>
  </div>
  <div v-else class="empty">加载中…</div>
</template>

<style scoped>
.grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
.headline-card { font-size: 15px; color: #2f3542; line-height: 1.7; border-left: 4px solid #3742fa; }
.date-label { font-size: 14px; color: #2f3542; padding: 0 4px; align-self: center; }
.rank-row { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.rank-label { width: 64px; font-size: 13px; color: #57606f; flex-shrink: 0; }
.app-label { width: 110px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rank-num { font-size: 12px; color: #747d8c; white-space: nowrap; }
.chart-sm { height: 240px; }
.phrase-list { list-style: none; }
.phrase-list li { display: flex; justify-content: space-between; align-items: center; padding: 10px 0; border-bottom: 1px solid #f1f2f6; font-size: 14px; }
.phrase-text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.phrase-count { font-size: 12px; color: #747d8c; flex-shrink: 0; margin-left: 12px; }
@media (max-width: 900px) { .grid-2 { grid-template-columns: 1fr; } }
</style>
