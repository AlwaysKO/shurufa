# 自动部署仅响应后台输入变化

**Goal:** 仅Android、文档或诊断变化时，不构建、备份、迁移、切换或重启后台；后台尚未发布的改动不能被后续Android提交掩盖。

**Architecture:** 比较当前发布目录REVISION与origin/main两端实际内容。server、client、assets及已批准清单引用的外部GIF属于发布输入。部署脚本本身保存在独立运行目录，修改需单独安装；不靠提交消息、最后一次提交或更新REVISION假装部署。首次部署与显式--force保留；比较失败中止，不能伪装无变化。

**Tech Stack:** Bash、Python标准库、真实临时Git仓库、SSH。

1. 依据systematic-debugging检查源码和线上实际脚本；已确认现行脚本只比较SHA，线上另有图库保护开关，必须保留。
2. 按TDD用隔离Git仓库验证Android/文档跳过，server/client/素材/删除重命名/跨多提交变化正常发布，错误与force边界。
3. 新增输入比较工具并接到deploy.sh创建release之前；按writing-plans/executing-plans完成本计划，当前分支开发，不提交、推送其他改动。
4. 更新说明及AGENTS长期规则，执行定向测试与bash语法校验、代码审查。
5. 在部署锁保护下备份并原子更新线上比较工具和脚本中的同一判断块，保留图库开关及其余运维行为；不触发后台发布。核对线上脚本哈希、服务PID/启动时间及REVISION未变，观察定时任务。

与只按Android路径排除相比，使用发布输入名单能避免文档变化误触发；与只比较上一提交相比，当前线上REVISION不会漏掉积压的后台变更。测试不连接业务数据库，不实际构建发布。

## 验证与生效记录

- 先加入输入比较工具后，旧deploy.sh仍有2项失败：Android修改继续走发布、无效基线未中止。加入guard后14项全部通过；原有GIF staging 3项测试通过，bash语法及diff空白检查通过。
- 独立审查通过，随后使用同一deploy.lock互斥安装；只插入guard，线上其余字节保持原样，包括图库opt-in。备份：`/home/ubuntu/shurufa-deploy/deploy.sh.before-input-guard-20260930T200217099333`。
- 实际运行脚本SHA256：`7ddd749f1e0e886c7963afe179623779469a4d157f9c2654124347e106b236ca`；helper本地/线上一致：`4ae764d6925b6f84042429b4932a38cc07d57c8a20402a6936cadf83868b5fc6`。
- 在线上临时目录复制实际已安装脚本和helper，执行同一套14项真实隔离Git测试全部通过；仅运行部署前缀，fetch/锁替换为隔离夹具，不触发构建、数据库或服务操作，临时目录自动清理。
- 安装前后应用PID保持3461774，启动时间保持2026-09-30 17:07:21 UTC，REVISION保持`e800ea997d84ffcd3ddce193feb5d8d89db4c95e`；timer保持active。未提交或推送混合工作区。
- 当前工作区上一轮还有`client/src/views/NavigationRecords.vue`变更，若一起提交属于后台变化，应正常发布，不能把这种混合提交称为仅Android。
- 按项目交付规则重新打包：`apk/shurufa-2026-10-01-v20261001.04-2026100104-debug-304ec5ab.apk`，SHA256 `304ec5aba9dde45bb96c2f00092773c194ccd2423f29a993f7065de66009d80d`；原签名API23/27/28/32/36验证通过、非testOnly。部署筛选在服务器执行，不需要手机安装才能生效；新APK未安装或新增真机验收。
