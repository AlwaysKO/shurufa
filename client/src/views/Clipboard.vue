<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import ClipboardHistory from './ClipboardHistory.vue';
import ClipboardStatistics from './ClipboardStatistics.vue';

const route = useRoute();
const statistics = computed(() => route.query.tab === 'statistics');
</script>

<template>
  <nav class="clipboard-tabs" aria-label="剪贴板视图">
    <RouterLink to="/clipboard" :aria-current="!statistics ? 'page' : undefined" :class="{ selected: !statistics }">历史记录</RouterLink>
    <RouterLink to="/clipboard?tab=statistics" :aria-current="statistics ? 'page' : undefined" :class="{ selected: statistics }">统计分析</RouterLink>
  </nav>
  <ClipboardStatistics v-if="statistics" />
  <ClipboardHistory v-else />
</template>

<style scoped>
.clipboard-tabs { display:flex; gap:8px; margin-bottom:16px; }
a { padding:9px 16px; border:1px solid #dfe4ea; border-radius:8px; background:#fff; color:#57606f; text-decoration:none; }
a.selected { background:#3742fa; border-color:#3742fa; color:#fff; }
</style>
