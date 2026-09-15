import {fileURLToPath} from 'node:url';
import {readFile,mkdtemp,cp,writeFile,rm,readdir} from 'node:fs/promises';
import {join} from 'node:path';import {tmpdir} from 'node:os';import {spawnSync} from 'node:child_process';import assert from 'node:assert/strict';
const repo=fileURLToPath(new URL('../../../',import.meta.url)).replace(/\/$/,''),base=repo+'/artifacts/expression-character-trials/original-volume-09-animated';
const src=await readFile(base+'/render-audited-subset.mts','utf8');
const fixture=await mkdtemp(join(tmpdir(),'v9-subset-'));
try{
 await cp(base+'/masters',fixture+'/masters',{recursive:true});
 const script=src.replace("const root=fileURLToPath(new URL('./',import.meta.url));",'const root='+JSON.stringify(fixture)+';').replaceAll('../../../server/',repo+'/server/');
 await writeFile(fixture+'/runner.mts',script);
 const run=()=>spawnSync(process.execPath,['--import',repo+'/server/node_modules/tsx/dist/loader.mjs',fixture+'/runner.mts'],{encoding:'utf8'});
 let r=run();assert.equal(r.status,0,r.stderr);
 let report=JSON.parse(await readFile(fixture+'/output/report.json','utf8'));
 assert.equal(report.pass,11);assert.equal(report.fail,1);assert.equal(report.failed[0].id,'duck-lost');assert.equal(report.publicationAllowed,false);assert.equal(report.staticCharacterReview,'pending');assert.equal(report.humanAnimationReview,'pending');
 assert.equal((await readdir(fixture+'/output/gifs')).length,11);
 const before=await readFile(fixture+'/output/report.json');
 await writeFile(fixture+'/masters/beagle-restrain.png','corrupt');
 r=run();assert.notEqual(r.status,0);assert.deepEqual(await readFile(fixture+'/output/report.json'),before);
 await cp(base+'/masters/beagle-restrain.png',fixture+'/masters/beagle-restrain.png');
 await rm(fixture+'/masters/duck-lost.png');r=run();assert.notEqual(r.status,0);assert.deepEqual(await readFile(fixture+'/output/report.json'),before);
 console.log('subset integration: 11+1 correct, unrelated corrupt/missing expected source abort without replacing prior output');
}finally{await rm(fixture,{recursive:true,force:true})}
