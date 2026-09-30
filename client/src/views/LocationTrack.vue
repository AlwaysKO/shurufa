<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { api, deviceLabel, type DeviceRow, type LocationRow } from '../api';
import { analyzeLocations, locationDay, wifiLabel, networkLabel, contextDetails, durationLabel, escapeLocationHtml } from '../locationAnalysis';

const devices = ref<DeviceRow[]>([]);
const locations = ref<LocationRow[]>([]);
const deviceId = ref('');
const loading = ref(false);
const error = ref('');
const today = ref(locationDay(new Date().toISOString()));
const selectedDay = ref(today.value);
const endDay = ref(today.value);
const singleDay = computed(() => selectedDay.value === endDay.value);
const hasMore = ref(false);
const visibleLocations = computed(() => locations.value);
const analysis = computed(() => analyzeLocations(visibleLocations.value));
const routeSegments = computed(() => analysis.value.segments.filter(segment => segment.length > 1));
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
  today.value = locationDay(new Date().toISOString());
  if (!background) {
    loading.value = true;
    locations.value = [];
    hasMore.value = false;
    renderMap(false);
  }
  let failed = false;
  try {
    const data = await api.locations({ device_id: deviceId.value || undefined, from: selectedDay.value, to: endDay.value, limit: 1000 });
    if (disposed || request !== latestRequest) return;
    const mapChanged = data.locations.length !== locations.value.length || data.locations.some((row, index) => {
      const previous = locations.value[index];
      return row.id !== previous?.id || row.address !== previous.address || row.last_seen_at !== previous.last_seen_at;
    });
    locations.value = data.locations;
    hasMore.value = data.has_more === true;
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

  const pts = [...visibleLocations.value].reverse().filter(p => Number.isFinite(Number(p.latitude)) && Math.abs(Number(p.latitude)) <= 90 && Number.isFinite(Number(p.longitude)) && Math.abs(Number(p.longitude)) <= 180);
  if (pts.length === 0) return;
  for (const segment of routeSegments.value) {
    L.polyline(
      segment.map((p) => [Number(p.latitude), Number(p.longitude)]),
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
        `<b>${escapeLocationHtml(p.address ?? `${lat.toFixed(4)}, ${lng.toFixed(4)}`)}</b><br>` +
          `${formatBeijingTime(p.occurred_at)}（北京时间）<br>` +
          `${escapeLocationHtml(providers[p.provider ?? ''] ?? p.provider ?? '-')} · 精度 ${escapeLocationHtml(String(p.accuracy ?? '-'))}m${p.speed != null ? ` · 速度 ${(Number(p.speed) * 3.6).toFixed(1)} km/h` : ''}<br>` +
          `Wi-Fi：${escapeLocationHtml(wifiLabel(p))}<br>${escapeLocationHtml(contextDetails(p))}`,
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

function chooseDays(days: number) {
  today.value = locationDay(new Date().toISOString());
  const start = shiftDay(today.value, -(days - 1));
  const unchanged = selectedDay.value === start && endDay.value === today.value;
  selectedDay.value = start;
  endDay.value = today.value;
  if (unchanged) void loadLocations();
}

function changeDate(event: Event, edge: 'start' | 'end' = 'start') {
  const input = event.target as HTMLInputElement;
  today.value = locationDay(new Date().toISOString());
  const value = input.value;
  if (!value) {
    chooseDays(1);
  } else if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || locationDay(`${value}T00:00:00+08:00`) !== value || value > today.value) {
    error.value = '请选择有效且不晚于今天的日期';
  } else if (edge === 'start') {
    selectedDay.value = value;
    if (endDay.value < value) endDay.value = value;
  } else {
    endDay.value = value;
    if (selectedDay.value > value) selectedDay.value = value;
  }
  input.value = edge === 'start' ? selectedDay.value : endDay.value;
}

function shiftDay(value: string, offset: number) {
  return locationDay(new Date(Date.parse(`${value}T00:00:00+08:00`) + offset * 86_400_000).toISOString());
}

function moveDay(offset: number) {
  today.value = locationDay(new Date().toISOString());
  const nextEnd = shiftDay(endDay.value, offset);
  if (nextEnd > today.value) return;
  selectedDay.value = shiftDay(selectedDay.value, offset);
  endDay.value = nextEnd;
}

watch([selectedDay, endDay, deviceId], () => loadLocations());

function focusPoint(point: LocationRow) {
  map?.setView([Number(point.latitude), Number(point.longitude)], 16);
}
function rowDevice(point: LocationRow) {
  const device = devices.value.find(item => item.id === point.device_id);
  return device ? deviceLabel(device) : point.device_id;
}

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
  const n = visibleLocations.value.length;
  if (n === 0) return '暂无位置数据';
  const t = timeRange(visibleLocations.value[0]);
  return `共 ${n} 条采样，最近记录（北京时间）：${t}`;
});
</script>

