<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { api, deviceLabel, type DeviceRow, type LocationRow } from '../api';

const devices = ref<DeviceRow[]>([]);
const locations = ref<LocationRow[]>([]);
const days = ref(7);
const deviceId = ref('');
const loading = ref(false);
const error = ref('');
let refreshTimer: ReturnType<typeof setTimeout> | null = null;
let latestRequest = 0;
let disposed = false;
const unresolvedAddresses = computed(() => locations.value.filter(row => !row.address && row.address_status && row.address_status !== 'resolved'));

let map: L.Map | null = null;
let layer: L.LayerGroup = L.layerGroup();

const providers: Record<string, string> = { gps: 'GPS', network: '基站/Wi-Fi', fused: '融合' };

// 保持 YYYY-MM-DD HH:mm:ss 格式，固定北京时间，不依赖浏览器所在时区。
const beijingTimeFormatter = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
});

function formatBeijingTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '-' : beijingTimeFormatter.format(date);
}

async function loadDevices() {
  try {
    devices.value = (await api.devices()).devices;
  } catch {
    /* 设备列表失败不阻塞页面 */
  }
}

function clearRefresh() {
  if (refreshTimer !== null) clearTimeout(refreshTimer);
  refreshTimer = null;
}

function scheduleRefresh(requestFailed = false) {
  clearRefresh();
  if (disposed || (!requestFailed && unresolvedAddresses.value.length === 0)) return;
  let delay = requestFailed ? 10_000 : 5000;
  if (!requestFailed && unresolvedAddresses.value.every(row => row.address_status === 'failed')) {
    const retries = unresolvedAddresses.value.map(row => Date.parse(row.address_retry_at ?? ''));
    delay = Math.max(5000, Math.min(300_000, ...retries.map(time => Number.isFinite(time) ? time - Date.now() : 30_000)));
  }
  refreshTimer = setTimeout(() => {
    refreshTimer = null;
    if (document.hidden) { scheduleRefresh(requestFailed); return; }
    void loadLocations(true);
  }, delay);
}

function addressLabel(row: LocationRow): string {
  if (row.address) return row.address;
  if (row.address_status === 'failed') return '解析失败，稍后重试';
  if (row.address_status === 'resolving') return '排队解析中…';
  if (row.address_status === 'pending') return '等待解析';
  return '尚未解析';
}

async function loadLocations(background = false) {
  clearRefresh();
  const request = ++latestRequest;
  if (!background) loading.value = true;
  let failed = false;
  try {
    const data = await api.locations({ device_id: deviceId.value || undefined, days: days.value, limit: 500 });
    if (disposed || request !== latestRequest) return;
    const mapChanged = data.locations.length !== locations.value.length || data.locations.some((row, index) => {
      const previous = locations.value[index];
      return row.id !== previous?.id || row.address !== previous.address;
    });
    locations.value = data.locations;
    error.value = '';
    // 自动更新状态时不重画地图；地址变化时更新标记，但保留用户缩放和中心点。
    if (!background || mapChanged) renderMap(!background);
  } catch (e) {
    if (disposed || request !== latestRequest) return;
    failed = true;
    error.value = (e as Error).message;
  } finally {
    if (!disposed && request === latestRequest) {
      loading.value = false;
      scheduleRefresh(failed);
    }
  }
}

function renderMap(recenter = true) {
  if (!map) return;
  if (layer) layer.remove();
  layer = L.layerGroup().addTo(map);

  const pts = [...locations.value].reverse(); // 按时间正序连线
  if (pts.length === 0) return;
  if (pts.length > 1) {
    L.polyline(
      pts.map((p) => [Number(p.latitude), Number(p.longitude)]),
      { color: '#3742fa', weight: 3, opacity: 0.7 },
    ).addTo(layer);
  }
  pts.forEach((p, i) => {
    const lat = Number(p.latitude);
    const lng = Number(p.longitude);
    const isFirst = i === 0;
    const isLast = i === pts.length - 1;
    const color = isFirst ? '#2ecc71' : isLast ? '#e74c3c' : '#3742fa';
    L.circleMarker([lat, lng], { radius: isFirst || isLast ? 8 : 5, color: '#fff', weight: 2, fillColor: color, fillOpacity: 0.9 })
      .addTo(layer)
      .bindPopup(
        `<b>${p.address ?? `${lat.toFixed(4)}, ${lng.toFixed(4)}`}</b><br>` +
          `${formatBeijingTime(p.occurred_at)}（北京时间）<br>` +
          `${providers[p.provider ?? ''] ?? p.provider ?? '-'} · 精度 ${p.accuracy ?? '-'}m${p.speed != null ? ` · 速度 ${Number(p.speed).toFixed(1)}m/s` : ''}`,
      );
  });
  const last = pts[pts.length - 1];
  if (recenter) map.setView([Number(last.latitude), Number(last.longitude)], Math.max(map.getZoom(), 13));
}

