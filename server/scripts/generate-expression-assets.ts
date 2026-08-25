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
const duplicateKeys = catalog.emojiCombinations.length
  - new Set(catalog.emojiCombinations.map((item) => item.key)).size;
console.log([
  `${catalog.templates.length} templates`,
  `${animated} GIF`,
  `${catalog.templates.length - animated} static`,
  `${catalog.emojiBases.length} bases`,
  `${catalog.emojiCombinations.length} ordered WebP combinations`,
  `${duplicateKeys} duplicate keys`,
  `${missingFiles} missing files`,
].join(', '));
if (
  catalog.templates.length !== 60
  || animated !== 20
  || catalog.emojiBases.length !== 48
  || catalog.emojiCombinations.length !== 2304
  || duplicateKeys !== 0
  || missingFiles !== 0
) {
  process.exitCode = 1;
}
