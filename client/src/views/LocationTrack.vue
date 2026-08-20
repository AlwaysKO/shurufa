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

let map: L.Map | null = null;
let layer: L.LayerGroup = L.layerGroup();

const providers: Record<string, string> = { gps: 'GPS', network: '基站/Wi-Fi', fused: '融合' };

async function loadDevices() {
  try {
    devices.value = (await api.devices()).devices;
  } catch {
    /* 设备列表失败不阻塞页面 */
  }
}

async function loadLocations() {
  loading.value = true;
  error.value = '';
  try {
    const data = await api.locations({ device_id: deviceId.value || undefined, days: days.value, limit: 500 });
    locations.value = data.locations;
    renderMap();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}

function renderMap() {
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
          `${p.occurred_at.replace('T', ' ').slice(0, 19)}<br>` +
          `${providers[p.provider ?? ''] ?? p.provider ?? '-'} · 精度 ${p.accuracy ?? '-'}m${p.speed != null ? ` · 速度 ${Number(p.speed).toFixed(1)}m/s` : ''}`,
      );
  });
  const last = pts[pts.length - 1];
  map.setView([Number(last.latitude), Number(last.longitude)], Math.max(map.getZoom(), 13));
}

function timeRange(row: LocationRow): string {
  const fmt = (s: string) => s.replace('T', ' ').slice(0, 19);
  return row.first_seen_at === row.last_seen_at
    ? fmt(row.occurred_at)
    : `${fmt(row.first_seen_at)} ~ ${fmt(row.last_seen_at)}`;
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
  layer.remove();
  map?.remove();
  map = null;
});

const summary = computed(() => {
  const n = locations.value.length;
  if (n === 0) return '暂无位置数据';
  const t = timeRange(locations.value[0]);
  return `共 ${n} 个不同位置，最近记录：${t}`;
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
      <button class="btn" :disabled="loading" @click="loadLocations">刷新</button>
      <span class="summary">{{ summary }}</span>
      <span v-if="error" class="err">{{ error }}</span>
    </div>

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
          <th>时间范围</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(r, i) in locations" :key="r.id">
          <td>{{ locations.length - i }}</td>
          <td class="mono">{{ Number(r.latitude).toFixed(4) }}, {{ Number(r.longitude).toFixed(4) }}</td>
          <td>{{ r.address ?? '解析中…' }}</td>
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
.err { color: #e74c3c; font-size: 13px; }
.map { height: 380px; border-radius: 8px; border: 1px solid #e5e5e5; margin-bottom: 16px; background: #f6f6f6; }
.table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 8px; overflow: hidden; }
.table th, .table td { padding: 10px 12px; border-bottom: 1px solid #f0f0f0; text-align: left; font-size: 13px; }
.table th { background: #fafafa; color: #555; font-weight: 600; white-space: nowrap; }
.mono { font-family: 'SF Mono', Consolas, monospace; font-size: 12px; }
.empty { text-align: center; color: #999; padding: 24px; }
</style>
