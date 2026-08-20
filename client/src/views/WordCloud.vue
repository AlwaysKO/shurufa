<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import * as echarts from 'echarts';
import type { ECElementEvent } from 'echarts';
import 'echarts-wordcloud';
import { api, type PhraseRow } from '../api';

const router = useRouter();
const days = ref<number | 'all'>(30);
const phrases = ref<PhraseRow[]>([]);
const loading = ref(false);
const error = ref('');
let chart: echarts.ECharts | null = null;

/** 词云数据：过滤长句，只保留 1~10 字的高频词 */
const cloudWords = () =>
  phrases.value
    .filter((p) => p.phrase.length >= 1 && p.phrase.length <= 10)
    .slice(0, 100)
    .map((p) => ({ name: p.phrase, value: Number(p.use_count) }));

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const data = await api.phrases('word', days.value, 100);
    phrases.value = data.phrases;
    render();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}

/** 点击词 → 跳转行为明细页并搜索该词 */
function jumpToDetail(word: string) {
  router.push({ path: '/activity', query: { q: word } });
}

function render() {
  const el = document.getElementById('wordcloud-chart');
  if (!el) return;
  chart ??= echarts.init(el);
  const words = cloudWords();
  chart.setOption({
    tooltip: {
      formatter: (p: { name: string; value: number }) => `${p.name}<br/>输入 ${p.value} 次`,
    },
    series: [
      {
        type: 'wordCloud',
        shape: 'circle',
        left: 'center',
        top: 'center',
        width: '92%',
        height: '92%',
        sizeRange: [14, 64],
        rotationRange: [0, 0],
        gridSize: 6,
        drawOutOfBound: false,
        textStyle: {
          fontWeight: 600,
          color: () =>
            `hsl(${Math.floor(Math.random() * 360)}, 65%, ${45 + Math.floor(Math.random() * 20)}%)`,
        },
        emphasis: {
          textStyle: { textShadowBlur: 8, textShadowColor: 'rgba(0,0,0,0.25)' },
        },
        data: words,
      },
    ],
  });
  chart.off('click');
  chart.on('click', (p: ECElementEvent) => {
    const name = (p.data as { name?: string } | undefined)?.name;
    if (name) jumpToDetail(name);
  });
}

const fmtTime = (s: string) => new Date(s).toLocaleString('zh-CN', { hour12: false });
const totalCount = () => phrases.value.reduce((sum, p) => sum + Number(p.use_count), 0);

onMounted(load);
</script>

<template>
  <div class="filters">
    <button :class="{ active: days === 7 }" @click="days = 7; load()">近7天</button>
    <button :class="{ active: days === 30 }" @click="days = 30; load()">近30天</button>
    <button :class="{ active: days === 90 }" @click="days = 90; load()">近90天</button>
    <button :class="{ active: days === 'all' }" @click="days = 'all'; load()">全部</button>
    <span class="hint">点击词可查看对应输入明细</span>
  </div>

  <div class="card">
    <h3>输入词云 <span class="count">Top {{ phrases.length }} · 共 {{ totalCount() }} 次</span></h3>
    <p v-if="loading" class="msg">加载中…</p>
    <p v-else-if="error" class="msg err">{{ error }}</p>
    <p v-else-if="cloudWords().length === 0" class="msg">暂无数据，开始输入后这里会生成你的输入词云</p>
    <div v-else id="wordcloud-chart" class="chart"></div>
  </div>

  <div class="card">
    <h3>高频词 TOP 50</h3>
    <p v-if="loading" class="msg">加载中…</p>
    <table v-else-if="phrases.length > 0" class="table">
      <thead>
        <tr><th>#</th><th>词 / 句</th><th>次数</th><th>活跃天数</th><th>最近使用</th></tr>
      </thead>
      <tbody>
        <tr v-for="(p, i) in phrases.slice(0, 50)" :key="p.phrase + i" class="clickable" @click="jumpToDetail(p.phrase)">
          <td>{{ i + 1 }}</td>
          <td class="phrase">{{ p.phrase }}</td>
          <td>{{ p.use_count }}</td>
          <td>{{ p.use_days }}</td>
          <td class="time">{{ fmtTime(p.last_used_at) }}</td>
        </tr>
      </tbody>
    </table>
    <p v-else class="msg">暂无数据</p>
  </div>
</template>

<style scoped>
.hint {
  margin-left: auto;
  font-size: 0.78em;
  color: var(--muted, #888);
}
.count {
  font-weight: normal;
  color: var(--muted, #888);
  font-size: 0.85em;
}
.chart {
  width: 100%;
  height: 520px;
  margin-top: 8px;
}
.table {
  width: 100%;
  border-collapse: collapse;
  margin-top: 10px;
  font-size: 0.9em;
}
.table th,
.table td {
  text-align: left;
  padding: 6px 10px;
  border-bottom: 1px solid var(--border, #eee);
}
.table th {
  color: var(--muted, #888);
  font-weight: normal;
  font-size: 0.82em;
}
.table tr.clickable {
  cursor: pointer;
}
.table tr.clickable:hover {
  background: #fafafa;
}
.phrase {
  max-width: 420px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.time {
  color: var(--muted, #888);
  font-size: 0.85em;
}
</style>
