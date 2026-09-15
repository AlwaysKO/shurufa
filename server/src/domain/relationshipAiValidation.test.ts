import { describe, expect, it } from 'vitest';
import {
  validateAiProfileDocument,
  validateAiReplyQuery,
  validateAiReplyResult,
} from './relationshipAiValidation.js';

describe('relationship AI validation', () => {
  it('accepts a bounded profile and normalizes string lists', () => {
    expect(validateAiProfileDocument({
      summary: ' 熟悉的同事 ',
      user_style: [' 简短 ', '简短', '爱用“哈”'],
      peer_style: ['直接'],
      relationship_signals: ['合作频繁'],
      preferred_tone: ['自然'],
      avoid: ['过度热情'],
      evidence_message_ids: ['m-1', 'm-1', 'm-2'],
    })).toEqual({
      summary: '熟悉的同事',
      user_style: ['简短', '爱用“哈”'],
      peer_style: ['直接'],
      relationship_signals: ['合作频繁'],
      preferred_tone: ['自然'],
      avoid: ['过度热情'],
      evidence_message_ids: ['m-1', 'm-2'],
    });
  });

  it('rejects unbounded profile fields and invalid reply requests', () => {
    expect(() => validateAiProfileDocument({ summary: 'x'.repeat(1001) })).toThrow('summary');
    expect(() => validateAiReplyQuery({
      platform: 'wechat', account_key: 'a', external_key: 'b', refresh_count: -1,
    })).toThrow('refresh_count');
  });

  it('deduplicates AI replies and limits their length', () => {
    expect(validateAiReplyResult({ replies: [' 好的 ', '好的', '我晚点处理'] }, 3))
      .toEqual(['好的', '我晚点处理']);
    expect(() => validateAiReplyResult({ replies: ['x'.repeat(501)] }, 3)).toThrow('replies');
  });
});
