import type { LocationRow } from './api';

const MAX_GAP_MS = 15 * 60_000;
const MIN_STAY_MS = 5 * 60_000;
const timestamp = (point: LocationRow) => Date.parse(point.occurred_at);
const accuracy = (point: LocationRow) => point.accuracy == null ? 0 : Number(point.accuracy);
export const locationDay = (time: string) => {
  const ms = Date.parse(time);
  return Number.isFinite(ms) ? new Date(ms + 8 * 3600_000).toISOString().slice(0, 10) : '';
};
export function validLocationPoint(point: LocationRow) {
  const lat = Number(point.latitude), lng = Number(point.longitude), error = accuracy(point);
  return point.latitude !== '' && point.longitude !== '' && Number.isFinite(lat) && Math.abs(lat) <= 90 &&
    Number.isFinite(lng) && Math.abs(lng) <= 180 && Number.isFinite(timestamp(point)) &&
    Number.isFinite(error) && error >= 0 && error <= 200;
}
function distance(a: LocationRow, b: LocationRow) {
  const rad = Math.PI / 180, lat = (Number(b.latitude) - Number(a.latitude)) * rad;
  const lng = (Number(b.longitude) - Number(a.longitude)) * rad;
  const h = Math.sin(lat / 2) ** 2 + Math.cos(Number(a.latitude) * rad) * Math.cos(Number(b.latitude) * rad) * Math.sin(lng / 2) ** 2;
  return 6_371_000 * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
}
function continuousTime(a: LocationRow, b: LocationRow) {
  const elapsed = timestamp(b) - timestamp(a);
  return elapsed > 0 && elapsed <= MAX_GAP_MS && locationDay(a.occurred_at) === locationDay(b.occurred_at);
}
function continuous(a: LocationRow, b: LocationRow) {
  return continuousTime(a, b) && distance(a, b) <= (timestamp(b) - timestamp(a)) / 1000 * 70 + accuracy(a) + accuracy(b);
}
function nearAnchor(a: LocationRow, b: LocationRow) {
  return distance(a, b) <= Math.min(150, Math.max(75, accuracy(a) + accuracy(b)));
}
export interface LocationSpan {
  points: LocationRow[];
  start: LocationRow;
  end: LocationRow;
  durationMs: number;
}
function span(points: LocationRow[]): LocationSpan {
  return { points, start: points[0]!, end: points[points.length - 1]!, durationMs: timestamp(points[points.length - 1]!) - timestamp(points[0]!) };
}
function wifiKey(point: LocationRow) {
  const wifi = point.context?.wifi;
  if (wifi?.status !== 'connected' || !wifi.ssid) return null;
  return JSON.stringify([wifi.ssid, wifi.bssid ?? null]);
}

/** 仅分析实际采集点；断档分段、固定停留锚点、禁止把末点延长到现在。 */
export function analyzeLocations(rows: LocationRow[]) {
  const byDevice = new Map<string, LocationRow[]>();
  for (const point of rows) {
    const group = byDevice.get(point.device_id) ?? [];
    group.push(point); byDevice.set(point.device_id, group);
  }
  const segments: LocationRow[][] = [], stays: LocationSpan[] = [], wifiSessions: LocationSpan[] = [];
  let excludedPoints = 0, distanceMeters = 0;
  for (const group of byDevice.values()) {
    group.sort((a, b) => timestamp(a) - timestamp(b) || a.id.localeCompare(b.id));
    let wifiPoints: LocationRow[] = [];
    const flushWifi = () => {
      if (wifiPoints.length > 1) wifiSessions.push(span(wifiPoints));
      wifiPoints = [];
    };
    for (const point of group) {
      const key = wifiKey(point);
      if (wifiPoints.length && (key !== wifiKey(wifiPoints[0]!) || !continuousTime(wifiPoints[wifiPoints.length - 1]!, point))) flushWifi();
      if (key && Number.isFinite(timestamp(point))) wifiPoints.push(point);
    }
    flushWifi();
    let current: LocationRow[] | null = null;
    for (const point of group) {
      if (!validLocationPoint(point)) { excludedPoints++; current = null; continue; }
      if (!current || !continuous(current[current.length - 1]!, point)) {
        current = []; segments.push(current);
      }
      current.push(point);
    }
  }
  for (const segment of segments) {
    let anchored: LocationRow[] = [];
    const stayInterior = new Set<LocationRow>();
    const flushStay = () => {
      if (anchored.length > 1 && timestamp(anchored[anchored.length - 1]!) - timestamp(anchored[0]!) >= MIN_STAY_MS) {
        stays.push(span(anchored));
        anchored.slice(1).forEach(point => stayInterior.add(point));
      }
      anchored = [];
    };
    for (const point of segment) {
      if (anchored.length && !nearAnchor(anchored[0]!, point)) flushStay();
      anchored.push(point);
    }
    flushStay();
    let travelAnchor = segment[0]!;
    for (const point of segment) {
      if (stayInterior.has(point)) { travelAnchor = point; continue; }
      const separation = distance(travelAnchor, point);
      // 相邻采样很近时累积到锚点，既抑制原地漂移，也不漏掉持续慢行。
      if (separation > Math.max(20, accuracy(travelAnchor) + accuracy(point))) {
        distanceMeters += separation; travelAnchor = point;
      }
    }
  }
  stays.sort((a, b) => timestamp(b.start) - timestamp(a.start));
  wifiSessions.sort((a, b) => timestamp(b.start) - timestamp(a.start));
  return { segments, stays, wifiSessions, distanceMeters, excludedPoints };
}

export function wifiLabel(point: LocationRow) {
  const wifi = point.context?.wifi;
  if (!wifi) return '未采集';
  if (wifi.status === 'connected') return wifi.ssid || '已连接，名称不可读取';
  return { disconnected: '未连接 Wi-Fi', unavailable: 'Wi-Fi 信息不可读取', permission_denied: 'Wi-Fi 权限不足', location_disabled: '定位开关关闭，Wi-Fi 名称不可读取' }[wifi.status];
}
export function networkLabel(point: LocationRow) {
  const type = point.context?.network_type;
  return type ? {wifi:'Wi-Fi',cellular:'移动数据',ethernet:'有线网络',vpn:'VPN',offline:'离线',other:'其他网络',unknown:'网络未知'}[type] : '未采集';
}
export function contextDetails(point: LocationRow) {
  const c = point.context;
  if (!c) return '未采集';
  const details: string[] = [];
  if (c.battery_percent != null) details.push(`电量 ${c.battery_percent}%`);
  if (c.charging != null) details.push(c.charging ? '充电中' : '未充电');
  if (c.is_interactive != null) details.push(c.is_interactive ? '亮屏' : '熄屏');
  if (c.power_save != null) details.push(c.power_save ? '省电模式' : '正常电源模式');
  if (c.altitude_m != null) details.push(`定位高度 ${c.altitude_m.toFixed(1)} m`);
  if (c.bearing_deg != null) details.push(`移动方向 ${c.bearing_deg.toFixed(0)}°`);
  if (c.speed_accuracy_mps != null) details.push(`速度误差 ±${c.speed_accuracy_mps.toFixed(1)} m/s`);
  return details.join(' · ') || '未采集';
}
export function escapeLocationHtml(value: string) {
  return value.replace(/[&<>"']/g, character => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[character]!));
}
export function durationLabel(ms: number) {
  const minutes = Math.floor(ms / 60_000);
  return minutes >= 60 ? `${Math.floor(minutes / 60)}小时${minutes % 60}分钟` : `${minutes}分钟`;
}
