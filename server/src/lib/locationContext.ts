const object = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value);
const number = (value: unknown, min: number, max: number) =>
  value == null || (typeof value === 'number' && Number.isFinite(value) && value >= min && value <= max);
const choice = (value: unknown, values: string[]) => value == null || (typeof value === 'string' && values.includes(value));
const text = (value: unknown, max: number) => value == null || (typeof value === 'string' && value.length <= max && !value.includes('\0'));
const boolean = (value: unknown) => value == null || typeof value === 'boolean';

/** 可选快照保持旧客户端兼容，拒绝无界内容及误传的其他设备数据。 */
export function validLocationContext(value: unknown): boolean {
  if (value == null) return true;
  if (!object(value) || value.version !== 1) return false;
  const allowed = new Set(['version', 'captured_at', 'capture_mode', 'network_type', 'wifi', 'battery_percent',
    'charging', 'is_interactive', 'power_save', 'altitude_m', 'bearing_deg', 'speed_accuracy_mps']);
  if (Object.keys(value).some(key => !allowed.has(key))) return false;
  if (value.captured_at != null && (typeof value.captured_at !== 'string' || value.captured_at.length > 64 ||
      !Number.isFinite(Date.parse(value.captured_at)))) return false;
  if (!choice(value.capture_mode, ['balanced', 'opportunistic']) ||
      !choice(value.network_type, ['wifi', 'cellular', 'ethernet', 'vpn', 'offline', 'other', 'unknown']) ||
      !number(value.battery_percent, 0, 100) || !boolean(value.charging) ||
      !boolean(value.is_interactive) || !boolean(value.power_save) ||
      !number(value.altitude_m, -20_000, 100_000) || !number(value.bearing_deg, 0, 360) || value.bearing_deg === 360 ||
      !number(value.speed_accuracy_mps, 0, 10_000)) return false;
  if (value.wifi == null) return true;
  const wifi = value.wifi;
  if (!object(wifi) || typeof wifi.status !== 'string' ||
      !choice(wifi.status, ['connected', 'disconnected', 'unavailable', 'permission_denied', 'location_disabled'])) return false;
  if (Object.keys(wifi).some(key => !['status', 'ssid', 'bssid', 'rssi', 'frequency_mhz', 'link_speed_mbps'].includes(key))) return false;
  return text(wifi.ssid, 128) && wifi.ssid !== '<unknown ssid>' &&
    (wifi.bssid == null || (typeof wifi.bssid === 'string' && /^([0-9a-f]{2}:){5}[0-9a-f]{2}$/i.test(wifi.bssid) &&
      !['02:00:00:00:00:00', '00:00:00:00:00:00', 'ff:ff:ff:ff:ff:ff'].includes(wifi.bssid.toLowerCase()))) &&
    number(wifi.rssi, -127, 0) && number(wifi.frequency_mhz, 1, 100_000) && number(wifi.link_speed_mbps, 0, 100_000);
}
