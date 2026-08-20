<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { api, appName, deviceDetailLines, deviceLabel, eventTypeName, networkName, type ActivityItem, type DeviceRow } from '../api';

const items = ref<ActivityItem[]>([]);
const devices = ref<DeviceRow[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = 20;
const error = ref('');

const type = ref<'all' | 'text' | 'paste' | 'voice' | 'image'>('all');
const deviceId = ref('');
const days = ref<number | null>(null);
const from = ref('');
const to = ref('');
const q = ref('');
const showAll = ref(false);
const loading = ref(false);

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const res = await api.events({
      device_id: deviceId.value || undefined,
      from: from.value || undefined,
      to: to.value || undefined,
      days: days.value ?? undefined,
      q: q.value.trim() || undefined,
      type: type.value,
      all: showAll.value,
      page: page.value,
      page_size: pageSize,
    });
    items.value = res.items;
    total.value = res.total;
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

function quickDays(d: number | null) {
  days.value = d;
  from.value = '';
  to.value = '';
  search();
}

function resetFilters() {
  type.value = 'all';
  deviceId.value = '';
  days.value = null;
  from.value = '';
  to.value = '';
  q.value = '';
  showAll.value = false;
  search();
}

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)));
const fmtTime = (s: string) => new Date(s).toLocaleString('zh-CN', { hour12: false });
const deviceOf = (id: string) => devices.value.find((d) => d.id === id);

/** 类型徽标：语音/图片/文字（颜色区分） */
const badge = (item: ActivityItem) => {
  if (item.content_type === 'voice') return { cls: 'badge voice', label: '语音' };
  if (item.content_type === 'image') return { cls: 'badge image', label: '图片' };
  return { cls: 'badge text', label: eventTypeName(item.event_type) };
};

const displayText = (item: ActivityItem) => {
  if (item.text) return item.text;
  if (item.content_type === 'image') return '[图片]';
  return '[空]';
};

onMounted(async () => {
  try {
    const d = await api.devices();
    devices.value = d.devices;
  } catch {
    /* 设备列表加载失败不阻塞列表 */
  }
  load();
});
</script>

<template>
  <div class="filters">
    <button :class="{ active: type === 'all' }" @click="type = 'all'; search()">全部</button>
    <button :class="{ active: type === 'text' }" @click="type = 'text'; search()">输入</button>
    <button :class="{ active: type === 'paste' }" @click="type = 'paste'; search()">粘贴</button>
    <button :class="{ active: type === 'voice' }" @click="type = 'voice'; search()">语音</button>
    <button :class="{ active: type === 'image' }" @click="type = 'image'; search()">图片</button>

    <span style="width: 8px"></span>
    <select v-model="deviceId" class="input" @change="search()">
      <option value="">全部设备</option>
      <option v-for="d in devices" :key="d.id" :value="d.id">{{ d.name || d.model || d.id.slice(0, 8) }}</option>
    </select>

    <span style="width: 8px"></span>
    <button :class="{ active: days === null && !from && !to }" @click="quickDays(null)">全部时间</button>
    <button v-for="d in [7, 30, 90]" :key="d" :class="{ active: days === d }" @click="quickDays(d)">近{{ d }}天</button>
    <input v-model="from" type="datetime-local" class="input" @change="search()" />
    <span style="color: #747d8c">—</span>
    <input v-model="to" type="datetime-local" class="input" @change="search()" />

    <input v-model="q" type="search" class="input search" placeholder="关键词搜索内容 / IP…" @keyup.enter="search()" />
    <button class="btn" @click="search()">搜索</button>
    <button class="btn" @click="resetFilters()">重置</button>

    <label class="check"><input v-model="showAll" type="checkbox" @change="search()" /> 显示全部事件（含按键/删除）</label>
  </div>

  <div v-if="error" class="empty">加载失败：{{ error }}</div>
  <div v-else class="card" style="padding: 0">
    <table>
      <thead>
        <tr>
          <th style="width: 160px">时间</th>
          <th style="width: 70px">类型</th>
          <th>内容</th>
          <th style="width: 100px">来源 App</th>
          <th style="width: 130px">设备</th>
          <th style="width: 80px">网络</th>
          <th style="width: 120px">IP</th>
          <th style="width: 160px">地址</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in items" :key="item.id">
          <td style="white-space: nowrap">{{ fmtTime(item.occurred_at) }}</td>
          <td><span class="badge" :class="badge(item).cls">{{ badge(item).label }}</span></td>
          <td :title="item.text ?? ''" style="max-width: 420px; word-break: break-all">{{ displayText(item) }}</td>
          <td>{{ appName(item.package_name) }}</td>
          <td>
            <span v-if="deviceDetailLines(deviceOf(item.device_id)).length" class="dev" :title="deviceDetailLines(deviceOf(item.device_id)).join('\n')">
              {{ deviceLabel(deviceOf(item.device_id)) }}
            </span>
            <span v-else>{{ item.device_id.slice(0, 8) }}</span>
          </td>
          <td><span v-if="item.network_type" class="badge" :class="'net-' + item.network_type">{{ networkName(item.network_type) }}</span><span v-else>-</span></td>
          <td style="font-family: monospace; font-size: 12px">{{ item.client_ip || '-' }}</td>
          <td style="font-size: 12px; color: #57606f">{{ item.ip_location || (item.client_ip ? '解析中…' : '-') }}</td>
        </tr>
      </tbody>
    </table>
    <div v-if="!items.length && !loading" class="empty">没有符合条件的行为记录</div>

    <div class="pager">
      <span>共 {{ total }} 条</span>
      <button :disabled="page <= 1" @click="page--; load()">上一页</button>
      <span>{{ page }} / {{ totalPages }}</span>
      <button :disabled="page >= totalPages" @click="page++; load()">下一页</button>
    </div>
  </div>
</template>

<style scoped>
.input {
  padding: 6px 10px;
  border-radius: 8px;
  border: 1px solid #dfe4ea;
  font-size: 13px;
  background: #fff;
  color: #2f3542;
}
.search { width: 220px; }
.btn {
  padding: 6px 14px;
  border-radius: 8px;
  border: 1px solid #dfe4ea;
  background: #fff;
  cursor: pointer;
  font-size: 13px;
  color: #57606f;
}
.btn:hover { border-color: #3742fa; color: #3742fa; }
.check { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: #57606f; cursor: pointer; }
.badge { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 12px; white-space: nowrap; }
.badge.text { background: #dfe4ea; color: #2f3542; }
.badge.voice { background: #eccc68; color: #7d5a00; }
.badge.image { background: #ffa502; color: #fff; }
.badge.net-wifi { background: #d1f2eb; color: #148f77; }
.badge.net-mobile { background: #d4e6f1; color: #2874a6; }
.badge.net-ethernet { background: #d5d8dc; color: #566573; }
.dev { cursor: help; border-bottom: 1px dashed #a4b0be; }
.pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding: 12px 20px;
  font-size: 13px;
  color: #57606f;
}
.pager button {
  padding: 4px 12px;
  border-radius: 6px;
  border: 1px solid #dfe4ea;
  background: #fff;
  cursor: pointer;
  font-size: 13px;
}
.pager button:disabled { opacity: 0.4; cursor: not-allowed; }
</style>
