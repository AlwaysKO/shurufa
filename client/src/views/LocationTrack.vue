<script setup lang="ts">
import RetentionCleanup from '../components/RetentionCleanup.vue';
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { api, deviceLabel, type DeviceRow, type LocationRow } from '../api';
import { intervalSpeeds, locationPointKey, orderedLocationPoints, analyzeLocations, locationDay, wifiLabel, networkLabel, contextDetails, durationLabel, escapeLocationHtml, speedLabel, speedDetails } from '../locationAnalysis';

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
const estimates = computed(() => intervalSpeeds(visibleLocations.value));
const selectedKey = ref<string | null>(null);
const candidateKeys = ref<string[]>([]);
const candidates = computed(() => candidateKeys.value.map(key => locations.value.find(row => locationPointKey(row) === key)).filter((row): row is LocationRow => !!row));
const selectedPoint = computed(() => locations.value.find(row => locationPointKey(row) === selectedKey.value));
const pointSpeed = (point: LocationRow) => speedLabel(point, estimates.value.get(locationPointKey(point)));
const pointSpeedDetails = (point: LocationRow) => speedDetails(point, estimates.value.get(locationPointKey(point)));
const hitMarkers = new Map<string, L.CircleMarker>();
const visualMarkers = new Map<string, L.CircleMarker>();
let refreshTimer: ReturnType<typeof setTimeout> | null = null;
let latestRequest = 0;
let disposed = false;
const resolutionStatus = (row: LocationRow) => row.place_status && row.place_status !== 'resolved' ? row.place_status : row.address_status;
const unresolvedAddresses = computed(() => locations.value.filter(row =>
  (!row.address && row.address_status && row.address_status !== 'resolved') ||
  (row.place_status && row.place_status !== 'resolved')));

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
  if (!requestFailed && unresolvedAddresses.value.every(row => resolutionStatus(row) === 'failed')) {
    const retries = unresolvedAddresses.value.map(row => Date.parse((row.place_status && row.place_status !== 'resolved' ? row.place_retry_at : row.address_retry_at) ?? ''));
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
    selectedKey.value = null;
    candidateKeys.value = [];
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
      return JSON.stringify(row) !== JSON.stringify(previous);
    });
    locations.value = data.locations;
    const validKeys = new Set(locations.value.map(locationPointKey));
    if (selectedKey.value && !validKeys.has(selectedKey.value)) selectedKey.value = null;
    candidateKeys.value = candidateKeys.value.filter(key => validKeys.has(key));
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

