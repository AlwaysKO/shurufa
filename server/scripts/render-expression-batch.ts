import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { parseArgs } from 'node:util';
import { renderExpressionBatch, resolveExpressionBatchPaths } from '../src/expression/expressionBatch.js';

async function main() {
  const { values } = parseArgs({ options: { ids: { type: 'string' }, batch: { type: 'string' } }, strict: true });
  const root = fileURLToPath(new URL('../../', import.meta.url));
  const paths = resolveExpressionBatchPaths(root, values.batch);
  const result = await renderExpressionBatch({
    manifest: JSON.parse(await readFile(join(paths.sourceRoot, 'manifest.json'), 'utf8')),
    ...paths,
    ...(values.ids === undefined ? {} : { ids: values.ids.split(',').map(id => id.trim()) }),
  });
  console.log(`机器审计 ${result.report.pass}/${result.report.total}；批次预期 ${result.report.expectedTotal}；人审待确认。输出：${result.outputRoot}`);
}
main().catch((error: unknown) => { console.error(error); process.exitCode = 1; });
