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
export interface LocationGap {
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
  const segments: LocationRow[][] = [], stays: LocationSpan[] = [], wifiSessions: LocationSpan[] = [], gaps: LocationGap[] = [];
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
        const previous = current?.[current.length - 1];
        if (previous) {
          const durationMs = timestamp(point) - timestamp(previous);
          if (durationMs > MAX_GAP_MS && durationMs <= 30 * 60_000 &&
              locationDay(previous.occurred_at) === locationDay(point.occurred_at) && distance(previous, point) <= 300) {
            gaps.push({ start: previous, end: point, durationMs });
          }
        }
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
  return { segments, stays, wifiSessions, gaps, distanceMeters, excludedPoints };
}

export const locationPointKey = (point: LocationRow) => JSON.stringify([point.device_id, point.id]);
export function orderedLocationPoints(rows: LocationRow[]) {
  return [...rows].sort((a, b) => a.device_id.localeCompare(b.device_id) ||
    (Number.isFinite(timestamp(a)) ? timestamp(a) : Infinity) - (Number.isFinite(timestamp(b)) ? timestamp(b) : Infinity) || a.id.localeCompare(b.id));
}
export interface IntervalSpeed { kmh: number; from: string; to: string }
/** 相邻采样直线位移的区间平均值，不是道路里程或瞬时速度。 */
export function intervalSpeeds(rows: LocationRow[]) {
  const result = new Map<string, IntervalSpeed>();
  const ordered = orderedLocationPoints(rows);
  for (let i = 1; i < ordered.length; i++) {
    const a = ordered[i - 1]!, b = ordered[i]!;
    if (a.device_id !== b.device_id || !validLocationPoint(a) || !validLocationPoint(b) || !continuous(a, b)) continue;
    const aError = nonnegative(a.accuracy), bError = nonnegative(b.accuracy);
    if (aError == null || bError == null || aError > 50 || bError > 50) continue;
    const meters = distance(a, b);
    // 至少为合计误差的两倍，且超过微小抖动，避免将误差范围内的变化解释成速度。
    if (meters <= Math.max(10, 2 * (aError + bError))) continue;
    const kmh = meters / (timestamp(b) - timestamp(a)) * 3600;
    if (kmh > 70 * 3.6) continue;
    result.set(locationPointKey(b), { kmh, from: a.occurred_at, to: b.occurred_at });
  }
  return result;
}
const intervalTime = (value: string) => new Date(value).toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' });

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
const nonnegative = (value: string | number | null | undefined) => {
  if (value == null || (typeof value === 'string' && !value.trim())) return null;
  const result = Number(value);
  return Number.isFinite(result) && result >= 0 ? result : null;
};
function speedIssue(point: LocationRow): string | null {
  const c = point.context, speed = nonnegative(point.speed);
  if (c?.speed_quality === 'unreliable' || c?.speed_quality === 'unavailable' ||
      (c?.speed_quality_reason != null && c.speed_quality_reason !== 'accurate')) {
    return c?.speed_quality_reason === 'poor_location_accuracy' ? '位置精度不足' :
      c?.speed_quality_reason === 'poor_speed_accuracy' ? '速度精度不足' :
      c?.speed_quality_reason === 'missing_speed_accuracy' ? '未提供速度精度' :
      c?.speed_quality_reason === 'missing_speed' ? '未提供速度' : '速度未通过可信度校验';
  }
  if (speed == null) return '未提供有效速度';
  if (!Number.isFinite(speed * 3.6)) return '速度换算超出可显示范围';
  const positionError = nonnegative(point.accuracy);
  if (positionError == null || positionError > 50) return '位置精度不足';
  const speedError = nonnegative(c?.speed_accuracy_mps);
  if (speedError == null) return '未提供速度精度';
  if (speedError > Math.max(1.5, Math.min(5, speed * 0.25))) return '速度精度不足';
  return null;
}
/** 新旧记录共用手机端精度门槛；缺少证据时不把原始读数当作实际速度。 */
export function speedLabel(point: LocationRow, estimate?: IntervalSpeed) {
  if (speedIssue(point) != null && estimate) return `${estimate.kmh.toFixed(1)} km/h（区间估算）`;
  if (point.speed == null && point.context?.raw_speed_mps == null &&
      point.context?.speed_quality !== 'unreliable') return '未知（未提供速度）';
  const issue = speedIssue(point);
  return issue == null ? `${(Number(point.speed) * 3.6).toFixed(1)} km/h` : `未知（${issue}）`;
}
export function speedDetails(point: LocationRow, estimate?: IntervalSpeed) {
  const raw = nonnegative(point.context?.raw_speed_mps ?? point.speed);
  const issue = speedIssue(point);
  const rawLabel = raw == null ? null : Number.isFinite(raw * 3.6)
    ? `原始速度 ${(raw * 3.6).toFixed(1)} km/h（仅供排查）`
    : `原始速度 ${raw} m/s（仅供排查，速度换算超出可显示范围）`;
  return [estimate && issue != null ? `区间估算：${intervalTime(estimate.from)} ～ ${intervalTime(estimate.to)}（北京时间），按采样点直线位移计算，非瞬时速度或道路里程` : null, rawLabel, issue].filter(Boolean).join(' · ');
}
export function contextDetails(point: LocationRow) {
  const c = point.context;
  if (!c) return '未采集';
  const details: string[] = [];
  if (c.battery_percent != null) details.push(`电量 ${c.battery_percent}%`);
  if (c.charging != null) details.push(c.charging ? '充电中' : '未充电');
  if (c.is_interactive != null) details.push(c.is_interactive ? '亮屏' : '熄屏');
  if (c.power_save != null) details.push(c.power_save ? '省电模式' : '正常电源模式');
  const height = c.altitude_m ?? c.raw_altitude_m;
  const trusted = c.altitude_m != null && c.altitude_quality_reason === 'accurate' &&
    c.vertical_accuracy_m != null && c.vertical_accuracy_m >= 0 && c.vertical_accuracy_m <= 20;
  const altitudeReason = ({ accurate: '', missing_altitude: '未提供高度', invalid_altitude: '高度值无效',
    missing_vertical_accuracy: '未提供垂直精度', poor_vertical_accuracy: '垂直精度不足' } as const)[c.altitude_quality_reason ?? 'accurate'];
  if (height != null) details.push(`${trusted ? '可信' : '参考'}高度 ${height.toFixed(1)} m（WGS84 椭球高，非海拔；${
    c.vertical_accuracy_m != null ? `垂直估计误差 ±${c.vertical_accuracy_m.toFixed(1)} m（68%置信范围）` : '垂直精度未记录'}${altitudeReason ? `；${altitudeReason}` : ''}）`);
  else if (c.altitude_quality_reason) details.push(`高度未知（${altitudeReason || '未提供高度'}）`);
  if (c.bearing_deg != null) details.push(`移动方向 ${c.bearing_deg.toFixed(0)}°`);
  if (c.speed_accuracy_mps != null) details.push(`速度估计误差 ±${c.speed_accuracy_mps.toFixed(1)} m/s（68%置信范围）`);
  return details.join(' · ') || '未采集';
}
export function escapeLocationHtml(value: string) {
  return value.replace(/[&<>"']/g, character => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[character]!));
}
export function durationLabel(ms: number) {
  const minutes = Math.floor(ms / 60_000);
  return minutes >= 60 ? `${Math.floor(minutes / 60)}小时${minutes % 60}分钟` : `${minutes}分钟`;
}
