import { access, mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import sharp from 'sharp';
import { describe, expect, it } from 'vitest';

import type { PrototypeManifest } from './prototypeManifest.js';
import {
  preflightPrototypePoses,
  publishDirectoryAtomically,
} from './prototypePublication.js';

function manifest(): PrototypeManifest {
  return {
    version: 'test',
    items: [{
      id: 'publication-sample',
      keyword: '谢谢',
      text: '谢谢',
      style: 'original-character',
      direction: 'core-performance',
      sourceType: 'ai-original',
      prompt: '完全原创，原创，无文字，无水印，无品牌，无现有角色',
      motionPreset: 'bow',
      frameCount: 10,
      durationMs: 1_000,
      masterFile: 'masters/publication-sample.png',
      poseFiles: Array.from({ length: 4 }, (_, index) => (
        `poses/publication-sample/pose-0${index + 1}.png`
      )),
      textPlacement: 'bottom',
    }],
  };
}

async function transparentPng(): Promise<Buffer> {
  return sharp({
    create: { width: 32, height: 32, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } },
  }).composite([{
    input: Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32"><circle cx="16" cy="16" r="8" fill="red"/></svg>'),
    left: 0,
    top: 0,
  }]).png().toBuffer();
}

describe('preflightPrototypePoses', () => {
  it('在生成前一次性确认所有姿势是可读的透明 PNG', async () => {
    const root = await mkdtemp(join(tmpdir(), 'prototype-preflight-'));
    try {
      const value = manifest();
      const png = await transparentPng();
      for (const poseFile of value.items[0].poseFiles) {
        const path = join(root, poseFile);
        await mkdir(join(path, '..'), { recursive: true });
        await writeFile(path, png);
      }

      const result = await preflightPrototypePoses(value, root);

      expect(result.get('publication-sample')).toEqual(
        value.items[0].poseFiles.map((poseFile) => join(root, poseFile)),
      );
    } finally {
      await rm(root, { recursive: true, force: true });
    }
  });

  it('任一源文件缺失或没有有效 alpha 时预检失败', async () => {
    const root = await mkdtemp(join(tmpdir(), 'prototype-preflight-bad-'));
    try {
      const value = manifest();
      const opaque = await sharp({
        create: { width: 32, height: 32, channels: 3, background: '#f00' },
      }).png().toBuffer();
      const first = join(root, value.items[0].poseFiles[0]);
      await mkdir(join(first, '..'), { recursive: true });
      await writeFile(first, opaque);

      await expect(preflightPrototypePoses(value, root))
        .rejects.toThrow(/publication-sample.*pose-01\.png.*alpha/);
    } finally {
      await rm(root, { recursive: true, force: true });
    }
  });
});

describe('publishDirectoryAtomically', () => {
  it('构建失败时保留旧完整目录并清理临时目录', async () => {
    const parent = await mkdtemp(join(tmpdir(), 'prototype-publish-fail-'));
    const outputRoot = join(parent, 'expression-prototypes');
    await mkdir(outputRoot);
    await writeFile(join(outputRoot, 'old.txt'), 'old-complete');
    try {
      await expect(publishDirectoryAtomically(outputRoot, async (temporaryRoot) => {
        await writeFile(join(temporaryRoot, 'new.txt'), 'partial-new');
        throw new Error('模拟渲染失败');
      })).rejects.toThrow(/模拟渲染失败/);

      expect(await readFile(join(outputRoot, 'old.txt'), 'utf8')).toBe('old-complete');
      await expect(access(join(outputRoot, 'new.txt'))).rejects.toThrow();
      expect((await readdir(parent)).filter((name) => name.includes('.tmp-'))).toEqual([]);
    } finally {
      await rm(parent, { recursive: true, force: true });
    }
  });

  it('构建成功时以新完整目录替换旧目录', async () => {
    const parent = await mkdtemp(join(tmpdir(), 'prototype-publish-ok-'));
    const outputRoot = join(parent, 'expression-prototypes');
    await mkdir(outputRoot);
    await writeFile(join(outputRoot, 'old.txt'), 'old-complete');
    try {
      await publishDirectoryAtomically(outputRoot, async (temporaryRoot) => {
        await mkdir(join(temporaryRoot, 'gifs'));
        await writeFile(join(temporaryRoot, 'gifs/new.gif'), 'new-complete');
      });

      expect(await readFile(join(outputRoot, 'gifs/new.gif'), 'utf8')).toBe('new-complete');
      await expect(access(join(outputRoot, 'old.txt'))).rejects.toThrow();
      expect((await readdir(parent)).filter((name) => (
        name.includes('.tmp-') || name.includes('.backup-')
      ))).toEqual([]);
    } finally {
      await rm(parent, { recursive: true, force: true });
    }
  });
});
