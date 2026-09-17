<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { api, appName, deviceDetailLines, deviceLabel, eventTypeName, networkName, type ActivityItem, type DeviceRow } from '../api';

const items = ref<ActivityItem[]>([]);
const devices = ref<DeviceRow[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = 20;
const error = ref('');
const deleteError = ref('');
const deleteMessage = ref('');
const deletingId = ref<string | null>(null);
let unmounted = false;

const type = ref<'all' | 'text' | 'paste' | 'voice' | 'image' | 'delete'>('all');
const deviceId = ref('');
const days = ref<number | null>(null);
const from = ref('');
const to = ref('');
const q = ref('');
const showAll = ref(false);
const grouped = ref(true);
const loading = ref(false);
let latestRequest = 0;

async function load(): Promise<void> {
  const request = ++latestRequest;
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
      grouped: grouped.value && !showAll.value,
      page: page.value,
      page_size: pageSize,
    });
    if (request !== latestRequest) return;
    items.value = res.items;
    total.value = res.total;
    // 删除末页最后一行时，按服务器返回的总数回到有效页。
    const lastPage = Math.max(1, Math.ceil(res.total / pageSize));
    if (page.value > lastPage) {
      page.value = lastPage;
      await load();
    }
  } catch (e) {
    if (request === latestRequest) error.value = (e as Error).message;
  } finally {
    if (request === latestRequest) loading.value = false;
  }
}

function search() {
  page.value = 1;
  load();
}

function changeMode(value: boolean) {
  grouped.value = value && !showAll.value;
  search();
}

function changeUnderlyingEvents() {
  if (showAll.value) grouped.value = false;
  search();
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
  grouped.value = true;
  search();
}

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)));
const fmtTime = (s: string) => new Date(s).toLocaleString('zh-CN', { hour12: false });
const deviceOf = (id: string) => devices.value.find((d) => d.id === id);

/** 类型徽标：语音/图片/文字（颜色区分） */
const badge = (item: ActivityItem) => {
  if (['delete', 'external_delete'].includes(item.event_type)) return { cls: 'badge deletion', label: '删除' };
  if (item.content_type === 'voice') return { cls: 'badge voice', label: '语音' };
  if (item.content_type === 'image') return { cls: 'badge image', label: '图片' };
  return { cls: 'badge text', label: eventTypeName(item.event_type) };
};

const displayText = (item: ActivityItem) => {
  if (item.text) return item.text;
  if (['delete', 'external_delete'].includes(item.event_type)) return '[删除内容未采集]';
  if (item.content_type === 'image') return '[图片]';
  return '[空]';
};

const hasCompleteEdit = (item: ActivityItem) => grouped.value && item.edit_complete === true && item.text_after != null;
const summaryText = (item: ActivityItem) => hasCompleteEdit(item)
  ? (item.text_after === '' ? '（已清空输入框）' : item.text_after!)
  : displayText(item);

const snapshotText = (value: string | null | undefined) => value == null ? '（未采集）' : value === '' ? '（空输入框）' : value;

async function deleteRecord(item: ActivityItem) {
  if (loading.value || deletingId.value || unmounted) return;
  const mode = grouped.value && !showAll.value ? 'group' : 'single';
  const ids = mode === 'group' && item.edit_events?.length ? item.edit_events.map(event => event.id) : [item.id];
  const preview = summaryText(item).slice(0, 160);
  const message = `确定永久删除${mode === 'group' ? '这段记录及其全部原始操作' : '这条原始操作'}吗？\n共 ${ids.length} 条原始记录。\n\n${preview}\n\n删除后无法撤销，仅影响当前后台，不联动手机或其他服务器副本。`;
  if (!confirm(message)) return;
  deletingId.value = item.id;
  deleteError.value = '';
  deleteMessage.value = '';
  try {
    const result = await api.deleteActivity(item.id, { confirm: 'DELETE', mode, event_ids: ids });
    if (unmounted) return;
    deleteMessage.value = `已删除 ${result.deleted} 条原始记录`;
    await load();
  } catch (error) {
    if (!unmounted) deleteError.value = (error as Error).message;
  } finally { deletingId.value = null; }
}

