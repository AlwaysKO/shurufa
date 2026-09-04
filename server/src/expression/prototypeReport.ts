import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { isDeepStrictEqual } from 'node:util';

import {
  type PrototypeAuditIssue,
  type PrototypeGifAuditResult,
  type PrototypeGifAuditMetadata,
  auditPrototypeGif,
} from './prototypeAudit.js';
import type { PrototypeManifest } from './prototypeManifest.js';

export interface PrototypeReportItem {
  id: string;
  keyword: string;
  style: string;
  direction: string;
  format: string | null;
  width: number | null;
  height: number | null;
  pageHeight: number | null;
  loop: number | null;
  bytes: number;
  frames: number;
  durationMs: number;
  sha256: string;
  motion: PrototypeGifAuditMetadata['motion'];
  loopClosure: PrototypeGifAuditMetadata['loopClosure'];
  issues: PrototypeAuditIssue[];
}

export interface PrototypeReport {
  version: string;
  generatedAt: string;
  total: number;
  pass: number;
  fail: number;
  items: PrototypeReportItem[];
}

export interface PrototypeReportVerificationIssue {
  field: string;
  expected?: unknown;
  actual?: unknown;
  message: string;
}

export interface PrototypeReportVerification {
  valid: boolean;
  issues: PrototypeReportVerificationIssue[];
}

export function generatedAtFromVersion(version: string): string {
  const match = /^(\d{4})\.(\d{2})\.(\d{2})/.exec(version);
  return match === null
    ? '1970-01-01T00:00:00.000Z'
    : `${match[1]}-${match[2]}-${match[3]}T00:00:00.000Z`;
}

/** 只从已审计的 GIF 结果构建确定性报告。 */
export function buildPrototypeReport(
  manifest: PrototypeManifest,
  audits: PrototypeGifAuditResult[],
): PrototypeReport {
  const auditById = new Map(audits.map((audit) => [audit.id, audit]));
  if (auditById.size !== audits.length) throw new Error('审计结果 ID 必须唯一');
  const items = manifest.items.map((item): PrototypeReportItem => {
    const audit = auditById.get(item.id);
    if (audit === undefined) throw new Error(`缺少样板 ${item.id} 的审计结果`);
    return {
      id: item.id,
      keyword: item.keyword,
      style: item.style,
      direction: item.direction,
      format: audit.metadata.format,
      width: audit.metadata.width,
      height: audit.metadata.height,
      pageHeight: audit.metadata.pageHeight,
      loop: audit.metadata.loop,
      bytes: audit.metadata.bytes,
      frames: audit.metadata.pages,
      durationMs: audit.metadata.durationMs,
      sha256: audit.metadata.sha256,
      motion: audit.metadata.motion,
      loopClosure: audit.metadata.loopClosure,
      issues: audit.issues,
    };
  });
  if (auditById.size !== items.length) throw new Error('审计结果包含清单外样板');
  const fail = items.filter(({ issues }) => issues.length > 0).length;
  return {
    version: manifest.version,
    generatedAt: generatedAtFromVersion(manifest.version),
    total: items.length,
    pass: items.length - fail,
    fail,
    items,
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function compareField(
  issues: PrototypeReportVerificationIssue[],
  field: string,
  expected: unknown,
  actual: unknown,
): void {
  if (isDeepStrictEqual(expected, actual)) return;
  issues.push({ field, expected, actual, message: `${field} 与实际 GIF 不一致` });
}

/** 重新读取和审计磁盘 GIF，防止陈旧、缺字段或被篡改的报告发布。 */
export async function verifyPrototypeReport(options: {
  report: unknown;
  manifest: PrototypeManifest;
  gifsRoot: string;
}): Promise<PrototypeReportVerification> {
  const { report, manifest, gifsRoot } = options;
  const issues: PrototypeReportVerificationIssue[] = [];
  const audits: PrototypeGifAuditResult[] = [];
  for (const item of manifest.items) {
    try {
      const gif = await readFile(join(gifsRoot, `${item.id}.gif`));
      audits.push(await auditPrototypeGif(gif, item));
    } catch (error) {
      issues.push({
        field: `gifs[${item.id}]`,
        message: `无法重新审计 GIF：${error instanceof Error ? error.message : String(error)}`,
      });
    }
  }
  if (audits.length !== manifest.items.length) return { valid: false, issues };

  const expected = buildPrototypeReport(manifest, audits);
  if (!isRecord(report)) {
    issues.push({ field: 'report', expected: '对象', actual: report, message: '报告必须是对象' });
    return { valid: false, issues };
  }
  for (const field of ['version', 'generatedAt', 'total', 'pass', 'fail'] as const) {
    compareField(issues, field, expected[field], report[field]);
  }
  if (!Array.isArray(report.items)) {
    issues.push({ field: 'items', expected: '数组', actual: report.items, message: '报告 items 必须是数组' });
    return { valid: false, issues };
  }

  const actualItems = new Map<string, Record<string, unknown>>();
  for (const value of report.items) {
    if (!isRecord(value) || typeof value.id !== 'string') {
      issues.push({ field: 'items', message: '报告项必须有字符串 ID' });
      continue;
    }
    if (actualItems.has(value.id)) {
      issues.push({ field: `items[${value.id}].id`, message: '报告项 ID 重复' });
      continue;
    }
    actualItems.set(value.id, value);
  }
  const comparedFields: Array<keyof PrototypeReportItem> = [
    'id', 'keyword', 'style', 'direction', 'format', 'width', 'height', 'pageHeight',
    'loop', 'bytes', 'frames', 'durationMs', 'sha256', 'motion', 'loopClosure', 'issues',
  ];
  for (const expectedItem of expected.items) {
    const actualItem = actualItems.get(expectedItem.id);
    if (actualItem === undefined) {
      issues.push({ field: `items[${expectedItem.id}]`, message: '报告缺少样板项' });
      continue;
    }
    for (const field of comparedFields) {
      compareField(
        issues,
        `items[${expectedItem.id}].${field}`,
        expectedItem[field],
        actualItem[field],
      );
    }
  }
  for (const id of actualItems.keys()) {
    if (!expected.items.some((item) => item.id === id)) {
      issues.push({ field: `items[${id}]`, message: '报告含有清单外样板' });
    }
  }
  return { valid: issues.length === 0, issues };
}
