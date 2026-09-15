import { describe, expect, it } from 'vitest';
import {
  validateStickerCandidateQuery,
  validateRelationshipCandidateQuery,
  validateRelationshipProfileInput,
} from './relationshipValidation.js';

describe('validateRelationshipProfileInput', () => {
  it('接受合法关系档案并保留显式字段', () => {
    expect(validateRelationshipProfileInput({
      relationship_type: 'friend',
      alias: '老朋友',
      intimacy_level: 80,
      humor_level: 70,
      notes: '可以开玩笑',
    })).toEqual({
      relationship_type: 'friend',
      alias: '老朋友',
      intimacy_level: 80,
      humor_level: 70,
      notes: '可以开玩笑',
    });
  });

  it('拒绝未知关系类型', () => {
    expect(() => validateRelationshipProfileInput({
      relationship_type: 'stranger',
      intimacy_level: 50,
      humor_level: 50,
    })).toThrow('relationship_type');
  });

  it.each([
    ['intimacy_level', -1],
    ['intimacy_level', 101],
    ['humor_level', 1.5],
  ])('拒绝非法等级 %s=%s', (field, value) => {
    expect(() => validateRelationshipProfileInput({
      relationship_type: 'family',
      intimacy_level: 50,
      humor_level: 50,
      [field]: value,
    })).toThrow(field);
  });

  it('拒绝过长别名和备注', () => {
    expect(() => validateRelationshipProfileInput({
      relationship_type: 'friend',
      alias: '别'.repeat(101),
      intimacy_level: 50,
      humor_level: 50,
    })).toThrow('alias');
    expect(() => validateRelationshipProfileInput({
      relationship_type: 'friend',
      intimacy_level: 50,
      humor_level: 50,
      notes: '注'.repeat(2001),
    })).toThrow('notes');
  });
});

describe('validateStickerCandidateQuery', () => {
  it('接受完整身份、可选资源哈希和候选数量', () => {
    expect(validateStickerCandidateQuery({
      platform: 'qq',
      account_key: ' account ',
      external_key: ' group ',
      incoming_asset_sha256: 'a'.repeat(64),
      limit: 8,
    })).toEqual({
      identity: {
        platform: 'qq',
        account_key: 'account',
        external_key: 'group',
      },
      incomingAssetSha256: 'a'.repeat(64),
      limit: 8,
    });
  });

  it('空哈希表示只查询高频表情', () => {
    expect(validateStickerCandidateQuery({
      platform: 'wechat',
      account_key: 'account',
      external_key: 'peer',
      incoming_asset_sha256: '',
    }).incomingAssetSha256).toBeNull();
  });

  it('拒绝非法哈希、数量和不完整身份', () => {
    expect(() => validateStickerCandidateQuery({
      platform: 'wechat',
      account_key: 'account',
      external_key: 'peer',
      incoming_asset_sha256: 'BAD',
    })).toThrow('incoming_asset_sha256');
    expect(() => validateStickerCandidateQuery({
      platform: 'wechat',
      account_key: 'account',
      external_key: 'peer',
      limit: 21,
    })).toThrow('limit');
    expect(() => validateStickerCandidateQuery({
      platform: 'wechat',
      account_key: 'account',
    })).toThrow('external_key');
  });
});

describe('validateRelationshipCandidateQuery', () => {
  it('规范会话身份并为候选数量提供默认值', () => {
    expect(validateRelationshipCandidateQuery({
      platform: 'wechat',
      account_key: ' account ',
      external_key: ' peer ',
      context_text: '  在吗  ',
    })).toEqual({
      identity: {
        platform: 'wechat',
        account_key: 'account',
        external_key: 'peer',
      },
      contextText: '  在吗  ',
      limit: 6,
    });
  });

  it('拒绝不完整身份和非法候选数量', () => {
    expect(() => validateRelationshipCandidateQuery({
      platform: 'wechat',
      account_key: 'account',
    })).toThrow('external_key');
    expect(() => validateRelationshipCandidateQuery({
      platform: 'wechat',
      account_key: 'account',
      external_key: 'peer',
      limit: 21,
    })).toThrow('limit');
  });
});
