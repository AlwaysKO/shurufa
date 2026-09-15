import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { EXPRESSION_CATALOG_VERSION } from './catalogVersion.js';

describe('expression catalog version', () => {
  it('与源清单版本保持一致', async () => {
    const manifest = JSON.parse(await readFile(
      resolve(import.meta.dirname, '..', '..', '..', 'assets', 'expression', 'manifest.source.json'),
      'utf8',
    )) as { version: string };

    expect(EXPRESSION_CATALOG_VERSION).toBe(manifest.version);
  });
});