function choosePoint(point: LocationRow, openPopup = true) {
  selectedKey.value = locationPointKey(point);
  for (const [key, marker] of visualMarkers) marker.setStyle({ color: key === selectedKey.value ? '#f59e0b' : '#fff', weight: key === selectedKey.value ? 4 : 2 });
  // 同坐标可能还有其他设备的大圆点；视觉层置顶但保持非交互，命中圈仍可点击。
  visualMarkers.get(selectedKey.value)?.bringToFront();
  if (openPopup) hitMarkers.get(selectedKey.value)?.openPopup();
}
function nearbyPoints(point: LocationRow, clicked: L.LatLng) {
  if (!map) return;
  const origin = map.latLngToContainerPoint(clicked);
  candidateKeys.value = orderedLocationPoints(visibleLocations.value).filter(row => {
    const lat = Number(row.latitude), lng = Number(row.longitude);
    return Number.isFinite(lat) && Number.isFinite(lng) && map!.latLngToContainerPoint([lat, lng]).distanceTo(origin) <= 22;
  }).sort((a, b) => Date.parse(a.occurred_at) - Date.parse(b.occurred_at) || a.device_id.localeCompare(b.device_id) || a.id.localeCompare(b.id)).map(locationPointKey);
  choosePoint(point);
}
function renderMap(recenter = true) {
  if (!map) return;
  const keepPopupOpen = selectedKey.value ? hitMarkers.get(selectedKey.value)?.isPopupOpen() === true : false;
  layer.remove();
  hitMarkers.clear(); visualMarkers.clear();
  layer = L.layerGroup().addTo(map);
  const pts = orderedLocationPoints(visibleLocations.value).filter(p => p.latitude !== '' && p.longitude !== '' && Number.isFinite(Number(p.latitude)) && Math.abs(Number(p.latitude)) <= 90 && Number.isFinite(Number(p.longitude)) && Math.abs(Number(p.longitude)) <= 180);
  if (pts.length === 0) return;
  const starts = new Set(analysis.value.segments.map(segment => locationPointKey(segment[0]!)));
  const ends = new Set(analysis.value.segments.map(segment => locationPointKey(segment[segment.length - 1]!)));
  for (const gap of analysis.value.gaps) {
    L.polyline([gap.start, gap.end].map(p => [Number(p.latitude), Number(p.longitude)]),
      { color: '#64748b', weight: 3, opacity: 0.8, dashArray: '6 8', interactive: false, className: 'location-gap' }).addTo(layer);
  }
  for (const segment of routeSegments.value) {
    L.polyline(segment.map(p => [Number(p.latitude), Number(p.longitude)]),
      { color: '#3742fa', weight: 3, opacity: 0.7, interactive: false, className: 'location-route' }).addTo(layer);
    for (let i = 1; i < segment.length; i++) {
      const a = segment[i - 1]!, b = segment[i]!;
      const lat = (Number(a.latitude) + Number(b.latitude)) / 2, lng = (Number(a.longitude) + Number(b.longitude)) / 2;
      const dx = (Number(b.longitude) - Number(a.longitude)) * Math.cos(lat * Math.PI / 180), dy = Number(b.latitude) - Number(a.latitude);
      if (dx === 0 && dy === 0) continue;
      const angle = -Math.atan2(dy, dx) * 180 / Math.PI;
      L.marker([lat, lng], { interactive: false, keyboard: false, icon: L.divIcon({ className: 'location-direction', iconSize: [16, 16], iconAnchor: [8, 8], html: `<span style="display:block;transform:rotate(${angle}deg);color:#3742fa" aria-hidden="true">➤</span>` }) }).addTo(layer);
    }
  }
  for (const p of pts) {
    const lat = Number(p.latitude), lng = Number(p.longitude), key = locationPointKey(p);
    const isFirst = starts.has(key), isLast = ends.has(key);
    const endpoint = isFirst && isLast ? '独立采样点' : isFirst ? '本段起点' : isLast ? '本段终点' : '采样点';
    const visual = L.circleMarker([lat, lng], { radius: isFirst || isLast ? 8 : 5, color: '#fff', weight: 2, fillColor: isFirst ? '#2ecc71' : isLast ? '#e74c3c' : '#3742fa', fillOpacity: 0.9, interactive: false, className: `location-point${isFirst ? ' location-start' : ''}${isLast ? ' location-end' : ''}` }).addTo(layer);
    visualMarkers.set(key, visual);
    const hit = L.circleMarker([lat, lng], { radius: 20, opacity: 0, fillOpacity: 0, weight: 0, className: 'location-hit', bubblingMouseEvents: false })
      .addTo(layer).bindPopup(
        `<b>${escapeLocationHtml(p.address ?? `${lat.toFixed(4)}, ${lng.toFixed(4)}`)}</b><br>` +
        `门牌号：${escapeLocationHtml(p.place_details?.doorplate || '未确认')} · 店名 / 楼宇：${escapeLocationHtml(p.place_details?.shop_name || p.place_details?.building_name || '未确认')}<br>` +
        `${endpoint} · ${escapeLocationHtml(rowDevice(p))}<br>${formatBeijingTime(p.occurred_at)}（北京时间）<br>` +
        `${escapeLocationHtml(providers[p.provider ?? ''] ?? p.provider ?? '-')} · 精度 ${escapeLocationHtml(String(p.accuracy ?? '-'))}m · 速度 ${escapeLocationHtml(pointSpeed(p))}<br>` +
        `Wi-Fi：${escapeLocationHtml(wifiLabel(p))}<br>${escapeLocationHtml(contextDetails(p))}` +
        `<details><summary>速度诊断</summary>${escapeLocationHtml(pointSpeedDetails(p))}</details>`,
        { autoPan: false, maxWidth: 280, className: 'location-popup' },
      ).on('click', (event: L.LeafletMouseEvent) => nearbyPoints(p, event.latlng));
    hitMarkers.set(key, hit);
  }
  const last = [...pts].sort((a, b) => Date.parse(a.occurred_at) - Date.parse(b.occurred_at)).at(-1)!;
  if (recenter) map.setView([Number(last.latitude), Number(last.longitude)], Math.max(map.getZoom(), 13));
  if (selectedPoint.value) choosePoint(selectedPoint.value, keepPopupOpen);
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
  candidateKeys.value = [locationPointKey(point)];
  choosePoint(point);
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
  hitMarkers.clear(); visualMarkers.clear();
  map?.remove();
  map = null;
});
</script>

