<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { api, appName, type ActivityItem } from '../api';

const items = ref<ActivityItem[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = 20;
const loading = ref(false);
const error = ref('');

const q = ref('');
const pkg = ref('');
const days = ref<number | null>(7);

/** 来源 App 下拉选项：接口 TOP20 合并列表页出现的包名 */
const appOptions = ref<Set<string>>(new Set());

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const res = await api.events({
      type: 'paste',
      q: q.value.trim() || undefined,
      package_name: pkg.value || undefined,
      days: days.value ?? undefined,
      page: page.value,
      page_size: pageSize,
    });
    items.value = res.items;
    total.value = res.total;
    // 聚合列表页出现的来源 App（下拉不遗漏）
    const seen = new Set(appOptions.value);
    res.items.forEach((it) => it.package_name && seen.add(it.package_name));
    appOptions.value = seen;
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}

function search() {
  page.value = 1;
  load();
}

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)));
const fmtTime = (s: string) => new Date(s).toLocaleString('zh-CN', { hour12: false });

/** 内容类型徽标：服务端只分 text/image/voice，链接/邮箱/电话用正则本地识别 */
const typeBadge = (item: ActivityItem) => {
  if (item.content_type === 'image') return { cls: 'badge image', label: '图片' };
  if (item.content_type === 'voice') return { cls: 'badge voice', label: '语音' };
  const t = (item.text ?? '').trim();
  if (/^(https?:\/\/|www\.)/i.test(t)) return { cls: 'badge url', label: '链接' };
  if (/^[\w.+-]+@[\w-]+(\.[\w-]+)+$/.test(t)) return { cls: 'badge email', label: '邮箱' };
  if (/^1[3-9]\d{9}$/.test(t)) return { cls: 'badge phone', label: '电话' };
  return { cls: 'badge text', label: '文字' };
};

const displayText = (item: ActivityItem) => {
  if (item.text) return item.text;
  if (item.content_type === 'image') return '[图片]';
  return '[空]';
};

onMounted(async () => {
  // 预载近 30 天 TOP 来源 App 作为下拉选项
  try {
    const d = await api.apps(30);
    appOptions.value = new Set(d.apps.map((a) => a.package_name).filter(Boolean));
  } catch {
    /* 加载失败不阻塞 */
  }
  load();
});
</script>

<template>
  <div class="filters">
    <button :class="{ active: days === 7 }" @click="days = 7; search()">近7天</button>
    <button :class="{ active: days === 30 }" @click="days = 30; search()">近30天</button>
    <button :class="{ active: days === 90 }" @click="days = 90; search()">近90天</button>
    <button :class="{ active: days === null }" @click="days = null; search()">全部</button>

    <span style="width: 8px"></span>
    <select v-model="pkg" class="input" @change="search()">
      <option value="">全部来源</option>
      <option v-for="p in [...appOptions].sort()" :key="p" :value="p">{{ appName(p) }}（{{ p }}）</option>
    </select>

    <input v-model="q" type="search" class="input search" placeholder="搜索复制内容…" @keyup.enter="search()" />
    <button class="btn" @click="search()">搜索</button>
  </div>

  <div class="card">
    <h3>复制 / 粘贴记录 <span class="count">共 {{ total }} 条</span></h3>

    <p v-if="loading" class="msg">加载中…</p>
    <p v-else-if="error" class="msg err">{{ error }}</p>
    <p v-else-if="items.length === 0" class="msg">暂无记录，复制或粘贴内容后会显示在这里</p>

    <div v-else class="list">
      <div v-for="it in items" :key="it.id" class="row">
        <div class="row-main">
          <div class="content" :title="displayText(it)">{{ displayText(it) }}</div>
          <div class="meta">
            <span class="src" :title="it.package_name ?? ''">{{ appName(it.package_name) }}</span>
            <span v-if="it.ip_location" class="loc">{{ it.ip_location }}</span>
            <span class="time">{{ fmtTime(it.occurred_at) }}</span>
          </div>
        </div>
        <span :class="typeBadge(it).cls">{{ typeBadge(it).label }}</span>
      </div>
    </div>

    <div v-if="totalPages > 1" class="pager">
      <button class="btn" :disabled="page <= 1" @click="page--; load()">上一页</button>
      <span class="page-info">{{ page }} / {{ totalPages }}</span>
      <button class="btn" :disabled="page >= totalPages" @click="page++; load()">下一页</button>
    </div>
  </div>
</template>

<style scoped>
.count {
  font-weight: normal;
  color: var(--muted, #888);
  font-size: 0.85em;
}
.list {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
}
.row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--border, #eee);
}
.row:hover {
  background: #fafafa;
}
.row-main {
  flex: 1;
  min-width: 0;
}
.content {
  font-size: 0.92em;
  line-height: 1.45;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-all;
  white-space: pre-wrap;
}
.meta {
  margin-top: 4px;
  display: flex;
  gap: 10px;
  font-size: 0.78em;
  color: var(--muted, #888);
}
.src {
  color: #3742fa;
}
.badge {
  flex-shrink: 0;
  font-size: 0.72em;
  padding: 2px 8px;
  border-radius: 10px;
  color: #fff;
}
.badge.text { background: #57606f; }
.badge.url { background: #3742fa; }
.badge.email { background: #a55eea; }
.badge.phone { background: #20bf6b; }
.badge.image { background: #e1b12c; }
.badge.voice { background: #e17055; }
.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-top: 12px;
}
.page-info {
  font-size: 0.85em;
  color: var(--muted, #888);
}
</style>