<template>
  <div>
    <div class="toolbar">
      <select v-model="deviceId">
        <option value="">全部设备</option>
        <option v-for="d in devices" :key="d.id" :value="d.id">{{ deviceLabel(d) }}</option>
      </select>
      <button class="btn" data-testid="location-prev-day" @click="moveDay(-1)">{{ singleDay ? '前一天' : '前移一天' }}</button>
      <label>开始日期（北京时间）
        <input type="date" data-testid="location-date" :value="selectedDay" :max="today" @change="changeDate" />
      </label>
      <label>结束日期（北京时间）
        <input type="date" data-testid="location-end-date" :value="endDay" :max="today" @change="changeDate($event, 'end')" />
      </label>
      <button class="btn" data-testid="location-next-day" :disabled="endDay >= today" @click="moveDay(1)">{{ singleDay ? '后一天' : '后移一天' }}</button>
      <button class="btn" @click="chooseDays(1)">今天</button>
      <button class="btn" @click="chooseDays(7)">近7天</button>
      <button class="btn" @click="chooseDays(30)">近30天</button>
      <button class="btn" :disabled="loading" @click="loadLocations()">刷新</button>
      <span class="summary">{{ summary }}</span>
      <span v-if="error" class="err">{{ error }}</span>
    </div>

    <p class="analysis-hint">新版手机仅在位置有效变化时上报，位置不变不重复上报。均衡模式移动时约 30 秒采样，停留后约 5 分钟检查；实际频率受权限、信号和系统限制。静止缺报无法确认准确停留或 Wi-Fi 连接时长。路线连线不代表实际经过的道路；超过 15 分钟的缺口、跨日和不同设备分段显示。</p>
    <p v-if="hasMore" class="coverage-warning" role="status">所选日期范围记录超过 1000 条，仅分析已加载的最近记录。可缩短日期范围或选择单台设备；这里的里程与停留不代表完整行程。</p>
    <div v-if="visibleLocations.length" class="location-stats">
      <div><strong>{{ routeSegments.length }}</strong><span>有连续观测的路线段</span></div>
      <div><strong>{{ (analysis.distanceMeters / 1000).toFixed(2) }} km</strong><span>过滤漂移后的估算里程</span></div>
      <div><strong>{{ analysis.stays.length }}</strong><span>估算停留（至少 5 分钟）</span></div>
      <div><strong>{{ analysis.excludedPoints }}</strong><span>低精度或无效点未参与分析</span></div>
    </div>

    <p v-if="unresolvedAddresses.length" class="address-hint" role="status">
      还有 {{ unresolvedAddresses.length }} 条地址未完成，页面会自动更新；解析失败后按提示时间重试。
    </p>
    <div id="loc-map" class="map"></div>

    <div v-if="visibleLocations.length" class="location-insights">
      <section>
        <h3>估算停留</h3>
        <p class="analysis-hint">同一区域的连续观测跨度。缺失期间无法确认是否离开；最后一次观测之后不继续计时。</p>
        <p v-if="!analysis.stays.length" class="analysis-hint">暂无足够连续的停留记录。</p>
        <button v-for="stay in analysis.stays" :key="`${stay.start.device_id}-${stay.start.id}`" class="insight-row" @click="focusPoint(stay.start)">
          <strong>{{ durationLabel(stay.durationMs) }} · {{ stay.start.address || '地址未解析' }}</strong>
          <span>{{ formatBeijingTime(stay.start.occurred_at) }} ～ {{ formatBeijingTime(stay.end.occurred_at) }}</span>
          <span>{{ rowDevice(stay.start) }} · {{ stay.points.length }} 次观测 · 点击定位</span>
        </button>
      </section>
      <section>
        <h3>Wi-Fi 观测跨度</h3>
        <p class="analysis-hint">采样时连接相同网络的时间跨度，不保证两次采样之间一直连接，也不能等同于在场时间。</p>
        <p v-if="!analysis.wifiSessions.length" class="analysis-hint">暂无连续的 Wi-Fi 记录；历史数据需手机升级后逐步补充新采样。</p>
        <div v-for="session in analysis.wifiSessions" :key="`${session.start.device_id}-${session.start.id}`" class="insight-row">
          <strong>{{ wifiLabel(session.start) }} · {{ durationLabel(session.durationMs) }}</strong>
          <span>{{ formatBeijingTime(session.start.occurred_at) }} ～ {{ formatBeijingTime(session.end.occurred_at) }}</span>
          <span>{{ rowDevice(session.start) }} · {{ session.start.context?.wifi?.bssid ? '相同热点标识' : '仅名称一致，热点标识不可读取' }}</span>
        </div>
      </section>
    </div>
    <details v-if="routeSegments.length" class="route-details">
      <summary>查看 {{ routeSegments.length }} 段路线的起止时间（北京时间）</summary>
      <button v-for="(segment, index) in routeSegments" :key="`${segment[0].device_id}-${segment[0].id}`" class="insight-row" @click="focusPoint(segment[0])">
        <strong>路线 {{ index + 1 }} · {{ rowDevice(segment[0]) }}</strong>
        <span>{{ formatBeijingTime(segment[0].occurred_at) }} ～ {{ formatBeijingTime(segment[segment.length - 1].occurred_at) }} · {{ segment.length }} 次观测</span>
        <span>{{ segment[0].address || '地址未解析' }} → {{ segment[segment.length - 1].address || '地址未解析' }}</span>
      </button>
    </details>

    <div class="table-scroll">
    <table class="table">
      <thead>
        <tr>
          <th>#</th>
          <th>位置（坐标）</th>
          <th>地址</th>
          <th>来源</th>
          <th>精度</th>
          <th>速度</th>
          <th>Wi-Fi / 网络</th>
          <th>设备状态</th>
          <th>时间范围（北京时间）</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(r, i) in visibleLocations" :key="r.id">
          <td>{{ visibleLocations.length - i }}<small class="address-detail">{{ rowDevice(r) }}</small></td>
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
          <td>{{ r.speed != null ? (Number(r.speed) * 3.6).toFixed(1) + ' km/h' : '-' }}</td>
          <td class="context-cell">
            <strong>{{ wifiLabel(r) }}</strong>
            <small class="address-detail">{{ networkLabel(r) }}</small>
            <small v-if="r.context?.wifi?.rssi != null" class="address-detail">信号 {{ r.context.wifi.rssi }} dBm</small>
            <small v-if="r.context?.wifi?.frequency_mhz != null" class="address-detail">频率 {{ r.context.wifi.frequency_mhz }} MHz</small>
            <details v-if="r.context?.wifi?.bssid || r.context?.wifi?.link_speed_mbps != null">
              <summary>连接详情</summary>
              <small v-if="r.context?.wifi?.bssid" class="address-detail">热点 {{ r.context.wifi.bssid }}</small>
              <small v-if="r.context?.wifi?.link_speed_mbps != null" class="address-detail">链路速率 {{ r.context.wifi.link_speed_mbps }} Mbps（非网速实测）</small>
            </details>
          </td>
          <td class="context-cell">{{ contextDetails(r) }}
            <small v-if="r.context?.capture_mode" class="address-detail">{{ r.context.capture_mode === 'balanced' ? '均衡记录' : '普通记录' }}</small>
            <small v-if="r.context?.captured_at" class="address-detail">状态采集：{{ formatBeijingTime(r.context.captured_at) }}</small>
          </td>
          <td class="mono">{{ timeRange(r) }}</td>
        </tr>
        <tr v-if="!loading && visibleLocations.length === 0">
          <td colspan="9" class="empty">所选日期范围暂无位置数据，可切换日期或设备查看。采集新记录需在手机设置中开启位置记录并授予权限。</td>
        </tr>
      </tbody>
    </table>
    </div>
  </div>