function timeRange(row: LocationRow): string {
  return row.first_seen_at === row.last_seen_at
    ? formatBeijingTime(row.occurred_at)
    : `${formatBeijingTime(row.first_seen_at)} ~ ${formatBeijingTime(row.last_seen_at)}`;
}

watch([days, deviceId], () => loadLocations());

onMounted(() => {
  map = L.map('loc-map').setView([23.13, 113.26], 5);
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '© OpenStreetMap',
  }).addTo(map);
  loadDevices();
  loadLocations();
});

onBeforeUnmount(() => {
  disposed = true;
  latestRequest += 1;
  clearRefresh();
  layer.remove();
  map?.remove();
  map = null;
});

const summary = computed(() => {
  const n = locations.value.length;
  if (n === 0) return '暂无位置数据';
  const t = timeRange(locations.value[0]);
  return `共 ${n} 个不同位置，最近记录（北京时间）：${t}`;
});
</script>

<template>
  <div>
    <div class="toolbar">
      <select v-model="deviceId">
        <option value="">全部设备</option>
        <option v-for="d in devices" :key="d.id" :value="d.id">{{ deviceLabel(d) }}</option>
      </select>
      <select v-model="days">
        <option :value="1">近 1 天</option>
        <option :value="7">近 7 天</option>
        <option :value="30">近 30 天</option>
        <option :value="90">近 90 天</option>
      </select>
      <button class="btn" :disabled="loading" @click="loadLocations()">刷新</button>
      <span class="summary">{{ summary }}</span>
      <span v-if="error" class="err">{{ error }}</span>
    </div>

    <p v-if="unresolvedAddresses.length" class="address-hint" role="status">
      还有 {{ unresolvedAddresses.length }} 条地址未完成，页面会自动更新；解析失败后按提示时间重试。
    </p>
    <div id="loc-map" class="map"></div>

    <table class="table">
      <thead>
        <tr>
          <th>#</th>
          <th>位置（坐标）</th>
          <th>地址</th>
          <th>来源</th>
          <th>精度</th>
          <th>速度</th>
          <th>时间范围（北京时间）</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(r, i) in locations" :key="r.id">
          <td>{{ locations.length - i }}</td>
          <td class="mono">{{ Number(r.latitude).toFixed(4) }}, {{ Number(r.longitude).toFixed(4) }}</td>
          <td>
            <span :class="{ 'address-failed': !r.address && r.address_status === 'failed' }">{{ addressLabel(r) }}</span>
            <template v-if="!r.address && r.address_status === 'failed'">
              <small v-if="r.address_error" class="address-detail">{{ r.address_error }}</small>
              <small v-if="r.address_retry_at" class="address-detail">下次重试：{{ formatBeijingTime(r.address_retry_at) }}（北京时间）</small>
            </template>
          </td>
          <td>{{ providers[r.provider ?? ''] ?? r.provider ?? '-' }}</td>
          <td>{{ r.accuracy ?? '-' }} m</td>
          <td>{{ r.speed != null ? Number(r.speed).toFixed(1) + ' m/s' : '-' }}</td>
          <td class="mono">{{ timeRange(r) }}</td>
        </tr>
        <tr v-if="!loading && locations.length === 0">
          <td colspan="7" class="empty">暂无位置数据 — 输入法端每分钟上报，位置变化时自动记录</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.toolbar select { padding: 6px 8px; border: 1px solid #ddd; border-radius: 6px; background: #fff; }
.btn { padding: 6px 14px; background: #3742fa; color: #fff; border: none; border-radius: 6px; cursor: pointer; }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.summary { color: #666; font-size: 13px; }
.address-hint { margin: 0 0 12px; color: #666; font-size: 13px; }
.address-failed { color: #c0392b; }
.address-detail { display: block; margin-top: 4px; color: #777; font-size: 12px; }
.err { color: #e74c3c; font-size: 13px; }
.map { height: 380px; border-radius: 8px; border: 1px solid #e5e5e5; margin-bottom: 16px; background: #f6f6f6; }
.table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 8px; overflow: hidden; }
.table th, .table td { padding: 10px 12px; border-bottom: 1px solid #f0f0f0; text-align: left; font-size: 13px; }
.table th { background: #fafafa; color: #555; font-weight: 600; white-space: nowrap; }
.mono { font-family: 'SF Mono', Consolas, monospace; font-size: 12px; }
.empty { text-align: center; color: #999; padding: 24px; }
</style>
