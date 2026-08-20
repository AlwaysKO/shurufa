<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { api, type PrefixRow, type PhraseRow } from '../api';

const kind = ref<'word' | 'phrase'>('phrase');
const days = ref<'all' | number>('all');
const phrases = ref<PhraseRow[]>([]);
const prefixes = ref<PrefixRow[]>([]);
const error = ref('');

async function load() {
  try {
    const [p, pr] = await Promise.all([api.phrases(kind.value, days.value, 50), api.prefixes()]);
    phrases.value = p.phrases;
    prefixes.value = pr.prefixes;
  } catch (e) {
    error.value = (e as Error).message;
  }
}

const fmtDate = (s: string) => (s ? new Date(s).toLocaleDateString('zh-CN') : '-');
onMounted(load);
</script>

<template>
  <div class="filters">
    <button :class="{ active: kind === 'phrase' }" @click="kind = 'phrase'; load()">高频短语</button>
    <button :class="{ active: kind === 'word' }" @click="kind = 'word'; load()">高频词</button>
    <span style="width: 8px"></span>
    <button :class="{ active: days === 'all' }" @click="days = 'all'; load()">全部</button>
    <button v-for="d in [7, 30]" :key="d" :class="{ active: days === d }" @click="days = d; load()">近{{ d }}天</button>
  </div>
  <div v-if="error" class="empty">加载失败：{{ error }}</div>

  <div class="card">
    <h3>{{ kind === 'phrase' ? '我的高频短语' : '我的高频词' }}</h3>
    <table v-if="phrases.length">
      <thead>
        <tr><th>内容</th><th>次数</th><th>使用天数</th><th>最近使用</th></tr>
      </thead>
      <tbody>
        <tr v-for="(p, i) in phrases" :key="i">
          <td style="max-width: 400px; word-break: break-all">{{ p.phrase }}</td>
          <td>{{ p.use_count }}</td>
          <td>{{ p.use_days }}</td>
          <td>{{ fmtDate(p.last_used_at) }}</td>
        </tr>
      </tbody>
    </table>
    <div v-else class="empty">还没有足够数据，先用几天输入法吧</div>
  </div>

  <div class="card">
    <h3>高频前缀 → 续写分布</h3>
    <table v-if="prefixes.length">
      <thead>
        <tr><th>前缀</th><th>次数</th><th>后续续写</th></tr>
      </thead>
      <tbody>
        <tr v-for="(p, i) in prefixes" :key="i">
          <td style="font-weight: 600">{{ p.prefix }}</td>
          <td>{{ p.count }}</td>
          <td>
            <div v-for="(c, j) in p.continuations" :key="j" style="margin: 2px 0">
              <span class="pct-bar" :style="{ width: (c.pct / 100) * 120 + 'px' }"></span>
              {{ c.text }} <span style="color: #747d8c">（{{ c.pct }}%）</span>
            </div>
            <span v-if="!p.continuations.length" style="color: #a4b0be">暂无续写</span>
          </td>
        </tr>
      </tbody>
    </table>
    <div v-else class="empty">数据不足（短语至少使用 3 次才会进入分析）</div>
  </div>
</template>