</template>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.toolbar select, .toolbar input { padding: 6px 8px; border: 1px solid #ddd; border-radius: 6px; background: #fff; }
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
.analysis-hint { color: #64748b; font-size: 13px; line-height: 1.6; margin: 8px 0 12px; }
.coverage-warning { padding: 12px; border: 1px solid #f5d18c; border-radius: 8px; color: #805a12; background: #fff9eb; }
.location-stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 16px; }
.location-stats > div { display: flex; flex-direction: column; gap: 7px; border: 1px solid #e5e7eb; border-radius: 8px; padding: 14px; background: white; }
.location-stats strong { font-size: 22px; color: #3742fa; }
.location-stats span { font-size: 12px; color: #64748b; }
.location-insights { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 16px; }
.location-insights section { border: 1px solid #e5e7eb; padding: 16px; border-radius: 8px; background: white; max-height: 360px; overflow: auto; }
.location-insights h3 { margin: 0; font-size: 15px; }
.insight-row { display: flex; flex-direction: column; gap: 6px; padding: 12px; margin: 8px 0; width: 100%; box-sizing: border-box; text-align: left; border: 1px solid #e5e7eb; background: #f8fafc; border-radius: 6px; overflow-wrap: anywhere; }
button.insight-row { cursor: pointer; }
.insight-row span { color: #64748b; font-size: 12px; }
.route-details { margin: 12px 0 16px; }
.table-scroll { overflow-x: auto; }
.context-cell { min-width: 170px; max-width: 270px; overflow-wrap: anywhere; line-height: 1.5; }
@media (max-width: 800px) { .location-stats { grid-template-columns: 1fr 1fr; } .location-insights { grid-template-columns: 1fr; } }
</style>
