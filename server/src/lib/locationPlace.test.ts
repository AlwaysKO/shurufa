import { expect, it } from 'vitest';
import { parseLocationPlace } from './locationPlace.js';

const ring = (x = 0) => [[x, 0], [x + 2, 0], [x + 2, 2], [x, 2], [x, 0]];
const shop = () => ({ category: 'shop', type: 'convenience', name: '测试便利店',
  address: { road: '测试路', house_number: '12号' }, geojson: { type: 'Polygon', coordinates: [ring()] } });
it('门牌使用地图返回的道路与号码，覆盖坐标的店铺面才返回店名', () => {
  expect(parseLocationPlace(shop(), 1, 1)).toEqual({ doorplate: '测试路12号', shop_name: '测试便利店', source: 'nominatim' });
});
it.each([
  { ...shop(), geojson: { type: 'Point', coordinates: [1, 1] } },
  { ...shop(), geojson: { type: 'Polygon', coordinates: [ring(3)] } },
  { ...shop(), geojson: undefined },
  { ...shop(), category: 'highway', type: 'residential', name: '路名不能算店名' },
  { ...shop(), category: 'building', name: '楼宇名称不能当店名' },
  { ...shop(), name: ['无效名称'] },
  { ...shop(), geojson: { type: 'Polygon', coordinates: [[[0, 0], [2, 0], [NaN, 2], [0, 0]]] } },
])('不拿附近店铺节点、未覆盖面、道路或无效结果充当所在店铺 %#', data => {
  expect(parseLocationPlace(data, 1, 1).shop_name).toBeNull();
});
it('多边形孔洞不算覆盖，边界上也不强行认定店铺', () => {
  const data = shop();
  data.geojson.coordinates.push([[.5, .5], [1.5, .5], [1.5, 1.5], [.5, 1.5], [.5, .5]]);
  expect(parseLocationPlace(data, 1, 1).shop_name).toBeNull();
  expect(parseLocationPlace(shop(), 0, 1).shop_name).toBeNull();
});
it('兼容多面几何及旧版class分类的餐饮店名', () => {
  expect(parseLocationPlace({ ...shop(), category: undefined, class: 'amenity', type: 'restaurant',
    geojson: { type: 'MultiPolygon', coordinates: [[ring(3)], [ring()]] } }, 1, 1).shop_name).toBe('测试便利店');
});
it.each([undefined, {}, { address: { road: '只有路名' } }, { address: { house_number: {} } }])('没有门牌或店名时明确空值 %#', data => {
  expect(parseLocationPlace(data, 1, 1)).toEqual({ doorplate: null, shop_name: null, source: 'nominatim' });
});
