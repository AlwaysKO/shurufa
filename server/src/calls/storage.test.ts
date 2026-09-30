import { describe, expect, it } from 'vitest';
import { randomBytes } from 'node:crypto';
import { encryptAudio, decryptAudio } from './storage.js';

describe('私有录音加密', () => {
  const key = randomBytes(32), audio = Buffer.from('synthetic audio fixture');
  it('密文不包含原文，只有同密钥同设备同记录可解密', () => {
    const encrypted = encryptAudio(audio, key, 'device:record');
    expect(encrypted.includes(audio)).toBe(false);
    expect(decryptAudio(encrypted, key, 'device:record')).toEqual(audio);
    expect(() => decryptAudio(encrypted, key, 'other:record')).toThrow();
    expect(() => decryptAudio(encrypted, randomBytes(32), 'device:record')).toThrow();
  });
  it('篡改和截断均不能播放', () => {
    const encrypted = encryptAudio(audio, key, 'id');
    encrypted[encrypted.length - 1] ^= 1;
    expect(() => decryptAudio(encrypted, key, 'id')).toThrow();
    expect(() => decryptAudio(encrypted.subarray(0, 5), key, 'id')).toThrow();
  });
});
