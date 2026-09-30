import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto';
import { readFile, stat } from 'node:fs/promises';

/** 私有密钥只从运维指定的文件读取；缺配置绝不降级明文。 */
export async function loadAudioKey(): Promise<Buffer> {
  const path = process.env.CALL_RECORDING_KEY_FILE;
  if (!path) throw new Error('call_audio_key_unavailable');
  const info = await stat(path);
  if (!info.isFile() || info.size !== 32 || (info.mode & 0o077) !== 0) throw new Error('call_audio_key_unavailable');
  const key = await readFile(path);
  if (key.length !== 32) throw new Error('call_audio_key_unavailable');
  return key;
}
export function encryptAudio(audio: Buffer, key: Buffer, identity: string): Buffer {
  const nonce = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', key, nonce);
  cipher.setAAD(Buffer.from(identity));
  const encrypted = Buffer.concat([cipher.update(audio), cipher.final()]);
  return Buffer.concat([nonce, cipher.getAuthTag(), encrypted]);
}
export function decryptAudio(encrypted: Buffer, key: Buffer, identity: string): Buffer {
  if (encrypted.length < 29) throw new Error('invalid_audio');
  const cipher = createDecipheriv('aes-256-gcm', key, encrypted.subarray(0, 12));
  cipher.setAAD(Buffer.from(identity));
  cipher.setAuthTag(encrypted.subarray(12, 28));
  return Buffer.concat([cipher.update(encrypted.subarray(28)), cipher.final()]);
}