onBeforeUnmount(() => { unmounted = true; ++latestRequest; });

onMounted(async () => {
  // 支持 URL ?q= 预填搜索（词云等页面点击词跳转过来）
  const route = useRoute();
  if (typeof route.query.q === 'string' && route.query.q.trim()) {
    q.value = route.query.q.trim();
  }
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
  <div class="edit-mode">
    <div class="mode-buttons" role="group" aria-label="记录显示方式">
      <button data-testid="mode-grouped" :class="{ active: grouped }" :disabled="showAll" @click="changeMode(true)">整段编辑</button>
      <button data-testid="mode-raw" :class="{ active: !grouped }" @click="changeMode(false)">原始操作</button>
    </div>
    <p>{{ grouped ? '按同一输入框的编辑会话展示最新状态，删除、替换过程可展开查看。' : '逐条显示原始操作，不拼接为完整句子。' }} 不代表消息已发送。</p>
  </div>
  <div class="filters">
    <button :class="{ active: type === 'all' }" @click="type = 'all'; search()">全部</button>
    <button :class="{ active: type === 'text' }" @click="type = 'text'; search()">输入</button>
    <button :class="{ active: type === 'paste' }" @click="type = 'paste'; search()">粘贴</button>
    <button :class="{ active: type === 'voice' }" @click="type = 'voice'; search()">语音</button>
    <button :class="{ active: type === 'image' }" @click="type = 'image'; search()">图片</button>

    <button :class="{ active: type === 'delete' }" @click="type = 'delete'; search()">删除</button>

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

    <label class="check"><input v-model="showAll" data-testid="show-all" type="checkbox" @change="changeUnderlyingEvents()" /> 显示底层事件（含按键/拼音组合）</label>
  </div>

  <div v-if="deleteError" class="delete-notice delete-error" role="alert">删除失败：{{ deleteError }}</div>
  <div v-if="deleteMessage" class="delete-notice delete-success" role="status">{{ deleteMessage }}</div>
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
          <th style="width: 90px">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in items" :key="item.id">
          <td style="white-space: nowrap">{{ fmtTime(item.occurred_at) }}</td>
          <td><span class="badge" :class="badge(item).cls">{{ badge(item).label }}</span></td>
          <td style="max-width: 420px; word-break: break-all">
            <div class="event-text">{{ summaryText(item) }}</div>
            <small v-if="grouped" class="edit-status" :class="{ incomplete: !hasCompleteEdit(item) }">
              {{ hasCompleteEdit(item) ? '完整快照 · 当前整段' : '片段 / 缺少完整编辑证据' }}
            </small>
            <details v-if="grouped && item.edit_events?.length" class="edit-snapshots">
              <summary>查看全部编辑过程 · {{ item.edit_count ?? item.edit_events.length }} 次操作</summary>
              <ol class="edit-history">
                <li v-for="operation in item.edit_events" :key="operation.id" class="edit-operation">
                  <div class="operation-meta">{{ fmtTime(operation.occurred_at) }} · {{ eventTypeName(operation.event_type) }}<span v-if="operation.sequence_no != null"> · #{{ operation.sequence_no }}</span></div>
                  <div class="operation-text">{{ displayText(operation) }}</div>
                  <dl>
                    <dt>编辑前</dt><dd>{{ snapshotText(operation.text_before) }}</dd>
                    <dt>编辑后</dt><dd>{{ snapshotText(operation.text_after) }}</dd>
                  </dl>
                </li>
              </ol>
              <small>保留原始输入、删除与替换记录；缺失快照不补猜。</small>
            </details>
            <details v-else-if="item.text_before != null || item.text_after != null" class="edit-snapshots">
              <summary>查看编辑前后</summary>
              <dl>
                <dt>编辑前</dt><dd>{{ snapshotText(item.text_before) }}</dd>
                <dt>编辑后</dt><dd>{{ snapshotText(item.text_after) }}</dd>
              </dl>
              <small>设备上报的文本快照，不代表消息已发送。</small>
            </details>
          </td>
          <td>{{ appName(item.package_name) }}</td>
          <td>
            <span v-if="deviceDetailLines(deviceOf(item.device_id)).length" class="dev" :title="deviceDetailLines(deviceOf(item.device_id)).join('\n')">
              {{ deviceLabel(deviceOf(item.device_id)) }}
            </span>
            <span v-else>{{ item.device_id.slice(0, 8) }}</span>
          </td>
          <td><span v-if="item.network_type" class="badge" :class="'net-' + item.network_type">{{ networkName(item.network_type) }}</span><span v-else>-</span></td>
          <td style="font-family: monospace; font-size: 12px">{{ item.client_ip || '-' }}</td>
          <td style="font-size: 12px; color: #57606f">{{ item.ip_location || (item.client_ip ? '暂未解析' : '-') }}</td>
          <td>
            <button class="delete-record" :data-testid="`delete-activity-${item.id}`"
              :disabled="loading || deletingId !== null" @click="deleteRecord(item)">
              {{ deletingId === item.id ? '删除中…' : grouped ? '删除整段' : '删除' }}
            </button>
          </td>
        </tr>
      </tbody>
    </table>
    <div v-if="!items.length && !loading" class="empty">没有符合条件的行为记录</div>

    <div class="pager">
      <span>共 {{ total }} {{ grouped ? '组' : '条' }} · 每页 {{ pageSize }} {{ grouped ? '组' : '条' }}</span>
      <button :disabled="page <= 1" @click="page--; load()">上一页</button>
      <span>{{ page }} / {{ totalPages }}</span>
      <button :disabled="page >= totalPages" @click="page++; load()">下一页</button>
    </div>
  </div>
</template>

<style scoped>
.delete-record { padding: 5px 10px; border: 1px solid #ffc9c9; border-radius: 5px; background: #fff5f5; color: #c0392b; cursor: pointer; white-space: nowrap; }
.delete-record:disabled { opacity: 0.5; cursor: not-allowed; }
.delete-notice { padding: 10px 14px; margin-bottom: 12px; border-radius: 6px; font-size: 13px; }
.delete-error { color: #a52a2a; background: #fff0f0; }
.delete-success { color: #23704b; background: #edf9f2; }
.edit-mode { margin-bottom: 16px; padding: 14px 16px; background: #fff; border: 1px solid #e7eaf2; border-radius: 12px; }
.mode-buttons { display: inline-flex; gap: 4px; padding: 4px; border-radius: 9px; background: #f3f5fb; }
.mode-buttons button { border: 0; border-radius: 6px; padding: 7px 16px; background: transparent; color: #65708a; cursor: pointer; }
.mode-buttons button.active { background: #fff; color: #4b57ce; box-shadow: 0 1px 4px #23305615; }
.mode-buttons button:disabled { opacity: 0.45; cursor: not-allowed; }
.edit-mode p { margin: 10px 0 0; color: #747d8c; font-size: 12px; line-height: 1.7; }
.edit-status { display: block; margin-top: 5px; color: #38836a; font-size: 11px; }
.edit-status.incomplete { color: #9a743e; }
.edit-history { margin: 12px 0; padding-left: 22px; }
.edit-operation { padding: 10px 0; border-bottom: 1px solid #e5e8f0; }
.edit-operation:last-child { border-bottom: 0; }
.operation-meta { color: #747d8c; font-size: 11px; }
.operation-text { margin-top: 6px; white-space: pre-wrap; overflow-wrap: anywhere; }

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
.badge.deletion { background: #fff0ef; color: #b5473e; }
.event-text, .edit-snapshots dd { white-space: pre-wrap; overflow-wrap: anywhere; }
.edit-snapshots { margin-top: 8px; padding: 8px 10px; background: #f7f8fc; border-radius: 8px; font-size: 12px; }
.edit-snapshots summary { cursor: pointer; color: #5865b5; }
.edit-snapshots dl { display: grid; grid-template-columns: 48px minmax(0, 1fr); gap: 8px; }
.edit-snapshots dt, .edit-snapshots small { color: #747d8c; }
.edit-snapshots dd { margin: 0; }
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
