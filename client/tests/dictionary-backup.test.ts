import { readFileSync } from 'node:fs';
import { expect, it } from '../../server/node_modules/vitest/dist/index.js';
const read=(path:string)=>readFileSync(new URL('../../android/YuyanIme/'+path,import.meta.url),'utf8');
it('系统换机备份不能复制个人词库来源数据库和同步凭据，迁移状态必须重新检测',()=>{
  const manifest=read('app/src/main/AndroidManifest.xml');
  expect(manifest).toContain('android:fullBackupContent="@xml/personal_data_backup_rules"');
  expect(manifest).toContain('android:dataExtractionRules="@xml/personal_data_extraction_rules"');
  for(const name of ['personal_data_backup_rules','personal_data_extraction_rules']) {
    const xml=read(`app/src/main/res/xml/${name}.xml`);
    for(const path of ['local_input.db','personal_dictionary_sync_v1.xml','system_dictionary_migration_v1.xml']) expect(xml).toContain(`path="${path}"`);
    if(name.endsWith('extraction_rules')) {expect(xml).toContain('<cloud-backup>');expect(xml).toContain('<device-transfer>');}
  }
});
