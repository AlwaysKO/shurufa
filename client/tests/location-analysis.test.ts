import { expect, it } from '../../server/node_modules/vitest/dist/index.js';
import type { LocationRow } from '../src/api';
import { analyzeLocations, wifiLabel, contextDetails, escapeLocationHtml } from '../src/locationAnalysis';

function point(minute: number, longitude = 113.26, device = 'a'): LocationRow {
 const time = new Date(Date.parse('2026-09-29T01:00:00Z') + minute * 60_000).toISOString();
 return {id:`${device}-${minute}`,device_id:device,latitude:'23.13',longitude:String(longitude),accuracy:'30',provider:'network',speed:'0',address:'位置',occurred_at:time,first_seen_at:time,last_seen_at:time};
}
it('按设备时间排序，连续同区域观测才形成停留，不延伸到当前时间',()=>{
 const result=analyzeLocations([point(10),point(0),point(5)]);
 expect(result.stays).toHaveLength(1);
 expect(result.stays[0].durationMs).toBe(10*60_000);
 expect(result.stays[0].points).toHaveLength(3);
 expect(result.distanceMeters).toBe(0);
});
it('长缺口、跨日和不同设备不连成路线或累计停留',()=>{
 const midnight={...point(5),occurred_at:'2026-09-29T16:00:00Z'};
 const before={...point(0),occurred_at:'2026-09-29T15:59:00Z'};
 const result=analyzeLocations([point(0),point(30),point(5,113.26,'b'),before,midnight]);
 expect(result.segments).toHaveLength(5);expect(result.stays).toHaveLength(0);
 expect(result.distanceMeters).toBe(0);
});
it('同地漂移不会累计成路程，慢慢漂走不形成跨区域停留',()=>{
 expect(analyzeLocations([point(0),point(5,113.2601),point(10,113.2602)]).distanceMeters).toBe(0);
 const result=analyzeLocations([0,1,2,3,4,5,6,7].map(i=>point(i,113.26+i*.0007)));
 expect(result.stays).toHaveLength(0);
 expect(result.distanceMeters).toBeGreaterThan(300);
});
it('已经识别为停留的小范围来回漂移不累计里程',()=>{
 const result=analyzeLocations(Array.from({length:13},(_,i)=>point(i*5,113.26+(i%2)*.00065)));
 expect(result.stays).toHaveLength(1);expect(result.stays[0].durationMs).toBe(60*60_000);
 expect(result.distanceMeters).toBe(0);
});
it('明显跳点、过低精度和无效坐标不膨胀里程',()=>{
 const result=analyzeLocations([point(0),point(1,120),{...point(2),accuracy:'500'},point(3),{...point(4),latitude:'bad'}]);
 expect(result.distanceMeters).toBe(0);expect(result.excludedPoints).toBe(2);
});
it('Wi-Fi 观测跨度单独统计，换热点或缺口结束跨度',()=>{
 const withWifi=(minute:number,bssid='aa:bb:cc:dd:ee:ff')=>({...point(minute),context:{version:1 as const,wifi:{status:'connected' as const,ssid:'Office',bssid}}});
 const result=analyzeLocations([withWifi(0),withWifi(5),withWifi(10,'aa:bb:cc:dd:ee:00'),withWifi(40,'aa:bb:cc:dd:ee:00')]);
 expect(result.wifiSessions).toHaveLength(1);
 expect(result.wifiSessions[0].durationMs).toBe(5*60_000);
});
it('定位不精确不抹掉明确的Wi-Fi连接观测',()=>{
 const rows=[0,5,10].map(minute=>({...point(minute),accuracy:'500',context:{version:1 as const,wifi:{status:'connected' as const,ssid:'Office',bssid:'aa:bb:cc:dd:ee:ff'}}}));
 const result=analyzeLocations(rows);
 expect(result.stays).toHaveLength(0);expect(result.wifiSessions).toHaveLength(1);
 expect(result.wifiSessions[0].durationMs).toBe(10*60_000);
});
it('Wi-Fi 缺失、权限不足和未连接不会混为一谈',()=>{
 expect(wifiLabel(point(0))).toBe('未采集');
 expect(wifiLabel({...point(0),context:{version:1,wifi:{status:'permission_denied'}}})).toContain('权限');
 expect(wifiLabel({...point(0),context:{version:1,wifi:{status:'disconnected'}}})).toBe('未连接 Wi-Fi');
});
it('设备快照保留零电量与否定状态，弹窗文本转义',()=>{
 const details=contextDetails({...point(0),context:{version:1,battery_percent:0,charging:false,is_interactive:false,wifi:{status:'connected',ssid:'<img src=x onerror=alert(1)>'}}});
 expect(details).toContain('0%');expect(details).toContain('未充电');expect(details).toContain('熄屏');
 expect(escapeLocationHtml('<img src=x onerror="x"> &')).toBe('&lt;img src=x onerror=&quot;x&quot;&gt; &amp;');
});
