<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { dashboardFetch } from '../auth';

type LoginRecord = { id: string; occurred_at: string; username: string; authenticated_username: string | null; result: string; ip: string; peer_ip: string; ip_source: string; user_agent: string };
const labels: Record<string, string> = { success: '登录成功', invalid_credentials: '账号或密码错误', rate_limited: '尝试过多，已限流', rejected: '请求来源校验未通过' };
const rows = ref<LoginRecord[]>([]), total = ref(0), page = ref(1), loading = ref(false), error = ref('');
const username = ref(''), ip = ref(''), result = ref(''), from = ref(''), to = ref('');
const pageSize = 20;
let submitted = new URLSearchParams();
let requestId = 0;
function beijingIso(value: string) { return value.length === 16 ? value + ':00+08:00' : value + '+08:00'; }
function formatTime(value: string) { return new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }); }
async function load(target = page.value) {
  const id = ++requestId;
  loading.value = true; error.value = '';
  const query = new URLSearchParams(submitted);
  query.set('page', String(target)); query.set('page_size', String(pageSize));
  try {
    const response = await dashboardFetch('/api/v1/auth/login-records?' + query);
    if (!response.ok) throw new Error(response.status === 400 ? '筛选条件无效，请核对IP和时间范围' : '登录记录加载失败，请重试');
    const data = await response.json();
    if (id !== requestId) return;
    rows.value = data.records; total.value = data.total; page.value = data.page;
  } catch (e) { if (id === requestId) error.value = e instanceof Error ? e.message : '加载失败'; }
  finally { if (id === requestId) loading.value = false; }
}
function search() {
  if (from.value && to.value && from.value >= to.value) { error.value = '结束时间必须晚于开始时间'; return; }
  submitted = new URLSearchParams();
  for (const [key, value] of Object.entries({ username: username.value.trim(), ip: ip.value.trim(), result: result.value, from: from.value ? beijingIso(from.value) : '', to: to.value ? beijingIso(to.value) : '' })) {
    if (value) submitted.set(key, value);
  }
  void load(1);
}
onMounted(() => void load(1));
</script>

<template>
  <section class="login-records">
    <p>记录功能启用后的后台登录尝试。时间均为北京时间，记录覆盖整个后台，与手机选择无关。</p>
    <form class="filters" @submit.prevent="search">
      <label>提交账号<input v-model="username" maxlength="200" placeholder="精确匹配" /></label>
      <label>来源 IP<input v-model="ip" maxlength="45" placeholder="IPv4 或 IPv6" /></label>
      <label>登录结果<select v-model="result"><option value="">全部</option><option v-for="(label, key) in labels" :key="key" :value="key">{{ label }}</option></select></label>
      <label>开始时间（北京）<input v-model="from" type="datetime-local" /></label>
      <label>结束时间（北京，不含）<input v-model="to" type="datetime-local" /></label>
      <button type="submit" :disabled="loading">查询</button>
      <button type="button" :disabled="loading" @click="load()">刷新</button>
    </form>
    <p>失败尝试中的账号未经验证；浏览器和系统来自客户端自述，不能证明操作者身份。IP 可能是共享网络或 VPN 出口。</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="loading" role="status">加载中…</p>
    <div class="table-scroll">
      <table>
        <thead><tr><th>时间（北京）</th><th>结果</th><th>提交账号</th><th>已验证身份</th><th>来源 IP</th><th>IP 来源 / 连接地址</th><th>浏览器及系统（User-Agent）</th></tr></thead>
        <tbody><tr v-for="row in rows" :key="row.id">
          <td>{{ formatTime(row.occurred_at) }}</td><td :class="row.result === 'success' ? 'success' : 'failure'">{{ labels[row.result] || row.result }}</td>
          <td>{{ row.username || '未提供' }}</td><td>{{ row.authenticated_username || '未验证' }}</td><td>{{ row.ip || '未知' }}</td>
          <td>{{ row.ip_source === 'local_proxy' ? '本机反代' : '直接连接' }}<br />{{ row.peer_ip || '未知' }}</td><td class="agent">{{ row.user_agent || '未提供' }}</td>
        </tr><tr v-if="!loading && !error && !rows.length"><td colspan="7">暂无符合条件的登录记录</td></tr></tbody>
      </table>
    </div>
    <div class="pagination"><span>共 {{ total }} 条，第 {{ page }} / {{ Math.max(1, Math.ceil(total / pageSize)) }} 页</span>
      <button :disabled="loading || page <= 1" @click="load(page - 1)">上一页</button>
      <button :disabled="loading || page * pageSize >= total" @click="load(page + 1)">下一页</button>
    </div>
  </section>
</template>

<style scoped>
.login-records { padding: 24px; }
.filters { display: flex; flex-wrap: wrap; gap: 12px; align-items: end; margin: 20px 0; }
label { display: flex; flex-direction: column; gap: 6px; }
input, select, button { font: inherit; padding: 8px; border: 1px solid #cbd5e1; border-radius: 6px; background: white; }
button { cursor: pointer; } button:disabled { cursor: default; opacity: .5; }
.table-scroll { overflow-x: auto; } table { width: 100%; border-collapse: collapse; font-size: 14px; }
th, td { text-align: left; padding: 12px; border-bottom: 1px solid #e2e8f0; vertical-align: top; }
th { white-space: nowrap; } .agent { min-width: 220px; max-width: 440px; overflow-wrap: anywhere; }
.error, .failure { color: #b91c1c; } .success { color: #15803d; }
.pagination { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-top: 16px; }
</style>
