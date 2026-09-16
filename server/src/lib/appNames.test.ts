import { expect, it } from 'vitest';
import { eventMetadata, normalizeAppName } from './appNames.js';
import type { MobileEvent } from '../types/events.js';

it('只接受长度合适的文本，移除控制字符及双向文本控制符', () => {
  expect(normalizeAppName('  测\u0000试\n应用\u202e  ')).toBe('测试应用');
  expect(normalizeAppName('My Notes')).toBe('My Notes');
  for (const value of [null, undefined, {}, [], 123, '', ' \n ', '甲'.repeat(121)]) {
    expect(normalizeAppName(value)).toBeNull();
  }
});
it('名称与既有元数据合并但不改变原对象，也不把包名当成真实名称', () => {
  const original = { edit_protocol: 1, app_name: '旧名称' };
  const base: MobileEvent = { id: 'event', device_id: 'device', event_type: 'commit', occurred_at: '2026-09-16T12:00:00Z', package_name: 'org.example.chat', metadata: original };
  expect(eventMetadata({ ...base, app_name: '新名称' })).toEqual({ edit_protocol: 1, app_name: '新名称' });
  expect(original.app_name).toBe('旧名称');
  expect(eventMetadata(base).app_name).toBe('旧名称');
  expect(eventMetadata({ ...base, app_name: base.package_name })).toEqual({ edit_protocol: 1 });
  expect(eventMetadata({ ...base, package_name: null })).toEqual({ edit_protocol: 1 });
});
it('旧协议和异常元数据不阻塞采集', () => {
  const base: MobileEvent = { id: 'event', device_id: 'device', event_type: 'commit', occurred_at: '2026-09-16T12:00:00Z' };
  expect(eventMetadata(base)).toEqual({});
  expect(eventMetadata({ ...base, metadata: { app_name: {}, custom: '保留' } })).toEqual({ custom: '保留' });
});
