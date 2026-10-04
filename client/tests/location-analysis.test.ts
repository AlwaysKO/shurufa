import { expect, it } from '../../server/node_modules/vitest/dist/index.js';
import type { LocationRow } from '../src/api';
import { analyzeLocations, wifiLabel, contextDetails, escapeLocationHtml, speedLabel, speedDetails } from '../src/locationAnalysis';

function point(minute: number, longitude = 113.26, device = 'a'): LocationRow {
 const time = new Date(Date.parse('2026-09-29T01:00:00Z') + minute * 60_000).toISOString();
 return {id:`${device}-${minute}`,device_id:device,latitude:'23.13',longitude:String(longitude),accuracy:'30',provider:'network',speed:'0',address:'位置',occurred_at:time,first_seen_at:time,last_seen_at:time};
}
it('历史速度缺失精度、精度差或数值非法均不作为实际速度展示', () => {
 const row = {...point(0), speed:'13.944444'};
 expect(speedLabel(row)).toBe('未知（可信度不足）');
 expect(speedLabel({...row, accuracy:'100', context:{version:1,speed_accuracy_mps:0.5}})).toBe('未知（可信度不足）');
 for (const speed of ['-1','NaN','Infinity','']) expect(speedLabel({...row,speed})).toBe('未知（可信度不足）');
 expect(speedLabel({...row,speed:null})).toBe('未知（未提供速度）');
});
it('可信历史和新速度都保留实际读数及零值，不按步行限速裁剪', () => {
 const row = {...point(0),speed:'20',context:{version:1 as const,speed_accuracy_mps:1}};
 expect(speedLabel(row)).toBe('72.0 km/h');
 expect(speedLabel({...row,speed:'0'})).toBe('0.0 km/h');
 expect(speedLabel({...row,context:{...row.context,raw_speed_mps:20,speed_quality:'trusted',speed_quality_reason:'accurate'}})).toBe('72.0 km/h');
});
it('速度精度按绝对及相对门槛限制，新质量标记不能绕过质量校验', () => {
 const row = {...point(0),speed:'10',context:{version:1 as const,speed_accuracy_mps:2.5}};
 expect(speedLabel(row)).toBe('36.0 km/h');
 expect(speedLabel({...row,context:{...row.context,speed_accuracy_mps:2.51}})).toBe('未知（可信度不足）');
 expect(speedLabel({...row,context:{version:1,speed_quality:'trusted'}})).toBe('未知（可信度不足）');
 expect(speedLabel({...row,context:{...row.context,speed_quality:'unreliable'}})).toBe('未知（可信度不足）');
});
it('新客户端过滤后的速度不被原值回填，原始速度和原因留在诊断详情', () => {
 const row:LocationRow = {...point(0),speed:null,context:{version:1,raw_speed_mps:50.2/3.6,speed_quality:'unreliable',speed_quality_reason:'poor_location_accuracy'}};
 expect(speedLabel(row)).toBe('未知（可信度不足）');
 expect(speedDetails(row)).toContain('原始速度 50.2 km/h（仅供排查）');
 expect(speedDetails(row)).toContain('位置精度不足');
});
it('极大有限原始值换算溢出时不展示Infinity速度，也不裁剪成其他数值', () => {
 const row:LocationRow = {...point(0),speed:String(Number.MAX_VALUE),context:{version:1,raw_speed_mps:Number.MAX_VALUE,speed_accuracy_mps:1}};
 expect(speedLabel(row)).toBe('未知（可信度不足）');
 expect(speedDetails(row)).not.toContain('Infinity');
 expect(speedDetails(row)).toContain('速度换算超出可显示范围');
});
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
