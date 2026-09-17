<script setup lang="ts">
import { useConfirmation } from '../confirmation';
import { computed, onMounted, ref } from 'vue';
import { api } from '../api';

const askConfirmation = useConfirmation();

const exporting = ref(false);
const exportMsg = ref('');
const busy = ref(false);
const result = ref('');
const confirmText = ref('');
const scope = ref<'events' | 'all'>('events');
const from = ref('');
const to = ref('');
const pkg = ref('');
const collectorBaseUrl = ref('');
const collectorBusy = ref(false);
const collectorMessage = ref('');

const canCleanup = computed(() => confirmText.value === 'DELETE' && !busy.value);

async function loadCollectorSetting() {
  collectorMessage.value = '';
  try { collectorBaseUrl.value = (await api.collectorSetting()).collector_base_url; }
  catch (e) { collectorMessage.value = `读取失败：${(e as Error).message}`; }
}

async function saveCollectorSetting() {
  collectorBusy.value = true;
  collectorMessage.value = '';
  try {
    const result = await api.updateCollectorSetting(collectorBaseUrl.value);
    collectorBaseUrl.value = result.collector_base_url;
    collectorMessage.value = '已保存。手机下一次连接当前线上后台时会迁移尚未确认的线上队列。';
  } catch (e) { collectorMessage.value = `保存失败：${(e as Error).message}`; }
  finally { collectorBusy.value = false; }
}

onMounted(loadCollectorSetting);

/** 导出全部数据为 JSON 文件下载 */
async function doExport() {
  exporting.value = true;
  exportMsg.value = '';
  try {
    const data = await api.exportData();
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `shurufa-export-${data.exported_at.slice(0, 10)}.json`;
    a.click();
    URL.revokeObjectURL(url);
    const c = data.counts;
    exportMsg.value = `已导出：${c.events} 条事件、${c.devices} 台设备、${c.sessions} 个会话、${c.phrases} 条词频、${c.completions} 条补全、${c.locations} 条位置`;
  } catch (e) {
    exportMsg.value = `导出失败：${(e as Error).message}`;
  } finally {
    exporting.value = false;
  }
}

async function doCleanup() {
  if (!canCleanup.value) return;
  const payload = {
    confirm: confirmText.value,
    scope: scope.value,
    from: from.value || undefined,
    to: to.value || undefined,
    package_name: pkg.value || undefined,
  };
  const timeRange = payload.from || payload.to ? `${payload.from || '不限起始'} ～ ${payload.to || '不限结束'}` : '全部时间';
  const message = `确定永久清理当前用户的数据吗？\n范围：${payload.scope === 'all' ? '输入事件及会话、位置、统计等关联数据' : '仅输入事件'}\n时间：${timeRange}\n应用：${payload.package_name || '全部应用'}\n\n删除后无法恢复，建议先导出备份。`;
  if (!(await askConfirmation(message, { title: '确认清理数据', confirmText: '确认清理' }))) return;
  if (!canCleanup.value) return;
  busy.value = true;
  result.value = '';
  try {
    const r = await api.cleanup(payload);
    const d = r.deleted;
    const parts = [`已删除 ${d.events ?? 0} 条事件`];
    if (r.scope === 'all') {
      if (d.sessions) parts.push(`${d.sessions} 个会话`);
      if (d.locations) parts.push(`${d.locations} 条位置`);
      if (d.phrases) parts.push(`${d.phrases} 条词频统计`);
      if (d.completions) parts.push(`${d.completions} 条补全候选`);
    }
    result.value = parts.join('、') + '（不可恢复）';
    confirmText.value = '';
  } catch (e) {
    result.value = `清理失败：${(e as Error).message}`;
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="card">
    <h3>线上采集 API 域名</h3>
    <p class="desc">只填写 HTTPS 根地址，例如 https://collector.example.com。请先保持旧域名可用，等手机取得新配置后再停用旧域名。</p>
    <form class="collector-form" @submit.prevent="saveCollectorSetting">
      <input v-model.trim="collectorBaseUrl" type="url" required placeholder="https://collector.example.com" />
      <button class="primary" type="submit" :disabled="collectorBusy">{{ collectorBusy ? '保存中…' : '保存域名' }}</button>
    </form>
    <p v-if="collectorMessage" class="msg" :class="{ err: collectorMessage.includes('失败') }">{{ collectorMessage }}</p>
  </div>

  <div class="card">
    <h3>导出数据（JSON）</h3>
    <p class="desc">将设备、事件日志、词频统计、补全模型、位置轨迹全部导出为 JSON 文件，用于备份或迁移。</p>
    <button class="primary" :disabled="exporting" @click="doExport">{{ exporting ? '导出中…' : '导出全部数据' }}</button>
    <p v-if="exportMsg" class="msg" :class="{ err: exportMsg.startsWith('导出失败') }">{{ exportMsg }}</p>
  </div>

  <div class="card danger">
    <h3>清理数据</h3>
    <p class="desc">删除不可恢复。建议先导出备份再清理。</p>

    <div class="form-row">
      <label>范围</label>
      <div class="radios">
        <label><input v-model="scope" type="radio" value="events" /> 仅事件日志</label>
        <label><input v-model="scope" type="radio" value="all" /> 全部采集数据（含词频/补全/位置，统计从零重建）</label>
      </div>
    </div>

    <div class="form-row">
      <label>时间范围</label>
      <input v-model="from" type="date" /> ～ <input v-model="to" type="date" />
    </div>
    <div class="form-row">
      <label>应用包名</label>
      <input v-model="pkg" type="text" placeholder="如 com.tencent.mm（留空 = 全部应用）" />
    </div>

    <div class="form-row">
      <label>确认</label>
      <input v-model="confirmText" type="text" placeholder="输入 DELETE 以启用清理按钮" class="confirm-input" />
    </div>

    <button class="danger-btn" :disabled="!canCleanup" @click="doCleanup">{{ busy ? '清理中…' : '确认清理' }}</button>
    <p v-if="result" class="msg err">{{ result }}</p>
  </div>
</template>

<style scoped>
.desc { font-size: 13px; color: #747d8c; margin-bottom: 14px; }
.form-row { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; font-size: 13px; }
.form-row label { width: 72px; color: #57606f; flex-shrink: 0; }
.form-row input[type='text'], .form-row input[type='date'] { padding: 6px 10px; border: 1px solid #dfe4ea; border-radius: 6px; font-size: 13px; }
.form-row input[type='text'] { min-width: 240px; }
.radios { display: flex; flex-direction: column; gap: 6px; }
.radios label { display: flex; align-items: center; gap: 6px; }
.confirm-input { border-color: #ff4757 !important; }
button.primary { padding: 8px 18px; border: none; border-radius: 6px; background: #3742fa; color: #fff; font-size: 13px; cursor: pointer; }
button.primary:disabled { opacity: .5; cursor: not-allowed; }
button.danger-btn { padding: 8px 18px; border: none; border-radius: 6px; background: #ff4757; color: #fff; font-size: 13px; cursor: pointer; }
button.danger-btn:disabled { opacity: .4; cursor: not-allowed; }
.danger { border-left: 4px solid #ff4757; }
.msg { font-size: 13px; margin-top: 12px; color: #2ed573; }
.msg.err { color: #ff4757; }
.collector-form { display:flex; gap:10px; align-items:center; flex-wrap:wrap; }
.collector-form input { min-width:min(460px, 100%); padding:8px 10px; border:1px solid #dfe4ea; border-radius:6px; }
</style>
