import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import sharp from 'sharp';
import { describe, expect, it } from 'vitest';

import { auditPrototypeGif } from './prototypeAudit.js';
import type { PrototypeManifest, PrototypeManifestItem } from './prototypeManifest.js';
import {
  buildPrototypeReport,
  verifyPrototypeReport,
} from './prototypeReport.js';

const TRANSPARENT = { r: 0, g: 0, b: 0, alpha: 0 } as const;

function item(): PrototypeManifestItem {
  return {
    id: 'report-sample',
    keyword: '谢谢',
    text: '谢谢',
    style: 'original-character',
    direction: 'core-performance',
    sourceType: 'ai-original',
    prompt: '完全原创，原创，无文字，无水印，无品牌，无现有角色',
    motionPreset: 'bow',
    frameCount: 10,
    durationMs: 1_000,
    masterFile: 'masters/report-sample.png',
    poseFiles: Array.from({ length: 4 }, (_, index) => (
      `poses/report-sample/pose-0${index + 1}.png`
    )),
    textPlacement: 'bottom',
  };
}

async function realGif(): Promise<Buffer> {
  const offsets = [0, 2, 4, 6, 8, 10, 8, 6, 2, 0];
  const frames = await Promise.all(offsets.map((offset) => sharp({
    create: { width: 240, height: 240, channels: 4, background: TRANSPARENT },
  }).composite([{
    input: Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="240" height="240">`
      + `<circle cx="${80 + offset}" cy="80" r="42" fill="#ff6038"/></svg>`),
    left: 0,
    top: 0,
  }]).png().toBuffer()));
  return sharp({
    create: { width: 240, height: 2_400, pageHeight: 240, channels: 4, background: TRANSPARENT },
  }).composite(frames.map((input, index) => ({ input, left: 0, top: index * 240 })))
    .gif({ loop: 0, delay: Array.from({ length: 10 }, () => 100), keepDuplicateFrames: true })
    .toBuffer();
}

async function fixture(): Promise<{
  root: string;
  gif: Buffer;
  manifest: PrototypeManifest;
  report: ReturnType<typeof buildPrototypeReport>;
}> {
  const root = await mkdtemp(join(tmpdir(), 'prototype-report-'));
  const gifsRoot = join(root, 'gifs');
  await mkdir(gifsRoot);
  const gif = await realGif();
  const manifest = { version: '2026.09.04.1', items: [item()] };
  await writeFile(join(gifsRoot, 'report-sample.gif'), gif);
  const audit = await auditPrototypeGif(gif, manifest.items[0]);
  return { root, gif, manifest, report: buildPrototypeReport(manifest, [audit]) };
}

describe('prototypeReport', () => {
  it('从真实 GIF 审计结果构建报告并逐字段重算验证', async () => {
    const value = await fixture();
    try {
      expect(value.report.items[0]).toMatchObject({
        format: 'gif', width: 240, height: 240, pageHeight: 240, loop: 0,
      });
      expect(value.report.items[0].motion.uniqueFrameCount).toBeGreaterThanOrEqual(4);
      expect(value.report.items[0].loopClosure.passed).toBe(true);

      const verification = await verifyPrototypeReport({
        report: value.report,
        manifest: value.manifest,
        gifsRoot: join(value.root, 'gifs'),
      });
      expect(verification).toEqual({ valid: true, issues: [] });
    } finally {
      await rm(value.root, { recursive: true, force: true });
    }
  });

  it('报告构建后 GIF 被篡改时报告 SHA 一致性验证失败', async () => {
    const value = await fixture();
    try {
      await writeFile(join(value.root, 'gifs/report-sample.gif'), Buffer.concat([
        value.gif,
        Buffer.from('tampered'),
      ]));

      const verification = await verifyPrototypeReport({
        report: value.report,
        manifest: value.manifest,
        gifsRoot: join(value.root, 'gifs'),
      });
      expect(verification.valid).toBe(false);
      expect(verification.issues).toContainEqual(expect.objectContaining({
        field: 'items[report-sample].sha256',
      }));
    } finally {
      await rm(value.root, { recursive: true, force: true });
    }
  });

  it('缺字段或陈旧版本的报告都验证失败', async () => {
    const value = await fixture();
    try {
      const missingField = structuredClone(value.report) as unknown as Record<string, unknown> & {
        items: Array<Record<string, unknown>>;
      };
      delete missingField.items[0].width;
      const missingVerification = await verifyPrototypeReport({
        report: missingField,
        manifest: value.manifest,
        gifsRoot: join(value.root, 'gifs'),
      });
      expect(missingVerification.valid).toBe(false);
      expect(missingVerification.issues).toContainEqual(expect.objectContaining({
        field: 'items[report-sample].width',
      }));

      const stale = structuredClone(value.report);
      stale.version = '2026.09.03.9';
      const staleVerification = await verifyPrototypeReport({
        report: stale,
        manifest: value.manifest,
        gifsRoot: join(value.root, 'gifs'),
      });
      expect(staleVerification.valid).toBe(false);
      expect(staleVerification.issues).toContainEqual(expect.objectContaining({ field: 'version' }));
    } finally {
      await rm(value.root, { recursive: true, force: true });
    }
  });
});
