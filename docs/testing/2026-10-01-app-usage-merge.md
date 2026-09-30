# 应用使用短间隔合并验证（2026-10-01）

## 交付行为

- 用户明确选定相邻间隔1分钟以内合并；同设备相邻同App、间隔0–60000毫秒合并展示，传回segment_count并在底部标注“短间隔合并 · N段”。首尾显示原始片段范围，“范围内时长”是各片段在查询范围内的交集之和，中间空档不计时。
- 先在全部App原始记录中判断邻接与重叠，再按包名过滤、合并并分页。其他App（包括隐藏的输入法自身记录）、gap、锁屏/熄屏/重启等明确结束原因阻断；前后两段都须不与别的记录重叠。
- 原始表、手机上报、统计总时长/次数、日趋势、当天时间轴不变。明细total改为合并后的展示行数，上方次数仍为原始已结束段数，页面明确说明。
- 历史没有精确桌面桥接字段，且≤3秒App段可能未上报，不能证明空白只在桌面；已向用户说明采用短间隔展示合并，不伪造桌面证据。

## 验证结果

- TDD：新增样例四段合并、阈值及先合并后分页测试，在旧实现分别观察4→期望1、4→期望3、102→期望51的断言失败。初次分页夹具越过当前时间，改为过去日期并断言上传200后重跑，排除夹具错误。
- 最终隔离真实PostgreSQL集成18项全部通过，无跳过。集群 `/tmp/shurufa-app-usage.wM1PVhaD`，专用 app_usage_test / socket / data_directory 校验，测试后实例已停止；从未对生产库运行测试。
- 覆盖：示例四段、60秒包含/超出排除、跨日裁剪、其他App/隐藏自身App、gap及9种明确结束原因、各种重叠、合并后分页、设备隔离、汇总/时间轴/原始表保留，以及既有上传校验/幂等/清理与保存开关。
- 独立审查发现较早长段侵入间隙的边界，新增测试确认旧实现失败，再扩展为每段前后重叠检查；补充长段同刻结束的lock和后续其他App重叠用例。复核无新阻断。
- client/tests/app-usage.test.ts 10项通过，覆盖局部分页与设备/筛选竞态。前后端生产构建通过；client既有>500kB分块提醒保留。git diff --check通过。
- 本机真实PostgreSQL + 开发API + Playwright浏览器：54个合成原始段显示51行，四段微信在第2页合为1行，显示3分48秒；验证统计DOM未重建、翻页没有重查当天时间轴、原始54段未变、0 pageerror。测试设备和数据已清理；截图 `artifacts/diagnostics/2026-10-01-app-usage-merge/merged-page2.png` 仅本地。
- 本地开发库缺少已有 app_usage_segment：确认127.0.0.1 / personal_ime后执行 `server/migrations/035_app_usage.sql` 并核验表。此轮无新增表结构/迁移文件；仅恢复本机已有迁移，不操作线上。

## 验证命令

```bash
PG_BINDIR=/opt/homebrew/opt/postgresql@14/bin bash .runtime/test-app-usage-merge.sh
./server/node_modules/.bin/vitest run client/tests/app-usage.test.ts
(cd server && npm run build)
(cd client && npm run build)
source scripts/lib/env.sh
load_dotenv .env.local
node artifacts/diagnostics/2026-10-01-app-usage-merge/browser.mjs
git diff --check
```

## 发布边界

- 尚未部署线上、未提交/推送Git。本功能发布后台后对已有数据生效，不需要手机升级；原始记录没有物理合并或删除。
- 按AGENTS构建原签名APK留档到Mac本机apk/，不自动安装；这轮仅为后台功能，不把APK安装当作后台发布。

## APK留档

- `/Users/pj/project/shurufa/apk/shurufa-2026-10-01-v20261001.00-2026100100-debug-d5a10c51.apk`。versionName `20261001.00`，versionCode `2026100100`，包名 `com.yuyan.pinyin.offline.debug`，非testOnly。
- SHA256：`d5a10c513a5165b1d90e7af91c39aa765a900eca53d7d2ec2b9af9e756780b78`；交付文件哈希已复核。API23/27/28/32/36原签名验证通过，未安装或作本轮手机端到端验收。
- 本机Mac，无需Windows E盘交付，构建脚本旧提示不适用。