<template>
  <RetentionCleanup :show-description="false" dataset="locations" label="位置记录" :filters="{ device_id: deviceId || undefined }" :scope-label="deviceId ? (devices.find(d => d.id === deviceId) ? deviceLabel(devices.find(d => d.id === deviceId)!) : deviceId) : '全部设备的位置记录'" :context="[deviceId, selectedDay, endDay]" @changed="loadLocations()" />
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
      <span v-if="error" class="err">{{ error }}</span>
    </div>

    <p v-if="analysis.gaps.length" class="analysis-hint" data-testid="location-gap-hint">灰色虚线：{{ analysis.gaps.length }} 处近距离缺口，期间缺少定位点，路线未知。</p>
    <p v-if="hasMore" class="coverage-warning" role="status">所选日期范围记录超过 1000 条，仅分析已加载的最近记录。可缩短日期范围或选择单台设备；这里的里程与停留不代表完整行程。</p>
    <div v-if="visibleLocations.length" class="location-stats">
      <div><strong>{{ routeSegments.length }}</strong><span>有连续观测的路线段</span></div>
      <div><strong>{{ (analysis.distanceMeters / 1000).toFixed(2) }} km</strong><span>过滤漂移后的估算里程</span></div>
      <div><strong>{{ analysis.stays.length }}</strong><span>估算停留（至少 5 分钟）</span></div>
      <div><strong>{{ analysis.excludedPoints }}</strong><span>低精度或无效点未参与分析</span></div>
    </div>

    <p v-if="unresolvedAddresses.length" class="address-hint" role="status">
      还有 {{ unresolvedAddresses.length }} 条地址或地点信息未完成，页面会自动更新；解析失败后按提示时间重试。
    </p>
    <div id="loc-map" class="map"></div>
    <section v-if="candidates.length" class="point-selection" aria-label="选择采样点">
      <h3>选择采样点（按时间排序）</h3>
      <p>点击位置附近共 {{ candidates.length }} 条记录；绿色为分段起点，红色为终点，箭头按采样时间前进。</p>
      <button v-for="point in candidates" :key="locationPointKey(point)" data-testid="location-candidate" :aria-pressed="selectedKey === locationPointKey(point)" @click="choosePoint(point)">
        {{ selectedKey === locationPointKey(point) ? '已选择 · ' : '' }}{{ formatBeijingTime(point.occurred_at) }} · {{ rowDevice(point) }} · {{ addressLabel(point) }}
      </button>
      <p v-if="selectedPoint" data-testid="selected-location">已选择：{{ formatBeijingTime(selectedPoint.occurred_at) }} · {{ addressLabel(selectedPoint) }} · {{ pointSpeed(selectedPoint) }}</p>
    </section>

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

    <div class="table-scroll" role="region" aria-label="位置记录，可横向滚动" tabindex="0">
    <table class="table">
      <thead>
        <tr>
          <th>#</th>
          <th>位置（坐标）</th>
          <th>地址</th>
          <th>门牌号</th>
          <th>店名 / 楼宇</th>
          <th>来源</th>
          <th>精度</th>
          <th>速度</th>
          <th>Wi-Fi / 网络</th>
          <th>设备状态</th>
          <th>时间范围（北京时间）</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(r, i) in visibleLocations" :key="locationPointKey(r)">
          <td>{{ visibleLocations.length - i }}<small class="address-detail">{{ rowDevice(r) }}</small></td>
          <td class="mono">{{ Number(r.latitude).toFixed(4) }}, {{ Number(r.longitude).toFixed(4) }}</td>
          <td>
            <span :class="{ 'address-failed': !r.address && r.address_status === 'failed' }">{{ addressLabel(r) }}</span>
            <template v-if="!r.address && r.address_status === 'failed'">
              <small v-if="r.address_error" class="address-detail">{{ r.address_error }}</small>
              <small v-if="r.address_retry_at" class="address-detail">下次重试：{{ formatBeijingTime(r.address_retry_at) }}（北京时间）</small>
            </template>
          </td>
          <td>{{ r.place_details?.doorplate || '未确认' }}</td>
          <td>{{ r.place_details?.shop_name || r.place_details?.building_name || '未确认' }}
            <small v-if="r.place_details?.source === 'amap' && (r.place_details.shop_name || r.place_details.building_name)" class="address-detail">{{ r.place_details.shop_name ? '高德商户匹配' : '高德所属楼宇/地点' }}</small>
            <template v-if="r.place_status === 'failed'">
              <small class="address-detail">{{ r.place_error || '解析失败，稍后重试' }}</small>
              <small v-if="r.place_retry_at" class="address-detail">下次重试：{{ formatBeijingTime(r.place_retry_at) }}（北京时间）</small>
            </template>
          </td>
          <td>{{ providers[r.provider ?? ''] ?? r.provider ?? '-' }}</td>
          <td>{{ r.accuracy ?? '-' }} m</td>
          <td>{{ pointSpeed(r) }}
            <details><summary>速度诊断</summary><small>{{ pointSpeedDetails(r) }}</small></details>
          </td>
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
          <td colspan="11" class="empty">所选日期范围暂无位置数据，可切换日期或设备查看。采集新记录需在手机设置中开启位置记录并授予权限。</td>
        </tr>
      </tbody>
    </table>
    </div>
  </div>
</template>

<style scoped>
.point-selection { padding: 12px; margin-bottom: 16px; background: #fff; overflow-wrap: anywhere; }
.point-selection h3 { margin: 0 0 8px; font-size: 15px; }
.point-selection p { font-size: 13px; color: #64748b; }
.point-selection button { display: block; width: 100%; min-height: 44px; text-align: left; padding: 8px; margin: 5px 0; white-space: normal; overflow-wrap: anywhere; background: #f8fafc; border: 1px solid #cbd5e1; border-radius: 6px; }
.point-selection button[aria-pressed="true"] { border-color: #d97706; background: #fffbeb; }
.map :deep(.location-direction) { pointer-events: none; }
.map :deep(.location-popup) { overflow-wrap: anywhere; }
.map :deep(.leaflet-popup-content) { max-width: min(260px, 65vw); max-height: 230px; overflow: auto; }

.table { min-width: 1100px; }
.table td:first-child { min-width: 100px; }
.table td:nth-child(3) { min-width: 200px; }

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
