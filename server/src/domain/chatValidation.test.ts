import { describe, expect, it } from 'vitest';
import {
  validateCapturedConversation,
  validateCapturedMessage,
} from './chatValidation.js';

const validConversation = {
  platform: 'wechat',
  account_key: 'local-account',
  external_key: 'direct:peer',
  display_name: '对方',
  conversation_type: 'direct',
  identity_confidence: 0.95,
};

const validMessage = {
  id: crypto.randomUUID(),
  fingerprint: 'a'.repeat(64),
  content_fingerprint: 'b'.repeat(64),
  sender_key: 'peer',
  direction: 'incoming',
  message_type: 'text',
  text: '你好',
  captured_at: new Date().toISOString(),
};

describe('validateCapturedConversation', () => {
  it('接受合法会话', () => {
    expect(validateCapturedConversation(validConversation)).toEqual(validConversation);
  });

  it('拒绝未知平台', () => {
    expect(() => validateCapturedConversation({
      ...validConversation,
      platform: 'unknown-app',
    })).toThrow('platform');
  });

  it('拒绝空会话键', () => {
    expect(() => validateCapturedConversation({
      ...validConversation,
      external_key: '   ',
    })).toThrow('external_key');
  });
});

describe('validateCapturedMessage', () => {
  it('接受合法消息', () => {
    expect(validateCapturedMessage(validMessage)).toEqual(validMessage);
  });

  it('拒绝未知消息类型', () => {
    expect(() => validateCapturedMessage({
      ...validMessage,
      message_type: 'unsupported',
    })).toThrow('message_type');
  });

  it('拒绝非 64 位小写十六进制消息指纹', () => {
    expect(() => validateCapturedMessage({
      ...validMessage,
      fingerprint: 'BAD',
    })).toThrow('fingerprint');
  });

  it('拒绝超过 20000 字符的文本', () => {
    expect(() => validateCapturedMessage({
      ...validMessage,
      text: 'x'.repeat(20_001),
    })).toThrow('text');
  });
});
