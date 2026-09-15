import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { generateExpressionAssets } from '../src/expression/assetGenerator.js';

const projectRoot = resolve(import.meta.dirname, '..', '..');
const sourceRoot = resolve(projectRoot, 'assets', 'expression');
const outputRoot = resolve(projectRoot, 'server', '.runtime', 'expression-assets');
const androidAssetsRoot = resolve(
  projectRoot,
  'android',
  'YuyanIme',
  'yuyansdk',
  'src',
  'main',
  'assets',
  'expression',
);
const manifest = JSON.parse(await readFile(resolve(sourceRoot, 'manifest.source.json'), 'utf8'));
const expected = manifest.expectedCounts;
const independentCount = (manifest.prebuiltAssets ?? []).length;
const expectedPrebuilt = (manifest.prebuiltPhrases ?? []).reduce(
  (count: number, phrase: { templateIds: string[] }) => count + phrase.templateIds.length, 0,
) + independentCount;
const catalog = await generateExpressionAssets({
  manifestPath: resolve(sourceRoot, 'manifest.source.json'),
  sourceRoot,
  outputRoot,
  androidAssetsRoot,
});

const files = [
  ...catalog.templates,
  ...catalog.emojiBases,
  ...catalog.emojiCombinations,
];
let missingFiles = 0;
for (const item of files) {
  try {
    const digest = createHash('sha256')
      .update(await readFile(resolve(outputRoot, item.fileName)))
      .digest('hex');
    if (digest !== item.sha256) missingFiles += 1;
  } catch {
    missingFiles += 1;
  }
}
const animated = catalog.templates.filter((item) => item.format === 'gif').length;
const prebuilt = catalog.templates.filter((item) => item.type === 'prebuilt').length;
const synthesis = catalog.templates.filter((item) => item.type === 'synthesis-template').length;
const duplicateKeys = catalog.emojiCombinations.length
  - new Set(catalog.emojiCombinations.map((item) => item.key)).size;
console.log([
  `${catalog.templates.length} templates`,
  `${prebuilt} prebuilt`,
  `${synthesis} synthesis`,
  `${animated} GIF`,
  `${catalog.templates.length - animated} static`,
  `${catalog.emojiBases.length} bases`,
  `${catalog.emojiCombinations.length} ordered WebP combinations`,
  `${duplicateKeys} duplicate keys`,
  `${missingFiles} missing files`,
].join(', '));
if (
  prebuilt !== expectedPrebuilt
  || synthesis !== expected.templates
  || animated !== expected.animatedTemplates + independentCount
  || catalog.emojiBases.length !== expected.emojiBases
  || catalog.emojiCombinations.length !== expected.emojiBases ** 2
  || duplicateKeys !== 0
  || missingFiles !== 0
) {
  process.exitCode = 1;
}
