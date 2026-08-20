<script setup lang="ts">
import { useRoute } from 'vue-router';

const route = useRoute();
const navs = [
  { path: '/', label: '输入总览' },
  { path: '/timeline', label: '时间线' },
  { path: '/apps', label: 'APP 分布' },
  { path: '/phrases', label: '高频词句' },
  { path: '/wordcloud', label: '词云' },
  { path: '/completion', label: '智能补全' },
  { path: '/clipboard', label: '剪贴板' },
  { path: '/clipboard-history', label: '剪贴板历史' },
  { path: '/activity', label: '行为明细' },
  { path: '/locations', label: '位置轨迹' },
  { path: '/report', label: '输入报告' },
  { path: '/stickers', label: '表情包' },
  { path: '/user-phrases', label: '常用语' },
  { path: '/chat-capture', label: '聊天采集' },
  { path: '/data', label: '数据管理' },
];
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="logo">⌨️ 我的输入法</div>
      <nav>
        <RouterLink v-for="n in navs" :key="n.path" :to="n.path" class="nav-item" :class="{ active: route.path === n.path }">
          {{ n.label }}
        </RouterLink>
      </nav>
    </aside>
    <main class="content">
      <h1 class="page-title">{{ route.meta.title }}</h1>
      <RouterView />
    </main>
  </div>
</template>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, 'PingFang SC', 'Microsoft YaHei', sans-serif; background: #f5f6fa; color: #2f3542; }
.layout { display: flex; min-height: 100vh; }
.sidebar { width: 200px; background: #2f3542; color: #fff; padding: 20px 0; flex-shrink: 0; }
.logo { font-size: 16px; font-weight: 600; padding: 0 20px 20px; border-bottom: 1px solid rgba(255,255,255,.1); }
nav { padding: 12px 0; }
.nav-item { display: block; padding: 12px 20px; color: rgba(255,255,255,.7); text-decoration: none; font-size: 14px; }
.nav-item:hover { background: rgba(255,255,255,.08); }
.nav-item.active { background: #3742fa; color: #fff; }
.content { flex: 1; padding: 24px 32px; overflow-x: hidden; }
.page-title { font-size: 20px; margin-bottom: 20px; }
.card { background: #fff; border-radius: 10px; padding: 20px; margin-bottom: 20px; box-shadow: 0 1px 3px rgba(0,0,0,.06); }
.card h3 { font-size: 14px; color: #747d8c; margin-bottom: 12px; }
.stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 16px; }
.stat { background: #fff; border-radius: 10px; padding: 16px 20px; box-shadow: 0 1px 3px rgba(0,0,0,.06); }
.stat .num { font-size: 26px; font-weight: 700; color: #3742fa; }
.stat .label { font-size: 12px; color: #747d8c; margin-top: 4px; }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th, td { padding: 10px 12px; text-align: left; border-bottom: 1px solid #f1f2f6; }
th { color: #747d8c; font-weight: 500; background: #fafbfc; }
.chart { width: 100%; height: 320px; }
.filters { display: flex; gap: 8px; margin-bottom: 16px; flex-wrap: wrap; }
.filters button { padding: 6px 14px; border-radius: 16px; border: 1px solid #dfe4ea; background: #fff; cursor: pointer; font-size: 13px; color: #57606f; }
.filters button.active { background: #3742fa; color: #fff; border-color: #3742fa; }
.pct-bar { display: inline-block; height: 8px; border-radius: 4px; background: #3742fa; vertical-align: middle; margin-right: 8px; }
.empty { color: #a4b0be; text-align: center; padding: 40px 0; font-size: 14px; }
</style>
